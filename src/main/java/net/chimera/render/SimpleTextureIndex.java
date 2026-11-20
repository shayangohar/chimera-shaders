// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import net.chimera.mixin.ChimeraTextureManagerAccessor;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The live TextureManager's resource-backed SimpleTextures, so draw-observed albedo images can be matched to their
 * resources by Vulkan image identity. Read-only, so safe while material map builds are pumped.
 */
public final class SimpleTextureIndex {
    private SimpleTextureIndex() {}

    public static List<MaterialMapOwner.SimpleEntry> snapshot(TextureManager manager) {
        List<MaterialMapOwner.SimpleEntry> entries = new ArrayList<>();
        if (!(manager instanceof ChimeraTextureManagerAccessor accessor)) return entries;
        for (Map.Entry<ResourceLocation, AbstractTexture> registered
                : accessor.chimera$texturesByPath().entrySet()) {
            if (!(registered.getValue() instanceof SimpleTexture simple)) continue;
            var image = ChimeraTextures.image(simple);
            if (image == null) continue;
            // a SimpleTexture registers under the resource it loads
            ResourceLocation resourceId = registered.getKey();
            entries.add(new MaterialMapOwner.SimpleEntry(image,
                    resourceId.getNamespace(), relativeSpritePath(resourceId.getPath())));
        }
        return entries;
    }

    /**
     * {@code textures/entity/creeper.png} to {@code entity/creeper}; a path outside {@code textures/} keeps its
     * prefix for the CIT rule.
     */
    static String relativeSpritePath(String resourcePath) {
        String path = resourcePath.startsWith("textures/")
                ? resourcePath.substring("textures/".length())
                : resourcePath;
        int extension = path.lastIndexOf('.');
        return extension > path.lastIndexOf('/')
                ? path.substring(0, extension)
                : path;
    }
}
