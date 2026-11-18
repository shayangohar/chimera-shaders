// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import net.chimera.ChimeraMod;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * Pack sampler overrides per selector slot, kept apart from the images VulkanMod shares.
 *
 * The host render path owns an image and its sampler, so pack draws record an override here instead of changing
 * the image: a descriptor reading a slot samples the recorded image with the recorded sampler, and any other
 * image with its own.
 */
public final class ChimeraTextureBindingState {
    private static final BindingStore<VulkanImage> STORE = new BindingStore<>();

    private static final Map<Integer, SamplerProfile> samplerProfiles = new HashMap<>();
    private static long loggedTerrainImage = Long.MIN_VALUE;

    private ChimeraTextureBindingState() {}

    /** Recorded profile for one selector slot, or null when never marked. */
    public static SamplerProfile samplerProfile(int slot) {
        synchronized (ChimeraTextureBindingState.class) {
            return samplerProfiles.get(slot);
        }
    }

    /**
     * Binds selector slot 0 to the terrain atlas and the sampler its texture parameters chose, at
     * OpenGL's mip bias (SamplerProfile.asGl).
     */
    public static void bindTerrainAtlas(VulkanImage image) {
        if (image == null) {
            clearPackBinding(0);
            return;
        }
        SamplerProfile profile = SamplerProfile.of(image).asGl(image);
        synchronized (ChimeraTextureBindingState.class) {
            STORE.bind(0, image, profile.id());
            samplerProfiles.put(0, profile);
            if (image.getId() != loggedTerrainImage) {
                loggedTerrainImage = image.getId();
                LOGGER.debug("[chimera] pack terrain binding: image={} sampler={} key={} atlasMipLevels={}",
                        image.getId(), profile.id(), profile.key(), image.mipLevels);
            }
        }
    }

    /** Records {@code image} with its own sampler for a pack selector slot. */
    public static void markPackBinding(int slot, VulkanImage image) {
        if (image == null) {
            clearPackBinding(slot);
            return;
        }
        synchronized (ChimeraTextureBindingState.class) {
            STORE.bind(slot, image, image.getSampler());
            samplerProfiles.put(slot, SamplerProfile.ofId(image.getSampler()));
        }
    }

    /** Records an image paired with an exact sampler for a pack selector slot. */
    public static void markPackBinding(int slot, VulkanImage image, long sampler) {
        if (image == null || sampler == 0L) {
            clearPackBinding(slot);
            return;
        }
        synchronized (ChimeraTextureBindingState.class) {
            STORE.bind(slot, image, sampler);
            samplerProfiles.put(slot, SamplerProfile.ofId(sampler));
        }
    }

    public record Snapshot(VulkanImage image, Long samplerOverride) {}

    public static synchronized Snapshot capture(int slot, VulkanImage image) {
        var entry = STORE.binding(slot);
        return new Snapshot(image, entry != null && entry.image() == image ? entry.sampler() : null);
    }

    public static synchronized void restore(int slot, Snapshot snapshot) {
        if (snapshot.samplerOverride() == null) STORE.clear(slot);
        else STORE.bind(slot, snapshot.image(), snapshot.samplerOverride());
    }

    public static void clearPackBinding(int slot) {
        synchronized (ChimeraTextureBindingState.class) {
            STORE.clear(slot);
            samplerProfiles.remove(slot);
        }
    }

    /**
     * The sampler a descriptor reading selector {@code slot} binds with {@code image}: the pack's
     * override for that pair, else the host's {@code fallback}.
     */
    public static long samplerForDescriptor(int slot, VulkanImage image, long fallback) {
        if (image == null) return fallback;
        synchronized (ChimeraTextureBindingState.class) {
            BindingStore.Entry<VulkanImage> binding = STORE.binding(slot);
            return binding != null && binding.image() == image ? binding.sampler() : fallback;
        }
    }

    /** Clears every slot's override, at pack cleanup. */
    public static void reset() {
        synchronized (ChimeraTextureBindingState.class) {
            STORE.reset();
            samplerProfiles.clear();
            loggedTerrainImage = Long.MIN_VALUE;
        }
    }

    /** Slot bindings matched by image identity, generic so tests can drive it without Vulkan images. */
    static final class BindingStore<I> {
        private final Map<Integer, Entry<I>> bindings = new HashMap<>();

        void bind(int slot, I image, long sampler) {
            bindings.put(slot, new Entry<>(image, sampler));
        }

        Entry<I> binding(int slot) {
            return bindings.get(slot);
        }

        void clear(int slot) {
            bindings.remove(slot);
        }

        void reset() {
            bindings.clear();
        }

        record Entry<I>(I image, long sampler) {}
    }

    private static final Logger LOGGER = ChimeraMod.LOGGER;
}
