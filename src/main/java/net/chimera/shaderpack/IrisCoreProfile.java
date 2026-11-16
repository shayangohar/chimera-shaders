package net.chimera.shaderpack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static net.chimera.shaderpack.GlslLexer.stripComments;

/**
 * Prepares pack sources the way Iris's TransformPatcher does before any conversion: the
 * core-profile names, widened lines, the glint layer and the host-layout identities.
 */
final class IrisCoreProfile {
    private IrisCoreProfile() {}

    private static final Set<String> CORE_INPUTS = Set.of("vaPosition", "vaColor", "vaNormal", "vaUV0", "vaUV1",
            "vaUV2");

    private static final Set<String> CORE_MATRICES = Set.of("modelViewMatrix", "modelViewMatrixInverse",
            "projectionMatrix", "projectionMatrixInverse", "normalMatrix", "textureMatrix");

    private static final Set<String> CORE_UNIFORMS = Set.of("modelViewMatrix", "modelViewMatrixInverse",
            "projectionMatrix", "projectionMatrixInverse", "normalMatrix", "textureMatrix", "chunkOffset");

    /** The uniforms SodiumCoreTransformer renames outside the vertex stage: all but textureMatrix. */
    private static final Set<String> SODIUM_FRAGMENT_UNIFORMS = Set.of("modelViewMatrix",
            "modelViewMatrixInverse", "projectionMatrix", "projectionMatrixInverse", "normalMatrix", "chunkOffset");

    /** The matrices CompositeCoreTransformer gives a composite vertex stage. */
    private static final Set<String> COMPOSITE_VERTEX_UNIFORMS = Set.of("modelViewMatrix",
            "modelViewMatrixInverse", "projectionMatrix", "projectionMatrixInverse", "textureMatrix");

    /** The Iris transformer family that compiles a program, as TransformPatcher's Patch. */
    enum Patch {
        /** Vanilla draws: entities, block entities, particles, sky, lines and the rest. */
        VANILLA,
        /** Sodium's terrain and shadow terrain passes. */
        SODIUM,
        /** Fullscreen passes: shadowcomp, prepare, deferred, composite and final. */
        COMPOSITE,
        /**
         * A pack's own gbuffers_line: VANILLA in core mode whatever its stages' versions, as TransformPatcher's
         * isLine. A compatibility stage still keeps its own variables of the core names it does not declare as
         * uniforms, since {@link #coreMode} is asked per stage.
         */
        LINES
    }

    /**
     * The Iris patch that compiles a program: COMPOSITE for a fullscreen pass, LINES for a pack's own
     * gbuffers_line, SODIUM for terrain and shadow terrain, and VANILLA for the rest, shadow entities and
     * host-layout shadow lanes included.
     */
    static Patch patchFor(String program, UniformRegistry.Stage stage, boolean packLine) {
        if (stage == UniformRegistry.Stage.POST) return Patch.COMPOSITE;
        if (packLine) return Patch.LINES;
        FamilyAdapterPlan.Family family = FamilyAdapterRegistry.forProgram(program).family();
        return family == FamilyAdapterPlan.Family.TERRAIN || family == FamilyAdapterPlan.Family.WATER
                || family == FamilyAdapterPlan.Family.SHADOW ? Patch.SODIUM : Patch.VANILLA;
    }

    /**
     * Whether Iris's TransformPatcher compiles a stage in core-profile mode: a core profile, or 150 and above
     * with no profile. That mode serves the core names whether or not the stage declares them.
     *
     * Iris also uses the mode for every stage of a pack's own gbuffers_line ({@link Patch#LINES}); there a
     * compatibility stage gets only the core names it declares. Pass the source as written, since the modern
     * post and terrain normalisations drop the version line.
     */
    static boolean coreMode(String source) {
        GlslVersion version = GlslVersion.first(source);
        return version != null && version.irisCore();
    }

