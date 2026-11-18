// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * gbufferModelView and gbufferModelViewInverse are the pack's uniforms in every world lane, as in
 * Iris. Ebin rebuilds positions through them, and an enchanted item in a frame draws its glint
 * with depth test EQUAL over the item: the two lanes must place each vertex bit for bit, so neither
 * computes its own inverse of ModelViewMat on the GPU. The same expression must also evaluate the
 * same in both programs, which GLSL promises only for an invariant position. A stage that projects
 * with the pack's gbufferProjection writes GL's clip range.
 */
final class WorldMatrixLaneTest {
    private static final String CONVERSION = "gl_Position.z = 0.5 * (gl_Position.z + gl_Position.w);";
    private static final String POSITION = "vec3 world = mat3(gbufferModelViewInverse) * (ModelViewMat * ";
    private static final String VERTEX = """
            #version 330 compatibility
            uniform mat4 gbufferModelView;
            uniform mat4 gbufferModelViewInverse;
            uniform mat4 gbufferProjection;
            out vec4 color;
            void main() {
                color = gl_Color;
                vec3 world = mat3(gbufferModelViewInverse) * (gl_ModelViewMatrix * gl_Vertex).xyz;
                gl_Position = gbufferProjection * vec4(mat3(gbufferModelView) * world, 1.0);
            }
            """;
    private static final String FRAGMENT = """
            #version 330 compatibility
            /* DRAWBUFFERS:0 */
            in vec4 color;
            void main() {
                gl_FragData[0] = color;
            }
            """;
    /** The entity and glint vertex Insanity shares with BSL: the position is plain ftransform(). */
    private static final String FTRANSFORM_VERTEX = """
            #version 120
            varying vec4 color;
            void main() {
                color = gl_Color;
                gl_Position = ftransform();
            }
            """;
    private static final String LEGACY_FRAGMENT = """
            #version 120
            /* DRAWBUFFERS:0 */
            varying vec4 color;
            void main() {
                gl_FragData[0] = color;
            }
            """;

    /**
     * An item and its glint both write ftransform() on the same inputs, which NVIDIA can still place
     * one ulp apart, speckling the glint. Every lane an item or its glint draws on declares its
     * position invariant ahead of any use, the hand's glint included.
     */
    @Test
    void itemAndGlintPositionsAreInvariant(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        String[] programs = {"gbuffers_entities", "gbuffers_block", "gbuffers_hand", "gbuffers_armor_glint"};
        for (String program : programs) {
            Files.writeString(shaders.resolve(program + ".vsh"), FTRANSFORM_VERTEX);
            Files.writeString(shaders.resolve(program + ".fsh"), LEGACY_FRAGMENT);
        }
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        for (String program : programs) {
            assertInvariantPosition(program, convertedVertex(analysis, program));
        }
        assertInvariantPosition("hand glint",
                EntityVertexBridge.handGlintVertex(convertedVertex(analysis, "gbuffers_armor_glint")));
        var compiled = PackCompileTest.run(root, analysis);
        assertTrue(compiled.failures().isEmpty(), "compilation: " + compiled.failures());
    }

    @Test
    void itemGlintAndCloudsKeepThePackMatrices(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        String[] programs = {"gbuffers_entities", "gbuffers_armor_glint", "gbuffers_clouds"};
        for (String program : programs) {
            Files.writeString(shaders.resolve(program + ".vsh"), VERTEX);
            Files.writeString(shaders.resolve(program + ".fsh"), FRAGMENT);
        }
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        String item = positionLine(analysis, "gbuffers_entities");
        assertEquals(item, positionLine(analysis, "gbuffers_armor_glint"),
                "the glint must place the item's vertices with the same expression");
        assertEquals(item, positionLine(analysis, "gbuffers_clouds"),
                "clouds' ModelViewMat carries their offset, never gbufferModelView");
        var compiled = PackCompileTest.run(root, analysis);
        assertTrue(compiled.failures().isEmpty(), "compilation: " + compiled.failures());
    }

