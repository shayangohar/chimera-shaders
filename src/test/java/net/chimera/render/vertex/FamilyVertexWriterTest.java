package net.chimera.render.vertex;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static net.chimera.render.vertex.ChimeraEntityVertexDataTest.assertWord;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The extra elements a family builder's vertices carry, through the hooks {@link FamilyBuilderHarness} applies:
 * ids and defaults as each vertex starts, then each polygon's mid-texture coordinate, tangent and level face
 * normal. Computed tangents, normals and mid coordinates may differ from the reference by one in a packed byte
 * or by one ulp; ids, defaults and every untouched byte must match exactly.
 */
final class FamilyVertexWriterTest {
    /** A family format's stride and offsets; normal is -1 where the format has none. */
    private record Layout(String name, VertexFormat format, int stride, int normal, int ids, int mid, int tangent) {}

    private static final Layout ENTITY =
            new Layout("entity", ChimeraVertexFormats.EXTENDED_ENTITY, 56, 32, 36, 44, 52);
    private static final Layout BLOCK = new Layout("block", ChimeraVertexFormats.EXTENDED_BLOCK, 52, 28, 32, 40, 48);
    private static final Layout GLYPH = new Layout("glyph", ChimeraVertexFormats.EXTENDED_GLYPH, 52, 28, 32, 40, 48);
    private static final Layout PARTICLE =
            new Layout("particle", ChimeraVertexFormats.EXTENDED_PARTICLE, 48, -1, 28, 36, 44);
    private static final Layout MAP_BACKGROUND = new Layout("map background",
            ChimeraVertexFormats.handFormats().get(DefaultVertexFormat.POSITION_TEX_COLOR), 44, -1, 24, 32, 40);

    private static final int LIGHT = 0x00F000F0;
    private static final float[] UP = {0, 1, 0};
    private static final float[] SOUTH = {0, 0, 1};
    // corners as (x, y, z, u, v)
    private static final float[][] SQUARE = {{0, 0, 0, 0, 1}, {1, 0, 0, 1, 1}, {1, 1, 0, 1, 0}, {0, 1, 0, 0, 0}};
    /** A quad turned 30 degrees about Y. */
    private static final float[][] ROTATED = {{0, 0, 0, 0.25f, 0.5625f}, {0.8660254f, 0, -0.5f, 0.3125f, 0.5625f},
            {0.8660254f, 1, -0.5f, 0.3125f, 0.5f}, {0, 1, 0, 0.25f, 0.5f}};
    private static final float[][] TRIANGLE = {{0, 0, 0, 0.1f, 0.2f}, {1, 0, 0, 0.2f, 0.2f}, {0, 1, 0, 0.1f, 0.4f}};
    private static final float[][] TRIANGLE_NORMALS = {{0, 0, 1}, {0.6f, 0, 0.8f}, {0, 0.6f, 0.8f}};
    private static final float[][] POINT = {{1, 2, 3, 0, 0}, {1, 2, 3, 1, 0}, {1, 2, 3, 1, 1}, {1, 2, 3, 0, 1}};

    @Test
    void everyVertexStartsWithItsIdsAndDefaults() {
        for (Layout layout : List.of(ENTITY, BLOCK, GLYPH, PARTICLE, MAP_BACKGROUND)) {
            assertEquals(layout.stride(), layout.format().getVertexSize(), layout.name());
            try (FamilyBuilderHarness harness = harness(layout, Mode.QUADS)) {
                harness.ids(70000, -1, 5);
                corner(harness, SQUARE[0], layout.normal() >= 0 && layout != GLYPH ? UP : null);
                harness.build();
                assertArrayEquals(hex("70 11 FF FF 05 00 00 00"), harness.bytes(0, layout.ids(), 8), layout.name());
                assertArrayEquals(new byte[8], harness.bytes(0, layout.mid(), 8), layout.name() + " mid");
                assertArrayEquals(hex("7F 00 00 7F"), harness.bytes(0, layout.tangent(), 4), layout.name());
                if (layout == GLYPH) {
                    assertArrayEquals(hex("00 7F 00 00"), harness.bytes(0, layout.normal(), 4), "appended normal");
                }
            }
        }
    }

