// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import net.chimera.shaderpack.WorldBetweenStages.PassedOn;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.chimera.shaderpack.WorldBetweenStages.arraySuffix;
import static net.chimera.shaderpack.WorldBetweenStages.qualified;

/**
 * A pack program's geometry stage (.gsh). Its inputs pair with the vertex outputs and its outputs with the
 * fragment inputs, each pair by name at one shared location. On a lane drawn with the draw's own matrices it
 * passes on the entity identities from the primitive's first vertex, as Iris's EntityPatcher does.
 */
final class GeometryStageBridge {
    private GeometryStageBridge() {}

    /** The suffix of a passed-on identity's output; its input keeps the vertex stage's name. */
    private static final String PASSED_ON_SUFFIX = "GS";
    /** The function a geometry stage's EmitVertex calls become: the clip-depth conversion, then the emit. */
    static final String EMIT = "chimeraEmitVertex";
    /** The prefix of the global that keeps a geometry stage output's value across an emit. */
    static final String KEPT_PREFIX = "chimeraKept_";
    /** The draw's legacy matrices on the entity and host-layout shadow lanes, served to the vertex stage alone. */
    private static final Set<String> DRAW_MATRICES = EntityVertexBridge.entityShadowMatrixBuiltinReplacements()
            .keySet();
    private static final Pattern DRAW_TEXTURE_MATRIX = Pattern.compile("\\bgl_TextureMatrix\\s*\\[\\s*0\\s*\\]");
    /** The stage's input primitive declaration, {@code layout(triangles) in;}. */
    private static final Pattern INPUT_PRIMITIVE = Pattern.compile("\\blayout\\s*\\(([^)]*)\\)\\s*in\\s*;");

    /** The converted stage, and the locations of its outputs, which the fragment stage's inputs take. */
    record Conversion(String source, LegacyGlslConverter.TerrainVaryingLayout outputLayout) {}

    /**
     * The stage as the vertex stage's consumer, its per-vertex inputs declared as single values so the vertex
     * bridge pairs with it as with a fragment. It also declares each entity identity only {@code fragment} reads.
     */
    static String consumerView(String geometry, String fragment) {
        String view = GlslInterfaceScanner.rewrite(geometry, GlslInterfaceScanner.Stage.GEOMETRY, declaration ->
                declaration.perVertex() ? qualified(declaration.qualifier()) + "in " + declaration.type() + " "
                        + declaration.name() + arraySuffix(declaration) + ";\n" : null);
        String stripped = GlslLexer.stripComments(view);
        StringBuilder passedOn = new StringBuilder();
        for (String name : EntityIdRewrites.ENTITY_ID_NAMES) {
            String type = EntityIdRewrites.entityIdentifierType(name, "", fragment);
            if (type != null && EntityIdRewrites.entityIdentifierType(name, stripped, null) == null) {
                passedOn.append("uniform ").append(type).append(' ').append(name).append(";\n");
            }
        }
        return view + passedOn;
    }

    /** The stage as the fragment stage's producer: its outputs alone. */
    static String producerView(String geometry) {
        return GlslInterfaceScanner.rewrite(geometry, GlslInterfaceScanner.Stage.GEOMETRY,
                declaration -> declaration.input() ? "" : null);
    }

