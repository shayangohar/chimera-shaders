// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.render.vertex.ChimeraVertexFormats;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.chimera.shaderpack.GlslLexer.stripComments;

/** Converts entity-family vertex stages onto the extended entity vertex contract, with a variant per host format. */
public final class EntityVertexBridge {
    private EntityVertexBridge() {}

    /** The storage keywords of a declaration the conversion must have consumed. */
    private static final Set<String> INTERFACE_STORAGES = Set.of("attribute", "varying", "in", "out");
    /** A leftover declaration's text: from its keyword through the rest of its line. */
    private static final Pattern REST_OF_LINE = Pattern.compile("\\s+.*$", Pattern.MULTILINE);
    private static final Pattern SAMPLER_TYPE = Pattern.compile("[iu]?sampler\\w*");

    /**
     * Glint draws with depth test EQUAL over another program's geometry, and only an invariant position is
     * bit-identical across programs (GLSL 4.60, 4.8.1); NVIDIA otherwise speckles. Interface blocks are
     * flattened first, so no gl_PerVertex redeclaration follows.
     */
    static final String INVARIANT_POSITION = "invariant gl_Position;\n";

    /** A host value forwarded to a fragment that reads it: the overlay coordinates or the vertex alpha. */
    record ForwardedVarying(String name, String type, String qualifier, String value) {
        static final List<ForwardedVarying> ALL = List.of(
                new ForwardedVarying(EntityOverlayColor.UV_VARYING, "ivec2", "flat ", "UV1"),
                new ForwardedVarying(PackAlphaTestPlan.VERTEX_ALPHA_VARYING, "float", "", "Color.a"));

        /** The forwarded varyings {@code fragment} reads, in a fixed order. */
        static List<ForwardedVarying> readBy(String fragment) {
            String stripped = stripComments(fragment == null ? "" : fragment);
            return ALL.stream().filter(varying -> LegacyGlslConverter.containsIdentifier(stripped, varying.name()))
                    .toList();
        }

        /** The varying's {@code in} or {@code out} declaration at its location in {@code layout}. */
        String declaration(LegacyGlslConverter.TerrainVaryingLayout layout, String direction) {
            return "layout(location = " + layout.location(name) + ") " + qualifier + direction + " " + type + " "
                    + name + ";\n";
        }
    }

    /**
     * Converts the shadow entity lane's vertex stage (Iris shadow_entities) onto EXTENDED_ENTITY, with entity
     * ids per vertex as in Iris's EntityPatcher. {@code consumerSource} is the fragment, or a geometry stage
     * when {@code geometryNext}.
     */
    public static LegacyGlslConverter.TerrainVertexConversion convertShadowEntityVertexChecked(
            String source,
            Path sourceFile,
            String consumerSource,
            UniformRegistry.ProgramInterface interfacePlan,
            boolean allowStorageBuffers,
            boolean geometryNext
    ) {
        return TerrainVertexBridge.convertModernTerrainVertexOrThrow(source, sourceFile, consumerSource,
                GlslVertexPreambles.ENTITY_SHADOW_VERTEX_PREAMBLE, interfacePlan, true, true, allowStorageBuffers,
                geometryNext);
    }

    /**
     * The legacy built-ins as the draw's host matrices, which hold the light view, as Iris's iris_ModelViewMat
     * does; a render type's layering transform stays in them.
     */
    static Map<String, String> entityShadowMatrixBuiltinReplacements() {
        return Map.of(
                "gl_ModelViewMatrix", "ModelViewMat",
                "gl_NormalMatrix", GlslVertexPreambles.normalMatrix("ModelViewMat"),
                "gl_ProjectionMatrix", "ProjMat",
                "gl_ModelViewProjectionMatrix", "(ProjMat * ModelViewMat)");
    }

    /** Converts an entity vertex stage onto EXTENDED_ENTITY. */
    public static LegacyGlslConverter.TerrainVertexConversion convertEntityVertex(
            String source,
            Path sourceFile,
            String fragmentSource
    ) {
        return convertEntityVertex(source, sourceFile, fragmentSource, null);
    }

