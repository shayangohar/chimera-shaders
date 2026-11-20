// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** The family, stage and vertex contract of each program Chimera knows; any other program is unsupported. */
public final class FamilyAdapterRegistry {
    private static final Map<String, FamilyAdapterPlan> CONTRACTS = Stream.of(
            plan("gbuffers_terrain", FamilyAdapterPlan.Family.TERRAIN,
                    UniformRegistry.Stage.GEOMETRY, FamilyAdapterPlan.VertexContract.FIXED_TERRAIN),
            plan("gbuffers_water", FamilyAdapterPlan.Family.WATER,
                    UniformRegistry.Stage.TRANSLUCENT, FamilyAdapterPlan.VertexContract.FIXED_TERRAIN),
            plan("shadow", FamilyAdapterPlan.Family.SHADOW,
                    UniformRegistry.Stage.SHADOW, FamilyAdapterPlan.VertexContract.FIXED_TERRAIN),
            // Iris SodiumPrograms SHADOW_TRANS: the shadow pass's translucent terrain layer
            plan("shadow_water", FamilyAdapterPlan.Family.SHADOW,
                    UniformRegistry.Stage.SHADOW, FamilyAdapterPlan.VertexContract.FIXED_TERRAIN),
            // Iris SHADOW_CUTOUT: cutout layers, which hold VulkanMod's solid blocks too; all alpha-test at 0.1
            plan("shadow_cutout", FamilyAdapterPlan.Family.SHADOW,
                    UniformRegistry.Stage.SHADOW, FamilyAdapterPlan.VertexContract.FIXED_TERRAIN),
            // Iris SHADOW_ENTITIES_CUTOUT; end portals land here after the entitySolid swap
            plan("shadow_entities", FamilyAdapterPlan.Family.SHADOW_ENTITIES,
                    UniformRegistry.Stage.SHADOW_ENTITY, FamilyAdapterPlan.VertexContract.EXTENDED_ENTITY),
            // Iris 1.21.1 SHADOW_BLOCK: every block entity model
            plan("shadow_block", FamilyAdapterPlan.Family.SHADOW_BLOCK,
                    UniformRegistry.Stage.SHADOW_ENTITY, FamilyAdapterPlan.VertexContract.EXTENDED_ENTITY),
            // Iris assignToShadow keys on the host layout; PackResolutionPlan resolves each to its Iris program
            plan("shadow_lines", FamilyAdapterPlan.Family.SHADOW_LINES,
                    UniformRegistry.Stage.SHADOW_ENTITY, FamilyAdapterPlan.VertexContract.HOST_LINE),
            plan("shadow_leash", FamilyAdapterPlan.Family.SHADOW_LEASH,
                    UniformRegistry.Stage.SHADOW_ENTITY, FamilyAdapterPlan.VertexContract.HOST_LEASH),
            plan("shadow_lightning", FamilyAdapterPlan.Family.SHADOW_LIGHTNING,
                    UniformRegistry.Stage.SHADOW_ENTITY, FamilyAdapterPlan.VertexContract.HOST_LIGHTNING),
            plan("shadow_text", FamilyAdapterPlan.Family.SHADOW_TEXT,
                    UniformRegistry.Stage.SHADOW_ENTITY, FamilyAdapterPlan.VertexContract.HOST_TEXT),
            plan("shadow_text_bg", FamilyAdapterPlan.Family.SHADOW_TEXT_BG,
                    UniformRegistry.Stage.SHADOW_ENTITY, FamilyAdapterPlan.VertexContract.HOST_TEXT_BACKGROUND),
            plan("shadow_basic", FamilyAdapterPlan.Family.SHADOW_BASIC,
                    UniformRegistry.Stage.SHADOW_ENTITY, FamilyAdapterPlan.VertexContract.HOST_WATER_MASK),
            plan("shadow_tex", FamilyAdapterPlan.Family.SHADOW_TEX,
                    UniformRegistry.Stage.SHADOW_ENTITY, FamilyAdapterPlan.VertexContract.HOST_POSITION_TEX),
            plan("shadow_tex_color", FamilyAdapterPlan.Family.SHADOW_TEX_COLOR,
                    UniformRegistry.Stage.SHADOW_ENTITY, FamilyAdapterPlan.VertexContract.HOST_POSITION_TEX_COLOR),
            plan("gbuffers_entities", FamilyAdapterPlan.Family.ENTITY,
                    UniformRegistry.Stage.ENTITY, FamilyAdapterPlan.VertexContract.EXTENDED_ENTITY),
            plan("gbuffers_entities_translucent",
                    FamilyAdapterPlan.Family.ENTITY_TRANSLUCENT, UniformRegistry.Stage.ENTITY,
                    FamilyAdapterPlan.VertexContract.EXTENDED_ENTITY),
            plan("gbuffers_spidereyes",
                    FamilyAdapterPlan.Family.SPIDER_EYES, UniformRegistry.Stage.ENTITY,
                    FamilyAdapterPlan.VertexContract.EXTENDED_ENTITY),
            plan("gbuffers_block", FamilyAdapterPlan.Family.BLOCK,
                    UniformRegistry.Stage.BLOCK, FamilyAdapterPlan.VertexContract.EXTENDED_ENTITY),
            // Iris BE_TRANSLUCENT and TEXT_BE; text uses the GLYPH layout variant
            plan("gbuffers_block_translucent", FamilyAdapterPlan.Family.BLOCK_TRANSLUCENT,
                    UniformRegistry.Stage.BLOCK, FamilyAdapterPlan.VertexContract.EXTENDED_ENTITY),
            // Iris CRUMBLING: BLOCK buffers widened to EXTENDED_BLOCK, as Iris widens them to TERRAIN
            plan("gbuffers_damagedblock",
                    FamilyAdapterPlan.Family.DAMAGED_BLOCK, UniformRegistry.Stage.BLOCK,
                    FamilyAdapterPlan.VertexContract.EXTENDED_ENTITY),
            plan("gbuffers_hand", FamilyAdapterPlan.Family.HAND,
                    UniformRegistry.Stage.HAND, FamilyAdapterPlan.VertexContract.EXTENDED_ENTITY),
            plan("gbuffers_hand_water",
                    FamilyAdapterPlan.Family.HAND_WATER, UniformRegistry.Stage.HAND,
                    FamilyAdapterPlan.VertexContract.EXTENDED_ENTITY),
            plan("gbuffers_particles", FamilyAdapterPlan.Family.PARTICLE,
                    UniformRegistry.Stage.PARTICLE, FamilyAdapterPlan.VertexContract.HOST_PARTICLE),
            plan("gbuffers_particles_translucent",
                    FamilyAdapterPlan.Family.PARTICLE_TRANSLUCENT, UniformRegistry.Stage.PARTICLE,
                    FamilyAdapterPlan.VertexContract.HOST_PARTICLE),
            plan("gbuffers_skybasic", FamilyAdapterPlan.Family.SKY,
                    UniformRegistry.Stage.SKY, FamilyAdapterPlan.VertexContract.SKY_POSITION_COLOR),
            plan("gbuffers_skytextured", FamilyAdapterPlan.Family.SKY,
                    UniformRegistry.Stage.SKY, FamilyAdapterPlan.VertexContract.SKY_POSITION_COLOR_UV),
            plan("gbuffers_clouds", FamilyAdapterPlan.Family.CLOUD,
                    UniformRegistry.Stage.CLOUD, FamilyAdapterPlan.VertexContract.CLOUD_POSITION_UV_COLOR_NORMAL),
            plan("gbuffers_weather", FamilyAdapterPlan.Family.WEATHER,
                    UniformRegistry.Stage.PARTICLE, FamilyAdapterPlan.VertexContract.HOST_PARTICLE),
            // Iris ShaderKey.LINES, served like weather from the host transform blocks
            plan("gbuffers_line", FamilyAdapterPlan.Family.LINE,
                    UniformRegistry.Stage.PARTICLE, FamilyAdapterPlan.VertexContract.HOST_LINE),
            // Iris GLINT (ShaderKey.GLINT) on the host POSITION_TEX glint layers
            plan("gbuffers_armor_glint", FamilyAdapterPlan.Family.GLINT,
                    UniformRegistry.Stage.PARTICLE, FamilyAdapterPlan.VertexContract.HOST_GLINT),
            // Iris LIGHTNING (LIGHTNING, DRAGON_RAYS) on the host POSITION_COLOR layout
            plan("gbuffers_lightning", FamilyAdapterPlan.Family.LIGHTNING,
                    UniformRegistry.Stage.PARTICLE, FamilyAdapterPlan.VertexContract.HOST_LIGHTNING),
            // Iris BEACON (BEACON_BEAM_OPAQUE, BEACON_BEAM_TRANSLUCENT)
            plan("gbuffers_beaconbeam", FamilyAdapterPlan.Family.BEACON_BEAM,
                    UniformRegistry.Stage.PARTICLE, FamilyAdapterPlan.VertexContract.HOST_BEACON),
            // Iris LEASH (ProgramId.Basic) on the host POSITION_COLOR_LIGHTMAP lead strip
            plan("gbuffers_basic", FamilyAdapterPlan.Family.BASIC,
                    UniformRegistry.Stage.PARTICLE, FamilyAdapterPlan.VertexContract.HOST_LEASH),
            // Iris TEXT_BG: a shader key on gbuffers_entities_translucent's chain
            plan("text_bg", FamilyAdapterPlan.Family.TEXT_BG,
                    UniformRegistry.Stage.PARTICLE, FamilyAdapterPlan.VertexContract.HOST_TEXT_BACKGROUND),
            // Iris TEXTURED and TEXTURED_COLOR (MixinGameRenderer), outside the sky phases
            plan("gbuffers_textured", FamilyAdapterPlan.Family.TEXTURED,
                    UniformRegistry.Stage.PARTICLE, FamilyAdapterPlan.VertexContract.HOST_POSITION_TEX),
            plan("textured_color", FamilyAdapterPlan.Family.TEXTURED_COLOR,
                    UniformRegistry.Stage.PARTICLE, FamilyAdapterPlan.VertexContract.HOST_POSITION_TEX_COLOR)
    ).collect(Collectors.toUnmodifiableMap(FamilyAdapterPlan::programName, plan -> plan));

