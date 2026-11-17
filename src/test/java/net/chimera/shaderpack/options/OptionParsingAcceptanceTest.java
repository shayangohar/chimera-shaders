package net.chimera.shaderpack.options;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance cases for the options a pack's source lines declare, how sightings of one name merge, and how
 * changed values are written back. Each case is a line or file and the option or text it must give.
 */
final class OptionParsingAcceptanceTest {
    private static final ShaderOption.Kind DEFINE = ShaderOption.Kind.DEFINE;
    private static final ShaderOption.Kind CONST = ShaderOption.Kind.CONST;

    /** The option one line declares, or null when it declares none. */
    private static ShaderOption option(String line) {
        AnnotatedSource source = AnnotatedSource.parse(List.of(line));
        assertTrue(source.options().keySet().stream().allMatch(index -> index == 0), line);
        return source.options().get(0);
    }

    private static void assertNothing(String... lines) {
        for (String line : lines) assertNull(option(line), line);
    }

    private static void assertToggle(String line, String name, ShaderOption.Kind kind, boolean on, String comment) {
        ShaderOption.Toggle toggle = assertInstanceOf(ShaderOption.Toggle.class, option(line), line);
        assertEquals(name, toggle.name(), line);
        assertEquals(kind, toggle.kind(), line);
        assertEquals(on, toggle.defaultValue(), line);
        assertEquals(Optional.ofNullable(comment), toggle.comment(), line);
    }

    private static void assertChoice(String line, String name, ShaderOption.Kind kind, String value,
                                     List<String> values, String comment) {
        ShaderOption.Choice choice = assertInstanceOf(ShaderOption.Choice.class, option(line), line);
        assertEquals(name, choice.name(), line);
        assertEquals(kind, choice.kind(), line);
        assertEquals(value, choice.defaultValue(), line);
        assertEquals(values, choice.values(), line);
        assertEquals(Optional.ofNullable(comment), choice.comment(), line);
    }

    /** A builder holding one file's options, each at its line. */
    private static OptionSet.Builder file(String path, String... lines) {
        OptionSet.Builder builder = new OptionSet.Builder();
        AnnotatedSource.parse(List.of(lines)).options()
                .forEach((line, option) -> builder.add(new OptionSet.Location(path, line), option));
        return builder;
    }

    /** The lines after applying {@code stored}, every option they declare counting as declared. */
    private static List<String> edited(List<String> lines, Map<String, String> stored) {
        AnnotatedSource source = AnnotatedSource.parse(lines);
        OptionSet options = file("/f", lines.toArray(String[]::new)).build();
        return source.apply(OptionValues.of(options, stored));
    }

    private static void assertEdit(String before, String name, String value, String after) {
        assertEquals(List.of(after), edited(List.of(before), Map.of(name, value)), before + " with " + value);
    }

    @Test
    void commentedOutDefinesAreOffToggles() {
        assertToggle("//#define A", "A", DEFINE, false, null);
        assertToggle("///#define A", "A", DEFINE, false, null);
        assertToggle("// #define A", "A", DEFINE, false, null);
        assertToggle("//   #define A  // c ", "A", DEFINE, false, "c");
    }

    @Test
    void togglesTakeAnyLineCommentAfterTheName() {
        assertToggle("#define A", "A", DEFINE, true, null);
        assertToggle("   #define A   ", "A", DEFINE, true, null);
        assertToggle("#define A//c", "A", DEFINE, true, "c");
        assertToggle("#define A //", "A", DEFINE, true, null);
        assertToggle("#define A // [a b]", "A", DEFINE, true, "[a b]");
    }

    @Test
    void namesAreRunsOfLettersDigitsAndUnderscores() {
        assertToggle("#define 2A", "2A", DEFINE, true, null);
        assertToggle("#define A_1", "A_1", DEFINE, true, null);
        assertChoice("#define Ä 1 // [1 2]", "Ä", DEFINE, "1", List.of("1", "2"), null);
        assertNothing("#define A$B");
    }

