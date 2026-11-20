// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import net.chimera.shaderpack.options.OptionValues;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The pack's options as Iris preprocesses its id maps with them: every option the shader files declare, at the
 * staged values. The staged sources can't be rescanned instead, since a changed value loses the list that makes
 * it an option.
 */
public final class PackOptionMacros {
    private PackOptionMacros() {}

    /**
     * The declared options with {@code settings}, the preprocessor defines, on top; an {@code overridden} switch
     * absent from {@code settings} stays undefined. As in Iris, shaders.properties' own defines are left out.
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
