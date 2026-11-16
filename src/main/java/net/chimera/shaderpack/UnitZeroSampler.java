package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Renames samplers Iris does not register to what texture unit 0 holds, since GL leaves them on unit 0.
 *
 * In every fullscreen pass (begin, prepare, deferred, composite and final) Iris binds colortex0 to unit 0 as
 * the default sampler ({@code IrisSamplers.addRenderTargetSamplers}). In a gbuffers or shadow program unit 0
 * holds the albedo texture ({@code IrisSamplers.addLevelSamplers}). A draw without texture coordinates
 * registers Iris's white pixel under tex, texture, gtexture, gcolor and colortex0 on a unit of its own; there
 * an unregistered name reads whatever unit 0 last held, and Chimera's white albedo
 * (HostLayoutLane.whiteAlbedo) stands in for it.
 *
 * So a {@code sampler2D} Iris does not know reads that texture. I Like Vanilla's composites read their scene
 * through {@code tex} and declare no colortex0; Kappa's particles read gcolor, which a gbuffers program never
 * registers. Renaming before planning gives every later step that meaning. A sampler of another type clashes
 * with the unit's sampler2D and fails Iris's draw, so it stays unmapped, as does a sampler whose rename a
 * local or parameter of the target name would capture.
 */
final class UnitZeroSampler {
    /** What unit 0 holds in a renderer, and the deviation that names a sampler read through it. */
    enum Unit {
        /** A fullscreen pass: colortex0. */
        FULLSCREEN("colortex0", "SAMPLER_UNIT0_COLORTEX0:"),
        /** A gbuffers or shadow program: the draw's albedo texture. */
        WORLD("gtexture", "SAMPLER_UNIT0_ALBEDO:");

        private final String texture;
        private final String deviation;

        Unit(String texture, String deviation) {
            this.texture = texture;
            this.deviation = deviation;
        }

        /** What unit 0 holds in {@code program}'s renderer. */
        static Unit of(String program) {
            return IrisSamplerOrder.of(program, false) == IrisSamplerOrder.WORLD ? WORLD : FULLSCREEN;
        }
    }

    private UnitZeroSampler() {}

    /**
     * {@code source} of {@code program} with each sampler2D that neither Iris nor the pack serves read
     * as what the program's unit 0 holds. A source the tree cannot parse stays as written; its
     * conversion reports the failure.
     */
    static String apply(String source, PackConfig.PackConfigData config, List<String> deviations, String program) {
        if (source == null) return null;
        Unit unit = Unit.of(program);
        IrisSamplerOrder.Order order = IrisSamplerOrder.of(program, false);
        if (unit == Unit.FULLSCREEN) {
            source = withCompositeAliases(source, deviations);
            // a shadowcomp program registers no colour target (ShadowCompositeRenderer.createProgram)
            // its unit 0 holds whichever sampler it registers first, which Chimera does not model
            if (!order.registers(IrisSamplerOrder.Source.TARGET)) return source;
        }
        return readThroughUnit(source, config, deviations, program, unit, order, false);
    }

    /**
     * {@code source} of a compute of {@code pass}, with its gdepthtex alias and, where Iris binds
     * colortex0 to unit 0, each sampler2D neither Iris nor the pack serves read as colortex0.
     *
     * CompositeRenderer and FinalPassRenderer build their computes like their fullscreen programs,
     * through IrisSamplers.addRenderTargetSamplers with isFullscreenPass true, and so do the setup
     * computes: Visual Vibrance's prepare2 reads its lightmap sampler as colortex0, still at its
     * clear colour. A shadowcomp compute registers no colour target, so its unit 0 holds whichever
     * sampler it registers first (noisetex, a custom texture or a shadow sampler). Chimera does not
     * model that and leaves the sampler as written.
     */
    static String applyCompute(String source, PackConfig.PackConfigData config, List<String> deviations, String pass) {
        if (source == null) return null;
        String aliased = withCompositeAliases(source, deviations);
        IrisSamplerOrder.Order order = IrisSamplerOrder.of(pass, true);
        if (!order.registers(IrisSamplerOrder.Source.TARGET)) return aliased;
        return readThroughUnit(aliased, config, deviations, pass, Unit.FULLSCREEN, order, true);
    }

    private static String readThroughUnit(String source, PackConfig.PackConfigData config, List<String> deviations,
                                          String program, Unit unit, IrisSamplerOrder.Order order, boolean compute) {
        GlslTree tree = GlslDeclarations.parseOrNull(source);
        if (tree == null || GlslScopedReferences.declaredLocally(tree, unit.texture)) return source;
        String unitType = uniformType(tree, unit.texture);
        if (unitType != null && !unitType.equals("sampler2D")) return source;
        boolean declared = unitType != null;
        GlslResourceUsage.Analysis usage = GlslResourceUsage.analyze(GlslLexer.stripComments(source));
        String result = source;
        for (String name : lineSampler2Ds(tree)) {
            boolean unused = usage.successful() && !usage.liveSamplers().contains(name);
            if (unused || name.equals(unit.texture) || served(name, config, program, order, compute)) continue;
            // the unit's texture is declared once, so a second sampler that reads it loses its own declaration
            if (declared) result = withoutDeclaration(result, name);
            result = GlslScopedReferences.replace(result, Map.of(name, unit.texture));
            deviations.add(unit.deviation + name);
            declared = true;
        }
        return result;
    }

