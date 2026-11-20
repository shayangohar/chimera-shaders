// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.mixin;

import net.chimera.render.shader.ChimeraTerrainPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Applies the pack's ambientOcclusionLevel to every block's shade brightness, where Iris does. Priority 990 is
 * Iris's for this hook, so Better Foliage's redirect of the call still succeeds.
 */
@Mixin(value = BlockBehaviour.BlockStateBase.class, priority = 990)
public abstract class ChimeraShadeBrightnessMixin {
    @Inject(method = "getShadeBrightness", at = @At("RETURN"), cancellable = true)
    private void chimera$ambientOcclusionLevel(BlockGetter level, BlockPos position,
                                              CallbackInfoReturnable<Float> result) {
        result.setReturnValue(ChimeraTerrainPipelines.shadeBrightness(result.getReturnValueF()));
    }
}
