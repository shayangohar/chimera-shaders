// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.mixin;

import net.chimera.render.ChimeraRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No void plane with the camera in a fluid while a pack renders, as Iris's MixinClientLevelData_DisableVoidPlane
 * keeps the fog illusion. Iris does this without a pack too.
 */
@Mixin(ClientLevel.ClientLevelData.class)
public abstract class ChimeraVoidPlaneMixin {
    @Inject(method = "getHorizonHeight", at = @At("HEAD"), cancellable = true)
    private void chimera$noVoidPlaneInFluid(LevelHeightAccessor level, CallbackInfoReturnable<Double> callback) {
        if (ChimeraRenderer.packProjectionActive()
                && Minecraft.getInstance().gameRenderer.getMainCamera().getFluidInCamera() != FogType.NONE) {
            callback.setReturnValue(Double.NEGATIVE_INFINITY);
        }
    }
}
