// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.mixin;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.ChimeraSkyBridge;
import net.chimera.render.vertex.ChimeraVertexFormats;
import net.minecraft.client.renderer.ShaderInstance;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * VulkanMod asks every draw's ShaderInstance for its pipeline; an open pack draw answers with its pack
 * pipeline, so only the program changes. A family mesh with no open draw is skipped, since no host program
 * reads its layout.
 */
@Mixin(ShaderInstance.class)
public abstract class ChimeraShaderPipelineMixin {
    @Inject(method = "getPipeline(Lcom/mojang/blaze3d/vertex/VertexFormat;)"
            + "Lnet/vulkanmod/vulkan/shader/GraphicsPipeline;",
            at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private void chimera$packPipeline(VertexFormat format, CallbackInfoReturnable<GraphicsPipeline> callback) {
        GraphicsPipeline pack = ChimeraEntityBridge.pipeline();
        if (pack == null) pack = ChimeraSkyBridge.pipeline();
        if (pack != null || ChimeraVertexFormats.isFamilyFormat(format)) callback.setReturnValue(pack);
    }
}