    /**
     * Converts a shadow program's geometry stage. {@code inputLayout} holds the vertex output locations; the
     * fragment's inputs decide the output layout. With {@code drawMatrices} the entity identities pass on to
     * the fragment, read as the first vertex's.
     *
     * @throws IllegalArgumentException on an input no vertex output feeds, an output the layout cannot place,
     *         an unadmitted storage buffer, an unplanned value uniform, or a draw matrix only the vertex stage
     *         is served
     */
    static Conversion convertShadow(String geometry, LegacyGlslConverter.TerrainVaryingLayout inputLayout,
                                    String fragment, UniformRegistry.ProgramInterface interfacePlan,
                                    boolean allowStorageBuffers, boolean drawMatrices) {
        String source = LegacyGlslConverter.expandModernVaryingLists(TerrainVertexBridge.normalizeShadowTerrain(
                geometry));
        Map<String, String> identities = passedOnIdentities(inputLayout);
        LegacyGlslConverter.TerrainVaryingLayout outputLayout = TerrainVertexBridge.modernVaryingLayout(
                producerView(source), LegacyGlslConverter.expandModernVaryingLists(
                        TerrainVertexBridge.normalizedFragment(fragment, true)), identities);
        String converted = TerrainVertexBridge.withoutUnreachableFunctions(
                LegacyGlslConverter.withoutVersionLines(source));
        converted = interfacePlan == null ? TerrainVertexBridge.stripShadowUniformDeclarations(converted)
                : InterfacePlanner.removeUniformDeclarations(converted, interfacePlan);
        if (drawMatrices) converted = EntityDeclarations.removeEntityIdDeclarations(converted, interfacePlan);
        requireSupported(converted, allowStorageBuffers);
        if (drawMatrices) requireServedMatrices(converted);
        converted = located(converted, inputLayout, outputLayout);
        converted = GlslScopedReferences.replace(converted, firstVertexIdentities(identities.keySet(), inputLayout));
        if (!drawMatrices) {
            converted = GlslTokenRewriter.replaceIdentifiers(converted, ShadowStageBuiltins.matrixReplacements());
        }
        converted = ShadowStageBuiltins.legacyBuiltins(converted);
        converted = TerrainVertexBridge.rewriteModernTerrainSamplers(converted, interfacePlan, true);
        converted = LegacyGlslConverter.convertTextureCalls(converted);
        converted = TerrainVertexBridge.qualifyPlannedShadowUniforms(converted, interfacePlan);
        converted = emitClipRange(converted, passOnStatements(identities.keySet()));
        return new Conversion("#version 460\n" + FragmentConverter.SHADOW_RENDER_STAGE_DEFINES
                + TerrainVertexBridge.modernUniformBlock(interfacePlan, true)
                + identityDeclarations(identities.keySet(), inputLayout, outputLayout) + converted, outputLayout);
    }

    /**
     * Converts a terrain, water or hand program's geometry stage; {@code inputLayout} holds the vertex output
     * locations. It takes GL clip positions, as under Iris, reads its matrices and uniforms as
     * WorldBetweenStages serves them, and converts each emitted vertex's clip depth. A hand's stage passes on
     * the entity bridge's added values from the first vertex, as Iris's EntityPatcher does.
     *
     * @throws IllegalArgumentException as {@link #convertShadow}, but for the draw matrices
     */
    static Conversion convertWorld(String geometry, LegacyGlslConverter.TerrainVaryingLayout inputLayout,
                                   String fragment, UniformRegistry.ProgramInterface interfacePlan,
                                   boolean allowStorageBuffers, boolean hand) {
        String source = LegacyGlslConverter.expandModernVaryingLists(TerrainVertexBridge.normalizeModernTerrain(
                geometry));
        PassedOn passedOn = hand ? PassedOn.of(inputLayout) : PassedOn.NONE;
        LegacyGlslConverter.TerrainVaryingLayout outputLayout = passedOn.outputLayout(producerView(source), fragment);
        String converted = TerrainVertexBridge.withoutUnreachableFunctions(
                LegacyGlslConverter.withoutVersionLines(source));
        converted = interfacePlan == null ? TerrainVertexBridge.stripShadowUniformDeclarations(converted)
                : InterfacePlanner.removeUniformDeclarations(converted, interfacePlan);
        requireSupported(converted, allowStorageBuffers);
        converted = located(converted, inputLayout, outputLayout);
        converted = WorldBetweenStages.builtins(converted, interfacePlan, hand);
        converted = emitClipRange(converted, passedOn.geometryStatements(PASSED_ON_SUFFIX));
        if (GlslTokenRewriter.containsIdentifier(converted, "chimeraGlClip")) {
            converted = WorldClipRange.GL.withHelpers(converted);
        }
        return new Conversion(WorldBetweenStages.preamble(converted, interfacePlan, hand)
                + passedOn.geometryDeclarations(inputLayout, outputLayout, PASSED_ON_SUFFIX) + converted, outputLayout);
    }

