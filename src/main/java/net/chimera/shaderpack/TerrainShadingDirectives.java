// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What the pack changes in chunk meshes, as Iris's WorldRenderingSettings applies it; a change rebuilds them.
 * Without {@code oldLighting} terrain is meshed unshaded, as the pack lights faces itself. {@code separateAo}
 * moves the per-vertex brightness from RGB into alpha. {@code voxelizeLightBlocks} meshes each light block as a
 * zero-area quad carrying its id and emission.
 */
public record TerrainShadingDirectives(boolean oldLighting, boolean separateAo, float ambientOcclusionLevel,
                                       boolean voxelizeLightBlocks, List<String> deviations) {
    public static final TerrainShadingDirectives DEFAULTS = new TerrainShadingDirectives(false, false, List.of());
    /** Minecraft with no pack: shaded faces and ambient occlusion multiplied into RGB at full strength. */
    public static final TerrainShadingDirectives VANILLA = new TerrainShadingDirectives(true, false, List.of());

    public TerrainShadingDirectives {
        deviations = deviations == null ? List.of() : List.copyOf(deviations);
    }

    /** The switches with Iris's full ambient occlusion. */
    public TerrainShadingDirectives(boolean oldLighting, boolean separateAo, List<String> deviations) {
        this(oldLighting, separateAo, 1.0F, deviations);
    }

    /** The lighting switches, with no light-block voxels. */
    public TerrainShadingDirectives(boolean oldLighting, boolean separateAo, float ambientOcclusionLevel,
                                    List<String> deviations) {
        this(oldLighting, separateAo, ambientOcclusionLevel, false, deviations);
    }

    static TerrainShadingDirectives fromProperties(Map<String, String> properties) {
        return fromProperties(properties, PackDirectives.DEFAULTS.ambientOcclusionLevel());
    }

    static TerrainShadingDirectives fromProperties(Map<String, String> properties, float ambientOcclusionLevel) {
        List<String> deviations = new ArrayList<>();
        boolean oldLighting = PackBooleanDirective.read(properties, "oldLighting", DEFAULTS.oldLighting(),
                deviations, "OLD_LIGHTING_DIRECTIVE_MALFORMED");
        boolean separateAo = PackBooleanDirective.read(properties, "separateAo", DEFAULTS.separateAo(),
                deviations, "SEPARATE_AO_DIRECTIVE_MALFORMED");
        boolean voxelizeLightBlocks = PackBooleanDirective.read(properties, "voxelizeLightBlocks",
                DEFAULTS.voxelizeLightBlocks(), deviations, "VOXELIZE_LIGHT_BLOCKS_DIRECTIVE_MALFORMED");
        return new TerrainShadingDirectives(oldLighting, separateAo, ambientOcclusionLevel, voxelizeLightBlocks,
                deviations);
    }

    /** Whether the pack lights faces itself, so the host's directional shade must not apply. */
    public boolean disablesDirectionalShading() {
        return !oldLighting;
    }

    /** A block's shade brightness at this level: Iris's {@code 1 - level * (1 - original)}. */
    public float shadeBrightness(float original) {
        return 1.0F - ambientOcclusionLevel * (1.0F - original);
    }
}
