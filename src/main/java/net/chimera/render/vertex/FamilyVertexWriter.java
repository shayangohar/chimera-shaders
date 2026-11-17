package net.chimera.render.vertex;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import org.lwjgl.system.MemoryUtil;

import java.util.List;

/**
 * Fills the elements a family format adds to its host prefix (EntityIds, MidTexCoord, Tangent and the glyph
 * format's Normal) for one host BufferBuilder, which no VertexConsumer call can reach. Every vertex gets the
 * current entity ids and neutral defaults as it starts. Each finished quad or triangle then gets its mean UV
 * as the mid-texture coordinate, a tangent from the positions and UVs its caller wrote and, while the level
 * renders, its face normal, as Iris's extended formats carry them.
 *
 * ChimeraBufferBuilderMixin drives it from the builder's hooks. Polygons are grouped from the builder's first
 * vertex; a partial polygon left at build keeps its defaults. Corners are kept as offsets from the arena's
 * base, since the arena moves when it grows.
 */
public final class FamilyVertexWriter {
    private static final List<String> REQUIRED_ELEMENTS =
            List.of("Position", "UV0", "EntityIds", "MidTexCoord", "Tangent");
    /** The appended normal a vertex keeps unless its caller or its quad's face normal replaces it: +Y. */
    private static final int DEFAULT_APPENDED_NORMAL = ChimeraEntityVertexData.pack(0.0f, 1.0f, 0.0f, 0.0f);

    private final VertexFormat format;
    private final int position;
    private final int uv;
    /** The Normal offset, or -1 on a particle layout, which has none. */
    private final int normal;
    private final int entityIds;
    private final int midTexCoord;
    private final int tangent;
    private final boolean appendsNormal;
    private final int ownElements;
    /** Corners per polygon: 4 in QUADS mode, 3 in TRIANGLES mode, 0 in modes with no polygons to fill. */
    private final int polygonSize;

    private final long[] corners = new long[4];
    private int cornerCount;
    private final float[] x = new float[4];
    private final float[] y = new float[4];
    private final float[] z = new float[4];
    private final float[] u = new float[4];
    private final float[] v = new float[4];
    private final float[] faceNormal = new float[3];

    private FamilyVertexWriter(VertexFormat format, VertexFormat.Mode mode) {
        this.format = format;
        this.position = offset(format, "Position");
        this.uv = offset(format, "UV0");
        this.normal = offset(format, "Normal");
        this.entityIds = offset(format, "EntityIds");
        this.midTexCoord = offset(format, "MidTexCoord");
        this.tangent = offset(format, "Tangent");
        this.appendsNormal = ChimeraVertexFormats.appendsNormal(format);
        this.ownElements = mask(format, "EntityIds") | mask(format, "MidTexCoord") | mask(format, "Tangent")
                | (appendsNormal ? mask(format, "Normal") : 0);
        this.polygonSize = switch (mode) {
            case QUADS -> 4;
            case TRIANGLES -> 3;
            default -> 0;
        };
    }

    /**
     * The writer for a new builder of {@code format}, or null for a builder Chimera leaves alone: any format
     * other than a family format (compared by identity) with the elements this writer fills.
     */
    public static FamilyVertexWriter forBuilder(VertexFormat format, VertexFormat.Mode mode) {
        if (!ChimeraVertexFormats.isFamilyFormat(format)
                || !format.getElementAttributeNames().containsAll(REQUIRED_ELEMENTS)) {
            return null;
        }
        return new FamilyVertexWriter(format, mode);
    }

    /**
     * The mask bits of the elements this writer fills, which the builder must not wait for. They must be
     * cleared just before the builder checks a vertex, since it resets its pending set after the vertex starts.
     */
    public int ownElements() {
        return ownElements;
    }

    /**
     * Writes the defaults of the vertex the builder just reserved at {@code vertex}, whose bytes are stale, and
     * records it as a corner. The ids are the renderer's at this moment. Call {@link #fillCompletePolygon}
     * first, so a vertex never joins a polygon that is already complete.
     */
    public void startVertex(long base, long vertex, int entityId, int blockEntityId, int itemId) {
        long ids = vertex + entityIds;
        MemoryUtil.memPutShort(ids, (short) entityId);
        MemoryUtil.memPutShort(ids + 2, (short) blockEntityId);
        MemoryUtil.memPutShort(ids + 4, (short) itemId);
        MemoryUtil.memPutShort(ids + 6, (short) 0);
        MemoryUtil.memPutLong(vertex + midTexCoord, 0L);
        MemoryUtil.memPutInt(vertex + tangent, ChimeraEntityVertexData.FLAT_TANGENT);
        if (appendsNormal) MemoryUtil.memPutInt(vertex + normal, DEFAULT_APPENDED_NORMAL);
        if (polygonSize > 0) corners[cornerCount++] = vertex - base;
    }

