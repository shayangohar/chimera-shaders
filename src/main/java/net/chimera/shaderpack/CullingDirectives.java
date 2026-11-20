// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code frustum.culling}, on by default as in Iris's PackDirectives; packs with an isometric camera or world
 * curvature turn it off.
 */
public record CullingDirectives(boolean frustumCulling, List<String> deviations) {
    public static final CullingDirectives DEFAULTS = new CullingDirectives(true, List.of());

    public CullingDirectives {
        deviations = deviations == null ? List.of() : List.copyOf(deviations);
    }

    static CullingDirectives fromProperties(Map<String, String> properties) {
        List<String> deviations = new ArrayList<>();
        boolean frustumCulling = PackBooleanDirective.read(properties, "frustum.culling", DEFAULTS.frustumCulling(),
                deviations, "CULLING_DIRECTIVE_MALFORMED:frustum.culling");
        return new CullingDirectives(frustumCulling, deviations);
    }
}
