// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render.shader;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.ChimeraMod;
import net.chimera.render.vertex.ChimeraExtTerrainBuilder;
import net.chimera.render.vertex.ChimeraVertexFormats;
import net.chimera.shaderpack.PackMaterialResolver;
import net.chimera.shaderpack.TerrainMaterialPlan;
import net.chimera.shaderpack.TerrainShadingDirectives;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.block.state.BlockState;
import net.vulkanmod.render.chunk.build.thread.ThreadBuilderPack;
import net.vulkanmod.render.PipelineManager;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Builds and installs Chimera's terrain pipelines. Enabling or disabling rebuilds the live level only when the
 * terrain format changes.
 */
public final class ChimeraTerrainPipelines {
    private static boolean initialized;
    /** Chunk builders read it. */
    private static volatile boolean extendedMode;
    /** Indexed by vertex layout (bit 1: modern) and separateAo (bit 0). */
    private static final GraphicsPipeline[] fixedPipelines = new GraphicsPipeline[4];
    /** Read by the chunk-build threads; replaced on the render thread. */
    private static volatile PackMaterialResolver materialResolver = PackMaterialResolver.empty();
    private static TerrainMaterialPlan materialPlan = TerrainMaterialPlan.legacy();
    /** The pack's gbuffers_terrain pipeline, for every layer but translucent. */
    private static GraphicsPipeline geometryOverride;
    /** The pack's water pipeline, for the translucent layer only. */
    private static GraphicsPipeline translucentOverride;
    /** True for one translucent draw whose pack inputs are not live this frame. */
    private static boolean translucentOverrideSuspended;
    /** Read by chunk builders, so it is replaced as one value. */
    private static volatile TerrainShadingDirectives shading = TerrainShadingDirectives.VANILLA;
    /** Rebuild reasons held by rebuildingOnce; null outside one. Render thread only. */
    private static Set<String> heldRebuilds;

    private ChimeraTerrainPipelines() {}

    /** Shade brightness under the pack's ambientOcclusionLevel (Iris's getShadeBrightness). */
    public static float shadeBrightness(float original) {
        return shading.shadeBrightness(original);
    }

    /** True when the pack sets oldLighting off, so ClientLevel.getShade answers unshaded for meshing. */
    public static boolean directionalShadingDisabled() {
        return shading.disablesDirectionalShading();
    }

    /**
     * True while meshes carry brightness in vertex alpha (Iris's separateAo). Never for host-format meshes, since
     * VulkanMod's terrain shader reads alpha as opacity.
     */
    public static boolean separateAoActive() {
        return shading.separateAo() && extendedMode;
    }

    /**
     * True while Chimera meshes terrain for a pack; sections then compile as NeoForge's SectionCompiler does
     * where VulkanMod departs (ChimeraEmitterOcclusionMixin, ChimeraGlobalBlockEntityMixin).
     */
    public static boolean vanillaSectionCompile() {
        return extendedMode;
    }

    /** Iris's voxelizeLightBlocks. */
    public static boolean voxelizeLightBlocks() {
        return shading.voxelizeLightBlocks();
    }

    /**
     * Meshes bake these in, so a change rebuilds the live level, as Iris reloads chunks when a
     * WorldRenderingSettings value changes.
     */
    public static void setTerrainShading(TerrainShadingDirectives directives) {
        TerrainShadingDirectives next = directives == null ? TerrainShadingDirectives.VANILLA : directives;
        TerrainShadingDirectives current = shading;
        if (current.disablesDirectionalShading() == next.disablesDirectionalShading()
                && current.separateAo() == next.separateAo()
                && current.ambientOcclusionLevel() == next.ambientOcclusionLevel()
                && current.voxelizeLightBlocks() == next.voxelizeLightBlocks()) {
            return;
        }
        shading = next;
        ChimeraMod.LOGGER.debug("[chimera] terrain shading: directional={}, ao={} at {}, lightVoxels={}",
                next.disablesDirectionalShading() ? "off" : "vanilla",
                next.separateAo() ? "alpha" : "rgb", next.ambientOcclusionLevel(),
                next.voxelizeLightBlocks());
        if (initialized && extendedMode) {
            // build the matching fixed pipeline now, before the first draw needs it
            fixedPipeline();
        }
        rebuildLiveLevel("terrain shading changed");
    }

