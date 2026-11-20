// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.texture.VulkanImage;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_VIEW_TYPE_2D;
import static org.lwjgl.vulkan.VK10.vkDestroyImageView;

/**
 * Storage views of target images in other formats of the same texel size. GL leaves a mismatched format qualifier
 * undefined, but NVIDIA and Mesa read the same bits through the shader's format, and packs rely on it. Such a
 * target is created mutable (ChimeraMutableImageMixin).
 */
public final class TargetFormatViews {
    /** Per thread, since MemoryManager.createImage is shared and only this thread's allocation is the target. */
    private static final AllocationScope<Boolean> MUTABLE = new AllocationScope<>();
    // render thread only
    private static final Map<VulkanImage, Map<Integer, Long>> VIEWS = new IdentityHashMap<>();

    private TargetFormatViews() {}

    /** Whether the image VulkanMod is allocating now is to be created mutable. */
    public static boolean preparing() {
        return MUTABLE.current() != null;
    }

    /** Mutable, with a storage view in each of {@code formats} but its own. */
    static VulkanImage create(Supplier<VulkanImage> create, int format, Set<Integer> formats) {
        Map<Integer, Long> views = new TreeMap<>();
        boolean mutable = formats.stream().anyMatch(view -> view != format);
        if (!mutable) return create.get();
        VulkanImage image = MUTABLE.with(Boolean.TRUE, create);
        try {
            for (int view : formats) {
                if (view != format) {
                    views.put(view, ImageViews.createStorage(image.getId(), VK_IMAGE_VIEW_TYPE_2D, view));
                }
            }
        } catch (RuntimeException failure) {
            // nothing has recorded a use of the new image yet
            views.values().forEach(view -> vkDestroyImageView(Vulkan.getVkDevice(), view, null));
            image.free();
            throw failure;
        }
        VIEWS.put(image, views);
        return image;
    }

    /** The level-0 view in the image's own format. */
    static long storageView(VulkanImage image, int format) {
        Map<Integer, Long> views = VIEWS.get(image);
        Long view = views == null ? null : views.get(format);
        return view != null ? view : PackPostTargets.attachmentView(image);
    }

    /**
     * An image with views is freed once every recorded use has finished, after its views, since
     * MemoryManager.initFrame frees retired images before it runs frame ops.
     */
    static void free(VulkanImage image) {
        Map<Integer, Long> views = VIEWS.remove(image);
        if (views == null) {
            image.free();
            return;
        }
        HandleRetirement.retire(() -> {
            views.values().forEach(view -> vkDestroyImageView(Vulkan.getVkDevice(), view, null));
            image.free();
        });
    }
}
