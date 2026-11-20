// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Translation cases reached through pack options a player can set on the settings screen. */
final class PackOptionCasesTest {
    /** Complementary WATER_FOG_MULT: the macro is defined inside a function body, behind an #if. */
    @Test
    void aDefineInsideAFunctionReachesTheCompiler(@TempDir Path dir) {
        String source = """
                #version 120
                #define MULT 25
                float fog(float d) {
                    #if MULT != 100
                        #define MULT_M MULT * 0.01;
                        d *= MULT_M;
                    #endif
                    return d;
                }
                void main() { gl_FragColor = vec4(fog(1.0)); }
                """;
        var result = ShaderSourcePreprocessor.prepare(dir, dir.resolve("composite1.fsh"), source,
                PackEngineDefines.forPack(Map.of()), Set.of());
        assertTrue(result.successful(), result.deviations().toString());
        assertTrue(result.source().contains("d *= 25 * 0.01;"), result.source());
    }

    /**
     * Bliss with LPV off still samples texLpv1, which Iris leaves on unit 0 beside a sampler2D; GL refuses
     * two sampler types on one unit, so Iris draws nothing and Chimera refuses the program.
     */
    @Test
    void aSamplerIrisServesNothingStillRefusesTheProgram() {
        UniformRegistry.ProgramInterface plan = InterfacePlanner.plan("""
                uniform sampler2D colortex0;
                uniform sampler3D texLpv1;
                void main() { gl_FragColor = texture2D(colortex0, vec2(0.0)) + texture3D(texLpv1, vec3(0.0)); }
                """, UniformRegistry.Stage.POST);
        assertTrue(!plan.executable(), plan.deviations().toString());
    }

    /** A post input its vertex never writes reads zero, as Iris's CompatibilityTransformer adds the output. */
    @Test
    void aPostInputNoVertexWritesReadsAsZero() {
        String vertex = """
                #version 120
                varying vec2 texcoord;
                void main() { texcoord = gl_MultiTexCoord0.xy; gl_Position = ftransform(); }
                """;
        String fragment = """
                #version 120
                varying vec2 texcoord;
                flat varying float exposure;
                void main() { gl_FragData[0] = vec4(texcoord, exposure, 1.0); }
                """;
        assertTrue(!GlslInterfaceScanner.match(GlslInterfaceScanner.scan(vertex, true),
                GlslInterfaceScanner.scan(fragment, false)).executable(), "unrepaired, the pair is refused");
        CrossStageRepair.Result repaired = CrossStageRepair.repair(vertex, fragment);
        assertEquals(List.of("VARYING_UNWRITTEN_ZEROED:exposure"), repaired.deviations());
        assertTrue(repaired.earlier().contains("flat varying float exposure;"), repaired.earlier());
        assertTrue(repaired.earlier().contains("exposure = float(0);"), repaired.earlier());
        var match = GlslInterfaceScanner.match(GlslInterfaceScanner.scan(repaired.earlier(), true),
                GlslInterfaceScanner.scan(fragment, false));
        assertTrue(match.executable(), match.deviations().toString());
        String converted = PostVertexBridge.convertPostVaryings(fragment, new PostVertexBridge.PostVaryingLayout(
                match.locations(), Map.of("texcoord", "vec2", "exposure", "float")));
        assertTrue(converted.contains("in float exposure;"), converted);
    }

    /** A local {@code float shadow} hides the global sampler only from its declaration to the end of its block. */
    @Test
    void aLocalHidesASamplerOnlyWhereItIsInScope() {
        String source = """
                #version 460
                uniform sampler2DShadow shadow;
                layout(location = 0) out vec4 color;
                void main() {
                    float lit = texture(shadow, vec3(0.5)).x;
                    {
                        float shadow = 2.0;
                        lit *= shadow;
                    }
                    color = vec4(lit);
                }
                """;
        assertTrue(GlslResourceUsage.analyze(source).referencedSamplers().contains("shadow"),
                "the read before the local's block is the sampler");
    }

