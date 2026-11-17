package net.chimera.render.vertex;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The packing and maths behind the family formats' extra elements and the modern terrain tangent. Words that
 * the maths computes may differ by one in any packed byte from the reference values, which came from an
 * earlier implementation with its own float evaluation order; encodings must match exactly.
 */
final class ChimeraEntityVertexDataTest {
    private static final float NAN = Float.NaN;

    @Test
    void packTruncatesClampedComponents() {
        assertEquals(0x7F00007F, ChimeraEntityVertexData.pack(1, 0, 0, 1));
        assertEquals(0x00007F00, ChimeraEntityVertexData.pack(0, 1, 0, 0));
        assertEquals(0x007F0000, ChimeraEntityVertexData.pack(0, 0, 1, 0));
        assertEquals(0x817DC13F, ChimeraEntityVertexData.pack(0.5f, -0.5f, 0.99f, -1));
        assertEquals(0x0000817F, ChimeraEntityVertexData.pack(2, -3, NAN, 0), "clamped, and NaN packs as 0");
        assertEquals(0x7F7EA759, ChimeraEntityVertexData.pack(0.70710677f, -0.70710677f, 0.99999994f, 1),
                "truncated, not rounded");
        assertEquals(0x0065004C, ChimeraEntityVertexData.pack(0.6f, 0, 0.8f, 0));
        assertEquals(0x7F00007F, ChimeraEntityVertexData.FLAT_TANGENT);
    }

    @Test
    void unpackDividesTheSignedByte() {
        assertEquals(1.0f, ChimeraEntityVertexData.unpack(0x7F, 0));
        assertEquals(-1.0f, ChimeraEntityVertexData.unpack(0x8100, 1));
        assertEquals(-128.0f / 127.0f, ChimeraEntityVertexData.unpack(0x800000, 2), "no clamp");
        assertEquals(0.7007874f, ChimeraEntityVertexData.unpack(0x59000000, 3), 1.0e-7f);
        assertEquals(0.5984252f, ChimeraEntityVertexData.unpack(0x4C, 0), 1.0e-7f);
        assertEquals(0.007874016f, ChimeraEntityVertexData.unpack(0x01, 0), 1.0e-9f);
        int word = 0x817DC13F;
        assertArrayEquals(new float[] {63.0f / 127.0f, -63.0f / 127.0f, 125.0f / 127.0f, -1.0f},
                new float[] {ChimeraEntityVertexData.unpack(word, 0), ChimeraEntityVertexData.unpack(word, 1),
                        ChimeraEntityVertexData.unpack(word, 2), ChimeraEntityVertexData.unpack(word, 3)});
    }

    @Test
    void faceNormalCrossesTheDiagonals() {
        assertFaceNormal(0x007F0000, 0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0);
        assertFaceNormal(0x00810000, 0, 0, 0, 0, 8, 0, 6, 8, 0, 6, 0, 0);
        assertFaceNormal(0x006D003F, 0, 0, 0, 0.8660254f, 0, -0.5f, 0.8660254f, 1, -0.5f, 0, 1, 0);
        assertFaceNormal(0x007F0000, 0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 0, 0);
        float[] out = new float[3];
        assertTrue(ChimeraEntityVertexData.faceNormal(out, 0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0));
        assertArrayEquals(new float[] {0, 0, 1}, out, 1.0e-6f, "the unit square faces +Z");
    }

    @Test
    void faceNormalRejectsQuadsWithoutArea() {
        assertDegenerate(0, 0, 0, 1, 0, 0, 2, 0, 0, 3, 0, 0);
        assertDegenerate(0, 0, 0, 1, 1, 0, 1, 0, 0, 0, 1, 0);
        float small = 7.0e-4f;
        assertDegenerate(0, 0, 0, small, 0, 0, small, small, 0, 0, small, 0);
        float large = 7.1e-4f;
        assertFaceNormal(0x007F0000, 0, 0, 0, large, 0, 0, large, large, 0, 0, large, 0);
    }

    @Test
    void faceNormalLetsNanThrough() {
        float[] out = new float[3];
        assertTrue(ChimeraEntityVertexData.faceNormal(out, NAN, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0),
                "a NaN corner is not degenerate");
        assertTrue(Float.isNaN(out[0]) && Float.isNaN(out[1]) && Float.isNaN(out[2]), "a NaN normal");
        assertEquals(0, ChimeraEntityVertexData.pack(out[0], out[1], out[2], 0));
    }

