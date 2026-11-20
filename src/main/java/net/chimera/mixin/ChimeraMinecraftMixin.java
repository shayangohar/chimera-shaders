// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.mixin;

import net.chimera.render.ChimeraRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells ChimeraRenderer when a screen opens or closes. */
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
        }
    }
}