    private FamilyAdapterRegistry() {}

    public static FamilyAdapterPlan forProgram(String name) {
        if (name == null || name.isBlank()) {
            return FamilyAdapterPlan.unsupported(name);
        }
        FamilyAdapterPlan exact = CONTRACTS.get(name);
        if (exact != null) {
            return exact;
        }
        if (PostTargetPlan.isFullscreenProgramName(name)) {
            return new FamilyAdapterPlan(name, FamilyAdapterPlan.Family.POST,
                    UniformRegistry.Stage.POST, FamilyAdapterPlan.VertexContract.FULLSCREEN,
                    true, List.of());
        }
        if (name.startsWith("gbuffers_sky")) {
            return new FamilyAdapterPlan(name, FamilyAdapterPlan.Family.SKY, UniformRegistry.Stage.SKY,
                    FamilyAdapterPlan.VertexContract.SKY_POSITION_COLOR, true, List.of());
        }
        if (name.startsWith("gbuffers_cloud")) {
            return new FamilyAdapterPlan(name, FamilyAdapterPlan.Family.CLOUD, UniformRegistry.Stage.CLOUD,
                    FamilyAdapterPlan.VertexContract.CLOUD_POSITION_UV_COLOR_NORMAL, true, List.of());
        }
        if (name.startsWith("gbuffers_weather")) {
            return unsupported(name, FamilyAdapterPlan.Family.WEATHER);
        }
        if (name.startsWith("gbuffers_")) {
            return unsupported(name, FamilyAdapterPlan.Family.SPECIAL);
        }
        return FamilyAdapterPlan.unsupported(name);
    }

