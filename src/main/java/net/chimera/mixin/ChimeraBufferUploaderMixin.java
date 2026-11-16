package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.MeshData;
import net.chimera.render.shader.PackHostDraws;
import net.chimera.render.shader.PatchDraws;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Runs immediate host draws under the pack program that serves them (PackHostDraws), as patches of 3 when
 * that program is tessellated (PatchDraws).
 */
@Mixin(BufferUploader.class)
public abstract class ChimeraBufferUploaderMixin {
    @WrapMethod(method = "drawWithShader")
    private static void chimera$packProgram(MeshData mesh, Operation<Void> original) {
        PackHostDraws.draw(RenderSystem.getShader(), mesh.drawState().format(),
                () -> PatchDraws.draw(mesh, () -> original.call(mesh)), mesh::close);
    }
}