    @Test
    void idsAreSampledAsEachVertexStarts() {
        try (FamilyBuilderHarness harness = harness(ENTITY, Mode.QUADS)) {
            for (int corner = 0; corner < 4; corner++) {
                harness.ids(100 + corner, 200 + corner, 300 + corner);
                corner(harness, ROTATED[corner], SOUTH);
            }
            harness.ids(0, 0, 0);
            harness.build();
            for (int corner = 0; corner < 4; corner++) {
                assertEquals((200 + corner) << 16 | 100 + corner, harness.word(corner, ENTITY.ids()));
                assertEquals(300 + corner, harness.word(corner, ENTITY.ids() + 4));
            }
        }
    }

    @Test
    void levelQuadTakesItsFaceNormal() {
        try (FamilyBuilderHarness harness = harness(ENTITY, Mode.QUADS)) {
            harness.renderingLevel = true;
            polygon(harness, SQUARE, SOUTH);
            harness.build();
            assertMids(harness, ENTITY, 4, 0.5f, 0.5f);
            assertTangents(harness, ENTITY, each(4, "7F 00 00 7F"));
            assertWrittenNormals(harness, ENTITY, 4, "00 00 7F 00");
        }
    }

    @Test
    void rotatedQuad() {
        assertRotatedQuad(1024, true);
        assertRotatedQuad(1024, false);
    }

    /** An arena that holds one vertex grows, and may move, for every later corner of the quad. */
    @Test
    void cornersSurviveArenaGrowth() {
        assertRotatedQuad(ENTITY.stride(), true);
    }

    @Test
    void smoothTrianglesFollowEachCornerNormal() {
        for (boolean level : new boolean[] {true, false}) {
            try (FamilyBuilderHarness harness = harness(ENTITY, Mode.TRIANGLES)) {
                harness.renderingLevel = level;
                smoothPolygon(harness, TRIANGLE, TRIANGLE_NORMALS);
                harness.build();
                assertMids(harness, ENTITY, 3, 0.13333334f, 0.26666668f);
                assertTangents(harness, ENTITY, "7F 00 00 81", "65 00 B4 81", "7F 00 00 81");
                assertHostNormals(harness, ENTITY, "00 00 7F", "4C 00 65", "00 4C 65");
            }
        }
    }

    /** Particles have no normal: a quad with area still takes a real tangent, anything else the flat one. */
    @Test
    void particlesTouchOnlyChimerasElements() {
        float[][] quad = {{-0.1f, -0.1f, 0, 0.5f, 0.25f}, {-0.1f, 0.1f, 0, 0.5f, 0.125f},
                {0.1f, 0.1f, 0, 0.625f, 0.125f}, {0.1f, -0.1f, 0, 0.625f, 0.25f}};
        assertParticle(Mode.QUADS, quad, 0.5625f, 0.1875f, "7F 00 00 81");
        assertParticle(Mode.TRIANGLES, TRIANGLE, 0.13333334f, 0.26666668f, "7F 00 00 7F");
        assertParticle(Mode.QUADS, POINT, 0.5f, 0.5f, "7F 00 00 7F");
    }

    @Test
    void glyphQuadsTakeTheirFaceNormalInTheLevel() {
        float[][] glyph = {{0, 0, 0, 0, 0}, {0, 8, 0, 0, 0.25f}, {6, 8, 0, 0.125f, 0.25f}, {6, 0, 0, 0.125f, 0}};
        for (boolean level : new boolean[] {true, false}) {
            try (FamilyBuilderHarness harness = harness(GLYPH, Mode.QUADS)) {
                harness.renderingLevel = level;
                polygon(harness, glyph, null);
                harness.build();
                assertMids(harness, GLYPH, 4, 0.0625f, 0.125f);
                assertTangents(harness, GLYPH, each(4, "7F 00 00 7F"));
                if (level) {
                    assertWrittenNormals(harness, GLYPH, 4, "00 00 81 00");
                } else {
                    assertNormalBytes(harness, GLYPH, "00 7F 00 00");
                }
            }
        }
        try (FamilyBuilderHarness harness = harness(GLYPH, Mode.QUADS)) {
            polygon(harness, glyph, new float[] {1, 0, 0});
            harness.build();
            assertNormalBytes(harness, GLYPH, "7F 00 00 00");
        }
    }

