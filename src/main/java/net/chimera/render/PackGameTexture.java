// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.vulkanmod.vulkan.texture.VulkanImage;

/**
 * A namespaced pack texture, read as Iris's CustomTextureManager reads CustomTextureData.ResourceData: the
 * game texture at that location, such as an atlas, a dynamic texture or a resource file the TextureManager
 * loads on first ask. It is looked up at every bind, so a resource reload never leaves a stale image.
 *
 * A path ending in a PBR suffix ({@code _n}, {@code _s}) names the normal or specular map of the texture
 * without the suffix, or the flat map when it has none, as PBRTextureManager's default holder does. A texture
 * the game cannot load is its missing texture.
 */
final class PackGameTexture {
    /**
     * Where LightTexture registers the game's lightmap ({@code TextureManager.register("light_map", ...)},
     * the first of that name). Iris reads {@code minecraft:dynamic/lightmap_1} and
     * {@code minecraft:dynamic/light_map_1} as the game renderer's light texture (ShaderPack.readTexture,
     * CustomTextureData.LightmapMarker); Pixel Perfect binds the first.
     *
     * Iris asks the game renderer for its light texture, so this location is that texture only while no mod
     * registers a "light_map" texture before GameRenderer makes its LightTexture.
     */
    static final String LIGHTMAP_PATH = "dynamic/light_map_1";

    private PackGameTexture() {
    }

    /** A texture location without its PBR suffix, and the map the suffix names; null for the texture itself. */
    record Source(String namespace, String path, MaterialMapOwner.MaterialMapKind map) {
        /**
         * Iris's split of {@code namespace:path} (ShaderPack.parseCustomTexture): the first two parts,
         * then PBRType.fromFileLocation on the path without its extension. Null when not namespaced.
         * Either lightmap name reads the game's lightmap.
         */
        static Source parse(String source) {
            String[] parts = source == null ? new String[0] : source.trim().split(":");
            if (parts.length < 2) return null;
            if (parts[0].equals("minecraft") && (parts[1].equals("dynamic/lightmap_1")
                    || parts[1].equals(LIGHTMAP_PATH))) {
                return new Source("minecraft", LIGHTMAP_PATH, null);
            }
            String path = parts[1];
            int dot = path.lastIndexOf('.');
            int extension = dot > path.lastIndexOf('/') ? dot : path.length();
            String stem = path.substring(0, extension);
            for (MaterialMapOwner.MaterialMapKind kind : MaterialMapOwner.MaterialMapKind.values()) {
                if (stem.endsWith(kind.suffix())) {
                    String base = stem.substring(0, stem.length() - kind.suffix().length());
                    return new Source(parts[0], base + path.substring(extension), kind);
                }
            }
            return new Source(parts[0], path, null);
        }
    }

    /**
     * The image {@code source} names now, or null when no image or flat map can stand for it yet. A pack can
     * load with the renderer, before Minecraft has a TextureManager; Iris's TextureWrapper looks only at bind
     * time.
     */
    static VulkanImage image(Source source, MaterialMapOwner materials) {
        Minecraft client = Minecraft.getInstance();
        TextureManager textures = client == null ? null : client.getTextureManager();
        if (textures == null) return null;
        AbstractTexture texture = ResourceLocation.isValidNamespace(source.namespace())
                && ResourceLocation.isValidPath(source.path())
                ? textures.getTexture(ResourceLocation.fromNamespaceAndPath(source.namespace(), source.path()))
                : null;
        VulkanImage base = ChimeraTextures.image(texture);
        if (base == null) base = ChimeraTextures.image(MissingTextureAtlasSprite.getTexture());
        if (source.map() == null) return base;
        if (materials == null) return null;
        String sampler = source.map().sampler();
        ChimeraTextureBindingState.Snapshot companion = materials.companionFor(base, sampler);
        if (companion != null) return companion.image();
        // Iris loads a texture's PBR holder on first use; a simple texture's maps build at the next pump
        materials.noteDrawAlbedo(base);
        return materials.image(sampler);
    }
}
