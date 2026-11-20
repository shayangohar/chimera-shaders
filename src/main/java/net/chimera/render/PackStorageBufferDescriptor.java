// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import net.vulkanmod.vulkan.memory.UniformBuffer;
import net.vulkanmod.vulkan.shader.descriptor.UBO;

import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC;

/**
 * A pack storage buffer descriptor in VulkanMod's buffer descriptor list. It declares no uniforms; VulkanMod
 * binds its attached {@link PackStorageBuffer} whole at offset 0.
 */
public final class PackStorageBufferDescriptor extends UBO {
    private final int logicalIndex;

    public PackStorageBufferDescriptor(int binding, int stages, int size, int logicalIndex) {
        super(binding, stages, size, null);
        this.logicalIndex = logicalIndex;
    }

    /** The pack's index of the buffer this descriptor binds (Iris bufferObject). */
    public int logicalIndex() {
        return logicalIndex;
    }

    /** The range VulkanMod binds: the attached buffer whole, which a relative buffer resizes with the screen. */
    @Override
    public int getSize() {
        UniformBuffer buffer = getUniformBuffer();
        return buffer == null ? super.getSize() : buffer.getBufferSize();
    }

    @Override
    public int getType() {
        return VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC;
    }
}
