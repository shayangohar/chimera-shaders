// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * dimension.properties, read as Iris 1.21.1's ShaderPack reads it.
 *
 * The file goes through PropertiesPreprocessor with Iris's environment defines and no pack options, then
 * OrderBackedProperties. Each world a {@code dimension.<folder>} key lists maps to that folder, and a later
 * key overrides an earlier one (parseDimensionMap). The same keys list the folders the option scan reads
 * (parseDimensionIds). A world list may continue onto the next line.
 */
public final class DimensionProperties {
    private static final String PREFIX = "dimension.";

    private DimensionProperties() {}

    /**
     * Each world the file maps to its folder. {@code present} is Iris's test that the file loads any
     * property; without one, Iris maps world0, world-1 and world1 as if there were no file.
     */
    record Folders(Map<String, String> worlds, boolean present) {}

    /** The file read with the environment defines a pack's dimension.properties sees. */
    static Folders read(Path shadersDir, List<String> deviations) {
        return read(shadersDir, environment(), deviations);
    }

    static Folders read(Path shadersDir, Map<String, String> macros, List<String> deviations) {
        Map<String, String> entries = entries(shadersDir, macros, deviations);
        if (entries == null) return new Folders(Map.of(), false);
        Map<String, String> worlds = new TreeMap<>();
        entries.forEach((key, value) -> {
            if (!key.startsWith(PREFIX)) return;
            String folder = key.substring(PREFIX.length());
            // the folder is a path under the pack, so only a plain name is read
            if (!folder.matches("[A-Za-z0-9_-]+")) return;
            for (String world : value.trim().split("\\s+")) {
                if (world.isEmpty()) continue;
                worlds.put(world.equals("*") ? "*" : PackSource.normalizeDimension(world), folder);
            }
        });
        return new Folders(Map.copyOf(worlds), !entries.isEmpty());
    }

    /**
     * The folders the file's dimension keys name, in file order, as Iris's parseDimensionIds lists them for
     * its option scan. Empty when the file is missing or loads no property; Iris then scans the world folders
     * that exist.
     */
    public static Optional<List<String>> folderNames(Path shadersDir) {
        Map<String, String> entries = entries(shadersDir, environment(), new ArrayList<>());
        if (entries == null || entries.isEmpty()) return Optional.empty();
        return Optional.of(entries.keySet().stream().filter(key -> key.startsWith(PREFIX))
                .map(key -> key.substring(PREFIX.length())).toList());
    }

    /**
     * Iris's environment defines, as near as Chimera has them: the standard set with IS_IRIS and the biome,
     * category and precipitation names of IrisDefines. Iris's GL-extension and DH_BLOCK_* names are missing.
     */
    private static Map<String, String> environment() {
        return PackEngineDefines.forPack(Map.of());
    }

    /** The file's properties after Iris's preprocessing, or null when it is missing or unreadable. */
    private static Map<String, String> entries(Path shadersDir, Map<String, String> macros,
                                               List<String> deviations) {
        Path file = shadersDir.resolve("dimension.properties");
        if (!Files.isRegularFile(file)) return null;
        PropertiesPreprocessor.Preprocessed text;
        try {
            // ISO-8859-1 as in Iris, which never fails on a stray byte
            text = PropertiesPreprocessor.preprocess(Files.readString(file, StandardCharsets.ISO_8859_1), macros);
        } catch (IOException e) {
            return null;
        }
        if (text.failed() || text.open()) deviations.add("PREPROCESSOR_CONDITION_UNSUPPORTED");
        try {
            return PropertiesPreprocessor.load(text.text());
        } catch (IOException | IllegalArgumentException e) {
            deviations.add("DIMENSION_PROPERTIES_MALFORMED");
            return null;
        }
    }
}
