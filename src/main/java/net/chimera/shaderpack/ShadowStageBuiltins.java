// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import java.util.Map;

/**
 * What a shadow program's stages before the rasterizer share: the legacy built-ins they read as the shadow
 * draw's own (TerrainVertexBridge, GeometryStageBridge), and the vertex stage's conversion of GL's clip
 * depth, [-w, w], to Vulkan's [0, w]. A geometry stage converts what it emits instead
 * (GeometryStageBridge.emitClipRange).
 */
final class ShadowStageBuiltins {
    /** The pack's own shadow {@code main}, renamed once the wrapper claims {@code main}. */
    static final String AUTHORED_SHADOW_ENTRY = "chimeraAuthoredShadowMain";

    private ShadowStageBuiltins() {}

    /** Converts the final clip depth after the pack's vertex code runs, unless a geometry stage follows. */
    static String vertexClipRange(String source, boolean geometryNext) {
        return geometryNext ? source : ClipDepth.afterEntry(source, AUTHORED_SHADOW_ENTRY);
    }

    /** Legacy matrix built-ins read the shadow draw's matrices; the camera's keep their Iris names. */
    static Map<String, String> matrixReplacements() {
        return Map.of(
                "gl_ModelViewMatrix", "shadowModelView",
                "gl_NormalMatrix", "mat3(transpose(shadowModelViewInverse))",
                "gl_ProjectionMatrix", "shadowProjection",
                "gl_ModelViewProjectionMatrix", "MVP");
    }

    /**
     * gl_TextureMatrix[1] and [2] read Iris's lightmap matrix and the rest identity; gl_VertexID reads Vulkan's
     * gl_VertexIndex, which shadow passes use to pick quads or voxels.
     */
    static String legacyBuiltins(String source) {
        String result = LegacyGlslConverter.replaceLightmapTextureMatrices(source).replaceAll(
                "\\bgl_TextureMatrix\\s*\\[\\s*\\d+\\s*\\]", "mat4(1.0)");
        return GlslTokenRewriter.replaceIdentifiers(result,
                Map.of(
                        "gl_VertexID", "gl_VertexIndex"));
    }
}
