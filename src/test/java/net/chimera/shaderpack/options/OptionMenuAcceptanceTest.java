// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack.options;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Acceptance cases for the menu screens and profiles shaders.properties makes of a pack's options. */
final class OptionMenuAcceptanceTest {
    /** Toggles T1 T2 T3 BOTH and choices C1 C2 BOTH, declared in this order in one file. */
    private static final OptionSet MENU_OPTIONS = options("#define T1", "#define T2", "#define T3",
            "#define C1 1 // [1 2]", "#define C2 1 // [1 2]", "#define BOTH", "#define BOTH 1 // [1 2]");
    /** Toggles SHADOWS (on) and FOG (off), choice QUALITY (2 of 1 2 3). */
    private static final OptionSet PROFILE_OPTIONS = options("#define SHADOWS", "//#define FOG",
            "#define QUALITY 2 // [1 2 3]");

    private static OptionSet options(String... lines) {
        OptionSet.Builder builder = new OptionSet.Builder();
        AnnotatedSource.parse(List.of(lines)).options()
                .forEach((line, option) -> builder.add(new OptionSet.Location("/f", line), option));
        return builder.build();
    }

    private static OptionMenu menu(String properties, OptionSet options) {
        MenuProperties parsed = MenuProperties.parse(properties);
        return OptionMenu.build(parsed, options, PackProfiles.parse(parsed.profiles(), options));
    }

    private static PackProfiles profiles(String properties) {
        return PackProfiles.parse(MenuProperties.parse(properties).profiles(), PROFILE_OPTIONS);
    }

    /**
     * A screen's cells as short labels: {@code _} blank, {@code <profile>}, {@code [ID]} link, {@code t:NAME}
     * toggle, {@code c:NAME} choice, with {@code ~} after a slider.
     */
    private static List<String> cells(OptionMenu.Screen screen) {
        List<String> cells = new ArrayList<>();
        for (OptionMenu.Element element : screen.elements()) {
            cells.add(switch (element) {
                case OptionMenu.Empty empty -> "_";
                case OptionMenu.ProfileButton button -> "<profile>";
                case OptionMenu.Link link -> "[" + link.screen() + "]";
                case OptionMenu.OptionEntry entry -> (entry.option() instanceof ShaderOption.Toggle ? "t:" : "c:")
                        + entry.option().name() + (entry.slider() ? "~" : "");
            });
        }
        return cells;
    }

    private static List<String> sub(OptionMenu menu, String id) {
        return cells(menu.subScreen(id).orElseThrow());
    }

    private static String current(PackProfiles profiles, Map<String, String> stored) {
        return profiles.match(OptionValues.of(PROFILE_OPTIONS, stored)).current().map(PackProfiles.Profile::name)
                .orElse("-");
    }

    private static List<String> names(PackProfiles profiles) {
        return profiles.declared().stream().map(PackProfiles.Profile::name).toList();
    }

