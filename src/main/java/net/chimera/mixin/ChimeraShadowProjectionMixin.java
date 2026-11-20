// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.mixin;

import net.chimera.render.ChimeraRenderer;
import net.vulkanmod.vulkan.VRenderSystem;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.FloatBuffer;

/**
 * Stores shadow projections unchanged, as upstream VulkanMod does. Reforged maps GL depth onto [0,1], but the
 * shadow programs map clip depth themselves, so mapped twice every shadow depth would land behind its caster.
 */
@Mixin(value = VRenderSystem.class, remap = false)
public abstract class ChimeraShadowProjectionMixin {
    @Shadow
    @Final
    private static FloatBuffer projectionFloatView;

    @Inject(method = "applyProjectionMatrix", at = @At("HEAD"), cancellable = true, require = 1)
    private static void chimera$keepShadowProjection(Matrix4f projection, CallbackInfo callback) {
        if (!ChimeraRenderer.shadowPassActive()) return;
        projection.get(projectionFloatView);
        callback.cancel();
    }
}
