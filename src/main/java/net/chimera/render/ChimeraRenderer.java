package net.chimera.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.chimera.ChimeraMod;
import net.chimera.config.ChimeraConfig;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.ChimeraSkyBridge;
import net.chimera.render.shader.ChimeraTerrainPipelines;
import net.chimera.render.shader.TerrainVertexUniforms;
import net.chimera.shaderpack.CullingDirectives;
import net.chimera.shaderpack.ParticleDirectives;
import net.chimera.shaderpack.ScreenOverlayDirectives;
import net.chimera.shaderpack.ShadowCullingPlan;
import net.chimera.shaderpack.SkyDirectives;
import net.chimera.shaderpack.StagedOptions;
import net.chimera.shaderpack.WeatherDirectives;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.pass.MainPass;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * Swaps Chimera's main pass in for VulkanMod's and hands the frame back when Chimera cannot run.
 *
 * {@link #onHostRendererReady} keeps VulkanMod's main pass as the host pass; Chimera's pass is installed once a
 * pack is loaded. Chimera keeps rendering behind screens over a live level. A screen without a level, or a level
 * unloading under an open screen (respawn, portals, loading screens), hands the frame to the host until a live
 * level returns (WorldRendererLevelMixin).
 */
public final class ChimeraRenderer {

    private static boolean ready;
    private static boolean installed;
    private static boolean screenMode;
    private static boolean screenOpen;
    /** Whether Chimera reinstalls at the next frame boundary, after a pack change or a deferred install. */
    private static boolean resumeAfterVariant;
    /**
     * False after a failed pack rebuild leaves the host renderer in charge, so the pack is not retried every
     * frame. The next pack request sets it again.
     */
    private static boolean chimeraEnabled = true;
    private static boolean noPackLogged;
    private static MainPass hostPass;
    private static ChimeraMainPass chimeraPass;

    public enum PackRequestResult {
        QUEUED,
        ALREADY_ACTIVE,
        NOT_READY
    }

    private ChimeraRenderer() {}

    /**
     * Reads a {@code -D} debug switch: unset is off; a bare {@code -Dname} or any value but {@code false}
     * (any case) is on.
     *
     * Boolean.getBoolean would read a bare flag as off, since it wants the literal {@code true}.
     */
    public static boolean debugFlag(String name) {
        String value = System.getProperty(name);
        return value != null && !"false".equalsIgnoreCase(value);
    }

    public static void onHostRendererReady() {
        Renderer renderer = Renderer.getInstance();
        if (renderer == null || ready) {
            return;
        }

        MainPass current = renderer.getMainPass();
        if (current == null) {
            ChimeraMod.LOGGER.warn("[chimera] VulkanMod has no main pass; Chimera stays idle");
            return;
        }
        if (!ChimeraDevice.dynamicRendering()) {
            ChimeraMod.LOGGER.debug("[chimera] no dynamic rendering; Chimera stays idle");
            return;
        }

        hostPass = current;
        // before the main pass and the terrain pipelines build the terrain and shadow configs
        TerrainVertexUniforms.register();
        chimeraPass = new ChimeraMainPass();
        renderer.addOnResizeCallback(ChimeraRenderer::refreshIdleHostPass);
        ready = true;

        ChimeraTerrainPipelines.init();

        ChimeraMod.LOGGER.debug("[chimera] host main pass: {}", hostPass.getClass().getName());
        chimeraEnabled = true;
        install();
    }

    /**
     * Resizes the host pass while Chimera's is current, since VulkanMod resizes only the current main pass.
     *
     * A host pass left at the old size keeps a stale depth texture. After {@code /chimera pack off}, vanilla's
     * hand depth clear then skips that image and the hand clips into the world.
     */
    private static void refreshIdleHostPass() {
        if (hostPass != null && Renderer.getInstance().getMainPass() != hostPass) {
            hostPass.onResize();
        }
    }

    /**
     * Cleans Chimera's pass at VulkanMod's final teardown unless it is the installed pass, which VulkanMod
     * cleans itself.
     *
     * Renderer.cleanUpResources, called from Vulkan.cleanUp once the device is idle, cleans only the installed
     * main pass, and Chimera hands the frame to the host when the level closes. Left alone, Chimera's Vulkan
     * objects would outlive the device and a zip pack's temporary folder would wait for the next start's sweep.
     *
     * Any failure, an Error included, is logged: one escaping would skip the rest of VulkanMod's teardown.
     * Chimera is not ready afterwards, so nothing installs or drives the torn down pass.
     */
    public static void beforeHostCleanup() {
        ChimeraMainPass pass = chimeraPass;
        boolean installedPass = pass != null && Renderer.getInstance().getMainPass() == pass;
        ready = false;
        if (pass == null || installedPass) {
            return;
        }
        try {
            pass.cleanUp();
        } catch (Throwable failure) {
            ChimeraMod.LOGGER.error("[chimera] final cleanup of the idle Chimera pass failed", failure);
        }
    }

    public static boolean isReady() {
        return ready;
    }

    public static boolean isInstalled() {
        return installed;
    }

    public static PackRequestResult requestPack(Path path) {
        if (!ready || chimeraPass == null) {
            return PackRequestResult.NOT_READY;
        }
        if (!chimeraPass.queuePackChange(path, false)) {
            return PackRequestResult.ALREADY_ACTIVE;
        }
        chimeraEnabled = true;
        return PackRequestResult.QUEUED;
    }

    public static PackRequestResult reloadPack() {
        if (!ready || chimeraPass == null) {
            return PackRequestResult.NOT_READY;
        }
        if (!chimeraPass.queuePackReload()) {
            return PackRequestResult.ALREADY_ACTIVE;
        }
        chimeraEnabled = true;
        return PackRequestResult.QUEUED;
    }

    public static PackRequestResult disablePack() {
        return requestPack(null);
    }

    /** The pack rendering now, or the queued one when a change is pending; null for vanilla. */
    public static Path selectedPackPath() {
        return ready && chimeraPass != null ? chimeraPass.selectedPackPath() : null;
    }

    /** A loaded pack and the options it was loaded with; each load gives a new instance. */
    public record LoadedPack(Path path, StagedOptions options) {}

    /** The pack loaded now, whatever is queued; null when none is. */
    public static LoadedPack loadedPack() {
        return ready && chimeraPass != null ? chimeraPass.loadedPack() : null;
    }

    public static String packStatus() {
        if (!ready || chimeraPass == null) {
            return "Chimera pack: renderer not ready";
        }
        String status = chimeraPass.packStatus() + ", renderer=" + (installed ? "chimera" : "vanilla");
        List<String> failures = PackFailureNotices.statusLines();
        return failures.isEmpty() ? status : status + "\nFailures this load:\n" + String.join("\n", failures);
    }

    /**
     * F3's lines below the system information, as Iris's MixinDebugScreenOverlay: the version, the pack with
     * whether it fell back or lost programs (PackFailureNotices.label), its profile and the colour space.
     */
    public static List<String> debugLines() {
        List<String> lines = new ArrayList<>(List.of("",
                "[Chimera] Version: " + ChimeraMod.version() + " (" + ChimeraMod.commit() + ")", ""));
        Path pack = selectedPackPath();
        if (pack == null) {
            lines.add("[Chimera] Shaders are disabled");
            return lines;
        }
        String name = PackSessionLifecycle.packLabel(pack);
        lines.add("[Chimera] Shaderpack: " + name + PackFailureNotices.label(name));
        LoadedPack loaded = loadedPack();
        if (loaded != null && loaded.path().equals(pack)) lines.add("[Chimera] " + loaded.options().profileInfo());
        lines.add("[Chimera] Color space: " + ChimeraConfig.get().colorSpace().name());
        return lines;
    }

    /**
     * F3's shadow line below the game information, Iris's shadow lines in one (IrisRenderingPipeline and
     * ShadowRenderer.addDebugText): the map's size and distance, the sections kept of those that cast (C), and
     * the entities (E) and block entities (BE) drawn.
     *
     * Says instead that the pack uses no shadow map or that the shadow distance in force is 0; empty while the
     * world is not pack-rendered.
     */
    public static List<String> shadowDebugLines() {
        if (!packProjectionActive()) return List.of();
        ChimeraShadowMap shadowMap = chimeraPass.shadowPass.shadowMap;
        if (!chimeraPass.session.shadowUse.runs() || !shadowMap.isInitialized()) {
            return List.of("", "[Chimera] Shadows: not used by pack");
        }
        if (chimeraPass.shadowPass.shadowDistanceInForce() == 0) {
            return List.of("", "[Chimera] Shadows: off, shadow distance 0");
        }
        return List.of("", "[Chimera] Shadows: " + shadowMap.debugInfo() + ", C: "
                + ShadowSectionQueue.sectionCounts() + ", " + ChimeraShadowCasters.counts());
    }

    /**
     * The shadow distance the loaded pack forces on the player's Max Shadow Distance, for the
     * settings page; empty with no pack or none forced, as Iris answers with no pipeline.
     */
    public static OptionalInt forcedShadowDistance() {
        ShadowCullingPlan culling = chimeraPass != null && chimeraPass.packLoaded()
                ? chimeraPass.session.shadowCulling : null;
        return culling == null ? OptionalInt.empty() : culling.forcedDistanceChunks();
    }

    /** Applies pending pack work before VulkanMod begins a new main command buffer. */
    public static void beforeMainCommandBuffer() {
        if (!ready || chimeraPass == null) {
            return;
        }
        // VulkanMod copies RenderSystem's texture matrix into VRenderSystem.TextureMatrix only when it is
        // set or reset on the render thread, and allocates that copy uninitialised
        // pack entity and hand stages read it as gl_TextureMatrix[0], which Iris serves from
        // RenderSystem.getTextureMatrix(); a zero matrix samples a hand's skin black until the first glint draw
        VRenderSystem.setTextureMatrix(RenderSystem.getTextureMatrix());
        // material maps build with or without a pack, since atlas uploads arrive either way
        chimeraPass.pumpMaterialMapBuilds();
        chimeraPass.targets.refreshWorldResourcesIfDue();
        ChimeraTerrainPipelines.rebuildingOnce(ChimeraRenderer::changePackAtFrameBoundary);
        // after any pack change above, so one pack's teardown and the next one's install upload the atlas once
        PackAtlasMipmaps.reconcile();
    }

    /** Hands a pending pack change or dimension variant to the host, applies it, and resumes Chimera. */
    private static void changePackAtFrameBoundary() {
        if (chimeraPass.hasPendingPackChange() && installed) {
            Renderer.getInstance().setMainPass(hostPass);
            ChimeraTerrainPipelines.disable();
            ChimeraEntityBridge.setEnabled(false);
            ChimeraSkyBridge.setEnabled(false);
            installed = false;
            resumeAfterVariant = chimeraEnabled;
            ChimeraMod.LOGGER.debug("[chimera] host renders during the pack change");
        }
        if (!chimeraPass.applyPendingPackVariantAtFrameBoundary()) {
            return;
        }
        boolean liveLevel = Minecraft.getInstance().level != null;
        // a first pack load installs too, since with no pack Chimera never took the frame
        boolean packArrived = !screenMode && chimeraPass.packLoaded();
        if (chimeraEnabled && (resumeAfterVariant || packArrived || (screenMode && liveLevel)) && !installed) {
            if (install()) {
                resumeAfterVariant = false;
                screenMode = false;
                ChimeraMod.LOGGER.debug("[chimera] resumed");
            }
        }
    }


    /** Chimera's pass owns the frame, so its world segments may run. */
    public static boolean segmentsActive() {
        return installed && !screenMode;
    }

    /**
     * This frame's world renders for a loaded pack, whose gbufferProjection keeps vanilla's finite far plane
     * (ChimeraProjectionFarMixin).
     */
    public static boolean packProjectionActive() {
        return segmentsActive() && chimeraPass != null && chimeraPass.packLoaded();
    }

    /** True while Chimera's shadow pass records (ChimeraShadowProjectionMixin). */
    public static boolean shadowPassActive() {
        return chimeraPass != null && chimeraPass.shadowPass.shadowPassActive;
    }

    /**
     * The screen overlay switches of the pack rendering this frame's world, or null when the
     * world is not pack-rendered and Minecraft's overlays draw as usual (Iris checks its active
     * pipeline the same way in MixinGui and MixinScreenEffectRenderer).
     */
    public static ScreenOverlayDirectives packScreenOverlays() {
        return packProjectionActive() ? chimeraPass.screenOverlays() : null;
    }

    /**
     * The sky switches of the pack rendering this frame's world, or null when the world is not
     * pack-rendered and Minecraft's whole sky draws (Iris MixinLevelRenderer_SunMoonToggle).
     */
    public static SkyDirectives packSkyDirectives() {
        return packProjectionActive() ? chimeraPass.skyDirectives() : null;
    }

    /**
     * The weather switch of the pack rendering this frame's world, or null when the world is not
     * pack-rendered and rain and snow draw as vanilla's (Iris MixinLevelRenderer checks its active
     * pipeline the same way).
     */
    public static WeatherDirectives packWeatherDirectives() {
        return packProjectionActive() ? chimeraPass.weatherDirectives() : null;
    }

    /**
     * Where the pack rendering this frame's world draws particles against its deferred passes, or
     * null when the world is not pack-rendered and NeoForge's split stands (Iris answers MIXED then).
     */
    public static ParticleDirectives.Ordering packParticleOrdering() {
        return packProjectionActive() ? chimeraPass.packPlan.particleDirectives().ordering() : null;
    }

    /**
     * Whether the pack rendering this frame's world turns the camera's frustum culling off (Iris
     * frustum.culling=false); false when the world is not pack-rendered, as Iris's vanilla pipeline
     * answers.
     */
    public static boolean packDisablesFrustumCulling() {
        CullingDirectives culling = packProjectionActive() ? chimeraPass.cullingDirectives() : null;
        return culling != null && !culling.frustumCulling();
    }

    /** The installed pack's separateEntityDraws, as Iris's batched entity rendering reads it; false with no pack. */
    public static boolean packSeparatesEntityDraws() {
        return packProjectionActive() && chimeraPass.packPlan.particleDirectives().separateEntityDraws();
    }

    /**
     * Whether Chimera's level segment may open, starting a new frame first when none is recording.
     *
     * A nested Minecraft.runTick (the respawn loading screen) makes VulkanMod submit the outer frame before it
     * renders its level, and Chimera records raw commands that need a recording command buffer. The new frame
     * boundary may hand the frame to the host, so ownership is checked again.
     */
    public static boolean beginLevelSegments() {
        if (!segmentsActive()) {
            return false;
        }
        if (!Renderer.isRecording()) {
            ChimeraMod.LOGGER.debug("[chimera] level segment: resuming a frame a nested tick submitted");
            Renderer.getInstance().beginFrame();
        }
        return segmentsActive();
    }

    public static ChimeraMainPass getMainPass() {
        return chimeraPass;
    }

    private static boolean install() {
        if (!ready || installed) {
            return installed;
        }
        if (!chimeraPass.packLoaded()) {
            // no pack: the frame stays vanilla, as in Iris
            if (!noPackLogged) {
                noPackLogged = true;
                ChimeraMod.LOGGER.info("[chimera] Shaders are disabled");
            }
            resumeAfterVariant = false;
            return false;
        }

        if (!chimeraPass.prepareForInstall()) {
            // requested while the command buffer still records; beforeMainCommandBuffer retries at the next
            // frame boundary
            resumeAfterVariant = true;
            ChimeraMod.LOGGER.debug("[chimera] install deferred to the next frame");
            return false;
        }
        Renderer.getInstance().setMainPass(chimeraPass);
        ChimeraTerrainPipelines.enable();
        ChimeraEntityBridge.setEnabled(!chimeraPass.packRuntimeRejected() && ChimeraEntityBridge.isInstalled());
        ChimeraSkyBridge.setEnabled(!chimeraPass.packRuntimeRejected() && ChimeraSkyBridge.isInstalled());
        installed = true;
        noPackLogged = false;
        ChimeraMod.LOGGER.debug("[chimera] installed");
        return true;
    }

    public static void setScreenOpen(boolean open) {
        screenOpen = open;
        if (chimeraPass != null) {
            chimeraPass.scheduleScreenResourceReset();
        }
    }

    /** Hands the frame to the host until a live level returns, for a screen without a level or a level unload. */
    private static void legacyUnhand() {
        Renderer.getInstance().endRenderPass();
        Renderer.getInstance().setMainPass(hostPass);
        ChimeraTerrainPipelines.suspendForScreens();
        ChimeraEntityBridge.setEnabled(false);
        ChimeraSkyBridge.setEnabled(false);
        // reset the global state VulkanMod reads at pipeline bind to host defaults before the host records
        VRenderSystem.enableDepthTest();
        VRenderSystem.depthMask(true);
        VRenderSystem.enableCull();
        VRenderSystem.enableBlend();
        VRenderSystem.colorMask(true, true, true, true);
        // install() returns early while installed is set, and Chimera's pass would never come back
        installed = false;
        resumeAfterVariant = false;
        screenMode = true;
    }

    /** Leaves the host renderer active after a failed Chimera variant rebuild. */
    static void fallbackToHostRenderer() {
        if (installed) {
            Renderer.getInstance().endRenderPass();
            Renderer.getInstance().setMainPass(hostPass);
        }
        ChimeraTerrainPipelines.disable();
        ChimeraEntityBridge.setEnabled(false);
        ChimeraSkyBridge.setEnabled(false);
        VRenderSystem.enableDepthTest();
        VRenderSystem.depthMask(true);
        VRenderSystem.enableCull();
        VRenderSystem.enableBlend();
        VRenderSystem.colorMask(true, true, true, true);
        installed = false;
        screenMode = false;
        resumeAfterVariant = false;
        chimeraEnabled = false;
        ChimeraMod.LOGGER.warn("[chimera] pack variant failed; rendering vanilla");
    }

    public static void enterScreenMode() {
        if (Minecraft.getInstance().level != null) {
            // over a live level Chimera keeps rendering and the menu blur composes with its frame
            // PostPassM ends any open pass before its barriers; draws to the main target rebind Chimera's aux pass
            ChimeraMod.LOGGER.debug("[chimera] screen open; rendering continues");
            return;
        }

        // no live level (loading and transition screens): the level teardown and swap around these frames
        // breaks Chimera's image transitions, so the host renders
        if (!installed) {
            return;
        }
        legacyUnhand();
        ChimeraMod.LOGGER.debug("[chimera] screen without a level; host renders until it closes");
    }

    public static void exitScreenMode() {
        if (!screenMode) {
            return;
        }
        ChimeraMod.LOGGER.debug("[chimera] screen closed; resuming at the next frame boundary");
    }

    /** A level unloading under an open screen hands the frame to the host until a live level returns. */
    public static void onLevelUnloaded() {
        ShadowSectionQueue.release();
        if (!screenOpen || screenMode || !installed) {
            return;
        }

        legacyUnhand();
        ChimeraMod.LOGGER.debug("[chimera] level unloaded; host renders");
    }

    public static void onLevelLoaded() {
        onLevelLoaded(Minecraft.getInstance().level);
    }

    /**
     * Selects the pack's dimension variant for the new level. If Chimera handed the frame to the host, it
     * reinstalls at the next frame boundary.
     */
    public static void onLevelLoaded(ClientLevel level) {
        ShadowSectionQueue.release();
        if (chimeraPass != null && level != null) {
            chimeraPass.onLevelChanged(level.dimension().location().toString());
        }
        if (!screenOpen || !screenMode) {
            return;
        }

        ChimeraMod.LOGGER.debug("[chimera] level loaded; resuming at the next frame boundary");
    }
}
