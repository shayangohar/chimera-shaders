// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.chimera.render.ChimeraRenderer;
import net.chimera.render.EndPortalCube;
import net.chimera.render.shader.PackUniformProvider;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.TheEndPortalRenderer;
import net.minecraft.world.level.block.entity.TheEndPortalBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While a pack renders, draws end portals and gateways as an entitySolid {@link EndPortalCube} the pack's
 * programs can shade; the gateway's beam stays vanilla.
 */
@Mixin(TheEndPortalRenderer.class)
public abstract class ChimeraEndPortalRendererMixin {
    @Shadow
    protected abstract float getOffsetUp();

    @Shadow
    protected abstract float getOffsetDown();

    @Inject(method = "render(Lnet/minecraft/world/level/block/entity/TheEndPortalBlockEntity;F"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At("HEAD"), cancellable = true, require = 1)
    private void chimera$drawPackCube(TheEndPortalBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                                      MultiBufferSource buffers, int packedLight, int packedOverlay,
                                      CallbackInfo callback) {
        if (!ChimeraRenderer.packProjectionActive()) return;
        callback.cancel();
        // fetched even when no face is drawn: like vanilla's own fetch, it may end the previous batch
        VertexConsumer consumer = buffers.getBuffer(RenderType.entitySolid(TheEndPortalRenderer.END_PORTAL_LOCATION));
        float scroll = EndPortalCube.scroll(PackUniformProvider.currentFrameTimeCounter());
        EndPortalCube.draw(consumer, poseStack.last(), blockEntity::shouldRenderFace, getOffsetUp(), getOffsetDown(),
                scroll, packedLight, packedOverlay);
    }
}