    /**
     * The core uniforms Iris gives one stage; only its core-profile transformers name them.
     *
     * VanillaCoreTransformer renames all of them in every stage. SodiumCoreTransformer renames all but
     * textureMatrix, which it replaces with the identity in the vertex stage alone. CompositeCoreTransformer
     * replaces the vertex stage's matrices other than normalMatrix. Outside core mode, and outside a pack's own
     * gbuffers_line, no transformer touches them, so a declared one is an ordinary uniform Iris never sets and
     * reads its initializer or zero.
     */
    static Set<String> servedUniforms(Patch patch, boolean vertexStage, boolean coreMode) {
        if (!coreMode && patch != Patch.LINES) return Set.of();
        return switch (patch) {
            case VANILLA, LINES -> CORE_UNIFORMS;
            case SODIUM -> vertexStage ? CORE_UNIFORMS : SODIUM_FRAGMENT_UNIFORMS;
            case COMPOSITE -> vertexStage ? COMPOSITE_VERTEX_UNIFORMS : Set.of();
        };
    }

    /** The overlay Iris's SodiumCoreTransformer gives a format without one: no hurt or white flash. */
    public static final String NO_OVERLAY = "ivec2(0, 10)";

    /** The type VanillaCoreTransformer gives a core name a stage reads without declaring it. */
    static final Map<String, String> CORE_NAME_TYPES = Map.ofEntries(
            Map.entry("vaPosition", "vec3"), Map.entry("vaColor", "vec4"), Map.entry("vaNormal", "vec3"),
            Map.entry("vaUV0", "vec2"), Map.entry("vaUV1", "vec2"), Map.entry("vaUV2", "vec2"),
            Map.entry("modelViewMatrix", "mat4"), Map.entry("modelViewMatrixInverse", "mat4"),
            Map.entry("projectionMatrix", "mat4"), Map.entry("projectionMatrixInverse", "mat4"),
            Map.entry("normalMatrix", "mat3"), Map.entry("textureMatrix", "mat4"), Map.entry("chunkOffset", "vec3"));

    /**
     * Rewrites the names Iris's core-profile mode serves to the compatibility built-ins, before the stage's
     * interface is planned. Every converter already serves those built-ins with the same values, as
     * VanillaCoreTransformer maps both spellings to one input.
     *
     * The names are vaPosition and the other core inputs, and the core uniforms the stage's transformer serves
     * ({@link #servedUniforms}). In core mode Iris serves them whether or not the stage declares them. Elsewhere
     * it leaves a declared core input unbound, which only a driver's attribute order made meaningful; Photon's
     * compatibility gbuffers_basic reads vaColor. Here a declared one gets its intended value. A core uniform the
     * stage's transformer does not serve stays declared, and Iris never sets it.
     *
     * chunkOffset is zero, since Chimera's positions are already camera-relative, as Iris's vaPosition plus
     * chunkOffset is. {@code overlay} is what vaUV1 reads, {@link #NO_OVERLAY} for fullscreen and terrain passes.
     * On a format without UVs Iris leaves vaUV0 and vaUV2 unbound, reading zero, where the compatibility inputs
     * read (0.5, 0.5) and full light; a pack's own gbuffers_line keeps Iris's zeros ({@link #prepareLineVertex}).
     */
    public static String prepareIrisCoreProfile(String source, Path sourceFile, boolean vertexStage,
                                                String overlay, boolean coreMode, Patch patch) {
        if (source == null) return null;
        Set<String> served = servedUniforms(patch, vertexStage, coreMode);
        Map<String, String> declared = new TreeMap<>();
        String result = withoutCoreDeclarations(expandIncludes(source, sourceFile), vertexStage, served, declared);
        if (coreMode) {
            // the source is macro-expanded as Iris's is, so a core name the pack defines as a macro has
            // expanded away, such as Noble's #define projectionMatrix gbufferProjection
            String stripped = stripComments(result);
            for (Map.Entry<String, String> name : CORE_NAME_TYPES.entrySet()) {
                boolean named = name.getKey().startsWith("va") ? vertexStage : served.contains(name.getKey());
                if (named && LegacyGlslConverter.containsIdentifier(stripped, name.getKey())) {
                    declared.putIfAbsent(name.getKey(), name.getValue());
                }
            }
        }
        if (declared.isEmpty()) return source;
        Map<String, String> replacements = new TreeMap<>();
        for (Map.Entry<String, String> entry : declared.entrySet()) {
            replacements.put(entry.getKey(), compatibilityValue(entry.getKey(), entry.getValue(), overlay));
        }
        // only the core names themselves: a same-named parameter or local keeps its meaning, as in Pixel
        // Perfect's toViewspace(in mat4 projectionMatrix, ...)
        // Iris gets the same result by renaming the parameter and its uses together to a fresh name
        // except in a composite vertex stage, where CompositeCoreTransformer replaces every reference, a
        // same-named parameter's uses included; none of the packs tested names one there
        return GlslScopedReferences.replace(result, replacements);
    }

