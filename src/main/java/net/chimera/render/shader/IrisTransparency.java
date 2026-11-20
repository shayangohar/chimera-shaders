// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render.shader;

import net.chimera.mixin.ChimeraCompositeRenderTypeAccessor;
import net.chimera.mixin.ChimeraCompositeStateAccessor;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;

/** Iris's batched-entity transparency of a render type (batchedentityrendering TransparencyType). */
public enum IrisTransparency {
    OPAQUE, OPAQUE_DECAL, GENERAL_TRANSPARENT, DECAL, WATER_MASK, LINES;

    /** As Iris's MixinSheets, then MixinCompositeRenderType, set it; GENERAL_TRANSPARENT for a non-composite type. */
    public static IrisTransparency of(RenderType type) {
        RenderType drawn = LightningBoltRenderType.unwrap(type);
        if (drawn == Sheets.armorTrimsSheet(false)) return OPAQUE_DECAL;
        if (drawn == RenderType.textBackground() || drawn == RenderType.textBackgroundSeeThrough()) return OPAQUE;
        if (drawn == RenderType.dragonRaysDepth()) return DECAL;
        if (!(drawn instanceof ChimeraCompositeRenderTypeAccessor composite)) return GENERAL_TRANSPARENT;
        var state = (ChimeraCompositeStateAccessor) (Object) composite.chimera$state();
        return ofComposite(drawn.name, state.chimera$transparencyState(), state.chimera$depthTestState());
    }

    static IrisTransparency ofComposite(String name, RenderStateShard.TransparencyStateShard transparency,
                                        RenderStateShard.DepthTestStateShard depthTest) {
        if ("water_mask".equals(name) || depthTest == RenderStateShard.NO_DEPTH_TEST) return WATER_MASK;
        if ("lines".equals(name)) return LINES;
        if (transparency == RenderStateShard.NO_TRANSPARENCY) return OPAQUE;
        if (transparency == RenderStateShard.GLINT_TRANSPARENCY
                || transparency == RenderStateShard.CRUMBLING_TRANSPARENCY) {
            return DECAL;
        }
        return GENERAL_TRANSPARENT;
    }

    /** With separateEntityDraws, whether this kind draws before the deferred passes (MixinLevelRenderer). */
    public boolean drawsBeforeDeferred() {
        return this == OPAQUE || this == OPAQUE_DECAL || this == WATER_MASK;
    }
}
