// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import net.vulkanmod.vulkan.texture.VulkanImage;

/**
 * A storage image bound through a view of another format of the same texel size, as a GL image unit
 * reinterprets one whose format qualifier differs. A colour target answers with its level-0 view, since a
 * storage descriptor takes one level of a mipmapped target.
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
