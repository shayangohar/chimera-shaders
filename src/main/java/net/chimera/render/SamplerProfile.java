package net.chimera.render;

import net.chimera.mixin.ChimeraSamplerManagerAccessor;
import net.vulkanmod.vulkan.texture.SamplerManager;
import net.vulkanmod.vulkan.texture.VulkanImage;

import java.util.Map;

import static org.lwjgl.opengl.GL11.GL_LEQUAL;
import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_LINEAR_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL11.GL_LINEAR_MIPMAP_NEAREST;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_NEAREST_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL11.GL_NEAREST_MIPMAP_NEAREST;
import static org.lwjgl.opengl.GL11.GL_NONE;
import static org.lwjgl.opengl.GL11.GL_REPEAT;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;

/**
 * The sampler a draw samples an image with and, when known, VulkanMod's OpenGL-style key for it (filters with
 * their mip mode, wrap modes, LOD range and bias). With a null key only the Vulkan sampler is known, and
 * anything derived from the profile uses that sampler as is.
 */
public record SamplerProfile(long id, SamplerManager.SamplerKey key) {
    /** The LOD bias VulkanMod gives every mipmapped sampler it builds from flags (MIP_BIAS). */
    static final float HOST_MIP_BIAS = -0.5F;

    public static SamplerProfile ofId(long id) {
        return new SamplerProfile(id, null);
    }

    /** The profile of an image's own sampler, read back from VulkanMod's sampler caches. */
    public static SamplerProfile of(VulkanImage image) {
        long id = image.getSampler();
        return new SamplerProfile(id, keyOf(id));
    }

    /**
     * This profile as OpenGL samples {@code image} under Iris, for a pack draw. A sampler VulkanMod built from
     * texture flags drops VulkanMod's {@link #HOST_MIP_BIAS}, which picks mip levels half a level finer and
     * which Vulkan also adds to an explicit textureLod. It also clamps to the edge where vanilla's upload asked
     * for that (EdgeClampedImages), which VulkanMod ignores. Any other sampler is kept as is; VulkanMod's own
     * draws keep their samplers.
     */
    public SamplerProfile asGl(VulkanImage image) {
        SamplerManager.SamplerKey gl = glKey(flagKey(id), EdgeClampedImages.contains(image));
        return gl == null ? this : new SamplerProfile(SamplerManager.getTextureSampler(gl), gl);
    }

    /**
     * This profile's sampling, clamped to the base level when {@code baseLevelOnly}; null when the key is
     * unknown. A {@code nearest} map reads the nearest texel of the nearest level, as Iris sets up a labPBR
     * specular map (TextureFormat.setupTextureParameters: GL_NEAREST_MIPMAP_NEAREST when mipmapped).
     *
     * Its class values (metal indices, emission 255 for none) must not blend between levels: a blend of 255
     * and 0 reads as half emission, and distant grass glows in Reverie.
     */
    public SamplerManager.SamplerKey derive(boolean nearest, boolean baseLevelOnly) {
        if (key == null) return null;
        int min = nearest ? nearestOf(key.minFilter()) : key.minFilter();
        int mag = nearest ? GL_NEAREST : key.magFilter();
        float maxLod = baseLevelOnly ? 0.0F : key.maxLod();
        return new SamplerManager.SamplerKey(min, mag, key.wrapS(), key.wrapT(), key.wrapR(),
                Math.min(key.minLod(), maxLod), maxLod, key.lodBias(), key.compareMode(), key.compareFunc());
    }

    static int nearestOf(int minFilter) {
        boolean mipmapped = minFilter == GL_NEAREST_MIPMAP_NEAREST || minFilter == GL_LINEAR_MIPMAP_NEAREST
                || minFilter == GL_NEAREST_MIPMAP_LINEAR || minFilter == GL_LINEAR_MIPMAP_LINEAR;
        return mipmapped ? GL_NEAREST_MIPMAP_NEAREST : GL_NEAREST;
    }

    /** The key VulkanMod created {@code sampler} from, or null when it is not one of its cached samplers. */
    static SamplerManager.SamplerKey keyOf(long sampler) {
        if (sampler == 0L) return null;
        for (Map.Entry<SamplerManager.SamplerKey, Long> entry
                : ChimeraSamplerManagerAccessor.chimera$glSamplers().entrySet()) {
            if (entry.getValue() == sampler) return entry.getKey();
        }
        int packed = flagKey(sampler);
        return packed < 0 ? null : legacyKey((short) packed);
    }

    /**
     * The flags (low byte) and mip ceiling (high byte) VulkanMod built {@code sampler} from, as its texture
     * sampler cache keys them; -1 when the sampler is not in that cache.
     */
    private static int flagKey(long sampler) {
        if (sampler == 0L) return -1;
        for (var entry : ChimeraSamplerManagerAccessor.chimera$samplers().short2LongEntrySet()) {
            if (entry.getLongValue() == sampler) return entry.getShortKey() & 0xFFFF;
        }
        return -1;
    }

    /**
     * The key of the flag sampler {@code packed} (see flagKey) at bias 0, clamped to the edge when
     * {@code clampToEdge}; null when that changes nothing or the sampler has no OpenGL form.
     */
    static SamplerManager.SamplerKey glKey(int packed, boolean clampToEdge) {
        SamplerManager.SamplerKey host = packed < 0 ? null : legacyKey((short) packed);
        if (host == null) return null;
        int wrap = clampToEdge ? GL_CLAMP_TO_EDGE : host.wrapS();
        if (host.lodBias() == 0.0F && wrap == host.wrapS()) return null;
        return new SamplerManager.SamplerKey(host.minFilter(), host.magFilter(), wrap, wrap, wrap,
                host.minLod(), host.maxLod(), 0.0F, host.compareMode(), host.compareFunc());
    }

    /**
     * The OpenGL-style key of a texture sampler VulkanMod built from flags, with the flags in the low byte of
     * {@code packed} and the mip ceiling in the high byte; mipmapped samplers carry {@link #HOST_MIP_BIAS}.
     * A min/max reduction sampler has no OpenGL form and returns null.
     */
    static SamplerManager.SamplerKey legacyKey(short packed) {
        int flags = packed & 0xFF;
        if ((flags & ~(SamplerManager.LINEAR_FILTERING_BIT | SamplerManager.CLAMP_BIT
                | SamplerManager.USE_MIPMAPS_BIT | SamplerManager.MIPMAP_LINEAR_FILTERING_BIT)) != 0) return null;
        boolean linear = (flags & SamplerManager.LINEAR_FILTERING_BIT) != 0;
        boolean mipmaps = (flags & SamplerManager.USE_MIPMAPS_BIT) != 0;
        boolean mipLinear = (flags & SamplerManager.MIPMAP_LINEAR_FILTERING_BIT) != 0;
        int mag = linear ? GL_LINEAR : GL_NEAREST;
        int min = !mipmaps ? mag
                : linear ? (mipLinear ? GL_LINEAR_MIPMAP_LINEAR : GL_LINEAR_MIPMAP_NEAREST)
                : (mipLinear ? GL_NEAREST_MIPMAP_LINEAR : GL_NEAREST_MIPMAP_NEAREST);
        int wrap = (flags & SamplerManager.CLAMP_BIT) != 0 ? GL_CLAMP_TO_EDGE : GL_REPEAT;
        float maxLod = mipmaps ? (packed >> 8) & 0xFF : 0.0F;
        return new SamplerManager.SamplerKey(min, mag, wrap, wrap, wrap, 0.0F, maxLod,
                mipmaps ? HOST_MIP_BIAS : 0.0F, GL_NONE, GL_LEQUAL);
    }
}
