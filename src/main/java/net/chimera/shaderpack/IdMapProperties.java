// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The id-map files (block.properties, item.properties, entity.properties) read as Iris reads them:
 * {@link #read} preprocesses and loads one as IdMap.loadProperties does, and {@link #ids} parses its keys.
 */
final class IdMapProperties {
    /** A continuation whose next lines preprocessing emptied goes on past them (IdMap.loadProperties). */
    private static final Pattern CONTINUED_PAST_EMPTY_LINES = Pattern.compile("\\\\\\n\\s*\\n");
    /**
     * IdMap.loadProperties starts a new line at each {@code block.} after other text on its line, dropping the
     * character before it, as Iris does.
     */
    private static final Pattern RUN_ON_BLOCK_KEY = Pattern.compile("\\S *block\\.");

    private IdMapProperties() {}

    /**
     * One file's entries in first-key order, each with its last value. {@code failure} is
     * {@code CONDITION_UNSUPPORTED} when an unevaluable conditional read as false, as JCPP reads it.
     */
    record Loaded(boolean present, String sourceHash, Map<String, String> entries, String failure) {}

    /** Reads {@code fileName} in the shaders folder, preprocessed with {@code macros}. */
    static Loaded read(Path shadersDir, String fileName, Map<String, String> macros) {
        Path file = shadersDir == null ? null : shadersDir.resolve(fileName);
        if (file == null || !Files.isRegularFile(file)) {
            return new Loaded(false, null, Map.of(), null);
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            return new Loaded(true, null, Map.of(), "READ_FAILED");
        }
        String hash = ConformanceReport.textSha256(bytes);
        // Iris reads id maps as ISO-8859-1, which never fails on a stray byte
        PropertiesPreprocessor.Preprocessed preprocessed =
                PropertiesPreprocessor.preprocess(new String(bytes, StandardCharsets.ISO_8859_1), macros);
        String text = CONTINUED_PAST_EMPTY_LINES.matcher(preprocessed.text()).replaceAll(" ");
        text = RUN_ON_BLOCK_KEY.matcher(text).replaceAll("\nblock.");
        try {
            return new Loaded(true, hash, PropertiesPreprocessor.load(text),
                    preprocessed.failed() ? "CONDITION_UNSUPPORTED" : null);
        } catch (IOException | IllegalArgumentException e) {
            return new Loaded(true, hash, Map.of(), "MALFORMED");
        }
    }

    /**
     * Iris's IdMap.parseIdMap over {@code <prefix><n>} keys: each name maps to n, a later key overriding an
     * earlier one. A name with a state selector is skipped; a key whose n is not an integer goes to
     * {@code invalidKeys}.
     */
    static Map<String, Integer> ids(Map<String, String> entries, String prefix, List<String> invalidKeys) {
        Map<String, Integer> ids = new LinkedHashMap<>();
        entries.forEach((key, value) -> {
            if (!key.startsWith(prefix)) return;
            int id;
            try {
                id = Integer.parseInt(key.substring(prefix.length()));
            } catch (NumberFormatException e) {
                invalidKeys.add(key);
                return;
            }
            for (String name : value.split("\\s+")) {
                if (!name.isEmpty() && !name.contains("=")) ids.put(namespaced(name), id);
            }
        });
        return ids;
    }

    /** Iris's NamespacedId: a name without a namespace is in {@code minecraft}. */
    static String namespaced(String name) {
        return name.indexOf(':') < 0 ? "minecraft:" + name : name;
    }
}
