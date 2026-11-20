// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.queue.GraphicsQueue;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Records a run of texture uploads into one graphics-queue command buffer, as MTextureManager.tick does. Unbatched,
 * uploadSubTextureAsync fences each region and every fiftieth fence stalls the render thread on the GPU. Render
 * thread only.
 */
final class TextureUploadBatch<I> {
    /** VulkanMod's graphics queue in game, a fake in tests. */
    interface Recorder<I> {
        void begin();

        /** Makes {@code written} shader-readable in the recording, then submits it. */
        void submit(List<I> written);
    }

    static final Recorder<VulkanImage> GRAPHICS_QUEUE = new Recorder<>() {
        @Override
        public void begin() {
            DeviceManager.getGraphicsQueue().startRecording();
        }

        @Override
        public void submit(List<VulkanImage> written) {
            GraphicsQueue queue = DeviceManager.getGraphicsQueue();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkCommandBuffer commands = queue.getCommandBuffer().getHandle();
                for (VulkanImage image : written) image.readOnlyLayout(stack, commands);
            } finally {
                queue.endRecordingAndSubmit();
            }
        }
    };

    private final Recorder<I> recorder;
    private final Set<I> written = new LinkedHashSet<>();
    private boolean open;
    private boolean recording;

    TextureUploadBatch(Recorder<I> recorder) {
        this.recorder = recorder;
    }

    void open() {
        open = true;
    }

    /** An open batch's first upload begins the recording. */
    void beforeUpload(I image) {
        if (!open) return;
        if (!recording) {
            recorder.begin();
            recording = true;
        }
        written.add(image);
    }

    void close() {
        open = false;
        if (!recording) return;
        recording = false;
        try {
            recorder.submit(List.copyOf(written));
        } finally {
            written.clear();
        }
    }
}
