package net.chimera.mixin;

import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Lifts the vanilla entity ground-shadow decal off its receiver, with or without a pack.
 *
 * EntityRenderDispatcher.renderBlockShadow builds the shadow.png quad exactly at the receiving voxel shape's
 * minimum Y. The entity shadow render type is translucent with no depth write and no depth bias, so the
 * decal would depth-fight the receiver as the camera angle changes. Only the decal's Y moves; X, Z, UVs,
 * alpha and depth state are untouched. Iris does not lift the decal. While a pack's shadow map replaces
 * entity shadows the decal is not drawn at all (ChimeraEntityRenderDispatcherMixin), as in Iris.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class ChimeraShadowDecalMixin {
    /**
     * 1/64 block: enough to beat depth-buffer quantisation at gameplay distances, small enough to stay
     * invisible and far below slab and stair geometry, so the decal cannot bleed through neighbouring
     * receivers. Exact in float32, so integer receiver heights stay exact after the lift.
     */
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
