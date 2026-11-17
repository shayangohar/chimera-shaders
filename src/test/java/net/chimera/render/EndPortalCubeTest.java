package net.chimera.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.core.Direction;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The end portal cube a pack draws: its faces, winding, texture window, colour, light and pose. */
final class EndPortalCubeTest {
    private static final int LIGHT = 0x00F000F0;
    private static final int OVERLAY = 0x000A0000;
    private static final int[] TEAL = {19, 38, 51, 255};

    /** Every face of a gateway, its corners and texture coordinates, at a scroll of 0.125. */
    @Test
    void gatewayCubeGeometry() {
        float p = 0.125F;
        float q = 0.325F;
        Map<Direction, Set<Corner>> expected = new EnumMap<>(Direction.class);
        expected.put(Direction.UP, Set.of(new Corner(0, 1, 0, q, p), new Corner(0, 1, 1, p, p),
                new Corner(1, 1, 1, p, q), new Corner(1, 1, 0, q, q)));
        expected.put(Direction.DOWN, Set.of(new Corner(0, 0, 0, p, q), new Corner(1, 0, 0, q, q),
                new Corner(1, 0, 1, q, p), new Corner(0, 0, 1, p, p)));
        expected.put(Direction.NORTH, Set.of(new Corner(0, 0, 0, q, p), new Corner(0, 1, 0, p, p),
                new Corner(1, 1, 0, p, q), new Corner(1, 0, 0, q, q)));
        expected.put(Direction.SOUTH, Set.of(new Corner(0, 0, 1, p, q), new Corner(1, 0, 1, q, q),
                new Corner(1, 1, 1, q, p), new Corner(0, 1, 1, p, p)));
        expected.put(Direction.WEST, Set.of(new Corner(0, 0, 0, q, q), new Corner(0, 0, 1, q, p),
                new Corner(0, 1, 1, p, p), new Corner(0, 1, 0, p, q)));
        expected.put(Direction.EAST, Set.of(new Corner(1, 0, 0, q, q), new Corner(1, 1, 0, q, p),
                new Corner(1, 1, 1, p, p), new Corner(1, 0, 1, p, q)));

        List<Vertex> vertices = draw(new PoseStack(), face -> true, 1.0F, 0.0F, p);
        assertEquals(24, vertices.size(), "one quad per face");
        Map<Direction, Set<Corner>> drawn = new EnumMap<>(Direction.class);
        for (int start = 0; start < vertices.size(); start += 4) {
            List<Vertex> quad = vertices.subList(start, start + 4);
            Vector3f normal = quad.get(0).normal;
            Direction face = Direction.getNearest(normal.x, normal.y, normal.z);
            assertTrue(normal.equals(face.step(), 0.0F), face + ": the normal is the face's axis");
            assertCounterClockwiseFromOutside(quad, normal, face);
            Set<Corner> corners = new HashSet<>();
            for (Vertex vertex : quad) {
                assertEquals(normal, vertex.normal, face + ": one normal per face");
                assertArrayEquals(TEAL, vertex.colour, face + ": colour");
                assertEquals(LIGHT, vertex.light, face + ": light passes through");
                assertEquals(OVERLAY, vertex.overlay, face + ": overlay passes through");
                corners.add(new Corner(vertex.position.x, vertex.position.y, vertex.position.z, vertex.u, vertex.v));
            }
            drawn.put(face, corners);
        }
        assertEquals(expected, drawn);
    }

    /** The end portal's own faces are its top and bottom: two sheets at its offsets, facing out. */
    @Test
    void portalDrawsTwoSheets() {
        List<Vertex> vertices = draw(new PoseStack(), face -> face.getAxis() == Direction.Axis.Y, 0.75F, 0.375F,
                0.0F);
        assertEquals(8, vertices.size());
        long top = vertices.stream().filter(vertex -> vertex.position.y == 0.75F
                && vertex.normal.equals(new Vector3f(0.0F, 1.0F, 0.0F), 0.0F)).count();
        long bottom = vertices.stream().filter(vertex -> vertex.position.y == 0.375F
                && vertex.normal.equals(new Vector3f(0.0F, -1.0F, 0.0F), 0.0F)).count();
        assertEquals(4, top, "the top sheet faces up");
        assertEquals(4, bottom, "the bottom sheet faces down");
    }

    @Test
    void hiddenFacesEmitNothing() {
        assertEquals(List.of(), draw(new PoseStack(), face -> false, 1.0F, 0.0F, 0.0F));
    }