    /** The override lives behind getTerrainPipeline, so it survives the shadow pass's getter swap. */
    public static void setGeometryOverride(GraphicsPipeline pipeline) {
        // the no-pack load clears it on every window resize
        if (pipeline != geometryOverride) {
            ChimeraMod.LOGGER.debug("[chimera] terrain override: {}", pipeline != null ? "installed" : "cleared");
        }
        geometryOverride = pipeline;
        if (!initialized) {
            return;
        }
        // in host mode the host getter stays
        if (extendedMode) {
            PipelineManager.setShaderGetter(ChimeraTerrainPipelines::getTerrainPipeline);
        }
    }

    /**
     * Final teardown; PipelineManager.destroyPipelines destroys only VulkanMod's own. The overrides go with the
     * pack's pipelines.
     */
    public static void cleanUp() {
        for (int variant = 0; variant < fixedPipelines.length; variant++) {
            if (fixedPipelines[variant] != null) fixedPipelines[variant].cleanUp();
            fixedPipelines[variant] = null;
        }
        initialized = false;
    }

    public static void init() {
        if (initialized) {
            return;
        }

        fixedPipelines[0] = buildPipeline("chimera_terrain", ChimeraVertexFormats.EXTENDED_COMPRESSED_TERRAIN,
                "chimera_terrain", false);
        initialized = true;

        ChimeraMod.LOGGER.debug("[chimera] terrain pipeline ready: stride={}B attributes={}",
                ChimeraVertexFormats.EXTENDED_COMPRESSED_TERRAIN.getVertexSize(),
                ChimeraVertexFormats.EXTENDED_COMPRESSED_TERRAIN.getElementAttributeNames());
    }

    public static void enable() {
        if (!initialized) {
            return;
        }

        setTerrainMode(true);
    }

    public static void disable() {
        setTerrainMode(false);
    }

    public static GraphicsPipeline getTerrainPipeline() {
        return geometryOverride != null ? geometryOverride : fixedPipeline();
    }

    /** Translucent takes only the water override. */
    public static GraphicsPipeline getTerrainPipeline(TerrainRenderType renderType) {
        if (renderType == TerrainRenderType.TRANSLUCENT && translucentOverride != null
                && !translucentOverrideSuspended) {
            return translucentOverride;
        }
        return renderType == TerrainRenderType.TRANSLUCENT ? fixedPipeline() : getTerrainPipeline();
    }

    public static VertexFormat getTerrainVertexFormat() {
        return ChimeraVertexFormats.terrainFormat(materialPlan);
    }

    /** While Chimera owns terrain, a new format rebuilds the level. */
    public static void setMaterialPlan(TerrainMaterialPlan plan) {
        TerrainMaterialPlan next = plan == null ? TerrainMaterialPlan.legacy() : plan;
        if (materialPlan.equals(next)) {
            return;
        }
        materialPlan = next;
        if (initialized && extendedMode) {
            fixedPipeline();
            setTerrainMode(true);
        }
    }

    /** The block.properties id, or -1 when unmapped (Iris's BlockMaterialMapping). */
    public static int blockStateId(BlockState state) {
        return materialResolver.resolve(state);
    }

    /** The pack's {@code layer.*} render type (Iris's BlockRenderType), or null to keep the host's. */
    public static RenderType packRenderType(BlockState state) {
        PackMaterialResolver.Layer layer = materialResolver.layer(state);
        if (layer == null) return null;
        return switch (layer) {
            case SOLID -> RenderType.solid();
            case CUTOUT -> RenderType.cutout();
            case CUTOUT_MIPPED -> RenderType.cutoutMipped();
            case TRANSLUCENT -> RenderType.translucent();
        };
    }

    public static void setMaterialResolver(PackMaterialResolver resolver) {
        PackMaterialResolver next = resolver == null ? PackMaterialResolver.empty() : resolver;
        if (materialResolver == next) {
            return;
        }
        materialResolver = next;
        if (extendedMode) {
            rebuildLiveLevel("material map changed");
        }
    }

