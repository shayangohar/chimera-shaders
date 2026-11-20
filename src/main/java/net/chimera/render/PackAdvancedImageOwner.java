// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import net.chimera.ChimeraMod;
import net.chimera.shaderpack.PackAdvancedResourcePlan;
import net.chimera.shaderpack.ProgramImageBindingManifest;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;


/** Owns the pack's writable images for one pack session. */
public final class PackAdvancedImageOwner implements AutoCloseable {
    private final PackAdvancedResourcePlan plan;
    /** The formats pass computes bind each image in (PassComputePlan.imageFormats). */
    private final Map<String, Set<Integer>> computeFormats;
    private final Map<String, PackStorageImage> images = new TreeMap<>();
    private final Map<String, String> failures = new TreeMap<>();
    private final Set<String> reportedLimits = new HashSet<>();
    /** The size each relative image last failed to refit at. */
    private final Map<String, String> failedRefits = new HashMap<>();
    private int generation;
    private boolean closed;

    public static boolean shouldInitializeImage(boolean clearEachFrame, boolean initialized) {
        return clearEachFrame || !initialized;
    }

    private PackAdvancedImageOwner(PackAdvancedResourcePlan plan, Map<String, Set<Integer>> computeFormats) {
        this.plan = plan == null ? PackAdvancedResourcePlan.empty() : plan;
        this.computeFormats = computeFormats;
    }

    /**
     * The session's images, relative ones sized to this screen until the first frame refits them to the main
     * target, as Iris sizes them by the render target. Each image gets a view per format it is read in.
     */
    public static PackAdvancedImageOwner load(PackAdvancedResourcePlan plan, int screenWidth, int screenHeight,
                                              Map<String, Set<Integer>> computeFormats) {
        PackAdvancedImageOwner owner = new PackAdvancedImageOwner(plan, computeFormats);
        for (PackAdvancedResourcePlan.ImageSpec spec : owner.plan.images().values()) {
            if (spec.supported()) owner.allocate(spec, spec.extent(screenWidth, screenHeight));
        }
        return owner;
    }

    /** The other formats an image is read in, by programs and pass computes. */
    private Set<Integer> viewFormats(PackAdvancedResourcePlan.ImageSpec spec) {
        Set<Integer> formats = new TreeSet<>(plan.viewFormats(spec.name()));
        formats.addAll(computeFormats.getOrDefault(spec.name(), Set.of()));
        formats.remove(spec.format().vkFormat());
        return formats;
    }

    private void allocate(PackAdvancedResourcePlan.ImageSpec spec, int[] extent) {
        try {
            images.put(spec.name(), PackStorageImage.create(spec, extent, viewFormats(spec)));
        } catch (RuntimeException failure) {
            images.remove(spec.name());
            failures.put(spec.name(), failure.getMessage() == null
                    ? "advanced image allocation failed" : failure.getMessage());
        }
    }

    /** Recreates relative images when the screen changes size (Iris: GlImage.Relative.updateNewSize). */
    private void fitRelativeImages(int width, int height) {
        for (PackAdvancedResourcePlan.ImageSpec spec : plan.images().values()) {
            if (!spec.supported() || !spec.relative() || failures.containsKey(spec.name())) continue;
            int[] extent = spec.extent(width, height);
            String failed = keptFailure(failedRefits.get(spec.name()), extent[0] + "x" + extent[1]);
            if (failed == null) failedRefits.remove(spec.name());
            PackStorageImage current = images.get(spec.name());
            if (current != null && current.width == extent[0] && current.height == extent[1]) continue;
            String limit = PackStorageImage.extentLimitFailure(spec, extent);
            if (limit != null) {
                // the screen outgrew the device: the image keeps its last size, as a buffer does
                if (reportedLimits.add(limit)) ChimeraMod.LOGGER.warn("[chimera] {}", limit);
                continue;
            }
            if (failed == null && refit(spec, extent, current)) generation++;
        }
    }

    /** Replaces a relative image; the new one is made first, so a failed allocation keeps the old one. */
    private boolean refit(PackAdvancedResourcePlan.ImageSpec spec, int[] extent, PackStorageImage current) {
        String size = extent[0] + "x" + extent[1];
        PackStorageImage replacement;
        try {
            replacement = PackStorageImage.create(spec, extent, viewFormats(spec));
        } catch (RuntimeException failure) {
            failedRefits.put(spec.name(), size);
            ChimeraMod.LOGGER.warn("[chimera] advanced image {} could not be recreated at {}; keeping {}: {}",
                    spec.name(), size, current == null ? "none" : current.width + "x" + current.height,
                    failure.toString());
            return false;
        }
        failedRefits.remove(spec.name());
        if (current != null) current.free();
        images.put(spec.name(), replacement);
        return true;
    }

