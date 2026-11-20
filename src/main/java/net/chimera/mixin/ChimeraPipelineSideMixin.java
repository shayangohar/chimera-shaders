// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.mixin;

import net.chimera.render.shader.PipelineSide;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Gives every GraphicsPipeline a PipelineSide for Chimera's state, which lives and goes with the pipeline. */
@Mixin(value = GraphicsPipeline.class, remap = false)
public abstract class ChimeraPipelineSideMixin implements PipelineSide.Holder {
    @Unique
    private final PipelineSide chimera$side = new PipelineSide();

    @Override
    public PipelineSide chimera$side() {
        return this.chimera$side;
    }
}
