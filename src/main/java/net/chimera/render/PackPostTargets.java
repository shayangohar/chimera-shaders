// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import net.chimera.render.shader.MrtPipelineContext;
import net.chimera.shaderpack.PackFlipPlan;
import net.chimera.shaderpack.PackFrameSchedulePlan;
import net.chimera.shaderpack.PackPipelines;
import net.chimera.shaderpack.PackTargetGraphPlan;
import net.chimera.shaderpack.PostTargetPlan;
import net.chimera.shaderpack.TargetSpec;
import net.chimera.shaderpack.TargetStep;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.lwjgl.vulkan.KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_INFO_KHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdBeginRenderingKHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdEndRenderingKHR;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_LOAD;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.vkCmdSetScissor;

/**
 * Runs the pack's target graph: makes the images and records the copies and clears the flip plan asks for
 * (PostTargetSides). Validity is tracked apart from the images, so a skipped pass cannot expose a stale one.
 */
public final class PackPostTargets {
    private static final Logger LOGGER = LoggerFactory.getLogger("chimera");
    private static final int TARGET_COUNT = PackTargetGraphPlan.MAX_TARGET + 1;

    /** Main and alt, as Iris's RenderTarget holds them. */
    private static final int SIDE_COUNT = 2;
    private final VulkanImage[][] images = new VulkanImage[SIDE_COUNT][TARGET_COUNT];
    private final TargetSpec[] specs = new TargetSpec[TARGET_COUNT];
    private final boolean[] used = new boolean[TARGET_COUNT];
    private final boolean[] valid = new boolean[TARGET_COUNT];
    private final VulkanImage[] sourceImages = new VulkanImage[TARGET_COUNT];
    private final VulkanImage[] pendingImages = new VulkanImage[TARGET_COUNT];
    /** Copies a geometry window samples in place of the targets it draws into. */
    private final VulkanImage[] snapshotImages = new VulkanImage[TARGET_COUNT];
    /** What world programs read (worldImages), refilled on each call. */
    private final VulkanImage[] worldImages = new VulkanImage[TARGET_COUNT];
    /** The targets the drawing post pass stores to as colorimgN, held in GENERAL for its draw. */
    private final PostTargetStorage storage = new PostTargetStorage();
    /** The targets the drawing hand lane stores to as colorimgN, held in GENERAL for its run. */
    private final PostTargetStorage worldStorage = new PostTargetStorage();

    private VulkanImage hdrIdentitySource;
    private PackTargetGraphPlan graph;
    private PostTargetSides sides;
    /** colortex0's clear colour this frame: its colortex0ClearColor, else the fog colour, as Iris clears it. */
    private float[] colortex0Clear;
    /** VulkanMod's stand-ins for a post pass's attachments, one per target extent (standIn). */
    private final Map<Long, Framebuffer> standIns = new HashMap<>();
    private boolean configured;
    private boolean rendering;
    private final PackTemporalState temporal = new PackTemporalState();
    /** Images whose sampler reads their generated mip chain until the frame ends. */
    private final Set<VulkanImage> mipSampling = Collections.newSetFromMap(new IdentityHashMap<>());
    /** The flip plan's step the frame is at (beginStep). */
    private PackFlipPlan.Step currentFlipStep;

    /** Installs the graph and allocates the sides it needs, alt only where the flip plan uses it. */
    public boolean configure(PackTargetGraphPlan graph, Map<Integer, Set<Integer>> storageTargets) {
        trace("configure begin oldTarget0=" + imageId(this.images[0][0])
                + " oldTarget0Alt=" + imageId(this.images[1][0]));
        cleanUp();
        if (graph == null || (graph.steps().isEmpty() && graph.targets().isEmpty())) {
            return false;
        }
        this.graph = graph;
        PackTargetImages.verifyFormats(graph.targets());
        Arrays.fill(this.used, false);
        Arrays.fill(this.valid, false);
        int allocated = 0;
        int cleared = 0;
        for (TargetSpec target : graph.targets()) {
            int index = target.index();
            if (index < 0 || index >= TARGET_COUNT) {
                continue;
            }
            this.used[index] = true;
            this.specs[index] = target;
            allocated |= 1 << index;
            if (target.clear()) cleared |= 1 << index;
            Set<Integer> storage = storageTargets.getOrDefault(index, Set.of());
            this.images[0][index] = PackTargetImages.createTargetImage(target, storage);
            if (target.doubled()) {
                this.images[1][index] = PackTargetImages.createTargetImage(target, storage);
            }
        }
        if (this.images[0][0] == null) {
            throw new IllegalStateException("target graph does not contain colortex0");
        }
        this.sides = new PostTargetSides(graph.flips(), allocated, cleared);
        addStandIns(this.standIns, this.images);
        this.configured = true;
        trace("configure done target0=" + imageId(this.images[0][0])
                + " target0Alt=" + imageId(this.images[1][0]));
        return true;
    }

