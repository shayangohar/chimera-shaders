// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import net.vulkanmod.vulkan.texture.VulkanImage;

/**
 * Owns the comparison sampler pack shadow lookups need. Programs sample the shadow depth attachment itself;
 * compare-sampling a colour copy of the depth instead loses the device under BSL.
 */
public final class PackShadowDepth {
    private long compareSampler;

    /** Takes the shared comparison sampler; safe to call on every pack load. */
    public void install() {
        this.compareSampler = ChimeraSamplers.depthCompare();
    }

    /** Compare-enabled sampler, or 0 before {@link #install()}. */
    public long compareSampler() {
        return this.compareSampler;
    }

    /** Whether a declared sampler type compares in hardware, as {@code sampler2DShadow} does. */
    public static boolean requiresCompareSampler(String declaredType) {
        return declaredType != null && declaredType.contains("Shadow");
    }

    /**
     * The sampler a shadow descriptor binds: a linear or nearest compare sampler when the lookup compares, as
     * Iris's GlSampler.LINEAR_HW and NEAREST_HW, else the image's own.
     */
    public long samplerFor(VulkanImage boundImage, boolean compareRequired, boolean linear) {
        if (!compareRequired || this.compareSampler == 0L) {
            return boundImage.getSampler();
        }
        return linear ? this.compareSampler : ChimeraSamplers.depthCompare(false);
    }

    public void cleanUp() {
        this.compareSampler = 0L;
    }
}
