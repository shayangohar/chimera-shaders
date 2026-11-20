// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.mixin;

import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.layout.PushConstants;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Sets a Chimera pipeline's push constants, which VulkanMod's builder takes only from a JSON config. */
@Mixin(value = Pipeline.Builder.class, remap = false)
public interface ChimeraPipelineBuilderAccessor {
    @Accessor("pushConstants")
    void chimera$setPushConstants(PushConstants pushConstants);
}
