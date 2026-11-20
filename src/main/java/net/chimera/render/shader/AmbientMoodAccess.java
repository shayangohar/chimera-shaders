// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render.shader;

/** Iris's constantMood: cave-sound moodiness in [0, 1], which unlike playerMood is not reset when the sound plays. */
public interface AmbientMoodAccess {
    float chimera$constantMood();
}
