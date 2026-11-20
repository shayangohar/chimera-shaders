// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import net.chimera.render.shader.PackGeometryContext;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdEndRenderingKHR;

/**
 * The render pass VulkanMod sees while one of Chimera's dynamic-rendering windows is open. VulkanMod keys
 * pipeline variants by render pass, so binding one makes the variant a dynamic-rendering pipeline for its
 * formats; one instance serves each set of formats and orientation, keeping the caches small. VulkanMod also
 * creates a Vulkan render pass for it, which is never begun.
 */
public final class ChimeraRenderingPass extends RenderPass {
    private static final Map<Key, ChimeraRenderingPass> PASSES = new HashMap<>();

    private final int[] colorFormats;
    private final int depthFormat;
    private final boolean glOriented;

    private record Key(String colors, int depth, boolean glOriented) {}

    private ChimeraRenderingPass(int[] colorFormats, int depthFormat, boolean glOriented) {
        super(new Formats(colorFormats[0], depthFormat),
                new AttachmentInfo(AttachmentInfo.Type.COLOR, colorFormats[0]),
                depthFormat == 0 ? null : new AttachmentInfo(AttachmentInfo.Type.DEPTH, depthFormat));
        this.colorFormats = colorFormats.clone();
        this.depthFormat = depthFormat;
        this.glOriented = glOriented;
    }

    /** The pass for these color attachment formats, depth format (0 for none) and row order. */
    public static synchronized ChimeraRenderingPass of(int[] colorFormats, int depthFormat, boolean glOriented) {
        if (colorFormats == null || colorFormats.length == 0) {
            throw new IllegalArgumentException("a rendering pass needs a color attachment");
        }
        return PASSES.computeIfAbsent(new Key(Arrays.toString(colorFormats), depthFormat, glOriented),
                key -> new ChimeraRenderingPass(colorFormats, depthFormat, glOriented));
    }

    public int[] colorFormats() {
        return colorFormats.clone();
    }

    /** The depth attachment format, or 0 when the window has none. */
    public int depthFormat() {
        return depthFormat;
    }

    /** Whether the window's images keep GL's bottom-up row order. */
    public boolean glOriented() {
        return glOriented;
    }

    /**
     * Ends the window where VulkanMod ends its render pass (Renderer.endRenderPass): a pack
     * geometry window hands its attachments back to sampling, any other just stops rendering.
     */
    @Override
    public void endRenderPass(VkCommandBuffer commandBuffer) {
        if (PackGeometryContext.active()) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PackGeometryContext.endRendering(commandBuffer, stack);
            }
        } else {
            vkCmdEndRenderingKHR(commandBuffer);
        }
        Renderer.getInstance().setBoundRenderPass(null);
    }

    /** The attachment formats VulkanMod reads from a bound pass's framebuffer; it has no images. */
    private static final class Formats extends Framebuffer {
        Formats(int colorFormat, int depthFormat) {
            this.format = colorFormat;
            this.depthFormat = depthFormat;
        }
    }
}