    /** Starts a frame with Iris's clears and copy-backs; recorded before the world target opens. */
    public void beginFrame(VkCommandBuffer commandBuffer, VulkanImage hdrColor, float[] colortex0Clear) {
        if (!this.configured || this.graph == null) {
            throw new IllegalStateException("pack target graph is not configured");
        }
        // a screen change makes a new HDR image between frames, holding nothing to write back
        if (hdrColor != this.hdrIdentitySource) this.sides.hdrReplaced();
        this.hdrIdentitySource = hdrColor;
        this.colortex0Clear = colortex0Clear;
        this.temporal.beginFrame(hdrColor != null);
        resetMipmapSampling();
        Arrays.fill(this.pendingImages, null);
        this.currentFlipStep = null;
        boolean full = this.sides.fullClearDue();
        withOps(commandBuffer, ops -> this.sides.beginFrame(ops));
        if (hdrColor != null && carriesColortex0()) {
            // it holds the colortex0 side the world draws first; the world target opens on it next, loading it
            try (MemoryStack stack = MemoryStack.stackPush()) {
                hdrColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            }
        }
        // every target opens the frame readable, as in Iris; otherwise a pass reading its own history, such as
        // TAA, would fall back on every frame after one missing input
        for (int target = 0; target < TARGET_COUNT; target++) {
            if (!this.used[target]) continue;
            this.valid[target] = true;
            this.temporal.seedCurrent(target);
        }
        refreshSources();
        trace("beginFrame full=" + full + " target0=" + imageId(this.sourceImages[0]));
    }

    public void requireFrameStarted() {
        this.temporal.requireFrameStarted();
    }

    /** Whether colortex0 carries over from the last frame (colortex0Clear=false); the world target then loads it. */
    public boolean carriesColortex0() {
        return this.configured && this.sides.carriesColortex0();
    }

    /** Starts a geometry window for the declared targets, not necessarily including HDR. */
    public void beginGeometry(VkCommandBuffer commandBuffer, VulkanImage hdrColor,
                              List<Integer> outputTargets) {
        beginGeometry(commandBuffer, hdrColor, outputTargets, List.of());
    }

    /**
     * As above, for a program that also samples some of its outputs. Iris lets a gbuffer program read the
     * target it draws into, which Vulkan forbids inside a render pass, so the window's draws sample a copy.
     */
    public void beginGeometry(VkCommandBuffer commandBuffer, VulkanImage hdrColor,
                              List<Integer> outputTargets, List<Integer> snapshotTargets) {
        requireFrameStarted();
        if (!this.configured || hdrColor == null || outputTargets == null || outputTargets.isEmpty()) {
            throw new IllegalStateException("pack geometry target state is not ready");
        }
        this.hdrIdentitySource = hdrColor;
        this.sourceImages[0] = image(this.sides.read(0));
        this.rendering = false;
        for (int target : outputTargets) {
            if (target < 0 || target >= TARGET_COUNT || !this.used[target]) {
                throw new IllegalStateException("missing geometry output target " + target);
            }
        }
        if (snapshotTargets != null && !snapshotTargets.isEmpty()) {
            snapshotOutputs(commandBuffer, outputTargets, snapshotTargets);
        }
    }

