// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render.shader;

/**
 * Iris's constantMood (CommonUniforms): the cave-sound moodiness the biome ambience handler steps each mood
 * tick, kept in [0, 1]. Unlike the vanilla value playerMood reads, it is not reset when the mood sound plays.
 */
public interface AmbientMoodAccess {
    float chimera$constantMood();
}
