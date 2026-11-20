// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render.shader;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.config.ColorSpace;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;

import java.util.Locale;
import java.util.function.UnaryOperator;

/** Builds Chimera's own pipelines; the fullscreen ones generate the quad in the vertex stage, with no vertex buffer. */
public final class ChimeraPostPipelines {
    /**
     * A two-triangle quad over UV 0..1, like Iris's; packs that rescale post geometry need its edges on the
     * screen edges.
     */
    public static final int FULLSCREEN_VERTICES = 6;

    private ChimeraPostPipelines() {}

    public static GraphicsPipeline create(String name) {
        return HostPipelineLayout.create(name, name, CustomVertexFormat.NONE, UnaryOperator.identity());
    }

    /** Copies the world's forward [0, 1] depth, unchanged, into the pack's depth textures. */
    public static GraphicsPipeline createDepthPipeline() {
        return create("chimera_depth");
    }

    /** The world resolve that also converts to a non-sRGB {@code space}, selected by its ordinal. */
    public static GraphicsPipeline createColorSpace(ColorSpace space) {
        if (space == ColorSpace.SRGB) throw new IllegalArgumentException("sRGB needs no conversion");
        return HostPipelineLayout.create("chimera_color_space_" + space.name().toLowerCase(Locale.ROOT),
                "chimera_color_space", CustomVertexFormat.NONE, UnaryOperator.identity(),
                source -> colorSpaceSource(source, space));
    }

    /** Defines CHIMERA_COLOR_SPACE after the version line. */
    static String colorSpaceSource(String source, ColorSpace space) {
        int lineEnd = source.indexOf('\n');
        return source.substring(0, lineEnd + 1) + "#define CHIMERA_COLOR_SPACE " + space.ordinal() + "\n"
                + source.substring(lineEnd + 1);
    }

    /** A terrain-format shadow pipeline; it reads the ChunkOffset push constant DrawBuffers sets per chunk area. */
    public static GraphicsPipeline createTerrainPipeline(String name, VertexFormat vertexFormat) {
        return HostPipelineLayout.create(name, name, vertexFormat, UnaryOperator.identity());
    }
}
