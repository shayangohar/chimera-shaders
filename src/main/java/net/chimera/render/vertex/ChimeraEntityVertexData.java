// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render.vertex;

/**
 * Packing and vector maths shared by the family and modern terrain writers, so both derive the same frames. All in
 * single precision, except the reciprocal square root, computed in double and rounded once.
 */
public final class ChimeraEntityVertexData {
    /** +X, handedness +1. */
    public static final int FLAT_TANGENT = 0x7F00007F;

    /** Squared lengths and UV determinants at or below this count as zero. */
    private static final float EPSILON = 1.0e-12f;

    private ChimeraEntityVertexData() {}

    /** x in the lowest byte; clamped, scaled by 127 and truncated, so 0.99999994 packs as 126 and NaN as 0. */
    public static int pack(float x, float y, float z, float w) {
        return snorm8(x) | snorm8(y) << 8 | snorm8(z) << 16 | snorm8(w) << 24;
    }

    /** Unclamped, so 0x80 reads below -1. */
    public static float unpack(int word, int component) {
        return (byte) (word >>> (component * 8)) / 127.0f;
    }

    /**
     * From the cross product of the diagonals. Returns false and leaves {@code out} alone when the quad has no
     * area; a NaN corner gives a NaN normal.
     */
    public static boolean faceNormal(float[] out, float x0, float y0, float z0, float x1, float y1, float z1,
                                     float x2, float y2, float z2, float x3, float y3, float z3) {
        float ax = x2 - x0;
        float ay = y2 - y0;
        float az = z2 - z0;
        float bx = x3 - x1;
        float by = y3 - y1;
        float bz = z3 - z1;
        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;
        float lengthSquared = dot(nx, ny, nz, nx, ny, nz);
        if (lengthSquared <= EPSILON) return false;
        float scale = inverseLength(lengthSquared);
        out[0] = nx * scale;
        out[1] = ny * scale;
        out[2] = nz * scale;
        return true;
    }

    /**
     * T follows increasing U, perpendicular to n; w is -1 when V runs against cross(T, n). With {@code flatten},
     * positions are first projected onto the plane through the origin perpendicular to n, not renormalised:
     * a no-op in exact arithmetic, but it moves the low bits that decide a truncated byte far from the origin.
     */
    public static int tangent(float nx, float ny, float nz, boolean flatten,
                              float x0, float y0, float z0, float u0, float v0,
                              float x1, float y1, float z1, float u1, float v1,
                              float x2, float y2, float z2, float u2, float v2) {
        if (flatten) {
            float d0 = dot(x0, y0, z0, nx, ny, nz);
            float d1 = dot(x1, y1, z1, nx, ny, nz);
            float d2 = dot(x2, y2, z2, nx, ny, nz);
            x0 -= d0 * nx;
            y0 -= d0 * ny;
            z0 -= d0 * nz;
            x1 -= d1 * nx;
            y1 -= d1 * ny;
            z1 -= d1 * nz;
            x2 -= d2 * nx;
            y2 -= d2 * ny;
            z2 -= d2 * nz;
        }
        float du1 = u1 - u0;
        float dv1 = v1 - v0;
        float du2 = u2 - u0;
        float dv2 = v2 - v0;
        float determinant = du1 * dv2 - du2 * dv1;
        if (!significant(Math.abs(determinant))) return fallbackTangent(nx, ny, nz);
        return frame(nx, ny, nz, 1.0f / determinant, du1, dv1, du2, dv2,
                x1 - x0, y1 - y0, z1 - z0, x2 - x0, y2 - y0, z2 - z0);
    }

    /** cross(axis least aligned with n, n), ties to X then Y, handedness +1. */
    public static int fallbackTangent(float nx, float ny, float nz) {
        float lengthSquared = dot(nx, ny, nz, nx, ny, nz);
        if (!significant(lengthSquared)) return FLAT_TANGENT;
        float scale = inverseLength(lengthSquared);
        nx *= scale;
        ny *= scale;
        nz *= scale;
        float ax = Math.abs(nx);
        float ay = Math.abs(ny);
        float az = Math.abs(nz);
        // cross(axis, n) per unit axis
        if (ax <= ay && ax <= az) return unitTangent(0.0f, -nz, ny);
        if (ay <= az) return unitTangent(nz, 0.0f, -nx);
        return unitTangent(-ny, nx, 0.0f);
    }

    /** {@code s} is the reciprocal UV determinant; B only gives the handedness. */
    private static int frame(float nx, float ny, float nz, float s, float du1, float dv1, float du2, float dv2,
                             float e1x, float e1y, float e1z, float e2x, float e2y, float e2z) {
        float tx = s * (dv2 * e1x - dv1 * e2x);
        float ty = s * (dv2 * e1y - dv1 * e2y);
        float tz = s * (dv2 * e1z - dv1 * e2z);
        float bx = s * (du1 * e2x - du2 * e1x);
        float by = s * (du1 * e2y - du2 * e1y);
        float bz = s * (du1 * e2z - du2 * e1z);
        float normalSquared = dot(nx, ny, nz, nx, ny, nz);
        // a normal with no length is used as given
        if (significant(normalSquared)) {
            float scale = inverseLength(normalSquared);
            nx *= scale;
            ny *= scale;
            nz *= scale;
            float along = dot(tx, ty, tz, nx, ny, nz);
            tx -= along * nx;
            ty -= along * ny;
            tz -= along * nz;
        }
        float tangentSquared = dot(tx, ty, tz, tx, ty, tz);
        if (!significant(tangentSquared)) return fallbackTangent(nx, ny, nz);
        float scale = inverseLength(tangentSquared);
        tx *= scale;
        ty *= scale;
        tz *= scale;
        // the sign of B against cross(T, n); zero and NaN count as right-handed
        float handedness = bx * (ty * nz - tz * ny) + by * (tz * nx - tx * nz) + bz * (tx * ny - ty * nx);
        return pack(tx, ty, tz, handedness < 0.0f ? -1.0f : 1.0f);
    }

    private static int unitTangent(float x, float y, float z) {
        float scale = inverseLength(dot(x, y, z, x, y, z));
        return pack(x * scale, y * scale, z * scale, 1.0f);
    }

    private static int snorm8(float value) {
        return (int) (Math.clamp(value, -1.0f, 1.0f) * 127.0f) & 0xFF;
    }

    private static float dot(float ax, float ay, float az, float bx, float by, float bz) {
        return ax * bx + ay * by + az * bz;
    }

    /** NaN is not significant. */
    private static boolean significant(float magnitude) {
        return Float.isFinite(magnitude) && magnitude > EPSILON;
    }

    private static float inverseLength(float lengthSquared) {
        return (float) (1.0 / Math.sqrt(lengthSquared));
    }
}