    /** Copies each written target the window also samples and points its reads at the copy. */
    private void snapshotOutputs(VkCommandBuffer commandBuffer, List<Integer> outputTargets,
                                 List<Integer> snapshotTargets) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int target : snapshotTargets) {
                if (target <= 0 || target >= TARGET_COUNT || !outputTargets.contains(target)) continue;
                VulkanImage held = worldImage(target, this.sourceImages[target], this.used[target],
                        worldSideImage(target));
                if (held == null) continue;
                if (this.snapshotImages[target] == null) {
                    this.snapshotImages[target] = PackTargetImages.createTargetImage(targetSpec(target), false);
                }
                PackTargetImages.copyImage(stack, commandBuffer, held, this.snapshotImages[target]);
                this.sourceImages[target] = this.snapshotImages[target];
                trace("snapshot target=" + target + " image=" + imageId(this.snapshotImages[target]));
            }
        }
    }

    private TargetSpec targetSpec(int target) {
        TargetSpec spec = target >= 0 && target < TARGET_COUNT ? this.specs[target] : null;
        if (spec == null) throw new IllegalStateException("missing geometry snapshot target " + target);
        return spec;
    }

    /**
     * Attachments in fragment output order: the HDR image for colortex0, else the world's side
     * (Iris: RenderTargets.createGbufferFramebuffer).
     */
    public List<VulkanImage> geometryAttachments(List<Integer> outputTargets) {
        if (outputTargets == null || outputTargets.isEmpty()) return List.of();
        ArrayList<VulkanImage> result = new ArrayList<>();
        for (int target : outputTargets) {
            VulkanImage image = target == 0 ? this.hdrIdentitySource : worldSideImage(target);
            if (image == null) {
                throw new IllegalStateException("geometry output image is unavailable: " + target);
            }
            result.add(image);
        }
        return List.copyOf(result);
    }

    /** Commits the auxiliary geometry outputs; colortex0 stays in the HDR image, which the world draws into. */
    public void commitGeometry(List<Integer> outputTargets, VulkanImage hdrColor) {
        if (outputTargets == null || hdrColor == null) return;
        this.hdrIdentitySource = hdrColor;
        this.sourceImages[0] = image(this.sides.read(0));
        for (int target : outputTargets) {
            if (target <= 0 || target >= TARGET_COUNT) continue;
            VulkanImage image = worldSideImage(target);
            if (image != null) {
                this.valid[target] = true;
                this.sourceImages[target] = image;
                this.temporal.seedCurrent(target);
            }
        }
    }

    /**
     * Returns to the world after {@code window}'s pass group, writing colortex0's side back into the HDR image,
     * as Iris has one colortex0 per frame. The HDR image is in the sampled layout; the caller reopens it.
     */
    public void enterWorld(VkCommandBuffer commandBuffer, PackFrameSchedulePlan.PostWindow window,
                           VulkanImage hdrColor) {
        if (!this.configured || hdrColor == null) return;
        this.hdrIdentitySource = hdrColor;
        withOps(commandBuffer, ops -> this.sides.enterWorld(window, ops));
        refreshSources();
        trace("enterWorld window=" + window + " state=" + this.sides.state());
    }

    /** The composite group is over: final, and the resolve without one, read the plan's final state. */
    public void enterFinal(VkCommandBuffer commandBuffer) {
        if (!this.configured) return;
        withOps(commandBuffer, ops -> this.sides.enterFinal(ops));
        refreshSources();
    }

    public void abortGeometry(VulkanImage hdrColor) {
        if (hdrColor != null) this.hdrIdentitySource = hdrColor;
        if (this.configured) this.sourceImages[0] = image(this.sides.read(0));
        for (int target = 1; target < TARGET_COUNT; target++) {
            if (this.sourceImages[target] != null && this.sourceImages[target] == this.snapshotImages[target]) {
                this.sourceImages[target] = worldSideImage(target);
            }
        }
    }

    /**
     * Invalidates a skipped stage's outputs for the rest of the frame, so later readers fall back too.
     * colortex0 is exempt: the skipped step passes it through, keeping earlier passes' output.
     */
    public void invalidateOutputs(List<Integer> targets) {
        if (targets == null) return;
        for (int target : targets) {
            if (target <= 0 || target >= TARGET_COUNT) continue;
            this.valid[target] = false;
            this.sourceImages[target] = null;
            this.temporal.invalidate(target);
        }
    }

    /**
     * Rebuilds the mip chain of each colortexNMipmapEnabled target a program reads, sampled through its mips
     * until the frame ends (Iris: CompositeRenderer.setupMipmapping). Call before the images are bound.
     */
    public void generateMipmaps(VkCommandBuffer commandBuffer, String programName) {
        TargetStep step = this.graph == null ? null : this.graph.step(programName);
        if (!this.configured || step == null || step.mipmapTargets().isEmpty()) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int target : step.mipmapTargets()) {
                if (target < 0 || target >= TARGET_COUNT || this.sourceImages[target] == null) continue;
                // a minimized window sizes targets 1x1, so their whole chain is level 0
                if (!needsMipChain(graphImage(target, this.sides.readSide(target)).mipLevels)) continue;
                VulkanImage image = image(this.sides.readGraph(target, false, ops(stack, commandBuffer)));
                if (target == 0) this.sourceImages[0] = image(this.sides.read(0));
                PackTargetImages.buildMipChain(stack, commandBuffer, image);
                noteMipSampling(image);
            }
        }
        trace("mipmaps program=" + programName + " targets=" + step.mipmapTargets());
    }

    static boolean needsMipChain(int levels) {
        return levels > 1;
    }

    /**
     * Opens {@code window}'s pass group, applying its {@code flip.<group>_pre} flips first as Iris does. Mip
     * sampling carries on, as Iris resets a target's filter only after final.
     */
    public void openPostWindow(VkCommandBuffer commandBuffer, PackFrameSchedulePlan.PostWindow window) {
        if (!this.configured) return;
        withOps(commandBuffer, ops -> this.sides.openWindow(window, ops));
        refreshSources();
    }

    /**
     * Brings the frame up to {@code pass}, passing through earlier steps that did not run. Call before the
     * pass's computes and bindings.
     */
    public void beginStep(VkCommandBuffer commandBuffer, String pass) {
        if (!this.configured) return;
        PackFlipPlan.Step[] step = new PackFlipPlan.Step[1];
        withOps(commandBuffer, ops -> step[0] = this.sides.advanceTo(pass, ops));
        this.currentFlipStep = step[0];
        refreshSources();
    }

    void noteMipSampling(VulkanImage image) {
        this.mipSampling.add(image);
    }

    boolean samplesMips(VulkanImage image) {
        return this.mipSampling.contains(image);
    }

    /** Iris resets every target's filter after final; Chimera does it as the next frame starts. */
    private void resetMipmapSampling() {
        for (VulkanImage image : this.mipSampling) {
            PackTargetImages.sampleWith(image, PackTargetImages.baseLevelSampler(image));
        }
        this.mipSampling.clear();
    }

    /** A pack target's clear colour in its format's kind (PackTargetImages.clearValue). */
    public static void clearColorValue(MemoryStack stack, VkClearColorValue clear, int vkFormat, float[] color) {
        PackTargetImages.clearValue(stack, clear, vkFormat, color);
    }

    /** Pack targets render to level 0 only, through an identity view of exactly one level. */
    public static long attachmentView(VulkanImage image) {
        return image.getLevelImageViews() != null ? image.getLevelImageView(0) : image.getImageView();
    }

    /**
     * The graph image a pass writes as {@code colorimgN}, its read side as in Iris. colortex0 is first written
     * back out of the HDR image, which has no storage usage.
     */
    public VulkanImage storageSource(VkCommandBuffer commandBuffer, int target) {
        if (!this.configured || target < 0 || target >= TARGET_COUNT || this.sourceImages[target] == null) return null;
        VulkanImage[] image = new VulkanImage[1];
        withOps(commandBuffer, ops -> image[0] = image(this.sides.readGraph(target, true, ops)));
        if (target == 0) this.sourceImages[0] = image(this.sides.read(0));
        return image[0];
    }

    /** Begins dynamic rendering for {@code post}'s graph step. */
    public void prepare(PackPipelines.PackPost post, VkCommandBuffer commandBuffer) {
        if (!this.configured || this.graph == null || this.rendering) {
            throw new IllegalStateException("pack post target state is not ready");
        }
        PostTargetPlan plan = post.targetPlan();
        TargetStep step = this.graph.step(post.name());
        if (plan == null || plan.isFinal() || step == null || !step.executable()) {
            throw new IllegalStateException("post step is not executable: " + post.name());
        }
        PackFlipPlan.Step flipStep = this.currentFlipStep;
        if (flipStep == null || !flipStep.program().equals(post.name())) {
            throw new IllegalStateException("post step was not begun: " + post.name());
        }
        Arrays.fill(this.pendingImages, null);
        int attachmentCount = plan.targetSlots().size();
        int deviceLimit = deviceMaxColorAttachments();
        if (!deviceSupportsMrt(attachmentCount, deviceLimit)) {
            throw new IllegalStateException("post pass requires " + attachmentCount
                    + " color attachments, device supports " + deviceLimit);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            claimOutputSides(stack, commandBuffer, plan, flipStep);
            // after the write-backs, outside the render pass: VulkanMod moves no image out of GENERAL
            // ("Unexpected value:1")
            this.storage.begin(commandBuffer, target -> storageSource(commandBuffer, target), post.storageTargets());
            PostAttachments attachments = colorAttachments(stack, commandBuffer, plan, step);
            int renderWidth = attachments.width();
            int renderHeight = attachments.height();

            VkRect2D renderArea = VkRect2D.calloc(stack);
            renderArea.offset().set(0, 0);
            renderArea.extent().set(renderWidth, renderHeight);
            VkRenderingInfo renderingInfo = VkRenderingInfo.calloc(stack);
            renderingInfo.sType(VK_STRUCTURE_TYPE_RENDERING_INFO_KHR);
            renderingInfo.renderArea(renderArea);
            renderingInfo.layerCount(1);
            renderingInfo.pColorAttachments(attachments.buffer());

            // the pass draws unblended unless its blend.<program> says otherwise (CompositeRenderer)
            MrtPipelineContext.begin(plan.outputFormatsArray(), deviceLimit,
                    post.blendPlan().attachments(plan.targetSlots()));
            Renderer.getInstance().setBoundFramebuffer(standIn(this.standIns, attachments.first()));
            Renderer.getInstance().setBoundRenderPass(ChimeraRenderingPass.of(plan.outputFormatsArray(), 0, false));
            Renderer.setViewport(0, 0, renderWidth, renderHeight, stack);
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.get(0).offset().set(0, 0);
            scissor.get(0).extent().set(renderWidth, renderHeight);
            vkCmdSetScissor(commandBuffer, 0, scissor);
            try {
                vkCmdBeginRenderingKHR(commandBuffer, renderingInfo);
                this.rendering = true;
            } catch (RuntimeException failure) {
                MrtPipelineContext.end();
                Renderer.getInstance().setBoundRenderPass(null);
                Renderer.getInstance().setBoundFramebuffer(null);
                throw failure;
            }
        }
    }

    /**
     * Picks each draw buffer's write side, the one the pass does not read. As in Iris it keeps what it held,
     * so a blend or discard lands on that.
     */
    private void claimOutputSides(MemoryStack stack, VkCommandBuffer commandBuffer, PostTargetPlan plan,
                                  PackFlipPlan.Step flipStep) {
        PostTargetSides.Ops ops = ops(stack, commandBuffer);
        for (int target : plan.targetSlots()) {
            if (target < 0 || target >= TARGET_COUNT || !this.used[target]) {
                throw new IllegalStateException("missing output target " + target);
            }
            this.pendingImages[target] = image(this.sides.write(flipStep, target, ops));
        }
    }

    /** One post draw's colour attachments, and its extent: the first attachment's size. */
    private record PostAttachments(VkRenderingAttachmentInfo.Buffer buffer, VulkanImage first, int width, int height) {}

    /** Gives each extent among the session's target images a stand-in built on one of them. */
    static void addStandIns(Map<Long, Framebuffer> standIns, VulkanImage[][] images) {
        for (VulkanImage[] side : images) {
            for (VulkanImage image : side) {
                if (image == null) continue;
                standIns.computeIfAbsent(extent(image), key -> Framebuffer.builder(image, null).build());
            }
        }
    }

    /**
     * The framebuffer VulkanMod takes a post pass's size from. Reforged sets the viewport's y from its height,
     * so a full-size stand-in would draw a smaller size.buffer target below its image.
     */
    static Framebuffer standIn(Map<Long, Framebuffer> standIns, VulkanImage first) {
        Framebuffer standIn = standIns.get(extent(first));
        return standIn != null ? standIn : Framebuffer.builder(first, null).build();
    }

    private static long extent(VulkanImage image) {
        return ((long) image.width << 32) | (image.height & 0xFFFFFFFFL);
    }

    /** Moves the plan's attachment targets to attachment layout and describes them in slot order. */
    private PostAttachments colorAttachments(MemoryStack stack, VkCommandBuffer commandBuffer,
                                             PostTargetPlan plan, TargetStep step) {
        List<Integer> attachmentTargets = plan.targetSlots();
        VkRenderingAttachmentInfo.Buffer attachments =
                VkRenderingAttachmentInfo.calloc(attachmentTargets.size(), stack);
        int renderWidth = step.width();
        int renderHeight = step.height();
        VulkanImage first = null;
        for (int i = 0; i < attachmentTargets.size(); i++) {
            int target = attachmentTargets.get(i);
            VulkanImage destination = this.pendingImages[target];
            if (destination == null) {
                throw new IllegalStateException("missing attachment target " + target);
            }
            destination.transitionImageLayout(stack, commandBuffer,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            VkRenderingAttachmentInfo attachment = attachments.get(i);
            attachment.sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR);
            attachment.imageView(attachmentView(destination));
            attachment.imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            attachment.loadOp(VK_ATTACHMENT_LOAD_OP_LOAD);
            attachment.storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            if (i == 0) {
                first = destination;
                renderWidth = destination.width;
                renderHeight = destination.height;
            }
        }
        return new PostAttachments(attachments, first, renderWidth, renderHeight);
    }

    /** Commits a successful draw: the step's flips take effect and each flipped draw buffer becomes valid. */
    public void finish(VkCommandBuffer commandBuffer) {
        if (!this.rendering) throw new IllegalStateException("pack post rendering is not active");
        boolean committed = false;
        PackFlipPlan.Step flipStep = this.currentFlipStep;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdEndRenderingKHR(commandBuffer);
            this.storage.end(commandBuffer);
            transitionWrittenTargetsForSampling(stack, commandBuffer);
            committed = true;
        } finally {
            if (committed && flipStep != null) {
                int flipped = flipStep.before() ^ flipStep.after();
                for (int target = 0; target < TARGET_COUNT; target++) {
                    if (this.pendingImages[target] != null && PackFlipPlan.flipped(flipped, target)) {
                        this.valid[target] = true;
                        this.temporal.stageWrite(target);
                    }
                }
                this.sides.finish(flipStep);
                refreshSources();
            }
            this.currentFlipStep = null;
            clearPendingState();
            finishRendererState();
        }
    }

    /** Returns the images the step wrote to the sampled layout. */
    private void transitionWrittenTargetsForSampling(MemoryStack stack, VkCommandBuffer commandBuffer) {
        for (VulkanImage image : this.pendingImages) {
            if (image != null) {
                image.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }
        }
    }

    /** Commits the current frame's logical state after all scheduled windows finish. */
    public boolean commitFrame() {
        return this.temporal.commit();
    }

    /** Aborts pending writes and closes the frame without changing committed logical state. */
    public void abort(VkCommandBuffer commandBuffer) {
        try {
            abortStep(commandBuffer);
        } finally {
            this.temporal.abort();
        }
    }

    /** Aborts the current step's pending writes and leaves the frame open; the next advance passes it through. */
    public void abortStep(VkCommandBuffer commandBuffer) {
        if (this.rendering) vkCmdEndRenderingKHR(commandBuffer);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            this.storage.end(commandBuffer);
            transitionWrittenTargetsForSampling(stack, commandBuffer);
        } finally {
            this.currentFlipStep = null;
            clearPendingState();
            finishRendererState();
        }
    }

    public VulkanImage[] sourceImages() { return this.sourceImages; }
    public PackTargetGraphPlan graph() { return this.graph; }

    /**
     * The images world programs read: each target's source, or the image a dropped target still holds, since
     * a world program has no fallback and Iris samples whatever the target holds.
     */
    public VulkanImage[] worldImages() {
        for (int target = 0; target < TARGET_COUNT; target++) {
            this.worldImages[target] = worldImage(target, this.sourceImages[target], this.used[target],
                    worldSideImage(target));
        }
        return this.worldImages;
    }

    /**
     * The image a world program reads as {@code target}: its source, else the held image. colortex0 keeps its
     * source, as the resolver reads the HDR image in its place.
     */
    static VulkanImage worldImage(int target, VulkanImage source, boolean used, VulkanImage held) {
        return source != null || target == 0 || !used ? source : held;
    }

    /** The image a hand program stores to as colorimgN, the one world programs read as colortexN, as in Iris. */
    public VulkanImage worldStorageImage(int target) {
        if (target <= 0 || target >= TARGET_COUNT) return null;
        return worldImage(target, this.sourceImages[target], this.used[target], worldSideImage(target));
    }

    /** Holds {@code targets}' world images in GENERAL for a hand lane's run. Recorded outside a render pass. */
    public void holdWorldStorage(VkCommandBuffer commandBuffer, List<Integer> targets) {
        this.worldStorage.begin(commandBuffer, this::worldStorageImage, targets);
    }

    /** Whether a hand lane's run holds any target in GENERAL. */
    public boolean holdsWorldStorage() {
        return this.worldStorage.holding();
    }

    /** Whether a hand lane's run already holds each of {@code targets} that has an image. */
    public boolean holdsWorldStorage(List<Integer> targets) {
        return targets.stream().map(this::worldStorageImage).allMatch(image -> image == null
                || this.worldStorage.holds(image));
    }

    /** Returns the targets a hand lane's run held to the sampled layout. Recorded outside a render pass. */
    public void releaseWorldStorage(VkCommandBuffer commandBuffer) {
        this.worldStorage.end(commandBuffer);
    }

    public boolean areInputsAvailable(List<Integer> requiredTargets) {
        if (requiredTargets == null) return true;
        for (int target : requiredTargets) if (!isTargetAvailable(target)) return false;
        return true;
    }

    /** The image a reader reads {@code target} from now, or null when the chain holds none. */
    public VulkanImage activeTarget(int target) {
        if (target < 0 || target >= TARGET_COUNT) return null;
        return this.sourceImages[target];
    }

    /** The side a reader does not read now, holding the frame's earlier or the last frame's content. */
    public VulkanImage previousTarget(int target) {
        if (!this.configured || target < 0 || target >= TARGET_COUNT || !this.valid[target]
                || !this.sides.sided(target) || !this.temporal.previousAvailable(target)) return null;
        return graphImage(target, 1 - this.sides.readSide(target));
    }

    public boolean currentTargetAvailable(int target) {
        return isTargetAvailable(target);
    }

    static boolean deviceSupportsMrt(int attachments, int maxColorAttachments) {
        return attachments > 0 && maxColorAttachments > 0
                && attachments <= Math.min(PackTargetGraphPlan.LOGICAL_ATTACHMENT_LIMIT, maxColorAttachments);
    }

    public boolean isRendering() { return this.rendering; }
    public boolean isConfigured() { return this.configured; }

    public void cleanUp() {
        trace("cleanup target0=" + imageId(this.images[0][0])
                + " target0Alt=" + imageId(this.images[1][0])
                + " configured=" + this.configured);
        if (this.rendering) throw new IllegalStateException("cannot clean up active pack post rendering");
        for (Framebuffer standIn : this.standIns.values()) standIn.cleanUp(false);
        this.standIns.clear();
        for (int side = 0; side < images.length; side++) {
            for (int target = 0; target < TARGET_COUNT; target++) {
                if (this.images[side][target] != null) {
                    TargetFormatViews.free(this.images[side][target]);
                    this.images[side][target] = null;
                }
            }
        }
        for (int target = 0; target < TARGET_COUNT; target++) {
            if (this.snapshotImages[target] != null) {
                this.snapshotImages[target].free();
                this.snapshotImages[target] = null;
            }
        }
        this.mipSampling.clear();
        Arrays.fill(this.sourceImages, null);
        Arrays.fill(this.pendingImages, null);
        Arrays.fill(this.specs, null);
        Arrays.fill(this.used, false);
        Arrays.fill(this.valid, false);
        this.hdrIdentitySource = null;
        this.graph = null;
        this.sides = null;
        this.currentFlipStep = null;
        this.configured = false;
        this.temporal.reset();
        MrtPipelineContext.end();
    }

    private boolean isTargetAvailable(int target) {
        if (target < 0 || target >= TARGET_COUNT) return false;
        return target == 0 ? this.sourceImages[0] != null
                : this.valid[target] && this.sourceImages[target] != null;
    }

    /** What each target is read through now: colortex0 always, any other target while valid. */
    private void refreshSources() {
        if (!this.configured) return;
        for (int target = 0; target < TARGET_COUNT; target++) {
            boolean readable = this.used[target] && (target == 0 || this.valid[target]);
            this.sourceImages[target] = readable ? image(this.sides.read(target)) : null;
        }
    }

    /** The side of {@code target} the world reads and writes now. */
    private VulkanImage worldSideImage(int target) {
        return this.configured ? graphImage(target, this.sides.readSide(target)) : null;
    }

    private VulkanImage graphImage(int target, int side) {
        if (target < 0 || target >= TARGET_COUNT || side < 0 || side >= SIDE_COUNT) return null;
        return this.images[side][target];
    }

    private VulkanImage image(PostTargetSides.Image image) {
        return image.hdr() ? this.hdrIdentitySource : graphImage(image.target(), image.side());
    }

    /** Records the side model's copies and clears on {@code commandBuffer}. */
    private PostTargetSides.Ops ops(MemoryStack stack, VkCommandBuffer commandBuffer) {
        return new PostTargetSides.Ops() {
            @Override
            public void copy(PostTargetSides.Image from, PostTargetSides.Image to) {
                VulkanImage source = image(from);
                VulkanImage destination = image(to);
                if (source == null || destination == null || source == destination) return;
                PackTargetImages.copyImage(stack, commandBuffer, source, destination);
                trace("copy " + from + "->" + to);
            }

            @Override
            public void clear(PostTargetSides.Image image) {
                VulkanImage destination = image(image);
                if (destination == null) return;
                PackTargetImages.clearImage(stack, commandBuffer, destination, clearColor(image.target()));
            }
        };
    }

    private void withOps(VkCommandBuffer commandBuffer, Consumer<PostTargetSides.Ops> action) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            action.accept(ops(stack, commandBuffer));
        }
    }

    /** A target's clear colour: colortex0's this frame's, any other's its declared one. */
    private float[] clearColor(int target) {
        if (target == 0 && this.colortex0Clear != null) return this.colortex0Clear.clone();
        TargetSpec spec = this.specs[target];
        return spec == null ? new float[] {0.0F, 0.0F, 0.0F, 0.0F} : spec.clearColor();
    }

    private void clearPendingState() {
        Arrays.fill(this.pendingImages, null);
        this.rendering = false;
    }

    private void finishRendererState() {
        MrtPipelineContext.end();
        Renderer.getInstance().setBoundRenderPass(null);
        Renderer.getInstance().setBoundFramebuffer(null);
    }

    private static int deviceMaxColorAttachments() {
        if (DeviceManager.deviceProperties == null) return PackTargetGraphPlan.LOGICAL_ATTACHMENT_LIMIT;
        return DeviceManager.deviceProperties.limits().maxColorAttachments();
    }

    private static long imageId(VulkanImage image) {
        return image == null ? 0L : image.getId();
    }

    private static void trace(String message) {
        if (Boolean.getBoolean("chimera.traceTransitions")) {
            LOGGER.info("[chimera] pack target {}", message);
        }
    }
}
