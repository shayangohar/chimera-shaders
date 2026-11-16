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
 * Builds and installs Chimera's terrain pipelines.
 *
 * The GLSL and JSON under /assets/chimera/shaders/ read the host terrain path's inputs: its uniform suppliers,
 * the ChunkOffset push constant DrawBuffers writes per area, and the section offset in the draw's base
 * instance.
 *
 * Enabling swaps the terrain shader getter to Chimera's pipeline and installs the matching extended builder.
 * Disabling restores the host getter, format and builder. Either rebuilds the live level only when the
 * terrain format changes.
 */
public final class ChimeraTerrainPipelines {
    private static boolean initialized;
    /** True while Chimera's terrain format and pipelines are installed for a pack; chunk builders read it. */
    private static volatile boolean extendedMode;
    /** Chimera's own terrain pipelines by vertex layout (bit 1: modern) and separateAo (bit 0). */
    private static final GraphicsPipeline[] fixedPipelines = new GraphicsPipeline[4];
    /** Read by the chunk-build threads, through the render-layer overrides; replaced on the render thread. */
    private static volatile PackMaterialResolver materialResolver = PackMaterialResolver.empty();
    private static TerrainMaterialPlan materialPlan = TerrainMaterialPlan.legacy();
    /** The pack's gbuffers_terrain pipeline, drawn over Chimera's own for every layer but translucent. */
    private static GraphicsPipeline geometryOverride;
    /** The pack's water pipeline, drawn over Chimera's own for the translucent layer only. */
    private static GraphicsPipeline translucentOverride;
    /** True for one translucent draw whose pack inputs are not live this frame. */
    private static boolean translucentOverrideSuspended;
    /**
     * The installed pack's terrain shading, which chunk builders read: its directional shading,
     * separateAo, ambient occlusion level and light-block voxels, replaced as one value.
     */
    private static volatile TerrainShadingDirectives shading = TerrainShadingDirectives.VANILLA;
    /** The rebuilds a handoff asked for (rebuildingOnce), by reason; null outside one. Render thread only. */
    private static Set<String> heldRebuilds;

    private ChimeraTerrainPipelines() {}

    /** A block's shade brightness under the installed pack's ambientOcclusionLevel (Iris's getShadeBrightness). */
    public static float shadeBrightness(float original) {
        return shading.shadeBrightness(original);
    }

    /**
     * True while the installed pack lights faces itself (Iris's oldLighting off), so ClientLevel.getShade
     * answers unshaded for block and fluid meshing.
     */
    public static boolean directionalShadingDisabled() {
        return shading.disablesDirectionalShading();
    }

    /**
     * True while Chimera's terrain meshes carry the light pipeline's brightness in the vertex colour's alpha
     * instead of multiplying it into RGB (Iris's separateAo). Host-format meshes never do, since VulkanMod's
     * own terrain shader reads alpha as opacity.
     */
    public static boolean separateAoActive() {
        return shading.separateAo() && extendedMode;
    }

    /**
     * True while Chimera meshes terrain for a pack. Sections then compile as NeoForge's SectionCompiler and
     * Sodium do where VulkanMod departs: a light-emitting block darkens its neighbours' ambient occlusion
     * (ChimeraEmitterOcclusionMixin), and a block entity that renders off-screen joins only the level's global
     * set (ChimeraGlobalBlockEntityMixin). The level rebuilds when this changes, with the terrain format.
     */
    public static boolean vanillaSectionCompile() {
        return extendedMode;
    }

    /**
     * True while the installed pack meshes each light block as a voxel quad (Iris's voxelizeLightBlocks);
     * chunk builders read it.
     */
    public static boolean voxelizeLightBlocks() {
        return shading.voxelizeLightBlocks();
    }

    /**
     * Applies the installed pack's oldLighting, separateAo, ambientOcclusionLevel and voxelizeLightBlocks, or
     * {@link TerrainShadingDirectives#VANILLA} when no pack is installed. Chunk meshes bake them in, so a change
     * rebuilds the live level once, as Iris reloads chunks when one of its WorldRenderingSettings values
     * changes.
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

    /**
     * Installs or clears the pack's gbuffers_terrain pipeline. Every terrain getter goes through
     * getTerrainPipeline(renderType), so the override holds across the shadow pass's getter swap and restore.
     */
    public static void setGeometryOverride(GraphicsPipeline pipeline) {
        // logged only on change, since the no-pack load clears it on every window resize
        if (pipeline != geometryOverride) {
            ChimeraMod.LOGGER.debug("[chimera] terrain override: {}", pipeline != null ? "installed" : "cleared");
        }
        geometryOverride = pipeline;
        if (!initialized) {
            return;
        }
        // re-register only while Chimera owns terrain; in host mode the host getter stays
        if (extendedMode) {
            PipelineManager.setShaderGetter(ChimeraTerrainPipelines::getTerrainPipeline);
        }
    }

    /**
     * Destroys Chimera's own terrain pipelines at the renderer's final teardown (ChimeraMainPass.cleanUp).
     * VulkanMod's PipelineManager.destroyPipelines destroys only its own, so these would outlive the device.
     * The overrides belong to the pack and are destroyed with its pipelines.
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

    /** Hands terrain to the host's format and pipeline. */
    public static void disable() {
        setTerrainMode(false);
    }

    public static GraphicsPipeline getTerrainPipeline() {
        return geometryOverride != null ? geometryOverride : fixedPipeline();
    }

    /** The pack's override for the layer, else Chimera's fixed pipeline; translucent takes only the water one. */
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

    /** Selects the pack-wide terrain vertex layout; while Chimera owns terrain, a new format rebuilds the level. */
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

    /** The pack's block.properties id for a block state, or -1 when unmapped (Iris's BlockMaterialMapping). */
    public static int blockStateId(BlockState state) {
        return materialResolver.resolve(state);
    }

    /**
     * The chunk render type the pack's {@code layer.*} lines give a block (Iris's BlockRenderType), or null to
     * keep the host's. A new resolver rebuilds the live level, so every mesh takes its layers.
     */
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

    /** Installs or clears the pack's water pipeline for the translucent layer. */
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

    /**
     * Routes one translucent layer draw to Chimera's fixed pipeline while the pack water program's sampled
     * inputs are unavailable. PackSceneCoverage sets it as the layer's window opens and clears it as it closes.
     */
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
     * Runs {@code handoff}, rebuilding the live level at most once, at its end. A frame boundary that hands
     * terrain to the host, applies a pack or dimension variant and reinstalls it can change the terrain format
     * and shading up to four times with no frame drawn in between. Rebuilding on each change would have the
     * chunk builders mesh sections only to throw them away.
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

    /**
     * Defines CHIMERA_SEPARATE_AO after the #version line. The vertex shader then multiplies the brightness
     * that separateAo meshes carry in alpha back into RGB, as the vanilla terrain shader would see it, and
     * keeps the block opaque.
     */
    public static String withSeparateAo(String source) {
        int lineEnd = source.indexOf('\n', source.indexOf("#version"));
        if (lineEnd < 0) {
            throw new IllegalArgumentException("chimera_terrain vertex shader has no #version line");
        }
        return source.substring(0, lineEnd + 1) + "#define CHIMERA_SEPARATE_AO\n" + source.substring(lineEnd + 1);
    }

    /** The fixed pipeline for the session's vertex layout and separateAo, built on first use. */
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
