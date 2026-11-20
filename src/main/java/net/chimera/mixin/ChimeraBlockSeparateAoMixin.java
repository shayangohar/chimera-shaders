// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.chimera.render.shader.ChimeraTerrainPipelines;
import net.chimera.render.vertex.ChimeraExtTerrainBuilder;
import net.chimera.render.vertex.SeparateAoColor;
import net.vulkanmod.render.chunk.build.BlockRenderer;
import net.vulkanmod.render.vertex.TerrainBufferBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Iris's separateAo in VulkanMod's block quads: the tint stays in RGB and the AO and shade brightness moves to
 * alpha, as Iris writes Sodium's terrain. Blocks outside chunk meshes go through vanilla's renderer instead.
 */
@Mixin(value = BlockRenderer.class, remap = false)
public abstract class ChimeraBlockSeparateAoMixin {
    @WrapOperation(method = "putQuadData(Lnet/vulkanmod/render/vertex/TerrainBufferBuilder;Lorg/joml/Vector3f;"
            + "Lnet/vulkanmod/render/model/quad/QuadView;"
            + "Lnet/vulkanmod/render/chunk/build/light/data/QuadLightData;FFF)V",
            at = @At(value = "INVOKE", target = "Lnet/vulkanmod/vulkan/util/ColorUtil$RGBA;pack(FFFF)I"))
    private static int chimera$separateAo(float r, float g, float b, float a, Operation<Integer> original,
                                          @Local(argsOnly = true) TerrainBufferBuilder builder,
                                          @Local(argsOnly = true, ordinal = 0) float red,
                                          @Local(argsOnly = true, ordinal = 1) float green,
                                          @Local(argsOnly = true, ordinal = 2) float blue,
                                          @Local(name = "quadR") float quadR,
                                          @Local(name = "quadG") float quadG,
                                          @Local(name = "quadB") float quadB,
                                          @Local(name = "brightness") float brightness) {
        return builder instanceof ChimeraExtTerrainBuilder && ChimeraTerrainPipelines.separateAoActive()
                ? SeparateAoColor.rgba(quadR * red, quadG * green, quadB * blue, brightness)
                : original.call(r, g, b, a);
    }
}
