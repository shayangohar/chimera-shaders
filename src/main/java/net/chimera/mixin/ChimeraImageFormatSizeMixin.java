// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.mixin;

import net.chimera.shaderpack.PackConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Texel sizes for pack target formats, which VulkanMod's image builder would otherwise reject. */
@Mixin(targets = "net.vulkanmod.vulkan.texture.VulkanImage$Builder", remap = false)
public abstract class ChimeraImageFormatSizeMixin {
    @Inject(method = "formatSize(I)I", at = @At("HEAD"), cancellable = true, require = 1)
    private static void chimera$packFormatSize(
            int format,
            CallbackInfoReturnable<Integer> callback
    ) {
        int bytes = PackConfig.formatBytes(format);
        if (bytes != 0) {
            callback.setReturnValue(bytes);
        }
    }
}
