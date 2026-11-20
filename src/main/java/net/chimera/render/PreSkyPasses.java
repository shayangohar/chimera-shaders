// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.vulkan.Renderer;

/**
 * What Iris draws just before the sky: the shadow pass, then the prepare passes, which read this frame's
 * shadow map. VulkanMod's section setup resets the indirect draws the shadow pass records, so a frame that
 * draws shadows here runs that setup first and skips the later call (WorldRendererMixin).
 */
public final class PreSkyPasses {
    // render thread only
    private static boolean setupRanEarly;

    private PreSkyPasses() {}

    /** Clears an early setup a frame left unconsumed. */
    public static void beginFrame() {
        setupRanEarly = false;
    }

    /**
     * Runs the shadow pass when the frame armed it, then the prepare window. The host shader is put back after,
     * as Iris does, or renderSky could draw its disc with a shadow caster's shader.
     */
    public static void render(ChimeraMainPass pass, Camera camera, Frustum frustum, boolean capturedFrustum,
                              boolean spectator) {
        ShaderInstance hostShader = RenderSystem.getShader();
        try {
            if (pass.consumeShadowPending()) {
                WorldRenderer.getInstance().setupRenderer(camera, frustum, capturedFrustum, spectator);
                setupRanEarly = true;
                Vec3 position = camera.getPosition();
                pass.renderShadowSegment(position.x, position.y, position.z);
            }
            if (pass.session.packPostChainActive && pass.postChain.hasPrepareWork()) {
                pass.postChain.runPrepareWindow(Renderer.getCommandBuffer());
            }
        } finally {
            RenderSystem.setShader(() -> hostShader);
        }
    }

    /** Whether this frame's section setup already ran; true once, for the call it replaces. */
    public static boolean consumeSetupRanEarly() {
        boolean ran = setupRanEarly;
        setupRanEarly = false;
        return ran;
    }
}