    /**
     * A world stage that projects with the pack's gbufferProjection (Ebin's and Glimmer's
     * gbuffers_main) writes GL's clip range and converts it once, after its authored entry point,
     * with the host built-ins it reads taken into GL's range too. A stage that projects with
     * ftransform() and reads no pack projection keeps the host's matrices and depth.
     */
    @Test
    void packProjectionStages(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        String projected = """
                #version 330 compatibility
                uniform mat4 gbufferProjection;
                out vec4 color;
                void main() {
                    color = gl_Color;
                    gl_Position = gbufferProjection * (gl_ModelViewMatrix * gl_Vertex);
                }
                """;
        for (String program : new String[] {"gbuffers_terrain", "gbuffers_entities", "gbuffers_armor_glint"}) {
            Files.writeString(shaders.resolve(program + ".vsh"), projected);
            Files.writeString(shaders.resolve(program + ".fsh"), FRAGMENT);
        }
        // reads the pack projection beside ftransform(): the host built-ins take GL's range too
        Files.writeString(shaders.resolve("gbuffers_block.vsh"), """
                #version 330 compatibility
                uniform mat4 gbufferProjection;
                out vec4 color;
                void main() {
                    color = gl_Color * gbufferProjection[1][1] + gl_ProjectionMatrix[2];
                    gl_Position = ftransform();
                }
                """);
        Files.writeString(shaders.resolve("gbuffers_block.fsh"), FRAGMENT);
        // declares the pack projection and never reads it: the host's range, unchanged
        Files.writeString(shaders.resolve("gbuffers_textured.vsh"), """
                #version 330 compatibility
                uniform mat4 gbufferProjection;
                out vec4 color;
                void main() {
                    color = gl_Color;
                    gl_Position = ftransform();
                }
                """);
        Files.writeString(shaders.resolve("gbuffers_textured.fsh"), FRAGMENT);

        PackProbe.Analysis analysis = PackProbe.analyze(root);
        for (String program : new String[] {"gbuffers_terrain", "gbuffers_entities", "gbuffers_armor_glint"}) {
            String vertex = convertedVertex(analysis, program);
            assertTrue(vertex.contains("void " + WorldClipRange.AUTHORED_ENTRY + "()")
                            && vertex.contains(WorldClipRange.AUTHORED_ENTRY + "();\n    " + CONVERSION)
                            && occurrences(vertex, "gl_Position.z = ") == 1,
                    program + " must convert GL's clip depth once, after its entry point: " + vertex);
        }
        String block = convertedVertex(analysis, "gbuffers_block");
        assertTrue(block.contains("gl_Position = chimeraGlClip(chimeraEntityFtransform());")
                        && block.contains("chimeraGlClip(ProjMat)[2]"),
                "the host built-ins of a stage in GL's range must take GL's range: " + block);
        String textured = convertedVertex(analysis, "gbuffers_textured");
        assertTrue(!textured.contains("chimeraGlClip") && !textured.contains(WorldClipRange.AUTHORED_ENTRY),
                "a stage that never reads a pack projection keeps the host's range: " + textured);

        // the held glint keeps its one conversion and takes the hand family's ftransform()
        String glint = convertedVertex(analysis, "gbuffers_armor_glint");
        String hand = EntityVertexBridge.handGlintVertex(glint);
        assertTrue(hand.contains("chimeraHandProjection() * ModelViewMat")
                        && occurrences(hand, "gl_Position.z = ") == 1
                        && !hand.contains("chimeraGlClip(chimeraEntityFtransform())"),
                "hand glint in GL's range must not convert twice: " + hand);
        var compiled = PackCompileTest.run(root, analysis);
        assertTrue(compiled.failures().isEmpty(), "compilation: " + compiled.failures());
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) count++;
        return count;
    }

    /**
     * RenderPearl's terrain clamps gl_ProjectionMatrix * view to the frustum and unprojects it with
     * gbufferProjectionInverse alone: reading the inverse takes the stage into GL's range too.
     */
    @Test
    void anInverseProjectionStageRunsInGlRange(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("gbuffers_water.vsh"), """
                #version 330 compatibility
                uniform mat4 gbufferProjectionInverse;
                out vec4 color;
                void main() {
                    vec4 clip = gl_ProjectionMatrix * (gl_ModelViewMatrix * gl_Vertex);
                    color = gbufferProjectionInverse * clamp(clip / clip.w, vec4(-1.0, -1.0, 0.0, 1.0), vec4(1.0));
                    gl_Position = clip;
                }
                """);
        Files.writeString(shaders.resolve("gbuffers_water.fsh"), FRAGMENT);
        String water = convertedVertex(PackProbe.analyze(root), "gbuffers_water");
        assertTrue(water.contains("chimeraGlClip(") && water.contains(WorldClipRange.AUTHORED_ENTRY + "();\n    "
                + CONVERSION) && occurrences(water, "gl_Position.z = ") == 1,
                "a stage that unprojects with gbufferProjectionInverse must run in GL's range: " + water);
    }

    /**
     * I Like Vanilla's isometric projection scales the view position by constants and reads no
     * projection at all: its clip depth is GL's, as under Iris, and converts once, held glint too. A
     * stage whose only projection is one host clip built-in keeps the host's range, a core-profile
     * one too: IrisCoreProfile names its projectionMatrix gl_ProjectionMatrix before the range is read.
     */
    @Test
    void aStageThatProjectsItselfRunsInGlRange(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        String isometric = """
                #version 330 compatibility
                out vec4 color;
                void main() {
                    color = gl_Color;
                    vec3 view = (gl_ModelViewMatrix * gl_Vertex).xyz;
                    gl_Position = vec4(view * vec3(1.0 / 32.0, 1.0 / 32.0, -1.0 / 256.5) - vec3(0.0, 0.0, 0.75), 1.0);
                }
                """;
        for (String program : new String[] {"gbuffers_terrain", "gbuffers_entities", "gbuffers_armor_glint"}) {
            Files.writeString(shaders.resolve(program + ".vsh"), isometric);
            Files.writeString(shaders.resolve(program + ".fsh"), FRAGMENT);
        }
        Files.writeString(shaders.resolve("gbuffers_water.vsh"), """
                #version 330 core
                uniform mat4 modelViewMatrix;
                uniform mat4 projectionMatrix;
                uniform vec3 chunkOffset;
                in vec3 vaPosition;
                in vec4 vaColor;
                out vec4 color;
                void main() {
                    color = vaColor;
                    gl_Position = projectionMatrix * (modelViewMatrix * vec4(vaPosition + chunkOffset, 1.0));
                }
                """);
        Files.writeString(shaders.resolve("gbuffers_water.fsh"), FRAGMENT);
        Files.writeString(shaders.resolve("gbuffers_textured.vsh"), """
                #version 330 compatibility
                out vec4 color;
                void main() {
                    color = gl_Color;
                    gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
                }
                """);
        Files.writeString(shaders.resolve("gbuffers_basic.vsh"), """
                #version 330 compatibility
                out vec4 color;
                void main() {
                    color = gl_Color;
                    gl_Position = gl_ProjectionMatrix * (gl_ModelViewMatrix * gl_Vertex);
                }
                """);
        Files.writeString(shaders.resolve("gbuffers_textured.fsh"), FRAGMENT);
        Files.writeString(shaders.resolve("gbuffers_basic.fsh"), FRAGMENT);

        PackProbe.Analysis analysis = PackProbe.analyze(root);
        for (String program : new String[] {"gbuffers_terrain", "gbuffers_entities", "gbuffers_armor_glint"}) {
            String vertex = convertedVertex(analysis, program);
            assertTrue(vertex.contains(WorldClipRange.AUTHORED_ENTRY + "();\n    " + CONVERSION)
                            && occurrences(vertex, "gl_Position.z = ") == 1,
                    program + " builds GL's clip depth and must convert it once: " + vertex);
        }
        String hand = EntityVertexBridge.handGlintVertex(convertedVertex(analysis, "gbuffers_armor_glint"));
        assertTrue(occurrences(hand, "gl_Position.z = ") == 1, "held glint must convert once: " + hand);
        for (String program : new String[] {"gbuffers_textured", "gbuffers_basic", "gbuffers_water"}) {
            String vertex = convertedVertex(analysis, program);
            assertTrue(!vertex.contains("chimeraGlClip") && !vertex.contains(WorldClipRange.AUTHORED_ENTRY),
                    program + " projects with a host clip built-in and keeps the host's range: " + vertex);
        }
        var compiled = PackCompileTest.run(root, analysis);
        assertTrue(compiled.failures().isEmpty(), "compilation: " + compiled.failures());
    }

    private static String convertedVertex(PackProbe.Analysis analysis, String name) {
        PackProgramPlan plan = analysis.plan().program(name);
        assertTrue(plan != null && plan.executable() && plan.convertedVertex() != null,
                name + " rejected: " + (plan == null ? "missing" : plan.deviations()));
        return plan.convertedVertex();
    }

    /** The stage declares gl_Position invariant, and the declaration is its first mention. */
    private static void assertInvariantPosition(String name, String vertex) {
        int declaration = vertex.indexOf(EntityVertexBridge.INVARIANT_POSITION);
        assertTrue(declaration >= 0 && vertex.indexOf("gl_Position") == declaration + "invariant ".length(),
                name + " must declare its position invariant before using it: " + vertex);
    }

    private static String positionLine(PackProbe.Analysis analysis, String name) {
        String vertex = convertedVertex(analysis, name);
        assertTrue(!vertex.contains("inverse(ModelViewMat)") && vertex.contains("mat3(gbufferModelView) * world"),
                name + " must read the pack's world matrices: " + vertex);
        int start = vertex.indexOf(POSITION);
        assertTrue(start >= 0, name + " lost the authored position: " + vertex);
        return vertex.substring(start, vertex.indexOf(';', start));
    }
}
