// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import net.chimera.shaderpack.options.OptionFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Where a pack's option values come from: Iris's {@code <pack>.txt} beside the pack, so one file drives both
 * renderers, with {@code chimera.option.*} JVM properties on top for automated runs.
 */
public final class PackOptionSources {
    private static final String OPTION_OVERRIDE_PREFIX = "chimera.option.";

    private PackOptionSources() {}

    /** Iris's per-pack option file for this pack, or null when there is none. */
    public static Path optionFile(Path packPath) {
        if (packPath == null || packPath.getFileName() == null) return null;
        Path file = OptionFile.of(packPath);
        return Files.isRegularFile(file) ? file : null;
    }

    /** {@code chimera.option.NAME=value} JVM properties, keyed by NAME. */
    static Map<String, String> systemOverrides() {
        Map<String, String> result = new TreeMap<>();
        for (String property : System.getProperties().stringPropertyNames()) {
            if (!property.startsWith(OPTION_OVERRIDE_PREFIX)) continue;
            String name = property.substring(OPTION_OVERRIDE_PREFIX.length());
            result.put(name, System.getProperty(property, ""));
        }
        return result;
    }
}
