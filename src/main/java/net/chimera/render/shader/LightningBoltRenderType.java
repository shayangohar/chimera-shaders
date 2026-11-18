// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render.shader;

import net.minecraft.client.renderer.RenderType;

/**
 * RenderType.lightning() as LightningBoltRenderer draws the bolt (ChimeraLightningBoltMixin), wrapped as Iris
 * wraps it in its LightningRenderStateShard. While it draws, the lightning lanes read the pack's
 * lightning_bolt id. Other users of RenderType.lightning() keep 0, and dragon rays draw with their own render
 * type.
 */
public final class LightningBoltRenderType extends RenderType {
    public static final RenderType INSTANCE = new LightningBoltRenderType(RenderType.lightning());

    private final RenderType host;

    private LightningBoltRenderType(RenderType lightning) {
        super("chimera_lightning_bolt", lightning.format(), lightning.mode(), lightning.bufferSize(),
                lightning.affectsCrumbling(), lightning.sortOnUpload(), () -> {
                    lightning.setupRenderState();
                    ChimeraEntityBridge.setLightningBoltDraw(true);
                }, () -> {
                    ChimeraEntityBridge.setLightningBoltDraw(false);
                    lightning.clearRenderState();
                });
        this.host = lightning;
    }

    /**
     * {@code type}, or for the bolt's type the RenderType.lightning() it draws as, whose shader and
     * transparency are the bolt's; Iris's InnerWrappedRenderType answers with its wrapped type's.
     *
     * Read as a non-composite type, the bolt would be neither held for its lightning lane nor ordered as an
     * Iris GENERAL_TRANSPARENT batch, and would draw with the entities before the deferred passes.
     */
    public static RenderType unwrap(RenderType type) {
        return type instanceof LightningBoltRenderType bolt ? bolt.host : type;
    }
}