    /**
     * Converts each emitted vertex's clip depth just before the emit, where the {@code passOn} statements also
     * run. GLSL leaves outputs undefined after EmitVertex; NVIDIA's GL keeps them but its Vulkan driver does
     * not, so the emit saves every output and the position and restores them afterwards. Other built-in
     * outputs and EmitStreamVertex are left as written.
     *
     * The emit is declared before the first function and defined at the end, since a pack may declare outputs
     * below its included functions.
     */
    private static String emitClipRange(String source, List<String> passOn) {
        List<GlslInterfaceScanner.Declaration> outputs = GlslInterfaceScanner.scan(source,
                GlslInterfaceScanner.Stage.GEOMETRY).outputs();
        String renamed = GlslTokenRewriter.replaceIdentifiers(source, Map.of("EmitVertex", EMIT));
        StringBuilder helper = new StringBuilder("\n");
        for (GlslInterfaceScanner.Declaration output : outputs) {
            helper.append(output.type()).append(' ').append(KEPT_PREFIX).append(output.name())
                    .append(arraySuffix(output)).append(";\n");
        }
        helper.append("void " + EMIT + "() {\n");
        passOn.forEach(statement -> helper.append("    ").append(statement).append('\n'));
        outputs.forEach(output -> helper.append("    ").append(KEPT_PREFIX).append(output.name()).append(" = ")
                .append(output.name()).append(";\n"));
        helper.append("    vec4 chimeraKeptPosition = gl_Position;\n")
                .append("    ").append(ClipDepth.TO_VULKAN).append('\n')
                .append("    EmitVertex();\n")
                .append("    gl_Position = chimeraKeptPosition;\n");
        outputs.forEach(output -> helper.append("    ").append(output.name()).append(" = ").append(KEPT_PREFIX)
                .append(output.name()).append(";\n"));
        helper.append("}\n");
        GlslTree tree = GlslTree.parse(renamed);
        for (GlslTree.External external : tree.externals()) {
            if (external instanceof GlslTree.Function) {
                int at = tree.offset(external.range().start());
                return renamed.substring(0, at) + "void " + EMIT + "();\n" + renamed.substring(at) + helper;
            }
        }
        throw new IllegalArgumentException("geometry stage has no function to emit from");
    }

    /**
     * Requires a main and triangle input, with no unadmitted storage buffer or unplanned value uniform.
     * VulkanMod draws lines, quads and strips as triangle lists, so every lane draws triangles.
     */
    private static void requireSupported(String source, boolean allowStorageBuffers) {
        String stripped = GlslLexer.stripComments(source);
        GlslTree tree = GlslTree.parse(stripped);
        if (GlslDeclarations.main(tree) == null) {
            throw new IllegalArgumentException("geometry stage main is missing");
        }
        Matcher primitive = INPUT_PRIMITIVE.matcher(stripped);
        if (primitive.find() && !List.of(primitive.group(1).split("\\s*,\\s*")).contains("triangles")) {
            throw new IllegalArgumentException("geometry stage input primitive is not triangles: "
                    + primitive.group(1).trim());
        }
        if (!allowStorageBuffers && GlslTokenRewriter.containsIdentifier(stripped, "buffer")) {
            throw new IllegalArgumentException("storage buffer was not admitted by the program plan");
        }
        String uniform = TerrainVertexBridge.valueUniform(tree);
        if (uniform != null) {
            throw new IllegalArgumentException("geometry stage value uniform is unplanned: " + uniform);
        }
    }

