// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The shaders.properties switches for the parts of Minecraft's sky, read as Iris's ShaderProperties reads them;
 * all default to on (Iris: MixinLevelRenderer_SunMoonToggle). With {@code sky} off, the horizon Iris draws ahead
 * of the sky goes too.
 */
public record SkyDirectives(boolean sun, boolean moon, boolean stars, boolean sky, List<String> deviations) {
    public static final SkyDirectives DEFAULTS = new SkyDirectives(true, true, true, true, List.of());

    public SkyDirectives {
        deviations = deviations == null ? List.of() : List.copyOf(deviations);
    }

    static SkyDirectives fromProperties(Map<String, String> properties) {
        List<String> deviations = new ArrayList<>();
        return new SkyDirectives(
                flag(properties, "sun", DEFAULTS.sun(), deviations),
                flag(properties, "moon", DEFAULTS.moon(), deviations),
                flag(properties, "stars", DEFAULTS.stars(), deviations),
                flag(properties, "sky", DEFAULTS.sky(), deviations),
                deviations);
    }

    private static boolean flag(Map<String, String> properties, String key, boolean fallback,
                                List<String> deviations) {
        return PackBooleanDirective.read(properties, key, fallback, deviations, "SKY_DIRECTIVE_MALFORMED:" + key);
    }
}
