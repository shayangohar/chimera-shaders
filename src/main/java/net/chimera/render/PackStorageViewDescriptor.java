// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import net.vulkanmod.vulkan.texture.VulkanImage;

/**
 * A pack storage image read through a view of another format with the same texel size, as a GL image unit
 * reinterprets an image whose format qualifier differs from its own; Alpha Piscium declares its R32F
 * uimg_csr32f as r32i for integer atomics. VulkanMod asks the descriptor for the view it binds, so this one
 * answers with the image's view in its format.
 *
 * A colour target a post pass stores to as colorimgN answers with its level-0 view in that format
 * (TargetFormatViews), since a storage descriptor takes one level of a mipmapped target.
 */
public final class PackStorageViewDescriptor extends PackImageDescriptor {
    private final int viewFormat;

    public PackStorageViewDescriptor(int binding, String type, String name, int textureSlot, int viewFormat) {
        super(binding, type, name, textureSlot, true);
        this.viewFormat = viewFormat;
    }

    @Override
    public long getImageView(VulkanImage image) {
        if (image instanceof PackStorageImage storage) return storage.view(viewFormat);
        return image == null ? super.getImageView(null) : TargetFormatViews.storageView(image, viewFormat);
    }
}