    /** A quad with no area keeps its normals in the level, and each corner's tangent follows its own. */
    @Test
    void entityQuadsWithoutArea() {
        try (FamilyBuilderHarness harness = harness(ENTITY, Mode.QUADS)) {
            harness.renderingLevel = true;
            polygon(harness, POINT, UP);
            harness.build();
            assertMids(harness, ENTITY, 4, 0.5f, 0.5f);
            assertTangents(harness, ENTITY, each(4, "00 00 7F 7F"));
            assertHostNormals(harness, ENTITY, each(4, "00 7F 00"));
        }
        float[][] line = {{0, 0, 0, 0, 0}, {1, 0, 0, 1, 0}, {2, 0, 0, 1, 1}, {3, 0, 0, 0, 1}};
        try (FamilyBuilderHarness harness = harness(ENTITY, Mode.QUADS)) {
            harness.renderingLevel = true;
            smoothPolygon(harness, line, new float[][] {UP, SOUTH, {0.6f, 0, 0.8f}, UP});
            harness.build();
            assertTangents(harness, ENTITY, "7F 00 00 7F", "7F 00 00 7F", "65 00 B4 7F", "7F 00 00 7F");
            assertHostNormals(harness, ENTITY, "00 7F 00", "00 00 7F", "4C 00 65", "00 7F 00");
        }
    }

    @Test
    void beaconBeamSide() {
        float[][] side = {{0.2f, 0, 0.2f, 0, 1}, {0.2f, 1, 0.2f, 0, 0}, {0.2f, 1, 0.8f, 1, 0}, {0.2f, 0, 0.8f, 1, 1}};
        try (FamilyBuilderHarness harness = harness(BLOCK, Mode.QUADS)) {
            harness.renderingLevel = true;
            polygon(harness, side, UP);
            harness.build();
            assertMids(harness, BLOCK, 4, 0.5f, 0.5f);
            assertTangents(harness, BLOCK, each(4, "00 00 7F 81"));
            assertWrittenNormals(harness, BLOCK, 4, "7F 00 00 00");
        }
    }

    /** U and V are summed in corner order from zero, then divided by the corner count. */
    @Test
    void meanUvBits() {
        float[][] triangle = {{0, 0, 0, 0.1f, 0}, {1, 0, 0, 0.1f, 0}, {0, 1, 0, 0.7f, 1}};
        assertMeanUBits(Mode.TRIANGLES, triangle, 0x3E999999);
        float[][] quad = {{0, 0, 0, 0.1f, 0}, {1, 0, 0, 0.1f, 0}, {1, 1, 0, 0.2f, 1}, {0, 1, 0, 0.3f, 1}};
        assertMeanUBits(Mode.QUADS, quad, 0x3E333334);
    }

    @Test
    void trailingPolygonKeepsItsDefaults() {
        try (FamilyBuilderHarness harness = harness(ENTITY, Mode.QUADS)) {
            polygon(harness, ROTATED, SOUTH);
            corner(harness, SQUARE[0], SOUTH);
            corner(harness, SQUARE[1], SOUTH);
            harness.build();
            assertTangents(harness, ENTITY, each(4, "6D 00 C1 7F"));
            for (int vertex = 4; vertex < 6; vertex++) {
                assertArrayEquals(new byte[8], harness.bytes(vertex, ENTITY.mid(), 8), "vertex " + vertex);
                assertArrayEquals(hex("7F 00 00 7F"), harness.bytes(vertex, ENTITY.tangent(), 4), "vertex " + vertex);
            }
        }
    }

    @Test
    void modesWithoutPolygonsGetDefaultsOnly() {
        for (Mode mode : new Mode[] {Mode.TRIANGLE_FAN, Mode.LINES}) {
            try (FamilyBuilderHarness harness = harness(ENTITY, mode)) {
                harness.renderingLevel = true;
                polygon(harness, ROTATED, SOUTH);
                // LINES repeats every vertex
                int vertices = harness.build().drawState().vertexCount();
                for (int vertex = 0; vertex < vertices; vertex++) {
                    assertArrayEquals(new byte[8], harness.bytes(vertex, ENTITY.mid(), 8), mode + " mid");
                    assertArrayEquals(hex("7F 00 00 7F"), harness.bytes(vertex, ENTITY.tangent(), 4), mode.name());
                    assertArrayEquals(hex("00 00 7F"), harness.bytes(vertex, ENTITY.normal(), 3), mode + " normal");
                }
            }
        }
    }

