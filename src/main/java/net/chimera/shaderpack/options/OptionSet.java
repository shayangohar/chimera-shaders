// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack.options;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The options a pack declares, toggles and choices apart, each with every place it is declared. A name may
 * be both a toggle and a choice.
 *
 * Both maps iterate as a {@code java.util.HashMap} would after the same insertions, replacements and
 * removals. That order reaches players: a menu without its own layout lists the options in it.
 */
public final class OptionSet {
    private static final OptionSet EMPTY = new OptionSet(Map.of(), Map.of());

    private final Map<String, Declared<ShaderOption.Toggle>> toggles;
    private final Map<String, Declared<ShaderOption.Choice>> choices;

    private OptionSet(Map<String, Declared<ShaderOption.Toggle>> toggles,
                      Map<String, Declared<ShaderOption.Choice>> choices) {
        this.toggles = toggles;
        this.choices = choices;
    }

    public static OptionSet empty() {
        return EMPTY;
    }

    public Map<String, Declared<ShaderOption.Toggle>> toggles() {
        return toggles;
    }

    public Map<String, Declared<ShaderOption.Choice>> choices() {
        return choices;
    }

    public boolean isToggle(String name) {
        return toggles.containsKey(name);
    }

    public boolean isChoice(String name) {
        return choices.containsKey(name);
    }

    /** A source line: {@code path} is pack-relative with a leading {@code /}, {@code line} counts from zero. */
    public record Location(String path, int line) {}

    /** An option with the places that declare it, in the order they were seen. */
    public record Declared<T extends ShaderOption>(T option, Set<Location> locations) {
        public Declared {
            locations = Collections.unmodifiableSet(new LinkedHashSet<>(locations));
        }
    }

    /**
     * Merges sightings of options. Sightings of one name that agree on the default merge; the first with a
     * comment supplies the option, else the latest. A disagreeing default drops the name and forgets its
     * places, and a later sighting starts it over.
     *
     * A pack is merged in two levels, each file's options first and the files after, so a conflict inside
     * one file does not drop what earlier files declared.
     */
    static final class Builder {
        private final Namespace<ShaderOption.Toggle> toggles = new Namespace<>();
        private final Namespace<ShaderOption.Choice> choices = new Namespace<>();
        private final Set<String> conflicts = new HashSet<>();

        void add(Location location, ShaderOption option) {
            switch (option) {
                case ShaderOption.Toggle toggle -> toggles.see(new Declared<>(toggle, Set.of(location)));
                case ShaderOption.Choice choice -> choices.see(new Declared<>(choice, Set.of(location)));
            }
        }

        /** Adds every name {@code other} holds as one sighting, with all its places, and its conflicts. */
        void addAll(Builder other) {
            other.toggles.declared.values().forEach(toggles::see);
            other.choices.declared.values().forEach(choices::see);
            conflicts.addAll(other.conflicts);
        }

        /** The names a conflict dropped here or in an added builder, unless declared again since. */
        Set<String> ambiguous() {
            Set<String> dropped = new HashSet<>(conflicts);
            dropped.removeIf(name -> toggles.declared.containsKey(name) || choices.declared.containsKey(name));
            return Collections.unmodifiableSet(dropped);
        }

        OptionSet build() {
            return new OptionSet(toggles.snapshot(), choices.snapshot());
        }

        /** One namespace's sightings, kept in a {@code HashMap} for its iteration order. */
        private final class Namespace<T extends ShaderOption> {
            private final Map<String, Declared<T>> declared = new HashMap<>();

            void see(Declared<T> sighting) {
                String name = sighting.option().name();
                Declared<T> known = declared.get(name);
                if (known == null) {
                    declared.put(name, sighting);
                } else if (known.option().defaultText().equals(sighting.option().defaultText())) {
                    Set<Location> locations = new LinkedHashSet<>(known.locations());
                    locations.addAll(sighting.locations());
                    T kept = known.option().comment().isPresent() ? known.option() : sighting.option();
                    declared.put(name, new Declared<>(kept, locations));
                } else {
                    declared.remove(name);
                    conflicts.add(name);
                }
            }

            /** The current state; a {@code LinkedHashMap} copy keeps the order. */
            Map<String, Declared<T>> snapshot() {
                return Collections.unmodifiableMap(new LinkedHashMap<>(declared));
            }
        }
    }
}
