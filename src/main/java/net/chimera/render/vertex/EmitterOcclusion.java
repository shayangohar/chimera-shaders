// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render.vertex;

import net.vulkanmod.render.chunk.build.light.data.LightDataAccess;

/**
 * Vanilla's ambient occlusion for a light-emitting block, in VulkanMod's light word. VulkanMod follows old
 * Sodium's rule of no AO from emitters; vanilla and the Sodium Iris draws with shade every block.
 */
public final class EmitterOcclusion {
    /** Ambient occlusion (12 bits from bit 8) and corner occlusion (8 bits from bit 20). */
    private static final int OCCLUSION_BITS = 0xFFF << 8 | 0xFF << 20;

    private EmitterOcclusion() {}

    /**
     * A non-full occluding block keeps its shape's corners; otherwise a full block that darkens occludes all of
     * them, and any other none.
     */
    public static int occluding(int word, float shade) {
        boolean fullOpaque = LightDataAccess.unpackFO(word);
        int corners = !fullOpaque && LightDataAccess.unpackOP(word) ? LightDataAccess.unpackCO(word)
                : (fullOpaque || LightDataAccess.unpackFC(word)) && shade < 1.0F ? 0xFF : 0;
        return word & ~OCCLUSION_BITS | LightDataAccess.packAO(shade) | LightDataAccess.packCO(corners);
    }
}