    /** The compatibility built-in that serves one core name, read as the type the stage gives it. */
    private static String compatibilityValue(String name, String type, String overlay) {
        return switch (name) {
            case "vaPosition" -> type.equals("vec4") ? "gl_Vertex"
                    : TerrainVertexBridge.adaptModernTerrainType(type, "gl_Vertex.xyz", "vec3");
            case "vaColor" -> TerrainVertexBridge.adaptModernTerrainType(type, "gl_Color", "vec4");
            case "vaNormal" -> TerrainVertexBridge.adaptModernTerrainType(type, "gl_Normal", "vec3");
            case "vaUV0" -> TerrainVertexBridge.adaptModernTerrainType(type, "gl_MultiTexCoord0.xy", "vec2");
            case "vaUV1" -> type.equals("ivec2") ? overlay
                    : TerrainVertexBridge.adaptModernTerrainType(type, "vec2(" + overlay + ")", "vec2");
            case "vaUV2" -> TerrainVertexBridge.adaptModernTerrainType(type, "gl_MultiTexCoord1.xy", "vec2");
            case "modelViewMatrix" -> "gl_ModelViewMatrix";
            case "modelViewMatrixInverse" -> "inverse(gl_ModelViewMatrix)";
            case "projectionMatrix" -> "gl_ProjectionMatrix";
            case "projectionMatrixInverse" -> "inverse(gl_ProjectionMatrix)";
            case "normalMatrix" -> "gl_NormalMatrix";
            case "textureMatrix" -> "gl_TextureMatrix[0]";
            default -> "vec3(0.0)"; // chunkOffset
        };
    }

    /**
     * Removes a vertex stage's plain core-input declarations and the stage's declarations of its served core
     * uniforms, recording each in {@code declared} with the type the stage gave it. A served uniform's
     * initializer goes too, since Iris's value replaces it.
     */
    private static String withoutCoreDeclarations(String source, boolean vertexStage, Set<String> served,
                                                  Map<String, String> declared) {
        GlslTree tree = GlslTree.parse(source);
        List<GlslDeclarations.Variable> removed = new ArrayList<>();
        for (GlslDeclarations.Variable variable : GlslDeclarations.variables(tree)) {
            boolean input = vertexStage && CORE_INPUTS.contains(variable.name()) && isInput(variable)
                    && plainInput(variable);
            boolean uniform = variable.qualified("uniform") && served.contains(variable.name())
                    && variable.unarrayed();
            if (input || uniform) {
                declared.put(variable.name(), variable.type());
                removed.add(variable);
            }
        }
        return GlslDeclarations.apply(source, GlslDeclarations.removeVariables(tree, removed));
    }

    /** A core input declared without array sizes or an initializer, alone or beside other names. */
    private static boolean plainInput(GlslDeclarations.Variable variable) {
        return variable.unarrayed() && variable.declarator().initializer() == null;
    }

    private static boolean isInput(GlslDeclarations.Variable variable) {
        return variable.qualified("in") || variable.qualified("attribute");
    }

    /** Removes plain declarations of the core inputs, the core matrices as mat3 or mat4, and chunkOffset as vec3. */
    private static String withoutLineCoreDeclarations(String source) {
        GlslTree tree = GlslTree.parse(source);
        List<GlslDeclarations.Variable> removed = new ArrayList<>();
        for (GlslDeclarations.Variable variable : GlslDeclarations.variables(tree)) {
            boolean input = CORE_INPUTS.contains(variable.name()) && isInput(variable);
            String type = variable.type();
            boolean matrix = CORE_MATRICES.contains(variable.name()) && (type.equals("mat3") || type.equals("mat4"));
            boolean offset = variable.name().equals("chunkOffset") && type.equals("vec3");
            // a served uniform's initializer goes with it, as in withoutCoreDeclarations
            boolean uniform = variable.qualified("uniform") && (matrix || offset) && variable.unarrayed();
            if (input && plainInput(variable) || uniform) removed.add(variable);
        }
        return GlslDeclarations.apply(source, GlslDeclarations.removeVariables(tree, removed));
    }

    private static String renameMain(String source) {
        String renamed = GlslDeclarations.renameMain(source, "chimeraLineMain");
        if (renamed == null) throw new IllegalArgumentException("line vertex requires main");
        return renamed;
    }