    /**
     * Fills the pending polygon if all its corners have been written, reading them back from the arena at
     * {@code base}; otherwise does nothing. {@code renderingLevel} decides whether a quad takes its face normal.
     */
    public void fillCompletePolygon(long base, boolean renderingLevel) {
        if (polygonSize == 0 || cornerCount < polygonSize) return;
        cornerCount = 0;
        float midU = 0.0f;
        float midV = 0.0f;
        for (int corner = 0; corner < polygonSize; corner++) {
            long at = base + corners[corner];
            x[corner] = MemoryUtil.memGetFloat(at + position);
            y[corner] = MemoryUtil.memGetFloat(at + position + 4);
            z[corner] = MemoryUtil.memGetFloat(at + position + 8);
            u[corner] = MemoryUtil.memGetFloat(at + uv);
            v[corner] = MemoryUtil.memGetFloat(at + uv + 4);
            midU += u[corner];
            midV += v[corner];
        }
        midU /= polygonSize;
        midV /= polygonSize;
        if (polygonSize == 4 && ChimeraEntityVertexData.faceNormal(faceNormal,
                x[0], y[0], z[0], x[1], y[1], z[1], x[2], y[2], z[2], x[3], y[3], z[3])) {
            fillFlatQuad(base, midU, midV, renderingLevel);
        } else {
            fillPerCorner(base, midU, midV);
        }
    }

    /**
     * A quad with area: one tangent from its full-precision face normal for every corner and, on a format that
     * takes it while the level renders, the face normal itself. A particle quad gets the tangent only.
     */
    private void fillFlatQuad(long base, float midU, float midV, boolean renderingLevel) {
        int frame = cornerTangent(faceNormal[0], faceNormal[1], faceNormal[2], false);
        boolean replacesNormal = normal >= 0 && ChimeraVertexFormats.takesFaceNormal(format, renderingLevel);
        int faceWord = ChimeraEntityVertexData.pack(faceNormal[0], faceNormal[1], faceNormal[2], 0.0f);
        for (int corner = 0; corner < 4; corner++) {
            long at = base + corners[corner];
            finishCorner(at, midU, midV, frame);
            if (replacesNormal) MemoryUtil.memPutInt(at + normal, faceWord);
        }
    }

    /**
     * A triangle, or a quad with no area: each corner's tangent follows that corner's own normal as written,
     * over the polygon's first three corners, with the positions flattened as smooth shading wants. A
     * particle layout has no normal, so its corners keep the flat tangent.
     */
    private void fillPerCorner(long base, float midU, float midV) {
        for (int corner = 0; corner < polygonSize; corner++) {
            long at = base + corners[corner];
            int frame = ChimeraEntityVertexData.FLAT_TANGENT;
            if (normal >= 0) {
                int word = MemoryUtil.memGetInt(at + normal);
                float nx = ChimeraEntityVertexData.unpack(word, 0);
                float ny = ChimeraEntityVertexData.unpack(word, 1);
                float nz = ChimeraEntityVertexData.unpack(word, 2);
                frame = cornerTangent(nx, ny, nz, true);
            }
            finishCorner(at, midU, midV, frame);
        }
    }

    private int cornerTangent(float nx, float ny, float nz, boolean flatten) {
        return ChimeraEntityVertexData.tangent(nx, ny, nz, flatten,
                x[0], y[0], z[0], u[0], v[0], x[1], y[1], z[1], u[1], v[1], x[2], y[2], z[2], u[2], v[2]);
    }

    private void finishCorner(long at, float midU, float midV, int frame) {
        MemoryUtil.memPutFloat(at + midTexCoord, midU);
        MemoryUtil.memPutFloat(at + midTexCoord + 4, midV);
        MemoryUtil.memPutInt(at + tangent, frame);
    }

    private static int offset(VertexFormat format, String name) {
        VertexFormatElement element = element(format, name);
        return element == null ? -1 : format.getOffset(element);
    }

    private static int mask(VertexFormat format, String name) {
        return element(format, name).mask();
    }

    private static VertexFormatElement element(VertexFormat format, String name) {
        int index = format.getElementAttributeNames().indexOf(name);
        return index < 0 ? null : format.getElements().get(index);
    }
}
