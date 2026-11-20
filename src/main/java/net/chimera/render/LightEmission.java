// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import net.minecraft.world.level.block.state.BlockState;

/**
 * The light a block state emits, as Iris 1.21.1 reads it: the state's own value, through the getter NeoForge
 * deprecates for a level-aware one Iris does not use.
 */
public final class LightEmission {
    private LightEmission() {}

    @SuppressWarnings("deprecation")
    public static int of(BlockState state) {
        return state.getLightEmission();
    }
}
