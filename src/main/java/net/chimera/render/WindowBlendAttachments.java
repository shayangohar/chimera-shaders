// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import net.chimera.shaderpack.IrisColorFormats;
import net.chimera.shaderpack.PackBlendPlan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;

import static org.lwjgl.vulkan.VK10.VK_BLEND_OP_ADD;

/**
 * The blend attachments of a pipeline variant for a Chimera rendering window, or for one of
 * VulkanMod's render passes when the pack pipeline has a directive there. VulkanMod writes the
 * host draw's blend (the vanilla render type's, as in Iris) into attachment 0; every attachment
 * starts from it and takes the pack's blend directive for its buffer where one applies.
 */
public final class WindowBlendAttachments {
    private WindowBlendAttachments() {}

    /**
     * Fills {@code attachments}. A null mode keeps the host's blend. An attachment whose bit in {@code written}
     * is clear gets no colour writes and keeps its contents, as a GL draw buffer does. An integer format never
     * blends, as GL skips blending for it.
     *
     * The host's state is copied first, since attachment 0 is the first one overwritten. Kappa's gbuffers_water
     * turns blending off for colortex1 and leaves colortex3 to the host's translucent blend; read after its own
     * directive, attachment 0 would turn colortex3's blending off too and stained glass would draw black.
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
