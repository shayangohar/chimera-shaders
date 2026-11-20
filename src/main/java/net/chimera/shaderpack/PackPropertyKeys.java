// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import java.util.List;
import java.util.Set;

/**
 * Sorts shaders.properties keys against what Iris 1.21.1's ShaderProperties reads. A key only Iris reads is
 * unsupported; a key neither reads is unknown.
 */
final class PackPropertyKeys {
    /** Prefixes Chimera reads: settings, resources, programs, directives and the option menu. */
    private static final List<String> APPLIED_PREFIXES = List.of(
            "profile.", "program.", "iris.features.", "size.buffer.", "flip.", "customTexture.", "texture.",
            "alphaTest.", "blend.", "indirect.", "image.", "bufferObject.", "uniform.", "variable.", "screen.",
            "particles.");

    /**
     * Keys Chimera reads by exact name. A source const such as shadowMapResolution is not a key here; neither
     * renderer reads it from this file.
     */
    private static final Set<String> APPLIED_NAMES = Set.of(
            "clouds", "shadow.culling", "shadow.enabled", "oldHandLight", "oldLighting", "separateAo", "shadowTerrain",
            "shadowTranslucent", "shadowEntities", "shadowPlayer", "shadowBlockEntities",
            "shadowLightBlockEntities", "underwaterOverlay", "vignette", "sun", "moon", "stars", "sky",
            "supportsColorCorrection", "voxelizeLightBlocks", "separateEntityDraws", "rain.depth", "screen",
            "sliders", "frustum.culling");

    /** ShaderProperties keys Iris 1.21.1 reads that Chimera does not apply. */
    private static final Set<String> IRIS_ONLY_NAMES = Set.of(
            "dhClouds", "dynamicHandLight", "backFace.solid", "backFace.cutout", "backFace.cutoutMipped",
            "backFace.translucent", "allowConcurrentCompute", "beacon.beam.depth",
            "occlusion.culling", "skipAllRendering", "dhShadow.enabled",
            "prepareBeforeShadow", "fallbackTex", "weather");
    private static final List<String> IRIS_ONLY_PREFIXES = List.of("scale.");

    private PackPropertyKeys() {}

    /** The deviation for a key Chimera does not apply, SETTING_UNSUPPORTED or SETTING_UNKNOWN; null if it applies. */
    static String unread(String key) {
        if (APPLIED_PREFIXES.stream().anyMatch(key::startsWith) || APPLIED_NAMES.contains(key)) {
            return null;
        }
        return IRIS_ONLY_NAMES.contains(key) || IRIS_ONLY_PREFIXES.stream().anyMatch(key::startsWith)
                ? "SETTING_UNSUPPORTED" : "SETTING_UNKNOWN";
    }
}
