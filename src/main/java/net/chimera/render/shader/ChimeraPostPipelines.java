// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render.shader;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.config.ColorSpace;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;

import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * Builds Chimera's own pipelines from assets/chimera/shaders/. The fullscreen ones use
 * CustomVertexFormat.NONE: the vertex stage generates the quad, with no vertex buffer.
 */
public final class ChimeraPostPipelines {
    /**
     * Vertices of every fullscreen draw: a two-triangle quad over UV 0..1, like Iris's fullscreen quad. Packs
     * that rescale the post geometry (Bliss draws clouds and bloom tiles into a sub-rectangle) need its edges
     * to stay on the screen edges.
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

    /**
     * The world resolve that also converts to {@code space} (chimera_color_space), for a colour space other
     * than sRGB; the space's ordinal selects the conversion.
     */
    public static GraphicsPipeline createColorSpace(ColorSpace space) {
        if (space == ColorSpace.SRGB) throw new IllegalArgumentException("sRGB needs no conversion");
        return HostPipelineLayout.create("chimera_color_space_" + space.name().toLowerCase(Locale.ROOT),
                "chimera_color_space", CustomVertexFormat.NONE, UnaryOperator.identity(),
                source -> colorSpaceSource(source, space));
    }

    /** The conversion source for {@code space}: its CHIMERA_COLOR_SPACE defined after the version line. */
    static String colorSpaceSource(String source, ColorSpace space) {
        int lineEnd = source.indexOf('\n');
        return source.substring(0, lineEnd + 1) + "#define CHIMERA_COLOR_SPACE " + space.ordinal() + "\n"
                + source.substring(lineEnd + 1);
    }

    /**
     * A terrain-format pipeline for shadow rendering. It reads the section offset and the ChunkOffset push
     * constant VulkanMod's DrawBuffers sets per chunk area.
     */
    public static GraphicsPipeline createTerrainPipeline(String name, VertexFormat vertexFormat) {
        return HostPipelineLayout.create(name, name, vertexFormat, UnaryOperator.identity());
    }
}
