// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.chimera.render.EdgeClampedImages;
import net.vulkanmod.gl.GlTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records the edge clamp NativeImage._upload asks for, such as misc/shadow.png's, for pack draws. Reforged's
 * overwrite drops it, so a pack sampling the ground shadow without its own clamp would tile it under mobs.
 */
@Mixin(NativeImage.class)
public abstract class ChimeraNativeImageClampMixin {
    @Inject(method = "_upload", at = @At("HEAD"), require = 1)
    private void chimera$clampToEdge(int level, int xOffset, int yOffset, int skipPixels, int skipRows, int width,
                                     int height, boolean blur, boolean clamp, boolean mipmap, boolean autoClose,
                                     CallbackInfo callback) {
        GlTexture bound = GlTexture.getBoundTexture();
        if (clamp && bound != null && bound.getVulkanImage() != null) {
            EdgeClampedImages.add(bound.getVulkanImage());
        }
    }
}
