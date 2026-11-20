// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.mixin;

import net.chimera.render.shader.ChimeraTerrainPipelines;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Removes directional face shading while the pack lights faces itself, as Iris's MixinClientLevel does.
 * VulkanMod's terrain and fluid meshing reach it through RenderRegion.getShade; both overloads change.
 */
@Mixin(ClientLevel.class)
public abstract class ChimeraClientLevelShadeMixin {
    @ModifyVariable(method = "getShade", at = @At("HEAD"), argsOnly = true)
    private boolean chimera$packDirectionalShading(boolean shaded) {
        return shaded && !ChimeraTerrainPipelines.directionalShadingDisabled();
    }
}
