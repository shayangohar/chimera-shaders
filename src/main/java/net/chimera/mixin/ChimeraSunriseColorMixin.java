// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.mixin;

import net.chimera.render.ChimeraRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No sunrise colour while a pack renders with the camera in a fluid or under blindness or darkness, as Iris's
 * MixinDimensionSpecialEffects drops it in high fog; the sky fan and the fog colour both lose it. Iris does
 * this without a pack too.
 */
@Mixin(DimensionSpecialEffects.class)
public abstract class ChimeraSunriseColorMixin {
    @Inject(method = "getSunriseColor", at = @At("HEAD"), cancellable = true)
    private void chimera$noSunriseInHighFog(float timeOfDay, float partialTick,
                                            CallbackInfoReturnable<float[]> callback) {
        if (!ChimeraRenderer.packProjectionActive()) return;
        Minecraft minecraft = Minecraft.getInstance();
        Camera camera = minecraft.gameRenderer.getMainCamera();
        if (camera.getFluidInCamera() != FogType.NONE
                || ((ChimeraLevelRendererAccessor) minecraft.levelRenderer).chimera$doesMobEffectBlockSky(camera)) {
            callback.setReturnValue(null);
        }
    }
}
