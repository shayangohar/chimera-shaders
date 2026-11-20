// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.chimera.render.shader.ChimeraTerrainPipelines;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.vulkanmod.render.chunk.build.task.BuildTask;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Slice;

import java.util.List;

/**
 * In pack terrain, keeps a block entity that renders off-screen, such as a beacon, out of its section's list,
 * as NeoForge and Sodium do. VulkanMod lists it in both (MC-112730), so it would draw twice and a beacon's
 * glow would come out twice as opaque.
 */
@Mixin(value = BuildTask.class, remap = false)
public abstract class ChimeraGlobalBlockEntityMixin {
    /** The first add after the section list is read, so the global list's add never matches, in either order. */
    @WrapOperation(method = "handleBlockEntity",
            at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z", ordinal = 0),
            slice = @Slice(from = @At(value = "FIELD",
                    target = "Lnet/vulkanmod/render/chunk/build/task/CompileResult;blockEntities:Ljava/util/List;")))
    private boolean chimera$sectionBlockEntity(List<BlockEntity> sectionList, Object blockEntity,
                                               Operation<Boolean> original,
                                               @Local BlockEntityRenderer<BlockEntity> renderer) {
        if (ChimeraTerrainPipelines.vanillaSectionCompile()
                && renderer.shouldRenderOffScreen((BlockEntity) blockEntity)) return false;
        return original.call(sectionList, blockEntity);
    }
}
