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
 * Which sections and entities a shadow frame draws: boxes inside the camera-centred outer cube that reach the
 * sweep, the camera frustum extruded without limit toward the light. The safe-zone mode also forces every box in
 * its inner cube, for packs that voxelise in the shadow program. The sweep is tested per plane, so a box just past
 * a corner can be kept; callers add any margin. {@link #visible} and {@link #forced} run per box each frame and
 * allocate nothing.
 */
public final class ShadowCullingFrustum {
    /** In blocks: a block model may reach a block outside its cell; the last eighth is slack. */
    public static final float SECTION_MARGIN = 1.125F;

    private static final Vector4f[] NO_PLANES = new Vector4f[0];
    private static final ShadowCullingFrustum NONE =
            new ShadowCullingFrustum(Culler.NONE, new Matrix4f(), new Vector3f());

    private final Culler culler;
    /** Infinite when there is no outer cube. */
    private final double outerHalfSize;
    private final boolean hasSafeZone;
    private final double safeZoneHalfSize;
    /** As (inward normal, offset); none when the mode does not sweep. */
    private final Vector4f[] sweep;

    /**
     * @param cameraViewProjection camera-relative, with GL clip depth; not kept
     * @param lightVector          toward the shadow light; only its direction counts, and it is not kept
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

    /** The box is camera-relative. */
    public boolean visible(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return forced(minX, minY, minZ, maxX, maxY, maxZ)
                || (meets(outerHalfSize, minX, minY, minZ, maxX, maxY, maxZ)
                && reachesSweep(minX, minY, minZ, maxX, maxY, maxZ));
    }

    /** Drawn whatever the view and the light: only in the safe zone. */
    public boolean forced(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return hasSafeZone && meets(outerHalfSize, minX, minY, minZ, maxX, maxY, maxZ)
                && meets(safeZoneHalfSize, minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Compared in double, so a half-size that is not a float is not rounded. */
    private static boolean meets(double halfSize, float minX, float minY, float minZ, float maxX, float maxY,
                                 float maxZ) {
        return !(maxX < -halfSize || minX > halfSize
                || maxY < -halfSize || minY > halfSize
                || maxZ < -halfSize || minZ > halfSize);
    }

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
     * Faces turned away from the light, or parallel to it, bound the sweep; between an away face and a toward face,
     * the plane through their shared edge parallel to the light does. With no light, the sweep is the frustum.
     */
    private static Vector4f[] sweepPlanes(Matrix4fc camera, Vector3fc light) {
        Vector4f[] faces = new Vector4f[6];
        // positive on a face turned away from the light
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
     * A blend of the two face planes whose weights cancel the light's component; both weights are positive, so
     * the frustum stays inside.
     */
    private static Vector4f rimPlane(Vector4f away, float awayAlong, Vector4f toward, float towardAlong) {
        return new Vector4f(away).mul(-towardAlong).fma(awayAlong, toward);
    }
}
