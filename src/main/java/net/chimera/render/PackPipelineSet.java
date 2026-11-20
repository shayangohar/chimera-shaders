// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.InstalledLanes;
import net.chimera.shaderpack.GeometryOutputPlan;
import net.chimera.shaderpack.PackEntityIdResolver;
import net.chimera.shaderpack.PackPipelines;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/** The pipelines one pack session installed, by family; a null family keeps its host draw. */
final class PackPipelineSet {
    PackPipelines.PackTerrain terrainRoute;
    PackPipelines.PackTerrain waterRoute;

    /** Built post programs, sorted into pass order at load. */
    final List<PackPipelines.PackPost> postStages = new ArrayList<>();
    /** The shadowcomp passes' programs, over the shadowcolor targets (PackShadowComposites). */
    final List<PackPipelines.PackPost> shadowcompStages = new ArrayList<>();

    /** The final pass, also in postStages, kept apart because it runs before the hand and GUI draw. */
    PackPipelines.PackPost finalPost;

    /** gbuffers_terrain for solid and cutout terrain; null keeps Chimera's terrain pipeline. */
    GraphicsPipeline terrainPipeline;

    /** gbuffers_water on the host translucent terrain path; null keeps the default pipeline. */
    GraphicsPipeline waterPipeline;

    /** The shadow program, on the shadow pass's terrain; null draws the fixed identity shadow. */
    GraphicsPipeline shadowPipeline;
    /** shadow_water for the shadow pass's translucent terrain (Iris SHADOW_TRANS); null draws it with shadow. */
    GraphicsPipeline shadowWaterPipeline;
    /** shadow_cutout for the shadow pass's cutout terrain layers (Iris SHADOW_CUTOUT); null draws them with shadow. */
    GraphicsPipeline shadowCutoutPipeline;

    /** shadow_entities on the shadow pass's entity lane; null leaves entities out of the shadow map. */
    PackPipelines.PackEntity shadowEntities;
    /** shadow_entities on the block layout, for moving blocks in the shadow pass. */
    PackPipelines.PackEntity shadowMovingBlock;
    /** shadow_block, for block entities' models in the shadow pass; null leaves them to shadow_entities. */
    PackPipelines.PackEntity shadowBlock;

    // the family bridge's lanes (InstalledLanes)
    PackPipelines.PackEntity entity;

    PackPipelines.PackEntity translucentEntity;

    /** The spider-eyes program (Iris EYES and ENTITY_TRANSLUCENT_EMISSIVE). */
    PackPipelines.PackEntity spiderEyes;

    /** gbuffers_block, for block entities' models. */
    PackPipelines.PackEntity block;

    /** gbuffers_block_translucent: block entities' translucent models (Iris BE_TRANSLUCENT). */
    PackPipelines.PackEntity blockTranslucent;

    /** gbuffers_block on the block layout: falling blocks and moving pistons (Iris MOVING_BLOCK). */
    PackPipelines.PackEntity movingBlock;

    /** The block breaking overlay's program. */
    PackPipelines.PackEntity damagedBlock;

    // the translucent-entity and block-translucent programs on Iris's GLYPH text layout (TEXT, TEXT_BE)
    PackPipelines.PackEntity translucentEntityText;
    PackPipelines.PackEntity blockTranslucentText;

    PackPipelines.PackHand hand;

    PackPipelines.PackHand handWater;

    PackPipelines.PackParticle particle;

    PackPipelines.PackParticle translucentParticle;

    /** gbuffers_beaconbeam on the beam's widened block layout (Iris BEACON). */
    PackPipelines.PackParticle beaconBeam;

    /** Programs of the installed host-layout lanes, such as weather, lines, glint and lightning. */
    final EnumMap<ChimeraEntityBridge.Family, PackPipelines.PackParticle> hostLayout =
            new EnumMap<>(ChimeraEntityBridge.Family.class);

    PackPipelines.PackSkyFamily skyBasic;
    PackPipelines.PackSkyFamily skyTextured;
    PackPipelines.PackSky cloud;
    boolean cloudsDrawNothing;

    /** Destroys every installed pipeline. */
    void destroy() {
        postStages.forEach(post -> post.pipeline().cleanUp());
        shadowcompStages.forEach(post -> post.pipeline().cleanUp());
        Stream.of(terrainPipeline, waterPipeline, shadowPipeline, shadowWaterPipeline, shadowCutoutPipeline)
                .filter(Objects::nonNull).forEach(GraphicsPipeline::cleanUp);
        terrainRoute = null;
        waterRoute = null;
        Stream.of(shadowEntities, shadowMovingBlock, shadowBlock, entity, translucentEntity, spiderEyes, block,
                        blockTranslucent, movingBlock, damagedBlock, translucentEntityText, blockTranslucentText)
                .filter(Objects::nonNull).forEach(value -> value.pipeline().cleanUp());
        if (hand != null) hand.cleanUp();
        if (handWater != null) handWater.cleanUp();
        if (particle != null) particle.pipeline().cleanUp();
        if (translucentParticle != null) translucentParticle.pipeline().cleanUp();
        if (beaconBeam != null) beaconBeam.pipeline().cleanUp();
        hostLayout.values().forEach(lane -> lane.pipeline().cleanUp());
        if (skyBasic != null) skyBasic.cleanUp();
        if (skyTextured != null) skyTextured.cleanUp();
        if (cloud != null) cloud.pipeline().cleanUp();
    }

