package net.chimera.shaderpack.options;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The profiles shaders.properties declares: named sets of option values, which may include each other and
 * switch programs off. A profile that includes a missing profile, or itself, is left out with a problem
 * rather than failing the pack.
 */
public final class PackProfiles {
    private static final String PROGRAM = "!program.";
    private static final String INCLUDE = "profile.";
    private static final PackProfiles EMPTY = new PackProfiles(List.of(), List.of(), OptionSet.empty());

    private final List<Profile> declared;
    /** The order profiles are matched and cycled in: the most settings first, ties in declaration order. */
    private final List<Profile> mostSpecificFirst;
    private final List<String> problems;
    private final OptionSet options;

    private PackProfiles(List<Profile> declared, List<String> problems, OptionSet options) {
        this.declared = List.copyOf(declared);
        this.mostSpecificFirst = declared.stream()
                .sorted(Comparator.comparingInt((Profile profile) -> profile.values().size()).reversed()).toList();
        this.problems = List.copyOf(problems);
        this.options = options;
    }

    /** A profile's name, the option values it sets as text, and the programs it switches off. */
    public record Profile(String name, Map<String, String> values, List<String> disabledPrograms) {
        public Profile {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
            disabledPrograms = List.copyOf(disabledPrograms);
        }
    }

    /**
     * The profile the values match, if any, and the ones before and after it in matching order, wrapping
     * round; without a match, the first and the last. Both neighbours are null when there are no profiles.
     */
    public record ProfileMatch(Optional<Profile> current, Profile next, Profile previous) {}

    public static PackProfiles empty() {
        return EMPTY;
    }

    /** Resolves the {@code profile.<name>} token lists in their order, each against the declared options. */
    public static PackProfiles parse(Map<String, List<String>> entries, OptionSet options) {
        Resolver resolver = new Resolver(entries, options);
        List<Profile> declared = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (String name : entries.keySet()) {
            try {
                declared.add(resolver.resolve(name, List.of(name)));
            } catch (InvalidProfile invalid) {
                problems.add("PROFILE_INVALID:" + name + ":" + invalid.getMessage());
            }
        }
        return new PackProfiles(declared, problems, options);
    }

    /** The valid profiles in declaration order. */
    public List<Profile> declared() {
        return declared;
    }

    public List<String> problems() {
        return problems;
    }

    public ProfileMatch match(OptionValues values) {
        if (mostSpecificFirst.isEmpty()) return new ProfileMatch(Optional.empty(), null, null);
        int size = mostSpecificFirst.size();
        for (int index = 0; index < size; index++) {
            Profile profile = mostSpecificFirst.get(index);
            if (matches(profile, values)) {
                return new ProfileMatch(Optional.of(profile), mostSpecificFirst.get((index + 1) % size),
                        mostSpecificFirst.get((index + size - 1) % size));
            }
        }
        return new ProfileMatch(Optional.empty(), mostSpecificFirst.getFirst(), mostSpecificFirst.getLast());
    }

    /**
     * Whether every value the profile sets for a declared option is the effective one. A toggle compares
     * as the words {@code true} and {@code false}; a name that is both a toggle and a choice must agree as
     * both, and an undeclared name never disagrees.
     */
    private boolean matches(Profile profile, OptionValues values) {
        return profile.values().entrySet().stream().allMatch(setting -> {
            String name = setting.getKey();
            String text = setting.getValue();
            boolean toggleAgrees = !options.isToggle(name)
                    || text.equals(Boolean.toString(values.toggleOrDefault(name)));
            return toggleAgrees && (!options.isChoice(name) || text.equals(values.choiceOrDefault(name)));
        });
    }

    /** Expands profiles' tokens, following includes depth first. */
    private record Resolver(Map<String, List<String>> entries, OptionSet options) {
        /** The profile {@code name} with its includes expanded; {@code chain} holds the profiles being expanded. */
        Profile resolve(String name, List<String> chain) throws InvalidProfile {
            Map<String, String> values = new LinkedHashMap<>();
            List<String> disabled = new ArrayList<>();
            for (String token : entries.get(name)) {
                if (token.startsWith(PROGRAM)) {
                    disabled.add(token.substring(PROGRAM.length()));
                } else if (token.startsWith(INCLUDE)) {
                    Profile included = include(token.substring(INCLUDE.length()), chain);
                    values.putAll(included.values());
                    disabled.addAll(included.disabledPrograms());
                } else {
                    set(token, values);
                }
            }
            return new Profile(name, values, disabled);
        }

        private Profile include(String name, List<String> chain) throws InvalidProfile {
            if (!entries.containsKey(name)) throw new InvalidProfile("missing profile " + name);
            if (chain.contains(name)) throw new InvalidProfile("circular profile " + name);
            List<String> longer = new ArrayList<>(chain);
            longer.add(name);
            return resolve(name, longer);
        }

        /**
         * One value token: {@code !NAME} sets false, {@code NAME=VALUE} or else {@code NAME:VALUE} sets the
         * value, and a bare declared toggle sets true. Any other bare name sets nothing.
         */
        private void set(String token, Map<String, String> values) {
            if (token.startsWith("!")) {
                values.put(token.substring(1), "false");
                return;
            }
            int split = token.contains("=") ? token.indexOf('=') : token.indexOf(':');
            if (split >= 0) {
                values.put(token.substring(0, split), token.substring(split + 1));
            } else if (options.isToggle(token)) {
                values.put(token, "true");
            }
        }
    }

    /** Why a profile cannot be used: the first missing or circular include met. */
    private static final class InvalidProfile extends Exception {
        InvalidProfile(String reason) {
            super(reason, null, false, false);
        }
    }
}