    @Test
    void linesThatOnlyMentionDefineDeclareNothing() {
        assertNothing("/ #define A", "#defineA", "x #define A", "# define A", "/* #define A 1 // [1 2] */",
                "#define A", "#define A", "#include \"/lib/settings.glsl\"", "");
    }

    @Test
    void aValueNeedsAGapAndAnActiveDefine() {
        assertNothing("#define A(x) 1 // [1 2]", "//#define A 1 // [1 2]", "// #define A 1 // [1 2]");
    }

    @Test
    void choicesNeedTheirListInALineComment() {
        assertChoice("#define A 1 // [1 2]", "A", DEFINE, "1", List.of("1", "2"), null);
        assertChoice("#define A 1// [1 2]", "A", DEFINE, "1", List.of("1", "2"), null);
        assertChoice("#define A 1 /// [1 2]", "A", DEFINE, "1", List.of("1", "2"), null);
        assertChoice("#define A 0.5//[0.5 1]", "A", DEFINE, "0.5", List.of("0.5", "1"), null);
        assertChoice("#define\tA\t1\t//\t[1 2]", "A", DEFINE, "1", List.of("1", "2"), null);
        assertChoice("  \t#define A 1 // [1 2]", "A", DEFINE, "1", List.of("1", "2"), null);
        assertChoice("#define QUALITY 2 // Shadow quality [1 2 3]", "QUALITY", DEFINE, "2", List.of("1", "2", "3"),
                "Shadow quality");
        assertNothing("#define A 1", "#define PI 3.14159", "#define A 1 // no list", "#define A 1 // [1 2",
                "#define A 1 /* [1 2] */", "#define A 10 20 // [10 20]");
    }

    @Test
    void valuesReadAsANumberWhenOneParsesThenAsAWord() {
        for (String value : List.of("-1", "+1", ".5", "1e5", "1_000", "0x10", "fancy", "2F", "1.0f", "-40.0")) {
            assertChoice("#define A " + value + " // [" + value + "]", "A", DEFINE, value, List.of(value), null);
        }
        for (String value : List.of("10u", "2048abc", "4096x", "1d", "1.", "1.5e-3", "-.5", "1-2", "1,0", "1.0.0",
                "(1)", "vec3(1)")) {
            assertNothing("#define A " + value + " // [" + value + "]");
        }
    }

    @Test
    void valueListsSplitOnEverySingleSpace() {
        assertChoice("#define A 1 // [1  2]", "A", DEFINE, "1", List.of("1", "", "2"), null);
        assertChoice("#define A 1 // [ 1 2]", "A", DEFINE, "1", List.of("", "1", "2"), null);
        assertChoice("#define A 1 // [1 2 ]", "A", DEFINE, "1", List.of("1", "2"), null);
        assertChoice("#define A 1 // []", "A", DEFINE, "1", List.of("", "1"), null);
        assertChoice("#define A 1 // [1\t2]", "A", DEFINE, "1", List.of("1\t2", "1"), null);
        assertChoice("#define A 1 // [1 1 2]", "A", DEFINE, "1", List.of("1", "1", "2"), null);
        assertChoice("#define A 3 // [1 2]", "A", DEFINE, "3", List.of("1", "2", "3"), null);
    }

    @Test
    void theCommentKeepsWhatSurroundsTheList() {
        assertChoice("#define A 1 // A [1 2] B", "A", DEFINE, "1", List.of("1", "2"), "A  B");
        assertChoice("#define A 1 // [1 2] see [x]", "A", DEFINE, "1", List.of("1", "2"), "see [x]");
        assertChoice("#define A 1 // a] [1 2]", "A", DEFINE, "1", List.of("1", "2"), "a]");
        assertChoice("#define A 1 // [1 2] // more", "A", DEFINE, "1", List.of("1", "2"), "// more");
        assertChoice("#define A 1 // [1 2] */", "A", DEFINE, "1", List.of("1", "2"), "*/");
    }