    /** The failed refit size while the screen still asks for it, so it is not retried every frame; else null. */
    public static String keptFailure(String failedSize, String requestedSize) {
        return requestedSize.equals(failedSize) ? failedSize : null;
    }

    /** Counts the images replaced since load, so a compute's descriptors can follow them. */
    int generation() {
        return generation;
    }

    /** Whether every image {@code program} binds exists; as in Iris, a reader runs even when its writer does not. */
    public boolean programImagesAvailable(String program) {
        for (PackAdvancedResourcePlan.GraphicsImageBinding binding : plan.imageBindings(program)) {
            // a colour target's colorimgN belongs to the target graph (PostTargetStorage), not this owner
            if (binding.target() < 0 && !availableForSymbol(binding.symbol())) return false;
        }
        return true;
    }

    private boolean availableForSymbol(String symbol) {
        return imageForSymbol(symbol) != null;
    }

    /** Makes earlier image writes visible to later shaders, as Iris's glMemoryBarrier; outside a render pass. */
    public void afterImageWrites(VkCommandBuffer commandBuffer) {
        if (commandBuffer == null || closed || images.isEmpty()) return;
        PackVulkanBarriers.afterShaderImageWrites(commandBuffer);
    }

    public boolean needsGraphicsTransition(ProgramImageBindingManifest manifest) {
        for (var entry : manifest.entries()) {
            if (entry.kind() != ProgramImageBindingManifest.Kind.ADVANCED_IMAGE) continue;
            PackStorageImage image = images.get(entry.resourceKey());
            if (image != null && image.getCurrentLayout() != VK10.VK_IMAGE_LAYOUT_GENERAL) return true;
        }
        return false;
    }

    public void prepareGraphicsImages(VkCommandBuffer commandBuffer,
            ProgramImageBindingManifest manifest) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (var entry : manifest.entries()) {
                if (entry.kind() != ProgramImageBindingManifest.Kind.ADVANCED_IMAGE) continue;
                PackStorageImage image = images.get(entry.resourceKey());
                if (image != null) image.transitionToGeneral(stack, commandBuffer);
            }
        }
    }

    /**
     * Refits relative images and clears the ones due a clear, as Iris does at beginLevelRendering. Vulkan leaves
     * new image memory undefined, so every new image is also cleared once, even without clear.
     */
    public void prepareFrame(VkCommandBuffer commandBuffer, int screenWidth, int screenHeight) {
        if (commandBuffer == null || closed) return;
        fitRelativeImages(screenWidth, screenHeight);
        if (images.isEmpty()) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            boolean hasClear = plan.images().values().stream().anyMatch(
                    spec -> {
                        PackStorageImage image = images.get(spec.name());
                        return image != null
                                && shouldInitializeImage(spec.clear(), image.initialized());
                    });
            if (hasClear) {
                PackVulkanBarriers.beforeTransfer(commandBuffer, stack);
            }
            for (PackAdvancedResourcePlan.ImageSpec spec : plan.images().values()) {
                PackStorageImage image = images.get(spec.name());
                if (image == null || !shouldInitializeImage(spec.clear(), image.initialized())) {
                    continue;
                }
                image.clearToZero(stack, commandBuffer);
                image.markInitialized();
            }
            if (hasClear) {
                PackVulkanBarriers.afterTransfer(commandBuffer, stack);
            }
        }
    }

    public PackAdvancedResourcePlan plan() { return plan; }
    public Map<String, String> failures() { return Map.copyOf(failures); }

    public int plannedImageCount() {
        return (int) plan.images().values().stream().filter(PackAdvancedResourcePlan.ImageSpec::supported).count();
    }

    public int allocatedImageCount() {
        return images.size();
    }

    public int initializedImageCount() {
        return (int) images.values().stream().filter(PackStorageImage::initialized).count();
    }

    public VulkanImage image(String name) {
        return images.get(name);
    }

    PackStorageImage imageForSymbol(String symbol) {
        PackAdvancedResourcePlan.ImageSpec spec = plan.imageForSymbol(symbol);
        return spec == null ? null : images.get(spec.name());
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        images.values().forEach(PackStorageImage::free);
        images.clear();
        failures.clear();
    }
}
