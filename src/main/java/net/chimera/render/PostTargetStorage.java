// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import static org.lwjgl.vulkan.VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_GEOMETRY_SHADER_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TESSELLATION_CONTROL_SHADER_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TESSELLATION_EVALUATION_SHADER_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT;

/**
 * Holds the colour targets a post pass or hand program stores to as colorimgN in the general layout for its
 * draws. VulkanMod's tracked transitions throw on an image in GENERAL ("Unexpected value:1"), so each goes
 * back to the sampled layout after the draws, and is held only after any copies of it are recorded.
 */
final class PostTargetStorage {
    private final List<VulkanImage> held = new ArrayList<>();

    /** Stages that may store to a held target; a barrier may not name a stage the device lacks. */
    private static int shaderStages() {
        return VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
                | (DeviceManager.isTessellationEnabled() ? VK_PIPELINE_STAGE_TESSELLATION_CONTROL_SHADER_BIT
                        | VK_PIPELINE_STAGE_TESSELLATION_EVALUATION_SHADER_BIT : 0)
                | (ChimeraDevice.geometryShader() ? VK_PIPELINE_STAGE_GEOMETRY_SHADER_BIT : 0);
    }

    boolean holding() {
        return !this.held.isEmpty();
    }

    boolean holds(VulkanImage image) {
        return this.held.contains(image);
    }

    /** Moves each stored target's image into the general layout after every earlier use; outside a render pass. */
    void begin(VkCommandBuffer commandBuffer, IntFunction<VulkanImage> images, List<Integer> stored) {
        if (stored.isEmpty()) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int target : stored) {
                VulkanImage image = images.apply(target);
                if (image == null || this.held.contains(image)) continue;
                VulkanImage.transitionLayout(stack, commandBuffer, image, image.getCurrentLayout(),
                        VK_IMAGE_LAYOUT_GENERAL, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                        VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
                                | VK_ACCESS_TRANSFER_WRITE_BIT,
                        shaderStages(), VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
                this.held.add(image);
            }
        }
    }

    /** Returns the held images to the sampled layout after the draws. Recorded outside a render pass. */
    void end(VkCommandBuffer commandBuffer) {
        if (this.held.isEmpty()) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (VulkanImage image : this.held) {
                VulkanImage.transitionLayout(stack, commandBuffer, image, VK_IMAGE_LAYOUT_GENERAL,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, shaderStages(), VK_ACCESS_SHADER_WRITE_BIT,
                        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_SHADER_READ_BIT);
            }
        } finally {
            this.held.clear();
        }
    }
}
