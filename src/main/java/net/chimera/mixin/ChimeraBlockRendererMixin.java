// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.mixin;

import net.chimera.render.vertex.ChimeraExtTerrainBuilder;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.vulkanmod.render.chunk.build.BlockRenderer;
import net.vulkanmod.render.vertex.TerrainBufferBuilder;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Gives the terrain builder the block's position, as Iris's block context does, for at_midBlock. */
@Mixin(value = BlockRenderer.class, remap = false)
public abstract class ChimeraBlockRendererMixin {
    @Inject(method = "renderBlock", at = @At("HEAD"))
    private void chimera$beginBlock(BlockState blockState, BlockPos blockPos, Vector3f pos,
                                    TerrainBufferBuilder builder, BakedModel model, ModelData modelData,
                                    RenderType renderType, CallbackInfo ci) {
        // VulkanMod writes terrain vertices relative to the section, like Iris's local positions
        if (builder instanceof ChimeraExtTerrainBuilder extended) {
            extended.beginBlock(blockPos.getX() & 15, blockPos.getY() & 15, blockPos.getZ() & 15);
        }
    }
}
