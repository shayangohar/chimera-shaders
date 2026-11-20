// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.mixin;

import net.chimera.gui.ShaderPackScreen;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hides the HUD under the shader pack screen, as Iris does, since the settings leave the world unblurred. */
@Mixin(Gui.class)
public abstract class ChimeraGuiHudMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void chimera$hideForShaderPackScreen(GuiGraphics graphics, DeltaTracker delta, CallbackInfo callback) {
        if (Minecraft.getInstance().screen instanceof ShaderPackScreen) {
            callback.cancel();
        }
    }
}
