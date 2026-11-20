// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render.vertex;

import net.vulkanmod.vulkan.util.ColorUtil;

/** Iris's separateAo vertex colour (XHFPTerrainVertex): unshaded tint in RGB, AO times face shade in alpha. */
public final class SeparateAoColor {
    private SeparateAoColor() {}

    /** Clamped, since VulkanMod masks each channel to eight bits and a value just above 1 would wrap to black. */
    public static int rgba(float red, float green, float blue, float brightness) {
        return ColorUtil.RGBA.pack(red, green, blue, Math.max(0.0F, Math.min(1.0F, brightness)));
    }
}
