// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render.shader;

import net.chimera.shaderpack.PackBlendPlan;
import net.chimera.shaderpack.SpirvOutputWrites;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;

import java.util.Arrays;
import java.util.Objects;

/**
 * Per-thread state GraphicsPipelineMrtMixin reads while VulkanMod creates a pipeline variant. VulkanMod creates
 * variants lazily inside a bound pass, so the pass's formats and blends must be in scope at that call.
 */
public final class MrtPipelineContext {
    public static final int LOGICAL_LIMIT = 8;

    /** A null blend mode keeps the host blend. */
    record Contract(int[] formats, PackBlendPlan.Mode[] blends) {}

    /** A pipeline's registered contract stands in for the pass's while its variant is created. */
    private record Scope(Contract pass, PipelineSide pipeline) {
        static final Scope EMPTY = new Scope(null, null);

        Contract contract() {
            return this.pipeline != null && this.pipeline.contract != null ? this.pipeline.contract : this.pass;
        }
    }

    private static final ThreadLocal<Scope> SCOPE = ThreadLocal.withInitial(() -> Scope.EMPTY);

    private MrtPipelineContext() {}

    public static void begin(int[] formats) {
        begin(formats, LOGICAL_LIMIT);
    }

    public static void begin(int[] formats, int deviceMaxColorAttachments) {
        begin(formats, deviceMaxColorAttachments, null);
    }

    /** {@code blends} may be null when every attachment keeps the host blend. */
    public static void begin(int[] formats, int deviceMaxColorAttachments, PackBlendPlan.Mode[] blends) {
        validate(formats, Math.min(LOGICAL_LIMIT, Math.max(0, deviceMaxColorAttachments)));
        SCOPE.set(new Scope(contract(formats, blends), SCOPE.get().pipeline()));
    }

    /** Ends any pipeline-creation scope too, so nothing outlives the pass. */
    public static void end() {
        SCOPE.remove();
    }

    public static void register(GraphicsPipeline pipeline, int[] formats) {
        register(pipeline, formats, null);
    }

    /** Keeps a pipeline's own formats and blends for the variants VulkanMod creates later. */
    public static void register(GraphicsPipeline pipeline, int[] formats, PackBlendPlan.Mode[] blends) {
        if (pipeline == null) throw new IllegalArgumentException("pipeline is required");
        validate(formats, LOGICAL_LIMIT);
        PipelineSide.of(pipeline).contract = contract(formats, blends);
    }

    public static void recordWrittenOutputs(GraphicsPipeline pipeline, int locations) {
        if (pipeline != null) PipelineSide.of(pipeline).writtenOutputs = locations;
    }

    /** Blends for the pipeline's variants in VulkanMod's own render passes; all-null modes register nothing. */
    public static void registerHostPassBlends(GraphicsPipeline pipeline, PackBlendPlan.Mode[] blends) {
        if (pipeline == null || blends == null || Arrays.stream(blends).allMatch(Objects::isNull)) return;
        PipelineSide.of(pipeline).hostPassBlends = blends.clone();
    }

    /** The caller ends it with {@link #endPipeline} however the creation ends. */
    public static void beginPipeline(GraphicsPipeline pipeline) {
        beginPipeline(pipeline == null ? null : PipelineSide.of(pipeline));
    }

    static void beginPipeline(PipelineSide side) {
        SCOPE.set(new Scope(SCOPE.get().pass(), side));
    }

    public static void endPipeline() {
        Contract pass = SCOPE.get().pass();
        if (pass == null) SCOPE.remove();
        else SCOPE.set(new Scope(pass, null));
    }

    public static int[] colorFormats() {
        Contract contract = SCOPE.get().contract();
        return contract == null ? null : contract.formats().clone();
    }

    /** Null when every attachment keeps the host blend. */
    public static PackBlendPlan.Mode[] attachmentBlends() {
        Contract contract = SCOPE.get().contract();
        return contract == null || contract.blends() == null ? null : contract.blends().clone();
    }

    public static PackBlendPlan.Mode[] hostPassBlends() {
        PipelineSide side = SCOPE.get().pipeline();
        return side == null || side.hostPassBlends == null ? null : side.hostPassBlends.clone();
    }

    /** A bit per written output location; SpirvOutputWrites.ALL when unrecorded, as for non-pack pipelines. */
    public static int writtenOutputs() {
        PipelineSide side = SCOPE.get().pipeline();
        return side == null ? SpirvOutputWrites.ALL : side.writtenOutputs;
    }

    static Contract contract(int[] formats, PackBlendPlan.Mode[] blends) {
        if (blends != null && blends.length != formats.length) {
            throw new IllegalArgumentException("blend modes do not match the " + formats.length + " attachments");
        }
        return new Contract(formats.clone(), blends == null ? null : blends.clone());
    }

    private static void validate(int[] formats, int limit) {
        int count = formats == null ? 0 : formats.length;
        if (count < 1 || count > limit) {
            throw new IllegalArgumentException("MRT needs 1 to " + limit + " attachments, got " + count);
        }
    }
}
