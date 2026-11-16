package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;

/** The material vertex inputs the pack's terrain, water and shadow programs read, merged pack-wide. */
public record TerrainMaterialPlan(
        boolean modern,
        boolean midTexCoord,
        boolean midBlock,
        boolean tangent,
        List<String> deviations
) {
    public TerrainMaterialPlan {
        deviations = deviations == null ? List.of() : deviations.stream().distinct().sorted().toList();
    }

    public static TerrainMaterialPlan legacy() {
        return new TerrainMaterialPlan(false, false, false, false, List.of());
    }

    /** Bytes per vertex: the modern layout adds twelve to the host's 24. */
    public int stride() {
        return modern ? 24 + 12 : 24;
    }

    public static TerrainMaterialPlan forProgram(
            String name,
            String vertexSource,
            String fragmentSource
    ) {
        String lane = FamilyAdapterRegistry.laneProgram(name);
        if (!lane.equals("gbuffers_terrain") && !lane.equals("gbuffers_water") && !lane.equals("shadow")) {
            return legacy();
        }
        boolean supportedModern = lane.equals("shadow")
                ? TerrainVertexBridge.supportsModernShadow(vertexSource, fragmentSource)
                : TerrainVertexBridge.requiresExtendedTerrain(vertexSource)
                || TerrainVertexBridge.requiresExtendedTerrain(fragmentSource);
        if (!supportedModern) {
            return legacy();
        }
        List<String> deviations = new ArrayList<>();
        deviations.add(lane.equals("gbuffers_water")
                ? "MODERN_WATER_VERTEX_BRIDGE"
                : lane.equals("shadow") ? "MODERN_SHADOW_VERTEX_BRIDGE"
                : "MODERN_TERRAIN_VERTEX_BRIDGE");
        deviations.add("TERRAIN_NORMAL_AUTHORITATIVE");
        deviations.add("TERRAIN_TANGENT_DERIVED_FALLBACK");
        return new TerrainMaterialPlan(true, true, true, true, deviations);
    }

    public TerrainMaterialPlan merge(TerrainMaterialPlan other) {
        if (other == null || !other.modern()) {
            return this;
        }
        List<String> merged = new ArrayList<>(deviations);
        merged.addAll(other.deviations());
        return new TerrainMaterialPlan(true,
                midTexCoord || other.midTexCoord(),
                midBlock || other.midBlock(),
                tangent || other.tangent(),
                merged);
    }
}
