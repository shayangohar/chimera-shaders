// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;

/**
 * A pack program's image at a selector slot Chimera binds itself before descriptors upload, so VulkanMod skips
 * the RenderSystem unit of that number. That unit holds nothing, and VulkanMod would log its missing texture
 * once per session, hiding later misses.
 */
public class PackImageDescriptor extends ImageDescriptor {
    public PackImageDescriptor(int binding, String type, String name, int textureSlot, boolean storage) {
        super(binding, type, name, textureSlot, storage);
    }

    @Override
    public boolean usesVanillaTextureBinding() {
        return false;
    }
}
