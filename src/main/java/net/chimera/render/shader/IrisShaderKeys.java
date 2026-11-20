// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render.shader;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.render.shader.ChimeraEntityBridge.Family;

import java.util.Map;
import java.util.Set;

/**
 * How Iris 1.21.1's MixinGameRenderer keys a host draw: the family program it draws with, and the AlphaTest
 * threshold the program reads per draw as iris_currentAlphaTest.
 */
public final class IrisShaderKeys {
    /** Iris AlphaTests.ONE_TENTH_ALPHA: GREATER 0.1. */
    static final float ONE_TENTH_ALPHA = 0.1F;
    /** Iris AlphaTests.NON_ZERO_ALPHA: GREATER 0.0001. */
    static final float NON_ZERO_ALPHA = 0.0001F;
    /** Iris AlphaTests.VERTEX_ALPHA; PackAlphaTestPlan reads a negative reference as this test. */
    static final float VERTEX_ALPHA = -1.0F;

    /** Each constant lists Iris's key in an entity; in a block entity; in the shadow pass. */
    enum EntityShader {
        /** ENTITIES_SOLID_DIFFUSE or ENTITIES_CUTOUT_DIFFUSE; BLOCK_ENTITY_DIFFUSE; SHADOW_BLOCK. */
        MODEL,
        /** ENTITIES_CUTOUT; BLOCK_ENTITY; SHADOW_ENTITIES_CUTOUT. */
        OVERLAY,
        /** ENTITIES_TRANSLUCENT; BE_TRANSLUCENT; SHADOW_BLOCK. */
        TRANSLUCENT,
        /** ENTITIES_EYES; BLOCK_ENTITY; SHADOW_ENTITIES_CUTOUT. */
        EYES,
        /** ENTITIES_EYES_TRANS; BLOCK_ENTITY; SHADOW_BLOCK. */
        EMISSIVE,
        /** ENTITIES_ALPHA (the dying dragon's dissolve) in either; Iris keeps no shadow key for it. */
        ALPHA
    }

    /** Keyed by shader, as Iris overrides shader getters, so mods' render types on these shaders follow. */
    private static final Map<String, EntityShader> ENTITY_SHADERS = Map.ofEntries(
            Map.entry("rendertype_entity_solid", EntityShader.MODEL),
            Map.entry("rendertype_entity_cutout", EntityShader.MODEL),
            Map.entry("rendertype_entity_cutout_no_cull", EntityShader.MODEL),
            Map.entry("rendertype_entity_cutout_no_cull_z_offset", EntityShader.MODEL),
            Map.entry("rendertype_entity_decal", EntityShader.MODEL),
            Map.entry("rendertype_entity_smooth_cutout", EntityShader.MODEL),
            Map.entry("rendertype_armor_cutout_no_cull", EntityShader.MODEL),
            Map.entry("rendertype_energy_swirl", EntityShader.OVERLAY),
            Map.entry("rendertype_entity_shadow", EntityShader.OVERLAY),
            Map.entry("rendertype_entity_translucent", EntityShader.TRANSLUCENT),
            Map.entry("rendertype_entity_translucent_cull", EntityShader.TRANSLUCENT),
            Map.entry("rendertype_item_entity_translucent_cull", EntityShader.TRANSLUCENT),
            Map.entry("rendertype_breeze_wind", EntityShader.TRANSLUCENT),
            Map.entry("rendertype_entity_no_outline", EntityShader.TRANSLUCENT),
            Map.entry("rendertype_eyes", EntityShader.EYES),
            Map.entry("rendertype_entity_translucent_emissive", EntityShader.EMISSIVE),
            Map.entry("rendertype_entity_alpha", EntityShader.ALPHA));
    private static final String CRUMBLING_SHADER = "rendertype_crumbling";
    private static final String BEACON_BEAM_SHADER = "rendertype_beacon_beam";
    /** Chunk shaders Iris draws as MOVING_BLOCK when an entity or block entity uses them. */
    private static final Set<String> MOVING_BLOCK_SHADERS = Set.of("rendertype_solid", "rendertype_cutout_mipped",
            "rendertype_cutout", "rendertype_translucent", "rendertype_translucent_moving_block",
            "rendertype_tripwire");
    /** Iris keys these TEXT and TEXT_BE, or their _INTENSITY forms for TrueType glyphs; HAND_TEXT in the hand. */
    private static final Set<String> TEXT_SHADERS = Set.of("rendertype_text", "rendertype_text_see_through",
            "position_color_tex_lightmap", "rendertype_text_intensity", "rendertype_text_intensity_see_through");

