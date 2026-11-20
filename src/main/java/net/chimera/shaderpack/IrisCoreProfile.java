// SPDX-License-Identifier: LGPL-3.0-only

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
        /** A pack's own gbuffers_line: VANILLA in core mode whatever its versions, as TransformPatcher's isLine. */
        LINES
    }

    /** The Iris patch that compiles a program; shadow entities and host-layout shadow lanes are VANILLA. */
    static Patch patchFor(String program, UniformRegistry.Stage stage, boolean packLine) {
        if (stage == UniformRegistry.Stage.POST) return Patch.COMPOSITE;
        if (packLine) return Patch.LINES;
        FamilyAdapterPlan.Family family = FamilyAdapterRegistry.forProgram(program).family();
        return family == FamilyAdapterPlan.Family.TERRAIN || family == FamilyAdapterPlan.Family.WATER
                || family == FamilyAdapterPlan.Family.SHADOW ? Patch.SODIUM : Patch.VANILLA;
    }

    /**
     * Whether Iris's TransformPatcher compiles a stage in core-profile mode: a core profile, or 150 and above
     * with no profile. Pass the source as written, since the modern normalisations drop the version line.
     */
    static boolean coreMode(String source) {
        GlslVersion version = GlslVersion.first(source);
        return version != null && version.irisCore();
    }

    /**
     * The core uniforms Iris's core-profile transformers give one stage. Outside core mode and a pack's own
     * gbuffers_line none is served, so a declared one is an ordinary uniform Iris never sets.
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
     * Rewrites the names Iris's core-profile mode serves to the compatibility built-ins, which every converter
     * serves with the same values, before the stage's interface is planned. A declared core input gets its
     * intended value even outside core mode, where Iris leaves it unbound.
     *
     * chunkOffset is zero, since Chimera's positions are already camera-relative. {@code overlay} is what vaUV1
     * reads. On a format without UVs, vaUV0 and vaUV2 read the compatibility defaults where Iris reads zero.
     */
    public static String prepareIrisCoreProfile(String source, Path sourceFile, boolean vertexStage,
                                                String overlay, boolean coreMode, Patch patch) {
        if (source == null) return null;
        Set<String> served = servedUniforms(patch, vertexStage, coreMode);
        Map<String, String> declared = new TreeMap<>();
        String result = withoutCoreDeclarations(expandIncludes(source, sourceFile), vertexStage, served, declared);
        if (coreMode) {
            // the source is macro-expanded as Iris's is, so a core name defined as a macro has expanded away
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
        // a same-named parameter or local keeps its meaning, as in Iris, except that Iris's composite vertex
        // stage replaces a same-named parameter's uses too
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

    /**
     * The values a pack's own gbuffers_line reads for the core names on the line layout. The layout has no UVs,
     * so the UV inputs read zero, and vaNormal reads the segment's direction the layout stores as its normal.
     */
    private static final Map<String, String> LINE_CORE_NAMES = Map.ofEntries(
            Map.entry("vaPosition", "gl_Vertex.xyz"), Map.entry("vaColor", "gl_Color"),
            Map.entry("vaNormal", "gl_Normal"), Map.entry("vaUV0", "vec2(0.0)"), Map.entry("vaUV1", "vec2(0.0)"),
            Map.entry("vaUV2", "vec2(0.0)"), Map.entry("modelViewMatrix", "gl_ModelViewMatrix"),
            Map.entry("modelViewMatrixInverse", "inverse(gl_ModelViewMatrix)"),
            Map.entry("projectionMatrix", "gl_ProjectionMatrix"),
            Map.entry("projectionMatrixInverse", "inverse(gl_ProjectionMatrix)"),
            Map.entry("normalMatrix", "gl_NormalMatrix"), Map.entry("textureMatrix", "gl_TextureMatrix[0]"),
            Map.entry("chunkOffset", "vec3(0.0)"));

    /** The name the pack's main takes once the widened line's entry point claims {@code main}. */
    static final String PACK_LINE_MAIN = "chimeraPackLineMain";

    /**
     * What the pack's vertex code reads while Chimera widens its line: the vertex moved by the run's offset,
     * the model view shrunk toward the eye, and a +Z normal.
     */
    private static final Map<String, String> WIDENED_LINE_BUILTINS = Map.of(
            "gl_Vertex", "(gl_Vertex + vec4(chimeraLineOffset, 0.0))",
            "gl_ModelViewMatrix", "(chimeraLineShrink * gl_ModelViewMatrix)",
            "gl_ModelViewProjectionMatrix", "(gl_ProjectionMatrix * chimeraLineShrink * gl_ModelViewMatrix)",
            "gl_Normal", "vec3(0.0, 0.0, 1.0)",
            "ftransform", "chimeraLineFtransform");

    /**
     * The widened line's globals. The offset moves the vertex to the far point, or is zero for its own; the
     * 255/256 shrink keeps the line's place on screen but just in front of the face it outlines.
     */
    private static final String LINE_GLOBALS = """
            vec3 chimeraLineOffset;
            const mat4 chimeraLineShrink = mat4(mat3(255.0 / 256.0));
            """;

    /** Fixed-function ftransform(), declared where the pack calls it and read through the widened built-ins. */
    private static final String LINE_FTRANSFORM = """
            vec4 chimeraLineFtransform() {
                return gl_ModelViewProjectionMatrix * gl_Vertex;
            }
            """;

    /**
     * The widened line's entry point. The pack's code runs from the far point for its clip position, then from
     * the vertex's own point for every output; the vertex then moves half the line width across the screen
     * direction, to the side its index picks.
     */
    private static final String LINE_ENTRY = """

            vec4 chimeraWidenLine(vec4 vertexClip, vec4 farClip) {
                vec2 screen = vec2(viewWidth, viewHeight);
                vec2 vertexNdc = vertexClip.xy / vertexClip.w;
                vec2 along = normalize((farClip.xy / farClip.w - vertexNdc) * screen);
                vec2 offset = vec2(-along.y, along.x) * chimeraLineWidth / screen;
                // the even vertex always moves toward +x, whichever way the segment runs
                if (offset.x < 0.0) offset = -offset;
                if ((chimeraLineVertexIndex & 1) == 1) offset = -offset;
                return vertexClip + vec4(offset * vertexClip.w, 0.0, 0.0);
            }

            void main() {
                chimeraLineOffset = chimeraLineDirection;
                %1$s();
                vec4 farClip = gl_Position;
                gl_Position = vec4(0.0);
                chimeraLineOffset = vec3(0.0);
                %1$s();
                gl_Position = chimeraWidenLine(gl_Position, farClip);
            }
            """.formatted(PACK_LINE_MAIN);

    /**
     * Prepares the vertex stage of a program on the host line layout, before its interface is planned. A pack's
     * own gbuffers_line ({@code packLineProgram}) widens its own line from the core names; any other program is
     * widened as Iris widens it ({@link #widenLine}).
     */
    public static String prepareLineVertex(String source, Path sourceFile, boolean packLineProgram) {
        if (source == null) return null;
        String src = GlslTokenRewriter.replaceIdentifiers(expandIncludes(source, sourceFile),
                Map.of("gl_VertexID", "chimeraLineVertexIndex"));
        if (!packLineProgram) return widenLine(src);
        // a parameter or local named like a core name keeps its own meaning
        return GlslScopedReferences.replace(withoutLineCoreDeclarations(src), LINE_CORE_NAMES);
    }

    /**
     * Widens a line drawn by a program that does not widen its own. The line must stay the host's width in
     * pixels after the pack's own transform, which may bend or jitter it, so both ends go through the pack's
     * code ({@link #LINE_ENTRY}).
     */
    private static String widenLine(String src) {
        String renamed = GlslDeclarations.renameMain(src, PACK_LINE_MAIN);
        if (renamed == null) throw new IllegalArgumentException("line vertex stage has no main");
        String stripped = stripComments(src);
        Set<String> declared = new HashSet<>();
        InterfacePlanner.scanUniformDeclarations(stripped).forEach(uniform -> declared.add(uniform.name()));
        StringBuilder globals = new StringBuilder();
        for (String size : List.of("viewWidth", "viewHeight")) {
            if (!declared.contains(size)) globals.append("uniform float ").append(size).append(";\n");
        }
        globals.append(LINE_GLOBALS);
        if (GlslTokenRewriter.containsIdentifier(stripped, "ftransform")) globals.append(LINE_FTRANSFORM);
        // the helper goes in before the rewrite, so its built-ins read as the pack's do
        String widened = GlslTokenRewriter.replaceIdentifiers(insertAfterHeader(renamed, globals.toString()),
                WIDENED_LINE_BUILTINS);
        return widened + LINE_ENTRY;
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
     * Folds entityId and blockEntityId into every stage of a host-layout program before planning, where Iris
     * keeps them CommonUniforms uniforms. blockEntityId is -1; entityId is {@link UniformRegistry#HOST_ENTITY_ID},
     * 0 except while a lightning bolt draws.
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
     * Declares Iris's {@code mc_chunkFade}, the section fade-in fraction, as 1.0 in a Sodium-patch vertex stage
     * that reads it; VulkanMod fades no section in.
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
