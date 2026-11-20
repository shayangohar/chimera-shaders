// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.mixin;

import net.chimera.render.shader.ChimeraTerrainPipelines;
import net.chimera.render.vertex.ChimeraExtTerrainBuilder;
import net.chimera.render.vertex.SeparateAoColor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.vulkanmod.render.chunk.build.LiquidRenderer;
import net.vulkanmod.render.chunk.build.thread.BuilderResources;
import net.vulkanmod.render.vertex.TerrainBufferBuilder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Writes the fluid's block attributes before the host fluid renderer emits vertices, and applies Iris's
 * separateAo to its vertex colours.
 */
@Mixin(value = LiquidRenderer.class, remap = false)
public abstract class ChimeraLiquidRendererMixin {
    @Shadow
    BuilderResources resources;

    @Shadow
    @Final
    private int[] quadColors;

    @Inject(method = "renderLiquid", at = @At("HEAD"))
    private void chimera$setFluidMaterial(BlockState blockState, FluidState fluidState, BlockPos blockPos,
                                          TerrainBufferBuilder builder, CallbackInfo ci) {
        if (builder instanceof ChimeraExtTerrainBuilder extended) {
            // fluid vertices are section-local, as VulkanMod's fluid renderer places them
            extended.setFluidBlockAttributes(blockState, fluidState,
                    blockPos.getX() & 15, blockPos.getY() & 15, blockPos.getZ() & 15);
        }
    }

    /**
     * Iris's separateAo: the tint stays in RGB and the brightness moves to alpha; VulkanMod's own updateColor
     * multiplies both into RGB.
     */
    @Inject(method = "updateColor", at = @At("TAIL"))
    private void chimera$separateAo(float red, float green, float blue, float brightness, CallbackInfo ci) {
        if (!ChimeraTerrainPipelines.separateAoActive()) return;
        float[] lightBrightness = resources.quadLightData.br;
        for (int index = 0; index < 4; index++) {
            quadColors[index] = SeparateAoColor.rgba(red, green, blue, lightBrightness[index] * brightness);
        }
    }
}
