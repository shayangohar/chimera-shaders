package net.chimera.mixin;

import net.chimera.render.ChimeraRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells ChimeraRenderer when a screen opens or closes.
 *
 * Over a live level Chimera stays installed and the world renders with the pack behind every screen, the
 * pause menu's blur included. A screen without a level, such as a loading or transition screen, hands the
 * frame to the host, since the level teardown and swap around it break Chimera's image transitions. A level
 * unloading under an open screen also hands off to the host until a live level returns
 * (WorldRendererLevelMixin).
 */
@Mixin(Minecraft.class)
public abstract class ChimeraMinecraftMixin {

    @Inject(method = "setScreen", at = @At("HEAD"))
    private void chimera$onSetScreen(Screen screen, CallbackInfo ci) {
        if (!ChimeraRenderer.isReady()) {
            return;
        }

        ChimeraRenderer.setScreenOpen(screen != null);

        if (screen != null) {
            ChimeraRenderer.enterScreenMode();
        } else {
            ChimeraRenderer.exitScreenMode();
        }
    }
}
