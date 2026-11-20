// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.chimera.render.ChimeraDevice;
import net.chimera.render.ChimeraRenderingPass;
import net.chimera.render.WindowBlendAttachments;
import net.chimera.render.shader.MrtPipelineContext;
import net.chimera.shaderpack.IrisColorFormats;
import net.chimera.shaderpack.PackBlendPlan;
import net.chimera.shaderpack.SpirvOutputWrites;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.PipelineState;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfoKHR;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Arrays;

import static org.lwjgl.system.MemoryStack.stackGet;
import static org.lwjgl.vulkan.KHRDynamicRendering.VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO_KHR;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;

/**
 * Pipelines for Chimera's dynamic-rendering windows: no Vulkan render pass, and one blend attachment per
 * colour attachment, from the host draw's blend unless the pack's MRT contract gives a directive. Locations
 * the fragment stage never writes get no colour writes. A variant for a VulkanMod render pass keeps that pass.
 */
@Mixin(value = GraphicsPipeline.class, remap = false)
public abstract class GraphicsPipelineMrtMixin {
    /** The window the variant being created renders into; null for a VulkanMod render pass. */
    @Unique
    private ChimeraRenderingPass chimera$window;

    /** The colour format of the VulkanMod render pass the variant renders into; 0 when unknown. */
    @Unique
    private int chimera$hostFormat;

    /** Scopes the pipeline-creation contract, so a creation that throws cannot leave it for later variants. */
    @WrapMethod(method = "createGraphicsPipeline")
    private long chimera$variant(PipelineState state, Operation<Long> original) {
        RenderPass renderPass = ((ChimeraPipelineStateAccessor) state).chimera$renderPass();
        this.chimera$window = renderPass instanceof ChimeraRenderingPass window ? window : null;
        this.chimera$hostFormat = renderPass == null || renderPass.getFramebuffer() == null
                ? 0 : renderPass.getFramebuffer().getFormat();
        MrtPipelineContext.beginPipeline((GraphicsPipeline) (Object) this);
        try {
            return original.call(state);
        } finally {
            MrtPipelineContext.endPipeline();
            this.chimera$window = null;
        }
    }

    /** A window variant has no render pass. */
    @Redirect(method = "createGraphicsPipeline",
            at = @At(value = "INVOKE", target = "Lnet/vulkanmod/vulkan/framebuffer/RenderPass;getId()J"))
    private long chimera$renderPass(RenderPass renderPass) {
        return this.chimera$window != null ? VK_NULL_HANDLE : renderPass.getId();
    }

    @Redirect(method = "createGraphicsPipeline",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VkPipelineColorBlendAttachmentState;"
                    + "calloc(ILorg/lwjgl/system/MemoryStack;)"
                    + "Lorg/lwjgl/vulkan/VkPipelineColorBlendAttachmentState$Buffer;"))
    private VkPipelineColorBlendAttachmentState.Buffer chimera$colorBlendAttachments(int hostCount,
                                                                                      MemoryStack stack) {
        int count = this.chimera$window == null ? hostCount : this.chimera$window.colorFormats().length;
        return VkPipelineColorBlendAttachmentState.calloc(count, stack);
    }

    @ModifyArg(method = "createGraphicsPipeline",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VkPipelineColorBlendStateCreateInfo;"
                    + "pAttachments(Lorg/lwjgl/vulkan/VkPipelineColorBlendAttachmentState$Buffer;)"
                    + "Lorg/lwjgl/vulkan/VkPipelineColorBlendStateCreateInfo;"))
    private VkPipelineColorBlendAttachmentState.Buffer chimera$copyBlendState(
            VkPipelineColorBlendAttachmentState.Buffer attachments) {
        if (this.chimera$window == null) return chimera$hostPassBlend(attachments);
        PackBlendPlan.Mode[] blends = MrtPipelineContext.attachmentBlends();
        int written = MrtPipelineContext.writtenOutputs();
        // a contract for another attachment set does not describe this window
        if (blends != null && blends.length != attachments.capacity()) blends = null;
        // without independentBlend every attachment must blend and mask alike: one shared blend, or none
        if (!ChimeraDevice.independentBlend()) {
            int[] formats = this.chimera$window.colorFormats();
            boolean[] integer = new boolean[attachments.capacity()];
            for (int index = 0; index < integer.length; index++) {
                integer[index] = IrisColorFormats.integer(formats[index]);
            }
            blends = PackBlendPlan.sharedAttachments(blends, integer);
            // a depth-only module masks every attachment alike; any other mask must cover them all
            if (written != 0) written = SpirvOutputWrites.ALL;
        }
        WindowBlendAttachments.fill(attachments, blends, written, this.chimera$window.colorFormats());
        return attachments;
    }

    /** A VulkanMod render pass's attachments with the pack pipeline's directive, where it has one. */
    @Unique
    private VkPipelineColorBlendAttachmentState.Buffer chimera$hostPassBlend(
            VkPipelineColorBlendAttachmentState.Buffer attachments) {
        PackBlendPlan.Mode[] blends = MrtPipelineContext.hostPassBlends();
        if (blends == null || blends.length != attachments.capacity()) return attachments;
        int[] formats = new int[attachments.capacity()];
        Arrays.fill(formats, this.chimera$hostFormat);
        WindowBlendAttachments.fill(attachments, blends, SpirvOutputWrites.ALL, formats);
        return attachments;
    }

    /** Names the window's attachment formats in place of the render pass. */
    @ModifyArg(method = "createGraphicsPipeline",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VK10;vkCreateGraphicsPipelines("
                    + "Lorg/lwjgl/vulkan/VkDevice;JLorg/lwjgl/vulkan/VkGraphicsPipelineCreateInfo$Buffer;"
                    + "Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"),
            index = 2)
    private VkGraphicsPipelineCreateInfo.Buffer chimera$renderingFormats(VkGraphicsPipelineCreateInfo.Buffer infos) {
        if (this.chimera$window == null) return infos;
        MemoryStack stack = stackGet();
        int depth = this.chimera$window.depthFormat();
        VkPipelineRenderingCreateInfoKHR rendering = VkPipelineRenderingCreateInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO_KHR)
                .pColorAttachmentFormats(stack.ints(this.chimera$window.colorFormats()))
                .depthAttachmentFormat(depth == 0 ? VK_FORMAT_UNDEFINED : depth)
                .stencilAttachmentFormat(VulkanImage.hasStencilComponent(depth) ? depth : VK_FORMAT_UNDEFINED);
        infos.get(0).pNext(rendering.address());
        return infos;
    }
}