    /** A quad fills when the next vertex starts, so the level then decides its normals, not the level at build. */
    @Test
    void levelIsSampledWhenThePolygonFills() {
        try (FamilyBuilderHarness harness = harness(ENTITY, Mode.QUADS)) {
            harness.renderingLevel = true;
            polygon(harness, ROTATED, SOUTH);
            harness.renderingLevel = false;
            corner(harness, SQUARE[0], SOUTH);
            harness.renderingLevel = true;
            harness.build();
            assertTangents(harness, ENTITY, each(4, "6D 00 C1 7F"));
            assertHostNormals(harness, ENTITY, each(4, "00 00 7F"));
        }
    }

    @Test
    void theHostWaitsOnlyForItsOwnElements() {
        assertEquals(0x00070000, FamilyVertexWriter.forBuilder(ENTITY.format(), Mode.QUADS).ownElements());
        assertEquals(0x00070000, FamilyVertexWriter.forBuilder(BLOCK.format(), Mode.QUADS).ownElements());
        assertEquals(0x00070020, FamilyVertexWriter.forBuilder(GLYPH.format(), Mode.QUADS).ownElements());
        assertEquals(0x00070000, FamilyVertexWriter.forBuilder(PARTICLE.format(), Mode.QUADS).ownElements());
        try (FamilyBuilderHarness harness = harness(ENTITY, Mode.QUADS)) {
            corner(harness, SQUARE[0], UP);
            assertNotNull(harness.build(), "an entity vertex with every host element");
        }
        try (FamilyBuilderHarness harness = harness(ENTITY, Mode.QUADS)) {
            harness.vertex(0, 0, 0).setColor(-1).setUv(0, 0).setOverlay(0).setNormal(0, 1, 0);
            IllegalStateException missing = assertThrows(IllegalStateException.class, harness::build);
            assertEquals("Missing elements in vertex: UV2", missing.getMessage(), "host elements stay enforced");
        }
        try (FamilyBuilderHarness harness = harness(GLYPH, Mode.QUADS)) {
            corner(harness, SQUARE[0], null);
            assertNotNull(harness.build(), "a glyph vertex with no normal");
        }
        VertexFormat host = DefaultVertexFormat.NEW_ENTITY;
        try (FamilyBuilderHarness harness = new FamilyBuilderHarness(host, Mode.QUADS)) {
            harness.vertex(0, 0, 0);
            assertEquals(host.getElementsMask() & ~VertexFormatElement.POSITION.mask(), harness.pendingElements());
            assertThrows(IllegalStateException.class, harness::build, "a host builder's pending set is its own");
        }
    }

    @Test
    void otherFormatsAreLeftAlone() {
        for (VertexFormat host : List.of(DefaultVertexFormat.NEW_ENTITY, DefaultVertexFormat.BLOCK,
                DefaultVertexFormat.PARTICLE, DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP,
                DefaultVertexFormat.POSITION_TEX_COLOR)) {
            assertNull(FamilyVertexWriter.forBuilder(host, Mode.QUADS), host.toString());
        }
        assertArrayEquals(hostQuad(false), hostQuad(true), "a host builder's bytes are vanilla's");
    }

    private static byte[] hostQuad(boolean hooks) {
        VertexFormat host = DefaultVertexFormat.NEW_ENTITY;
        try (FamilyBuilderHarness harness = new FamilyBuilderHarness(host, Mode.QUADS, 1024, hooks)) {
            harness.ids(70000, -1, 5);
            harness.renderingLevel = true;
            polygon(harness, ROTATED, SOUTH);
            harness.build();
            return harness.bytes(0, 0, 4 * host.getVertexSize());
        }
    }

    private static void assertRotatedQuad(int capacity, boolean level) {
        try (FamilyBuilderHarness harness = new FamilyBuilderHarness(ENTITY.format(), Mode.QUADS, capacity, true)) {
            harness.renderingLevel = level;
            polygon(harness, ROTATED, SOUTH);
            harness.build();
            assertMids(harness, ENTITY, 4, 0.28125f, 0.53125f);
            assertTangents(harness, ENTITY, each(4, "6D 00 C1 7F"));
            if (level) {
                assertWrittenNormals(harness, ENTITY, 4, "3F 00 6D 00");
            } else {
                assertHostNormals(harness, ENTITY, each(4, "00 00 7F"));
            }
        }
    }

