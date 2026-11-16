package net.chimera.mixin;

import net.chimera.render.ChimeraRenderer;
import net.vulkanmod.vulkan.Renderer;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chimera's hooks into VulkanMod's Renderer lifecycle. It takes over the main pass once the Renderer is fully
 * built, with its swapchain, default main pass and sync objects; applies pending pack work before a frame
 * records; and cleans up its own pass at final teardown when the host pass is the installed one.
 */
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

    /**
     * Runs after Renderer.beginFrame has waited on the frame slot's fence and before command recording
     * starts, the point where pack-owned Vulkan resources can be replaced, as on a dimension change.
     */
    @Inject(method = "beginRenderPass(Lorg/lwjgl/system/MemoryStack;)V", at = @At("HEAD"))
    private void chimera$beforeMainCommandBuffer(MemoryStack stack, CallbackInfo ci) {
        ChimeraRenderer.beforeMainCommandBuffer();
    }
}
