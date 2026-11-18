// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.chimera.render.ChimeraMainPass;
import net.chimera.render.ChimeraRenderer;
import net.chimera.render.PreSkyPasses;
import net.chimera.render.TerrainLayers;
import net.chimera.render.shader.FamilyBufferSource;
import net.chimera.render.shader.PackUniformProvider;
import net.chimera.render.shader.TerrainVertexUniforms;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.vertex.TerrainRenderType;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chimera's frame around VulkanMod's section layers.
 *
 * Every layer call runs in its Iris rendering phase, and only the layers VulkanMod draws ({@link TerrainLayers})
 * open Chimera's windows. Before translucent terrain, depth is captured and the deferred passes run ahead of the
 * pack window, whose guarded attachments must not intercept them; the entity batches a pack's
 * separateEntityDraws held back draw right after (FamilyBufferSource). A pack window spans each layer's
 * draws.
 *
 * Every layer opens on the main target (ChimeraMainPass.rebindMainTarget), which opens the shadow pass or a
 * pending pack geometry window, since Reforged's renderSectionLayer draws into whatever is bound.
 */
@Mixin(value = WorldRenderer.class, remap = false)
public abstract class WorldRendererMixin {

    @WrapMethod(method = "renderSectionLayer")
    private void chimera$terrainLayer(RenderType renderType, double camX, double camY, double camZ,
                                      Matrix4f view, Matrix4f projection, Operation<Void> original) {
        // the camera every area's ChunkOffset is relative to, and the atlas shrink, for Chimera's terrain shaders
        TerrainVertexUniforms.update(camX, camY, camZ);
        TerrainRenderType type = ChimeraRenderer.segmentsActive() ? TerrainRenderType.get(renderType) : null;
        if (type == null) {
            original.call(renderType, camX, camY, camZ, view, projection);
            return;
        }
        var previous = PackUniformProvider.setRenderingPhase(TerrainLayers.phase(type));
        try {
            ChimeraMainPass pass = ChimeraRenderer.getMainPass();
            if (pass == null || !TerrainLayers.drawn(type)) {
                // Reforged still binds a pipeline for a layer it merged away
                if (pass != null) pass.rebindMainTarget();
                original.call(renderType, camX, camY, camZ, view, projection);
                return;
            }
            if (type == TerrainRenderType.TRANSLUCENT) {
                // the deferred passes, then the entity batches separateEntityDraws held back for them
                pass.captureOpaqueDepthBeforeTranslucent();
                FamilyBufferSource.endHeldLevelBatches(pass);
            }
            pass.beginTerrainLayerWindow(type);
            try {
                pass.rebindMainTarget();
                // the shadow pass sets its own layers' alpha references (PackShadowPass)
                if (!ChimeraRenderer.shadowPassActive()) {
                    PackUniformProvider.updateDrawAlphaReference(TerrainLayers.irisAlphaReference(type));
                }
                original.call(renderType, camX, camY, camZ, view, projection);
            } finally {
                pass.endTerrainLayerWindow(type);
            }
        } finally {
            PackUniformProvider.setRenderingPhase(previous);
        }
    }

    /**
     * Skips the frame's section setup when the shadow pass already ran it before the sky (PreSkyPasses);
     * a second run would reset the indirect draws the shadow pass recorded.
     */
    @WrapMethod(method = "setupRenderer")
    private void chimera$setupOnce(Camera camera, Frustum frustum, boolean capturedFrustum, boolean spectator,
                                   Operation<Void> original) {
        if (PreSkyPasses.consumeSetupRanEarly()) return;
        original.call(camera, frustum, capturedFrustum, spectator);
    }

    /**
     * Keeps back faces culled on every layer under Chimera, as vanilla and upstream VulkanMod do.
     *
     * Reforged draws translucent terrain unculled. A water surface's reversed copy then passes the depth test
     * and overwrites it lit from below, and every far face of glass draws.
     */
    @WrapWithCondition(method = "renderSectionLayer", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;disableCull()V"), require = 1)
    private boolean chimera$keepBackFacesCulled() {
        return !ChimeraRenderer.segmentsActive();
    }

    @Inject(method = "renderSectionLayer",
            at = @At(value = "INVOKE",
                    target = "Lnet/vulkanmod/vulkan/texture/VTextureSelector;"
                            + "bindShaderTextures(Lnet/vulkanmod/vulkan/shader/Pipeline;)V",
                    shift = At.Shift.AFTER),
            require = 1)
    private void chimera$bindTerrainImages(RenderType renderType, double camX, double camY,
            double camZ, Matrix4f modelView, Matrix4f projection, CallbackInfo ci) {
        if (!ChimeraRenderer.segmentsActive()) return;
        TerrainRenderType type = TerrainRenderType.get(renderType);
        ChimeraMainPass pass = ChimeraRenderer.getMainPass();
        if (pass != null && type != null && TerrainLayers.drawn(type)) pass.bindTerrainImagesAfterSelector(type);
    }
}
