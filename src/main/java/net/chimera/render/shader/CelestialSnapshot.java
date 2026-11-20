// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render.shader;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Where the sun, moon and light are in a frame, shared by the pack uniforms and the shadow matrix so a caster
 * and a receiver cannot disagree. The transform is the sky renderer's own, as in Iris's CelestialUniforms;
 * Chimera's {@code sunPathOffset} shifts the angle before the pack's {@code sunPathRotation} turn.
 */
final class CelestialSnapshot {
    /** The quarter turn about Y the sky places a body with. */
    private static final float QUARTER_TURN_DEGREES = -90.0F;
    /** Positions stay unnormalised: a body sits a hundred units out, as in Iris. */
    private static final float CELESTIAL_LENGTH = 100.0F;

    private static final Matrix4f SCRATCH = new Matrix4f();
    private static final Vector4f VECTOR = new Vector4f();

    private CelestialSnapshot() {}

    /** The pack-facing angle in turns: the raw angle plus a quarter turn, wrapped once as Iris's getSunAngle does. */
    static float angle01(float angleDegrees) {
        return wrapped(angleDegrees) / 360.0F;
    }

    /** Whether the sun lights the world at this raw angle, rather than the moon; Iris keeps the sun at 180. */
    static boolean isDay(float sunAngleDegrees) {
        return wrapped(sunAngleDegrees) <= 180.0F;
    }

    private static float wrapped(float angleDegrees) {
        float angle = angleDegrees + 90.0F;
        if (angle < 0.0F) {
            angle += 360.0F;
        } else if (angle >= 360.0F) {
            angle -= 360.0F;
        }
        return angle;
    }

    /** Eye-space sun or moon position, unnormalised; w = 0 as in Iris, so view bobbing's translation stays out. */
    static void position(Matrix4f gbufferModelView, float sunPathRotation, float offsetDegrees,
                         float angleDegrees, Vector3f dest) {
        SCRATCH.set(gbufferModelView)
                .rotateY((float) Math.toRadians(QUARTER_TURN_DEGREES))
                .rotateZ((float) Math.toRadians(sunPathRotation))
                .rotateX((float) Math.toRadians(angleDegrees + offsetDegrees));
        VECTOR.set(0.0F, CELESTIAL_LENGTH, 0.0F, 0.0F);
        SCRATCH.transform(VECTOR);
        dest.set(VECTOR.x, VECTOR.y, VECTOR.z);
    }

    /** Eye-space up: the sky's quarter turn without the sky angle, as Iris's upPosition. */
    static void upPosition(Matrix4f gbufferModelView, Vector3f dest) {
        SCRATCH.set(gbufferModelView).rotateY((float) Math.toRadians(QUARTER_TURN_DEGREES));
        VECTOR.set(0.0F, CELESTIAL_LENGTH, 0.0F, 0.0F);
        SCRATCH.transform(VECTOR);
        dest.set(VECTOR.x, VECTOR.y, VECTOR.z);
    }

    /** World-space unit light direction: {@link #position}'s rotation without the model view. */
    static void lightVector(float sunPathRotation, float offsetDegrees, float angleDegrees,
                            Vector3f dest) {
        SCRATCH.identity()
                .rotateY((float) Math.toRadians(QUARTER_TURN_DEGREES))
                .rotateZ((float) Math.toRadians(sunPathRotation))
                .rotateX((float) Math.toRadians(angleDegrees + offsetDegrees));
        VECTOR.set(0.0F, 1.0F, 0.0F, 0.0F);
        SCRATCH.transform(VECTOR);
        dest.set(VECTOR.x, VECTOR.y, VECTOR.z).normalize();
    }

    /**
     * The shadow caster's rotation, as Iris's {@code ShadowMatrices.createBaselineModelViewMatrix} builds it.
     * Its view Z axis is {@link #lightVector}, and unlike a look-at it stays defined with the light overhead.
     */
    static void shadowRotation(float sunPathRotation, float offsetDegrees, float angleDegrees,
                               Matrix4f dest) {
        dest.identity()
                .rotateX((float) Math.toRadians(90.0F))
                .rotateZ((float) Math.toRadians(-(angleDegrees + offsetDegrees)))
                .rotateX((float) Math.toRadians(sunPathRotation));
    }
}