    private static PackProfiles.Profile profile(PackProfiles profiles, String name) {
        return profiles.declared().stream().filter(profile -> profile.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void withoutAScreenKeyTheMainScreenListsEveryOption() {
        List<String> every = List.of("t:T1", "t:T2", "t:BOTH", "t:T3", "c:C1", "t:BOTH", "c:C2");
        OptionMenu plain = menu("", MENU_OPTIONS);
        assertEquals(every, cells(plain.main()), "toggles then choices, each in hash order; BOTH shows its toggle");
        assertEquals(2, plain.main().columnCount());
        assertEquals(Map.of(), plain.subScreens());
        assertEquals(every, cells(menu("screen=<profile> *\n", MENU_OPTIONS).main()),
                "the profile button needs a valid profile");
        assertEquals(every, cells(menu("screen=<profile> *\nprofile.BAD=profile.MISSING\n", MENU_OPTIONS).main()));
    }

    @Test
    void onlyTheFirstStarIsFilledAndItRepeatsTheMainScreensOwnOptions() {
        assertEquals(List.of("t:T1", "t:T1", "t:T2", "t:BOTH", "t:T3", "c:C1", "t:BOTH", "c:C2", "_"),
                cells(menu("screen=T1 * <empty> *\n", MENU_OPTIONS).main()));
    }

    @Test
    void subScreensTakeTheirOptionsOutOfTheFill() {
        OptionMenu menu = menu("screen=[A] [B]\nscreen.A=* T1\nscreen.B=T2 *\nscreen.C=*\n", MENU_OPTIONS);
        assertEquals(List.of("[A]", "[B]"), cells(menu.main()));
        assertEquals(List.of("t:BOTH", "t:T3", "c:C1", "t:BOTH", "c:C2", "t:T1"), sub(menu, "A"),
                "without a main *, the first sub-screen in hash order takes the fill");
        assertEquals(List.of("t:T2"), sub(menu, "B"));
        assertEquals(List.of(), sub(menu, "C"));
        assertEquals(List.of("t:T1", "t:T2", "t:T3", "c:C1", "t:BOTH", "c:C2"),
                cells(menu("screen=*\nscreen.X=BOTH\n", MENU_OPTIONS).main()), "a sub-screen takes out the first BOTH");
    }

    @Test
    void entriesBecomeLinksBlanksOptionsOrUnknownBlanks() {
        OptionMenu menu = menu("screen=[] [ [x <foo> BOTH BOTH T1 T1 <empty>\nscreen.A=T1\n", MENU_OPTIONS);
        assertEquals(List.of("[]", "_", "_", "_", "t:BOTH", "t:BOTH", "t:T1", "t:T1", "_"), cells(menu.main()));
        assertEquals(List.of("MENU_ELEMENT_UNKNOWN:[", "MENU_ELEMENT_UNKNOWN:[x", "MENU_ELEMENT_UNKNOWN:<foo>"),
                menu.problems());
        assertEquals(new OptionMenu.Link(""), menu.main().elements().getFirst());
        assertEquals(Optional.empty(), menu.subScreen(""), "a link needs no screen behind it");
        assertEquals(Optional.empty(), menu.subScreen("nope"));
    }

    @Test
    void slidersMarkAnyOptionTheyNameAndTheProfileButtonNeedsAProfile() {
        OptionMenu menu = menu("screen=\nscreen.S=<profile> T2 C1 *\nsliders=T2 C1\nprofile.P=T1\n", MENU_OPTIONS);
        assertEquals(List.of("_"), cells(menu.main()));
        assertEquals(List.of("MENU_ELEMENT_UNKNOWN:"), menu.problems(), "an empty screen value is one empty entry");
        assertEquals(List.of("<profile>", "t:T2~", "c:C1~", "t:T1", "t:BOTH", "t:T3", "t:BOTH", "c:C2"),
                sub(menu, "S"));
    }

    @Test
    void columnsDefaultToThreeAboveEighteenElements() {
        String eighteen = "<empty> ".repeat(18);
        assertEquals(2, menu("screen=" + eighteen + "\n", MENU_OPTIONS).main().columnCount());
        assertEquals(3, menu("screen=" + eighteen + "<empty>\n", MENU_OPTIONS).main().columnCount());
        assertEquals(3, menu("screen=" + "<empty> ".repeat(16) + "*\n", MENU_OPTIONS).main().columnCount(),
                "the fill counts");
        assertEquals(-1, menu("screen=*\nscreen.columns=-1\n", MENU_OPTIONS).main().columnCount());
        assertEquals(0, menu("screen=*\nscreen.columns=0\n", MENU_OPTIONS).main().columnCount());
        assertEquals(4, menu("screen=[S]\nscreen.S=T1\nscreen.S.columns=4\n", MENU_OPTIONS).subScreen("S")
                .orElseThrow().columnCount());
    }

    @Test
    void anEmptyMenuHasNoElements() {
        OptionMenu menu = OptionMenu.build(MenuProperties.empty(), OptionSet.empty(), PackProfiles.empty());
        assertEquals(List.of(), menu.main().elements());
        assertEquals(List.of(), menu.problems());
        assertEquals(Map.of(), menu.subScreens());
    }

    @Test
    void columnCountsParseAsIntegersWithTrailingSpaceKept() {
        MenuProperties properties = MenuProperties.parse(
                "screen.columns=3 \nscreen.X.columns= 2\nscreen.Y.columns=+2\nscreen.Z.columns=\nscreen.W.columns=x\n");
        assertEquals(Optional.empty(), properties.mainColumns());
        assertEquals(List.of("MENU_COLUMNS_MALFORMED:screen.columns=3 ", "MENU_COLUMNS_MALFORMED:screen.Z.columns=",
                "MENU_COLUMNS_MALFORMED:screen.W.columns=x"), properties.problems());
        OptionMenu menu = menu("screen.X=T1\nscreen.X.columns= 2\nscreen.Y=T1\nscreen.Y.columns=+2\n", MENU_OPTIONS);
        assertEquals(2, menu.subScreen("X").orElseThrow().columnCount());
        assertEquals(2, menu.subScreen("Y").orElseThrow().columnCount());
    }

    @Test
    void screenKeysNameSubScreensByEverythingAfterTheFirstDot() {
        OptionMenu menu = menu("screen.A.columns=x\nscreen.B.C=T1\nscreen.B.C.columns=4\nscreen..columns=T2\n"
                + "screen.=T3\nscreen.S=a\tb  c\n", MENU_OPTIONS);
        assertEquals(Optional.empty(), menu.subScreen("A"), "a malformed count makes no screen");
        assertEquals(4, menu.subScreen("B.C").orElseThrow().columnCount());
        assertEquals(List.of("t:T2"), sub(menu, ".columns"));
        assertEquals(List.of("t:T3"), sub(menu, ""));
        assertEquals(List.of("_", "_"), sub(menu, "S"), "tabs do not split a list");
        assertTrue(menu.problems().containsAll(List.of("MENU_ELEMENT_UNKNOWN:a\tb", "MENU_ELEMENT_UNKNOWN:c")));
    }

    @Test
    void repeatedKeysKeepTheirFirstPlaceAndLastValue() {
        MenuProperties properties = MenuProperties.parse("profile.B=X\nprofile.A=Y\nprofile.B=Z\nscreen=1\n"
                + "screen=C  D \n");
        assertEquals(List.of(Map.entry("B", List.of("Z")), Map.entry("A", List.of("Y"))),
                List.copyOf(properties.profiles().entrySet()));
        assertEquals(Optional.of(List.of("C", "D")), properties.mainScreen());
        OptionMenu menu = menu("screen=T1 T2 T3\nsliders=T1 T2\nsliders=T3\n", MENU_OPTIONS);
        assertEquals(List.of("t:T1", "t:T2", "t:T3~"), cells(menu.main()));
    }

    @Test
    void shadersPropertiesIsReadAsLatin1WhenItIsAFile(@TempDir Path root) throws IOException {
        MenuProperties missing = MenuProperties.read(root);
        assertTrue(missing.mainScreen().isEmpty() && missing.mainColumns().isEmpty() && missing.profiles().isEmpty()
                && missing.problems().isEmpty());
        Path folder = Files.createDirectories(root.resolve("folder"));
        Files.createDirectories(folder.resolve("shaders.properties"));
        assertTrue(MenuProperties.read(folder).mainScreen().isEmpty(), "a folder of that name is no file");
        Files.write(root.resolve("shaders.properties"), new byte[] {'s', 'c', 'r', 'e', 'e', 'n', '=', 'A',
                (byte) 0xE9, ' ', 'B', '\n'});
        assertEquals(Optional.of(List.of("A\u00e9", "B")), MenuProperties.read(root).mainScreen());
    }

    @Test
    void orderedEntriesFollowThePropertiesFormat() {
        assertEquals(List.of(Map.entry("b", "3"), Map.entry("a", "2")),
                List.copyOf(MenuProperties.orderedEntries(new StringReader("b=1\na=2\nb=3\nc=\\u00zz\nd=4\n"))
                        .entrySet()), "a bad escape ends the read and keeps what came before");
        assertEquals(List.of(Map.entry("x", "a b"), Map.entry("y", "c"), Map.entry("z", "d")),
                List.copyOf(MenuProperties.orderedEntries(new StringReader("x=a \\\n   b\ny : c\nz d\n!c=1\n#q=2\n"))
                        .entrySet()));
    }

    @Test
    void profileTokensSetIncludeOrSwitchOff() {
        PackProfiles profiles = profiles("""
                profile.A=QUALITY=1 SHADOWS:x=y A:B=C !program.p1 !program.world0/p2
                profile.B=profile.A QUALITY=3 !SHADOWS !UNDECLARED !QUALITY FOG UNKNOWN_BARE profile.C
                profile.C=FOG=1
                profile.P=QUALITY SHADOWS !A=1 =
                """);
        assertEquals(Map.of("QUALITY", "1", "SHADOWS:x", "y", "A:B", "C"), profile(profiles, "A").values());
        assertEquals(List.of("p1", "world0/p2"), profile(profiles, "A").disabledPrograms());
        assertEquals(Map.of("QUALITY", "false", "SHADOWS:x", "y", "A:B", "C", "SHADOWS", "false", "UNDECLARED",
                "false", "FOG", "1"), profile(profiles, "B").values());
        assertEquals(List.of("p1", "world0/p2"), profile(profiles, "B").disabledPrograms());
        assertEquals(Map.of("SHADOWS", "true", "A=1", "false", "", ""), profile(profiles, "P").values(),
                "a bare choice name is ignored");
        assertEquals(List.of("A", "B", "C", "P"), names(profiles));
    }

    @Test
    void aProfileIncludingAMissingOrCircularOneIsLeftOut() {
        PackProfiles profiles = profiles("""
                profile.D=profile.MISSING
                profile.E=profile.D
                profile.F=profile.G
                profile.G=profile.F
                profile.H=profile.C profile.C !program.x profile.C
                profile.C=FOG=1 !program.c
                profile.I=
                profile.LOOP=profile.LOOP
                profile.X=profile.Y=1
                """);
        assertEquals(List.of("PROFILE_INVALID:D:missing profile MISSING", "PROFILE_INVALID:E:missing profile MISSING",
                "PROFILE_INVALID:F:circular profile F", "PROFILE_INVALID:G:circular profile G",
                "PROFILE_INVALID:LOOP:circular profile LOOP", "PROFILE_INVALID:X:missing profile Y=1"),
                profiles.problems());
        assertEquals(List.of("H", "C", "I"), names(profiles));
        assertEquals(List.of("c", "c", "x", "c"), profile(profiles, "H").disabledPrograms());
        assertEquals(Map.of(), profile(profiles, "I").values());
    }

    @Test
    void scanTriesTheProfilesSettingMostFirstAndCyclesInThatOrder() {
        PackProfiles profiles = profiles("""
                profile.LOW=QUALITY=1
                profile.MID=QUALITY=2
                profile.HIGH=QUALITY=3 SHADOWS
                profile.ULTRA=QUALITY=3 FOG
                """);
        for (String[] row : new String[][] {{"1", "LOW", "MID", "ULTRA"}, {"2", "MID", "HIGH", "LOW"},
                {"3", "HIGH", "ULTRA", "MID"}, {"7", "-", "HIGH", "MID"}}) {
            for (String fog : List.of("false", "true")) {
                PackProfiles.ProfileMatch scan = profiles.match(OptionValues.of(PROFILE_OPTIONS,
                        Map.of("QUALITY", row[0], "FOG", fog)));
                assertEquals(row[1], scan.current().map(PackProfiles.Profile::name).orElse("-"), row[0]);
                assertEquals(row[2], scan.next().name(), row[0]);
                assertEquals(row[3], scan.previous().name(), row[0]);
            }
        }
    }

    @Test
    void oneProfileIsItsOwnNeighbourAndNoProfilesHaveNone() {
        PackProfiles.ProfileMatch one = profiles("profile.ONLY=QUALITY=2\n")
                .match(OptionValues.of(PROFILE_OPTIONS, Map.of()));
        assertEquals("ONLY", one.current().orElseThrow().name());
        assertEquals("ONLY", one.next().name());
        assertEquals("ONLY", one.previous().name());
        for (PackProfiles none : List.of(PackProfiles.empty(), profiles(""), profiles("profile.BAD=profile.NONE\n"))) {
            PackProfiles.ProfileMatch scan = none.match(OptionValues.of(PROFILE_OPTIONS, Map.of()));
            assertTrue(scan.current().isEmpty());
            assertNull(scan.next());
            assertNull(scan.previous());
        }
    }

    @Test
    void aToggleMatchesOnlyTheWordsTrueAndFalse() {
        PackProfiles profiles = profiles("""
                profile.ONE=SHADOWS=1
                profile.UPPER=SHADOWS=TRUE
                profile.WORD=SHADOWS=true
                """);
        assertEquals("WORD", current(profiles, Map.of()));
        assertEquals("-", current(profiles, Map.of("SHADOWS", "false")));
        assertEquals("OFF", current(profiles("profile.OFF=!SHADOWS FOG=false\n"), Map.of("SHADOWS", "false")));
    }

    @Test
    void undeclaredNamesNeverDisagreeButStillCountTowardsTheOrder() {
        PackProfiles profiles = profiles("""
                profile.A=QUALITY=1 FOG SHADOWS
                profile.J=X=1 Y=2 Z=3 W=4
                profile.E=
                """);
        PackProfiles.ProfileMatch scan = profiles.match(OptionValues.of(PROFILE_OPTIONS, Map.of()));
        assertEquals("J", scan.current().orElseThrow().name());
        assertEquals("A", scan.next().name());
        assertEquals("E", scan.previous().name());
        assertEquals("J", current(profiles, Map.of("QUALITY", "1", "FOG", "true")), "even where A matches too");
        assertEquals("E", current(profiles("profile.E=\nprofile.N=QUALITY=3\n"), Map.of()), "an empty profile matches");
    }
}
