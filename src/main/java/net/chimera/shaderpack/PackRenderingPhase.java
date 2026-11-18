// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

/** Iris's WorldRenderingPhase. Shaders read the ordinals, so the order is fixed. */
public enum PackRenderingPhase {
    NONE, SKY, SUNSET, CUSTOM_SKY, SUN, MOON, STARS, VOID,
    TERRAIN_SOLID, TERRAIN_CUTOUT_MIPPED, TERRAIN_CUTOUT,
    ENTITIES, BLOCK_ENTITIES, DESTROY, OUTLINE, DEBUG, HAND_SOLID,
    TERRAIN_TRANSLUCENT, TRIPWIRE, PARTICLES, CLOUDS, RAIN_SNOW,
    WORLD_BORDER, HAND_TRANSLUCENT
}