    private static void assertParticle(Mode mode, float[][] corners, float midU, float midV, String tangent) {
        try (FamilyBuilderHarness harness = harness(PARTICLE, mode)) {
            harness.ids(70000, -1, 5);
            harness.renderingLevel = true;
            polygon(harness, corners, null);
            // the host elements and the ids, as they stood before the fill
            byte[][] before = new byte[corners.length][];
            for (int corner = 0; corner < corners.length; corner++) {
                before[corner] = harness.bytes(corner, 0, PARTICLE.mid());
            }
            harness.build();
            assertMids(harness, PARTICLE, corners.length, midU, midV);
            assertTangents(harness, PARTICLE, each(corners.length, tangent));
            for (int corner = 0; corner < corners.length; corner++) {
                assertArrayEquals(before[corner], harness.bytes(corner, 0, PARTICLE.mid()), "corner " + corner);
            }
        }
    }

    private static void assertMeanUBits(Mode mode, float[][] corners, int bits) {
        try (FamilyBuilderHarness harness = harness(ENTITY, mode)) {
            polygon(harness, corners, SOUTH);
            harness.build();
            int actual = Float.floatToRawIntBits(harness.floatAt(0, ENTITY.mid()));
            assertTrue(Math.abs(actual - bits) <= 1, "mean U bits " + Integer.toHexString(actual));
        }
    }

    private static FamilyBuilderHarness harness(Layout layout, Mode mode) {
        return new FamilyBuilderHarness(layout.format(), mode);
    }

    /** Writes a corner with every host element of its format; a null normal leaves Normal to Chimera. */
    private static void corner(FamilyBuilderHarness harness, float[] corner, float[] normal) {
        BufferBuilder builder = harness.vertex(corner[0], corner[1], corner[2]);
        builder.setColor(-1).setUv(corner[3], corner[4]).setOverlay(0).setLight(LIGHT);
        if (normal != null) builder.setNormal(normal[0], normal[1], normal[2]);
    }

    private static void polygon(FamilyBuilderHarness harness, float[][] corners, float[] normal) {
        for (float[] corner : corners) corner(harness, corner, normal);
    }

    private static void smoothPolygon(FamilyBuilderHarness harness, float[][] corners, float[][] normals) {
        for (int corner = 0; corner < corners.length; corner++) corner(harness, corners[corner], normals[corner]);
    }

    private static void assertMids(FamilyBuilderHarness harness, Layout layout, int corners, float u, float v) {
        for (int corner = 0; corner < corners; corner++) {
            assertEquals(u, harness.floatAt(corner, layout.mid()), Math.ulp(u), "mid u of corner " + corner);
            assertEquals(v, harness.floatAt(corner, layout.mid() + 4), Math.ulp(v), "mid v of corner " + corner);
        }
    }

    private static void assertTangents(FamilyBuilderHarness harness, Layout layout, String... tangents) {
        for (int corner = 0; corner < tangents.length; corner++) {
            assertWord(word(tangents[corner]), harness.word(corner, layout.tangent()), "tangent of corner " + corner);
        }
    }

    /** A face normal Chimera wrote, all four bytes. */
    private static void assertWrittenNormals(FamilyBuilderHarness harness, Layout layout, int corners, String normal) {
        for (int corner = 0; corner < corners; corner++) {
            assertWord(word(normal), harness.word(corner, layout.normal()), "normal of corner " + corner);
        }
    }

    /** Normals as the caller wrote them: three bytes, the fourth being the host's padding. */
    private static void assertHostNormals(FamilyBuilderHarness harness, Layout layout, String... normals) {
        for (int corner = 0; corner < normals.length; corner++) {
            assertArrayEquals(hex(normals[corner]), harness.bytes(corner, layout.normal(), 3), "corner " + corner);
        }
    }

    private static void assertNormalBytes(FamilyBuilderHarness harness, Layout layout, String normal) {
        for (int corner = 0; corner < 4; corner++) {
            assertArrayEquals(hex(normal), harness.bytes(corner, layout.normal(), 4), "corner " + corner);
        }
    }

    private static String[] each(int corners, String value) {
        String[] values = new String[corners];
        Arrays.fill(values, value);
        return values;
    }

    /** Bytes in memory order, as "7F 00 00 7F". */
    private static byte[] hex(String bytes) {
        String[] parts = bytes.split(" ");
        byte[] values = new byte[parts.length];
        for (int index = 0; index < parts.length; index++) values[index] = (byte) Integer.parseInt(parts[index], 16);
        return values;
    }

    /** The little-endian word of four bytes in memory order. */
    private static int word(String bytes) {
        byte[] values = hex(bytes);
        int word = 0;
        for (int index = 0; index < values.length; index++) word |= (values[index] & 0xFF) << (index * 8);
        return word;
    }
}
