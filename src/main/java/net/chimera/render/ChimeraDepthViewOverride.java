// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import net.vulkanmod.vulkan.texture.VulkanImage;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Depth-only views for sampled depth-stencil images, since VulkanMod's view of a combined format carries both
 * aspects, which a sampled descriptor must not use.
 */
public final class ChimeraDepthViewOverride {
    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);
    // render thread only, registered and read while recording descriptors
    private static final Map<VulkanImage, Long> OWNED = new IdentityHashMap<>();

    private ChimeraDepthViewOverride() {
    }

    public static void bind(VulkanImage image, long view) {
        State state = STATE.get();
        state.image = image;
        state.view = view;
    }

    public static void clear() {
        State state = STATE.get();
        state.image = null;
        state.view = 0L;
    }

    /** Every sampled binding of this owned image uses its depth-only view until unregistered. */
    public static void registerOwned(VulkanImage image, long view) {
        if (image != null && view != 0L) OWNED.put(image, view);
    }

    public static void unregisterOwned(VulkanImage image) {
        if (image != null) OWNED.remove(image);
    }

    public static long viewFor(VulkanImage image) {
        State state = STATE.get();
        if (state.image == image && state.view != 0L) {
            return state.view;
        }
        Long owned = image == null ? null : OWNED.get(image);
        return owned == null ? 0L : owned;
    }

    private static final class State {
        private VulkanImage image;
        private long view;
    }
}
