// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The shaders.properties {@code oldHandLight} switch, on by default as in Iris: heldBlockLightValue then reports
 * the brighter hand.
 */
public record HandLightDirectives(boolean oldHandLight, List<String> deviations) {
    public static final HandLightDirectives DEFAULTS = new HandLightDirectives(true, List.of());

    public HandLightDirectives {
        deviations = deviations == null ? List.of() : List.copyOf(deviations);
    }

    static HandLightDirectives fromProperties(Map<String, String> properties) {
        List<String> deviations = new ArrayList<>();
        boolean oldHandLight = PackBooleanDirective.read(properties, "oldHandLight", DEFAULTS.oldHandLight(),
                deviations, "OLD_HAND_LIGHT_DIRECTIVE_MALFORMED");
        return new HandLightDirectives(oldHandLight, deviations);
    }
}
