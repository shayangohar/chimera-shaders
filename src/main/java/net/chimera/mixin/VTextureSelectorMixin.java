// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.mixin;

import net.chimera.shaderpack.SelectorNamespace;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Backs the extended selector slots ({@link SelectorNamespace#FIRST_EXTENDED} through
 * {@link SelectorNamespace#LAST_RESERVED}) with Chimera's own image array and resolves their {@code SamplerN} names.
 */
@Mixin(value = VTextureSelector.class, remap = false)
public abstract class VTextureSelectorMixin {
    @Unique
    private static final VulkanImage[] chimera$extendedTextures =
            new VulkanImage[SelectorNamespace.EXTENDED_CAPACITY];

    @Inject(method = "getTextureIdx", at = @At("HEAD"), cancellable = true)
    private static void chimera$extendedSampler(String name, CallbackInfoReturnable<Integer> cir) {
        if (name != null && name.startsWith("Sampler")) {
            try {
                int index = Integer.parseInt(name.substring("Sampler".length()));
                if (SelectorNamespace.extendedIndex(index) >= 0) {
                    cir.setReturnValue(index);
                }
            } catch (NumberFormatException ignored) {
                // not a numbered sampler; VulkanMod resolves it
            }
        }
    }

    @Inject(method = "bindTexture(ILnet/vulkanmod/vulkan/texture/VulkanImage;)V",
            at = @At("HEAD"), cancellable = true)
    private static void chimera$bindExtendedTexture(int index, VulkanImage texture,
            CallbackInfo ci) {
        int extended = SelectorNamespace.extendedIndex(index);
        if (extended >= 0) {
            chimera$extendedTextures[extended] = texture;
            ci.cancel();
        }
    }

    @Inject(method = "getImage", at = @At("HEAD"), cancellable = true)
    private static void chimera$getExtendedImage(int index, CallbackInfoReturnable<VulkanImage> cir) {
        int extended = SelectorNamespace.extendedIndex(index);
        if (extended >= 0) {
            cir.setReturnValue(chimera$extendedTextures[extended]);
        }
    }

    @Inject(method = "getBoundTexture(I)Lnet/vulkanmod/vulkan/texture/VulkanImage;",
            at = @At("HEAD"), cancellable = true)
    private static void chimera$getExtendedBoundTexture(int index,
            CallbackInfoReturnable<VulkanImage> cir) {
        int extended = SelectorNamespace.extendedIndex(index);
        if (extended >= 0) {
            cir.setReturnValue(chimera$extendedTextures[extended]);
        }
    }
}