    /** The window slides 0.01 per second, wraps every 100 seconds and stays 0.2 wide. */
    @Test
    void scrollOffset() {
        float[][] rows = {
                {0.0F, 0.0F, 0.2F}, {1.0F, 0.01F, 0.21000001F}, {12.5F, 0.125F, 0.325F}, {50.0F, 0.5F, 0.7F},
                {99.99F, 0.9999F, 1.1999F}, {100.0F, 0.0F, 0.2F}, {123.4F, 0.23399997F, 0.43399996F},
                {250.0F, 0.5F, 0.7F}, {3599.99F, 0.9999008F, 1.1999009F}, {3600.0F, 0.0F, 0.2F}};
        for (float[] row : rows) {
            float scroll = EndPortalCube.scroll(row[0]);
            assertEquals(row[1], scroll, "offset at " + row[0] + " s");
            Set<Float> coordinates = new HashSet<>();
            for (Vertex vertex : draw(new PoseStack(), face -> face == Direction.UP, 1.0F, 0.0F, scroll)) {
                coordinates.add(vertex.u);
                coordinates.add(vertex.v);
            }
            assertEquals(Set.of(row[1], row[2]), coordinates, "texture coordinates at " + row[0] + " s");
        }
    }

    /** Positions go through the pose, and normals through its normal matrix. */
    @Test
    void poseIsApplied() {
        List<Vertex> plain = draw(new PoseStack(), face -> true, 1.0F, 0.0F, 0.0F);
        PoseStack moved = new PoseStack();
        moved.translate(5.0F, 6.0F, 7.0F);
        List<Vertex> translated = draw(moved, face -> true, 1.0F, 0.0F, 0.0F);
        Quaternionf quarterTurn = new Quaternionf().rotationY((float) Math.toRadians(90.0));
        PoseStack turned = new PoseStack();
        turned.mulPose(quarterTurn);
        List<Vertex> rotated = draw(turned, face -> true, 1.0F, 0.0F, 0.0F);
        for (int index = 0; index < plain.size(); index++) {
            Vertex vertex = plain.get(index);
            assertEquals(new Vector3f(vertex.position).add(5.0F, 6.0F, 7.0F), translated.get(index).position);
            assertEquals(vertex.normal, translated.get(index).normal, "a translation leaves normals alone");
            Vector3f position = quarterTurn.transform(new Vector3f(vertex.position));
            assertTrue(position.equals(rotated.get(index).position, 1.0e-6F), "positions turn with the pose");
            Vector3f normal = quarterTurn.transform(new Vector3f(vertex.normal));
            assertTrue(normal.equals(rotated.get(index).normal, 1.0e-6F), "normals turn with the pose");
            if (vertex.normal.x == 1.0F) {
                assertTrue(rotated.get(index).normal.equals(new Vector3f(0.0F, 0.0F, -1.0F), 1.0e-6F),
                        "east turns to north");
            }
        }
    }

    private static void assertCounterClockwiseFromOutside(List<Vertex> quad, Vector3f normal, Direction face) {
        Vector3f first = quad.get(0).position;
        Vector3f side = new Vector3f(quad.get(1).position).sub(first);
        Vector3f diagonal = new Vector3f(quad.get(2).position).sub(first);
        assertTrue(side.cross(diagonal).dot(normal) > 0.0F, face + " winds counter-clockwise from outside");
    }

    private static List<Vertex> draw(PoseStack poses, Predicate<Direction> faces, float top, float bottom,
                                     float scroll) {
        Recorder recorder = new Recorder();
        EndPortalCube.draw(recorder, poses.last(), faces, top, bottom, scroll, LIGHT, OVERLAY);
        return recorder.vertices;
    }

    /** A corner's position and texture coordinates, with negative zero read as zero. */
    private record Corner(float x, float y, float z, float u, float v) {
        Corner {
            x += 0.0F;
            y += 0.0F;
            z += 0.0F;
        }
    }

    private static final class Vertex {
        final Vector3f position;
        int[] colour;
        float u;
        float v;
        int overlay;
        int light;
        Vector3f normal;

        Vertex(Vector3f position) {
            this.position = position;
        }
    }

    /** Keeps every element of every vertex it is given. */
    private static final class Recorder implements VertexConsumer {
        final List<Vertex> vertices = new ArrayList<>();

        private Vertex last() {
            return vertices.get(vertices.size() - 1);
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            vertices.add(new Vertex(new Vector3f(x, y, z)));
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            last().colour = new int[] {red, green, blue, alpha};
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            last().u = u;
            last().v = v;
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            last().overlay = v << 16 | u;
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            last().light = v << 16 | u;
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            last().normal = new Vector3f(x, y, z);
            return this;
        }
    }
}
