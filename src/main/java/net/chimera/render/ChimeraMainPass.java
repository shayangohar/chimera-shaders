package net.chimera.render;

import net.chimera.config.ChimeraConfig;
import net.chimera.mixin.ChimeraRendererAccessor;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.ChimeraPostPipelines;
import net.chimera.render.shader.ChimeraTerrainPipelines;
import net.chimera.render.shader.PackGeometryContext;
import net.chimera.shaderpack.ConformanceReport;
import net.chimera.shaderpack.CullingDirectives;
import net.chimera.shaderpack.GeometryOutputPlan;
import net.chimera.shaderpack.PackConfig;
import net.chimera.shaderpack.PackFrameSchedulePlan;
import net.chimera.shaderpack.PackPipelines;
import net.chimera.shaderpack.PackPlan;
import net.chimera.shaderpack.PackProgram;
import net.chimera.shaderpack.PackSource;
import net.chimera.shaderpack.PackTargetGraphPlan;
import net.chimera.shaderpack.PostTargetPlan;
import net.chimera.shaderpack.ProgramImageBindingManifest;
import net.chimera.shaderpack.ScreenOverlayDirectives;
import net.chimera.shaderpack.SkyDirectives;
import net.chimera.shaderpack.TargetSpec;
import net.chimera.shaderpack.WeatherDirectives;
import net.minecraft.client.renderer.RenderType;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.pass.MainPass;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Chimera's main pass. The world draws into an internal HDR target and everything after it into a separate
 * output target, so a screen change can retire the world target at a frame boundary while the GUI keeps its
 * target, and the two never transition the same image.
 *
 * The world clear opens the HDR target, in colortex0's format under a pack and RGBA16F without one. The
 * shadow map records just before the sky (PreSkyPasses), then the world target resumes. At renderLevel's
 * return the world resolves into the output target. Until then Minecraft's main target is the HDR target;
 * after it, the output target, where the host hand, the GUI and post passes draw. {@link #end} presents the
 * output to the swapchain.
 *
 * This class schedules the frame and holds the pack session's state; each collaborator field owns one part
 * of the work.
 */
public final class ChimeraMainPass implements MainPass {
    // built before every other field, so collaborator constructors may only store the pass
    final PackSessionLifecycle sessions = new PackSessionLifecycle(this);
    final PackPostChain postChain = new PackPostChain(this);
    final MainPassTargets targets = new MainPassTargets(this);
    final PackPipelineSet pipelines = new PackPipelineSet();
    final PackShadowPass shadowPass = new PackShadowPass(this);
    final PackSceneCoverage coverage = new PackSceneCoverage(this);
    final PackImageBindings images = new PackImageBindings(this);
    final PackPipelineLoad load = new PackPipelineLoad(this);
    final OpaqueDepthCapture depth = new OpaqueDepthCapture(this);
    final MaterialMapRuntime materials = new MaterialMapRuntime(this);
    final PackFamilyWindows windows = new PackFamilyWindows(this);

    private static final HandBoundaryPolicy HAND_BOUNDARY_POLICY =
            new HandBoundaryPolicy(true, true);

    static record HandBoundaryPolicy(boolean loadColor, boolean clearDepth) {}

    static final Logger LOGGER = LoggerFactory.getLogger("chimera");

    /** Host pipeline state and viewport, saved around a fullscreen draw. */
    record PackRenderState(
            HostPipelineState pipeline,
            int viewportWidth,
            int viewportHeight
    ) {}

    PackRenderState capturePackRenderState() {
        Framebuffer boundFramebuffer = boundFramebuffer();
        if (boundFramebuffer == null) {
            boundFramebuffer = this.currentFramebuffer;
        }
        int viewportWidth = boundFramebuffer == null ? 0 : boundFramebuffer.getWidth();
        int viewportHeight = boundFramebuffer == null ? 0 : boundFramebuffer.getHeight();
        return new PackRenderState(HostPipelineState.capture(), viewportWidth, viewportHeight);
    }

    static void preparePackFullscreenState() {
        HostPipelineState.prepareFullscreen();
    }

    void restorePackRenderState(PackRenderState state) {
        if (state == null) return;
        state.pipeline().restore();
        if (state.viewportWidth() > 0 && state.viewportHeight() > 0) {
            Renderer.setViewport(0, 0, state.viewportWidth(), state.viewportHeight());
            // pack passes set the Vulkan scissor directly; the host's scissor box is GL state, so reapply it
            Renderer.applyScissor();
        }
    }

    static final boolean TRACE_TRANSITIONS = ChimeraRenderer.debugFlag("chimera.traceTransitions");
    Framebuffer currentFramebuffer;

    private GraphicsPipeline presentPipeline;
    final OutputResolvePipelines outputResolve = new OutputResolvePipelines();
    final PackPostTargets packPostTargets = new PackPostTargets();
    final PackDepthTargets packDepthTargets = new PackDepthTargets();
    PackTargetGraphPlan packTargetGraph;
    PackFrameSchedulePlan packFrameSchedule = PackFrameSchedulePlan.empty();
    /** Per-session flags, once-only logs and plans; cleanUpPackVariant replaces it whole. */
    PackSessionState session = new PackSessionState();
    /**
     * Why this session draws the host frame instead, or null: mask-critical families that did not install,
     * or required features Chimera lacks.
     *
     * While set the session loads as if no pack were selected, so a resize or screen reset cannot rebuild a
     * half-installed frame. A pack change, reload or dimension variant clears it.
     */
    String packFrameUnsupported;
    // frame progress, reset when the world segment opens
    boolean packPostFrameStarted;
    boolean packEarlyPostCompleted;
    boolean packWorldResolved;
    boolean packFinalApplied;
    final VulkanImage[] packFinalInputs = new VulkanImage[PostTargetPlan.MAX_TARGET + 1];
    PackConfig.PackConfigData packConfig;
    PackPlan packPlan;
    List<PackProgram> packPrograms;
    /** Holds a zip pack's extracted sources until the session tears down. */
    PackSource.LoadResult packSource;
    /** Pack-owned sampled images. */
    PackResourceOwner packResourceOwner;
    /** Pack-owned writable images. */
    PackAdvancedImageOwner packAdvancedImageOwner;
    /** Iris's smoothed centre depth, when a fullscreen pass reads it. */
    PackCenterDepth packCenterDepth;
    PackStorageBufferOwner packStorageBufferOwner;
    /** The pack's computes, each dispatched before its deferred, composite or prepare pass draws. */
    PackPassComputes packPassComputes;
    /** What the pack declares, and what of it compiled and installed. */
    ConformanceReport conformanceReport;
    /** HDR image format: the pack's colortex0 format, RGBA8 if undeclared as under Iris; RGBA16F without a pack. */
    int packHdrFormat = 97;
    /** A loaded post program may sample the HDR depth attachment. */
    boolean packNeedsHdrDepth;
    /** Selector bindings to restore once a terrain draw with pack geometry textures ends. */
    ProgramImageBindingTransaction<ChimeraTextureBindingState.Snapshot> packGeometryBindings;
    record ProgramImages(String name, ProgramImageBindingManifest manifest,
                                Set<String> shadowSamplerSymbols) {}
    /** The current translucent layer draws with the fixed pipeline, since gbuffers_water's inputs are not ready. */
    boolean packTranslucentSuspended;
    /** Which screen overlays the pack keeps (Iris vignette and underwaterOverlay); null without a pack. */
    ScreenOverlayDirectives screenOverlays;
    /** Which parts of the sky the pack keeps (Iris sun, moon, stars and sky); null without a pack. */
    SkyDirectives skyDirectives;
    /** Whether the pack's rain and snow write depth (Iris rain.depth); null without a pack. */
    WeatherDirectives weatherDirectives;
    /** Whether the pack's camera culls to its view frustum (Iris frustum.culling); null without a pack. */
    CullingDirectives cullingDirectives;
    /** Set from the world target opening until the frame ends. */
    boolean levelPhase;
    /** Vanilla asked for a depth clear while no render pass was open; the next rebind applies it. */
    boolean pendingDepthClear;
    private final ChimeraHorizonRenderer horizonRenderer = new ChimeraHorizonRenderer();

    /** While set, {@code begin} opens and clears the output target; rebuilding the world attachments resets it. */
    boolean earlyOutputPass = true;

    public ChimeraMainPass() {
        this(PackSessionLifecycle.startupPackPath());
    }

    ChimeraMainPass(Path initialPackPath) {
        this.sessions.packPath = initialPackPath;
        this.levelPhase = true;
        PackFailureNotices.setReporter(new PackFailureReporter(this));
        targets.createResources();
        // VulkanMod resizes the current main pass itself; this covers frames where the host pass is current,
        // without resizing ours twice
        Renderer.getInstance().addOnResizeCallback(() -> {
            if (Renderer.getInstance().getMainPass() != this) onResize();
        });
    }

     Path selectedPackPath() {
        return sessions.selectedPackPath();
    }

    ChimeraRenderer.LoadedPack loadedPack() {
        return sessions.loadedPack();
    }

    public void renderShadowSegment(double cameraX, double cameraY, double cameraZ) {
        shadowPass.renderShadowSegment(cameraX, cameraY, cameraZ);
    }

    public boolean consumeShadowPending() {
        return shadowPass.consumeShadowPending();
    }
    /**
     * On a screen change, keeps the output target and recreates only the world attachments, at the next
     * frame boundary (MainPassTargets.refreshWorldResourcesIfDue).
     */
    public void scheduleScreenResourceReset() {
        this.earlyOutputPass = true;
        if (TRACE_TRANSITIONS) {
            LOGGER.info("[chimera] scheduled world attachment reset frame={} hdrId={}",
                    Renderer.getCurrentFrame(), targets.hdrImageId());
        }
        this.targets.worldRefreshDue = true;
    }

     boolean prepareForInstall() {
        return sessions.prepareForInstall();
    }

     boolean applyPendingPackVariantAtFrameBoundary() {
        return sessions.applyPendingPackVariantAtFrameBoundary();
    }

     boolean hasPendingPackChange() {
        return sessions.hasPendingPackChange();
    }

     boolean queuePackChange(Path path, boolean forceReload) {
        return sessions.queuePackChange(path, forceReload);
    }

     boolean queuePackReload() {
        return sessions.queuePackReload();
    }

     String packStatus() {
        return sessions.packStatus();
    }

    public void openLevelSegment(double cameraX, double cameraY, double cameraZ, float[] fogColor) {
        targets.ensureWorldResources();
        Renderer.getInstance().endRenderPass();
        if (this.packStorageBufferOwner != null) {
            this.packStorageBufferOwner.beginExecutionFrame();
            // Iris sizes relative buffers, like relative images, from the main render target; a buffer that
            // cannot grow would be read past its end, so the pack is turned off
            String unfit = this.packStorageBufferOwner.fitRelativeBuffers(Renderer.getCommandBuffer(),
                    this.targets.hdrFramebuffer.getWidth(), this.targets.hdrFramebuffer.getHeight());
            if (unfit != null && !this.session.packPostChainRejected) {
                postChain.turnPackOff("STORAGE_BUFFER_REFIT_FAILED",
                        "a storage buffer", "it cannot grow to the window's size", new IllegalStateException(unfit));
            }
            boolean initialized = this.packStorageBufferOwner.initialize(Renderer.getCommandBuffer());
            if (initialized || !this.session.packStorageInitializationLogged) {
                load.logPackStoragePath("storage-initialization");
                this.session.packStorageInitializationLogged = true;
            }
            this.packStorageBufferOwner.prepareForUse(Renderer.getCommandBuffer());
        }
        if (this.packAdvancedImageOwner != null) {
            // Iris sizes relative images from the main render target
            this.packAdvancedImageOwner.prepareFrame(Renderer.getCommandBuffer(),
                    this.targets.hdrFramebuffer.getWidth(), this.targets.hdrFramebuffer.getHeight());
        }
        this.currentFramebuffer = this.targets.hdrFramebuffer;
        this.depth.hdrDepthReadable = false;
        this.depth.markWorldDrawn();
        this.shadowPass.shadowFrameReady = false;
        this.packPostFrameStarted = false;
        this.packEarlyPostCompleted = false;
        this.packWorldResolved = false;
        this.packFinalApplied = false;
        if (this.packDepthTargets.isConfigured()) {
            this.packDepthTargets.beginFrame(Renderer.getCommandBuffer());
        }
        if (this.packCenterDepth != null) this.packCenterDepth.beginFrame(Renderer.getCommandBuffer());
        if (this.packPostTargets.isConfigured()) {
            postChain.beginPostFrame(Renderer.getCommandBuffer(),
                    PackConfig.targetSettings(this.packConfig, 0).irisClearColor(0, fogColor));
        }
        shadowPass.beginShadowFrame(cameraX, cameraY, cameraZ);

        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanImage hdrColor = this.targets.hdrFramebuffer.getColorAttachment();
            trace("openHdr", "hdrColor", hdrColor, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            Renderer.getInstance().beginRendering(this.targets.worldOpenPass(), this.targets.hdrFramebuffer);
            Renderer.setViewport(0, 0, this.targets.hdrFramebuffer.getWidth(), this.targets.hdrFramebuffer.getHeight(),
                    stack);
            VK10.vkCmdSetScissor(commandBuffer, 0, this.targets.hdrFramebuffer.scissor(stack));
            clearWorldTarget(fogColor);
            this.levelPhase = true;
            this.pendingDepthClear = false;
        }
        if (this.session.packPostChainActive && postChain.hasOpeningWork()) {
            postChain.runOpeningWindows(commandBuffer);
        }
    }

    /**
     * Clears the world target as it opens. With a pack's colortex0 in the HDR image this is Iris's ClearPass:
     * colortex0ClearColor, else the fog colour with alpha 1.
     *
     * Under colortex0Clear=false only depth clears. PackPostTargets.beginFrame has already copied in the
     * colortex0 side the world draws first, so a new pack starts from its full clear and a new HDR image
     * after a screen change keeps the content.
     */
    private void clearWorldTarget(float[] fogColor) {
        if (!this.session.packPostChainActive || !this.packPostTargets.isConfigured()) {
            Renderer.clearAttachments(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            return;
        }
        PackConfig.TargetSettings colortex0 = PackConfig.targetSettings(this.packConfig, 0);
        if (!colortex0.clear()) {
            Renderer.clearAttachments(GL11.GL_DEPTH_BUFFER_BIT);
            return;
        }
        float[] color = colortex0.irisClearColor(0, fogColor);
        VRenderSystem.setClearColor(color[0], color[1], color[2], color[3]);
        Renderer.clearAttachments(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
    }

    /** True while the HDR image is a pack's colortex0. */
    public boolean packOwnsColortex0() {
        return this.levelPhase && this.session.packPostChainActive && this.packPostTargets.isConfigured();
    }

    /**
     * Draws the horizon after the world clear and before the sky. Without a gbuffers_skybasic it draws as
     * Iris's SKY_BASIC fallback does (PackHostDraws).
     */
    public void drawPackHorizon(Matrix4f modelView, Matrix4f projection) {
        if (this.levelPhase && this.skyDirectives != null && this.skyDirectives.sky()) {
            this.horizonRenderer.draw(modelView, projection);
        }
    }

    /** Resolves the world into the output target, through the pack's post passes when they run. */
    public void finishLevelSegment() {
        if (!this.levelPhase || this.targets.hdrFramebuffer == null) {
            return;
        }

        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        Renderer.getInstance().endRenderPass(commandBuffer);
        if (this.packStorageBufferOwner != null) {
            this.packStorageBufferOwner.prepareForUse(commandBuffer);
        }
        if (PackGeometryContext.active()) {
            PackGeometryContext.close();
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanImage hdrColor = this.targets.hdrFramebuffer.getColorAttachment();
            trace("resolveWorld", "hdrColor", hdrColor, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            hdrColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            if (this.packNeedsHdrDepth) {
                depth.captureWorldDepth(stack, commandBuffer);
            }

            if (coverage.packCoverageRuntimeEnabled() && this.coverage.packCoverageState.canSeed()) {
                coverage.seedPackScene(commandBuffer, hdrColor);
            }

            if (this.session.packPostChainActive && this.packPostTargets.isConfigured()) {
                postChain.runWorldPostWindows(commandBuffer, hdrColor);
                resolvePackWorldToOutput(commandBuffer, hdrColor);
                this.packWorldResolved = true;
                this.currentFramebuffer = this.targets.compositeFramebuffer;
                coverage.endCoverageFrame();
                // pack final must finish before vanilla submits the hand, or hand and GUI draws are lost
                postChain.finishPackFinalBeforeHand();
                return;
            }

            postChain.commitPostFrame();
            // post chain blocked or unconfigured: only HDR target 0 exists, so the pack composite can't bind
            // its auxiliary samplers; present the HDR image through the identity resolve instead and leave
            // installed pack objects alone for cleanup and reload
            resolveWorldToOutput(commandBuffer, hdrColor);
            this.currentFramebuffer = this.targets.compositeFramebuffer;
            coverage.endCoverageFrame();
        }
    }

    public void beginPackCoverageWindow(TerrainRenderType renderType) {
        coverage.beginPackCoverageWindow(renderType);
    }

    /**
     * The pack outputs a lane's draws need a window for, or null when they draw into the open pass: the lane
     * writes only target 0, or the shadow pass owns the attachments.
     */
    public GeometryOutputPlan laneWindowOutputs(ChimeraEntityBridge.Family lane) {
        GeometryOutputPlan outputs = pipelines.laneOutputs(lane);
        return outputs != null && outputs.requiresDynamicAttachments() && !shadowPass.shadowPassActive
                ? outputs : null;
    }

    /** Draws buffered family batches inside their lanes' pack windows (FamilyBufferSource). */
    public void drawFamilyBatches(List<RenderType> batches, Consumer<RenderType> draw,
                                  Consumer<RenderType> discard) {
        windows.drawFamilyBatches(batches, draw, discard);
    }

    public boolean beginPackFamilyWindow(PackPipelines.PackEntity family) {
        return windows.beginPackFamilyWindow(family);
    }

    public boolean beginPackFamilyWindow(GeometryOutputPlan outputs) {
        return windows.beginPackFamilyWindow(outputs);
    }

    public void endPackFamilyWindow(PackPipelines.PackEntity family) {
        windows.endPackFamilyWindow(family);
    }

    public void endPackFamilyWindow(GeometryOutputPlan outputs) {
        windows.endPackFamilyWindow(outputs);
    }
     boolean packCoverageRuntimeEnabled() {
        return coverage.packCoverageRuntimeEnabled();
    }

    public void endPackCoverageWindow(TerrainRenderType renderType) {
        coverage.endPackCoverageWindow(renderType);
    }

    public void bindTerrainImagesAfterSelector(TerrainRenderType renderType) {
        windows.bindTerrainImagesAfterSelector(renderType);
    }

    @Override
    public void begin(VkCommandBuffer commandBuffer, MemoryStack stack) {
        targets.ensureOutputResources();
        this.currentFramebuffer = this.targets.compositeFramebuffer;
        this.levelPhase = false;
        this.pendingDepthClear = false;

        if (this.earlyOutputPass) {
            trace("beginOutput", "outputColor",
                    this.targets.compositeFramebuffer.getColorAttachment(), VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            Renderer.getInstance().beginRendering(this.targets.compositeRenderPass, this.targets.compositeFramebuffer);
            Renderer.clearAttachments(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        }
    }

    @Override
    public void end(VkCommandBuffer commandBuffer) {
        if (this.levelPhase && this.currentFramebuffer == this.targets.hdrFramebuffer) {
            finishLevelSegment();
        }
        if (this.packWorldResolved && !this.packFinalApplied) {
            // pack final runs at renderLevel's return; if it did not run, keep the resolved image and let the
            // host hand and GUI draw over it
            if (this.session.packFinalExecution != null) {
                postChain.markPostStageIdentityBoundary(this.session.packFinalExecution,
                        "level boundary not reached");
            }
            this.packFinalApplied = true;
        }
        Renderer.getInstance().endRenderPass(commandBuffer);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanImage outputColor = this.targets.compositeFramebuffer.getColorAttachment();
            trace("presentRead", "outputColor", outputColor, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            outputColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            if (this.packNeedsHdrDepth) {
                depth.transitionHdrDepthForSampling(stack, commandBuffer);
            }
            VTextureSelector.bindTexture(outputColor);
            presentToSwapChain(commandBuffer, stack, outputColor);
        }

        this.levelPhase = false;
        int result = vkEndCommandBuffer(commandBuffer);
        if (result != VK_SUCCESS) {
            throw new RuntimeException("Failed to record command buffer: " + result);
        }
    }

    /** Draws the output target onto the swapchain image and leaves that image ready to present. */
    private void presentToSwapChain(VkCommandBuffer commandBuffer, MemoryStack stack, VulkanImage outputColor) {
        SwapChain swapChain = Vulkan.getSwapChain();
        if (swapChain.getId() == VK_NULL_HANDLE) {
            return;
        }
        trace("presentSwap", "swapchain", swapChain.getColorAttachment(),
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        swapChain.getColorAttachment().transitionImageLayout(stack, commandBuffer,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        Renderer.getInstance().beginRendering(this.targets.presentRenderPass, swapChain);
        // the present draw would otherwise inherit the GUI's last viewport and scissor and leave the swapchain
        // on its clear colour; cover the whole swapchain as DefaultMainPass.begin does
        Renderer.setViewport(0, 0, swapChain.getWidth(), swapChain.getHeight(), stack);
        VK10.vkCmdSetScissor(commandBuffer, 0, swapChain.scissor(stack));
        // VulkanMod picks the pipeline variant from the VRenderSystem state at bind time, and the GUI leaves
        // its blend and colour mask behind; SRC_ALPHA blending over the output's zero alpha, a cleared colour
        // mask or a depth test against the DONT_CARE swapchain depth would make the quad write nothing, so draw
        // with the opaque fullscreen state and restore the globals after
        PackRenderState previousState = capturePackRenderState();
        preparePackFullscreenState();
        try {
            // pack final already ran before the hand; the swapchain always gets the host present pipeline
            drawFullscreen(commandBuffer, this.presentPipeline);
            // end the frame with slot 0 on outputColor, as the identity path does; slot 0 may still hold
            // hdrColor from bindAsTexture, and the next world target rebuild would hand the host a dead image
            if (this.pipelines.finalPipeline != null || this.pipelines.finalPost != null) {
                VTextureSelector.bindTexture(outputColor);
            }
        } finally {
            restorePackRenderState(previousState);
        }
        Renderer.getInstance().endRenderPass(commandBuffer);

        trace("presentSrc", "swapchain", swapChain.getColorAttachment(), VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
        swapChain.getColorAttachment().transitionImageLayout(stack, commandBuffer,
                VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
    }

    void drawFullscreen(VkCommandBuffer commandBuffer, GraphicsPipeline pipeline) {
        Renderer renderer = Renderer.getInstance();
        renderer.bindGraphicsPipeline(pipeline);
        renderer.uploadAndBindUBOs(pipeline);
        VK10.vkCmdDraw(commandBuffer, ChimeraPostPipelines.FULLSCREEN_VERTICES, 1, 0, 0);
        ProgramImages program = this.images.programImages.get(pipeline);
        if (program != null && this.packStorageBufferOwner != null) {
            this.packStorageBufferOwner.recordDraw(program.name(), VK_SHADER_STAGE_FRAGMENT_BIT);
        }
    }

    private void resolvePackWorldToOutput(VkCommandBuffer commandBuffer, VulkanImage hdrColor) {
        VulkanImage resolved = this.packPostTargets.activeTarget(0);
        resolveWorldToOutput(commandBuffer, resolved == null ? hdrColor : resolved);
    }

    /** Draws {@code source} into the output target through the host resolve pipeline, never a pack program. */
    void resolveWorldToOutput(
            VkCommandBuffer commandBuffer,
            VulkanImage source
    ) {
        GraphicsPipeline resolvePipeline = outputResolvePipeline();
        PackRenderState previousState = capturePackRenderState();
        preparePackFullscreenState();
        ProgramImageBindingTransaction<ChimeraTextureBindingState.Snapshot> previous = null;
        try {
            images.prepareProgramImages(resolvePipeline);
            previous = images.bindProgramImages(resolvePipeline, null, source,
                    DrawMaterialContext.captureLive());
            Renderer.getInstance().beginRendering(this.targets.compositeRenderPass, this.targets.compositeFramebuffer);
            drawFullscreen(commandBuffer, resolvePipeline);
        } finally {
            if (previous != null) previous.close();
            restorePackRenderState(previousState);
        }
    }

    /** Copies the pre-hand depth into depthtex2 before the pack's solid hand draws. */
    public void beginHandSegment() {
        if (this.levelPhase && this.packDepthTargets.isConfigured()
                && this.packDepthTargets.plan().depthtex2()) {
            var commandBuffer = Renderer.getCommandBuffer();
            Renderer.getInstance().endRenderPass(commandBuffer);
            if (!this.packDepthTargets.currentAvailable("depthtex2")) {
                this.packDepthTargets.capturePreHand(commandBuffer, this.targets.hdrFramebuffer.getDepthAttachment());
            }
            this.rebindMainTarget();
            return;
        }
        if (!this.packWorldResolved || !this.packDepthTargets.isConfigured()
                || !this.packDepthTargets.plan().depthtex2()) {
            return;
        }
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        if (Renderer.getInstance().getBoundRenderPass() != null) {
            Renderer.getInstance().endRenderPass(commandBuffer);
        }
        if (!this.packDepthTargets.currentAvailable("depthtex2")
                && !this.packDepthTargets.capturePreHand(commandBuffer,
                this.targets.hdrFramebuffer == null ? null : this.targets.hdrFramebuffer.getDepthAttachment())) {
            LOGGER.warn("[chimera] pack depth graph: fallback=IDENTITY (pre-hand depth conversion failed)");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // the world is already resolved into the output: load its colour and give the hand cleared depth
            RenderPass handPass = HAND_BOUNDARY_POLICY.clearDepth()
                    ? this.targets.compositeAuxClearDepthRenderPass : this.targets.compositeAuxRenderPass;
            Renderer.getInstance().beginRendering(handPass,
                    this.targets.compositeFramebuffer);
            Renderer.setViewport(0, 0, this.targets.compositeFramebuffer.getWidth(),
                    this.targets.compositeFramebuffer.getHeight(), stack);
            VK10.vkCmdSetScissor(commandBuffer, 0, this.targets.compositeFramebuffer.scissor(stack));
        }
    }

    /** Whether the pack draws the hand: only in a live pack frame with both hand programs installed. */
    public boolean hasPackHandSchedule() {
        return this.levelPhase && this.session.packPostChainActive && this.session.packTargetResourcesReady
                && this.pipelines.hand != null && this.pipelines.handWater != null;
    }

    static String runtimeFailureReason(String phase, RuntimeException failure) {
        String message = failure.getMessage();
        return "phase=" + phase + ",exception=" + failure.getClass().getName()
                + (message == null || message.isBlank() ? "" : ",message=" + message);
    }

    boolean shouldLogPackRuntimeFailure(String reason) {
        if (!reason.contains("PACK_TARGET_FRAME_NOT_STARTED")) return true;
        if (this.session.packTargetFrameNotStartedLogged) return false;
        this.session.packTargetFrameNotStartedLogged = true;
        return true;
    }

    void logPackRuntimeFailure(String phase, RuntimeException failure) {
        String reason = runtimeFailureReason(phase, failure);
        if (shouldLogPackRuntimeFailure(reason)) {
            LOGGER.warn("[chimera] pack runtime fallback ({})", reason);
            LOGGER.debug("[chimera] pack runtime failure", failure);
        }
    }

    /** Marks a group of world draws done, such as a particle call; the next depthtex0 reader captures it again. */
    public void markWorldDrawn() {
        depth.markWorldDrawn();
    }

    public void captureOpaqueDepthBeforeTranslucent() {
        depth.captureOpaqueDepthBeforeTranslucent();
    }

    /** Iris's beginHand centre-depth sample, before the hand draws. */
    public void sampleCenterDepthBeforeHand() {
        depth.sampleCenterDepthBeforeHand();
    }

    /**
     * Minecraft's main target bind before each frame's game render, which Reforged forwards here. A frame
     * that renders no level, such as one ticked while the world saves, draws into what this binds.
     */
    @Override
    public void mainTargetBindWrite() {
        rebindMainTarget();
    }

    @Override
    public void rebindMainTarget() {
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();

        if (this.shadowPass.shadowPassActive) {
            Framebuffer shadow = this.shadowPass.shadowMap.getShadowFramebuffer();
            if (boundFramebuffer() != shadow) {
                Renderer.getInstance().beginRendering(this.shadowPass.shadowMap.getShadowRenderPass(), shadow);
            }
            return;
        }

        Framebuffer target = this.currentFramebuffer != null
                ? this.currentFramebuffer
                : this.targets.compositeFramebuffer;
        boolean forceDepthClear = this.pendingDepthClear;
        if (boundFramebuffer() == target && !forceDepthClear) {
            return;
        }

        RenderPass pass;
        String imageName;
        if (target == this.targets.hdrFramebuffer) {
            pass = forceDepthClear ? this.targets.hdrAuxClearDepthRenderPass : this.targets.hdrAuxRenderPass;
            imageName = "hdrColor";
        } else {
            pass = forceDepthClear ? this.targets.compositeAuxClearDepthRenderPass
                    : this.targets.compositeAuxRenderPass;
            imageName = "outputColor";
        }

        this.pendingDepthClear = false;
        trace("rebind", imageName, target.getColorAttachment(), VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        if (forceDepthClear) {
            Renderer.getInstance().endRenderPass(commandBuffer);
        }
        Renderer.getInstance().beginRendering(pass, target);
    }

    @Override
    public void bindAsTexture() {
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        VulkanImage color = currentFramebuffer().getColorAttachment();

        if (Renderer.getInstance().getBoundRenderPass() != null) {
            Renderer.getInstance().endRenderPass(commandBuffer);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            color.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
        VTextureSelector.bindTexture(color);
    }

    @Override
    public VulkanImage getColorAttachment() {
        return currentFramebuffer().getColorAttachment();
    }

    @Override
    public VulkanImage getDepthAttachment() {
        return currentFramebuffer().getDepthAttachment();
    }

    /** The OpenGL name Minecraft's main target reads the current phase's color image through. */
    @Override
    public int getColorAttachmentGlId() {
        return this.currentFramebuffer == this.targets.hdrFramebuffer
                ? this.targets.hdrColorGlId
                : this.targets.compositeColorGlId;
    }

    @Override
    public int getDepthAttachmentGlId() {
        return this.currentFramebuffer == this.targets.hdrFramebuffer
                ? this.targets.hdrDepthGlId
                : this.targets.compositeDepthGlId;
    }

    @Override
    public RenderPass resumeRenderPass(RenderPass renderPass) {
        return this.targets.resumePass(renderPass);
    }

    public void requestPendingDepthClear() {
        this.pendingDepthClear = true;
    }

    /** Reopens the current target with a depth clear; does nothing outside the level phase. */
    public void reopenWithPendingClear() {
        if (!this.levelPhase) {
            return;
        }
        this.requestPendingDepthClear();
        this.rebindMainTarget();
    }

    Framebuffer currentFramebuffer() {
        return this.currentFramebuffer != null
                ? this.currentFramebuffer
                : this.targets.compositeFramebuffer;
    }

    /** The framebuffer VulkanMod is recording into, or null between passes. */
    static Framebuffer boundFramebuffer() {
        return ((ChimeraRendererAccessor) Renderer.getInstance()).chimera$boundFramebuffer();
    }

    @Override
    public void cleanUp() {
        stopRenderWorkForCleanup();
        sessions.waitForImmediateDestruction("final pack cleanup", this::releaseAfterIdle);
    }

    /** Final teardown, after the device goes idle, so every retired handle can go at once. */
    private void releaseAfterIdle() {
        this.horizonRenderer.close();
        ChimeraCloudRenderer.shared().close();
        sessions.cleanUpPackVariant();
        // material maps live as long as the renderer: a pack change never closes them, so a failed install
        // keeps the previous ones; they close only here, while the device is still alive
        if (this.materials.materialMapOwner != null) this.materials.materialMapOwner.close();
        this.materials.materialMapOwner = null;
        targets.cleanUpFramebuffersAndPasses();
        cleanUpPipelines();
        ChimeraTerrainPipelines.cleanUp();
        if (this.packSource != null) this.packSource.close();
        this.packSource = null;
        this.sessions.packPath = null;
        this.sessions.packChangeQueue.clear();
        this.sessions.loggedPendingPackChange = null;
        this.sessions.pendingPackDimension = null;
        this.sessions.loggedPendingPackDimension = null;
        this.conformanceReport = null;
    }

    public MaterialMapOwner materialMaps() {
        return materials.materialMaps();
    }

    public void pumpMaterialMapBuilds() {
        materials.pumpMaterialMapBuilds();
    }

    /** Ends whatever Chimera work is still recording before the final Vulkan objects are destroyed. */
    private void stopRenderWorkForCleanup() {
        if (this.packPostTargets.isRendering()) {
            try {
                this.packPostTargets.abort(Renderer.getCommandBuffer());
            } catch (RuntimeException failure) {
                logPackRuntimeFailure("post-cleanup-abort", failure);
            }
        }
        if (Renderer.isRecording()) {
            Renderer.getInstance().endRenderPass();
        }
        if (PackGeometryContext.active()) {
            PackGeometryContext.close();
        }
        if (Renderer.getInstance().getBoundRenderPass() != null) {
            Renderer.getInstance().setBoundRenderPass(null);
            Renderer.getInstance().setBoundFramebuffer(null);
        }
    }

    public void onLevelChanged(String dimension) {
        sessions.onLevelChanged(dimension);
    }

    @Override
    public void onResize() {
        if (targets.serveSwapchain()) return;
        targets.createResources();
    }

    static int deviceMaxColorAttachments() {
        if (DeviceManager.deviceProperties == null) {
            return PackTargetGraphPlan.LOGICAL_ATTACHMENT_LIMIT;
        }
        return DeviceManager.deviceProperties.limits().maxColorAttachments();
    }

    static int deviceMaxImageDimension() {
        if (DeviceManager.deviceProperties == null) {
            return Integer.MAX_VALUE;
        }
        return DeviceManager.deviceProperties.limits().maxImageDimension2D();
    }

    int packGeometryTargetFormat() {
        TargetSpec target = this.packTargetGraph == null ? null : this.packTargetGraph.target(0);
        return target == null ? this.packHdrFormat : target.format();
    }

    void createPipelines() {
        this.presentPipeline = ChimeraPostPipelines.create("chimera_present");
        this.outputResolve.create();
    }

    public boolean packRuntimeRejected() { return this.session.packPostChainRejected; }

    public boolean packLoaded() { return this.packPlan != null; }

    public ScreenOverlayDirectives screenOverlays() { return this.screenOverlays; }

    public SkyDirectives skyDirectives() { return this.skyDirectives; }

    public CullingDirectives cullingDirectives() { return this.cullingDirectives; }

    public WeatherDirectives weatherDirectives() { return this.weatherDirectives; }

    public void recordShadowDraw() {
        shadowPass.recordShadowDraw();
    }

    public ProgramImageBindingTransaction<ChimeraTextureBindingState.Snapshot> bindDescriptorImages(
            GraphicsPipeline pipeline) {
        return images.bindDescriptorImages(pipeline);
    }

    public void prepareProgramImages(GraphicsPipeline pipeline) {
        images.prepareProgramImages(pipeline);
    }

    /** The targets {@code pipeline}'s program holds as storage while its run draws (PackImageBindings). */
    List<Integer> worldStorageTargets(GraphicsPipeline pipeline) {
        return images.worldStorageTargets(pipeline);
    }

    public boolean packShadowPassActive() {
        return shadowPass.packShadowPassActive();
    }

    /** The world-to-output resolve pipeline, converting to the Color Space setting where Iris does. */
    GraphicsPipeline outputResolvePipeline() {
        boolean packRuns = packLoaded() && !packRuntimeRejected();
        return this.outputResolve.forSpace(OutputResolvePipelines.outputSpace(packRuns,
                packRuns && this.packPlan.supportsColorCorrection(), ChimeraConfig.get().colorSpace()));
    }

    void cleanUpPipelines() {
        if (this.presentPipeline != null) this.presentPipeline.cleanUp();
        this.outputResolve.cleanUp();
        if (this.coverage.sceneSeedPipeline != null) this.coverage.sceneSeedPipeline.cleanUp();
        this.presentPipeline = null;
        this.coverage.sceneSeedPipeline = null;
    }

    /**
     * Logs an image transition under {@code -Dchimera.traceTransitions}. The last line before a native crash
     * names the faulting site and image.
     */
    static void trace(String site, String image, VulkanImage img, int toLayout) {
        if (!TRACE_TRANSITIONS) {
            return;
        }
        LOGGER.info("[chimera] tr {} frame={} {} id={} {}->{}", site,
                Renderer.getCurrentFrame(), image, img.getId(),
                layoutName(img.getCurrentLayout()), layoutName(toLayout));
    }

    private static String layoutName(int layout) {
        return switch (layout) {
            case 0 -> "UNDEFINED";
            case 2 -> "COLOR_ATTACHMENT";
            case 5 -> "SHADER_READ_ONLY";
            case 6 -> "GENERAL";
            case 7 -> "TRANSFER_DST";
            case 1000001002 -> "PRESENT_SRC";
            default -> "vk" + layout;
        };
    }
}