    /** Converts an entity vertex stage at the locations the shared stage matcher picked. */
    public static LegacyGlslConverter.TerrainVertexConversion convertEntityVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations
    ) {
        return convertEntityVertex(source, sourceFile, fragmentSource, sharedLocations, false);
    }

    /** Converts an entity vertex with storage blocks admitted by the plan. */
    public static LegacyGlslConverter.TerrainVertexConversion convertEntityVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations,
            boolean allowStorageBuffers
    ) {
        return convertEntityVertex(source, sourceFile, fragmentSource, sharedLocations,
                GlslVertexPreambles.ENTITY_VERTEX_PREAMBLE, allowStorageBuffers, List.of());
    }

    /** Entity vertex conversion that reports the first concrete failure. */
    public static LegacyGlslConverter.TerrainVertexConversion convertEntityVertexChecked(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations,
            boolean allowStorageBuffers,
            List<UniformRegistry.UniformDeclaration> fragmentUniforms
    ) {
        return convertEntityVertexChecked(source, sourceFile, fragmentSource, sharedLocations,
                allowStorageBuffers, fragmentUniforms, null);
    }

    /** Entity vertex conversion whose referenced samplers take the program's shared bindings. */
    public static LegacyGlslConverter.TerrainVertexConversion convertEntityVertexChecked(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations,
            boolean allowStorageBuffers,
            List<UniformRegistry.UniformDeclaration> fragmentUniforms,
            VertexSamplerPlan samplerPlan
    ) {
        return convertEntityVertexOrThrow(source, sourceFile, fragmentSource, sharedLocations,
                GlslVertexPreambles.ENTITY_VERTEX_PREAMBLE, allowStorageBuffers, fragmentUniforms, samplerPlan);
    }

    /** Converts the block-entity variant: block entities draw posed, on the entity inputs. */
    public static LegacyGlslConverter.TerrainVertexConversion convertBlockVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations
    ) {
        return convertBlockVertex(source, sourceFile, fragmentSource, sharedLocations, false);
    }

    /** Converts a block vertex with storage blocks admitted by the plan. */
    public static LegacyGlslConverter.TerrainVertexConversion convertBlockVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations,
            boolean allowStorageBuffers
    ) {
        return convertEntityVertex(source, sourceFile, fragmentSource, sharedLocations,
                GlslVertexPreambles.ENTITY_VERTEX_PREAMBLE, allowStorageBuffers, List.of());
    }

    /** Block vertex conversion that reports the first concrete failure. */
    public static LegacyGlslConverter.TerrainVertexConversion convertBlockVertexChecked(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations,
            boolean allowStorageBuffers,
            List<UniformRegistry.UniformDeclaration> fragmentUniforms
    ) {
        return convertBlockVertexChecked(source, sourceFile, fragmentSource, sharedLocations,
                allowStorageBuffers, fragmentUniforms, null);
    }

    /** Block vertex conversion whose referenced samplers take the program's shared bindings. */
    public static LegacyGlslConverter.TerrainVertexConversion convertBlockVertexChecked(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations,
            boolean allowStorageBuffers,
            List<UniformRegistry.UniformDeclaration> fragmentUniforms,
            VertexSamplerPlan samplerPlan
    ) {
        return convertEntityVertexOrThrow(source, sourceFile, fragmentSource, sharedLocations,
                GlslVertexPreambles.ENTITY_VERTEX_PREAMBLE, allowStorageBuffers, fragmentUniforms, samplerPlan);
    }

    /** Converts a hand vertex stage onto the entity inputs; held items and maps get layout variants at build time. */
    public static LegacyGlslConverter.TerrainVertexConversion convertHandVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations
    ) {
        return convertHandVertex(source, sourceFile, fragmentSource, sharedLocations, false);
    }

    /** Converts a hand vertex with storage blocks admitted by the plan. */
    public static LegacyGlslConverter.TerrainVertexConversion convertHandVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations,
            boolean allowStorageBuffers
    ) {
        return convertEntityVertex(source, sourceFile, fragmentSource, sharedLocations,
                GlslVertexPreambles.HAND_VERTEX_PREAMBLE, allowStorageBuffers, List.of());
    }

    /** Hand vertex conversion that reports the first concrete failure. */
    public static LegacyGlslConverter.TerrainVertexConversion convertHandVertexChecked(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations,
            boolean allowStorageBuffers,
            List<UniformRegistry.UniformDeclaration> fragmentUniforms
    ) {
        return convertHandVertexChecked(source, sourceFile, fragmentSource, sharedLocations,
                allowStorageBuffers, fragmentUniforms, null);
    }

    /** Hand vertex conversion whose referenced samplers take the program's shared bindings. */
    public static LegacyGlslConverter.TerrainVertexConversion convertHandVertexChecked(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations,
            boolean allowStorageBuffers,
            List<UniformRegistry.UniformDeclaration> fragmentUniforms,
            VertexSamplerPlan samplerPlan
    ) {
        return convertEntityVertexOrThrow(source, sourceFile, fragmentSource, sharedLocations,
                GlslVertexPreambles.HAND_VERTEX_PREAMBLE, allowStorageBuffers, fragmentUniforms, samplerPlan);
    }

    /**
     * The stage the vertex outputs feed. {@code laterStageConverts} when it is a tessellation or geometry stage,
     * after which a later stage converts the clip depth.
     */
    record Consumer(String source, boolean laterStageConverts) {}

    /**
     * Hand vertex conversion feeding {@code consumer}. Ahead of a tessellation or geometry stage the position
     * stays in GL's clip range, as Iris's next stage reads it; the last stage converts the depth.
     */
    public static LegacyGlslConverter.TerrainVertexConversion convertHandVertexChecked(
            String source, Path sourceFile, Consumer consumer, String fragmentSource,
            Map<String, Integer> sharedLocations, boolean allowStorageBuffers,
            List<UniformRegistry.UniformDeclaration> fragmentUniforms, VertexSamplerPlan samplerPlan) {
        return convertEntityVertexOrThrow(source, sourceFile, fragmentSource, sharedLocations,
                GlslVertexPreambles.HAND_VERTEX_PREAMBLE, allowStorageBuffers, fragmentUniforms, samplerPlan,
                consumer);
    }

    static LegacyGlslConverter.TerrainVertexConversion convertEntityVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations,
            String vertexPreamble,
            boolean allowStorageBuffers,
            List<UniformRegistry.UniformDeclaration> fragmentUniforms
    ) {
        try {
            return convertEntityVertexOrThrow(source, sourceFile, fragmentSource, sharedLocations,
                    vertexPreamble, allowStorageBuffers, fragmentUniforms, null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Throws the first concrete failure for PackPlanBuilder; capability checks use the overloads returning null. */
    static LegacyGlslConverter.TerrainVertexConversion convertEntityVertexOrThrow(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations,
            String vertexPreamble,
            boolean allowStorageBuffers,
            List<UniformRegistry.UniformDeclaration> fragmentUniforms,
            VertexSamplerPlan samplerPlan
    ) {
        return convertEntityVertexOrThrow(source, sourceFile, fragmentSource, sharedLocations, vertexPreamble,
                allowStorageBuffers, fragmentUniforms, samplerPlan, new Consumer(fragmentSource, false));
    }

    private static LegacyGlslConverter.TerrainVertexConversion convertEntityVertexOrThrow(
            String source, Path sourceFile, String fragmentSource, Map<String, Integer> sharedLocations,
            String vertexPreamble, boolean allowStorageBuffers,
            List<UniformRegistry.UniformDeclaration> fragmentUniforms, VertexSamplerPlan samplerPlan, Consumer consumer
    ) {
        String src = LegacyGlslConverter.prepareSource(source, sourceFile, true);
        src = LegacyGlslConverter.expandModernVaryingLists(src);
        src = LegacyGlslConverter.pruneUnreachableFunctions(src);
        String stripped = stripComments(src);
        GlslVersion.requireSupported(stripped, "entity vertex", true);
        if (GlslDeclarations.main(GlslTree.parse(stripped)) == null
                || !GlslTokenRewriter.containsIdentifier(stripped, "gl_Position")) {
            throw new IllegalArgumentException("entity vertex requires main and gl_Position");
        }
        Map<String, String> entityIdTypes = EntityIdRewrites.entityIdTypes(stripped, fragmentSource);
        // reject only what nothing below can translate
        String beforeFeatures = EntityDeclarations.removeEntityIdDeclarations(stripped);
        rejectEntityVertexFeatures(
                LegacyGlslConverter.stripUnusableUniformDeclarations(beforeFeatures, fragmentUniforms),
                allowStorageBuffers, samplerPlan != null, samplerPlan == null ? Set.of() : samplerPlan.storageImages());

        EntityVaryingDeclarations vertexVaryings = parseEntityVaryingDeclarations(stripped, true);
        requireMatchingFragmentVaryings(vertexVaryings, consumer.source());
        List<ForwardedVarying> forwarded = ForwardedVarying.readBy(fragmentSource);
        // bridge varyings follow the pack's: entity ids, then forwarded values
        Map<String, String> addedVaryings = new LinkedHashMap<>();
        entityIdTypes.forEach((name, type) -> addedVaryings.put(EntityIdRewrites.entityIdVarying(name), type));
        forwarded.forEach(varying -> addedVaryings.put(varying.name(), varying.type()));
        LegacyGlslConverter.TerrainVaryingLayout layout =
                entityVaryingLayout(vertexVaryings, sharedLocations, addedVaryings);

        String converted = rewriteEntityDeclarations(src, layout, fragmentUniforms);
        if (samplerPlan != null) {
            converted = bindVertexSamplers(converted, samplerPlan);
        }
        WorldClipRange clip = projectsInGlRange(vertexPreamble) ? WorldClipRange.HOST : WorldClipRange.of(stripped);
        // the plan already renamed pack identifiers that clash with the preamble
        converted = EntityIdRewrites.replaceInputs(converted,
                clip.builtins(entityInputReplacements(vertexPreamble, stripped, layout)));
        converted = converted.replaceAll("\\bgl_TextureMatrix\\s*\\[\\s*0\\s*\\]",
                "TextureMat");
        // raw UV2 for both light aliases, normalised once by the legacy matrix (Iris: VanillaTransformer)
        converted = LegacyGlslConverter.replaceLightmapTextureMatrices(converted);
        converted = converted.replaceAll("\\bftransform\\s*\\(\\s*\\)",
                clip.ftransform("chimeraEntityFtransform()"));
        converted = EntityIdRewrites.injectEntityIdPrologues(converted, entityIdTypes.keySet());
        for (ForwardedVarying varying : forwarded) {
            converted = LegacyGlslConverter.injectMainPrologue(converted,
                    varying.name() + " = " + varying.value() + ";");
        }
        requireConsumedDeclarations(converted);
        String chimeraVaryings = EntityIdRewrites.entityIdOutputDeclarations(entityIdTypes.keySet(), layout);
        for (ForwardedVarying varying : forwarded) {
            chimeraVaryings += varying.declaration(layout, "out");
        }
        String cameraBlock = GlslVertexPreambles.entityCameraUniformBlock(converted, fragmentUniforms);
        converted = clip.convert(consumer.laterStageConverts() ? converted : wrapProjectedMain(converted,
                vertexPreamble));
        return new LegacyGlslConverter.TerrainVertexConversion("#version 460\n" + INVARIANT_POSITION
                + vertexPreamble + chimeraVaryings + cameraBlock + converted,
                layout);
    }

    /** Every input the fragment reads must be a vertex output of the same type; interpolation may differ. */
    private static void requireMatchingFragmentVaryings(EntityVaryingDeclarations vertexVaryings,
                                                        String fragmentSource) {
        String expandedFragment = LegacyGlslConverter.expandModernVaryingLists(
                fragmentSource == null ? "" : stripComments(fragmentSource));
        EntityVaryingDeclarations fragmentVaryings = parseEntityVaryingDeclarations(expandedFragment, false);
        for (Map.Entry<String, String> entry : fragmentVaryings.types().entrySet()) {
            String vertexType = vertexVaryings.types().get(entry.getKey());
            if (!entry.getValue().equals(vertexType)) {
                throw new IllegalArgumentException("entity varying mismatch: " + entry.getKey());
            }
        }
    }

    /** Shared varyings at the matcher's locations, then other outputs by name, then the bridge's in given order. */
    private static LegacyGlslConverter.TerrainVaryingLayout entityVaryingLayout(
            EntityVaryingDeclarations vertexVaryings,
            Map<String, Integer> sharedLocations,
            Map<String, String> addedVaryings
    ) {
        Map<String, String> vertexTypes = new TreeMap<>(vertexVaryings.types());
        Map<String, Integer> locations = new TreeMap<>();
        if (sharedLocations != null) {
            locations.putAll(sharedLocations);
        }
        // a varying takes as many locations as its type, three for a mat3
        int location = 0;
        for (Map.Entry<String, Integer> shared : locations.entrySet()) {
            location = Math.max(location,
                    shared.getValue() + vertexVaryings.slots().getOrDefault(shared.getKey(), 1));
        }
        for (String name : vertexTypes.keySet().stream().sorted().toList()) {
            if (!locations.containsKey(name)) {
                locations.put(name, location);
                location += vertexVaryings.slots().get(name);
            }
        }
        for (Map.Entry<String, String> added : addedVaryings.entrySet()) {
            locations.put(added.getKey(), location++);
            vertexTypes.put(added.getKey(), added.getValue());
        }
        return new LegacyGlslConverter.TerrainVaryingLayout(vertexTypes, locations, vertexVaryings.qualifiers());
    }

    /** Drops what the entity contract declares itself and gives the varyings their layout locations. */
    private static String rewriteEntityDeclarations(
            String src,
            LegacyGlslConverter.TerrainVaryingLayout layout,
            List<UniformRegistry.UniformDeclaration> fragmentUniforms
    ) {
        String converted = LegacyGlslConverter.withoutVersionLines(src);
        converted = EntityDeclarations.removeEntityIdDeclarations(converted);
        converted = EntityDeclarations.removeEntityAttributes(converted);
        converted = replaceEntityVaryings(converted, layout, "out", true);
        // before identifier replacement, which would mangle these declarations
        converted = LegacyGlslConverter.stripUnusableUniformDeclarations(converted, fragmentUniforms);
        // an unread sampler has no layout(binding), which Vulkan requires
        return LegacyGlslConverter.stripUnreferencedSamplerDeclarations(converted);
    }

    /**
     * Replacements for the legacy inputs and matrices. gbufferModelView and its inverse stay pack uniforms, as
     * in Iris: a GPU inverse of ModelViewMat rounds differently and breaks the glint's EQUAL depth test, and
     * clouds' ModelViewMat carries the mesh offset.
     */
    private static Map<String, String> entityInputReplacements(
            String vertexPreamble,
            String stripped,
            LegacyGlslConverter.TerrainVaryingLayout layout
    ) {
        // the MVP stays a mat4; a vec4 ftransform() would make MVP * gl_Vertex component-wise
        Map<String, String> inputs = Map.ofEntries(
                Map.entry("gl_Vertex", "chimeraEntityVertexValue()"),
                Map.entry("gl_Color", GlslVertexPreambles.nativeLayoutPreamble(vertexPreamble)
                        ? "(Color * ColorModulator)" : "Color"),
                Map.entry("gl_MultiTexCoord0", "vec4(UV0, 0.0, 1.0)"),
                Map.entry("gl_MultiTexCoord1", "vec4(vec2(UV2), 0.0, 1.0)"),
                Map.entry("gl_MultiTexCoord2", "vec4(vec2(UV2), 0.0, 1.0)"),
                Map.entry("gl_Normal", "Normal.xyz"),
                Map.entry("gl_NormalMatrix", GlslVertexPreambles.normalMatrix("ModelViewMat")),
                Map.entry("gl_ModelViewMatrix", "ModelViewMat"),
                Map.entry("gl_ModelViewProjectionMatrix", "(ProjMat * ModelViewMat)"),
                Map.entry("gl_ProjectionMatrix", "ProjMat"),
                Map.entry("mc_midTexCoord", EntityDeclarations.midTexCoordValue(stripped)),
                Map.entry("at_tangent", "chimeraEntityTangentValue()"),
                // Vulkan's index counts from the draw's first vertex, as GL's does
                Map.entry("gl_VertexID", "gl_VertexIndex"),
                Map.entry("entityId", EntityIdRewrites.entityIdExpression(layout, "entityId")),
                Map.entry("blockEntityId", EntityIdRewrites.entityIdExpression(layout, "blockEntityId")),
                Map.entry("currentRenderedItemId",
                        EntityIdRewrites.entityIdExpression(layout, "currentRenderedItemId")));
        // hand and sky draws use their own projection; gbufferProjection stays the pack's, in GL clip depth
        if (vertexPreamble == GlslVertexPreambles.HAND_VERTEX_PREAMBLE) {
            inputs = new HashMap<>(inputs);
            inputs.put("gl_ProjectionMatrix", "chimeraHandProjection()");
            inputs.put("gl_ModelViewProjectionMatrix", "(chimeraHandProjection() * ModelViewMat)");
        }
        if (GlslVertexPreambles.isSkyPreamble(vertexPreamble)) {
            inputs = new HashMap<>(inputs);
            inputs.put("gl_ProjectionMatrix", "chimeraSkyProjection()");
            inputs.put("gl_ModelViewProjectionMatrix", "(chimeraSkyProjection() * ModelViewMat)");
        }
        return inputs;
    }

    /** Fails on an attribute, varying or interface declaration the conversion did not consume. */
    private static void requireConsumedDeclarations(String converted) {
        String stripped = stripComments(converted);
        GlslTree tree = GlslTree.parse(stripped);
        for (GlslTree.External external : tree.externals()) {
            List<GlslTree.Qualifier> qualifiers = external instanceof GlslTree.Declaration declaration
                    ? declaration.qualifiers()
                    : external instanceof GlslTree.InterfaceBlock block ? block.qualifiers() : List.of();
            if (qualifiers.isEmpty()) continue;
            String storage = tree.text(qualifiers.get(0).keyword());
            int start = tree.offset(qualifiers.get(0).keyword());
            int end = GlslDeclarations.tailEnd(stripped, start + storage.length(), REST_OF_LINE);
            if (INTERFACE_STORAGES.contains(storage) && end >= 0
                    && GlslDeclarations.lineStartBefore(stripped, start) >= 0) {
                String line = stripped.substring(start, end).trim();
                throw new IllegalArgumentException("entity declaration was not consumed: "
                        + line.substring(0, Math.min(80, line.length())));
            }
        }
    }

    /** Whether the draw's whole stage already runs in GL's clip range: the hand and sky draws (wrapProjectedMain). */
    private static boolean projectsInGlRange(String vertexPreamble) {
        return vertexPreamble == GlslVertexPreambles.HAND_VERTEX_PREAMBLE
                || GlslVertexPreambles.isSkyPreamble(vertexPreamble);
    }

    /** Hand and sky draws wrap the pack's main, then map GL clip depth onto Vulkan's zero-to-one range. */
    private static String wrapProjectedMain(String converted, String vertexPreamble) {
        String wrapped = converted;
        if (vertexPreamble == GlslVertexPreambles.HAND_VERTEX_PREAMBLE) {
            wrapped = ClipDepth.afterRenamedEntry(renamedMain(wrapped, "chimeraHandMain"), "chimeraHandMain");
        }
        if (GlslVertexPreambles.isSkyPreamble(vertexPreamble)) {
            wrapped = ClipDepth.afterRenamedEntry(renamedMain(wrapped, "chimeraSkyMain"), "chimeraSkyMain");
        }
        return wrapped;
    }

    /** Converts the legacy particle vertex contract onto DefaultVertexFormat.PARTICLE. */
    public static LegacyGlslConverter.TerrainVertexConversion convertParticleVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations
    ) {
        return convertParticleVertex(source, sourceFile, fragmentSource, sharedLocations, false);
    }

    /** Converts a particle vertex with storage blocks admitted by the plan. */
    public static LegacyGlslConverter.TerrainVertexConversion convertParticleVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations,
            boolean allowStorageBuffers
    ) {
        try {
            return convertParticleVertexChecked(source, sourceFile, fragmentSource,
                    sharedLocations, allowStorageBuffers, List.of());
        } catch (RuntimeException unsupported) {
            return null;
        }
    }

    /** Particle vertex conversion that reports the first concrete failure. */
    public static LegacyGlslConverter.TerrainVertexConversion convertParticleVertexChecked(
            String source, Path sourceFile, String fragmentSource,
            Map<String, Integer> sharedLocations, boolean allowStorageBuffers,
            List<UniformRegistry.UniformDeclaration> fragmentUniforms
    ) {
        return convertParticleVertexChecked(source, sourceFile, fragmentSource, sharedLocations,
                allowStorageBuffers, fragmentUniforms, null);
    }

    /**
     * Particle and weather vertex conversion with shared sampler bindings. PARTICLE has no mid-coordinate or
     * tangent, in Iris too, so the preamble serves GL's unbound defaults for both.
     */
    public static LegacyGlslConverter.TerrainVertexConversion convertParticleVertexChecked(
            String source, Path sourceFile, String fragmentSource,
            Map<String, Integer> sharedLocations, boolean allowStorageBuffers,
            List<UniformRegistry.UniformDeclaration> fragmentUniforms,
            VertexSamplerPlan samplerPlan
    ) {
        return convertEntityVertexOrThrow(source, sourceFile, fragmentSource, sharedLocations,
                GlslVertexPreambles.PARTICLE_VERTEX_PREAMBLE, allowStorageBuffers, fragmentUniforms, samplerPlan);
    }

    /** Whether the entity vertex bridge can convert {@code source}. */
    public static boolean supportsEntityVertex(String source, String fragmentSource) {
        return convertEntityVertex(source, null, fragmentSource) != null;
    }

    /** Whether the hand vertex bridge can convert {@code source}. */
    public static boolean supportsHandVertex(String source, String fragmentSource) {
        return convertHandVertex(source, null, fragmentSource, null) != null;
    }

    static String replaceEntityVaryings(
            String source,
            LegacyGlslConverter.TerrainVaryingLayout layout,
            String direction,
            boolean vertexStage
    ) {
        if (layout == null) {
            throw new IllegalArgumentException("entity varying layout is missing");
        }
        return GlslInterfaceScanner.rewrite(source, vertexStage, declaration -> {
            if (vertexStage ? !declaration.output() : !declaration.input()) {
                return null;
            }
            // drop unread declarations before the layout check; GL links them as dead either way
            if (!declaration.referenced()) {
                return "";
            }
            String name = declaration.name();
            if (!declaration.typeWithArray().equals(layout.types().get(name))) {
                throw new IllegalArgumentException("entity varying is not in the shared layout: " + name);
            }
            // each stage keeps its own interpolation; the fragment's decides (GLSL 4.40)
            String qualifier = declaration.qualifier() == null ? "" : declaration.qualifier();
            return "layout(location = " + layout.location(name) + ") " + direction + " "
                    + (qualifier.isEmpty() ? "" : qualifier + " ")
                    + declaration.type() + " " + declaration.declarator() + ";";
        });
    }

    /** Varyings by name; types carry any array suffix, and {@code slots} is the locations each occupies. */
    record EntityVaryingDeclarations(Map<String, String> types, Map<String, String> qualifiers,
                                             Map<String, Integer> slots) {}

    static EntityVaryingDeclarations parseEntityVaryingDeclarations(
            String source, boolean vertexStage) {
        Map<String, String> result = new TreeMap<>();
        Map<String, String> qualifiers = new TreeMap<>();
        Map<String, Integer> slots = new TreeMap<>();
        GlslInterfaceScanner.StageInterface stage = GlslInterfaceScanner.scan(source == null ? "" : source,
                vertexStage);
        if (!stage.deviations().isEmpty()) {
            throw new IllegalArgumentException("entity varying declarations unsupported: " + stage.deviations());
        }
        for (GlslInterfaceScanner.Declaration declaration : vertexStage ? stage.outputs() : stage.inputs()) {
            if (!vertexStage && !declaration.referenced()) {
                continue;
            }
            result.put(declaration.name(), declaration.typeWithArray());
            qualifiers.put(declaration.name(), declaration.qualifier() == null ? "" : declaration.qualifier());
            slots.put(declaration.name(), declaration.slots());
        }
        return new EntityVaryingDeclarations(result, qualifiers, slots);
    }

    /**
     * The program's samplers as the vertex stage sees them. {@code slots} is the sampler order, which fixes the
     * shared bindings; {@code storageImages} keep their declarations for PackDescriptorContracts.
     */
    public record VertexSamplerPlan(UniformRegistry.ProgramInterface vertexInterface, int[] slots,
                                    Set<String> storageImages) {
        public VertexSamplerPlan {
            storageImages = Set.copyOf(storageImages);
        }
    }

    /** Declares the vertex stage's samplers at the fragment's bindings; Iris binds the same textures to every stage. */
    static String bindVertexSamplers(String source, VertexSamplerPlan plan) {
        UniformRegistry.ProgramInterface vertexInterface = plan.vertexInterface();
        Map<String, String> samplerTypes = new TreeMap<>();
        for (UniformRegistry.SamplerBinding sampler : vertexInterface.samplers()) {
            samplerTypes.put(sampler.name(), sampler.glslType());
        }
        // rename an OptiFine 'texture' sampler before texture2D becomes texture, as the fragment stage does
        String converted = GlslTokenRewriter.renameSamplerIdentifier(source, "texture", "chimeraTexture");
        converted = LegacyGlslConverter.convertTextureCalls(converted);
        converted = GlslTokenRewriter.rewriteShadowCalls(converted, samplerTypes);
        for (UniformRegistry.SamplerBinding sampler : vertexInterface.samplers()) {
            String name = sampler.name().equals("texture") ? "chimeraTexture" : sampler.name();
            if (!FragmentConverter.hasSamplerDeclaration(converted, name)) continue;
            int binding = FragmentConverter.geometrySamplerBinding(sampler.name(), plan.slots(), vertexInterface);
            if (binding < 0) {
                throw new IllegalArgumentException("unsupported entity vertex sampler: " + sampler.name());
            }
            converted = FragmentConverter.rewriteSamplerDeclaration(converted, name, binding);
        }
        String unbound = unboundSampler(converted);
        if (unbound != null) {
            throw new IllegalArgumentException("unsupported entity vertex sampler: " + unbound);
        }
        return converted;
    }

    /** The first line-starting {@code uniform samplerX} declaration without a binding; null if none. */
    private static String unboundSampler(String source) {
        GlslTree tree = GlslTree.parse(source);
        for (GlslDeclarations.Variable variable : GlslDeclarations.variables(tree)) {
            int start = tree.offset(variable.declaration().range().start());
            if (variable.qualifiers().equals(List.of("uniform")) && SAMPLER_TYPE.matcher(variable.type()).matches()
                    && GlslDeclarations.lineStartBefore(source, start) >= 0) {
                return variable.name();
            }
        }
        return null;
    }

    /** The source with its main renamed {@code name}; unchanged without one. */
    private static String renamedMain(String source, String name) {
        String renamed = GlslDeclarations.renameMain(source, name);
        return renamed == null ? source : renamed;
    }

    /** The hand program adapted to this draw's host inputs. */
    public static String handVertexForFormat(String converted, VertexFormat host) {
        return layoutVertexForFormat(converted, host);
    }

    /** An entity, block or hand program adapted to its host layout's extended format, as in Iris. */
    public static String layoutVertexForFormat(String converted, VertexFormat host) {
        if (host == DefaultVertexFormat.NEW_ENTITY) return converted;
        String contract = converted == null ? null
                : converted.contains(GlslVertexPreambles.HAND_VERTEX_PREAMBLE)
                        ? GlslVertexPreambles.HAND_VERTEX_PREAMBLE
                : converted.contains(GlslVertexPreambles.ENTITY_VERTEX_PREAMBLE)
                        ? GlslVertexPreambles.ENTITY_VERTEX_PREAMBLE : null;
        if (contract == null) {
            throw new IllegalArgumentException("program lacks the entity input contract");
        }
        var extended = ChimeraVertexFormats.extendedLayout(host);
        if (extended == null) {
            throw new IllegalArgumentException("unsupported entity-family host format");
        }
        // a missing normal reads +Z and a missing lightmap zero, as in Iris's VanillaTransformer
        String preamble = contract;
        var names = extended.getElementAttributeNames();
        String[] types = {"vec3", "vec4", "vec2", "ivec2", "ivec2", "vec3"};
        String[] attributes = {"Position", "Color", "UV0", "UV1", "UV2", "Normal"};
        String[] defaults = {null, "vec4(1.0)", null, "ivec2(0)", "ivec2(0)", "vec3(0.0, 0.0, 1.0)"};
        for (int i = 0; i < attributes.length; i++) {
            int location = names.indexOf(attributes[i]);
            String declaration = location < 0
                    ? "const " + types[i] + " " + attributes[i] + " = " + defaults[i] + ";"
                    : "layout(location = " + location + ") in " + types[i] + " " + attributes[i] + ";";
            preamble = preamble.replace("layout(location = " + i + ") in " + types[i] + " " + attributes[i] + ";",
                    declaration);
        }
        preamble = preamble.replace("layout(location = 6) in uvec4 EntityIds;",
                        "layout(location = " + names.indexOf("EntityIds") + ") in uvec4 EntityIds;")
                .replace("layout(location = 7) in vec2 MidTexCoord;",
                        "layout(location = " + names.indexOf("MidTexCoord") + ") in vec2 MidTexCoord;")
                .replace("layout(location = 8) in vec4 ChimeraPackedTangent;",
                        "layout(location = " + names.indexOf("Tangent") + ") in vec4 ChimeraPackedTangent;");
        String adapted = converted.replace(contract, preamble);
        return host == DefaultVertexFormat.BLOCK ? movingBlockEntity(adapted) : adapted;
    }

    /**
     * Gives mc_Entity Iris's (-1, -1) for a block quad drawn outside a chunk mesh, such as a moving block. A
     * gbuffers program declares mc_Entity as a constant; a shadow program reads it through chimeraMcEntityValue.
     */
    static String movingBlockEntity(String source) {
        source = source.replaceFirst("(vec2 chimeraMcEntityValue\\(\\) \\{\\s*return )vec2\\(0\\.0\\);",
                "$1vec2(-1.0);");
        return EntityDeclarations.withEntityConstants(source, type -> switch (type) {
            case "float" -> "-1.0";
            case "vec2" -> "vec2(-1.0)";
            case "vec3" -> "vec3(-1.0, -1.0, 0.0)";
            default -> "vec4(-1.0, -1.0, 0.0, 1.0)";
        });
    }

    /**
     * The crumbling overlay's entity ids, all 0. Iris reads them as uniforms there: entityId is 0 by draw time,
     * but blockEntityId and currentRenderedItemId are -1, which this does not match.
     */
    public static String withoutEntityIds(String source) {
        return source.replaceFirst("layout\\(location = \\d+\\) in uvec4 EntityIds;",
                "const uvec4 EntityIds = uvec4(0);");
    }

    /**
     * The light input of a key Iris draws fullbright (LightingModel.FULLBRIGHT): Iris drops UV2, so the light
     * reads (240, 240) whatever the vertex holds.
     */
    public static String fullbrightLight(String source) {
        return source.replaceFirst("layout\\(location = \\d+\\) in ivec2 UV2;", "const ivec2 UV2 = ivec2(240);");
    }

    /** Iris's CRUMBLING inputs: {@link #fullbrightLight} and {@link #withoutEntityIds}. */
    public static String crumblingInputs(String source) {
        return fullbrightLight(withoutEntityIds(source));
    }

    /**
     * The program's fragment, or a shadow program's geometry stage, for {@code format}. A layout without UV1
     * has no overlay, so entityColor is zero there, as under Iris.
     */
    public static String fragmentForFormat(String converted, VertexFormat format) {
        return format == null || format.getElementAttributeNames().contains("UV1")
                ? converted : EntityOverlayColor.withoutOverlay(converted);
    }

    /**
     * The glint vertex stage for first-person glint. It takes the hand's projection and clip-depth conversion,
     * so its depth matches the held item's exactly for the EQUAL test. A glint already in GL's range
     * (WorldClipRange) only swaps its ftransform() for the hand preamble's.
     */
    public static String handGlintVertex(String converted) {
        int start = converted == null ? -1 : converted.indexOf(GlslVertexPreambles.GLINT_VERTEX_PREAMBLE);
        if (start < 0) throw new IllegalArgumentException("not a converted glint vertex");
        String body = converted.substring(start + GlslVertexPreambles.GLINT_VERTEX_PREAMBLE.length());
        String hand = converted.substring(0, start) + GlslVertexPreambles.GLINT_HAND_VERTEX_PREAMBLE;
        if (WorldClipRange.converts(body)) {
            return hand + body.replace(WorldClipRange.GL.ftransform("chimeraEntityFtransform()"),
                    "chimeraEntityFtransform()");
        }
        body = GlslTokenRewriter.replaceIdentifiers(body, Map.of("ProjMat", "chimeraHandProjection()"));
        return hand + ClipDepth.afterRenamedEntry(renamedMain(body, "chimeraHandMain"), "chimeraHandMain");
    }

    /** Built-ins an entity vertex may use; any other gl_ built-in is rejected by name. */
    static final Set<String> ENTITY_SERVED_BUILTINS = Set.of(
            "gl_Vertex", "gl_Color", "gl_MultiTexCoord0", "gl_MultiTexCoord1",
            "gl_MultiTexCoord2", "gl_Normal", "gl_NormalMatrix", "gl_ModelViewMatrix",
            "gl_ModelViewProjectionMatrix", "gl_ProjectionMatrix", "gl_TextureMatrix",
            "gl_Position", "gl_VertexID");

    /** Host inputs the entity vertex bridge serves, with the types a stage may declare each as. */
    private static final Map<String, Set<String>> SERVED_ATTRIBUTE_TYPES = Map.of(
            "entityId", Set.of("float", "int"),
            "blockEntityId", Set.of("float", "int"),
            "mc_midTexCoord", Set.of("vec2", "vec4"),
            "at_tangent", Set.of("vec4"),
            // terrain inputs the entity format lacks read GL's unbound default
            "mc_Entity", Set.of("float", "vec2", "vec3", "vec4"),
            "at_midBlock", Set.of("float", "vec2", "vec3", "vec4"));

    /** The value types the catalogue could serve; a leftover one is a custom uniform nothing serves. */
    private static final Pattern VALUE_TYPE = Pattern.compile("bool|int|float|vec[234]|ivec[234]|mat[234]");
    private static final Pattern SAMPLER_OR_IMAGE_TYPE = Pattern.compile("[iu]?sampler\\w*|u?i?image\\w*");
    /** Image types and image built-ins only; a name like image0Sampler is a sampler. */
    private static final Pattern IMAGE = Pattern.compile(
            "[iu]?image(?:1D|2D|3D|Cube|2DRect|1DArray|2DArray|CubeArray|Buffer|2DMS|2DMSArray)"
                    + "|image(?:Load|Store|Size|Samples|Atomic\\w+)");
    private static final Set<String> OTHER_STAGES = Set.of("geometry", "tessellation", "compute");
    private static final Set<String> ATTRIBUTE_DECLARATION_TYPES = Set.of("int", "float", "vec2", "vec3", "vec4");

    /**
     * Rejects what the bridge cannot serve. {@code source} is comment-stripped; {@code storageImages} are the
     * images the plan binds this stage (GraphicsImageScan), as Iris binds images in every stage.
     */
    static void rejectEntityVertexFeatures(
            String source, boolean allowStorageBuffers, boolean allowSamplers, Set<String> storageImages
    ) {
        GlslTree tree = GlslTree.parse(source);
        rejectReferencedCustomUniforms(tree);
        rejectUnboundSamplers(tree, allowSamplers, storageImages);
        String builtin = firstIdentifier(tree, name -> name.matches("gl_[A-Za-z]\\w*")
                && !ENTITY_SERVED_BUILTINS.contains(name));
        if (builtin != null) throw new IllegalArgumentException("unsupported entity vertex builtin: " + builtin);
        if (firstIdentifier(tree, name -> OTHER_STAGES.contains(name)
                || storageImages.isEmpty() && IMAGE.matcher(name).matches()) != null) {
            throw new IllegalArgumentException("unsupported entity vertex feature");
        }
        if (!allowStorageBuffers && firstIdentifier(tree, "buffer"::equals) != null) {
            throw new IllegalArgumentException("storage buffer was not admitted by the program plan");
        }
        rejectUnservedAttributes(tree);
        // an admitted storage block or image keeps its own layout; the plan rewrites its binding
        String interfaces = allowStorageBuffers ? GlslStorageBufferParser.withoutBlocks(source) : source;
        interfaces = withoutImageDeclarations(interfaces, storageImages);
        GlslTree withoutInterfaces = GlslTree.parse(withoutScannedInterfaces(interfaces));
        rejectUnservedAttributeDeclarations(withoutInterfaces);
        // scanned varyings are gone; a pack layout would clash with the generated locations
        if (firstIdentifier(withoutInterfaces, Set.of("layout", "flat", "noperspective")::contains) != null) {
            throw new IllegalArgumentException("entity vertex layout is fixed by Chimera");
        }
    }

    /** Served and dead uniforms are already stripped, so a referenced value uniform left is one nothing serves. */
    private static void rejectReferencedCustomUniforms(GlslTree tree) {
        for (GlslDeclarations.Variable variable : GlslDeclarations.variables(tree)) {
            if (variable.qualified("uniform") && VALUE_TYPE.matcher(variable.type()).matches()
                    && referencedOutside(tree, variable.name(), variable.declaration())) {
                throw new IllegalArgumentException("unsupported entity vertex uniform: " + variable.name());
            }
        }
    }

    /**
     * Rejects a referenced sampler when the plan binds none, and any unbound image, referenced or not, since
     * the stage keeps every declaration.
     */
    private static void rejectUnboundSamplers(GlslTree tree, boolean allowSamplers, Set<String> storageImages) {
        for (GlslDeclarations.Variable variable : GlslDeclarations.variables(tree)) {
            if (!variable.qualified("uniform") || !SAMPLER_OR_IMAGE_TYPE.matcher(variable.type()).matches()) continue;
            boolean image = variable.type().matches("u?i?image\\w*");
            boolean unbound = image
                    ? variable.declaration().declarators().stream()
                            .anyMatch(name -> !storageImages.contains(tree.text(name.name())))
                    : !allowSamplers && variable.declaration().declarators().stream()
                            .anyMatch(name -> referencedOutside(tree, tree.text(name.name()), variable.declaration()));
            if (unbound) {
                String first = tree.text(variable.declaration().declarators().get(0).name());
                throw new IllegalArgumentException("unsupported entity vertex sampler: " + first);
            }
        }
    }

    /** The source without the declarations of the images the plan binds. */
    private static String withoutImageDeclarations(String source, Set<String> storageImages) {
        if (storageImages.isEmpty()) return source;
        return PackPipelines.STORAGE_IMAGE_DECLARATION.matcher(source).replaceAll(declaration ->
                storageImages.contains(declaration.group(2)) ? "" : Matcher.quoteReplacement(declaration.group()));
    }

    /** Each input names a host input the bridge serves, declared as a type it serves that input as. */
    private static void rejectUnservedAttributes(GlslTree tree) {
        for (GlslDeclarations.Variable variable : GlslDeclarations.variables(tree)) {
            if ((variable.qualified("attribute") || variable.qualified("in"))
                    && !SERVED_ATTRIBUTE_TYPES.getOrDefault(variable.name(), Set.of()).contains(variable.type())) {
                throw new IllegalArgumentException("unsupported entity attribute: " + variable.name());
            }
        }
    }

    /** An attribute the interface scanner left behind is one it could not read as a served input. */
    private static void rejectUnservedAttributeDeclarations(GlslTree tree) {
        for (GlslDeclarations.Variable variable : GlslDeclarations.variables(tree)) {
            if (variable.qualified("attribute") && !(variable.plain()
                    && ATTRIBUTE_DECLARATION_TYPES.contains(variable.type())
                    && SERVED_ATTRIBUTE_TYPES.containsKey(variable.name()))) {
                throw new IllegalArgumentException("unsupported entity attribute declaration");
            }
        }
    }

    /** The source without its file-scope interface declarations, once every input is a served one. */
    private static String withoutScannedInterfaces(String source) {
        // the shared scanner reads `const in float depth` parameters and matrix varyings correctly
        try {
            for (GlslInterfaceScanner.Declaration input : GlslInterfaceScanner.scan(source, true).inputs()) {
                if (!SERVED_ATTRIBUTE_TYPES.containsKey(input.name())) {
                    throw new IllegalArgumentException("unsupported entity attribute: " + input.name());
                }
            }
            return GlslInterfaceScanner.rewrite(source, true, declaration -> "");
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("entity vertex interface: " + failure.getMessage());
        }
    }

    /** Whether any identifier outside {@code declaration} is {@code name}: the rest of the stage reads it. */
    private static boolean referencedOutside(GlslTree tree, String name, GlslTree.Declaration declaration) {
        for (int index = 0; index < tree.tokens().size(); index++) {
            if (!declaration.range().contains(index) && tree.tokens().get(index).identifier(name)) return true;
        }
        return false;
    }

    private static String firstIdentifier(GlslTree tree, Predicate<String> test) {
        for (GlslLexer.Token token : tree.tokens()) {
            if (token.kind() == GlslLexer.Kind.IDENTIFIER && test.test(token.text())) return token.text();
        }
        return null;
    }
}
