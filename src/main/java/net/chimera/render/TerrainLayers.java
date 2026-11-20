// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import net.chimera.shaderpack.PackRenderingPhase;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.vertex.TerrainRenderType;

/**
 * The section layers VulkanMod draws. Its builds put solid blocks into cutout_mipped, cutout too under
 * uniqueOpaqueLayer, and tripwire into translucent (BuildTask.compactRenderTypes).
 */
public final class TerrainLayers {
    /** Drawn first, before any other terrain. */
    public static final TerrainRenderType OPAQUE = TerrainRenderType.CUTOUT_MIPPED;

    private TerrainLayers() {}

    public static boolean drawn(TerrainRenderType type) {
        return (Initializer.CONFIG.uniqueOpaqueLayer
                ? TerrainRenderType.COMPACT_RENDER_TYPES : TerrainRenderType.SEMI_COMPACT_RENDER_TYPES).contains(type);
    }

    /**
     * Iris's SodiumPrograms.getAlphaTest. Solid blocks in cutout_mipped are tested too, unlike Iris, but their
     * opaque texels pass 0.1 either way.
     */
    public static float irisAlphaReference(TerrainRenderType type) {
        return type == TerrainRenderType.CUTOUT || type == TerrainRenderType.CUTOUT_MIPPED ? 0.1F : 0.0F;
    }

    /**
     * Sodium draws its cutout passes in the solid call, so under Iris every opaque block reads TERRAIN_SOLID.
     * Some packs voxelise only that stage.
     */
    public static PackRenderingPhase phase(TerrainRenderType type) {
        return switch (type) {
            case SOLID, CUTOUT_MIPPED, CUTOUT -> PackRenderingPhase.TERRAIN_SOLID;
            case TRANSLUCENT -> PackRenderingPhase.TERRAIN_TRANSLUCENT;
            case TRIPWIRE -> PackRenderingPhase.TRIPWIRE;
        };
    }
}
