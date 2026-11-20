// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render.shader;

import net.minecraft.client.renderer.RenderType;

/**
 * The bolt's RenderType.lightning(), wrapped as Iris's LightningRenderStateShard does, so only the bolt reads the
 * pack's lightning_bolt id.
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
     * The wrapped type, as Iris's InnerWrappedRenderType answers. Read as non-composite, the bolt would miss its
     * lane and draw before the deferred passes.
     */
    public static RenderType unwrap(RenderType type) {
        return type instanceof LightningBoltRenderType bolt ? bolt.host : type;
    }
}
