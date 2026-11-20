// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.chimera.render.SpriteTransparentTexels;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceMetadata;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Gives atlas sprites' transparent texels the colour Sodium gives them, before any mip level is built. */
@Mixin(SpriteContents.class)
public abstract class ChimeraSpriteTransparentTexelsMixin {
    @Shadow @Final private NativeImage originalImage;

    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void chimera$fillTransparentTexels(ResourceLocation name, FrameSize frameSize, NativeImage image,
                                               ResourceMetadata metadata, CallbackInfo callback) {
        SpriteTransparentTexels.fill(originalImage);
    }
}