    /** A shadow program on the terrain layout: shadow, shadow_cutout or shadow_water. */
    public static boolean isShadowTerrain(String name) {
        FamilyAdapterPlan plan = forProgram(name);
        return plan.family() == FamilyAdapterPlan.Family.SHADOW
                && plan.vertexContract() == FamilyAdapterPlan.VertexContract.FIXED_TERRAIN;
    }

    /** Terrain, water and the shadow terrain programs: the programs the terrain vertex bridge converts. */
    static boolean isTerrainLike(String name) {
        return name.equals("gbuffers_terrain") || name.equals("gbuffers_water") || isShadowTerrain(name);
    }

    /** Programs whose modern sources are normalised like terrain: terrain, water and every shadow program. */
    static boolean isTerrainMaterialFamily(String name) {
        return name.equals("gbuffers_terrain") || name.equals("gbuffers_water") || isShadowFamily(name);
    }

    /**
     * Families that get a geometry output plan for DRAWBUFFERS or RENDERTARGETS; an unused directive never
     * blocks one.
     */
    static boolean isGeometryOutputFamily(String name) {
        return isEntityLike(name) || isParticleLike(name) || isWeatherFamily(name) || isHostLayoutFamily(name)
                || isSkyFamily(name) || isCloudFamily(name)
                || name.equals("gbuffers_terrain") || name.equals("gbuffers_water");
    }

