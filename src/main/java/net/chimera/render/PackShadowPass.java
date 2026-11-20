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
 * The main pass's shadow pass: records the pack's shadow map, or the host's fixed one, before the terrain
 * samples it, as Iris renders shadows first.
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

    /** The pack shadow program the open terrain draws run. */
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
     * Records the shadow pass and binds the map for the receivers. Runs with no render pass open;
     * renderSectionLayer's rebind opens the shadow pass, and this closes it.
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

    /** One shadow map recording. */
    private record ShadowFrame(VkCommandBuffer cmd, VulkanImage color, VulkanImage depth, boolean pack,
            Matrix4f projection, double cameraX, double cameraY, double cameraZ) {}

    /** Binds a pack shadow terrain program's own images; each draw binds its host textures itself. */
    private ProgramImageBindingTransaction<ChimeraTextureBindingState.Snapshot> bindPackShadowImages(
            GraphicsPipeline pipeline) {
        pass.prepareProgramImages(pipeline);
        return pass.images.bindPassImages(pipeline);
    }

    /**
     * Draws the translucent layer into the shadow map unless shadowTranslucent is off, with no fixed alpha
     * test, as Iris's SHADOW_TRANS. shadowtex1 is captured first, so this layer reaches shadowtex0 alone.
     * Returns the bindings open afterwards.
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

    /** Redirects terrain into the shadow pass, with the light's matrices and its sections. */
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
        // Iris tests cutout shadows at alpha 0.1 (SodiumPrograms.getAlphaTest); solid blocks share VulkanMod's
        // cutout_mipped layer and pass it, and without it foliage would cast whole squares
        if (packShadow) {
            PackUniformProvider.updateDrawAlphaReference(SHADOW_CUTOUT_ALPHA);
        }
    }

    /**
     * The program a shadow terrain draw of {@code type} runs, as Iris's SodiumPrograms pick it: cutout or
     * water where the pack has them, else shadow.
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
     * Closes the shadow recording. The sampled copies close first, so shadowcomp and the receivers use the
     * targets themselves, as in Iris; a copy would lose state a shadowcomp steps each frame.
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

    /** Makes the recorded map sampleable, runs shadowcomp over it and publishes it for the receivers. */
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

    /** Runs the shadowcomp passes over the new map; computes dispatch at the main target's size. */
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
     * Draws entity casters after the opaque terrain and before the translucent layer, as Iris does; a failure
     * turns them off for the session.
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
     * Chooses the shadow sections with Iris's terrain shadow frustum. Only the host's shadow is bounded by the
     * light volume, since a pack's shadow vertex stage may scale depth past the ortho's planes.
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

    /** This frame's Iris shadow frustum, terrain or entity, with the render distance Iris reads each frame. */
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
     * Renders the shadow map just before the sky, as Iris does, then reopens the HDR pass. VulkanMod's section
     * setup, which resets the indirect draws, has run by now, so the shadow pass draws its own sections.
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
            // close the pack geometry scope, or the shadow draws and clear land in the world target
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

    /** Readies the frame's shadow map as the level segment opens, computing the light once for the frame. */
    void beginShadowFrame(double cameraX, double cameraY, double cameraZ) {
        boolean drawn = shadowPassObserved();
        if (this.shadowMap.isInitialized()) {
            this.shadowMap.updateLight(PackUniformProvider.currentShadowLightRotation(),
                    cameraX, cameraY, cameraZ, Minecraft.getInstance().options.getEffectiveRenderDistance());
            this.shadowFrameReady = prepareShadowForSampling(cameraX, cameraY, cameraZ);
            if (this.shadowFrameReady && !drawn) {
                // no map is drawn, but Iris recomputes the shadow matrices every frame (MatrixUniforms)
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
     * Clears a map Max Shadow Distance 0 leaves undrawn, as Iris does, or receivers would read the last map
     * through the moving light's matrices.
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

    /** Clears the shadowcolor targets only computes name as the frame opens, as Iris does in beginLevelRendering. */
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

    /** The sampler a manifest entry binds: the compare sampler for a {@code sampler2DShadow}, else the image's. */
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

    /** The sampler a shadow image reads through, filtering as the pack's shadowtexNNearest says. */
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

    /** Whether the pack's shadow program draws the map; it needs gbuffers_terrain and the pack not turned off. */
    boolean usePackShadowRuntime() {
        return !pass.session.packPostChainRejected && pass.pipelines.shadowPipeline != null
                && pass.pipelines.terrainPipeline != null;
    }

    /**
     * True while the pack has a shadow map Iris would build its ShadowRenderer for, which suppresses vanilla
     * entity shadows (IrisRenderingPipeline.shouldDisableVanillaEntityShadows).
     */
    public boolean packShadowPassActive() {
        return usePackShadowRuntime() && pass.session.shadowUse.replacesEntityShadows();
    }

    /**
     * Whether this frame draws a shadow map: always on the host path; the pack's when observed and the
     * distance in force is not 0, where Iris skips the pass and its shadowcomp.
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
