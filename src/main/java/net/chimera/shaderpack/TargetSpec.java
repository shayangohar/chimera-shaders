// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import java.util.List;

/**
 * One pack colour target. {@code paddedAlpha} marks a target declared in a format without alpha, such as
 * RGB16F, and stored in an RGBA image.
 */
public record TargetSpec(
        int index,
        int format,
        int width,
        int height,
        boolean clear,
        float[] clearColor,
        boolean persistent,
        boolean doubled,
        boolean mipmapped,
        boolean paddedAlpha,
        List<String> deviations
) {
    public TargetSpec {
        clearColor = clearColor == null
                ? new float[] {0.0F, 0.0F, 0.0F, 0.0F}
                : clearColor.clone();
        if (clearColor.length != 4) {
            clearColor = new float[] {0.0F, 0.0F, 0.0F, 0.0F};
        }
        deviations = deviations == null ? List.of() : deviations.stream().distinct().sorted().toList();
    }

    @Override
    public float[] clearColor() {
        return clearColor.clone();
    }

    /** A full chain down to 1x1, as glGenerateMipmap builds, or the base level alone. */
    public int mipLevels() {
        return mipmapped ? 32 - Integer.numberOfLeadingZeros(Math.max(1, Math.max(width, height))) : 1;
    }
}
