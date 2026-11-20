// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.inventory.InventoryMenu;
import net.vulkanmod.render.texture.SpriteUtil;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gives the block atlas Iris's mip levels (AtlasMipBlend) while a pack is installed, and VulkanMod's otherwise.
 * Levels are rewritten in SpriteContents.byMipLevel, since animations upload from there. Pack lifecycle only
 * records the wanted state and the frame boundary applies it, so the atlas uploads at most once. Render thread
 * only.
 */
public final class PackAtlasMipmaps {
    private static final Logger LOGGER = LoggerFactory.getLogger(PackAtlasMipmaps.class);
    /** The block atlas's levels: VulkanMod's, the pack's, or a mix a failed swap left. */
    private static final AtlasLevelSwap<SpriteContents> LEVELS = new AtlasLevelSwap<>();
    private static TextureAtlas atlas;
    private static boolean wanted;

    private PackAtlasMipmaps() {}

    /** Whether an installed pack's terrain directives are in force; a swap that failed may run again. */
    public static void setPackInstalled(boolean installed) {
        wanted = installed;
        LEVELS.retry();
    }

    /** A stitched atlas finished uploading; the block atlas's sprites now carry VulkanMod's levels. */
    public static void noteAtlasUpload(TextureAtlas uploaded) {
        if (!InventoryMenu.BLOCK_ATLAS.equals(uploaded.location())) return;
        atlas = uploaded;
        LEVELS.reset();
    }

    /** Applies the wanted levels at the frame boundary. Never throws; a failed swap retries at the next pack change. */
    public static void reconcile() {
        if (atlas == null || !LEVELS.due(wanted)) return;
        long start = System.nanoTime();
        try {
            int sprites = LEVELS.swap(wanted, atlas.getTextures().values().stream()
                    .map(TextureAtlasSprite::contents).toList(), PackAtlasMipmaps::levels, PackAtlasMipmaps::upload);
            LOGGER.debug("[chimera] atlas mipmaps: {} for {} sprites ({} ms)",
                    wanted ? "alpha-weighted, as Iris" : "VulkanMod's restored",
                    sprites, (System.nanoTime() - start) / 1_000_000);
        } catch (RuntimeException failure) {
            LOGGER.warn("[chimera] pack atlas mipmaps: {} failed", wanted ? "rewrite" : "restore", failure);
        }
    }

    /** A sprite's levels to rewrite: an RGBA sprite with mips; null for any other. */
    private static AtlasLevelSwap.Levels levels(SpriteContents contents) {
        NativeImage[] images = contents.byMipLevel;
        return images.length < 2 || images[0].format() != NativeImage.Format.RGBA ? null : new SpriteLevels(images);
    }

    /** A sprite's NativeImage levels, read and written as ABGR texels. */
    private record SpriteLevels(NativeImage[] images) implements AtlasLevelSwap.Levels {
        @Override
        public int count() {
            return images.length;
        }

        @Override
        public int width(int level) {
            return images[level].getWidth();
        }

        @Override
        public int height(int level) {
            return images[level].getHeight();
        }

        @Override
        public int[] read(int level) {
            return pixels(images[level]);
        }

        @Override
        public void write(int level, int[] abgr) {
            store(images[level], abgr);
        }
    }

    /**
     * Uploads every sprite's first frame in one recording. VulkanMod uploads sprites only on a frame's last
     * client tick (SpriteUtil.shouldUpload), so its flag is forced on here.
     */
    private static void upload() {
        VulkanImage image = ChimeraTextures.image(atlas);
        if (image == null) return;
        boolean gate = SpriteUtil.shouldUpload();
        SpriteUtil.setDoUpload(true);
        TextureUploadBatch<VulkanImage> batch = new TextureUploadBatch<>(TextureUploadBatch.GRAPHICS_QUEUE);
        batch.open();
        try {
            atlas.bind();
            batch.beforeUpload(image);
            for (TextureAtlasSprite sprite : atlas.getTextures().values()) sprite.uploadFirstFrame();
        } finally {
            batch.close();
            SpriteUtil.setDoUpload(gate);
        }
    }

    private static int[] pixels(NativeImage image) {
        int width = image.getWidth();
        int[] abgr = new int[width * image.getHeight()];
        for (int index = 0; index < abgr.length; index++) {
            abgr[index] = image.getPixelRGBA(index % width, index / width);
        }
        return abgr;
    }

    private static void store(NativeImage image, int[] abgr) {
        int width = image.getWidth();
        for (int index = 0; index < abgr.length; index++) {
            image.setPixelRGBA(index % width, index / width, abgr[index]);
        }
    }
}
