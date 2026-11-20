// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render.shader;

import net.chimera.shaderpack.PackBlendPlan;
import net.chimera.shaderpack.SpirvOutputWrites;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;

/**
 * What Chimera keeps on one VulkanMod GraphicsPipeline (ChimeraPipelineSideMixin). A pack load writes it on the
 * render thread, before VulkanMod creates the pipeline's later variants there.
 */
public final class PipelineSide {
    /** A GraphicsPipeline, through ChimeraPipelineSideMixin. */
    public interface Holder {
        PipelineSide chimera$side();
    }

    /** Null for the pass's. */
    MrtPipelineContext.Contract contract;
    /** A bit per output location the fragment stage writes. */
    int writtenOutputs = SpirvOutputWrites.ALL;
    /** Per attachment of a VulkanMod render pass, or null to keep the host's. */
    PackBlendPlan.Mode[] hostPassBlends;
    /** The blend the pipeline's draws report in place of the host's, or null. */
    PackBlendPlan.Mode programBlend;
    /** 0 without a geometry stage; the pipeline's cleanUp destroys it. */
    long geometryModule;

    static PipelineSide of(GraphicsPipeline pipeline) {
        return ((Holder) (Object) pipeline).chimera$side();
    }

    /** Null when the pipeline is not a GraphicsPipeline. */
    static PipelineSide ofBound(Pipeline pipeline) {
        return pipeline instanceof Holder holder ? holder.chimera$side() : null;
    }
}
