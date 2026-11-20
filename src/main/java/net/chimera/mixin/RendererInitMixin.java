// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.mixin;

import net.chimera.render.ChimeraRenderer;
import net.vulkanmod.vulkan.Renderer;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Chimera's hooks into VulkanMod's Renderer lifecycle. */
@Mixin(value = Renderer.class, remap = false)
public abstract class RendererInitMixin {

    @Inject(method = "initRenderer", at = @At("TAIL"))
    private static void chimera$onRendererInit(CallbackInfo ci) {
        ChimeraRenderer.onHostRendererReady();
    }

    /**
     * cleanUpResources cleans only the installed main pass, so an idle Chimera pass is cleaned here first,
     * while the device and the host pass are still alive.
     */
    @Inject(method = "cleanUpResources", at = @At("HEAD"))
    private void chimera$beforeCleanUpResources(CallbackInfo ci) {
        ChimeraRenderer.beforeHostCleanup();
    }

    /** After beginFrame waits on the frame's fence and before recording: where pack resources can be replaced. */
    @Inject(method = "beginRenderPass(Lorg/lwjgl/system/MemoryStack;)V", at = @At("HEAD"))
    private void chimera$beforeMainCommandBuffer(MemoryStack stack, CallbackInfo ci) {
        ChimeraRenderer.beforeMainCommandBuffer();
    }
}
