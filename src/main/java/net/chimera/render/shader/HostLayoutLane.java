// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render.shader;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.chimera.render.ChimeraHandRenderer;
import net.chimera.shaderpack.PackRenderingPhase;

import java.util.Arrays;
import java.util.List;

/**
 * The Iris shader keys served on the host's own vertex layout (Iris MixinGameRenderer's overrides). A lane
 * swaps only the program and targets at BufferUploader.drawWithShader; the host mesh and raster state stay.
 * Depth-only main-pass shaders Iris maps (water mask, dragon rays' depth) write no colour, so they need no
 * main-pass lane.
 */
public enum HostLayoutLane {
    /** Iris WEATHER: rain and snow. */
    WEATHER(ChimeraEntityBridge.Family.WEATHER, "gbuffers_weather", DefaultVertexFormat.PARTICLE,
            PackRenderingPhase.RAIN_SNOW, Pass.WEATHER, AlphaTest.ONE_TENTH, "particle"),
    /** Iris LINES; the block outline, written outside every Iris wrapper, reports NONE. */
    LINE(ChimeraEntityBridge.Family.LINE, "gbuffers_line", DefaultVertexFormat.POSITION_COLOR_NORMAL,
            PackRenderingPhase.NONE, Pass.WORLD, AlphaTest.OFF, "rendertype_lines"),
    /** Iris GLINT; Iris draws world glint inside the entity phase. */
    GLINT(ChimeraEntityBridge.Family.GLINT, "gbuffers_armor_glint", DefaultVertexFormat.POSITION_TEX,
            PackRenderingPhase.ENTITIES, Pass.WORLD, AlphaTest.NON_ZERO, Shaders.GLINT),
    HAND_GLINT(ChimeraEntityBridge.Family.HAND_GLINT, "gbuffers_armor_glint", DefaultVertexFormat.POSITION_TEX,
            PackRenderingPhase.HAND_SOLID, Pass.HAND, AlphaTest.NON_ZERO, Shaders.GLINT),
    /** Iris LIGHTNING: lightning bolts and dragon death rays. */
    LIGHTNING(ChimeraEntityBridge.Family.LIGHTNING, "gbuffers_lightning", DefaultVertexFormat.POSITION_COLOR,
            PackRenderingPhase.ENTITIES, Pass.WORLD, AlphaTest.OFF, "rendertype_lightning"),
    /** Iris LEASH: leads, drawn with gbuffers_basic. */
    LEASH(ChimeraEntityBridge.Family.LEASH, "gbuffers_basic", DefaultVertexFormat.POSITION_COLOR_LIGHTMAP,
            PackRenderingPhase.ENTITIES, Pass.WORLD, AlphaTest.OFF, "rendertype_leash"),
    /** Iris TEXT_BG, with gbuffers_entities_translucent's chain. */
    TEXT_BG(ChimeraEntityBridge.Family.TEXT_BG, "text_bg", DefaultVertexFormat.POSITION_COLOR_LIGHTMAP,
            PackRenderingPhase.ENTITIES, Pass.WORLD, AlphaTest.ONE_TENTH, Shaders.TEXT_BACKGROUND),
    // Iris TEXTURED and TEXTURED_COLOR outside the sky; the world border is vanilla's one such draw
    TEXTURED(ChimeraEntityBridge.Family.TEXTURED, "gbuffers_textured", DefaultVertexFormat.POSITION_TEX,
            PackRenderingPhase.WORLD_BORDER, Pass.WORLD, AlphaTest.NON_ZERO, "position_tex"),
    TEXTURED_COLOR(ChimeraEntityBridge.Family.TEXTURED_COLOR, "textured_color", DefaultVertexFormat.POSITION_TEX_COLOR,
            PackRenderingPhase.WORLD_BORDER, Pass.WORLD, AlphaTest.ONE_TENTH, "position_tex_color"),
    // shadow lanes report ENTITIES, the phase Iris ShadowRenderer holds for entities
    // glint has no shadow lane: Iris keys it to GLINT in every pass, which would write the gbuffers, so it is
    // dropped (ChimeraEntityBridge.dropsHostDraw)
    /** Iris SHADOW_LINES. */
    SHADOW_LINES(ChimeraEntityBridge.Family.SHADOW_LINES, "shadow_lines", DefaultVertexFormat.POSITION_COLOR_NORMAL,
            PackRenderingPhase.ENTITIES, Pass.SHADOW, AlphaTest.OFF, "rendertype_lines"),
    /** Iris SHADOW_LEASH. */
    SHADOW_LEASH(ChimeraEntityBridge.Family.SHADOW_LEASH, "shadow_leash", DefaultVertexFormat.POSITION_COLOR_LIGHTMAP,
            PackRenderingPhase.ENTITIES, Pass.SHADOW, AlphaTest.OFF, "rendertype_leash"),
    /** Iris SHADOW_LIGHTNING. */
    SHADOW_LIGHTNING(ChimeraEntityBridge.Family.SHADOW_LIGHTNING, "shadow_lightning",
            DefaultVertexFormat.POSITION_COLOR, PackRenderingPhase.ENTITIES, Pass.SHADOW, AlphaTest.OFF,
            "rendertype_lightning"),
    /** Iris SHADOW_TEXT and SHADOW_TEXT_INTENSITY. */
    SHADOW_TEXT(ChimeraEntityBridge.Family.SHADOW_TEXT, "shadow_text", DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP,
            PackRenderingPhase.ENTITIES, Pass.SHADOW, AlphaTest.NON_ZERO, "rendertype_text",
            "rendertype_text_see_through", "rendertype_text_intensity", "rendertype_text_intensity_see_through"),
    /** Iris SHADOW_TEXT_BG. */
    SHADOW_TEXT_BG(ChimeraEntityBridge.Family.SHADOW_TEXT_BG, "shadow_text_bg",
            DefaultVertexFormat.POSITION_COLOR_LIGHTMAP, PackRenderingPhase.ENTITIES, Pass.SHADOW,
            AlphaTest.NON_ZERO, Shaders.TEXT_BACKGROUND),
    /** Iris SHADOW_BASIC: the boat water mask, since every shadow-pass draw writes the shadow map. */
    SHADOW_BASIC(ChimeraEntityBridge.Family.SHADOW_BASIC, "shadow_basic", DefaultVertexFormat.POSITION,
            PackRenderingPhase.ENTITIES, Pass.SHADOW, AlphaTest.OFF, "rendertype_water_mask"),
    /** Iris SHADOW_TEX and SHADOW_TEX_COLOR. */
    SHADOW_TEX(ChimeraEntityBridge.Family.SHADOW_TEX, "shadow_tex", DefaultVertexFormat.POSITION_TEX,
            PackRenderingPhase.ENTITIES, Pass.SHADOW, AlphaTest.NON_ZERO, "position_tex"),
    SHADOW_TEX_COLOR(ChimeraEntityBridge.Family.SHADOW_TEX_COLOR, "shadow_tex_color",
            DefaultVertexFormat.POSITION_TEX_COLOR, PackRenderingPhase.ENTITIES, Pass.SHADOW, AlphaTest.ONE_TENTH,
            "position_tex_color");