    public static void setTranslucentOverride(GraphicsPipeline pipeline) {
        if (pipeline != translucentOverride) {
            ChimeraMod.LOGGER.debug("[chimera] translucent override: {}", pipeline != null ? "installed" : "cleared");
        }
        translucentOverride = pipeline;
        translucentOverrideSuspended = false;
        if (!initialized) {
            return;
        }
        if (extendedMode) {
            PipelineManager.setShaderGetter(ChimeraTerrainPipelines::getTerrainPipeline);
        }
    }

    /** Routes one translucent draw to the fixed pipeline while the water program's sampled inputs are unavailable. */
    public static void suspendTranslucentOverride(boolean suspended) {
        translucentOverrideSuspended = suspended;
    }

    public static boolean isExtendedMode() {
        return extendedMode;
    }

    private static void setTerrainMode(boolean chimeraMode) {
        VertexFormat desiredFormat = chimeraMode
                ? ChimeraVertexFormats.terrainFormat(materialPlan)
                : CustomVertexFormat.COMPRESSED_TERRAIN;
        boolean changed = extendedMode != chimeraMode
                || PipelineManager.TERRAIN_VERTEX_FORMAT != desiredFormat;

        PipelineManager.setTerrainVertexFormat(desiredFormat);
        if (chimeraMode) {
            ThreadBuilderPack.setTerrainBuilderConstructor(renderType -> {
                return new ChimeraExtTerrainBuilder(TerrainRenderType.getRenderType(renderType).bufferSize(),
                        materialResolver, materialPlan);
            });
            PipelineManager.setShaderGetter(ChimeraTerrainPipelines::getTerrainPipeline);
        } else {
            ThreadBuilderPack.defaultTerrainBuilderConstructor();
            PipelineManager.setDefaultShader();
        }
        extendedMode = chimeraMode;

        if (changed) {
            rebuildLiveLevel("terrain format changed");
        }
    }

    /**
     * Runs {@code handoff}, rebuilding the live level at most once, at its end. A handoff can change format and
     * shading several times with no frame in between.
     */
    public static void rebuildingOnce(Runnable handoff) {
        if (heldRebuilds != null) {
            handoff.run();
            return;
        }
        heldRebuilds = new LinkedHashSet<>();
        try {
            handoff.run();
        } finally {
            Set<String> reasons = heldRebuilds;
            heldRebuilds = null;
            if (!reasons.isEmpty()) rebuildLiveLevel(String.join(", ", reasons));
        }
    }

    private static void rebuildLiveLevel(String reason) {
        if (heldRebuilds != null) {
            heldRebuilds.add(reason);
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && minecraft.levelRenderer != null) {
            minecraft.levelRenderer.allChanged();
            ChimeraMod.LOGGER.debug("[chimera] {}: live level rebuilt (extended={})", reason, extendedMode);
        }
    }

    private static GraphicsPipeline buildPipeline(
            String name,
            VertexFormat vertexFormat,
            String shaderConfigName,
            boolean separateAo
    ) {
        return HostPipelineLayout.create(name, shaderConfigName, vertexFormat,
                separateAo ? ChimeraTerrainPipelines::withSeparateAo : UnaryOperator.identity());
    }

    /** Defines CHIMERA_SEPARATE_AO, so the vertex shader folds the alpha brightness back into RGB. */
    public static String withSeparateAo(String source) {
        int lineEnd = source.indexOf('\n', source.indexOf("#version"));
        if (lineEnd < 0) {
            throw new IllegalArgumentException("chimera_terrain vertex shader has no #version line");
        }
        return source.substring(0, lineEnd + 1) + "#define CHIMERA_SEPARATE_AO\n" + source.substring(lineEnd + 1);
    }

    /** Built on first use. */
    private static GraphicsPipeline fixedPipeline() {
        boolean modern = materialPlan.modern();
        boolean separateAo = shading.separateAo();
        int variant = (modern ? 2 : 0) | (separateAo ? 1 : 0);
        GraphicsPipeline pipeline = fixedPipelines[variant];
        if (pipeline == null) {
            String name = "chimera_terrain" + (modern ? "_modern" : "") + (separateAo ? "_separate_ao" : "");
            pipeline = buildPipeline(name, ChimeraVertexFormats.terrainFormat(materialPlan), "chimera_terrain",
                    separateAo);
            fixedPipelines[variant] = pipeline;
        }
        return pipeline;
    }
}
