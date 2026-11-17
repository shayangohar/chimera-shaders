package net.chimera.render;

import net.chimera.render.shader.ChimeraTerrainPipelines;
import net.chimera.render.shader.PackGeometryContext;
import net.chimera.shaderpack.PackPipelines;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/** The terrain layers' pack windows. */
final class PackSceneCoverage {
    private static final Logger LOGGER = LoggerFactory.getLogger("chimera");
    private final ChimeraMainPass pass;

    PackSceneCoverage(ChimeraMainPass pass) {
        this.pass = pass;
    }

    /**
     * Opens a terrain layer's pack window. Terrain writing only colortex0 stays on the live HDR render pass,
     * which the post graph reads as colortex0; terrain with more outputs gets a geometry window over them.
     */
    public void beginPackCoverageWindow(TerrainRenderType renderType) {
        if (suspendTranslucentWithoutInputs(renderType)) {
            return;
        }
        pass.windows.beginPackResourceWindow(renderType);
        PackPipelines.PackTerrain terrain = switch (renderType) {
            case SOLID, CUTOUT_MIPPED, CUTOUT -> pass.pipelines.terrainRoute;
            case TRANSLUCENT -> pass.pipelines.waterRoute;
            default -> null;
        };
        if (terrain == null || !terrain.requiresDynamicAttachments()
                || !pass.session.packTargetResourcesReady || pass.shadowPass.shadowPassActive
                || pass.targets.hdrFramebuffer == null || pass.targets.hdrFramebuffer.getDepthAttachment() == null) {
            return;
        }
        if (PackGeometryContext.active()) {
            return;
        }
        try {
            List<Integer> outputs = terrain.outputPlan().targetSlots();
            VulkanImage hdrColor = pass.targets.hdrFramebuffer.getColorAttachment();
            Renderer.getInstance().endRenderPass();
            pass.packPostTargets.beginGeometry(Renderer.getCommandBuffer(), hdrColor, outputs,
                    terrain.outputPlan().snapshotTargets());
            PackGeometryContext.beginGeometry(
                    pass.packPostTargets.geometryAttachments(outputs),
                    pass.targets.hdrFramebuffer.getDepthAttachment());
        } catch (RuntimeException failure) {
            pass.packPostTargets.abortGeometry(pass.targets.hdrFramebuffer.getColorAttachment());
            pass.currentFramebuffer = pass.targets.hdrFramebuffer;
            pass.rebindMainTarget();
            pass.logPackRuntimeFailure("geometry-begin:" + renderType, failure);
        }
    }

    /**
     * Hands the translucent layer to the fixed pipeline when the water program's inputs are not
     * live this frame. True when it did, so the layer opens no pack window.
     */
    private boolean suspendTranslucentWithoutInputs(TerrainRenderType renderType) {
        if (renderType != TerrainRenderType.TRANSLUCENT || pass.shadowPass.shadowPassActive
                || pass.pipelines.waterRoute == null
                || pass.images.packTranslucentInputsReady(pass.pipelines.waterRoute)) {
            return false;
        }
        // the fixed pipeline draws this layer, so the pack program never binds a stale or missing image
        ChimeraTerrainPipelines.suspendTranslucentOverride(true);
        pass.packTranslucentSuspended = true;
        if (!pass.session.packTranslucentSuspendLogged) {
            pass.session.packTranslucentSuspendLogged = true;
            LOGGER.warn("[chimera] gbuffers_water inputs unavailable; fixed translucent pipeline this frame");
        }
        return true;
    }

    /** Closes a terrain layer's pack window, committing a geometry window's targets. */
    public void endPackCoverageWindow(TerrainRenderType renderType) {
        if (pass.shadowPass.shadowPassActive) return;
        pass.depth.markWorldDrawn();
        if (pass.packTranslucentSuspended && renderType == TerrainRenderType.TRANSLUCENT) {
            pass.packTranslucentSuspended = false;
            ChimeraTerrainPipelines.suspendTranslucentOverride(false);
            return;
        }
        if (PackGeometryContext.active()) {
            PackPipelines.PackTerrain terrain = renderType == TerrainRenderType.TRANSLUCENT
                    ? pass.pipelines.waterRoute : pass.pipelines.terrainRoute;
            if (terrain == null || terrain.outputPlan() == null) {
                PackGeometryContext.close();
                pass.windows.restorePackResourceWindow();
                return;
            }
            try {
                Renderer.getInstance().endRenderPass();
                PackGeometryContext.close();
                pass.packPostTargets.commitGeometry(terrain.outputPlan().targetSlots(),
                        pass.targets.hdrFramebuffer.getColorAttachment());
                pass.currentFramebuffer = pass.targets.hdrFramebuffer;
                pass.rebindMainTarget();
            } catch (RuntimeException failure) {
                pass.packPostTargets.abortGeometry(pass.targets.hdrFramebuffer.getColorAttachment());
                pass.logPackRuntimeFailure("geometry-commit", failure);
                if (PackGeometryContext.active()) PackGeometryContext.close();
                pass.rebindMainTarget();
            } finally {
                pass.windows.restorePackResourceWindow();
            }
            return;
        }
        pass.windows.restorePackResourceWindow();
    }
}
