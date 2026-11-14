package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The defects the 2026-11-13 corpus widening found: each a popular pack, at default settings, that
 * ran a program Iris 1.21.1 runs as a fallback or not at all.
 */
final class CorpusBreadthFixesTest {
    /**
     * AstraLex derives {@code uniform.float.velocity} from the camera's motion. Iris 1.21.1 never
     * registers its HardcodedCustomUniforms, so velocity is no built-in there and the pack's own value
     * builds; Chimera listed it as one and refused 13 programs that read it.
     */
    @Test
    void astraLexVelocityIsThePacksOwnUniform() {
        PackRuntimeSettings settings = PackRuntimeSettings.build(List.of(
                PackRuntimeSettings.declaration(false, "float", "dx", "cameraPosition.x - previousCameraPosition.x"),
                PackRuntimeSettings.declaration(false, "float", "dy", "cameraPosition.y - previousCameraPosition.y"),
                PackRuntimeSettings.declaration(false, "float", "dz", "cameraPosition.z - previousCameraPosition.z"),
                PackRuntimeSettings.declaration(true, "float", "velocity", "sqrt(dx * dx + dy * dy + dz * dz)")),
                List.of());
        assertTrue(settings.rejected().isEmpty(), settings.rejected().toString());
        assertTrue(settings.customDescriptors().containsKey("velocity")
                        && settings.customDescriptors().get("velocity").availability()
                        != UniformRegistry.Availability.REJECTED,
                "velocity builds as the pack's own uniform: " + settings.customDescriptors().get("velocity"));
    }

    /**
     * BSL Classic defines texture2DShadow twice, on sampler2D and on sampler2DShadow, and calls it on
     * its sampler2DShadow shadowtex0. Iris leaves both to GLSL's overload resolution. Chimera deleted
     * the sampler2DShadow overload, the other kept the call, and 17 programs failed to compile; only
     * the helper's own shadow2D call is a legacy lookup, on its parameter's type.
     */
    @Test
    void bslClassicShadowOverloadsStay() {
        String source = """
                #version 120
                uniform sampler2DShadow shadowtex0;
                float texture2DShadow(sampler2D shadowtex, vec3 shadowPos) {
                    return step(shadowPos.z, texture2D(shadowtex, shadowPos.xy).r);
                }
                float texture2DShadow(sampler2DShadow shadowtex, vec3 shadowPos) {
                    return shadow2D(shadowtex, shadowPos).x;
                }
                void main() { gl_FragColor = vec4(texture2DShadow(shadowtex0, vec3(0.5))); }
                """;
        String rewritten = GlslTokenRewriter.rewriteShadowCalls(source, Map.of("shadowtex0", "sampler2DShadow"));
        assertTrue(rewritten.contains("float texture2DShadow(sampler2DShadow shadowtex, vec3 shadowPos)")
                        && rewritten.contains("return vec4(texture(shadowtex, shadowPos)).x;")
                        && rewritten.contains("texture2DShadow(shadowtex0, vec3(0.5))"),
                "both overloads stay, the helper's lookup reads its parameter:\n" + rewritten);

        // The whole fragment conversion keeps both overloads too: it used to delete the second.
        PostTargetPlan targetPlan = PostTargetPlan.parse("composite", source).plan();
        String converted = FragmentConverter.convertPostFragment(source, null,
                InterfacePlanner.planPreparedPost(source, targetPlan), targetPlan);
        assertTrue(converted != null
                        && converted.contains("float texture2DShadow(sampler2D shadowtex, vec3 shadowPos)")
                        && converted.contains("float texture2DShadow(sampler2DShadow shadowtex, vec3 shadowPos)"),
                "the converter keeps both overloads:\n" + converted);
    }

    /**
     * Pixel Perfect is GLSL 330, so Iris's core names apply, and its toViewspace(in mat4
     * projectionMatrix, in mat4 modelViewMatrix, ...) names parameters after two of them. Iris's
     * rename moves each parameter and its uses together to a fresh name, so they keep their meaning;
     * Chimera replaced the parameters with built-ins, and the parameter list stopped parsing in almost
     * every vertex stage. A pack's line program takes the same scoped replacement.
     */
    @Test
    void pixelPerfectCoreNameParametersKeepTheirNames() {
        String vertex = """
                #version 330 compatibility
                uniform mat4 projectionMatrix;
                uniform mat4 modelViewMatrix;
                in vec3 vaPosition;
                vec4 toViewspace(in mat4 projectionMatrix, in mat4 modelViewMatrix, in vec3 position) {
                    return projectionMatrix * modelViewMatrix * vec4(position, 1.0);
                }
                void main() { gl_Position = toViewspace(projectionMatrix, modelViewMatrix, vaPosition); }
                """;
        String core = IrisCoreProfile.prepareIrisCoreProfile(vertex, null, true, IrisCoreProfile.NO_OVERLAY, true,
                IrisCoreProfile.Patch.VANILLA);
        GlslTree.parse(core);
        assertTrue(core.contains("toViewspace(in mat4 projectionMatrix, in mat4 modelViewMatrix, in vec3 position)")
                        && core.contains("return projectionMatrix * modelViewMatrix * vec4(position, 1.0);"),
                "the parameters keep their names:\n" + core);
        assertTrue(core.contains("toViewspace(gl_ProjectionMatrix, gl_ModelViewMatrix, gl_Vertex.xyz)"),
                "the globals are renamed:\n" + core);

        String line = IrisCoreProfile.prepareLineVertex(vertex, null, true);
        GlslTree.parse(line);
        assertTrue(line.contains("toViewspace(in mat4 projectionMatrix, in mat4 modelViewMatrix, in vec3 position)")
                        && line.contains("toViewspace(gl_ProjectionMatrix, gl_ModelViewMatrix, gl_Vertex.xyz)"),
                "a line program keeps the parameters and renames the globals:\n" + line);
    }

