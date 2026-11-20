// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.mixin;

import net.vulkanmod.vulkan.texture.VulkanImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Sets the sampler and view a Chimera-owned image is read with; VulkanMod's flag-derived samplers and identity
 * swizzle cannot express what pack images need.
 */
@Mixin(value = VulkanImage.class, remap = false)
public interface ChimeraVulkanImageAccessor {
    @Accessor("sampler")
    void chimera$setSampler(long sampler);

    /** The view descriptors sample the image through; VulkanImage.free destroys it. */
    @Accessor("mainImageView")
    void chimera$setMainImageView(long view);
}