    @Test
    void tangentFollowsU() {
        assertWord(0x7F00007F, tangent(0, 1, 0, 0, 1, 0, 0, 0, 0, 1, 1, 0, 1, 1, 1, 1, 1, 1), "up");
        assertWord(0x7F000081, tangent(0, 0, -1, 1, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 1), "north");
        assertWord(0x7F810000, tangent(1, 0, 0, 1, 1, 1, 0, 0, 1, 0, 1, 0, 1, 1, 0, 0, 1, 1), "east");
        assertWord(0x8100007F, tangent(0, 0, -1, 1, 1, 0, 1, 0, 1, 0, 0, 1, 1, 0, 0, 0, 0, 1), "mirrored");
        assertWord(0x7F00007F, tangent(0, 0, 1, 0, 0, 0, 0, 1, 1, 0, 0, 1, 1, 1, 1, 0, 1, 0), "square +Z");
        assertWord(0x81000081, tangent(0, 0, 1, 0, 0, 0, 1, 1, 1, 0, 0, 0, 1, 1, 1, 0, 0, 0), "mirrored U");
        assertWord(0x8100A759, tangent(0, 0, 1, 0, 0, 0, 0.5f, 0, 1, 0, 0, 1, 0.5f, 0, 1, 0, 0, 0.5f),
                "UV rotated 45 degrees");
        assertWord(0x7F00007F, tangent(0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 0, 1, 1, 1, 1, 0, 1, 0),
                "a zero normal skips re-orthogonalisation, and h = 0 is right-handed");
        assertWord(0x7F00007F, tangent(0, 0, 5, 0, 0, 0, 0, 1, 1, 0, 0, 1, 1, 1, 1, 0, 1, 0),
                "n is normalised before use");
    }

    @Test
    void tangentFallsBackWithoutAFrame() {
        assertWord(0x7F008100, tangent(0, 0, 1, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 1, 1, 1, 1), "T along n");
        assertWord(0x7F008100, tangent(0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 1, 0, 0, 0), "UVs all zero");
        assertWord(0x7F008100, tangent(0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 1.0e-6f, 0, 1, 1, 0, 0, 1.0e-6f),
                "a determinant of 1e-12 is not above the threshold");
        assertWord(0x8100007F, tangent(0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 2.0e-6f, 0, 1, 1, 0, 0, 2.0e-6f),
                "a determinant of 4e-12 is");
        assertWord(0x7F008100, tangent(0, 0, 1, 0, 0, 0, 0, 1, 1, 0, 0, Float.POSITIVE_INFINITY, 1, 1, 1, 0, 1, 0),
                "an infinite determinant");
        assertWord(0x7F810000, tangent(1, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1),
                "T along n once re-orthogonalised");
    }

    @Test
    void fallbackTangentCrossesTheLeastAlignedAxis() {
        assertWord(0x7F810000, ChimeraEntityVertexData.fallbackTangent(1, 0, 0), "+X");
        assertWord(0x7F7F0000, ChimeraEntityVertexData.fallbackTangent(-1, 0, 0), "-X");
        assertWord(0x7F7F0000, ChimeraEntityVertexData.fallbackTangent(0, 1, 0), "+Y");
        assertWord(0x7F008100, ChimeraEntityVertexData.fallbackTangent(0, 0, 1), "+Z");
        assertWord(0x7F007F00, ChimeraEntityVertexData.fallbackTangent(0, 0, -1), "-Z");
        assertWord(0x7F004C9B, ChimeraEntityVertexData.fallbackTangent(0.6f, 0.8f, 0), "axis Z");
        assertWord(0x7F59A700, ChimeraEntityVertexData.fallbackTangent(1, 1, 1), "a tie goes to X");
        assertEquals(ChimeraEntityVertexData.FLAT_TANGENT, ChimeraEntityVertexData.fallbackTangent(0, 0, 0));
        assertEquals(ChimeraEntityVertexData.FLAT_TANGENT, ChimeraEntityVertexData.fallbackTangent(NAN, 0, 0));
        assertEquals(ChimeraEntityVertexData.FLAT_TANGENT, ChimeraEntityVertexData.fallbackTangent(0, 0, 1.0e-7f));
    }