    /**
     * Iris registers gdepthtex together with depthtex0 (IrisSamplers.addCompositeSamplers), so in a
     * fullscreen pass or a compute both names read the one texture: a gdepthtex sampler2D reads as
     * depthtex0, and loses its declaration where depthtex0 is declared too. A stage texture of
     * either name still replaces it (IrisSamplerOrder.Order.names). A source where that cannot be
     * done cleanly stays as written.
     */
    static String withCompositeAliases(String source, List<String> deviations) {
        if (source == null || !source.contains("gdepthtex")) return source;
        GlslTree tree = GlslDeclarations.parseOrNull(source);
        if (tree == null) return source;
        GlslDeclarations.Variable alias = null;
        String depthType = null;
        for (GlslDeclarations.Variable variable : GlslDeclarations.variables(tree)) {
            if (!variable.qualified("uniform")) continue;
            if (variable.name().equals("gdepthtex")) alias = variable;
            if (variable.name().equals("depthtex0")) depthType = variable.type();
        }
        if (alias == null || !lineSampler2D(tree, alias) || depthType != null && !depthType.equals("sampler2D")) {
            return source;
        }
        String result = depthType != null ? withoutDeclaration(source, "gdepthtex") : source;
        if (GlslTokenRewriter.identifierCount(source, "gdepthtex") > 1) {
            deviations.add("STANDARD_RESOURCE_ALIAS:gdepthtex:depthtex0");
        }
        return GlslScopedReferences.replace(result, Map.of("gdepthtex", "depthtex0"));
    }

    /** The type of the first uniform named {@code name}, or null when none is declared. */
    private static String uniformType(GlslTree tree, String name) {
        for (GlslDeclarations.Variable variable : GlslDeclarations.variables(tree)) {
            if (variable.name().equals(name) && variable.qualified("uniform")) return variable.type();
        }
        return null;
    }

    /** The names declared as line-starting {@code uniform sampler2D name;}, sorted. */
    private static Set<String> lineSampler2Ds(GlslTree tree) {
        Set<String> names = new TreeSet<>();
        for (GlslDeclarations.Variable variable : GlslDeclarations.variables(tree)) {
            if (lineSampler2D(tree, variable)) names.add(variable.name());
        }
        return names;
    }

    /** {@code uniform sampler2D name;} alone at the start of its line, after any indentation. */
    private static boolean lineSampler2D(GlslTree tree, GlslDeclarations.Variable variable) {
        return variable.qualifiers().equals(List.of("uniform")) && variable.type().equals("sampler2D")
                && variable.plain() && GlslDeclarations.indentationStart(tree.source(),
                tree.offset(variable.declaration().range().start())) >= 0;
    }

    /** The source without the line-starting {@code uniform sampler2D name;}; its indentation stays. */
    private static String withoutDeclaration(String source, String name) {
        GlslTree tree = GlslTree.parse(source);
        List<GlslDeclarations.TextEdit> edits = new ArrayList<>();
        for (GlslDeclarations.Variable variable : GlslDeclarations.variables(tree)) {
            if (variable.name().equals(name) && lineSampler2D(tree, variable)) {
                edits.add(new GlslDeclarations.TextEdit(tree.offset(variable.declaration().range().start()),
                        tree.offset(variable.declaration().range().end()), ""));
            }
        }
        return GlslDeclarations.apply(source, edits);
    }

    /**
     * Whether {@code program}'s renderer, registering in {@code order}, registers {@code name}: a
     * built-in sampler, a customTexture entry, an image, or a raw stage texture, which Iris renames to
     * its own customtexN and registers in every renderer. A PNG texture.<stage> entry only replaces a
     * registration, so alone it leaves the sampler on unit 0. A name Iris registers but Chimera cannot
     * serve counts as served here; unit 0 does not apply to it.
     */
    private static boolean served(String name, PackConfig.PackConfigData config, String program,
                                  IrisSamplerOrder.Order order, boolean compute) {
        // shadowcolor2-7 and shadowtexNHW count only where Iris binds them; unbound, they read unit 0
        // a compute sees every shadowcolor Iris creates on first use, beyond those the shadow pass renders:
        // Alpha Piscium's composite7 compute reads shadowcolor6, which no program names
        int shadowColor = PackResourcePlan.shadowColorIndex(name);
        if (compute && shadowColor >= 0) return ShadowTargetDirectives.computeHas(shadowColor, config);
        if (shadowColor >= 0 || name.matches("shadowtex[01]HW")) {
            return ShadowTargetDirectives.bound(name, config);
        }
        PackSettingsPlan settings = config == null ? PackSettingsPlan.empty() : config.settings();
        Map<String, PackResourceDeclaration> declarations = settings.resourceDeclarations();
        if (PackResourcePlan.rawTextureServes(program, name, "sampler2D", declarations)) return true;
        boolean customTexture = declarations.containsKey("customTexture." + name);
        boolean image = PackResourcePlan.advancedImageName(settings.propertyValues(), name) != null;
        return order.last(name, image, customTexture) != null;
    }
}