    @Test
    void constNumbersNeedAConfigurableNameAndAList() {
        List<String> resolutions = List.of("1024", "2048", "4096");
        assertChoice("const int shadowMapResolution = 2048; // [1024 2048 4096]", "shadowMapResolution", CONST,
                "2048", resolutions, null);
        assertChoice("const int shadowMapResolution=2048;// [1024 2048 4096]", "shadowMapResolution", CONST,
                "2048", resolutions, null);
        assertChoice("  const  int  shadowMapResolution  =  2048 ; // Size [1024 2048 4096]", "shadowMapResolution",
                CONST, "2048", resolutions, "Size");
        assertChoice("const int shadowMapResolution = abc; // [abc def]", "shadowMapResolution", CONST, "abc",
                List.of("abc", "def"), null);
        assertChoice("const float sunPathRotation = -40.0f; // [-40.0f 0.0f]", "sunPathRotation", CONST, "-40.0f",
                List.of("-40.0f", "0.0f"), null);
        assertChoice("const float shadowcolor7Nearest = 1; // [1 2]", "shadowcolor7Nearest", CONST, "1",
                List.of("1", "2"), null);
    }

    @Test
    void constLinesOutsideTheFormDeclareNothing() {
        assertNothing("const int shadowMapResolution = 2048;", "const int shadowMapResolution = 2048; // plain",
                "const int shadowMapResolution = 2048; /* [1 2] */", "const int ShadowMapResolution = 2048; // [1 2]",
                "const highp float sunPathRotation = 0.0; // [0.0]", "constint shadowMapResolution = 2048; // [2048]",
                "const int shadowMapResolution = 2048 // [2048]", "const vec3 sunPathRotation = 1; // [1]",
                "//const int shadowMapResolution = 1024; // [1024 2048]", "const float notConfigurable = 1.0; // [1.0]",
                "const int colortex8Format = RGBA16F; // [RGBA16F RGBA32F]", "const int shadowcolor8Mipmap = 1; // [1]",
                "const int shadowtex2Mipmap = 1; // [1]", "const bool shadowHardwareFiltering8 = true;");
    }

    @Test
    void constBoolsAreTogglesKeepingTheirWholeComment() {
        assertToggle("const bool shadowHardwareFiltering = true;", "shadowHardwareFiltering", CONST, true, null);
        assertToggle("const bool shadowHardwareFiltering = true; // [true false]", "shadowHardwareFiltering", CONST,
                true, "[true false]");
        assertToggle("const bool shadowHardwareFiltering7 = false;", "shadowHardwareFiltering7", CONST, false, null);
        assertNothing("const bool shadowHardwareFiltering = 1;", "const bool shadowHardwareFiltering = TRUE;",
                "const bool notListed = true;");
    }

    @Test
    void everyConfigurableConstNameIsRecognised() {
        List<String> names = new ArrayList<>(List.of("shadowMapResolution", "shadowDistance", "voxelDistance",
                "shadowDistanceRenderMul", "entityShadowDistanceMul", "shadowIntervalSize", "generateShadowMipmap",
                "generateShadowColorMipmap", "shadowHardwareFiltering", "shadowtex0Mipmap", "shadowtexMipmap",
                "shadowtex1Mipmap", "shadowtex0Nearest", "shadowtexNearest", "shadow0MinMagNearest",
                "shadowtex1Nearest", "shadow1MinMagNearest", "wetnessHalflife", "drynessHalflife",
                "eyeBrightnessHalflife", "centerDepthHalflife", "sunPathRotation", "ambientOcclusionLevel",
                "superSamplingLevel", "noiseTextureResolution"));
        for (String suffix : List.of("Mipmap", "Nearest", "MinMagNearest")) {
            for (String prefix : List.of("shadowcolor", "shadowColor")) {
                for (int index = 0; index <= 7; index++) names.add(prefix + index + suffix);
            }
        }
        for (int index = 0; index <= 7; index++) names.add("shadowHardwareFiltering" + index);
        assertEquals(81, Set.copyOf(names).size());
        for (String name : names) {
            assertChoice("const float " + name + " = 1; // [1 2]", name, CONST, "1", List.of("1", "2"), null);
        }
    }

