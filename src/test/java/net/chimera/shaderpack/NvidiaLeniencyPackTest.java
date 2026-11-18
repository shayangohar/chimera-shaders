// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The NVIDIA leniencies also apply to the stages the bridges emit.
 *
 * Body Camera's terrain compares mc_Entity, which the terrain bridge reads as a constructor, with a number
 * (VectorScalarComparisons). Pixel Perfect's composites loop to an array's bare length (ArrayLengthCalls).
 * Each program compiles as the NVIDIA driver compiles it under Iris.
 */
final class NvidiaLeniencyPackTest {
    @Test
    void bodyCameraAndPixelPerfectFormsCompileThroughTheBridges(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("gbuffers_terrain.vsh"), """
                #version 330 compatibility
                in vec3 mc_Entity;
                out vec4 color;
                void main() {
                    color = (mc_Entity==1.) ? vec4(1.0) : gl_Color;
                    gl_Position = ftransform();
                }
                """);
        Files.writeString(shaders.resolve("gbuffers_terrain.fsh"), """
                #version 330 compatibility
                /* DRAWBUFFERS:0 */
                in vec4 color;
                void main() {
                    gl_FragData[0] = color;
                }
                """);
        Files.writeString(shaders.resolve("composite.vsh"), """
                #version 330 compatibility
                out vec2 uv;
                void main() {
                    uv = gl_MultiTexCoord0.xy;
                    gl_Position = ftransform();
                }
                """);
        Files.writeString(shaders.resolve("composite.fsh"), """
                #version 330 compatibility
                /* DRAWBUFFERS:0 */
                uniform sampler2D colortex0;
                in vec2 uv;
                const vec2 offsets[2] = vec2[2](vec2(0.001), vec2(-0.001));
                void main() {
                    vec3 sum = vec3(0.0);
                    for (int i = 0; i < offsets.length; i++) sum += texture2D(colortex0, uv + offsets[i]).rgb;
                    gl_FragData[0] = vec4(sum, 1.0);
                }
                """);

        PackProbe.Analysis analysis = PackProbe.analyze(root);
        for (String name : new String[] {"gbuffers_terrain", "composite"}) {
            PackProgramPlan plan = analysis.plan().program(name);
            assertTrue(plan != null && plan.executable(), name + " rejected: "
                    + (plan == null ? "missing" : plan.deviations()));
        }
        String terrain = analysis.plan().program("gbuffers_terrain").convertedVertex();
        assertTrue(terrain.contains("(vec3(chimeraMcEntityValue(), 0.0)==1.)"),
                "the terrain bridge's mc_Entity, as the leniency meets it: " + terrain);
        var compiled = PackCompileTest.run(root, analysis);
        assertTrue(compiled.failures().isEmpty(), "compilation: " + compiled.failures());
    }
}