    /**
     * Applies Iris's line handling to an authored vertex source before its interface is planned.
     *
     * When the pack has its own gbuffers_line, IrisRenderingPipeline sets isLines and Iris compiles it through
     * VanillaCoreTransformer: the core inputs and matrices name the host values, and the program widens its own
     * line. Iris's VanillaTransformer widens a fallback program such as gbuffers_basic itself: its main runs
     * twice and gl_ModelViewMatrix carries VIEW_SHRINK.
     */
    public static String prepareLineVertex(String source, Path sourceFile, boolean packLineProgram) {
        if (source == null) return null;
        String src = expandIncludes(source, sourceFile);
        src = GlslTokenRewriter.replaceIdentifiers(src, Map.of("gl_VertexID", "chimeraLineVertexIndex"));
        if (packLineProgram) {
            src = withoutLineCoreDeclarations(src);
            return GlslScopedReferences.replace(src, Map.ofEntries(
                    Map.entry("vaPosition", "gl_Vertex.xyz"),
                    Map.entry("vaColor", "gl_Color"),
                    Map.entry("vaNormal", "gl_Normal"),
                    Map.entry("vaUV0", "vec2(0.0)"),
                    Map.entry("vaUV1", "vec2(0.0)"),
                    Map.entry("vaUV2", "vec2(0.0)"),
                    Map.entry("modelViewMatrix", "gl_ModelViewMatrix"),
                    Map.entry("modelViewMatrixInverse", "inverse(gl_ModelViewMatrix)"),
                    Map.entry("projectionMatrix", "gl_ProjectionMatrix"),
                    Map.entry("projectionMatrixInverse", "inverse(gl_ProjectionMatrix)"),
                    Map.entry("normalMatrix", "gl_NormalMatrix"),
                    Map.entry("textureMatrix", "gl_TextureMatrix[0]"),
                    Map.entry("chunkOffset", "vec3(0.0)")));
        }
        src = src.replaceAll("\\bftransform\\s*\\(\\s*\\)", "(gl_ModelViewProjectionMatrix * gl_Vertex)");
        src = GlslTokenRewriter.replaceIdentifiers(src, Map.of(
                "gl_Vertex", "(gl_Vertex + vec4(chimeraLineOffset, 0.0))",
                "gl_ModelViewMatrix", "(chimeraLineViewScale * gl_ModelViewMatrix)",
                "gl_ModelViewProjectionMatrix", "(gl_ProjectionMatrix * chimeraLineViewScale * gl_ModelViewMatrix)",
                // Iris feeds a widened program +Z; only the wrapper reads the line direction
                "gl_Normal", "vec3(0.0, 0.0, 1.0)"));
        src = renameMain(src);
        String globals = GlslVertexPreambles.LINE_WIDEN_GLOBALS;
        Set<String> declared = new HashSet<>();
        InterfacePlanner.scanUniformDeclarations(stripComments(src)).forEach(value -> declared.add(value.name()));
        if (!declared.contains("viewWidth")) globals += "uniform float viewWidth;\n";
        if (!declared.contains("viewHeight")) globals += "uniform float viewHeight;\n";
        return insertAfterHeader(src, globals) + GlslVertexPreambles.LINE_WIDEN_MAIN;
    }

    /**
     * Applies Iris's glint handling to an authored vertex before its interface is planned, so the glint strength
     * is an ordinary planned uniform. For GLINT, VanillaTransformer reads gl_Color as
     * {@code vec4(ColorModulator.rgb, ColorModulator.a * GlintAlpha)}.
     */
    public static String prepareGlintVertex(String source, Path sourceFile) {
        if (source == null) return null;
        String src = expandIncludes(source, sourceFile);
        src = GlslTokenRewriter.replaceIdentifiers(src, Map.of("gl_Color",
                "vec4(gl_Color.rgb, gl_Color.a * " + UniformRegistry.GLINT_ALPHA + ")"));
        return insertAfterHeader(src, "uniform float " + UniformRegistry.GLINT_ALPHA + ";\n");
    }

    /**
     * Expands a host-layout stage's includes before its interface is planned; the line and
     * glint vertices do this in {@link #prepareLineVertex} and {@link #prepareGlintVertex}.
     */
    public static String prepareHostLayoutSource(String source, Path sourceFile) {
        return source == null ? null : expandIncludes(source, sourceFile);
    }