    /** Rejects a read of the draw's legacy matrices, which the host serves to the vertex stage alone. */
    private static void requireServedMatrices(String source) {
        String stripped = GlslLexer.stripComments(source);
        String matrix = GlslTokenRewriter.firstIdentifier(stripped, DRAW_MATRICES::contains);
        if (matrix == null && DRAW_TEXTURE_MATRIX.matcher(stripped).find()) matrix = "gl_TextureMatrix[0]";
        if (matrix != null) {
            throw new IllegalArgumentException("geometry stage reads a draw matrix only the vertex stage is served: "
                    + matrix);
        }
    }

    /** The entity identities the vertex stage passes on, each with the type the stages read it as. */
    private static Map<String, String> passedOnIdentities(LegacyGlslConverter.TerrainVaryingLayout inputs) {
        Map<String, String> identities = new LinkedHashMap<>();
        for (String name : EntityIdRewrites.ENTITY_ID_NAMES) {
            String varying = EntityIdRewrites.entityIdVarying(name);
            if (inputs.locations().containsKey(varying)) identities.put(name, inputs.types().get(varying));
        }
        return identities;
    }

    /** Each identity the stage reads, as the first vertex's value: Iris's iris_entityInfo[0]. */
    private static Map<String, String> firstVertexIdentities(Collection<String> names,
                                                             LegacyGlslConverter.TerrainVaryingLayout inputs) {
        Map<String, String> reads = new LinkedHashMap<>();
        for (String name : names) reads.put(name, EntityIdRewrites.entityIdExpression(inputs, name, "[0]"));
        return reads;
    }

    /** Each identity's output written from the first vertex's input. */
    private static List<String> passOnStatements(Collection<String> names) {
        return names.stream().map(EntityIdRewrites::entityIdVarying)
                .map(varying -> varying + PASSED_ON_SUFFIX + " = " + varying + "[0];").toList();
    }

    /** Each identity's flat input at the vertex stage's location, and its output at the fragment stage's. */
    private static String identityDeclarations(Collection<String> names,
                                               LegacyGlslConverter.TerrainVaryingLayout inputs,
                                               LegacyGlslConverter.TerrainVaryingLayout outputs) {
        StringBuilder declarations = new StringBuilder();
        for (String name : names) {
            String varying = EntityIdRewrites.entityIdVarying(name);
            declarations.append("layout(location = ").append(inputs.location(varying)).append(") flat in uint ")
                    .append(varying).append("[];\n")
                    .append("layout(location = ").append(outputs.location(varying)).append(") flat out uint ")
                    .append(varying).append(PASSED_ON_SUFFIX).append(";\n");
        }
        return declarations.toString();
    }

    /**
     * Places each input at its vertex output's location and each output at its fragment input's; integer
     * outputs are flat. An unread input is dropped, as GL leaves it inactive.
     */
    private static String located(String source, LegacyGlslConverter.TerrainVaryingLayout inputs,
                                  LegacyGlslConverter.TerrainVaryingLayout outputs) {
        return GlslInterfaceScanner.rewrite(source, GlslInterfaceScanner.Stage.GEOMETRY, declaration -> {
            if (declaration.input()) {
                if (!inputs.locations().containsKey(declaration.name())) {
                    if (declaration.referenced()) {
                        throw new IllegalArgumentException("geometry input has no vertex output: "
                                + declaration.name());
                    }
                    return "";
                }
                return "layout(location = " + inputs.location(declaration.name()) + ") "
                        + qualified(declaration.qualifier()) + "in " + declaration.type() + " "
                        + declaration.declarator() + ";\n";
            }
            String interpolation = outputs.qualifier(declaration.name());
            if (interpolation.isEmpty() && declaration.integral()) interpolation = "flat";
            return "layout(location = " + outputs.location(declaration.name()) + ") " + qualified(interpolation)
                    + "out " + declaration.type() + " " + declaration.declarator() + ";\n";
        });
    }
}
