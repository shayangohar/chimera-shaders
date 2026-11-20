// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.chimera.render.shader.ChimeraTerrainPipelines;
import net.chimera.render.vertex.ChimeraExtTerrainBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.vulkanmod.render.chunk.build.task.BuildTask;
import net.vulkanmod.render.chunk.build.task.CompileResult;
import net.vulkanmod.render.chunk.build.thread.BuilderResources;
import net.vulkanmod.render.chunk.build.thread.ThreadBuilderPack;
import net.vulkanmod.render.vertex.TerrainRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Iris's voxelizeLightBlocks: a light block has no model, so it gets a zero-area quad in the cutout-mipped
 * mesh, from which a pack voxelising terrain in its shadow pass reads the id and emission.
 */
@Mixin(value = BuildTask.class, remap = false)
public abstract class ChimeraBuildTaskMixin {
    @Inject(method = "compile", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;"
                    + "isSolidRender(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Z"),
            require = 1)
    private void chimera$lightBlockQuad(float camX, float camY, float camZ, BuilderResources builderResources,
                                        CallbackInfoReturnable<CompileResult> cir, @Local BlockState blockState,
                                        @Local BlockPos.MutableBlockPos blockPos,
                                        @Local ThreadBuilderPack bufferBuilders) {
        if (!(blockState.getBlock() instanceof LightBlock) || !ChimeraTerrainPipelines.voxelizeLightBlocks()) return;
        // pack meshes only; the host builder has no block-data words
        if (bufferBuilders.builder(TerrainRenderType.CUTOUT_MIPPED) instanceof ChimeraExtTerrainBuilder extended) {
            extended.lightBlockQuad(blockState, blockPos.getX() & 15, blockPos.getY() & 15, blockPos.getZ() & 15);
        }
    }
}
