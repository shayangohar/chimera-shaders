// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * shaders.properties keys as the report names them: a key Iris reads and Chimera does not apply is
 * SETTING_NOT_APPLIED. Keys in a skipped branch are not read.
 */
final class PropertyKeyReportTest {
    @Test
    void keysAreClassifiedAgainstIris() {
        for (String applied : List.of("clouds", "blend.composite", "screen.MAIN", "screen", "sliders",
                "profile.HIGH", "vignette", "shadowTranslucent", "particles.ordering", "image.voxels",
                "size.buffer.colortex1", "uniform.float.x", "shadow.culling", "shadow.enabled",
                "separateEntityDraws", "voxelizeLightBlocks", "rain.depth", "supportsColorCorrection",
                "frustum.culling")) {
            assertNull(PackPropertyKeys.unread(applied), applied);
        }
        // source const names: Iris reads them only as const directives, so neither reader takes them here
        for (String constOnly : List.of("colortex3Format", "colortex1Clear", "shadowMapResolution",
                "sunPathRotation")) {
            assertEquals("SETTING_UNKNOWN", PackPropertyKeys.unread(constOnly), constOnly);
        }
        for (String irisOnly : List.of("beacon.beam.depth", "backFace.solid", "dhShadow.enabled", "scale.composite",
                "weather")) {
            assertEquals("SETTING_UNSUPPORTED", PackPropertyKeys.unread(irisOnly), irisOnly);
        }
        assertEquals("SETTING_UNKNOWN", PackPropertyKeys.unread("customImage0"));
    }

