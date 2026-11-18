// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.mixin;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The arena's base address, which moves as it grows, so polygon corners are kept as offsets from it. */
@Mixin(ByteBufferBuilder.class)
public interface ChimeraByteBufferBuilderAccessor {
    @Accessor("pointer")
    long chimera$pointer();
}
