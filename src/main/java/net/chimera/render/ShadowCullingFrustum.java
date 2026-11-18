// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import net.chimera.shaderpack.ShadowCullingPlan.Culler;
import net.chimera.shaderpack.ShadowCullingPlan.Mode;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Which chunk sections and entities one shadow frame draws: those that could darken something the camera sees.
 *
 * Every mode keeps a box only where it meets the camera-centred outer cube, when there is one. The advanced and
 * safe-zone modes also need the box to reach the sweep: the camera frustum extruded without limit toward the
 * light, since a caster shades the points behind it along the light. The safe-zone mode also draws every box that
 * meets its inner cube, within the outer one, whatever the view, so a pack that voxelises the scene in its shadow
 * program receives every caster near the camera.
 *
 * The sweep is tested one bounding plane at a time: a box is culled only when a single plane has all of it
 * outside, so a box just past one of the sweep's corners can still be kept. Nothing here adds a margin; callers
 * pad the boxes they need to ({@link #SECTION_MARGIN}).
 *
 * Immutable once built. {@link #visible} and {@link #forced} allocate nothing, since they run for every section
 * and entity each frame.
 */
public final class ShadowCullingFrustum {
    /**
     * How far, in blocks, a chunk section's box is padded on every side before it is tested. A block model may
     * reach a block outside its own cell, so geometry can stick out of its section by up to a block; the last
     * eighth is slack.
     */
    public static final float SECTION_MARGIN = 1.125F;

    private static final Vector4f[] NO_PLANES = new Vector4f[0];
    private static final ShadowCullingFrustum NONE =
            new ShadowCullingFrustum(Culler.NONE, new Matrix4f(), new Vector3f());

    private final Culler culler;
    /** Half-size of the cube around the camera that every drawn box meets; infinite when there is none. */
    private final double outerHalfSize;
    private final boolean hasSafeZone;
    private final double safeZoneHalfSize;
    /** The sweep's bounding planes as (inward normal, offset); none when the mode does not sweep. */
    private final Vector4f[] sweep;

    /**
     * @param cameraViewProjection the camera's projection times its model-view, camera-relative, with GL clip
     *                             depth; read here and not kept
     * @param lightVector          the world direction toward the shadow light; only its direction counts, and
     *                             it is read here and not kept
     */
    public ShadowCullingFrustum(Culler culler, Matrix4fc cameraViewProjection, Vector3fc lightVector) {
        Mode mode = culler.mode();
        this.culler = culler;
        this.outerHalfSize = mode == Mode.NONE ? Double.POSITIVE_INFINITY : culler.box();
        this.hasSafeZone = mode == Mode.SAFE_ZONE;
        this.safeZoneHalfSize = culler.safeZone();
        boolean swept = mode == Mode.ADVANCED || mode == Mode.SAFE_ZONE;
        this.sweep = swept ? sweepPlanes(cameraViewProjection, lightVector) : NO_PLANES;
    }

    /** Culls nothing and forces nothing. */
    public static ShadowCullingFrustum none() {
        return NONE;
    }

    public Culler culler() {
        return culler;
    }

    /** Whether the camera-relative box goes into the shadow map. */
    public boolean visible(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return forced(minX, minY, minZ, maxX, maxY, maxZ)
                || (meets(outerHalfSize, minX, minY, minZ, maxX, maxY, maxZ)
                && reachesSweep(minX, minY, minZ, maxX, maxY, maxZ));
    }

    /** Whether the camera-relative box is drawn whatever the view and the light: only in the safe zone. */
    public boolean forced(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return hasSafeZone && meets(outerHalfSize, minX, minY, minZ, maxX, maxY, maxZ)
                && meets(safeZoneHalfSize, minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Whether the box meets the closed cube of this half-size around the camera. The comparison is in double, so
     * a half-size that is not a float is not rounded.
     */
    private static boolean meets(double halfSize, float minX, float minY, float minZ, float maxX, float maxY,
                                 float maxZ) {
        return !(maxX < -halfSize || minX > halfSize
                || maxY < -halfSize || minY > halfSize
                || maxZ < -halfSize || minZ > halfSize);
    }

    /** Whether no single bounding plane of the sweep has the whole box strictly outside it. */
    private boolean reachesSweep(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        for (Vector4f plane : sweep) {
            // the corner farthest along the inward normal
            float x = plane.x < 0.0F ? minX : maxX;
            float y = plane.y < 0.0F ? minY : maxY;
            float z = plane.z < 0.0F ? minZ : maxZ;
            if (plane.x * x + plane.y * y + plane.z * z + plane.w < 0.0F) return false;
        }
        return true;
    }

    /**
     * The planes bounding the camera frustum swept toward the light. A face turned away from the light, or
     * parallel to it, still bounds the sweep; one turned toward the light is swept past. Where a face turned away
     * meets a face turned toward the light, the sweep is bounded by the plane through their shared edge that runs
     * parallel to the light. With no light, the sweep is the frustum.
     */
    private static Vector4f[] sweepPlanes(Matrix4fc camera, Vector3fc light) {
        Vector4f[] faces = new Vector4f[6];
        // the inward normal's component along the light: positive on a face turned away from the light
        float[] along = new float[6];
        for (int face = 0; face < faces.length; face++) {
            faces[face] = camera.frustumPlane(face, new Vector4f());
            along[face] = faces[face].x * light.x() + faces[face].y * light.y() + faces[face].z * light.z();
        }
        List<Vector4f> planes = new ArrayList<>();
        for (int face = 0; face < faces.length; face++) {
            if (along[face] >= 0.0F) planes.add(faces[face]);
        }
        for (int away = 0; away < faces.length; away++) {
            for (int toward = 0; toward < faces.length; toward++) {
                if (along[away] > 0.0F && along[toward] < 0.0F && !opposite(away, toward)) {
                    planes.add(rimPlane(faces[away], along[away], faces[toward], along[toward]));
                }
            }
        }
        return planes.toArray(NO_PLANES);
    }

    /** JOML numbers the frustum's planes in opposite pairs: -x and +x, -y and +y, near and far. */
    private static boolean opposite(int face, int other) {
        return face / 2 == other / 2;
    }

    /**
     * The plane through the edge where a face turned away from the light meets one turned toward it, parallel to
     * the light. Every plane through that edge is a blend of the two face planes; these weights cancel the
     * light's component, and being both positive they keep the frustum, and so the whole sweep, inside.
     */
    private static Vector4f rimPlane(Vector4f away, float awayAlong, Vector4f toward, float towardAlong) {
        return new Vector4f(away).mul(-towardAlong).fma(awayAlong, toward);
    }
}