    /** The program whose lane {@code name} runs on: a shadow terrain program on shadow's; any other on its own. */
    public static String laneProgram(String name) {
        return isShadowTerrain(name) ? "shadow" : name;
    }

    /**
     * Iris's per-layer terrain programs for layers VulkanMod does not draw apart: it builds solid blocks into
     * cutout_mipped, so gbuffers_terrain and shadow_cutout (or shadow) draw both.
     */
    private static final Set<String> MERGED_LAYER_PROGRAMS = Set.of(
            "gbuffers_terrain_solid", "gbuffers_terrain_cutout", "shadow_solid");

    public static boolean isMergedLayerProgram(String name) {
        return MERGED_LAYER_PROGRAMS.contains(name);
    }

    public static UniformRegistry.Stage stageFor(String name) {
        return forProgram(name).stage();
    }

    public static boolean isExecutableFamily(String name) {
        return forProgram(name).executable();
    }

    public static boolean isEntityLike(String name) {
        FamilyAdapterPlan.Family family = forProgram(name).family();
        return family == FamilyAdapterPlan.Family.ENTITY
                || family == FamilyAdapterPlan.Family.ENTITY_TRANSLUCENT
                || family == FamilyAdapterPlan.Family.SPIDER_EYES
                || family == FamilyAdapterPlan.Family.BLOCK
                || family == FamilyAdapterPlan.Family.BLOCK_TRANSLUCENT
                || family == FamilyAdapterPlan.Family.DAMAGED_BLOCK
                || family == FamilyAdapterPlan.Family.HAND
                || family == FamilyAdapterPlan.Family.HAND_WATER;
    }

    /** The programs that draw into the shadow map: shadow (terrain), shadow_entities and the shadow lanes. */
    public static boolean isShadowFamily(String name) {
        return forProgram(name).stage().shadowPass();
    }

    /** A shadow program on the entity layout (Iris ProgramId.ShadowEntities and ShadowBlock). */
    public static boolean isShadowEntityFamily(String name) {
        FamilyAdapterPlan.Family family = forProgram(name).family();
        return family == FamilyAdapterPlan.Family.SHADOW_ENTITIES || family == FamilyAdapterPlan.Family.SHADOW_BLOCK;
    }

