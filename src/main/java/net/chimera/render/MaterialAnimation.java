// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import java.util.List;

/**
 * One animated material map in an atlas slot: its frame schedule, each frame's mip chain, and a ticker that
 * mirrors Minecraft's {@code SpriteContents.Ticker}, so the map keeps step with its atlas.
 *
 * The first schedule entry shows first. Each atlas tick adds one sub-frame; reaching the entry's time moves to
 * the next entry, wrapping, and restarts the count. A map redraws when the frame index changes, and an
 * interpolated map also redraws on every tick while the next entry shows a different frame. A redraw writes
 * every mip level from that frame's own level.
 *
 * Interpolation follows Sodium's {@code SpriteContentsInterpolationMixin}, which Iris runs with:
 * {@code ColorMixer.mix} on the colour channels, weighted by the current frame's {@code 1 - subFrame / time}
 * as a byte, and the current frame's alpha. Sodium's "animate only visible textures" is not mirrored, since a
 * map off screen changes nothing visible.
 */
final class MaterialAnimation {
    /** Frame of the strip to show, and for how many ticks. */
    record Frame(int index, int time) {}

    private final List<Frame> frames;
    private final boolean interpolate;
    /** Logical pixels by strip frame index, then mip level. */
    private final int[][][] frameLevels;
    private final int width;
    private final int height;
    private int frame;
    private int subFrame;

    MaterialAnimation(List<Frame> frames, boolean interpolate, int[][][] frameLevels, int width, int height) {
        if (frames.size() < 2) {
            throw new IllegalArgumentException("MATERIAL_MAP_ANIMATION:needs two frames");
        }
        for (Frame entry : frames) {
            if (entry.index() < 0 || entry.index() >= frameLevels.length || entry.time() <= 0) {
                throw new IllegalArgumentException("MATERIAL_MAP_ANIMATION:frame " + entry);
            }
        }
        this.frames = List.copyOf(frames);
        this.interpolate = interpolate;
        this.frameLevels = frameLevels;
        this.width = width;
        this.height = height;
    }

    /**
     * Advances one atlas tick. True when the slot must be redrawn: the frame index changed, or the map
     * interpolates between two different frames.
     */
    boolean tick() {
        subFrame++;
        boolean changed = false;
        Frame current = frames.get(frame);
        if (subFrame >= current.time()) {
            int previous = current.index();
            frame = (frame + 1) % frames.size();
            subFrame = 0;
            changed = previous != frames.get(frame).index();
        }
        return changed || (interpolate && currentIndex() != nextIndex());
    }

    /** Logical pixels the slot shows now at {@code level}. */
    int[] level(int level) {
        int[] current = frameLevels[currentIndex()][level];
        if (!interpolate || currentIndex() == nextIndex()) {
            return current;
        }
        int[] next = frameLevels[nextIndex()][level];
        // the current frame's weight as a byte, as Sodium's ColorU8.normalizedFloatToByte converts it
        int weight = (int) ((1.0f - (float) subFrame / (float) frames.get(frame).time()) * 255.0f) & 0xFF;
        int[] mixed = new int[current.length];
        for (int i = 0; i < current.length; i++) {
            mixed[i] = mixRgb(current[i], next[i], weight) | current[i] & 0xFF000000;
        }
        return mixed;
    }

    /**
     * Each colour channel of {@code current} weighted by {@code weight / 255} against {@code next}, rounded up
     * as Sodium's interpolation rounds; the alpha byte comes back zero.
     */
    private static int mixRgb(int current, int next, int weight) {
        int rgb = 0;
        for (int shift = 0; shift < 24; shift += 8) {
            int mine = current >>> shift & 0xFF;
            int theirs = next >>> shift & 0xFF;
            rgb |= (mine * weight + theirs * (255 - weight) + 255) >> 8 << shift;
        }
        return rgb;
    }

    int levels() {
        return frameLevels[0].length;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    int currentIndex() {
        return frames.get(frame).index();
    }

    private int nextIndex() {
        return frames.get((frame + 1) % frames.size()).index();
    }
}
