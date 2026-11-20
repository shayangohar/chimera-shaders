// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import net.chimera.render.ChimeraRenderer;
import net.vulkanmod.render.vertex.VertexUtil;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Normalises a non-unit normal while a pack renders, as vanilla's putBulkData does after a non-uniform scale
 * such as the hotbar bounce. Reforged's packNormal wraps a component past 1 negative, swapping lit and shaded
 * faces. Mixin 0.8.7 cannot inject into the interface method, and reaches Reforged's merged BufferBuilderM
 * body only from a later priority.
 */
@Mixin(value = BufferBuilder.class, priority = 1100)
public abstract class ChimeraBulkNormalMixin {
    @Redirect(method = "addVertex(FFFIFFIIFFF)V", at = @At(value = "INVOKE",
            target = "Lnet/vulkanmod/render/vertex/VertexUtil;packNormal(FFF)I", remap = false))
    private int chimera$unitNormal(float x, float y, float z) {
        float lengthSquared = x * x + y * y + z * z;
        if (ChimeraRenderer.packProjectionActive() && lengthSquared > 0.0f
                && Math.abs(lengthSquared - 1.0f) > 1.0e-4f) {
            Vector3f normal = new Vector3f(x, y, z).normalize();
            return VertexUtil.packNormal(normal.x, normal.y, normal.z);
        }
        return VertexUtil.packNormal(x, y, z);
    }
}