    /** Iris's preprocessor reports a stray #endif and an #error and carries on, as Chimera's does. */
    @Test
    void aStrayEndifAndAnErrorDirectiveAreReportedNotFatal(@TempDir Path dir) {
        String source = """
                #version 400 compatibility
                void main() { gl_FragColor = vec4(1.0); }
                #ifndef CLOUD_SHADOWS
                #error "This program should be disabled if Cloud Shadows are disabled"
                #endif
                #endif
                """;
        var result = ShaderSourcePreprocessor.prepare(dir, dir.resolve("prepare.fsh"), source,
                PackEngineDefines.forPack(Map.of()), Set.of());
        assertTrue(result.successful(), result.deviations().toString());
        assertEquals(List.of("PREPROCESSOR_DIRECTIVE_REPORTED:error", "PREPROCESSOR_UNMATCHED_CONDITIONAL:endif"),
                result.deviations());
        assertTrue(result.deviations().stream().allMatch(ShaderSourcePreprocessor::isWarning));
        assertTrue(!result.source().contains("#error"), result.source());
    }

    /**
     * JCPP ends a directive name at the first non-word character, tests one name after #ifdef, and keeps a
     * source whose conditional is never closed.
     */
    @Test
    void directivesReadAsJcppReadsThem(@TempDir Path dir) {
        String source = """
                #version 400 compatibility
                #ifndef GUARD
                #define GUARD
                #endif//GUARD
                #ifdef GUARD > 1
                void main() { gl_FragColor = vec4(1.0); }
                #ifdef TAA
                """;
        var result = ShaderSourcePreprocessor.prepare(dir, dir.resolve("composite.fsh"), source,
                PackEngineDefines.forPack(Map.of()), Set.of());
        assertTrue(result.successful(), result.deviations().toString());
        assertEquals(List.of("PREPROCESSOR_UNMATCHED_CONDITIONAL:unterminated"), result.deviations());
        assertTrue(result.source().contains("gl_FragColor"), result.source());
    }

    /** A macro defined with no body expands to nothing, so JCPP reads "#if NAME" as false. */
    @Test
    void aBareDefineTestedByIfIsFalse(@TempDir Path dir) {
        String source = """
                #version 400 compatibility
                #define TAA_USE_YCOCG
                #if TAA_USE_YCOCG
                vec3 ycocg;
                #endif
                #ifdef TAA_USE_YCOCG
                vec3 defined;
                #endif
                void main() { gl_FragColor = vec4(1.0); }
                """;
        var result = ShaderSourcePreprocessor.prepare(dir, dir.resolve("taa.fsh"), source,
                PackEngineDefines.forPack(Map.of()), Set.of());
        assertTrue(result.successful(), result.deviations().toString());
        assertTrue(!result.source().contains("ycocg") && result.source().contains("defined"), result.source());
    }

    /**
     * A custom value Iris cannot parse, such as one ending in a semicolon, declares nothing and reads zero;
     * one Chimera cannot compile for a missing feature still fails closed.
     */
    @Test
    void aCustomValueIrisCannotParseReadsAsUnset() {
        PackRuntimeSettings settings = PackRuntimeSettings.build(List.of(
                PackRuntimeSettings.declaration(true, "float", "sandStorm", "0.0;"),
                PackRuntimeSettings.declaration(true, "float", "wrong", "sinh(frameTimeCounter)")),
                List.of());
        assertTrue(settings.rejected().containsAll(List.of("sandStorm", "wrong")), settings.rejected().toString());
        assertTrue(!settings.customDescriptors().containsKey("sandStorm"),
                "a value that does not parse is left unset, as Iris leaves it");
        assertEquals(UniformRegistry.Availability.REJECTED, settings.customDescriptors().get("wrong").availability());
    }

    /** Blanking a function no program calls keeps the directives inside it, which later code relies on. */
    @Test
    void blankingAnUnusedFunctionKeepsTheDefinesInside() {
        String source = """
                #version 460
                float unusedFog(float d) {
                    #define MULT_M 25 * 0.01;
                    return d * MULT_M;
                }
                layout(location = 0) out vec4 color;
                void main() {
                    float x = 1.0 / MULT_M
                    color = vec4(x);
                }
                """;
        String result = GlslTokenRewriter.removeUnreachableFunctions(source, GlslResourceUsage.analyze(source));
        assertTrue(!result.contains("unusedFog"), result);
        assertTrue(result.contains("#define MULT_M 25 * 0.01;"), result);
        assertTrue(PackCompileTest.compile(result, false) == null, PackCompileTest.compile(result, false));
    }

