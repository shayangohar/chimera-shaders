// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

/**
 * Marks the image views VulkanMod creates for a RED texture (a TrueType glyph page), which then get an RRRR
 * swizzle, as Iris swizzles it for its TEXT_INTENSITY keys; vanilla's intensity shader reads red alone.
 */
public final class IntensityTextureViews {
    private static final AllocationScope<Boolean> PREPARING = new AllocationScope<>();

    private IntensityTextureViews() {}

    /** Runs {@code allocate}, a RED texture's allocation, with the views it creates marked. */
    public static void allocate(Runnable allocate) {
        PREPARING.run(Boolean.TRUE, allocate);
    }

    /** Whether the view VulkanMod is creating belongs to a RED texture this thread is allocating. */
    public static boolean preparing() {
        return PREPARING.current() != null;
    }
}