    /**
     * A comment line left empty by PropertiesPreprocessor ends a backslash continuation, while a blank line is
     * dropped first; a '#' with a space before the directive's name is a comment.
     */
    @Test
    void aCommentEndsAContinuationAndASpacedDirectiveIsAComment(@TempDir Path root) throws Exception {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), """
                screen = A \\

                    B \\
                # screen = \\
                #   C \\
                screen.A.columns=1
                # if 0
                vignette=false
                # endif
                """);
        Files.writeString(shaders.resolve("composite.fsh"), """
                #version 120
                uniform sampler2D colortex0;
                varying vec2 uv;
                void main() { gl_FragData[0] = texture2D(colortex0, uv); }
                """);
        var values = PackProbe.analyze(root).settings().propertyValues();
        assertEquals("A B", values.get("screen").replaceAll("\\s+", " ").trim());
        assertEquals("1", values.get("screen.A.columns"));
        assertEquals("false", values.get("vignette"));
    }

    /**
     * JCPP lexes shaders.properties as a whole, so a block comment runs across lines, in a skipped branch too;
     * its other lines stay properties for Properties.load.
     */
    @Test
    void aBlockCommentRunsAcrossShadersPropertiesLines(@TempDir Path root) throws Exception {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("composite.fsh"), """
                #version 120
                void main() { gl_FragColor = vec4(1.0); }
                """);
        Files.writeString(shaders.resolve("shaders.properties"), """
                uniform.float.a = 1.0
                /*
                #if 0
                */
                uniform.float.b = 2.0
                #if 0 /* open
                uniform.float.hidden = 3.0
                */ || 1
                uniform.float.c = 4.0
                #endif
                #ifdef MISSING
                /*
                #endif
                */
                uniform.float.d = 5.0
                #endif
                uniform.float.e = 6.0
                """);
        PackRuntimeSettings settings = PackProbe.analyze(root).plan().runtimeSettings();
        for (String read : List.of("a", "b", "c", "e")) {
            assertTrue(settings.indexOf(read) >= 0, read + " is read");
        }
        assertEquals(-1, settings.indexOf("hidden"), "the #if line's comment took it");
        assertEquals(-1, settings.indexOf("d"), "the #endif in a comment ended no branch");

        // a # after the close of a comment that began its line starts a directive there too
        Files.writeString(shaders.resolve("shaders.properties"), """
                uniform.float.k1 = 1.0
                /*
                #define A */ #if 0
                uniform.float.k2 = 2.0
                #endif
                uniform.float.k3 = 3.0
                """);
        PackRuntimeSettings closed = PackProbe.analyze(root).plan().runtimeSettings();
        assertTrue(closed.indexOf("k1") >= 0 && closed.indexOf("k3") >= 0);
        assertEquals(-1, closed.indexOf("k2"), "the #if 0 after the comment's close applies");
    }

    /**
     * JCPP reports a malformed conditional and reads on: {@code #if F(1)} with no F reads false, and a stray
     * #else turns the rest off. A condition Chimera cannot evaluate also reads false and is reported.
     */
    @Test
    void aMalformedConditionalIsSkippedAndTheFileReadsOn(@TempDir Path root) throws Exception {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("composite.fsh"), """
                #version 120
                void main() { gl_FragColor = vec4(1.0); }
                """);
        Files.writeString(shaders.resolve("shaders.properties"), """
                #if F(1)
                uniform.float.skipped = 1.0
                #endif
                uniform.float.after = 2.0
                #else
                uniform.float.off = 3.0
                """);
        PackRuntimeSettings settings = PackProbe.analyze(root).plan().runtimeSettings();
        assertTrue(settings.indexOf("after") >= 0, "the file reads on past the #if");
        assertEquals(-1, settings.indexOf("skipped"));
        assertEquals(-1, settings.indexOf("off"), "a stray #else turns the rest off");

        // an #ifdef with no name keeps its branch on and takes the next line; blank lines are gone first
        Files.writeString(shaders.resolve("shaders.properties"), """
                #ifdef

                # a comment, emptied by Iris's line pass
                uniform.float.taken = 1.0
                uniform.float.kept = 2.0
                #endif
                """);
        PackRuntimeSettings ifdef = PackProbe.analyze(root).plan().runtimeSettings();
        assertEquals(-1, ifdef.indexOf("taken"));
        assertTrue(ifdef.indexOf("kept") >= 0);

        Files.writeString(shaders.resolve("dimension.properties"), """
                dimension.world0 = *
                #if F(1)
                dimension.world_never = minecraft:overworld
                #endif
                dimension.world-1 = minecraft:the_nether
                """);
        List<String> deviations = new ArrayList<>();
        assertEquals(Map.of("*", "world0", "minecraft:the_nether", "world-1"),
                DimensionProperties.read(shaders, Map.of(), deviations).worlds());
        assertEquals(List.of(), deviations, "JCPP evaluates it, so nothing is unsupported");

        deviations.clear();
        assertEquals(Map.of("*", "world0", "minecraft:the_nether", "world-1"),
                DimensionProperties.read(shaders, Map.of("F", "F(x) x"), deviations).worlds());
        assertEquals(List.of("PREPROCESSOR_CONDITION_UNSUPPORTED"), deviations);
    }

    /**
     * Iris hides each backslash behind an identifier before JCPP reads a properties file, so a
     * string in a #define closes at a backslash's quote and what follows can be a comment.
     */
    @Test
    void aPropertiesDirectiveReadsItsBackslashesHidden() {
        assertEquals("\"a\\\"", PropertiesPreprocessor.withoutComments("\"a\\\" // b\"").trim());
        assertEquals("\"a\\\" // b\"", PackConditionals.withoutComments("\"a\\\" // b\"").trim(),
                "GLSL's reader keeps the escape, as JCPP reads a GLSL string");
        assertEquals("1 + 1", PropertiesPreprocessor.withoutComments("1 /* c */+ 1").replaceAll("\\s+", " "));
    }

    @Test
    void theReportNamesOnlyKeysIrisReadsAndChimeraDoesNotApply(@TempDir Path root) throws Exception {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), """
                backFace.solid=true
                rain.depth=true
                vignette=false
                blend.composite=off
                customImage0=x.png
                #if 0
                beacon.beam.depth=true
                #endif
                """);
        Files.writeString(shaders.resolve("composite.fsh"), """
                #version 120
                uniform sampler2D colortex0;
                varying vec2 uv;
                void main() { gl_FragData[0] = texture2D(colortex0, uv); }
                """);
        List<String> settings = PackProbe.analyze(root).report().deviations().stream()
                .filter(deviation -> deviation.startsWith("SETTING_")).toList();
        assertEquals(List.of("SETTING_NOT_APPLIED:backFace.solid"), settings);
    }

    /**
     * With supportsColorCorrection, Iris adds the COLOR_SPACE_* defines to shader sources after it has
     * preprocessed shaders.properties.
     */
    @Test
    void colorCorrectionDefinesTheColorSpaces(@TempDir Path root) throws Exception {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("composite.fsh"), """
                #version 120
                void main() {
                #if defined COLOR_SPACE_SRGB && COLOR_SPACE_REC2020 == 3 && COLOR_SPACE_ADOBE_RGB == 4
                    gl_FragColor = vec4(0.25 * float(COLOR_SPACE_DISPLAY_P3));
                #else
                    gl_FragColor = vec4(0.75);
                #endif
                }
                """);
        for (String flag : List.of("true", "1", "false", "0")) {
            Files.writeString(shaders.resolve("shaders.properties"), """
                    supportsColorCorrection = %s
                    #ifdef COLOR_SPACE_SRGB
                    uniform.float.inProperties = 1.0
                    #endif
                    """.formatted(flag));
            PackPlan plan = PackProbe.analyze(root).plan();
            boolean on = flag.equals("true") || flag.equals("1");
            Map<String, String> defines = plan.settingsPreprocessorDefines();
            assertEquals(on ? "0" : null, defines.get("COLOR_SPACE_SRGB"), flag);
            assertEquals(on ? "2" : null, defines.get("COLOR_SPACE_DISPLAY_P3"), flag);
            String fragment = plan.program("composite").convertedFragment();
            assertEquals(on, fragment.contains("0.25") && !fragment.contains("0.75"), flag + ":\n" + fragment);
            // Kappa reads them as values too (case COLOR_SPACE_SRGB:), so a use expands to the value
            assertEquals(on, fragment.contains("0.25 * float(2)"), flag + ":\n" + fragment);
            assertEquals(-1, plan.runtimeSettings().indexOf("inProperties"),
                    "shaders.properties is preprocessed before the colour spaces are defined");
        }
    }
}
