package net.chimera.shaderpack;

import net.chimera.shaderpack.options.OptionDiscovery;
import net.chimera.shaderpack.options.OptionSet;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The pack's options as Iris preprocesses its id maps with them (IdMap, PropertiesPreprocessor). block.properties
 * and item.properties see every option the pack's shader files declare, whichever file declares it. Bliss, for
 * one, defines {@code FORCE_TRANSLUCENT_GLASS} in {@code lib/settings.glsl} and tests it around its
 * {@code layer.translucent} line.
 *
 * The options are the ones the settings screen discovers (OptionDiscovery) in the staged sources: a switch that
 * is on is defined as 1 and a value option takes its value, as Iris defines its boolean and string options.
 */
public final class PackOptionMacros {
    private PackOptionMacros() {}

    /**
     * The macros the id maps are preprocessed with: the options the shader files declare, with
     * {@code settings}, the settings plan's preprocessor defines, on top. An {@code overridden} switch with no
     * value in {@code settings} is turned off, so it stays undefined.
     *
     * shaders.properties' own defines are not among them, as Iris's IdMap preprocesses with options and
     * environment defines only.
     */
    public static Map<String, String> forIdMaps(Path shadersDir, Map<String, String> settings, Set<String> overridden) {
        OptionSet options = shadersDir == null || !Files.isDirectory(shadersDir) ? OptionSet.empty()
                : OptionDiscovery.discover(shadersDir).options();
        Map<String, String> macros = new TreeMap<>();
        options.toggles().forEach((name, declared) -> {
            if (declared.option().defaultValue()) macros.put(name, "1");
        });
        options.choices().forEach((name, declared) -> macros.put(name, declared.option().defaultValue()));
        if (overridden != null) macros.keySet().removeAll(overridden);
        if (settings != null) macros.putAll(settings);
        return macros;
    }
}
