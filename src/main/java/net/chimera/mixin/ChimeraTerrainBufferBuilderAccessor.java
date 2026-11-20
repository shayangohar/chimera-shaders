// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.mixin;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.vulkanmod.render.vertex.TerrainBufferBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Sets a Chimera terrain builder's vertex format, since VulkanMod's constructor accepts only its own. */
@Mixin(value = TerrainBufferBuilder.class, remap = false)
public interface ChimeraTerrainBufferBuilderAccessor {
    @Mutable
    @Accessor("format")
    void chimera$setFormat(VertexFormat format);
}
