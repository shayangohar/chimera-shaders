// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.mixin;

import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Lifts the vanilla entity ground-shadow decal off its receiver, with or without a pack, since its render type
 * has no depth bias and would depth-fight. Iris does not lift it.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class ChimeraShadowDecalMixin {
    /** Enough to beat depth quantisation yet far below slab and stair steps; exact in float32. */
    private static final float GROUND_SHADOW_LIFT = 1.0F / 64.0F;

    // the quad has four shadowVertex calls; require = 4 fails if their count or signature changes
    @ModifyArg(
            method = "renderBlockShadow",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;"
                            + "shadowVertex(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;"
                            + "Lcom/mojang/blaze3d/vertex/VertexConsumer;IFFFFF)V"),
            index = 4,
            require = 4)
    private static float chimera$liftShadowDecal(float y) {
        return y + GROUND_SHADOW_LIFT;
    }
}
