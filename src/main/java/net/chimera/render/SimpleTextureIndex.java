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
 * The live {@code TextureManager}'s simple textures, so draw-observed albedo images can be matched to their
 * resources by Vulkan image identity.
 *
 * Only resource-backed {@code SimpleTexture}s qualify: atlases, dynamic textures, lightmaps, overlays and
 * textures without a Vulkan image never match, so no material maps are made up for them. Read-only, so safe
 * to call while material map builds are pumped.
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
     * The atlas-style sprite path ({@code entity/creeper}) that material sibling lookup works on, from a simple
     * texture's full file path ({@code textures/entity/creeper.png}): the leading {@code textures/} and the file
     * extension come off. A path outside {@code textures/} keeps its prefix for the CIT rule.
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
