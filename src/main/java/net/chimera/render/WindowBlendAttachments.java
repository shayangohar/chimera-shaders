// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import net.chimera.shaderpack.IrisColorFormats;
import net.chimera.shaderpack.PackBlendPlan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;

import static org.lwjgl.vulkan.VK10.VK_BLEND_OP_ADD;

/**
 * Per-attachment blends of a pipeline variant: each starts from the host blend VulkanMod writes into attachment 0
 * and takes the pack's directive where one applies.
 */
public final class WindowBlendAttachments {
    private WindowBlendAttachments() {}

    /**
     * A null mode keeps the host's blend; an unwritten attachment keeps its contents, and an integer format never
     * blends, as in GL. The host state is copied first, since overwriting attachment 0 would otherwise leak its
     * directive into later attachments.
     */
    public static void fill(VkPipelineColorBlendAttachmentState.Buffer attachments, PackBlendPlan.Mode[] blends,
                            int written, int[] formats) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineColorBlendAttachmentState host =
                    VkPipelineColorBlendAttachmentState.malloc(stack).set(attachments.get(0));
            for (int index = 0; index < attachments.capacity(); index++) {
                VkPipelineColorBlendAttachmentState target = attachments.get(index);
                PackBlendPlan.Mode mode = blends == null ? null : blends[index];
                target.colorWriteMask((written >>> index & 1) != 0 ? host.colorWriteMask() : 0);
                if (mode == null) {
                    target.blendEnable(host.blendEnable())
                            .srcColorBlendFactor(host.srcColorBlendFactor())
                            .dstColorBlendFactor(host.dstColorBlendFactor())
                            .colorBlendOp(host.colorBlendOp())
                            .srcAlphaBlendFactor(host.srcAlphaBlendFactor())
                            .dstAlphaBlendFactor(host.dstAlphaBlendFactor())
                            .alphaBlendOp(host.alphaBlendOp());
                } else {
                    target.blendEnable(mode.enabled())
                            .srcColorBlendFactor(mode.srcColor())
                            .dstColorBlendFactor(mode.dstColor())
                            .colorBlendOp(VK_BLEND_OP_ADD)
                            .srcAlphaBlendFactor(mode.srcAlpha())
                            .dstAlphaBlendFactor(mode.dstAlpha())
                            .alphaBlendOp(VK_BLEND_OP_ADD);
                }
                if (IrisColorFormats.integer(formats[index])) target.blendEnable(false);
            }
        }
    }
}