    @Test
    void onlyABareIfdefOrIfndefTestsAName() {
        for (String line : List.of("#ifdef A", "#ifndef A", "  #ifdef A  ", "#ifdef\tA")) {
            AnnotatedSource source = AnnotatedSource.parse(List.of(line));
            assertEquals(Set.of("A"), source.testedNames(), line);
            assertTrue(source.options().isEmpty(), line);
        }
        for (String line : List.of("#ifdef A // c", "#ifdef A B", "#ifdef(A)", "#ifdefA", "# ifdef A", "//#ifdef A",
                "#if defined A", "#elif defined A", "#if A")) {
            assertEquals(Set.of(), AnnotatedSource.parse(List.of(line)).testedNames(), line);
        }
        assertEquals(Set.of("A", "B"), AnnotatedSource.parse(List.of("#ifdef A", "#endif", "#ifndef B")).testedNames());
    }

    @Test
    void everyLineIsReadOnItsOwnAndOptionsComeInLineOrder() {
        AnnotatedSource source = AnnotatedSource.parse(List.of("#version 120", "/*", "#define B 1 // [1 2]", "*/",
                "#define A", "void main() {}", "const int shadowMapResolution = 2048; // [2048]"));
        assertEquals(List.of(2, 4, 6), List.copyOf(source.options().keySet()), "a block comment hides nothing");
        assertEquals(List.of("B", "A", "shadowMapResolution"),
                source.options().values().stream().map(ShaderOption::name).toList());
    }

