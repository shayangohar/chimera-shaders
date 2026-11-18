// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import net.chimera.config.ChimeraConfig;
import net.chimera.render.shader.ChimeraTerrainPipelines;
import net.chimera.render.shader.PackGeometryContext;
import net.chimera.render.shader.PackUniformProvider;
import net.chimera.shaderpack.PackPlan;
import net.chimera.shaderpack.ProgramImageBindingManifest;
import net.chimera.shaderpack.ShadowCasterDirectives;
import net.chimera.shaderpack.ShadowCullingPlan;
import net.chimera.shaderpack.ShadowPassUse;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.PipelineManager;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.lwjgl.vulkan.VK10.*;

/**
 * The main pass's shadow pass. Records the pack's shadow map, or the host's fixed one, before the opaque
 * terrain samples it, as Iris renders shadows first; entity casters and the translucent layer draw into it
 * too. Also keeps the map's sampling state and views.
 */
final class PackShadowPass {
    private static final Logger LOGGER = LoggerFactory.getLogger("chimera");
    private final ChimeraMainPass pass;

    /** Iris's AlphaTests.ONE_TENTH_ALPHA, its cutout shadow pass's test; here the opaque layers'. */
    static final float SHADOW_CUTOUT_ALPHA = 0.1F;

    ChimeraShadowMap shadowMap = new ChimeraShadowMap();
    final PackShadowDepth packShadowDepth = new PackShadowDepth();
    final PackShadowComposites composites;

    /**
     * While set, rebindMainTarget opens the shadow render pass instead of the main target, so
     * renderSectionLayer's rebind sends terrain into the shadow map.
     */
    boolean shadowPassActive;

    /**
     * The pack shadow program the open terrain draws run: shadow_cutout for the cutout layers and
     * shadow_water for the translucent one where the pack has them, shadow otherwise.
     */
    private String shadowTerrainProgram = "shadow";
    /** The plan colorReads was taken from, and the shadowcolor targets its shadow programs sample. */
    private PackPlan colorReadsPlan;
    private Set<Integer> colorReads = Set.of();

    /** Armed as the level segment opens, consumed just before the sky (PreSkyPasses): one shadow segment a frame. */
    boolean shadowPending;

    /** False when the shadow map could not be prepared this frame. */
    boolean shadowFrameReady;

    PackShadowPass(ChimeraMainPass pass) {
        this.pass = pass;
        this.composites = new PackShadowComposites(pass);
    }

