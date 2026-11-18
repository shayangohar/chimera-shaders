// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.mixin;

import net.chimera.render.shader.GeometryStageModules;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs a pack's geometry stage, which VulkanMod's GraphicsShaderStages has no slot for
 * (GeometryStageModules). Its module is made after the pipeline's own modules, every variant gets it in its
 * stages, and it is released with the pipeline.
 */
@Mixin(value = GraphicsPipeline.class, remap = false)
public abstract class GraphicsPipelineGeometryStageMixin {
    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/vulkanmod/vulkan/shader/GraphicsPipeline;"
            + "createShaderModules(Lnet/vulkanmod/vulkan/shader/Pipeline$Builder;)"
            + "Lnet/vulkanmod/vulkan/shader/GraphicsShaderStages;", shift = At.Shift.AFTER), require = 1)
    private void chimera$attachGeometryStage(Pipeline.Builder builder, CallbackInfo callback) {
        GeometryStageModules.attach((GraphicsPipeline) (Object) this);
    }

    @ModifyArg(method = "createGraphicsPipeline", at = @At(value = "INVOKE", target =
            "Lorg/lwjgl/vulkan/VkGraphicsPipelineCreateInfo$Buffer;pStages("
                    + "Lorg/lwjgl/vulkan/VkPipelineShaderStageCreateInfo$Buffer;)"
                    + "Lorg/lwjgl/vulkan/VkGraphicsPipelineCreateInfo$Buffer;"), require = 1)
    private VkPipelineShaderStageCreateInfo.Buffer chimera$geometryStage(
            VkPipelineShaderStageCreateInfo.Buffer stages) {
        return GeometryStageModules.withGeometry((GraphicsPipeline) (Object) this, stages);
    }

    @Inject(method = "cleanUp", at = @At("HEAD"))
    private void chimera$releaseGeometryStage(CallbackInfo callback) {
        GeometryStageModules.release((GraphicsPipeline) (Object) this);
    }
}
