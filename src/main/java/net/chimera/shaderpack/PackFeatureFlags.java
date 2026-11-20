// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Iris's shader-pack feature flags, checked against the features Chimera provides. A pack that requires a flag
 * Iris cannot provide is refused; a flag whose feature Chimera lacks stays undefined, so the pack takes its path
 * without it.
 */
public final class PackFeatureFlags {
    static final String TESSELLATION_SHADERS = "TESSELLATION_SHADERS";
    static final String HIGHER_SHADOWCOLOR = "HIGHER_SHADOWCOLOR";
    static final String SEPARATE_HARDWARE_SAMPLERS = "SEPARATE_HARDWARE_SAMPLERS";

    /**
     * The Iris 1.21.1 FeatureFlags Chimera provides, apart from CUSTOM_IMAGES. TESSELLATION_SHADERS and
     * CAN_DISABLE_WEATHER are not provided.
     */
    static final Set<String> PROVIDED = Set.of("PER_BUFFER_BLENDING", "COMPUTE_SHADERS", "ENTITY_TRANSLUCENT",
            "REVERSED_CULLING", "BLOCK_EMISSION_ATTRIBUTE", "SSBO", HIGHER_SHADOWCOLOR, SEPARATE_HARDWARE_SAMPLERS);
    /**
     * Program sources see this flag per pack once Chimera knows it can produce the images
     * ({@link PackEngineDefines#forCustomImages}); shaders.properties always sees it, as under Iris. A pack that
     * requires it still loads, and programs that need images fall back.
     */
    static final String CUSTOM_IMAGES = "CUSTOM_IMAGES";
    private static final String PREFIX = "IRIS_FEATURE_";
    private static final Map<String, String> PROPERTY_MACROS = propertyMacroTable();

    private PackFeatureFlags() {}

    private static Map<String, String> propertyMacroTable() {
        Map<String, String> macros = new TreeMap<>();
        PROVIDED.forEach(flag -> macros.put(PREFIX + flag, "1"));
        macros.put(PREFIX + CUSTOM_IMAGES, "1");
        return Collections.unmodifiableMap(macros);
    }

    /**
     * The feature macros shaders.properties is read with: every usable flag, listed by the pack or not, custom
     * images included, as Iris's FeatureFlags.isUsable defines them.
     */
    static Map<String, String> propertyMacros() {
        return PROPERTY_MACROS;
    }

    /** Program-source defines for the optional flags Chimera provides, spelled as the pack wrote them. */
    static Map<String, String> sourceDefines(Collection<String> optional) {
        Map<String, String> defines = new TreeMap<>();
        for (String flag : optional) {
            if (PROVIDED.contains(canonical(flag)) && flag.matches("\\w+")) defines.put(PREFIX + flag, "1");
        }
        return defines;
    }

    /** The required flags Chimera cannot provide, unknown names included, as the pack wrote them. */
    static Set<String> unprovided(Collection<String> required) {
        Set<String> missing = new TreeSet<>();
        for (String flag : required) {
            String name = canonical(flag);
            if (!PROVIDED.contains(name) && !CUSTOM_IMAGES.equals(name)) missing.add(flag);
        }
        return missing;
    }

    /** Whether the pack lists {@code flag} as required or optional, as Iris's ShaderPack.hasFeature reads. */
    static boolean active(Collection<String> required, Collection<String> optional, String flag) {
        return required.stream().anyMatch(value -> canonical(value).equals(flag))
                || optional.stream().anyMatch(value -> canonical(value).equals(flag));
    }

    /** A flag as Iris names it to the player: {@code TESSELLATION_SHADERS} reads "Tessellation shaders". */
    public static String readableName(String flag) {
        String words = canonical(flag).replace('_', ' ').toLowerCase(Locale.ROOT);
        return words.isEmpty() ? words : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    /** The flag's Iris name; Iris also takes the misspelling TESSELATION_SHADERS. */
    private static String canonical(String flag) {
        String name = flag.toUpperCase(Locale.ROOT);
        return name.equals("TESSELATION_SHADERS") ? "TESSELLATION_SHADERS" : name;
    }
}
