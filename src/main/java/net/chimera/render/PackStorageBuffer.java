// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.UniformBuffer;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;

/**
 * One pack storage buffer: a uniform buffer with storage usage, since VulkanMod binds buffer descriptors from
 * those. It never grows, as VulkanMod's capacity check would replace it with an empty one.
 */
public final class PackStorageBuffer extends UniformBuffer {
    public PackStorageBuffer(int size) {
        super(size, MemoryTypes.GPU_MEM);
    }

    /** Runs from the superclass constructor, before any field of this class is set. */
    @Override
    protected void createBuffer(int size) {
        this.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT
                | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
        super.createBuffer(size);
    }

    @Override
    public void checkCapacity(int size) {}

    /**
     * Replaces the device buffer with an empty one. The new one is allocated first, so a failure keeps the old
     * one; freeing first, as VulkanMod does, would free the old handle twice.
     */
    public void recreate(int size) {
        PackStorageBuffer replacement = new PackStorageBuffer(size);
        freeBuffer();
        this.id = replacement.id;
        this.allocation = replacement.allocation;
        this.bufferSize = replacement.bufferSize;
        this.data = replacement.data;
    }
}
