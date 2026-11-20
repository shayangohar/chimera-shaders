// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.mixin;

import net.chimera.render.ChimeraRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Restores vanilla's finite far plane while a pack renders, as Iris gives packs vanilla's projection.
 * VulkanMod's infinite far plane would make sky pixels at depth 1 reconstruct rays behind the camera.
 */
@Mixin(GameRenderer.class)
public abstract class ChimeraProjectionFarMixin {
    @Shadow private float renderDistance;

    /** Vanilla 1.21.1's getDepthFar: four times the render distance in blocks. */
    @ModifyArg(method = "getProjectionMatrix", at = @At(value = "INVOKE",
            target = "Lorg/joml/Matrix4f;perspective(FFFF)Lorg/joml/Matrix4f;", remap = false),
            index = 3, require = 1)
    private float chimera$vanillaDepthFar(float hostFar) {
        return ChimeraRenderer.packProjectionActive() ? this.renderDistance * 4.0F : hostFar;
    }
}
