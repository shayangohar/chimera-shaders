// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render.shader;

import net.chimera.shaderpack.PackEntityIdResolver;
import net.chimera.shaderpack.PackPipelines;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * The pack pipelines the entity bridge's lanes draw with, installed and dropped whole per session. A null
 * pipeline leaves its lane to the host, or to the next program in Iris's chain.
 *
 * @param translucentEntityText gbuffers_entities_translucent on Iris's GLYPH text layout (TEXT)
 * @param blockTranslucent gbuffers_block_translucent (BE_TRANSLUCENT)
 * @param blockTranslucentText gbuffers_block_translucent on the GLYPH layout (TEXT_BE)
 * @param movingBlock gbuffers_block on the block layout (MOVING_BLOCK)
 * @param damagedBlock gbuffers_damagedblock on the widened block layout
 * @param beaconBeam gbuffers_beaconbeam on the widened block layout (BEACON)
 * @param shadowMovingBlock shadow_entities on the block layout
 * @param shadowBlock shadow_block: a block entity's models in the shadow pass
 */
public record InstalledLanes(
        GraphicsPipeline entity,
        GraphicsPipeline translucentEntity,
        GraphicsPipeline translucentEntityText,
        GraphicsPipeline spiderEyes,
        GraphicsPipeline block,
        GraphicsPipeline blockTranslucent,
        GraphicsPipeline blockTranslucentText,
        GraphicsPipeline movingBlock,
        GraphicsPipeline damagedBlock,
        GraphicsPipeline beaconBeam,
        PackPipelines.PackHand hand,
        PackPipelines.PackHand handWater,
        GraphicsPipeline particle,
        GraphicsPipeline translucentParticle,
        Map<ChimeraEntityBridge.Family, GraphicsPipeline> hostLayout,
        GraphicsPipeline shadowEntities,
        GraphicsPipeline shadowMovingBlock,
        GraphicsPipeline shadowBlock,
        PackEntityIdResolver entityIds
) {
    public static final InstalledLanes NONE = new InstalledLanes(null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, Map.of(), null, null, null, null);

    /** Keeps only host-layout families with a lane and a pipeline. */
    public InstalledLanes {
        EnumMap<ChimeraEntityBridge.Family, GraphicsPipeline> lanes = new EnumMap<>(ChimeraEntityBridge.Family.class);
        if (hostLayout != null) {
            hostLayout.forEach((family, pipeline) -> {
                if (HostLayoutLane.of(family) != null && pipeline != null) lanes.put(family, pipeline);
            });
        }
        hostLayout = Collections.unmodifiableMap(lanes);
        entityIds = entityIds == null ? PackEntityIdResolver.empty() : entityIds;
    }

    /**
     * Whether the bridge turns on. The text, moving-block and block-translucent variants and shadow_block do not
     * count on their own.
     */
    public boolean any() {
        return this.entity != null || this.translucentEntity != null || this.spiderEyes != null || this.block != null
                || this.damagedBlock != null || this.hand != null || this.handWater != null || this.particle != null
                || this.translucentParticle != null || this.beaconBeam != null || !this.hostLayout.isEmpty()
                || this.shadowEntities != null;
    }
}
