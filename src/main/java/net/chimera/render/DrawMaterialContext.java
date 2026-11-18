// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;


/**
 * The albedo image and its sampler profile for one material program draw, captured once and passed through the
 * whole binding transaction. The resolver never reads the live slot state again, so an entity batch cannot leak
 * its sampler into the next terrain draw.
 */
public record DrawMaterialContext(VulkanImage albedo, SamplerProfile profile) {
    public DrawMaterialContext {
        if (albedo != null && profile == null) {
            throw new IllegalArgumentException("MATERIAL_CONTEXT_PROFILE_MISSING");
        }
    }

    /** Terrain path: the atlas and the sampler its texture parameters chose, at OpenGL's mip bias. */
    public static DrawMaterialContext forTerrain(VulkanImage albedo) {
        return albedo == null ? new DrawMaterialContext(null, null)
                : new DrawMaterialContext(albedo, SamplerProfile.of(albedo).asGl(albedo));
    }

    /** For callers with no captured context: the image in slot 0 now, with its recorded profile. */
    public static DrawMaterialContext captureLive() {
        VulkanImage albedo = VTextureSelector.getImage(0);
        if (albedo == null) {
            return new DrawMaterialContext(null, null);
        }
        SamplerProfile recorded = ChimeraTextureBindingState.samplerProfile(0);
        SamplerProfile profile = recorded != null
                ? recorded
                : SamplerProfile.of(albedo);
        return new DrawMaterialContext(albedo, profile.asGl(albedo));
    }
}