    /**
     * Folds entityId and blockEntityId into every stage of a host-layout program before planning. Iris applies
     * EntityPatcher only to entity and text formats; on any other layout the two stay CommonUniforms uniforms.
     *
     * blockEntityId is the constant -1. entityId is Iris's fallback for lightning, where attributes cannot carry
     * it, read from CapturedRenderingState when the batch draws. Here it is the per-draw
     * {@link UniformRegistry#HOST_ENTITY_ID}, 0 except while a lightning bolt draws.
     */
    public static String prepareHostLayoutIdentity(String source) {
        if (source == null) return null;
        Map<String, String> types = new HashMap<>();
        for (EntityDeclarations.IdentityDeclaration declaration
                : EntityDeclarations.identityDeclarations(stripComments(source))) {
            for (String name : declaration.names()) {
                if (EntityIdRewrites.ENTITY_ID_NAMES.contains(name)) types.put(name, declaration.type());
            }
        }
        if (types.isEmpty()) return source;
        String src = EntityDeclarations.removeEntityIdDeclarations(source);
        Map<String, String> replacements = new HashMap<>();
        String entityId = UniformRegistry.HOST_ENTITY_ID;
        if (types.containsKey("entityId")) {
            replacements.put("entityId", "float".equals(types.get("entityId")) ? "float(" + entityId + ")"
                    : "(" + entityId + ")");
        }
        if (types.containsKey("blockEntityId")) {
            replacements.put("blockEntityId", "float".equals(types.get("blockEntityId")) ? "(-1.0)" : "(-1)");
        }
        // CommonUniforms registers currentRenderedItemId as the constant -1, not CapturedRenderingState's item
        if (types.containsKey("currentRenderedItemId")) {
            replacements.put("currentRenderedItemId",
                    "float".equals(types.get("currentRenderedItemId")) ? "(-1.0)" : "(-1)");
        }
        // Iris leaves the uniforms named, so a parameter or local of the same name keeps its own
        String folded = GlslScopedReferences.replace(src, replacements);
        return types.containsKey("entityId") ? insertAfterHeader(folded, "uniform int " + entityId + ";\n") : folded;
    }

    static String expandIncludes(String source, Path sourceFile) {
        if (sourceFile == null) return source;
        ShaderSourcePreprocessor.Result prepared = ShaderSourcePreprocessor.prepare(
                sourceFile.getParent(), sourceFile, source,
                PackEngineDefines.forPack(Map.of()),
                PackEngineDefines.lockedNames(Set.of(), false));
        if (!prepared.successful()) {
            throw new IllegalArgumentException(prepared.deviations().toString());
        }
        return prepared.source();
    }

    /**
     * Declares Iris's {@code mc_chunkFade} as 1.0 in a Sodium-patch vertex stage that reads it. Iris's
     * SodiumTransformer.injectVertInit declares it in every terrain and shadow terrain vertex stage, core or not,
     * as the section's fade-in fraction, 1.0 for a section not fading; VulkanMod fades no section in.
     */
    static String withChunkFade(String vertex) {
        if (vertex == null || !GlslTokenRewriter.containsIdentifier(vertex, "mc_chunkFade")) return vertex;
        return insertAfterHeader(vertex, "float mc_chunkFade = 1.0;\n");
    }

    /**
     * Inserts declarations after the #version and #extension lines that must lead the source, and
     * the blank lines among them: after the last of the leading lines that end in a line break.
     */
    static String insertAfterHeader(String source, String declarations) {
        Map<Integer, GlslDirectives.Line> directives = new HashMap<>();
        for (GlslDirectives.Line line : GlslDirectives.lines(source)) directives.put(line.hash(), line);
        int insert = 0;
        for (int lineEnd = source.indexOf('\n'); lineEnd >= 0; lineEnd = source.indexOf('\n', insert)) {
            int text = insert;
            while (text < lineEnd && (source.charAt(text) == ' ' || source.charAt(text) == '\t')) text++;
            GlslDirectives.Line directive = directives.get(text);
            boolean blank = text == lineEnd || text == lineEnd - 1 && source.charAt(text) == '\r';
            boolean header = directive != null && directive.adjacent()
                    && (directive.is("version") || directive.is("extension"));
            if (!blank && !header) break;
            insert = lineEnd + 1;
        }
        return source.substring(0, insert) + declarations + source.substring(insert);
    }
}
