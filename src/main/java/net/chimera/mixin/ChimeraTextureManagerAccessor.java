// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.mixin;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** TextureManager's textures by location, for reading only. */
@Mixin(TextureManager.class)
public interface ChimeraTextureManagerAccessor {
    @Accessor("byPath")
    Map<ResourceLocation, AbstractTexture> chimera$texturesByPath();
}
