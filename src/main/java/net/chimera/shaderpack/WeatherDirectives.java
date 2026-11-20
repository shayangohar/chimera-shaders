// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * shaders.properties {@code rain.depth}: rain and snow write depth, so later passes see them in
 * depthtex0. Off by default, as in Iris's PackDirectives.
 */
public record WeatherDirectives(boolean rainDepth, List<String> deviations) {
    public static final WeatherDirectives DEFAULTS = new WeatherDirectives(false, List.of());

    public WeatherDirectives {
        deviations = deviations == null ? List.of() : List.copyOf(deviations);
    }

    static WeatherDirectives fromProperties(Map<String, String> properties) {
        List<String> deviations = new ArrayList<>();
        boolean rainDepth = PackBooleanDirective.read(properties, "rain.depth", DEFAULTS.rainDepth(), deviations,
                "WEATHER_DIRECTIVE_MALFORMED:rain.depth");
        return new WeatherDirectives(rainDepth, deviations);
    }
}
