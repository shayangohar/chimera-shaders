// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders global block entities such as beacons after the visible sections' ones, as vanilla and Sodium do;
 * Reforged renders the sections' at the stage's closing checkPoseStack, after vanilla's global loop. Write
 * order sets batch draw order (IrisBatchOrder), so otherwise a pack with its own block-entity target shows a
 * chest over a beacon beam.
 */
@Mixin(LevelRenderer.class)
public abstract class ChimeraBlockEntityOrderMixin {
    private static final String RENDER_BLOCK_ENTITY = "Lnet/minecraft/client/renderer/blockentity/"
            + "BlockEntityRenderDispatcher;render(Lnet/minecraft/world/level/block/entity/BlockEntity;F"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V";
    private static final String CHECK_POSE_STACK =
            "Lnet/minecraft/client/renderer/LevelRenderer;checkPoseStack(Lcom/mojang/blaze3d/vertex/PoseStack;)V";

    /** This frame's global block-entity renders, held until the sections' block entities have rendered. */
    @Unique private final List<Runnable> chimera$globalRenders = new ArrayList<>();

    /** Vanilla's global loop, the second block-entity render in renderLevel; holds the render with its pose. */
    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = RENDER_BLOCK_ENTITY, ordinal = 1),
            require = 1)
    private void chimera$holdGlobalRender(BlockEntityRenderDispatcher dispatcher, BlockEntity blockEntity,
                                          float partialTick, PoseStack poses, MultiBufferSource buffers,
                                          Operation<Void> original) {
        PoseStack held = new PoseStack();
        held.last().pose().set(poses.last().pose());
        held.last().normal().set(poses.last().normal());
        chimera$globalRenders.add(() -> original.call(dispatcher, blockEntity, partialTick, held, buffers));
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = CHECK_POSE_STACK, ordinal = 1,
            shift = At.Shift.AFTER), require = 1)
    private void chimera$renderGlobalBlockEntities(CallbackInfo callback) {
        List<Runnable> held = List.copyOf(chimera$globalRenders);
        chimera$globalRenders.clear();
        held.forEach(Runnable::run);
    }
}
