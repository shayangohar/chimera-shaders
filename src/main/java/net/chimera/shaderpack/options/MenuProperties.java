// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack.options;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * The menu entries of a pack's shaders.properties: the screens, their column counts, the sliders and the
 * profiles. The file is read as plain properties: {@code #if} and {@code #define} lines are comments, so
 * where a key appears in several branches the last value wins.
 *
 * @param mainScreen the {@code screen} entries
 * @param subScreens the {@code screen.<id>} entries, iterating as a {@code HashMap} keyed by id and filled in
 *                   file order; which sub-screen takes the {@code *} fill depends on it
 * @param profiles the {@code profile.<name>} entries, in the order the keys first appear
 * @param problems one {@code MENU_COLUMNS_MALFORMED} entry for each column count that is no integer
 */
public record MenuProperties(Optional<List<String>> mainScreen, Optional<Integer> mainColumns,
                             Map<String, List<String>> subScreens, Map<String, Integer> subScreenColumns,
                             Set<String> sliders, Map<String, List<String>> profiles, List<String> problems) {
    private static final String SCREEN = "screen.";
    private static final String COLUMNS = ".columns";
    private static final String PROFILE = "profile.";

    public MenuProperties {
        mainScreen = mainScreen.map(List::copyOf);
        subScreens = frozen(subScreens);
        subScreenColumns = Map.copyOf(subScreenColumns);
        sliders = Set.copyOf(sliders);
        profiles = frozen(profiles);
        problems = List.copyOf(problems);
    }

    public static MenuProperties empty() {
        return parse("");
    }

    /** The shaders.properties under {@code shadersRoot}, as ISO-8859-1; empty when it is no readable file. */
    public static MenuProperties read(Path shadersRoot) {
        Path file = shadersRoot.resolve("shaders.properties");
        if (!Files.isRegularFile(file)) return empty();
        try {
            return parse(Files.readString(file, StandardCharsets.ISO_8859_1));
        } catch (IOException unreadable) {
            return empty();
        }
    }

    static MenuProperties parse(String contents) {
        Optional<List<String>> main = Optional.empty();
        Optional<Integer> mainColumns = Optional.empty();
        Map<String, List<String>> subScreens = new HashMap<>();
        Map<String, Integer> subScreenColumns = new HashMap<>();
        Set<String> sliders = Set.of();
        Map<String, List<String>> profiles = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> entry : orderedEntries(new StringReader(contents)).entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key.equals("screen")) {
                main = Optional.of(list(value));
            } else if (key.equals(SCREEN + "columns")) {
                mainColumns = columns(key, value, problems);
            } else if (key.startsWith(SCREEN) && key.endsWith(COLUMNS)
                    && key.length() > SCREEN.length() + COLUMNS.length()) {
                // a malformed count stays a count key and makes no screen
                String id = key.substring(SCREEN.length(), key.length() - COLUMNS.length());
                columns(key, value, problems).ifPresent(count -> subScreenColumns.put(id, count));
            } else if (key.startsWith(SCREEN)) {
                subScreens.put(key.substring(SCREEN.length()), list(value));
            } else if (key.equals("sliders")) {
                sliders = Set.copyOf(list(value));
            } else if (key.startsWith(PROFILE)) {
                profiles.put(key.substring(PROFILE.length()), list(value));
            }
        }
        return new MenuProperties(main, mainColumns, subScreens, subScreenColumns, sliders, profiles, problems);
    }

    /**
     * Reads properties exactly as {@link Properties#load(Reader)} does, keeping each key where it first
     * appears with the value it last had. A malformed unicode escape or a read failure ends the read and
     * keeps what came before.
     */
    static Map<String, String> orderedEntries(Reader reader) {
        Map<String, String> entries = new LinkedHashMap<>();
        Properties recorder = new Properties() {
            @Override
            public synchronized Object put(Object key, Object value) {
                return entries.put((String) key, (String) value);
            }
        };
        try {
            recorder.load(reader);
        } catch (IOException | IllegalArgumentException stopped) {
            // the entries read so far stand
        }
        return Collections.unmodifiableMap(entries);
    }

    /** A list value, split on runs of spaces only; an empty value is one empty entry. */
    private static List<String> list(String value) {
        return List.of(value.split(" +"));
    }

    private static Optional<Integer> columns(String key, String value, List<String> problems) {
        try {
            return Optional.of(Integer.parseInt(value));
        } catch (NumberFormatException malformed) {
            problems.add("MENU_COLUMNS_MALFORMED:" + key + "=" + value);
            return Optional.empty();
        }
    }

    private static Map<String, List<String>> frozen(Map<String, List<String>> lists) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        lists.forEach((key, list) -> copy.put(key, List.copyOf(list)));
        return Collections.unmodifiableMap(copy);
    }
}
