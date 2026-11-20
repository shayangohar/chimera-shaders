// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.vulkanmod.interfaces.ExtendedVertexBuilder;
import net.vulkanmod.render.vertex.VertexUtil;
import net.vulkanmod.vulkan.util.ColorUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * While a screen is open, Reforged's ModelPart hook lets vanilla's Cube.compile run, and its ARGB colour
 * lands in Reforged's RGBA buffer with red and blue swapped. A cube writing to a fast-vertex consumer takes
 * VulkanMod's path instead.
 */
@Mixin(ModelPart.Cube.class)
public abstract class ChimeraModelCubeColorMixin {
    @WrapOperation(method = "compile", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;addVertex(FFFIFFIIFFF)V"), require = 1)
    private void chimera$packedColor(VertexConsumer buffer, float x, float y, float z, int color, float u, float v,
                                     int overlay, int light, float normalX, float normalY, float normalZ,
                                     Operation<Void> original) {
        ExtendedVertexBuilder fast = ExtendedVertexBuilder.of(buffer);
        if (fast == null || !fast.canUseFastVertex()) {
            original.call(buffer, x, y, z, color, u, v, overlay, light, normalX, normalY, normalZ);
            return;
        }
        fast.vertex(x, y, z, ColorUtil.RGBA.fromArgb32(color), u, v, overlay, light,
                VertexUtil.packNormal(normalX, normalY, normalZ));
    }
}
