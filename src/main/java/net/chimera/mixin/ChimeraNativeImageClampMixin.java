package net.chimera.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.chimera.render.EdgeClampedImages;
import net.vulkanmod.gl.GlTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NativeImage._upload clamps the bound texture to its edge when the caller asks, as for a texture's "clamp"
 * metadata such as misc/shadow.png, the entity ground shadow. Reforged's overwrite (MNativeImage) drops the
 * request, so a pack program sampling such a texture without a clamp of its own, which Iris allows, would
 * repeat it and draw the ground shadow as a disc on every block under a mob. This records the request
 * (EdgeClampedImages) for pack draws. VulkanMod's own draws are left alone; vanilla's entity-shadow shader
 * clamps its coordinate itself.
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
