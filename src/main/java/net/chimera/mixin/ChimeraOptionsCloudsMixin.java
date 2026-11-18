// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.mixin;

import net.chimera.render.shader.ChimeraSkyBridge;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * As Iris's MixinOptions_CloudsOverride: the pack's {@code clouds} directive, off, fast or fancy, replaces
 * the player's cloud setting while the pack is loaded. Bliss sets {@code clouds=off} because it draws its
 * own clouds.
 */
@Mixin(Options.class)
public abstract class ChimeraOptionsCloudsMixin {
    @Shadow
    @Final
    private OptionInstance<Integer> renderDistance;

    @Inject(method = "getCloudsType", at = @At("HEAD"), cancellable = true)
    private void chimera$packCloudsType(CallbackInfoReturnable<CloudStatus> callback) {
        // vanilla draws no clouds below render distance 4; Iris keeps that check
        if (renderDistance.get() < 4) return;
        CloudStatus pack = ChimeraSkyBridge.packCloudStatus();
        if (pack != null) callback.setReturnValue(pack);
    }
}
