// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.RenderedItemIds;
import net.chimera.render.vertex.FamilyVertexWriter;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fills the elements Chimera's family formats add, through a {@link FamilyVertexWriter} on each builder of a
 * family format: defaults as each vertex starts, and each polygon once its last corner is written, which is
 * when the next vertex starts or the batch is built. The builder is also told not to wait for those elements,
 * since no VertexConsumer call fills them. Builders of any other format keep a null writer and are left alone.
 */
@Mixin(BufferBuilder.class)
public abstract class ChimeraBufferBuilderMixin {
    @Shadow @Final private ByteBufferBuilder buffer;
    @Shadow private int elementsToFill;

    @Unique
    private FamilyVertexWriter chimera$family;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void chimera$recogniseFamily(ByteBufferBuilder arena, VertexFormat.Mode mode, VertexFormat format,
                                         CallbackInfo callback) {
        chimera$family = FamilyVertexWriter.forBuilder(format, mode);
    }

    /** The host checks the last vertex's pending elements next, so a polygon it closes is complete. */
    @Inject(method = "endLastVertex", at = @At("HEAD"))
    private void chimera$endVertex(CallbackInfo callback) {
        FamilyVertexWriter family = chimera$family;
        if (family == null) return;
        family.fillCompletePolygon(chimera$base(), ChimeraEntityBridge.renderingLevel());
        elementsToFill &= ~family.ownElements();
    }

    /** The new vertex's bytes are reserved but stale, and the arena may have moved to reserve them. */
    @ModifyReturnValue(method = "beginVertex", at = @At("RETURN"))
    private long chimera$beginVertex(long vertex) {
        FamilyVertexWriter family = chimera$family;
        if (family != null) {
            long base = chimera$base();
            family.fillCompletePolygon(base, ChimeraEntityBridge.renderingLevel());
            family.startVertex(base, vertex, ChimeraEntityBridge.currentEntityId(),
                    ChimeraEntityBridge.currentBlockEntityId(), RenderedItemIds.current());
        }
        return vertex;
    }

    @Inject(method = "build", at = @At("HEAD"))
    private void chimera$build(CallbackInfoReturnable<MeshData> callback) {
        FamilyVertexWriter family = chimera$family;
        if (family != null) family.fillCompletePolygon(chimera$base(), ChimeraEntityBridge.renderingLevel());
    }

    @Unique
    private long chimera$base() {
        return ((ChimeraByteBufferBuilderAccessor) buffer).chimera$pointer();
    }
}
