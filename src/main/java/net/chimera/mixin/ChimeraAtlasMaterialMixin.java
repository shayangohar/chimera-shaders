// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.mixin;

import net.chimera.render.ChimeraRenderer;
import net.chimera.render.ChimeraTextures;
import net.chimera.render.MaterialMapOwner;
import net.chimera.render.MaterialMapPixels;
import net.chimera.render.PackAtlasMipmaps;
import net.chimera.render.shader.AtlasSizes;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.world.inventory.InventoryMenu;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Reports each stitched atlas: its layout to the material map owner, its size to {@link AtlasSizes} for the
 * {@code atlasSize} uniform, and a restitched block atlas to {@link PackAtlasMipmaps}.
 *
 * At {@code TextureAtlas.upload}'s return the atlas is fully uploaded, so this only queues metadata with the
 * owner: atlas location, image, sprite coordinates and padding. Sprite origins are padded slot origins while
 * content sizes are logical, so the padding is recovered from the UV edge in atlas texels. Only square
 * padding is supported; a sprite whose X and Y padding differ is skipped with a warning. Decoding and GPU
 * allocation happen later, when the owner's builds are pumped. A failure here warns and leaves vanilla
 * rendering alone.
 */
@Mixin(TextureAtlas.class)
public abstract class ChimeraAtlasMaterialMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChimeraAtlasMaterialMixin.class);

    @Inject(method = "upload", at = @At("RETURN"), require = 1)
    private void chimera$noteAtlasUpload(SpriteLoader.Preparations preparations, CallbackInfo info) {
        try {
            TextureAtlas atlas = (TextureAtlas) (Object) this;
            VulkanImage image = ChimeraTextures.image(atlas);
            if (image == null) {
                LOGGER.warn("[chimera] material maps: atlas {} has no Vulkan image, skipping companions",
                        atlas.location());
                return;
            }
            AtlasSizes.note(image, preparations.width(), preparations.height(),
                    atlas.location().equals(InventoryMenu.BLOCK_ATLAS));
            PackAtlasMipmaps.noteAtlasUpload(atlas);
            var pass = ChimeraRenderer.getMainPass();
            if (pass == null) return;
            MaterialMapOwner owner = pass.materialMaps();
            if (owner == null) return;
            List<MaterialMapOwner.AtlasSprite> sprites =
                    new ArrayList<>(preparations.regions().size());
            for (var entry : preparations.regions().entrySet()) {
                var id = entry.getKey();
                var sprite = entry.getValue();
                int padX = MaterialMapPixels.recoverPad(
                        sprite.getU0(), preparations.width(), sprite.getX());
                int padY = MaterialMapPixels.recoverPad(
                        sprite.getV0(), preparations.height(), sprite.getY());
                if (padX != padY) {
                    LOGGER.warn("[chimera] material maps: skipping {} with asymmetric padding {}x{}",
                            id, padX, padY);
                    continue;
                }
                sprites.add(new MaterialMapOwner.AtlasSprite(id.toString(), id.getNamespace(),
                        id.getPath(), sprite.getX(), sprite.getY(),
                        sprite.contents().width(), sprite.contents().height(), padX, padY));
            }
            owner.noteAtlasUpload(new MaterialMapOwner.AtlasUpload(atlas.location().toString(),
                    preparations.width(), preparations.height(), preparations.mipLevel() + 1,
                    List.copyOf(sprites)), image);
        } catch (RuntimeException failure) {
            LOGGER.warn("[chimera] material maps: atlas observation failed", failure);
        }
    }

    /**
     * Advances animated material maps with this atlas, where the game ticks every sprite animation once per
     * client tick. Only metadata changes here; the pump writes the changed slots.
     */
    @Inject(method = "cycleAnimationFrames", at = @At("RETURN"), require = 1)
    private void chimera$tickMaterialAnimations(CallbackInfo info) {
        try {
            var pass = ChimeraRenderer.getMainPass();
            if (pass == null) return;
            MaterialMapOwner owner = pass.materialMaps();
            if (owner == null) return;
            owner.tickAtlas(((TextureAtlas) (Object) this).location().toString());
        } catch (RuntimeException failure) {
            LOGGER.warn("[chimera] material maps: animation tick failed", failure);
        }
    }
}
