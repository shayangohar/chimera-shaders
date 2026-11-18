// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.mixin;

import net.chimera.render.ChimeraRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Restores vanilla's finite far plane for the world projection while a pack renders the world. VulkanMod
 * makes GameRenderer.getDepthFar infinite, which would hand packs an infinite gbufferProjection; every sky
 * pixel at depth 1 would then reconstruct a ray behind the camera, removing pack clouds and skewing sky and
 * fog. Iris gives packs vanilla's projection. The depth buffer is written with the same matrix, so raster
 * and published uniforms agree.
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