    /**
     * Aurora's water writes {@code #if WATERCOLOR_B = 200}, one '='. JCPP 1.4.14 evaluates up to the
     * '=', warns, and skips the rest of the line, so the branch holds for any nonzero value; Chimera
     * refused the condition and gbuffers_water fell back. Each case below is JCPP's own result, the
     * character literal aside.
     */
    @Test
    void auroraMalformedConditionsReadAsJcppReadsThem() {
        Map<String, String> macros = Map.of("WATERCOLOR_B", "160", "ZERO", "0");
        assertTrue(PreprocessorExpression.evaluate("WATERCOLOR_B = 200", macros), "the value before '=' stands");
        assertFalse(PreprocessorExpression.evaluate("ZERO = 200", macros), "a zero before '=' is false");
        assertFalse(PreprocessorExpression.evaluate("0 = 1 || 1", macros), "the rest of the line is skipped");
        assertTrue(PreprocessorExpression.evaluate("2 @ 3", macros), "any stray character stops the expression");
        assertTrue(PreprocessorExpression.evaluate("1 + = 2", macros), "a bad operand reads 0");
        assertTrue(PreprocessorExpression.evaluate("1 || (1", macros), "a group missing its ')' reads 0");
        assertTrue(PreprocessorExpression.evaluate("1 || (0 2)", macros), "only the call that wanted ')' reads 0");
        assertFalse(PreprocessorExpression.evaluate("(1 = 2) || 1", macros), "the group reads 0");
        assertFalse(PreprocessorExpression.evaluate("(1 2 + 3)", macros),
                "the token in the ')' place is not consumed: it ends the expression");
        assertTrue(PreprocessorExpression.evaluate("!(1 = 0)", macros), "a group missing its ')' reads 0 under '!'");
        assertFalse(PreprocessorExpression.evaluate("1 ? 2 = 3 : 0", macros), "a conditional missing ':' reads 0");
        assertFalse(PreprocessorExpression.evaluate("(1 ? 2 3) || 1", macros),
                "the token in the ':' place is not consumed either");
        assertFalse(PreprocessorExpression.evaluate("F(1)", macros), "an undefined name reads 0 before its '('");
        assertThrows(IllegalArgumentException.class, () -> PreprocessorExpression.evaluate("'a' == 97", macros),
                "JCPP reads a character literal as its value; Chimera refuses one rather than misread it");
    }

    /**
     * Clarity ships composite.fsh with no images beside composite.csh, which stores to a pack image.
     * Iris builds the program and the pass's compute apart (CompositeRenderer); the report judged the
     * program by the compute's image and refused it, while the plan ran it.
     */
    @Test
    void clarityPassComputeImagesDoNotRefuseTheProgram(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"),
                "image.tempCol = tempColS rgba rgba16 unsigned_short false true 1.0 1.0\n");
        Files.writeString(shaders.resolve("composite.vsh"), """
                #version 120
                varying vec2 texcoord;
                void main() {
                    gl_Position = ftransform();
                    texcoord = gl_MultiTexCoord0.xy;
                }
                """);
        Files.writeString(shaders.resolve("composite.fsh"), """
                #version 120
                uniform sampler2D colortex0;
                varying vec2 texcoord;
                void main() {
                /* DRAWBUFFERS:0 */
                    gl_FragData[0] = texture2D(colortex0, texcoord);
                }
                """);
        Files.writeString(shaders.resolve("composite.csh"), """
                #version 430
                layout(local_size_x = 8, local_size_y = 8) in;
                uniform layout(rgba16) restrict writeonly image2D tempCol;
                void main() { imageStore(tempCol, ivec2(gl_GlobalInvocationID.xy), vec4(1.0)); }
                """);
        Files.writeString(shaders.resolve("final.fsh"), """
                #version 120
                uniform sampler2D colortex0;
                void main() { gl_FragColor = texture2D(colortex0, vec2(0.5)); }
                """);
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        assertTrue(analysis.plan().shouldAttempt("composite"), "the plan runs composite");
        assertEquals(analysis.plan().shouldAttempt("composite"), analysis.report().shouldAttempt("composite"),
                "the report agrees with the plan: " + analysis.report().program("composite").deviations());
    }
}
