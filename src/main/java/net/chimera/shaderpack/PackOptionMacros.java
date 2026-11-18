// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import net.chimera.shaderpack.options.OptionValues;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The pack's options as Iris preprocesses its id maps with them (IdMap, PropertiesPreprocessor). block.properties
 * and item.properties see every option the pack's shader files declare, whichever file declares it. Bliss, for
 * one, defines {@code FORCE_TRANSLUCENT_GLASS} in {@code lib/settings.glsl} and tests it around its
 * {@code layer.translucent} line.
 *
 * The options are the ones the settings screen discovers in the pack's own sources, at the values the sources
 * were staged with: a switch that is on is defined as 1 and a value option takes its value, as Iris defines its
 * boolean and string options. The staged sources can't be rescanned instead, since a changed value loses the
 * list that makes it an option.
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
    public static Map<String, String> forIdMaps(OptionValues values, Map<String, String> settings,
                                                Set<String> overridden) {
        Map<String, String> macros = new TreeMap<>();
        if (values != null) {
            values.options().toggles().keySet().forEach(name -> {
                if (values.toggleOrDefault(name)) macros.put(name, "1");
            });
            values.options().choices().keySet().forEach(name -> macros.put(name, values.choiceOrDefault(name)));
        }
        if (overridden != null) macros.keySet().removeAll(overridden);
        if (settings != null) macros.putAll(settings);
        return macros;
    }
}