    /** Forgets every pipeline, leaving each family to its host draw. */
    void clear() {
        postStages.clear();
        shadowcompStages.clear();
        finalPost = null;
        terrainPipeline = null;
        terrainRoute = null;
        waterPipeline = null;
        waterRoute = null;
        shadowPipeline = null;
        shadowWaterPipeline = null;
        shadowCutoutPipeline = null;
        shadowEntities = null;
        shadowMovingBlock = null;
        shadowBlock = null;
        entity = null;
        translucentEntity = null;
        spiderEyes = null;
        block = null;
        blockTranslucent = null;
        movingBlock = null;
        damagedBlock = null;
        translucentEntityText = null;
        blockTranslucentText = null;
        hand = null;
        handWater = null;
        particle = null;
        translucentParticle = null;
        beaconBeam = null;
        hostLayout.clear();
        skyBasic = null;
        skyTextured = null;
        cloud = null;
        cloudsDrawNothing = false;
    }

    /** The installed program of a family lane, whose outputs its window opens. */
    private PackPipelines.PackEntity familyPipeline(ChimeraEntityBridge.Family family) {
        return switch (family) {
            case ENTITY -> this.entity;
            case ENTITY_TRANSLUCENT -> this.translucentEntity;
            case SPIDER_EYES -> this.spiderEyes;
            case BLOCK -> this.block;
            // block-entity models and text draw in gbuffers_block_translucent's own target window
            case BLOCK_TRANSLUCENT -> this.blockTranslucent != null ? this.blockTranslucent : this.blockTranslucentText;
            case MOVING_BLOCK -> this.movingBlock;
            case DAMAGED_BLOCK -> this.damagedBlock;
            case HAND -> this.hand == null ? null : this.hand.primary();
            case HAND_WATER -> this.handWater == null ? null : this.handWater.primary();
            // the shadow pass owns the shadow map's attachments, so casters open no window there
            case SHADOW_ENTITIES -> this.shadowEntities;
            case SHADOW_BLOCK -> this.shadowBlock;
            default -> null;
        };
    }

    /** The authored outputs of a lane's installed program, or null when the lane has none. */
    GeometryOutputPlan laneOutputs(ChimeraEntityBridge.Family family) {
        PackPipelines.PackEntity entity = familyPipeline(family);
        if (entity != null) return entity.outputPlan();
        PackPipelines.PackParticle lane = particlePipeline(family);
        return lane == null ? null : lane.outputPlan();
    }

    /** The program of a particle, beacon-beam or host-layout lane. */
    private PackPipelines.PackParticle particlePipeline(ChimeraEntityBridge.Family family) {
        return switch (family) {
            case PARTICLE -> this.particle;
            case PARTICLE_TRANSLUCENT -> this.translucentParticle;
            case BEACON_BEAM -> this.beaconBeam;
            default -> this.hostLayout.get(family);
        };
    }

    /** The lanes these pipelines give the family bridge. */
    InstalledLanes lanes(PackEntityIdResolver entityIds) {
        EnumMap<ChimeraEntityBridge.Family, GraphicsPipeline> hostLayoutPipelines =
                new EnumMap<>(ChimeraEntityBridge.Family.class);
        this.hostLayout.forEach((family, lane) -> hostLayoutPipelines.put(family, pipeline(lane)));
        return new InstalledLanes(
                pipeline(this.entity),
                pipeline(this.translucentEntity),
                pipeline(this.translucentEntityText),
                pipeline(this.spiderEyes),
                pipeline(this.block),
                pipeline(this.blockTranslucent),
                pipeline(this.blockTranslucentText),
                pipeline(this.movingBlock),
                pipeline(this.damagedBlock),
                pipeline(this.beaconBeam),
                this.hand,
                this.handWater,
                pipeline(this.particle),
                pipeline(this.translucentParticle),
                hostLayoutPipelines,
                pipeline(this.shadowEntities),
                pipeline(this.shadowMovingBlock),
                pipeline(this.shadowBlock),
                entityIds);
    }

    private static GraphicsPipeline pipeline(PackPipelines.PackEntity entity) {
        return entity == null ? null : entity.pipeline();
    }

    private static GraphicsPipeline pipeline(PackPipelines.PackParticle lane) {
        return lane == null ? null : lane.pipeline();
    }
}
