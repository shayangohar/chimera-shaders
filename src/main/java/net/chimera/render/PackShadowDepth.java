// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import net.vulkanmod.vulkan.texture.VulkanImage;

/**
 * Owns the comparison sampler pack shadow lookups need.
 *
 * Pack programs sample the shadow depth attachment itself: {@code sampler2DShadow} lookups compare against the
 * stored depth and {@code sampler2D} lookups read it. The shadow vertex stage already maps clip output into the
 * GL window range the receivers expect, so comparing against the stored depth matches OptiFine. The descriptor's
 * sampler does the comparison; compare-sampling a colour copy of the depth instead loses the device under BSL.
 */
public final class PackShadowDepth {
    private long compareSampler;

    /**
     * Takes the shared comparison sampler: the engine shadow sampler's linear filter and edge clamp, with
     * the GL shadow comparison packs are written against. Safe to call on every pack load.
     */
    public void install() {
        this.compareSampler = ChimeraSamplers.depthCompare();
    }

    /** Compare-enabled sampler, or 0 before {@link #install()}. */
    public long compareSampler() {
        return this.compareSampler;
    }

    /**
     * Whether a declared sampler type reads shadow depth through a hardware comparison, as
     * {@code sampler2DShadow} does; a {@code sampler2D} reader compares by hand.
     */
    public static boolean requiresCompareSampler(String declaredType) {
        return declaredType != null && declaredType.contains("Shadow");
    }

    /**
     * The sampler one shadow descriptor binds: a compare sampler when the program's lookup compares, else
     * the image's own. The compare sampler filters linearly, or nearest under the pack's shadowtexNNearest,
     * as Iris's GlSampler.LINEAR_HW and NEAREST_HW do.
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
