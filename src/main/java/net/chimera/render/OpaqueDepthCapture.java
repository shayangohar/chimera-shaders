// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import net.chimera.render.shader.PackGeometryContext;
import net.chimera.shaderpack.PackFrameSchedulePlan;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Snapshots the opaque depth before translucent terrain (Iris's depthtex1) and makes the world depth readable.
 * Vulkan cannot sample the depth attachment being drawn into, so a world program that reads Iris's live
 * depthtex0 gets a capture, retaken after each group of world draws.
 */
final class OpaqueDepthCapture {
    static final Logger LOGGER = LoggerFactory.getLogger("chimera");
    private final ChimeraMainPass pass;

    /** The HDR depth is in a sampled layout; cleared when the world target reopens as an attachment. */
    boolean hdrDepthReadable;

    /** The world has drawn since depthtex0 last captured it this frame. */
    boolean drawnDepthStale = true;

    OpaqueDepthCapture(ChimeraMainPass pass) {
        this.pass = pass;
    }

    /** Marks a group of world draws done: the next program that samples depthtex0 captures it again. */
    void markWorldDrawn() {
        this.drawnDepthStale = true;
    }

    /** Whether {@code pipeline} samples depthtex0 while the world still draws and the capture is stale. */
    boolean needsDrawnDepth(GraphicsPipeline pipeline) {
        ChimeraMainPass.ProgramImages program = pass.images.programImages.get(pipeline);
        return this.drawnDepthStale && program != null && program.manifest().readsDepth("depthtex0")
                && drawnDepthCapturable();
    }

    /** Whether depthtex0 can be captured from the world depth the world is drawing into now. */
    boolean drawnDepthCapturable() {
        return pass.levelPhase && !this.hdrDepthReadable
                && !pass.shadowPass.shadowPassActive && !PackGeometryContext.active()
                && pass.targets.hdrFramebuffer != null
                && pass.currentFramebuffer == pass.targets.hdrFramebuffer
                && pass.packDepthTargets.isConfigured() && pass.packDepthTargets.plan().depthtex0();
    }

    /** Captures depthtex0 as the world drawn so far. No render pass may be open. */
    void captureDrawnDepth(VkCommandBuffer commandBuffer) {
        VulkanImage worldDepth = pass.targets.hdrFramebuffer.getDepthAttachment();
        if (pass.packDepthTargets.captureDrawnScene(commandBuffer, worldDepth)) {
            this.drawnDepthStale = false;
        } else {
            LOGGER.warn("[chimera] pack depth graph: depthtex0 capture of the drawn world unavailable");
        }
    }

    /** Before translucent terrain: captures depthtex1, and depthtex2 unless a hand did, then runs deferred passes. */
    public void captureOpaqueDepthBeforeTranslucent() {
        if (pass.shadowPass.shadowPassActive || !pass.levelPhase || pass.targets.hdrFramebuffer == null) {
            return;
        }
        boolean captureOpaque = pass.packDepthTargets.isConfigured()
                && pass.packDepthTargets.plan().depthtex1();
        boolean capturePreHand = capturesPreHandBeforeTranslucent();
        boolean runEarlyPost = pass.session.packPostChainActive
                && pass.packPostTargets.isConfigured()
                && pass.postChain.windowDue(PackFrameSchedulePlan.PostWindow.EARLY);
        if (!captureOpaque && !capturePreHand && !runEarlyPost) {
            return;
        }
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        Renderer.getInstance().endRenderPass(commandBuffer);
        VulkanImage worldDepth = pass.targets.hdrFramebuffer.getDepthAttachment();
        if (captureOpaque && !pass.packDepthTargets.captureOpaque(commandBuffer, worldDepth)) {
            LOGGER.warn("[chimera] pack depth graph: depthtex1 capture unavailable");
        }
        if (capturePreHand && !pass.packDepthTargets.capturePreHand(commandBuffer, worldDepth)) {
            LOGGER.warn("[chimera] pack depth graph: depthtex2 capture before translucents unavailable");
        }
        if (runEarlyPost) {
            runEarlyPostWindow(commandBuffer);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            Renderer.getInstance().beginRendering(pass.targets.hdrAuxRenderPass, pass.targets.hdrFramebuffer);
            Renderer.setViewport(0, 0, pass.targets.hdrFramebuffer.getWidth(),
                    pass.targets.hdrFramebuffer.getHeight(), stack);
            VK10.vkCmdSetScissor(commandBuffer, 0, pass.targets.hdrFramebuffer.scissor(stack));
        }
        this.hdrDepthReadable = false;
    }

    /** Samples the centre depth before the hand and translucent terrain (Iris: IrisRenderingPipeline.beginHand). */
    void sampleCenterDepthBeforeHand() {
        if (pass.packCenterDepth == null || pass.shadowPass.shadowPassActive || !pass.levelPhase
                || pass.targets.hdrFramebuffer == null) {
            return;
        }
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        Renderer.getInstance().endRenderPass(commandBuffer);
        pass.packCenterDepth.sample(commandBuffer, pass.targets.hdrFramebuffer.getDepthAttachment());
        pass.rebindMainTarget();
    }

    /** Whether to capture depthtex2 here, as Iris's beginHand does, unless a solid hand already captured it. */
    private boolean capturesPreHandBeforeTranslucent() {
        return pass.packDepthTargets.isConfigured() && pass.packDepthTargets.plan().depthtex2()
                && !pass.packDepthTargets.currentAvailable("depthtex2");
    }

    /** Runs the deferred (EARLY) window over the opaque world. */
    private void runEarlyPostWindow(VkCommandBuffer commandBuffer) {
        // deferred passes read depthtex0 as the opaque depth, as in Iris; finishLevelSegment recaptures it
        if (pass.packFrameSchedule.earlyReadsDepthtex0() && this.drawnDepthStale && drawnDepthCapturable()) {
            captureDrawnDepth(commandBuffer);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanImage hdrColor = pass.targets.hdrFramebuffer.getColorAttachment();
            hdrColor.transitionImageLayout(stack, commandBuffer,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            pass.postChain.runPackPostWindow(commandBuffer, hdrColor,
                    PackFrameSchedulePlan.PostWindow.EARLY);
            pass.packEarlyPostCompleted = true;
            // the rest of the world draws over the deferred result, as over Iris's colortex0
            if (pass.session.packPostChainActive) {
                pass.packPostTargets.enterWorld(commandBuffer, PackFrameSchedulePlan.PostWindow.EARLY, hdrColor);
            }
            hdrColor.transitionImageLayout(stack, commandBuffer,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        }
    }

    /** At level end, captures the missing depth snapshots and makes the HDR depth readable. */
    void captureWorldDepth(MemoryStack stack, VkCommandBuffer commandBuffer) {
        if (pass.packDepthTargets.isConfigured()
                && pass.packDepthTargets.plan().depthtex2()
                && !pass.packDepthTargets.currentAvailable("depthtex2")
                && !pass.packDepthTargets.capturePreHand(
                        commandBuffer, pass.targets.hdrFramebuffer.getDepthAttachment())) {
            LOGGER.warn("[chimera] pack depth graph: fallback=IDENTITY (pre-hand depth conversion failed)");
        }
        transitionHdrDepthForSampling(stack, commandBuffer);
        if (pass.packDepthTargets.isConfigured()
                && !pass.packDepthTargets.captureScene(
                commandBuffer, pass.targets.hdrFramebuffer.getDepthAttachment())) {
            LOGGER.warn("[chimera] pack depth graph: fallback=IDENTITY (depth conversion failed)");
        }
    }

    void transitionHdrDepthForSampling(MemoryStack stack, VkCommandBuffer commandBuffer) {
        if (!pass.packNeedsHdrDepth || this.hdrDepthReadable || pass.targets.hdrFramebuffer == null) {
            return;
        }
        VulkanImage hdrDepth = pass.targets.hdrFramebuffer.getDepthAttachment();
        ChimeraMainPass.trace("resolveWorldDepth", "hdrDepth", hdrDepth, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        hdrDepth.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        this.hdrDepthReadable = true;
    }
}
