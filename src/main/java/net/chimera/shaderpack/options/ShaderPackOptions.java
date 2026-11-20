// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack.options;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Everything a pack offers the player to configure, with the values applied. Loading never fails a pack;
 * what cannot be read goes to {@link #problems}.
 */
public final class ShaderPackOptions {
    private final OptionDiscovery discovery;
    private final OptionValues values;
    private final PackProfiles profiles;
    private final OptionMenu menu;
    private final PackLanguage language;
    private final List<String> problems;

    private ShaderPackOptions(OptionDiscovery discovery, OptionValues values, PackProfiles profiles,
                              OptionMenu menu, PackLanguage language, List<String> problems) {
        this.discovery = discovery;
        this.values = values;
        this.profiles = profiles;
        this.menu = menu;
        this.language = language;
        this.problems = List.copyOf(problems);
    }

    /** Discovers the options and applies {@code stored}, dropping undeclared names and default values. */
    public static ShaderPackOptions load(Path shadersRoot, Map<String, String> stored) {
        OptionDiscovery discovery = OptionDiscovery.discover(shadersRoot);
        OptionValues values = OptionValues.of(discovery.options(), stored);
        MenuProperties properties = MenuProperties.read(shadersRoot);
        PackProfiles profiles = PackProfiles.parse(properties.profiles(), discovery.options());
        OptionMenu menu = OptionMenu.build(properties, discovery.options(), profiles);
        List<String> problems = new ArrayList<>(discovery.failures());
        discovery.ambiguous().forEach(name -> problems.add("OPTION_AMBIGUOUS:" + name));
        problems.addAll(profiles.problems());
        problems.addAll(properties.problems());
        problems.addAll(menu.problems());
        return new ShaderPackOptions(discovery, values, profiles, menu, PackLanguage.read(shadersRoot), problems);
    }

    /** Nothing to configure, for a pack whose sources could not be read. */
    public static ShaderPackOptions empty() {
        OptionDiscovery discovery = OptionDiscovery.none();
        OptionMenu menu = OptionMenu.build(MenuProperties.empty(), discovery.options(), PackProfiles.empty());
        return new ShaderPackOptions(discovery, OptionValues.of(discovery.options(), Map.of()), PackProfiles.empty(),
                menu, PackLanguage.empty(), List.of());
    }

    public OptionSet options() {
        return discovery.options();
    }

    /** The values in effect; a copy is what a menu edits before applying. */
    public OptionValues values() {
        return values;
    }

    public PackProfiles profiles() {
        return profiles;
    }

    public OptionMenu menu() {
        return menu;
    }

    public PackLanguage language() {
        return language;
    }

    public List<String> problems() {
        return problems;
    }

    /** Whether every source file the includes name was read, so no declared option can be missing. */
    public boolean complete() {
        return discovery.failures().isEmpty();
    }

    /** Which profile the applied values match. */
    public PackProfiles.ProfileMatch profile() {
        return profiles.match(values);
    }

    /** Programs the matched profile switches off, as extensionless paths like {@code world0/composite1}. */
    public List<String> profileDisabledPrograms(OptionValues current) {
        return profiles.match(current).current().map(PackProfiles.Profile::disabledPrograms).orElse(List.of());
    }

    /** The source files {@code current} changes, with their new text, by pack-relative path. */
    public Map<String, String> editedFiles(OptionValues current) {
        return discovery.editedFiles(current);
    }
}
