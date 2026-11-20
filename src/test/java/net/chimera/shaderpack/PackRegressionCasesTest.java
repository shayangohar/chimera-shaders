// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import net.chimera.render.shader.GlslCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Programs popular packs run at default settings, which Iris 1.21.1 runs as written. */
final class PackRegressionCasesTest {
    /** Iris 1.21.1 never registers HardcodedCustomUniforms, so a pack's own {@code velocity} value builds. */
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
     * Overloads of a pack's own texture2DShadow stay, as in Iris; only the shadow2D call inside one is
     * rewritten, for its parameter's type.
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

        // the whole fragment conversion keeps both overloads too
        PostTargetPlan targetPlan = PostTargetPlan.parse("composite", source).plan();
        String converted = FragmentConverter.convertPostFragment(source, null,
                InterfacePlanner.planPreparedPost(source), targetPlan);
        assertTrue(converted != null
                        && converted.contains("float texture2DShadow(sampler2D shadowtex, vec3 shadowPos)")
                        && converted.contains("float texture2DShadow(sampler2DShadow shadowtex, vec3 shadowPos)"),
                "the converter keeps both overloads:\n" + converted);
    }

    /**
     * Parameters named after Iris's core names keep their meaning: only the globals are replaced, in a pack's
     * line program too.
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
     * JCPP evaluates {@code #if WATERCOLOR_B = 200} up to the '=' and skips the rest, so the branch holds for
     * any nonzero value. Each case is JCPP's own result, the character literal aside.
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

    /** Iris builds a pass's program and compute apart, so the compute's image does not refuse the program. */
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

    private static final String DEPTH_ONLY_SHADOW = """
            #version 150 compatibility
            /* DRAWBUFFERS: */
            uniform sampler2D gtexture;
            in vec2 texcoord;
            void main() { if (texture2D(gtexture, texcoord).a < 0.1) discard; }
            """;

    /**
     * An empty DRAWBUFFERS route lets a shadow program draw depth alone. Passes refuse it, as Iris's
     * createColorFramebuffer does, and so do gbuffers programs, which need a colour attachment; final keeps
     * output 0. An empty RENDERTARGETS stays malformed, as Iris's Integer.parseInt("") fails on it.
     */
    @Test
    void anEmptyDrawBuffersRouteDrawsAShadowProgramsDepthAlone() {
        PostTargetPlan shadow = PostTargetPlan.parseShadow("shadow", DEPTH_ONLY_SHADOW, Map.of()).plan();
        assertEquals(List.of(), shadow.targetSlots());
        assertTrue(shadow.executable() && shadow.deviations().isEmpty(), shadow.deviations().toString());
        assertEquals(List.of(), PostTargetPlan.parseShadow("shadow",
                DEPTH_ONLY_SHADOW.replace("DRAWBUFFERS: */", "DRAWBUFFERS:  \n */"), Map.of()).plan().targetSlots(),
                "a blank body is empty, as Iris trims it");
        PostTargetPlan last = PostTargetPlan.parse("final", DEPTH_ONLY_SHADOW).plan();
        assertTrue(last.executable() && last.targetSlots().equals(List.of(0)), last.deviations().toString());
        PostTargetPlan writing = PostTargetPlan.parseShadow("shadow",
                DEPTH_ONLY_SHADOW.replace("discard; }", "discard; gl_FragData[0] = vec4(1.0); }"), Map.of()).plan();
        assertEquals(List.of(0), writing.discardedOutputs(), "GL drops an output with no draw buffer");
        assertEquals(List.of(), PostTargetPlan.parseShadow("shadow", "/* DRAWBUFFERS:0 */\n" + DEPTH_ONLY_SHADOW,
                Map.of()).plan().targetSlots(), "the last directive wins");
        assertEquals(List.of(1), PostTargetPlan.parseShadow("shadow", DEPTH_ONLY_SHADOW + "/* DRAWBUFFERS:1 */\n",
                Map.of()).plan().targetSlots(), "the last directive wins");
        for (PostTargetPlan refused : List.of(PostTargetPlan.parse("composite", DEPTH_ONLY_SHADOW).plan(),
                PostTargetPlan.parseShadowcomp("shadowcomp", DEPTH_ONLY_SHADOW, null).plan())) {
            assertTrue(!refused.executable() && refused.deviations().contains("POST_TARGET_ROUTE_EMPTY_UNSUPPORTED")
                    && !refused.deviations().contains("POST_TARGET_DIRECTIVE_MALFORMED"),
                    refused.programName() + " " + refused.deviations());
        }
        PostTargetPlan renderTargets = PostTargetPlan.parseShadow("shadow",
                DEPTH_ONLY_SHADOW.replace("DRAWBUFFERS:", "RENDERTARGETS:"), Map.of()).plan();
        assertTrue(!renderTargets.executable()
                && renderTargets.deviations().contains("POST_TARGET_DIRECTIVE_MALFORMED"),
                renderTargets.deviations().toString());
        GeometryOutputPlan lane = GeometryOutputPlan.parse("shadow_lines", DEPTH_ONLY_SHADOW, Map.of());
        assertTrue(lane.executable() && lane.targetSlots().isEmpty(), lane.deviations().toString());
        GeometryOutputPlan gbuffers = GeometryOutputPlan.parse("gbuffers_textured", DEPTH_ONLY_SHADOW, Map.of());
        assertTrue(!gbuffers.executable() && gbuffers.deviations().contains("GEOMETRY_TARGET_ROUTE_EMPTY_UNSUPPORTED"),
                gbuffers.deviations().toString());
    }

    /**
     * With the empty route the shadow fragments write no colour, as GL drops an output with no buffer; the
     * pass masks unwritten attachments, so each shadowcolor target keeps its contents.
     */
    @Test
    void aDepthOnlyShadowProgramWritesNoShadowColour(@TempDir Path root) throws IOException {
        List<String> fragments = List.of(DEPTH_ONLY_SHADOW,
                DEPTH_ONLY_SHADOW.replace("discard; }", "discard; gl_FragData[0] = vec4(1.0); }"),
                DEPTH_ONLY_SHADOW.replace("discard; }", "discard; gl_FragColor = vec4(1.0); }"));
        for (int index = 0; index < fragments.size(); index++) {
            Path shaders = Files.createDirectories(root.resolve("pack" + index).resolve("shaders"));
            Files.writeString(shaders.resolve("shadow.vsh"), """
                    #version 150 compatibility
                    out vec2 texcoord;
                    void main() { texcoord = gl_MultiTexCoord0.xy; gl_Position = ftransform(); }
                    """);
            Files.writeString(shaders.resolve("shadow.fsh"), fragments.get(index));
            PackPlan plan = PackProbe.analyze(shaders.getParent()).plan();
            for (String name : List.of("shadow", "shadow_entities", "shadow_lines")) {
                PackProgramPlan program = plan.program(name);
                String context = name + " of fragment " + index;
                assertTrue(program.executable() && program.convertedFragment() != null,
                        context + " " + program.deviations());
                ByteBuffer module = GlslCompiler.compile(name + ".fsh", program.convertedFragment(),
                        Shaderc.shaderc_glsl_fragment_shader, GlslCompiler::optimize);
                try {
                    assertEquals(0, SpirvOutputWrites.writtenLocations(module),
                            context + ": " + program.convertedFragment());
                } finally {
                    MemoryUtil.memFree(module);
                }
            }
        }
    }
}
