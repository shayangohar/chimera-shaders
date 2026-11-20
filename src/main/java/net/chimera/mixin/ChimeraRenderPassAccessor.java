// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.mixin;

import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Points a render pass at a recreated framebuffer of the same formats, so pipelines, which cache variants by
 * RenderPass, keep every compiled variant.
 */
@Mixin(value = RenderPass.class, remap = false)
public interface ChimeraRenderPassAccessor {
    @Accessor("framebuffer")
    void chimera$setFramebuffer(Framebuffer framebuffer);
}
