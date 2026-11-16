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
 * Follows level swaps under an open screen, which setScreen never sees.
 *
 * On a respawn or a nether portal the screen opens while the old level is live, then the level goes null and a
 * new one arrives. While it is null the host renders; Chimera reinstalls once a live level exists again, and the
 * new level selects the pack's dimension variant.
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
