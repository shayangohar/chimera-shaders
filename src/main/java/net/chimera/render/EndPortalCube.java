// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;

import java.util.function.Predicate;

/**
 * The end portal and gateway as an opaque cube with the end portal texture, so the pack's programs can shade
 * it; vanilla's portal shader takes position-only vertices, which they cannot draw. Every face shows the same
 * square texture window, unmirrored from outside, sliding with the pack's frame time.
 */
public final class EndPortalCube {
    /** The side of the texture window each face shows, in UV units. */
    static final float WINDOW = 0.2F;
    /** How far the window slides along u and v per second; it comes round again every 100 seconds. */
    private static final float SCROLL_PER_SECOND = 0.01F;
    private static final float RED = 0.075F;
    private static final float GREEN = 0.15F;
    private static final float BLUE = 0.2F;

    private final VertexConsumer consumer;
    private final PoseStack.Pose pose;
    private final float top;
    private final float bottom;
    private final int light;
    private final int overlay;

    private EndPortalCube(VertexConsumer consumer, PoseStack.Pose pose, float top, float bottom, int light,
                          int overlay) {
        this.consumer = consumer;
        this.pose = pose;
        this.top = top;
        this.bottom = bottom;
        this.light = light;
        this.overlay = overlay;
    }

    /**
     * The window's offset along u and v for the pack's frame time in seconds, in [0, 1). The frame time resets
     * every hour, a whole number of the window's periods, so the reset does not show.
     */
    public static float scroll(float frameTimeCounter) {
        return frameTimeCounter * SCROLL_PER_SECOND % 1.0F;
    }

    /** Draws the faces the predicate lets through, one quad each, in the block's own coordinates. */
    public static void draw(VertexConsumer consumer, PoseStack.Pose pose, Predicate<Direction> faces, float top,
                            float bottom, float scroll, int light, int overlay) {
        EndPortalCube cube = new EndPortalCube(consumer, pose, top, bottom, light, overlay);
        for (Direction face : Direction.values()) {
            if (faces.test(face)) cube.face(face, scroll, WINDOW + scroll);
        }
    }

    private void face(Direction face, float low, float high) {
        Direction u = uGrowth(face);
        Direction v = vGrowth(face);
        // from the corner where u and v are lowest, along v and then along u: counter-clockwise from outside
        vertex(face, u.getOpposite(), v.getOpposite(), low, low);
        vertex(face, u.getOpposite(), v, low, high);
        vertex(face, u, v, high, high);
        vertex(face, u, v.getOpposite(), high, low);
    }

    /** The face's corner that lies toward {@code uSide} and {@code vSide}, with its texture coordinates. */
    private void vertex(Direction face, Direction uSide, Direction vSide, float u, float v) {
        // each component is 1 or -1: the end of the cube the corner takes on that axis
        Vec3i corner = face.getNormal().relative(uSide).relative(vSide);
        float x = corner.getX() > 0 ? 1.0F : 0.0F;
        float y = corner.getY() > 0 ? top : bottom;
        float z = corner.getZ() > 0 ? 1.0F : 0.0F;
        consumer.addVertex(pose, x, y, z)
                .setColor(RED, GREEN, BLUE, 1.0F)
                .setUv(u, v)
                .setOverlay(overlay)
                .setLight(light)
                .setNormal(pose, face.getStepX(), face.getStepY(), face.getStepZ());
    }

    /** The direction u grows toward on a face. */
    private static Direction uGrowth(Direction face) {
        return switch (face) {
            case UP, EAST -> Direction.NORTH;
            case DOWN, SOUTH -> Direction.EAST;
            case NORTH, WEST -> Direction.DOWN;
        };
    }

    /**
     * The direction v grows toward on a face: a quarter turn from u's, so that the outward normal crossed with v
     * gives u and the window is not mirrored.
     */
    private static Direction vGrowth(Direction face) {
        return switch (face) {
            case UP, NORTH -> Direction.EAST;
            case DOWN, WEST -> Direction.NORTH;
            case SOUTH, EAST -> Direction.DOWN;
        };
    }
}