    /** GL sizes an interface array with any constant integral expression; an undeclared name as size is refused. */
    @Test
    void aConstantExpressionSizesAnInterfaceArray() {
        String vertex = """
                #version 400 compatibility
                flat out vec4[32 / 4] histogram_pdf;
                out vec2 uv;
                void main() {
                    for (int i = 0; i < 8; ++i) histogram_pdf[i] = vec4(i);
                    uv = gl_MultiTexCoord0.xy;
                    gl_Position = ftransform();
                }
                """;
        String fragment = """
                #version 400 compatibility
                flat in vec4[32 / 4] histogram_pdf;
                in vec2 uv;
                layout(location = 0) out vec4 result;
                void main() { result = histogram_pdf[int(uv.x * 8.0)]; }
                """;
        GlslInterfaceScanner.StageInterface vs = GlslInterfaceScanner.scan(vertex, true);
        GlslInterfaceScanner.StageInterface fs = GlslInterfaceScanner.scan(fragment, false);
        assertEquals(List.of(), vs.deviations());
        assertEquals(List.of(), fs.deviations());
        GlslInterfaceScanner.Declaration pdf = fs.input("histogram_pdf");
        assertEquals(8, pdf.arraySize());
        assertEquals(8, pdf.slots());
        assertEquals("vec4[8]", vs.output("histogram_pdf").typeWithArray());
        GlslInterfaceScanner.ProgramMatch match = GlslInterfaceScanner.match(vs, fs);
        assertTrue(match.executable(), match.deviations().toString());
        assertEquals(Map.of("histogram_pdf", 0, "uv", 8), match.locations());
        assertEquals(List.of("PROGRAM_INTERFACE_DECLARATION_UNSUPPORTED"), GlslInterfaceScanner.scan(
                fragment.replace("32 / 4", "BINS"), false).deviations());
    }

    /**
     * GL reads a file-scope const int, even one built from other consts, as an interface array's size; a size
     * the scanner cannot read refuses the statement rather than declaring a plain vec2.
     */
    @Test
    void aFileScopeConstIntSizesAnInterfaceArray() {
        String vertex = """
                #version 130
                const int lidarSampleCount = 24;
                out vec2 lidarSampleCoords[lidarSampleCount];
                out vec2 texcoord;
                void main() {
                    for (int i = 0; i < lidarSampleCount; i++) lidarSampleCoords[i] = vec2(0.5);
                    texcoord = gl_MultiTexCoord0.xy;
                    gl_Position = ftransform();
                }
                """;
        String fragment = """
                #version 130
                const int lidarSampleCount = 24;
                in vec2 lidarSampleCoords[lidarSampleCount];
                in vec2 texcoord;
                void main() { gl_FragData[0] = vec4(lidarSampleCoords[int(texcoord.x * 24.0)], 0.0, 1.0); }
                """;
        GlslInterfaceScanner.StageInterface vs = GlslInterfaceScanner.scan(vertex, true);
        GlslInterfaceScanner.StageInterface fs = GlslInterfaceScanner.scan(fragment, false);
        assertEquals(List.of(), fs.deviations());
        assertEquals("vec2[24]", vs.output("lidarSampleCoords").typeWithArray());
        assertEquals(24, fs.input("lidarSampleCoords").slots());
        GlslInterfaceScanner.ProgramMatch match = GlslInterfaceScanner.match(vs, fs);
        assertTrue(match.executable(), match.deviations().toString());
        assertEquals(Map.of("lidarSampleCoords", 0, "texcoord", 24), match.locations());
        assertEquals(List.of("PROGRAM_INTERFACE_DECLARATION_UNSUPPORTED"), GlslInterfaceScanner.scan(
                fragment.replace("[lidarSampleCount]", "[sampleCount]"), false).deviations(),
                "a name that is no file-scope const refuses");
        GlslInterfaceScanner.StageInterface chained = GlslInterfaceScanner.scan(fragment.replace(
                "const int lidarSampleCount = 24;",
                "const highp uint quarter = 6u;\nconst uint lidarSampleCount = quarter * 4u;"), false);
        assertEquals(24, chained.input("lidarSampleCoords").slots(), chained.deviations().toString());
        assertEquals(List.of("PROGRAM_INTERFACE_DECLARATION_UNSUPPORTED"), GlslInterfaceScanner.scan(
                fragment.replace("= 24;", "= int(24.0);"), false).deviations(), "a constructor is not read");
    }
}
