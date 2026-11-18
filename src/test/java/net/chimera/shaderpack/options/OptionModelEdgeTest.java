// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack.options;

import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rules of the option model the acceptance cases leave open: on-edits of lines already on, and edge keys. */
final class OptionModelEdgeTest {
    private static OptionSet declare(AnnotatedSource source) {
        OptionSet.Builder builder = new OptionSet.Builder();
        source.options().forEach((index, option) -> builder.add(new OptionSet.Location("/s", index), option));
        return builder.build();
    }

    @Test
    void switchingOnLeavesADefineThatIsAlreadyOn() {
        AnnotatedSource source = AnnotatedSource.parse(List.of("#define T", "//#define T", "  //#define T"));
        OptionSet options = declare(source);
        assertTrue(options.isToggle("T") && !options.toggles().get("T").option().defaultValue(),
                "the conflict drops the first two lines and the third restarts T, off");
        assertEquals(List.of("#define T", "#define T", "#define T"),
                source.apply(OptionValues.of(options, Map.of("T", "true"))));
        assertSame(source.lines(), source.apply(OptionValues.of(options, Map.of("T", "false"))));
    }

    @Test
    void anEmptyScreenValueIsOneEmptyEntryAndACountAloneMakesNoScreen() {
        MenuProperties properties = MenuProperties.parse("screen=\nscreen.Q.columns=3\nscreen.R.columns=4\n"
                + "screen.R=\n");
        assertEquals(Optional.of(List.of("")), properties.mainScreen());
        OptionMenu menu = OptionMenu.build(properties, OptionSet.empty(), PackProfiles.empty());
        assertEquals(Optional.empty(), menu.subScreen("Q"));
        assertEquals(4, menu.subScreen("R").orElseThrow().columnCount());
        assertEquals(List.of("MENU_ELEMENT_UNKNOWN:", "MENU_ELEMENT_UNKNOWN:"), menu.problems());
    }

    @Test
    void aProgramSwitchKeepsItsRestVerbatimEvenEmpty() {
        PackProfiles profiles = PackProfiles.parse(Map.of("P", List.of("!program.", "!program.world-1/gbuffers_water",
                "!program.", "profile.")), OptionSet.empty());
        assertEquals(List.of("PROFILE_INVALID:P:missing profile "), profiles.problems());
        PackProfiles kept = PackProfiles.parse(Map.of("P", List.of("!program.", "!program.world-1/gbuffers_water",
                "!program.")), OptionSet.empty());
        assertEquals(List.of("", "world-1/gbuffers_water", ""), kept.declared().getFirst().disabledPrograms());
    }

    @Test
    void everyResultIsUnmodifiable() {
        AnnotatedSource source = AnnotatedSource.parse(List.of("#define T", "#ifdef T", "#define C 1 // [1 2]"));
        OptionSet options = declare(source);
        MenuProperties properties = MenuProperties.parse("screen=T C\nscreen.S=*\nprofile.P=T C=2 !program.x\n");
        PackProfiles profiles = PackProfiles.parse(properties.profiles(), options);
        OptionMenu menu = OptionMenu.build(properties, options, profiles);
        PackProfiles.Profile profile = profiles.declared().getFirst();
        for (Collection<?> collection : List.of(source.lines(), source.testedNames(),
                options.toggles().get("T").locations(), options.choices().get("C").option().values(),
                properties.mainScreen().orElseThrow(), properties.problems(), profiles.declared(), profiles.problems(),
                profile.disabledPrograms(), menu.main().elements(), menu.problems())) {
            assertThrows(UnsupportedOperationException.class, collection::clear);
        }
        for (Map<?, ?> map : List.of(source.options(), options.toggles(), options.choices(), properties.profiles(),
                profile.values(), menu.subScreens())) {
            assertThrows(UnsupportedOperationException.class, map::clear);
        }
    }
}