    /** Smooth triangles project their positions, with the unpacked normal as given, before the edges. */
    @Test
    void flatteningProjectsPositions() {
        float nx = ChimeraEntityVertexData.unpack(0xF6, 0);
        float ny = ChimeraEntityVertexData.unpack(0x0A00, 1);
        float nz = ChimeraEntityVertexData.unpack(0x700000, 2);
        for (boolean flatten : new boolean[] {true, false}) {
            int word = ChimeraEntityVertexData.tangent(nx, ny, nz, flatten,
                    205.87343f, 393.8694f, 387.559f, 0, 0,
                    206.87343f, 393.8694f, 387.559f, 1, 0,
                    205.87343f, 394.8694f, 387.559f, 0, 1);
            assertWord(flatten ? 0x810B007E : 0x810B017E, word, "far from the origin, flatten " + flatten);
        }
        int normal = 0x0065004C;
        for (boolean flatten : new boolean[] {true, false}) {
            int word = ChimeraEntityVertexData.tangent(ChimeraEntityVertexData.unpack(normal, 0),
                    ChimeraEntityVertexData.unpack(normal, 1), ChimeraEntityVertexData.unpack(normal, 2), flatten,
                    3, 5, 7, 0, 0, 4, 5, 7, 1, 0, 3, 6, 7, 0, 1);
            assertWord(0x81B40065, word, "near the origin, flatten " + flatten);
        }
    }

    @Test
    void nonFiniteInputs() {
        float[] normal = new float[3];
        assertTrue(ChimeraEntityVertexData.faceNormal(normal, NAN, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0));
        int frame = ChimeraEntityVertexData.tangent(normal[0], normal[1], normal[2], false,
                NAN, 0, 0, 0, 1, 1, 0, 0, 1, 1, 1, 1, 0, 1, 0);
        assertEquals(ChimeraEntityVertexData.FLAT_TANGENT, frame, "a NaN corner falls back to flat");
        assertEquals(0, ChimeraEntityVertexData.pack(normal[0], normal[1], normal[2], 0), "its normal packs as 0");
        assertWord(0x7F008100, tangent(0, 0, 1, 0, 0, 0, 0, 1, 3.0e38f, 0, 0, 1, 1, 3.0e38f, 3.0e38f, 0, 1, 0),
                "T overflows near 3e38");
    }

    /** {@link ChimeraEntityVertexData#tangent} with no flattening, one corner as (x, y, z, u, v) after n. */
    private static int tangent(float nx, float ny, float nz, float x0, float y0, float z0, float u0, float v0,
                               float x1, float y1, float z1, float u1, float v1,
                               float x2, float y2, float z2, float u2, float v2) {
        return ChimeraEntityVertexData.tangent(nx, ny, nz, false,
                x0, y0, z0, u0, v0, x1, y1, z1, u1, v1, x2, y2, z2, u2, v2);
    }

    private static void assertFaceNormal(int expected, float... corners) {
        float[] out = new float[3];
        assertTrue(ChimeraEntityVertexData.faceNormal(out, corners[0], corners[1], corners[2], corners[3],
                corners[4], corners[5], corners[6], corners[7], corners[8], corners[9], corners[10], corners[11]));
        assertWord(expected, ChimeraEntityVertexData.pack(out[0], out[1], out[2], 0), "face normal");
    }

    private static void assertDegenerate(float... corners) {
        float[] out = {9, 9, 9};
        assertFalse(ChimeraEntityVertexData.faceNormal(out, corners[0], corners[1], corners[2], corners[3],
                corners[4], corners[5], corners[6], corners[7], corners[8], corners[9], corners[10], corners[11]));
        assertArrayEquals(new float[] {9, 9, 9}, out, "a degenerate quad leaves out alone");
    }

    /** A computed word: each signed byte within one of the reference. */
    static void assertWord(int expected, int actual, String message) {
        for (int component = 0; component < 4; component++) {
            int want = (byte) (expected >>> (component * 8));
            int got = (byte) (actual >>> (component * 8));
            assertTrue(Math.abs(want - got) <= 1, message + ": expected " + Integer.toHexString(expected) + ", got "
                    + Integer.toHexString(actual));
        }
    }
}