    private static final class Shaders {
        static final String[] GLINT = {"rendertype_glint", "rendertype_glint_translucent", "rendertype_entity_glint",
                "rendertype_entity_glint_direct", "rendertype_armor_entity_glint"};
        static final String[] TEXT_BACKGROUND = {"rendertype_text_background",
                "rendertype_text_background_see_through"};
    }

    public enum Pass {
        WORLD,
        /** Inside LevelRenderer.renderSnowAndRain. */
        WEATHER,
        /** Only while the hand renders. */
        HAND,
        /** The shadow caster window (ChimeraShadowCasters). */
        SHADOW
    }

    private enum AlphaTest {
        OFF(0.0F),
        NON_ZERO(IrisShaderKeys.NON_ZERO_ALPHA),
        ONE_TENTH(IrisShaderKeys.ONE_TENTH_ALPHA);

        private final float reference;

        AlphaTest(float reference) {
            this.reference = reference;
        }
    }

    private final ChimeraEntityBridge.Family family;
    private final String program;
    private final VertexFormat format;
    private final PackRenderingPhase phase;
    private final Pass pass;
    private final AlphaTest alphaTest;
    private final List<String> shaders;

    HostLayoutLane(ChimeraEntityBridge.Family family, String program, VertexFormat format,
                   PackRenderingPhase phase, Pass pass, AlphaTest alphaTest, String... shaders) {
        this.family = family;
        this.program = program;
        this.format = format;
        this.phase = phase;
        this.pass = pass;
        this.alphaTest = alphaTest;
        this.shaders = List.of(shaders);
    }

    public ChimeraEntityBridge.Family family() {
        return family;
    }

    /** Resolved through the Iris fallback chain. */
    public String program() {
        return program;
    }

    public VertexFormat format() {
        return format;
    }

    public List<String> shaders() {
        return shaders;
    }

    public Pass pass() {
        return pass;
    }

    /**
     * The Iris renderStage of this lane's draws. A world lane's held batch draws in its write phase instead
     * (ChimeraEntityBridge.hostLaneWritePhase).
     */
    public PackRenderingPhase phase() {
        if (pass == Pass.HAND) {
            return ChimeraHandRenderer.translucentPhase()
                    ? PackRenderingPhase.HAND_TRANSLUCENT : PackRenderingPhase.HAND_SOLID;
        }
        return phase;
    }

    /** 0 with no alpha test. */
    public float alphaReference() {
        return alphaTest.reference;
    }

    /** A format without UV0 reads Iris's white pixel as albedo (IrisSamplers.addLevelSamplers). */
    public boolean whiteAlbedo() {
        return !format.contains(VertexFormatElement.UV0);
    }

    /** A format without UV2 reads Iris's white pixel as lightmap (IrisSamplers.addLevelSamplers). */
    public boolean whiteLightmap() {
        return !format.contains(VertexFormatElement.UV2);
    }

    /** Null when the draw keeps its host program. */
    public static HostLayoutLane forDraw(String shader, VertexFormat format, Pass pass) {
        for (HostLayoutLane lane : values()) {
            if (lane.pass == pass && lane.format == format && lane.shaders.contains(shader)) {
                return lane;
            }
        }
        return null;
    }

    /** Null for a family drawn another way. */
    public static HostLayoutLane of(ChimeraEntityBridge.Family family) {
        for (HostLayoutLane lane : values()) {
            if (lane.family == family) return lane;
        }
        return null;
    }

    /** World lane first. */
    public static List<HostLayoutLane> forProgram(String program) {
        return Arrays.stream(values()).filter(lane -> lane.program.equals(program)).toList();
    }
}
