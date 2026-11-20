// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import net.chimera.config.ColorSpace;
import net.chimera.render.shader.ChimeraPostPipelines;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;

/** The pipelines that resolve the world into the output target: a plain one, and one per colour space on first use. */
final class OutputResolvePipelines {
    private static final Logger LOGGER = LoggerFactory.getLogger("chimera");
    private final Map<ColorSpace, GraphicsPipeline> conversions = new EnumMap<>(ColorSpace.class);
    private GraphicsPipeline plain;
    /** Set once a conversion failed to build; the plain resolve stands in until the next cleanup. */
    private boolean conversionFailed;

    /**
     * The output colour space: the setting while a pack runs that does not convert itself (Iris skips the
     * conversion when a pack sets supportsColorCorrection), otherwise sRGB.
     */
    static ColorSpace outputSpace(boolean packRuns, boolean packConvertsItself, ColorSpace setting) {
        return packRuns && !packConvertsItself ? setting : ColorSpace.SRGB;
    }

    void create() {
        this.plain = ChimeraPostPipelines.create("chimera_composite");
    }

    /** The resolve for {@code space}: the plain one for sRGB, or when its conversion could not be built. */
    GraphicsPipeline forSpace(ColorSpace space) {
        if (space == ColorSpace.SRGB || this.conversionFailed) return this.plain;
        GraphicsPipeline converted = this.conversions.get(space);
        if (converted != null) return converted;
        try {
            converted = ChimeraPostPipelines.createColorSpace(space);
        } catch (RuntimeException failure) {
            this.conversionFailed = true;
            LOGGER.warn("[chimera] colour space {}: conversion unavailable, output stays sRGB", space, failure);
            return this.plain;
        }
        this.conversions.put(space, converted);
        return converted;
    }

    boolean owns(GraphicsPipeline pipeline) {
        return pipeline != null && (pipeline == this.plain || this.conversions.containsValue(pipeline));
    }

    void cleanUp() {
        if (this.plain != null) this.plain.cleanUp();
        this.conversions.values().forEach(GraphicsPipeline::cleanUp);
        this.conversions.clear();
        this.plain = null;
        this.conversionFailed = false;
    }
}
