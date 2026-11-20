// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.mixin;

import net.vulkanmod.vulkan.framebuffer.SwapChain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips SwapChain.cleanUp once recreate has destroyed the swapchain on a zero extent (minimised window); a
 * second cleanup would free the missing depth image, an access violation on exit.
 */
@Mixin(value = SwapChain.class, remap = false)
public abstract class SwapChainMixin {

    @Shadow
    private long swapChainId;

    @Inject(method = "cleanUp", at = @At("HEAD"), cancellable = true)
    private void chimera$skipReapedSwapChain(CallbackInfo ci) {
        if (this.swapChainId == 0L) {
            ci.cancel();
        }
    }
}