    private IrisShaderKeys() {}

    /**
     * Crumbling and beacon beams batch on their own family whoever writes them, as Iris widens every BLOCK
     * buffer to TERRAIN. Null for other types, whose family follows the drawer (worldFamily).
     */
    static Family blockLayoutFamily(String shader, VertexFormat format) {
        if (format != DefaultVertexFormat.BLOCK) return null;
        if (CRUMBLING_SHADER.equals(shader)) return Family.DAMAGED_BLOCK;
        return BEACON_BEAM_SHADER.equals(shader) ? Family.BEACON_BEAM : null;
    }

    /** {@code context} is ENTITY or BLOCK; null when Iris keeps the draw on another program. */
    public static Family worldFamily(String shader, VertexFormat format, Family context) {
        if (shader == null) return null;
        boolean blockEntity = context == Family.BLOCK;
        if (format == DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP) {
            if (!TEXT_SHADERS.contains(shader)) return null;
            return blockEntity ? Family.BLOCK_TRANSLUCENT : Family.ENTITY_TRANSLUCENT;
        }
        if (format == DefaultVertexFormat.BLOCK) {
            return MOVING_BLOCK_SHADERS.contains(shader) ? Family.MOVING_BLOCK : null;
        }
        EntityShader key = format == DefaultVertexFormat.NEW_ENTITY ? ENTITY_SHADERS.get(shader) : null;
        return key == null ? null : entityFamily(key, blockEntity);
    }

    private static Family entityFamily(EntityShader key, boolean blockEntity) {
        return switch (key) {
            case MODEL, OVERLAY -> blockEntity ? Family.BLOCK : Family.ENTITY;
            case TRANSLUCENT -> blockEntity ? Family.BLOCK_TRANSLUCENT : Family.ENTITY_TRANSLUCENT;
            case EYES, EMISSIVE -> blockEntity ? Family.BLOCK : Family.SPIDER_EYES;
            case ALPHA -> Family.ENTITY;
        };
    }

    /** Null leaves the caster out of the shadow map. */
    public static Family shadowFamily(String shader, VertexFormat format, Family context) {
        if (format == DefaultVertexFormat.BLOCK) {
            return context != null && MOVING_BLOCK_SHADERS.contains(shader) ? Family.SHADOW_ENTITIES : null;
        }
        if (format != DefaultVertexFormat.NEW_ENTITY) return null;
        EntityShader key = shader == null ? null : ENTITY_SHADERS.get(shader);
        boolean blockModel = context == Family.BLOCK && key != null
                && key != EntityShader.OVERLAY && key != EntityShader.EYES && key != EntityShader.ALPHA;
        return blockModel ? Family.SHADOW_BLOCK : Family.SHADOW_ENTITIES;
    }

    /** Without a test, a flat item or glyph quad would write depth and gbuffer data across transparent texels. */
    public static float reference(String shader, Family family, boolean shadowCaster) {
        if (shadowCaster) return ONE_TENTH_ALPHA;
        return switch (family) {
            case SPIDER_EYES -> "rendertype_eyes".equals(shader) ? NON_ZERO_ALPHA : ONE_TENTH_ALPHA;
            case HAND, HAND_WATER -> isText(shader) ? NON_ZERO_ALPHA : ONE_TENTH_ALPHA;
            case ENTITY -> entityReference(shader);
            case BEACON_BEAM -> 0.0F;
            default -> ONE_TENTH_ALPHA;
        };
    }

    private static float entityReference(String shader) {
        if ("rendertype_entity_solid".equals(shader)) return 0.0F;
        return "rendertype_entity_alpha".equals(shader) ? VERTEX_ALPHA : ONE_TENTH_ALPHA;
    }

    private static boolean isText(String shader) {
        return shader != null && TEXT_SHADERS.contains(shader);
    }
}
