// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render.vertex;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.lwjgl.system.MemoryUtil;

import java.lang.reflect.Field;

/**
 * A vanilla BufferBuilder with ChimeraBufferBuilderMixin's hooks applied by hand, since tests run without
 * mixins. The arena starts filled with {@link #STALE}, so a byte nothing wrote shows.
 */
final class FamilyBuilderHarness implements AutoCloseable {
    static final int STALE = 0xAB;
    private static final Field VERTEX_POINTER = field(BufferBuilder.class, "vertexPointer");
    private static final Field ELEMENTS_TO_FILL = field(BufferBuilder.class, "elementsToFill");
    private static final Field ARENA_BASE = field(ByteBufferBuilder.class, "pointer");

    final FamilyVertexWriter writer;
    /** What the renderer reports when the hooks sample it. */
    int entityId;
    int blockEntityId;
    int itemId;
    boolean renderingLevel;

    private final int stride;
    private final ByteBufferBuilder arena;
    private final BufferBuilder builder;
    private MeshData mesh;

    FamilyBuilderHarness(VertexFormat format, VertexFormat.Mode mode) {
        this(format, mode, 1024, true);
    }

    /** {@code capacity} is the arena's initial size; without {@code hooks} the builder is plain vanilla. */
    FamilyBuilderHarness(VertexFormat format, VertexFormat.Mode mode, int capacity, boolean hooks) {
        this.stride = format.getVertexSize();
        this.arena = new ByteBufferBuilder(capacity);
        MemoryUtil.memSet(base(), STALE, capacity);
        this.builder = new BufferBuilder(arena, mode, format);
        this.writer = hooks ? FamilyVertexWriter.forBuilder(format, mode) : null;
    }

    void ids(int entity, int blockEntity, int item) {
        entityId = entity;
        blockEntityId = blockEntity;
        itemId = item;
    }

    /** Starts a vertex through the host's addVertex, with the hooks at endLastVertex and beginVertex. */
    BufferBuilder vertex(float x, float y, float z) {
        endLastVertex();
        builder.addVertex(x, y, z);
        if (writer != null) {
            long base = base();
            writer.fillCompletePolygon(base, renderingLevel);
            writer.startVertex(base, read(VERTEX_POINTER, builder), entityId, blockEntityId, itemId);
        }
        return builder;
    }

    /** Builds the mesh with the hooks at build and endLastVertex; the harness closes it. */
    MeshData build() {
        if (writer != null) writer.fillCompletePolygon(base(), renderingLevel);
        endLastVertex();
        mesh = builder.build();
        return mesh;
    }

    /** The builder's pending element set for the vertex being written. */
    int pendingElements() {
        try {
            return ELEMENTS_TO_FILL.getInt(builder);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    byte[] bytes(int vertex, int offset, int length) {
        byte[] bytes = new byte[length];
        long at = base() + (long) vertex * stride + offset;
        for (int index = 0; index < length; index++) bytes[index] = MemoryUtil.memGetByte(at + index);
        return bytes;
    }

    int word(int vertex, int offset) {
        return MemoryUtil.memGetInt(base() + (long) vertex * stride + offset);
    }

    float floatAt(int vertex, int offset) {
        return MemoryUtil.memGetFloat(base() + (long) vertex * stride + offset);
    }

    @Override
    public void close() {
        if (mesh != null) mesh.close();
        arena.close();
    }

    private void endLastVertex() {
        if (writer == null) return;
        writer.fillCompletePolygon(base(), renderingLevel);
        try {
            ELEMENTS_TO_FILL.setInt(builder, ELEMENTS_TO_FILL.getInt(builder) & ~writer.ownElements());
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    private long base() {
        return read(ARENA_BASE, arena);
    }

    private static long read(Field field, Object owner) {
        try {
            return field.getLong(owner);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    private static Field field(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            throw new AssertionError(e);
        }
    }
}
