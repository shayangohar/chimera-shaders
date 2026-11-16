package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The shaders.properties switch for the camera's frustum culling, read as Iris reads it:
 * {@code frustum.culling} defaults to on (PackDirectives: {@code getFrustumCulling().orElse(true)}).
 * A pack whose projection shows what the perspective frustum misses turns it off: I Like Vanilla's
 * isometric camera, BSL's and Shrimple's world curvature, Ebin's terrain deformation.
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