    @Test
    void aConstBoolNeedsAnIfdefLikeAnyToggle(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("composite.fsh"), "const bool shadowHardwareFiltering = true;\n");
        assertFalse(OptionDiscovery.discover(shaders).options().isToggle("shadowHardwareFiltering"));
        Files.writeString(shaders.resolve("final.fsh"), "#ifdef shadowHardwareFiltering\n#endif\n");
        ShaderOption.Toggle toggle = OptionDiscovery.discover(shaders).options().toggles()
                .get("shadowHardwareFiltering").option();
        assertEquals(CONST, toggle.kind());
        assertTrue(toggle.defaultValue());
    }

    @Test
    void togglesWriteBackByCommentingTheLine() {
        assertEdit("#define A", "A", "false", "//#define A");
        assertEdit("  #define A // c", "A", "false", "//  #define A // c");
        assertEdit("//#define A // c", "A", "true", "#define A // c");
        assertEdit("    //#define A // c   ", "A", "true", "#define A // c");
        assertEdit("//#define A   ", "A", "true", "#define A   ");
        assertEdit("// #define A", "A", "true", " #define A");
        assertEdit("///#define A", "A", "true", "#define A");
    }

    @Test
    void defineChoicesWriteBackAsAWholeNewLine() {
        assertEdit("  #define A 1 // Q [1 2]", "A", "2", "#define A 2 // changed by the shader pack options");
        assertEdit("#define A 1 // [1 2]", "A", "7", "#define A 7 // changed by the shader pack options");
        assertEdit("#define A 1 // [1 2]", "A", "$1\\x", "#define A $1\\x // changed by the shader pack options");
    }

    @Test
    void constsWriteBackOnlyTheirValue() {
        assertEdit("  const int shadowMapResolution=2048;// [1024 2048 4096]", "shadowMapResolution", "1024",
                "  const int shadowMapResolution=1024;// [1024 2048 4096]");
        assertEdit("const float sunPathRotation = -40.0; // [-40.0 0.0 40.0]", "sunPathRotation", "0.0",
                "const float sunPathRotation = 0.0; // [-40.0 0.0 40.0]");
        assertEdit("const float sunPathRotation = -40.0f; // [-40.0f 0.0f]", "sunPathRotation", "0.0f",
                "const float sunPathRotation = 0.0f; // [-40.0f 0.0f]");
        assertEdit("const bool shadowHardwareFiltering = true; // [true false]", "shadowHardwareFiltering", "false",
                "const bool shadowHardwareFiltering = false; // [true false]");
    }

    @Test
    void unchangedLinesComeBackAsTheSameList() {
        List<String> lines = List.of("#version 120", "#define A 1 // [1 2]", "#define T");
        AnnotatedSource source = AnnotatedSource.parse(lines);
        OptionSet options = file("/f", lines.toArray(String[]::new)).build();
        assertSame(source.lines(), source.apply(OptionValues.of(options, Map.of())));
        assertSame(source.lines(), source.apply(OptionValues.of(options, Map.of("A", "1", "T", "true", "X", "2"))));
        assertEquals(List.of("#version 120", "#define A 2 // changed by the shader pack options", "#define T"),
                source.apply(OptionValues.of(options, Map.of("A", "2"))));
        assertEquals(lines, source.lines(), "applying values leaves the source as read");
    }

    @Test
    void everyLineNamingAChangedOptionIsEditedEvenOneThatLostAConflict() {
        List<String> lines = List.of("#define T", "//#define T", "#define T", "#define L 1 // [1 2]",
                "#define L 2 // [1 2]", "#define L 1 // [1 2]", "const int shadowMapResolution = 2048; // [1024 2048]",
                "const int shadowMapResolution = 1024; // [1024 2048]",
                "const int shadowMapResolution = 2048; // [1024 2048]");
        String changedL = "#define L 2 // changed by the shader pack options";
        String smaller = "const int shadowMapResolution = 1024; // [1024 2048]";
        assertEquals(List.of("//#define T", "////#define T", "//#define T", changedL, changedL, changedL, smaller,
                smaller, smaller), edited(lines, Map.of("T", "false", "L", "2", "shadowMapResolution", "1024")));
        String thirdL = "#define L 3 // changed by the shader pack options";
        List<String> expected = new ArrayList<>(lines);
        expected.subList(3, 6).replaceAll(line -> thirdL);
        assertEquals(expected, edited(lines, Map.of("L", "3")));
    }

    @Test
    void toggleAndChoiceLinesOfOneNameReadTheirOwnValue() {
        List<String> lines = List.of("#define N", "#define N 1 // [1 2]");
        assertEquals(List.of("#define N", "#define N 2 // changed by the shader pack options"),
                edited(lines, Map.of("N", "2")));
        assertEquals(List.of("//#define N", "#define N false // changed by the shader pack options"),
                edited(lines, Map.of("N", "false")));
    }

    @Test
    void theEarliestCommentedSightingWinsElseTheLatest() {
        OptionSet options = file("/a", "#define Q 1 // [1 2]", "#define Q 1 // [1 3]", "#define Q 1 // first [1 4]",
                "#define Q 1 // second [1 5]", "#define Q 1 // [1 6]", "#define R 1 // [1 2]", "#define R 1 // [1 3]")
                .build();
        OptionSet.Declared<ShaderOption.Choice> q = options.choices().get("Q");
        assertEquals(Optional.of("first"), q.option().comment());
        assertEquals(List.of("1", "4"), q.option().values());
        assertEquals(List.of(0, 1, 2, 3, 4), q.locations().stream().map(OptionSet.Location::line).toList());
        OptionSet.Declared<ShaderOption.Choice> r = options.choices().get("R");
        assertEquals(List.of("1", "3"), r.option().values());
        assertEquals(List.of(new OptionSet.Location("/a", 5), new OptionSet.Location("/a", 6)),
                List.copyOf(r.locations()));
    }

    @Test
    void aDisagreeingDefaultDropsTheNameUntilItIsDeclaredAgain() {
        OptionSet.Builder sameFile = file("/a", "#define T", "//#define T");
        assertFalse(sameFile.build().isToggle("T"));
        assertEquals(Set.of("T"), sameFile.ambiguous());
        OptionSet.Builder restarted = file("/a", "#define T", "//#define T", "#define T");
        assertEquals(List.of(new OptionSet.Location("/a", 2)),
                List.copyOf(restarted.build().toggles().get("T").locations()));
        assertEquals(Set.of(), restarted.ambiguous());
        OptionSet.Builder text = file("/a", "#define P 1 // [1 2]", "#define P 1.0 // [1 2]");
        assertFalse(text.build().isChoice("P"), "defaults compare as text");
        assertEquals(Set.of("P"), text.ambiguous());
    }

    @Test
    void aToggleAndAChoiceMayShareAName() {
        OptionSet options = file("/a", "#define N", "#define N 1 // [1 2]").build();
        assertTrue(options.isToggle("N") && options.isChoice("N"));
        assertTrue(options.toggles().get("N").option().defaultValue());
        assertEquals("1", options.choices().get("N").option().defaultValue());
    }

    @Test
    void filesMergeAsWholesSoAConflictInsideOneKeepsEarlierFiles() {
        OptionSet.Builder pack = new OptionSet.Builder();
        pack.addAll(file("/f0", "#define L 1 // [1 2]"));
        pack.addAll(file("/f1", "#define L 1 // [1 2]", "#define L 2 // [1 2]", "#define L 1 // [1 2]"));
        assertEquals(List.of(new OptionSet.Location("/f0", 0), new OptionSet.Location("/f1", 2)),
                List.copyOf(pack.build().choices().get("L").locations()));
        assertEquals(Set.of(), pack.ambiguous());
    }

    @Test
    void aConflictAcrossFilesDropsTheNameAndALaterFileRestartsIt() {
        OptionSet.Builder pack = new OptionSet.Builder();
        pack.addAll(file("/a", "#define M 1 // [1 2]"));
        pack.addAll(file("/b", "#define M 2 // [1 2]"));
        assertFalse(pack.build().isChoice("M"));
        assertEquals(Set.of("M"), pack.ambiguous());
        pack.addAll(file("/c", "#define M 2 // [1 2]"));
        OptionSet.Declared<ShaderOption.Choice> m = pack.build().choices().get("M");
        assertEquals("2", m.option().defaultValue());
        assertEquals(List.of(new OptionSet.Location("/c", 0)), List.copyOf(m.locations()));
        assertEquals(Set.of(), pack.ambiguous());
    }

    @Test
    void namespacesIterateAsAHashMapGivenTheSameHistory(@TempDir Path root) throws IOException {
        List<String> names = List.of("SHADOWS", "BLOOM", "FOG", "QUALITY", "SAMPLES", "WATER", "CLOUDS", "SKY", "RAIN",
                "SNOW", "LIGHT", "SUN", "MOON");
        StringBuilder all = new StringBuilder();
        names.forEach(name -> all.append("#define ").append(name).append("\n#ifdef ").append(name).append('\n'));
        Path conflicting = Files.createDirectories(root.resolve("conflicting/shaders"));
        Files.writeString(conflicting.resolve("composite.fsh"), all);
        Files.writeString(conflicting.resolve("final.fsh"), "//#define SHADOWS\n");
        ShaderPackOptions dropped = ShaderPackOptions.load(conflicting, Map.of());
        assertTrue(dropped.problems().contains("OPTION_AMBIGUOUS:SHADOWS"));
        assertEquals(List.of("SKY", "QUALITY", "SNOW", "MOON", "BLOOM", "LIGHT", "SUN", "RAIN", "CLOUDS", "SAMPLES",
                "FOG", "WATER"), mainScreenNames(dropped), "the peak of thirteen names sets the order");
        Path plain = Files.createDirectories(root.resolve("plain/shaders"));
        Files.writeString(plain.resolve("composite.fsh"), all.toString().replace("#define SHADOWS\n", ""));
        assertEquals(List.of("SKY", "RAIN", "QUALITY", "SNOW", "MOON", "BLOOM", "CLOUDS", "LIGHT", "SAMPLES", "SUN",
                "FOG", "WATER"), mainScreenNames(ShaderPackOptions.load(plain, Map.of())));
    }

    private static List<String> mainScreenNames(ShaderPackOptions options) {
        return options.menu().main().elements().stream()
                .map(element -> ((OptionMenu.OptionEntry) element).option().name()).toList();
    }
}
