// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.mixin;

import net.chimera.render.ChimeraRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.vulkanmod.render.chunk.WorldRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Follows level swaps under an open screen, which setScreen never sees. While the level is null the host
 * renders; the new level reinstalls Chimera with the pack's dimension variant.
 */
@Mixin(value = WorldRenderer.class, remap = false)
public abstract class WorldRendererLevelMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("chimera");
    private static final boolean TRACE = ChimeraRenderer.debugFlag("chimera.traceTransitions");

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void chimera$onSetLevel(ClientLevel level, CallbackInfo ci) {
        if (!ChimeraRenderer.isReady()) {
            return;
        }

        if (TRACE) {
            LOGGER.info("[chimera] setLevel {}", level == null ? "null" : "live");
        }

        if (level == null) {
            ChimeraRenderer.onLevelUnloaded();
        } else {
            ChimeraRenderer.onLevelLoaded(level);
        }
    }
}
