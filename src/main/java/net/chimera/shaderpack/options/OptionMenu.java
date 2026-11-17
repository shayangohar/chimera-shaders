package net.chimera.shaderpack.options;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The settings screens shaders.properties lays out over a pack's options: a main screen and the sub-screens
 * its links open. Entries that name nothing become blank cells with a problem.
 */
public final class OptionMenu {
    /** More elements than this get three columns by default, otherwise two. */
    private static final int TWO_COLUMN_LIMIT = 18;

    private final Screen main;
    private final Map<String, Screen> subScreens;
    private final List<String> problems;

    private OptionMenu(Screen main, Map<String, Screen> subScreens, List<String> problems) {
        this.main = main;
        this.subScreens = Collections.unmodifiableMap(subScreens);
        this.problems = List.copyOf(problems);
    }

    public sealed interface Element permits Empty, ProfileButton, Link, OptionEntry {}

    /** A blank cell. */
    public record Empty() implements Element {}

    /** The button that cycles the pack's profiles. */
    public record ProfileButton() implements Element {}

    /** A button opening another screen, which need not exist. */
    public record Link(String screen) implements Element {}

    public record OptionEntry(ShaderOption option, boolean slider) implements Element {}

    /** A screen; the main screen's id is null. */
    public record Screen(String id, List<Element> elements, Optional<Integer> columns) {
        public Screen {
            elements = List.copyOf(elements);
        }

        /** The column count the pack gives, any integer, or one that suits the number of elements. */
        public int columnCount() {
            return columns.orElse(elements.size() > TWO_COLUMN_LIMIT ? 3 : 2);
        }
    }

    /**
     * Lays out the screens. The main screen without a {@code screen} key is a lone {@code *}. The first
     * {@code *}, counting the main screen then the sub-screens in their iteration order, lists every option
     * no sub-screen names, toggles then choices; every later {@code *} adds nothing.
     */
    public static OptionMenu build(MenuProperties properties, OptionSet options, PackProfiles profiles) {
        Layout layout = new Layout(options, properties.sliders(), !profiles.declared().isEmpty(),
                unplaced(properties, options));
        Screen main = layout.screen(null, properties.mainScreen().orElse(List.of("*")), properties.mainColumns());
        Map<String, Screen> subScreens = new LinkedHashMap<>();
        properties.subScreens().forEach((id, entries) -> subScreens.put(id,
                layout.screen(id, entries, Optional.ofNullable(properties.subScreenColumns().get(id)))));
        return new OptionMenu(main, subScreens, layout.problems);
    }

    public Screen main() {
        return main;
    }

    public Optional<Screen> subScreen(String id) {
        return Optional.ofNullable(subScreens.get(id));
    }

    /** The sub-screens by id, in the order they were laid out. */
    public Map<String, Screen> subScreens() {
        return subScreens;
    }

    /** One {@code MENU_ELEMENT_UNKNOWN} entry per unknown entry: the main screen's, then each sub-screen's. */
    public List<String> problems() {
        return problems;
    }

    /**
     * The names the fill lists: every toggle, then every choice, so a name that is both comes twice, less
     * one occurrence for each time a sub-screen names it. The main screen's own names stay.
     */
    private static List<String> unplaced(MenuProperties properties, OptionSet options) {
        List<String> names = new ArrayList<>(options.toggles().keySet());
        names.addAll(options.choices().keySet());
        for (List<String> entries : properties.subScreens().values()) {
            for (String entry : entries) names.remove(entry);
        }
        return names;
    }

    /** Turns screen entries into elements, handing the fill to the first {@code *}. */
    private static final class Layout {
        private final OptionSet options;
        private final Set<String> sliders;
        private final boolean profileButton;
        private final List<String> problems = new ArrayList<>();
        private List<String> fill;

        Layout(OptionSet options, Set<String> sliders, boolean profileButton, List<String> fill) {
            this.options = options;
            this.sliders = sliders;
            this.profileButton = profileButton;
            this.fill = fill;
        }

        Screen screen(String id, List<String> entries, Optional<Integer> columns) {
            List<Element> elements = new ArrayList<>();
            for (String entry : entries) {
                if (!entry.equals("*")) {
                    element(entry).ifPresent(elements::add);
                } else if (fill != null) {
                    fill.forEach(name -> elements.add(option(name)));
                    fill = null;
                }
            }
            return new Screen(id, elements, columns);
        }

        /** The element an entry gives; none for the profile button of a pack without profiles. */
        private Optional<Element> element(String entry) {
            if (entry.equals("<empty>")) return Optional.of(new Empty());
            if (entry.equals("<profile>")) return profileButton ? Optional.of(new ProfileButton()) : Optional.empty();
            if (entry.length() >= 2 && entry.startsWith("[") && entry.endsWith("]")) {
                return Optional.of(new Link(entry.substring(1, entry.length() - 1)));
            }
            if (options.isToggle(entry) || options.isChoice(entry)) return Optional.of(option(entry));
            problems.add("MENU_ELEMENT_UNKNOWN:" + entry);
            return Optional.of(new Empty());
        }

        /** The option a declared name gives, the toggle where it is both. */
        private OptionEntry option(String name) {
            ShaderOption option = options.isToggle(name) ? options.toggles().get(name).option()
                    : options.choices().get(name).option();
            return new OptionEntry(option, sliders.contains(name));
        }
    }
}
