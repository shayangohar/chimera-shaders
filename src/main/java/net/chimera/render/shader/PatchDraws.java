// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.renderer.ShaderInstance;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.texture.VTextureSelector;

/**
 * An immediate mesh drawn under a tessellated pack pipeline as patches of 3, as Iris draws GL_PATCHES of 3.
 * VulkanMod's own immediate draw builds four-vertex patches and throws for anything but QUADS, so this repeats
 * BufferUploaderM.drawWithShader with a triangle draw.
 */
public final class PatchDraws {
    private PatchDraws() {}

    /** Falls back to {@code host} unless a tessellated pack pipeline is open. */
    public static void draw(MeshData mesh, Runnable host) {
        GraphicsPipeline pipeline = ChimeraEntityBridge.pipeline();
        if (pipeline == null || !pipeline.hasTessellation()) {
            host.run();
            return;
        }
        try {
            MeshData.DrawState state = mesh.drawState();
            ShaderInstance shader = RenderSystem.getShader();
            if (state.vertexCount() <= 0 || shader == null) return;
            // the host shader binds the draw's textures, which the pack pipeline's samplers read
            shader.apply();
            Renderer renderer = Renderer.getInstance();
            VRenderSystem.setPrimitiveTopology(state.mode());
            renderer.bindGraphicsPipeline(pipeline);
            VTextureSelector.bindShaderTextures(pipeline);
            renderer.uploadAndBindUBOs(pipeline);
            Renderer.getDrawer().draw(mesh.vertexBuffer(), state, mesh.indexBuffer());
        } finally {
            mesh.close();
        }
    }
}