    /** A shadow-pass key drawn on the host's own layout. */
    public static boolean isShadowLaneFamily(String name) {
        FamilyAdapterPlan plan = forProgram(name);
        return plan.stage().shadowPass() && plan.vertexContract().hostLayout();
    }

    /**
     * Whether Iris's EntityPatcher applies, as on shadow text's GLYPH format: the ids are attributes the host
     * mesh lacks, so they read 0. Other host-layout lanes read the draw's entity id and -1 for blockEntityId.
     */
    public static boolean readsEntityIdsPerVertex(String name) {
        return forProgram(name).family() == FamilyAdapterPlan.Family.SHADOW_TEXT;
    }

    public static boolean isParticleLike(String name) {
        FamilyAdapterPlan.Family family = forProgram(name).family();
        return family == FamilyAdapterPlan.Family.PARTICLE
                || family == FamilyAdapterPlan.Family.PARTICLE_TRANSLUCENT;
    }

    public static boolean isWeatherFamily(String name) {
        return forProgram(name).family() == FamilyAdapterPlan.Family.WEATHER;
    }

    public static boolean isGlintFamily(String name) {
        return forProgram(name).family() == FamilyAdapterPlan.Family.GLINT;
    }

    /** Lanes whose pack program runs on a host vertex layout Iris also draws natively. */
    public static boolean isHostLayoutFamily(String name) {
        return forProgram(name).vertexContract().hostLayout();
    }

    /** The lane label a host-layout program's deviations carry (LINE, GLINT, SHADOW_LINES, ...), or null. */
    public static String hostLayoutLane(String name) {
        FamilyAdapterPlan plan = forProgram(name);
        return plan.vertexContract().hostLayout() ? plan.family().name() : null;
    }

    /** Every host-layout lane label. */
    public static Set<String> hostLayoutLanes() {
        return CONTRACTS.values().stream().filter(plan -> plan.vertexContract().hostLayout())
                .map(plan -> plan.family().name())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    public static boolean isSkyFamily(String name) {
        return forProgram(name).family() == FamilyAdapterPlan.Family.SKY;
    }

    public static boolean isCloudFamily(String name) {
        return forProgram(name).family() == FamilyAdapterPlan.Family.CLOUD;
    }

    public static boolean isWorldEntityFamily(String name) {
        FamilyAdapterPlan.Family family = forProgram(name).family();
        return family == FamilyAdapterPlan.Family.ENTITY
                || family == FamilyAdapterPlan.Family.ENTITY_TRANSLUCENT
                || family == FamilyAdapterPlan.Family.SPIDER_EYES;
    }

    public static boolean isBlockFamily(String name) {
        FamilyAdapterPlan.Family family = forProgram(name).family();
        return family == FamilyAdapterPlan.Family.BLOCK
                || family == FamilyAdapterPlan.Family.BLOCK_TRANSLUCENT
                || family == FamilyAdapterPlan.Family.DAMAGED_BLOCK;
    }

    public static boolean isHandFamily(String name) {
        FamilyAdapterPlan.Family family = forProgram(name).family();
        return family == FamilyAdapterPlan.Family.HAND
                || family == FamilyAdapterPlan.Family.HAND_WATER;
    }

    private static FamilyAdapterPlan plan(
            String name,
            FamilyAdapterPlan.Family family,
            UniformRegistry.Stage stage,
            FamilyAdapterPlan.VertexContract contract
    ) {
        return new FamilyAdapterPlan(name, family, stage, contract, true, List.of());
    }

    private static FamilyAdapterPlan unsupported(String name, FamilyAdapterPlan.Family family) {
        return new FamilyAdapterPlan(name, family, UniformRegistry.Stage.POST,
                FamilyAdapterPlan.VertexContract.HOST_FALLBACK, false,
                List.of("FAMILY_LANE_UNSUPPORTED"));
    }
}
