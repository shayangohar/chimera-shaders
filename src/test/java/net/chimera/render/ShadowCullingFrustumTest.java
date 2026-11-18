// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import net.chimera.shaderpack.ShadowCullingPlan.Culler;
import net.chimera.shaderpack.ShadowCullingPlan.Mode;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the shadow culling sweep guarantees for any camera and light, and how it takes inputs callers never pass. */
final class ShadowCullingFrustumTest {
    private static final List<Vector3f> LIGHTS = List.of(new Vector3f(0.0F, 1.0F, 0.0F),
            new Vector3f(1.0F, 0.0F, 0.0F), new Vector3f(0.0F, 0.0F, -1.0F), new Vector3f(0.0F, 0.0F, 1.0F),
            new Vector3f(0.3F, 0.8F, -0.5F).normalize(), new Vector3f(-0.6F, -0.2F, 0.7F).normalize(),
            new Vector3f());
    /** Box corners well clear of every plane the cameras below put a face on. */
    private static final float[] GRID = {-517.3F, -211.7F, -61.9F, -13.3F, -2.7F, 3.1F, 17.9F, 73.3F, 247.1F};

    /** A tilted perspective camera, and an orthographic one turned about the vertical. */
    private static List<Matrix4f> cameras() {
        Matrix4f perspective = new Matrix4f().perspective((float) Math.toRadians(70.0), 16.0F / 9.0F, 0.05F, 1000.0F);
        return List.of(perspective, new Matrix4f(perspective).rotateX(0.4F).rotateY(-1.1F),
                new Matrix4f().ortho(-30.0F, 30.0F, -20.0F, 20.0F, -50.0F, 50.0F).rotateY(0.7F));
    }

    private static ShadowCullingFrustum sweep(Matrix4f camera, Vector3f light) {
        return new ShadowCullingFrustum(new Culler(Mode.ADVANCED, Double.POSITIVE_INFINITY, 0.0), camera, light);
    }

    /** Whatever the light, a small box around a point the camera sees is kept. */
    @Test
    void whatTheCameraSeesIsKept() {
        for (Matrix4f camera : cameras()) {
            Matrix4f unproject = new Matrix4f(camera).invert();
            for (Vector3f light : LIGHTS) {
                ShadowCullingFrustum sweep = sweep(camera, light);
                for (int i = -3; i <= 3; i++) {
                    for (int j = -3; j <= 3; j++) {
                        for (int k = -3; k <= 3; k++) {
                            Vector3f seen = unproject.transformProject(0.3F * i, 0.3F * j, 0.3F * k, new Vector3f());
                            assertTrue(sweep.visible(seen.x - 0.01F, seen.y - 0.01F, seen.z - 0.01F,
                                    seen.x + 0.01F, seen.y + 0.01F, seen.z + 0.01F), seen + " with light " + light);
                        }
                    }
                }
            }
        }
    }

    /** A kept caster moved toward the light is still kept: whatever it shaded, it still shades. */
    @Test
    void keptBoxesStayKeptTowardTheLight() {
        for (Matrix4f camera : cameras()) {
            for (Vector3f light : LIGHTS) {
                ShadowCullingFrustum sweep = sweep(camera, light);
                Vector3f step = new Vector3f(light).mul(400.0F);
                for (float x : GRID) {
                    for (float y : GRID) {
                        for (float z : GRID) {
                            if (!sweep.visible(x, y, z, x + 1.0F, y + 1.0F, z + 1.0F)) continue;
                            float movedX = x + step.x;
                            float movedY = y + step.y;
                            float movedZ = z + step.z;
                            assertTrue(sweep.visible(movedX, movedY, movedZ, movedX + 1.0F, movedY + 1.0F,
                                    movedZ + 1.0F), x + ", " + y + ", " + z + " moved along " + light);
                        }
                    }
                }
            }
        }
    }

    /** A box with no coordinates and a camera with no frustum keep rather than throw or cull. */
    @Test
    void degenerateInputsKeep() {
        float nan = Float.NaN;
        Matrix4f camera = new Matrix4f().perspective(1.0F, 1.0F, 0.1F, 100.0F);
        Vector3f light = new Vector3f(0.0F, 1.0F, 0.0F);
        for (Mode mode : Mode.values()) {
            ShadowCullingFrustum frustum = new ShadowCullingFrustum(new Culler(mode, 64.0, 32.0), camera, light);
            assertTrue(frustum.visible(nan, nan, nan, nan, nan, nan), mode + ": a box of NaN is drawn");
            assertEquals(mode == Mode.SAFE_ZONE, frustum.forced(nan, nan, nan, nan, nan, nan), mode + ": forced");
        }
        ShadowCullingFrustum flat = sweep(new Matrix4f().zero(), light);
        for (float x : GRID) {
            assertTrue(flat.visible(x, x, x, x + 1.0F, x + 1.0F, x + 1.0F), "a zero camera matrix culls nothing");
        }
    }
}