    /**
     * Records the shadow pass: the terrain sections the shadow culling keeps, with every facing, then the
     * pack's casters and translucent layer, and binds the map for the receivers. Runs with no render pass
     * open; renderSectionLayer's rebind opens the shadow pass through shadowPassActive, and this closes it.
     */
    public void renderShadowMap(double cameraX, double cameraY, double cameraZ) {
        if (!this.shadowFrameReady || !shadowAttachmentsLive()) {
            return;
        }
        VkCommandBuffer cmd = Renderer.getCommandBuffer();
        VulkanImage shadowColor = this.shadowMap.getShadowFramebuffer().getColorAttachment();
        VulkanImage shadowDepth = this.shadowMap.getShadowFramebuffer().getDepthAttachment();
        boolean packShadow = usePackShadowRuntime();
        Matrix4f projection = packShadow
                ? this.shadowMap.getPackLightProjection()
                : this.shadowMap.getHostLightProjection();
        ShadowFrame frame = new ShadowFrame(cmd, shadowColor, shadowDepth, packShadow, projection,
                cameraX, cameraY, cameraZ);
        ProgramImageBindingTransaction<ChimeraTextureBindingState.Snapshot> shadowBindings = null;
        try {
            // receivers keep the last committed map state until this pass commits its own (commitRenderedMap)
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ChimeraMainPass.trace("shadowPre", "shadowColor", shadowColor,
                        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                ChimeraMainPass.trace("shadowPre", "shadowDepth", shadowDepth,
                        VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
                shadowColor.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                shadowDepth.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            }

            try {
                if (packShadow) {
                    // the shadow pass draws, and its programs sample, every target's main side
                    this.shadowMap.resetColorSides();
                    this.shadowMap.snapshotPassReads(cmd, shadowPassColorReads());
                    shadowBindings = bindPackShadowImages(layerPipeline(TerrainLayers.OPAQUE));
                }
                beginShadowDraw(frame);
                ShadowCasterDirectives casters = packShadow && pass.packPlan != null
                        ? pass.packPlan.shadowCasters() : ShadowCasterDirectives.DEFAULTS;
                if (casters.terrain()) drawShadowTerrain(frame);
                if (packShadow) {
                    renderShadowCasters(cameraX, cameraY, cameraZ);
                    shadowBindings = drawShadowTranslucent(frame, shadowBindings, casters.translucent());
                }
            } finally {
                endShadowRecording(frame, shadowBindings);
            }
            publishShadowMap(frame);
        } catch (RuntimeException failure) {
            recoverShadowMap(frame, failure);
        } finally {
            this.shadowPassActive = false;
            PipelineManager.setShaderGetter(ChimeraTerrainPipelines::getTerrainPipeline);
        }
    }

    /** The shadowcolor targets the loaded plan's shadow programs sample (PackPlan.shadowPassColorReads). */
    private Set<Integer> shadowPassColorReads() {
        if (pass.packPlan != this.colorReadsPlan) {
            this.colorReadsPlan = pass.packPlan;
            this.colorReads = pass.packPlan == null ? Set.of() : pass.packPlan.shadowPassColorReads();
        }
        return this.colorReads;
    }

    /** One shadow map recording: its command buffer, attachments, caster path, projection and camera. */
    private record ShadowFrame(VkCommandBuffer cmd, VulkanImage color, VulkanImage depth, boolean pack,
            Matrix4f projection, double cameraX, double cameraY, double cameraZ) {}

    /**
     * Binds a pack shadow terrain program's own images for its draws. The pass opens before any
     * layer has bound its albedo, so each draw binds its host textures (bindDescriptorImages).
     */
    private ProgramImageBindingTransaction<ChimeraTextureBindingState.Snapshot> bindPackShadowImages(
            GraphicsPipeline pipeline) {
        pass.prepareProgramImages(pipeline);
        return pass.images.bindPassImages(pipeline);
    }

    /**
     * Draws the translucent terrain layer into the shadow map unless the pack's shadowTranslucent is off
     * (Iris ShadowRenderer.renderShadows), with shadow_water where the pack has it, else shadow. Its
     * program's images replace the cutout layers' where the programs differ, and it alpha tests only as
     * shadow_water declares, as Iris's SHADOW_TRANS does.
     *
     * shadowtex1, captured either way, holds only the opaque, cutout and entity casters; this layer reaches
     * shadowtex0 alone, and packs tint through shadowcolor where the two differ. Returns the bindings open
     * afterwards.
     */
    private ProgramImageBindingTransaction<ChimeraTextureBindingState.Snapshot> drawShadowTranslucent(
            ShadowFrame frame, ProgramImageBindingTransaction<ChimeraTextureBindingState.Snapshot> bindings,
            boolean draw) {
        if (Renderer.getInstance().getBoundRenderPass() != null) {
            Renderer.getInstance().endRenderPass(frame.cmd());
        }
        this.shadowMap.captureOpaqueDepth(frame.cmd());
        if (!draw) return bindings;
        ProgramImageBindingTransaction<ChimeraTextureBindingState.Snapshot> open = bindings;
        GraphicsPipeline translucent = layerPipeline(TerrainRenderType.TRANSLUCENT);
        if (translucent != layerPipeline(TerrainLayers.OPAQUE)) {
            if (open != null) open.close();
            open = bindPackShadowImages(translucent);
        }
        this.shadowTerrainProgram = layerProgram(TerrainRenderType.TRANSLUCENT);
        PackUniformProvider.updateDrawAlphaReference(0.0F);
        WorldRenderer.getInstance().renderSectionLayer(RenderType.translucent(), frame.cameraX(), frame.cameraY(),
                frame.cameraZ(), this.shadowMap.getLightView(), frame.projection());
        return open;
    }

    /**
     * Redirects terrain into the shadow pass, with the light's matrices and its sections; the pack
     * caster also draws inside its shadow geometry scope.
     */
    private void beginShadowDraw(ShadowFrame frame) {
        boolean packShadow = frame.pack();
        if (packShadow) {
            PackGeometryContext.beginShadow(this.shadowMap.shadowColors(), frame.depth(),
                    this.shadowMap.colorClears(false));
            // pack receivers read the map with GL's v = ndc * 0.5 + 0.5; the fixed host receiver
            // compensates for VulkanMod's flip
            ChimeraRasterOrientation.markGlOriented(this.shadowMap.getShadowRenderPass());
        }
        this.shadowPassActive = true;
        this.shadowTerrainProgram = layerProgram(TerrainLayers.OPAQUE);
        PipelineManager.setShaderGetter(rt -> {
            if (packShadow) {
                VRenderSystem.disableBlend();
                return layerPipeline(rt);
            }
            return this.shadowMap.getShadowPipeline();
        });
        if (packShadow) {
            // the matrix pair MVP and ftransform() use in the pack shadow draw
            this.shadowMap.publishCurrentDrawState();
        }
        VRenderSystem.applyProjectionMatrix(frame.projection());
        VRenderSystem.applyModelViewMatrix(this.shadowMap.getLightView());
        VRenderSystem.calculateMVP();
        beginShadowSections(frame.cameraX(), frame.cameraY(), frame.cameraZ(), packShadow);
        // Iris tests its cutout shadow pass at alpha 0.1, and solid and translucent terrain not at all
        // (SodiumPrograms.getAlphaTest)
        // VulkanMod builds solid blocks into cutout_mipped, so they take the test too; their opaque texels pass
        // without the test, foliage quads cast their whole square
        if (packShadow) {
            PackUniformProvider.updateDrawAlphaReference(SHADOW_CUTOUT_ALPHA);
        }
    }

    /**
     * The program a pack shadow terrain draw of {@code type} runs, as Iris's SodiumPrograms pick it,
     * where the pack has it: shadow_cutout's for the cutout and cutout_mipped layers (SHADOW_CUTOUT),
     * shadow_water's for the translucent one (SHADOW_TRANS); shadow's otherwise.
     */
    static <P> P shadowTerrainProgram(TerrainRenderType type, P shadow, P cutout, P water) {
        P layer = switch (type) {
            case CUTOUT, CUTOUT_MIPPED -> cutout;
            case TRANSLUCENT -> water;
            default -> null;
        };
        return layer != null ? layer : shadow;
    }

    /** The pack shadow pipeline a terrain layer draws with. */
    private GraphicsPipeline layerPipeline(TerrainRenderType type) {
        PackPipelineSet pipelines = pass.pipelines;
        return shadowTerrainProgram(type, pipelines.shadowPipeline, pipelines.shadowCutoutPipeline,
                pipelines.shadowWaterPipeline);
    }

    /** The name of the pack shadow program a terrain layer draws with. */
    private String layerProgram(TerrainRenderType type) {
        PackPipelineSet pipelines = pass.pipelines;
        return shadowTerrainProgram(type, "shadow", pipelines.shadowCutoutPipeline == null ? null : "shadow_cutout",
                pipelines.shadowWaterPipeline == null ? null : "shadow_water");
    }

    /** Draws the opaque terrain layer, then the cutout layer unless the host merged it into opaque. */
    private void drawShadowTerrain(ShadowFrame frame) {
        WorldRenderer.getInstance().renderSectionLayer(
                TerrainRenderType.getRenderType(TerrainLayers.OPAQUE),
                frame.cameraX(), frame.cameraY(), frame.cameraZ(),
                this.shadowMap.getLightView(), frame.projection());

        boolean cutoutDrawn = TerrainLayers.drawn(TerrainRenderType.CUTOUT);
        recordShadowCutoutDisposition(!cutoutDrawn);
        if (cutoutDrawn) {
            WorldRenderer.getInstance().renderSectionLayer(
                    RenderType.cutout(), frame.cameraX(), frame.cameraY(), frame.cameraZ(),
                    this.shadowMap.getLightView(), frame.projection());
        }
    }

    /**
     * Closes the shadow sections, the shadow pass, the pack's shadow geometry scope and its bindings.
     *
     * The pass's copies of the targets its programs sample close first, so shadowcomp and the receivers read
     * and write the targets themselves, as in Iris. Rethinking Voxels' shadowcomp2 steps its interactive water
     * in shadowcolor2, and each step would be lost on a copy.
     */
    private void endShadowRecording(ShadowFrame frame,
            ProgramImageBindingTransaction<ChimeraTextureBindingState.Snapshot> shadowBindings) {
        this.shadowMap.closePassReads();
        ShadowSectionQueue.end();
        if (Renderer.getInstance().getBoundRenderPass() != null) {
            Renderer.getInstance().endRenderPass(frame.cmd());
        }
        if (frame.pack()) {
            PackGeometryContext.close();
            ChimeraRasterOrientation.unmark(this.shadowMap.getShadowRenderPass());
        }
        this.shadowPassActive = false;
        if (shadowBindings != null) shadowBindings.close();
    }

    /**
     * Makes the recorded map sampleable, runs shadowcomp over it, then publishes the pack map or
     * clears the pack map state for the fixed host map, and binds it for the receivers.
     */
    private void publishShadowMap(ShadowFrame frame) {
        VkCommandBuffer cmd = frame.cmd();
        if (frame.pack() && pass.packStorageBufferOwner != null) {
            pass.packStorageBufferOwner.prepareForUse(cmd);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ChimeraMainPass.trace("shadowPost", "shadowColor", frame.color(),
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            ChimeraMainPass.trace("shadowPost", "shadowDepth", frame.depth(),
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            frame.color().transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            if (frame.pack()) {
                this.shadowMap.publishColorTargets(stack, cmd);
            }
            frame.depth().transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
        if (frame.pack() && pass.packAdvancedImageOwner != null) {
            pass.packAdvancedImageOwner.afterImageWrites(cmd);
        }
        // Iris runs shadowcomp only inside its ShadowRenderer, over the pack's own map
        if (frame.pack()) runShadowComposites(cmd);
        this.shadowMap.markInitialSamplingLayoutReady();
        if (frame.pack()) {
            this.shadowMap.commitRenderedMap(frame.cameraX(), frame.cameraY(), frame.cameraZ());
            this.shadowMap.publishMapState(frame.cameraX(), frame.cameraY(), frame.cameraZ());
        } else {
            this.shadowMap.invalidatePackMapSnapshot();
            PackUniformProvider.clearShadowState();
        }

        this.shadowMap.bindShadowTexture();
    }

    /**
     * Runs the shadowcomp passes over the new map, in pass order (ShadowCompositeRenderer.renderAll):
     * each pass's computes at the main target's size, then its program at the map's.
     */
    private void runShadowComposites(VkCommandBuffer cmd) {
        VulkanImage screen = pass.targets.hdrFramebuffer.getColorAttachment();
        this.composites.run(cmd, screen.width, screen.height);
    }

    /**
     * After a failed shadow write, clears the map to the all-lit fallback; when that fails too,
     * the frame's shadow inputs stay unavailable.
     */
    private void recoverShadowMap(ShadowFrame frame, RuntimeException failure) {
        VkCommandBuffer cmd = frame.cmd();
        double cameraX = frame.cameraX();
        double cameraY = frame.cameraY();
        double cameraZ = frame.cameraZ();
        this.shadowMap.invalidateMapSnapshot();
        this.shadowFrameReady = false;
        if (pass.conformanceReport != null) {
            pass.conformanceReport.addDeviation("SHADOW_MAP_WRITE_FAILED");
        }
        if (!pass.session.shadowTransitionFallbackLogged) {
            pass.session.shadowTransitionFallbackLogged = true;
            LOGGER.warn("[chimera] shadow map write failed; clearing to all-lit fallback: {}",
                    failure.toString());
        }
        try {
            if (Renderer.getInstance().getBoundRenderPass() != null) {
                Renderer.getInstance().endRenderPass(cmd);
            }
            this.shadowPassActive = false;
            PackGeometryContext.close();
            this.shadowMap.initializeSampling(cmd);
            this.shadowMap.seedClearedMap(cameraX, cameraY, cameraZ);
            this.shadowMap.publishMapState(cameraX, cameraY, cameraZ);
            this.shadowMap.bindShadowTexture();
            this.shadowFrameReady = true;
        } catch (RuntimeException clearFailure) {
            this.shadowFrameReady = false;
            PackUniformProvider.clearShadowState();
            LOGGER.warn("[chimera] shadow map recovery failed; shadow inputs remain unavailable: {}",
                    clearFailure.toString());
        }
    }

    /**
     * Draws entities and block entities into the map after the opaque terrain and before the translucent
     * layer, as Iris's ShadowRenderer does. A failure leaves the terrain map intact and turns the casters off
     * for the session.
     */
    void renderShadowCasters(double cameraX, double cameraY, double cameraZ) {
        ChimeraShadowCasters.clearCounts();
        if (pass.pipelines.shadowEntities == null || pass.session.shadowCastersFailed
                || pass.packPlan == null || pass.packConfig == null) {
            return;
        }
        ShadowCasterDirectives directives = pass.packPlan.shadowCasters();
        if (!directives.anyEntities() && !directives.anyBlockEntities()) return;
        try {
            pass.prepareProgramImages(pass.pipelines.shadowEntities.pipeline());
            ChimeraShadowCasters.render(cameraX, cameraY, cameraZ, this.shadowMap.getLightView(),
                    this.shadowMap.getPackLightProjection(), directives, shadowCullingFrustum(true));
        } catch (RuntimeException failure) {
            pass.session.shadowCastersFailed = true;
            if (pass.conformanceReport != null) {
                pass.conformanceReport.addDeviation("SHADOW_ENTITY_DRAW_FAILED");
            }
            LOGGER.warn("[chimera] shadow casters failed; entities cast no shadow this session", failure);
        }
    }

    /**
     * Chooses the shadow pass's sections with Iris's terrain shadow frustum. The host's own shadow
     * is also bounded by the light volume; a pack's is not, as in Iris, since its shadow vertex
     * stage may scale depth to reach past the ortho's planes. The pack projection carries GL clip
     * depth, which the frustum test expects; both projections cover the same volume.
     */
    void beginShadowSections(double cameraX, double cameraY, double cameraZ, boolean pack) {
        WorldRenderer world = WorldRenderer.getInstance();
        var grid = world == null ? null : world.getSectionGrid();
        if (grid == null) return;
        Matrix4f lightVolume = pack ? null
                : new Matrix4f(this.shadowMap.getPackLightProjection()).mul(this.shadowMap.getLightView());
        ShadowSectionQueue.begin(grid, grid.getChunkAreaManager().size, lightVolume,
                shadowCullingFrustum(false), cameraX, cameraY, cameraZ);
    }

    /**
     * This frame's Iris shadow frustum, terrain or entity: built from the camera's view and the
     * shadow light, with the render distance Iris reads each frame.
     */
    ShadowCullingFrustum shadowCullingFrustum(boolean entities) {
        ShadowCullingPlan culling = pass.session.shadowCulling;
        if (culling == null) return ShadowCullingFrustum.none();
        int renderDistance = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
        int shadowChunks = ChimeraConfig.get().maxShadowRenderDistance();
        ShadowCullingPlan.Culler culler = entities ? culling.entities(renderDistance, shadowChunks)
                : culling.terrain(renderDistance, shadowChunks);
        return new ShadowCullingFrustum(culler, PackUniformProvider.currentCameraViewProjection(new Matrix4f()),
                PackUniformProvider.currentSunLightVector());
    }

    /**
     * Renders the shadow map just before the sky, as Iris does (PreSkyPasses): closes the HDR pass, draws
     * the map, then reopens the HDR pass with load ops for the sky and terrain.
     *
     * VulkanMod's section setup for this frame has run by now, and it resets the indirect draws this pass
     * records. The shadow pass draws its own sections, chosen with the shadow culling ({@link ShadowSectionQueue}).
     */
    public void renderShadowSegment(double cameraX, double cameraY, double cameraZ) {
        if (!this.shadowMap.isInitialized() || !this.shadowFrameReady || !shadowAttachmentsLive()) {
            return;
        }
        if (!pass.session.shadowSegmentObserved) {
            pass.session.shadowSegmentObserved = true;
            LOGGER.debug("[chimera] shadow segment active; advanced compute dispatch is eligible");
        }
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        VkCommandBuffer cmd = Renderer.getCommandBuffer();
        InterruptedGeometry interrupted = InterruptedGeometry.capture();
        Renderer.getInstance().endRenderPass(cmd);
        if (interrupted.terrain()) {
            // close the pack geometry scope, or the shadow draws land in the world target at the map's
            // viewport and the shadow pass's clear erases the terrain just drawn
            PackGeometryContext.close();
        }
        try {
            this.renderShadowMap(cameraX, cameraY, cameraZ);
        } finally {
            resumeAfterShadow(interrupted);
        }
    }

    /** The pack geometry scope the shadow segment interrupts, to reopen once the map is drawn. */
    private record InterruptedGeometry(boolean terrain, List<VulkanImage> colors, VulkanImage depth) {
        static InterruptedGeometry capture() {
            return new InterruptedGeometry(PackGeometryContext.active(), PackGeometryContext.colors(),
                    PackGeometryContext.depth());
        }
    }

    /** Reopens the interrupted pack terrain scope, or else the plain HDR pass. */
    private void resumeAfterShadow(InterruptedGeometry interrupted) {
        List<VulkanImage> resumeColors = interrupted.colors();
        if (interrupted.terrain() && !resumeColors.isEmpty() && interrupted.depth() != null
                && pass.targets.hdrFramebuffer != null) {
            PackGeometryContext.beginGeometry(resumeColors, interrupted.depth());
            pass.rebindMainTarget();
        } else {
            // reopen the HDR pass with load ops for the terrain layers
            pass.rebindMainTarget();
        }
    }

    /**
     * Readies the frame's shadow map as the level segment opens: computes the light once before any terrain
     * draw, for the shadow pass before the sky to reuse, arms the shadow segment, and binds the map for the
     * terrain shaders' first draw.
     */
    void beginShadowFrame(double cameraX, double cameraY, double cameraZ) {
        boolean drawn = shadowPassObserved();
        if (this.shadowMap.isInitialized()) {
            this.shadowMap.updateLight(PackUniformProvider.currentShadowLightRotation(),
                    cameraX, cameraY, cameraZ, Minecraft.getInstance().options.getEffectiveRenderDistance());
            this.shadowFrameReady = prepareShadowForSampling(cameraX, cameraY, cameraZ);
            if (this.shadowFrameReady && !drawn) {
                // no map is drawn, but Iris recomputes shadowModelView and shadowProjection every frame
                // (MatrixUniforms), so the current light's matrices go out
                this.shadowMap.publishCurrentDrawState();
            } else if (this.shadowFrameReady && this.shadowMap.hasMapSnapshot()) {
                this.shadowMap.publishMapState(cameraX, cameraY, cameraZ);
            } else {
                PackUniformProvider.clearShadowState();
            }
        }

        // arm this frame's shadow segment when something observes it
        this.shadowPending = this.shadowFrameReady && drawn;

        // terrain shaders sample the shadow map from their first draw
        if (this.shadowFrameReady) {
            clearComputeColors();
            if (shadowDistanceOff()) clearUndrawnMap();
            this.shadowMap.bindShadowTexture();
        }
    }

    /**
     * Whether the pack's shadow pass would run but the shadow distance in force is 0: Iris clears
     * the map as the frame opens and draws nothing into it (ShadowRenderer.renderShadows).
     */
    private boolean shadowDistanceOff() {
        return usePackShadowRuntime() && pass.session.shadowUse.runs() && shadowDistanceInForce() == 0;
    }

    /**
     * On a frame Max Shadow Distance 0 leaves undrawn, clears the map as Iris clears it each frame
     * (ChimeraShadowMap.clearUndrawnFrame). Otherwise receivers would read the last drawn map through the
     * moving light's matrices. A failed clear falls back as the first-use clear does
     * (prepareShadowForSampling), and the next frame clears the map afresh.
     */
    private void clearUndrawnMap() {
        if (!Renderer.isRecording()) return;
        if (Renderer.getInstance().getBoundRenderPass() != null) {
            recordShadowTransitionFallback("undrawn shadow map clear inside a render pass");
            return;
        }
        try {
            this.shadowMap.clearUndrawnFrame(Renderer.getCommandBuffer());
        } catch (RuntimeException failure) {
            // its layouts may be half moved, so initialise the map again next frame
            this.shadowMap.invalidateMapSnapshot();
            recordShadowTransitionFallback(String.valueOf(failure.getMessage()));
        }
    }

    /**
     * Clears the shadowcolor targets only computes name as the frame opens, before setup and begin run,
     * as Iris clears its shadow targets in beginLevelRendering. The shadow pass clears the targets it draws
     * when it opens.
     */
    private void clearComputeColors() {
        if (!Renderer.isRecording()) return;
        if (Renderer.getInstance().getBoundRenderPass() != null) {
            recordShadowTransitionFallback("compute shadowcolor clear inside a render pass");
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            this.shadowMap.clearComputeColors(stack, Renderer.getCommandBuffer(), false);
        }
    }

    /** Consumes the frame's shadow-pending flag; true at most once per level segment. */
    public boolean consumeShadowPending() {
        boolean p = this.shadowPending;
        this.shadowPending = false;
        return p;
    }

    /** Records a pack shadow terrain draw against the program running it, for storage-buffer ordering. */
    public void recordShadowDraw() {
        if (this.shadowPassActive && usePackShadowRuntime() && pass.packStorageBufferOwner != null) {
            pass.packStorageBufferOwner.recordDraw(this.shadowTerrainProgram, VK_SHADER_STAGE_VERTEX_BIT);
        }
    }

    /**
     * The sampler one manifest entry binds.
     *
     * A {@code sampler2DShadow} lookup is a depth comparison and needs the compare sampler; a program that
     * declares the same texture as {@code sampler2D} reads it through the image's own sampler. VulkanMod
     * writes each slot's image and sampler pair into the descriptor, so one image serves both kinds of
     * program without changing per draw.
     */
    long shadowSamplerFor(ChimeraMainPass.ProgramImages program, ProgramImageBindingManifest.Entry entry,
            VulkanImage image) {
        boolean compareRequired = entry.kind() == ProgramImageBindingManifest.Kind.SHADOW_DEPTH
                && program.shadowSamplerSymbols().contains(entry.sourceSymbol());
        long sampler = shadowSamplerFor(entry.resourceKey(), image, compareRequired);
        if (compareRequired && pass.session.loggedShadowSamplerPrograms.add(program.name())) {
            LOGGER.debug("[chimera] pack shadow sampler: program={} symbol={} slot={} image={} "
                            + "sampler={} compareOp=LESS_OR_EQUAL",
                    program.name(), entry.sourceSymbol(), entry.slot(), image.getId(), sampler);
        }
        return sampler;
    }

    /**
     * The sampler a shadow image reads through, for a program or a pass compute: a comparison one
     * for a {@code sampler2DShadow}, filtering as the pack's shadowtexNNearest says, otherwise the
     * image's own.
     */
    long shadowSamplerFor(String resource, VulkanImage image, boolean compare) {
        boolean linear = !this.shadowMap.depthNearest(resource.equals("shadowtex1") ? 1 : 0);
        return this.packShadowDepth.samplerFor(image, compare, linear);
    }

    /** The program's samplers that need a compare sampler, from the plan's interface. */
    Set<String> shadowSamplerSymbols(String program) {
        if (pass.packPlan == null) {
            return Set.of();
        }
        var plan = pass.packPlan.program(program);
        if (plan == null || plan.interfacePlan() == null) {
            return Set.of();
        }
        Set<String> symbols = new HashSet<>();
        for (var sampler : plan.interfacePlan().samplers()) {
            if (PackShadowDepth.requiresCompareSampler(sampler.glslType())) {
                symbols.add(sampler.name());
            }
        }
        return Set.copyOf(symbols);
    }

    /**
     * Whether the pack's shadow program draws the map. It needs gbuffers_terrain installed to read its depth,
     * and the pack not turned off; otherwise VulkanMod's fixed shadow path stays.
     */
    boolean usePackShadowRuntime() {
        return !pass.session.packPostChainRejected && pass.pipelines.shadowPipeline != null
                && pass.pipelines.terrainPipeline != null;
    }

    /**
     * True while the pack has a shadow map Iris would build its ShadowRenderer for. Iris then
     * suppresses vanilla entity ground shadows (IrisRenderingPipeline.shouldDisableVanillaEntityShadows:
     * shadowRenderer != null); a pass that only writes pack images keeps them (ShadowPassUse).
     */
    public boolean packShadowPassActive() {
        return usePackShadowRuntime() && pass.session.shadowUse.replacesEntityShadows();
    }

    /**
     * Whether this frame draws a shadow map: the host's fixed path always; the pack's when it is
     * observed and the shadow distance in force is not 0. At 0 Iris skips the shadow pass and its
     * shadowcomp (ShadowRenderer.renderShadows), while the shadow matrices still follow the light,
     * as on a frame no one observes; the map it cleared as the frame opened stays empty
     * (clearUndrawnMap).
     */
    private boolean shadowPassObserved() {
        return !usePackShadowRuntime() || (pass.session.shadowUse.runs() && shadowDistanceInForce() != 0);
    }

    /** The shadow distance in force, in chunks (ShadowCullingPlan.distanceInForce); the player's before a plan. */
    int shadowDistanceInForce() {
        ShadowCullingPlan culling = pass.session.shadowCulling;
        int player = ChimeraConfig.get().maxShadowRenderDistance();
        return culling == null ? player : culling.distanceInForce(player);
    }

    /** Makes newly created shadow images valid shader inputs exactly once. */
    boolean prepareShadowForSampling(double cameraX, double cameraY, double cameraZ) {
        if (!this.shadowMap.needsInitialSamplingLayout()) {
            return true;
        }
        if (!Renderer.isRecording() || Renderer.getInstance().getBoundRenderPass() != null) {
            recordShadowTransitionFallback("unsafe command state");
            return false;
        }
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        if (commandBuffer == null || !shadowAttachmentsLive()) {
            recordShadowTransitionFallback("missing command buffer or attachment");
            return false;
        }
        try {
            this.shadowMap.initializeSampling(commandBuffer);
            if (!this.shadowMap.hasMapSnapshot()) {
                this.shadowMap.seedClearedMap(cameraX, cameraY, cameraZ);
            }
            return true;
        } catch (RuntimeException failure) {
            recordShadowTransitionFallback(String.valueOf(failure.getMessage()));
            return false;
        }
    }

    boolean shadowAttachmentsLive() {
        if (!this.shadowMap.isInitialized() || this.shadowMap.getShadowFramebuffer() == null) {
            return false;
        }
        VulkanImage color = this.shadowMap.getShadowFramebuffer().getColorAttachment();
        VulkanImage depth = this.shadowMap.getShadowFramebuffer().getDepthAttachment();
        return color != null && depth != null && color.getId() != 0L && depth.getId() != 0L
                && this.shadowMap.shadowColors().stream().allMatch(target -> target != null && target.getId() != 0L);
    }

    void recordShadowTransitionFallback(String detail) {
        if (pass.conformanceReport != null) {
            pass.conformanceReport.addDeviation("SHADOW_RESOURCE_TRANSITION_SKIPPED");
        }
        if (!pass.session.shadowTransitionFallbackLogged) {
            pass.session.shadowTransitionFallbackLogged = true;
            LOGGER.warn("[chimera] shadow sampling fallback=IDENTITY (transition skipped: {})", detail);
        }
    }

    void recordShadowCutoutDisposition(boolean merged) {
        if (pass.session.shadowCutoutDispositionLogged || !usePackShadowRuntime()) {
            return;
        }
        pass.session.shadowCutoutDispositionLogged = true;
        if (merged && pass.conformanceReport != null) {
            pass.conformanceReport.addDeviation("SHADOW_CUTOUT_MERGED");
        }
        LOGGER.debug("[chimera] pack shadow cutout path: {}",
                merged ? "merged by uniqueOpaqueLayer" : "separate");
    }
}
