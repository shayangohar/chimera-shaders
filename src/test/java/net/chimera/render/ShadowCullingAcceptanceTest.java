package net.chimera.render;

import net.chimera.shaderpack.ShadowCullingPlan.Culler;
import net.chimera.shaderpack.ShadowCullingPlan.Mode;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * What shadow caster culling keeps, for known cameras, lights and boxes, through the public API only.
 *
 * In the advanced modes a caster is kept when it can shade the camera's view: the camera frustum swept without
 * limit toward the light. Boxes touching a face that is axis-aligned and exact in float are kept. A box touching
 * a tilted plane is not pinned, since the answer there depends on rounding; every tilted case below keeps a
 * margin from its plane.
 */
final class ShadowCullingAcceptanceTest {
    private static final boolean KEPT = true;
    private static final boolean CULLED = false;
    private static final double UNBOUNDED = Double.POSITIVE_INFINITY;
    private static final Vector3f UP = new Vector3f(0.0F, 1.0F, 0.0F);
    private static final Vector3f DOWN = new Vector3f(0.0F, -1.0F, 0.0F);
    private static final Vector3f EAST = new Vector3f(1.0F, 0.0F, 0.0F);
    private static final Vector3f OBLIQUE = new Vector3f(1.0F, 1.0F, 0.0F).normalize();
    private static final Vector3f NO_LIGHT = new Vector3f();
    /** Camera-relative cube corners shared by the composition checks. */
    private static final float[] GRID = {-300.0F, -130.0F, -60.0F, -33.0F, -20.0F, -1.0F, 0.0F, 20.0F, 31.5F, 60.0F,
            127.5F, 300.0F};

    /** A camera at the origin looking down -Z: 70 degree vertical field, 16:9, near 0.05, far 1000, GL depth. */
    private static Matrix4f perspective() {
        return new Matrix4f().perspective((float) Math.toRadians(70.0), 16.0F / 9.0F, 0.05F, 1000.0F);
    }

    /** The same camera looking straight down, its screen top toward -Z. */
    private static Matrix4f lookingDown() {
        return perspective().mul(new Matrix4f().lookAt(0.0F, 0.0F, 0.0F, 0.0F, -1.0F, 0.0F, 0.0F, 0.0F, -1.0F));
    }

    /** The clip volume of the identity matrix is the cube [-1, 1] on each axis. */
    private static Matrix4f unitCube() {
        return new Matrix4f();
    }

    private static ShadowCullingFrustum culling(Mode mode, double box, double safeZone, Matrix4f camera,
                                                Vector3f light) {
        return new ShadowCullingFrustum(new Culler(mode, box, safeZone), camera, light);
    }

    private static ShadowCullingFrustum advanced(Matrix4f camera, Vector3f light) {
        return culling(Mode.ADVANCED, UNBOUNDED, 0.0, camera, light);
    }

    private static ShadowCullingFrustum distance(double box) {
        return culling(Mode.DISTANCE, box, 0.0, new Matrix4f(), UP);
    }

    private static void kept(ShadowCullingFrustum frustum, boolean expected, String what,
                             float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        assertEquals(expected, frustum.visible(minX, minY, minZ, maxX, maxY, maxZ), what);
    }

    /** The unit cube with its least corner at (x, y, z). */
    private static void cube(ShadowCullingFrustum frustum, boolean expected, String what, float x, float y, float z) {
        kept(frustum, expected, what, x, y, z, x + 1.0F, y + 1.0F, z + 1.0F);
    }

    /** Both answers: whether the box is drawn, and whether it is drawn whatever the view. */
    private static void drawn(ShadowCullingFrustum frustum, boolean visible, boolean forced, String what,
                              float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        assertEquals(visible, frustum.visible(minX, minY, minZ, maxX, maxY, maxZ), what + ": visible");
        assertEquals(forced, frustum.forced(minX, minY, minZ, maxX, maxY, maxZ), what + ": forced");
    }

    /** A chunk section at (x, y, z), padded by the section margin as the section queue pads it. */
    private static boolean section(ShadowCullingFrustum frustum, float x, float y, float z) {
        float margin = ShadowCullingFrustum.SECTION_MARGIN;
        return frustum.visible(x - margin, y - margin, z - margin,
                x + 16.0F + margin, y + 16.0F + margin, z + 16.0F + margin);
    }

    /** The unit cube camera with the light straight up: kept means |x| <= 1, |z| <= 1 and y >= -1. */
    private static void assertCubeSweptUp(ShadowCullingFrustum sweep) {
        kept(sweep, KEPT, "far toward the light", -0.5F, 1000.0F, -0.5F, 0.5F, 1001.0F, 0.5F);
        kept(sweep, CULLED, "below the face turned away from the light", -0.5F, -3.0F, -0.5F, 0.5F, -2.0F, 0.5F);
        kept(sweep, KEPT, "touching the face turned away", -0.5F, -2.0F, -0.5F, 0.5F, -1.0F, 0.5F);
        kept(sweep, CULLED, "one ulp beyond it", -0.5F, -2.0F, -0.5F, 0.5F, Math.nextDown(-1.0F), 0.5F);
        kept(sweep, KEPT, "touching a face parallel to the light", 1.0F, 5.0F, 0.0F, 2.0F, 6.0F, 0.5F);
        kept(sweep, CULLED, "one ulp beyond it", Math.nextUp(1.0F), 5.0F, 0.0F, 2.0F, 6.0F, 0.5F);
        kept(sweep, KEPT, "touching the parallel face z = -1", 0.0F, 5.0F, -2.0F, 0.5F, 6.0F, -1.0F);
    }

    /** Faces turned away from the light and faces parallel to it bound the sweep; touching one is kept. */
    @Test
    void trailingAndParallelFacesBoundTheSweep() {
        assertCubeSweptUp(advanced(unitCube(), UP));
    }

    /** The light vector's length plays no part. */
    @Test
    void onlyTheLightDirectionMatters() {
        assertCubeSweptUp(advanced(unitCube(), new Vector3f(0.0F, 5.0F, 0.0F)));
    }

    /** With no light direction there is no sweep: the camera frustum alone is kept. */
    @Test
    void zeroLightIsTheCameraFrustum() {
        ShadowCullingFrustum cube = advanced(unitCube(), NO_LIGHT);
        kept(cube, CULLED, "above the frustum", -0.5F, 5.0F, -0.5F, 0.5F, 6.0F, 0.5F);
        kept(cube, KEPT, "touching its top", -0.5F, 1.0F, -0.5F, 0.5F, 2.0F, 0.5F);
        kept(cube, KEPT, "inside it", -0.5F, -0.5F, -0.5F, 0.5F, 0.5F, 0.5F);
        ShadowCullingFrustum view = advanced(perspective(), NO_LIGHT);
        cube(view, KEPT, "in view", 0.0F, 0.0F, -20.0F);
        cube(view, CULLED, "above the view", 0.0F, 50.0F, -20.0F);
        cube(view, CULLED, "beside the view", 200.0F, 0.0F, -20.0F);
    }

    /** A light below the scene sweeps the frustum downward. */
    @Test
    void lightFromBelowSweepsDown() {
        ShadowCullingFrustum sweep = advanced(unitCube(), DOWN);
        kept(sweep, CULLED, "above", -0.5F, 5.0F, -0.5F, 0.5F, 6.0F, 0.5F);
        kept(sweep, KEPT, "far below, toward the light", -0.5F, -1000.0F, -0.5F, 0.5F, -999.0F, 0.5F);
    }

    /** The unit cube swept along (1, 1, 0): x >= -1, y >= -1, -2 <= y - x <= 2 and |z| <= 1. */
    @Test
    void obliqueSweepOnACube() {
        ShadowCullingFrustum sweep = advanced(unitCube(), OBLIQUE);
        kept(sweep, KEPT, "along the diagonal", 9.0F, 9.0F, 0.0F, 10.0F, 10.0F, 0.5F);
        kept(sweep, CULLED, "below the band", 3.0F, 0.0F, 0.0F, 4.0F, 0.5F, 0.5F);
        kept(sweep, KEPT, "inside the band", 3.0F, 1.5F, 0.0F, 4.0F, 2.0F, 0.5F);
        kept(sweep, CULLED, "0.01 below the band", 3.0F, 0.0F, 0.0F, 4.0F, 0.99F, 0.5F);
        kept(sweep, CULLED, "behind the face turned away", -1.5F, 5.0F, 0.0F, -1.01F, 6.0F, 0.5F);
        kept(sweep, CULLED, "0.01 above the band", 0.0F, 2.51F, 0.0F, 0.5F, 3.0F, 0.5F);
        kept(sweep, CULLED, "past the parallel face z = 1", 9.0F, 9.0F, 1.01F, 10.0F, 10.0F, 2.0F);
        kept(sweep, CULLED, "away from the light", -5.0F, -5.0F, 0.0F, -4.0F, -4.0F, 0.5F);
    }

    /** A section's own box is padded by 1.125 blocks, so geometry reaching out of it still casts. */
    @Test
    void sectionMarginAtAFrustumFace() {
        assertEquals(1.125F, ShadowCullingFrustum.SECTION_MARGIN);
        // the clip volume of this scale is the cube [-16, 16] on each axis
        ShadowCullingFrustum sweep = advanced(new Matrix4f().scaling(1.0F / 16.0F), UP);
        kept(sweep, KEPT, "touching x = 16", 16.0F, 0.0F, 0.0F, 17.0F, 1.0F, 1.0F);
        kept(sweep, CULLED, "one ulp past x = 16", Math.nextUp(16.0F), 0.0F, 0.0F, 17.0F, 1.0F, 1.0F);
        kept(sweep, KEPT, "touching y = -16", 0.0F, -17.0F, 0.0F, 1.0F, -16.0F, 1.0F);
        kept(sweep, CULLED, "one ulp past y = -16", 0.0F, -17.0F, 0.0F, 1.0F, Math.nextDown(-16.0F), 1.0F);
        assertEquals(KEPT, section(sweep, 17.125F, 0.0F, 0.0F), "padded to touch x = 16");
        assertEquals(CULLED, section(sweep, 17.25F, 0.0F, 0.0F), "padded to x = 16.125");
        assertEquals(KEPT, section(sweep, 0.0F, -33.125F, 0.0F), "padded to touch y = -16");
        assertEquals(CULLED, section(sweep, 0.0F, -33.25F, 0.0F), "padded to y = -16.125");
    }

    /** The padded section against the distance cube. */
    @Test
    void sectionMarginAtTheDistanceCube() {
        ShadowCullingFrustum box = distance(256.0);
        assertEquals(KEPT, section(box, 0.0F, -180.0F, 0.0F), "well inside");
        assertEquals(KEPT, section(box, 0.0F, 120.0F, 0.0F), "well inside, above");
        assertEquals(KEPT, section(box, 0.0F, -40.0F, 0.0F), "near the camera");
        assertEquals(CULLED, section(box, 0.0F, -300.0F, 0.0F), "well outside");
        assertEquals(KEPT, section(box, 0.0F, -273.125F, 0.0F), "padded to touch y = -256");
        assertEquals(CULLED, section(box, 0.0F, -273.25F, 0.0F), "padded to y = -256.125");
        assertEquals(KEPT, section(box, 257.125F, 0.0F, 0.0F), "padded to touch x = 256");
        assertEquals(CULLED, section(box, 257.25F, 0.0F, 0.0F), "padded to x = 256.125");
    }

    /** The distance cube is closed, compares in double, never forces, and ignores the view and the light. */
    @Test
    void distanceCubeIsClosedAndComparedInDouble() {
        ShadowCullingFrustum box = distance(64.0);
        drawn(box, KEPT, false, "touching +64", 64.0F, 0.0F, 0.0F, 65.0F, 1.0F, 1.0F);
        drawn(box, CULLED, false, "one ulp past +64", Math.nextUp(64.0F), 0.0F, 0.0F, 65.0F, 1.0F, 1.0F);
        drawn(box, KEPT, false, "touching -64", -65.0F, 0.0F, 0.0F, -64.0F, 1.0F, 1.0F);
        drawn(box, CULLED, false, "one ulp past -64", -65.0F, 0.0F, 0.0F, Math.nextDown(-64.0F), 1.0F, 1.0F);
        drawn(box, CULLED, false, "above", 0.0F, 100.0F, 0.0F, 1.0F, 101.0F, 1.0F);
        drawn(box, KEPT, false, "spanning", -1000.0F, -1000.0F, -1000.0F, 1000.0F, 1000.0F, 1000.0F);
        // 0.1f is 0.10000000149, just past the double 0.1
        ShadowCullingFrustum tenth = distance(0.1);
        drawn(tenth, CULLED, false, "min at 0.1f", 0.1F, 0.0F, 0.0F, 1.0F, 0.05F, 0.05F);
        drawn(tenth, KEPT, false, "min one ulp below 0.1f", Math.nextDown(0.1F), 0.0F, 0.0F, 1.0F, 0.05F, 0.05F);
        drawn(tenth, CULLED, false, "max at -0.1f", -1.0F, 0.0F, 0.0F, -0.1F, 0.05F, 0.05F);
        ShadowCullingFrustum point = distance(0.0);
        drawn(point, KEPT, false, "touching the camera", 0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F);
        drawn(point, CULLED, false, "the least float away", Float.MIN_VALUE, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F);
        drawn(distance(UNBOUNDED), KEPT, false, "no cube", 1e30F, 1e30F, 1e30F, 1e31F, 1e31F, 1e31F);
        ShadowCullingFrustum viewed = culling(Mode.DISTANCE, 64.0, 0.0, perspective(), EAST);
        drawn(viewed, KEPT, false, "behind the camera", 0.0F, 0.0F, 20.0F, 1.0F, 1.0F, 21.0F);
        drawn(viewed, CULLED, false, "above", 0.0F, 100.0F, 0.0F, 1.0F, 101.0F, 1.0F);
    }

    /** No culling keeps every box and forces none. */
    @Test
    void noneKeepsEverything() {
        ShadowCullingFrustum none = ShadowCullingFrustum.none();
        assertSame(Culler.NONE, none.culler());
        drawn(none, KEPT, false, "far away", 1e9F, 1e9F, 1e9F, 2e9F, 2e9F, 2e9F);
        ShadowCullingFrustum off = new ShadowCullingFrustum(Culler.NONE, perspective(), EAST);
        drawn(off, KEPT, false, "away from the light", -200.0F, 0.0F, -20.0F, -199.0F, 1.0F, -19.0F);
        Culler culler = new Culler(Mode.DISTANCE, 64.0, 0.0);
        assertSame(culler, new ShadowCullingFrustum(culler, perspective(), UP).culler());
    }

    /** The light overhead: the view's frustum extended upward without limit. */
    @Test
    void perspectiveOverheadLight() {
        ShadowCullingFrustum sweep = advanced(perspective(), UP);
        cube(sweep, KEPT, "in view", 0.0F, 0.0F, -20.0F);
        cube(sweep, KEPT, "above the view", 0.0F, 50.0F, -20.0F);
        cube(sweep, CULLED, "behind the camera", 0.0F, 50.0F, 20.0F);
        cube(sweep, CULLED, "below the view", 0.0F, -100.0F, -20.0F);
        // the near face is z = -0.05 in GL depth; a 0..w depth range would put it at -0.1
        kept(sweep, KEPT, "column just past the near face", -0.01F, 0.0F, -0.06F, 0.01F, 100.0F, 1.0F);
        kept(sweep, CULLED, "column short of the near face", -0.01F, 0.0F, -0.04F, 0.01F, 100.0F, 1.0F);
        // the float matrix puts its far face at z = -999.884
        kept(sweep, CULLED, "past the far face", 0.0F, 0.0F, -1002.0F, 1.0F, 1.0F, -1001.0F);
        kept(sweep, KEPT, "short of the far face", 0.0F, 0.0F, -999.0F, 1.0F, 1.0F, -998.0F);
        cube(sweep, KEPT, "high above the view", 0.0F, 5000.0F, -500.0F);
        // at z = -21 the left face is x = -26.141 and the bottom face y = -14.704
        kept(sweep, KEPT, "inside the left face", -27.0F, 0.0F, -21.0F, -26.0F, 1.0F, -20.0F);
        kept(sweep, CULLED, "outside the left face", -27.5F, 0.0F, -21.0F, -26.5F, 1.0F, -20.0F);
        kept(sweep, KEPT, "above the bottom face", 0.0F, -15.0F, -21.0F, 1.0F, -14.5F, -20.0F);
        kept(sweep, CULLED, "below the bottom face", 0.0F, -16.0F, -21.0F, 1.0F, -15.0F, -20.0F);
    }

    /** A box is culled only when one plane alone has it outside, so one past a corner can stay. */
    @Test
    void conservativeTestKeepsCornerFalsePositive() {
        ShadowCullingFrustum sweep = advanced(perspective(), UP);
        // where z >= -999.884 the sweep needs x >= -1244.67, so this box misses it
        kept(sweep, KEPT, "beyond the far left edge", -1250.0F, 0.0F, -1004.0F, -1246.0F, 1.0F, -999.0F);
        kept(sweep, CULLED, "wholly past the far face", -1250.0F, 0.0F, -1004.0F, -1246.0F, 1.0F, -1001.0F);
    }

    /** A horizontal light: faces parallel to it, the top among them, still bound the sweep. */
    @Test
    void sideLightParallelFacesBound() {
        ShadowCullingFrustum sweep = advanced(perspective(), EAST);
        cube(sweep, KEPT, "on the light's side", 200.0F, 0.0F, -20.0F);
        cube(sweep, CULLED, "on the far side of the view", -200.0F, 0.0F, -20.0F);
        kept(sweep, KEPT, "far toward the light", 10000.0F, 0.0F, -21.0F, 10001.0F, 1.0F, -20.0F);
        kept(sweep, KEPT, "inside the left face", -27.0F, 0.0F, -21.0F, -26.0F, 1.0F, -20.0F);
        kept(sweep, CULLED, "outside the left face", -27.5F, 0.0F, -21.0F, -26.5F, 1.0F, -20.0F);
        // at z = -21 the top face is y = 14.704
        kept(sweep, CULLED, "above the top face", 200.0F, 100.0F, -21.0F, 201.0F, 101.0F, -20.0F);
        kept(sweep, KEPT, "below the top face", 200.0F, 13.0F, -21.0F, 201.0F, 14.0F, -20.0F);
        kept(sweep, CULLED, "behind the camera", 200.0F, 0.0F, 20.0F, 201.0F, 1.0F, 21.0F);
    }

    /** An oblique light: the sweep is bounded through the view's top-left and bottom-right corner rays. */
    @Test
    void obliqueLightRimPlanes() {
        ShadowCullingFrustum sweep = advanced(perspective(), OBLIQUE);
        cube(sweep, KEPT, "the light's ray crosses the view", 30.0F, 30.0F, -20.0F);
        cube(sweep, CULLED, "the light's ray leaves the view", -30.0F, -30.0F, -20.0F);
        cube(sweep, KEPT, "far along the light from a visible point", 707.0F, 707.0F, -20.0F);
        cube(sweep, CULLED, "low on the light's side", 300.0F, -50.0F, -20.0F);
        cube(sweep, CULLED, "high on the far side", -50.0F, 300.0F, -20.0F);
        cube(sweep, KEPT, "in view, left", -20.0F, 0.0F, -20.0F);
        cube(sweep, CULLED, "below the view", 0.0F, -20.0F, -20.0F);
    }

    /** The light behind the camera or ahead of it, along the view axis. */
    @Test
    void lightBehindAndAheadOfTheCamera() {
        // kept: z >= -999.884, |x| <= 1244.669, |y| <= 700.126
        ShadowCullingFrustum behind = advanced(perspective(), new Vector3f(0.0F, 0.0F, 1.0F));
        cube(behind, KEPT, "behind the camera", 0.0F, 0.0F, 50.0F);
        cube(behind, KEPT, "beside the camera", 100.0F, 0.0F, 0.0F);
        cube(behind, KEPT, "inside the far rectangle's width", 1200.0F, 0.0F, 50.0F);
        cube(behind, CULLED, "outside its width", 1300.0F, 0.0F, 50.0F);
        cube(behind, KEPT, "inside its height", 0.0F, 690.0F, 50.0F);
        cube(behind, CULLED, "outside its height", 0.0F, 710.0F, 50.0F);
        kept(behind, CULLED, "past the far face", 0.0F, 0.0F, -1003.0F, 1.0F, 1.0F, -1002.0F);
        ShadowCullingFrustum ahead = advanced(perspective(), new Vector3f(0.0F, 0.0F, -1.0F));
        cube(ahead, KEPT, "beyond the far face", 0.0F, 0.0F, -2000.0F);
        cube(ahead, KEPT, "beyond it, inside the far rectangle", 1200.0F, 0.0F, -2000.0F);
        cube(ahead, CULLED, "beyond it, outside the far rectangle", 1300.0F, 0.0F, -2000.0F);
        cube(ahead, CULLED, "behind the camera", 0.0F, 0.0F, 5.0F);
        // at z = -500 the side faces are x = +-622.4
        cube(ahead, CULLED, "outside a side face", 700.0F, 0.0F, -500.0F);
        cube(ahead, KEPT, "inside the side faces", 600.0F, 0.0F, -500.0F);
    }

    /** The view's rotation is part of the camera matrix: looking down with the light overhead. */
    @Test
    void viewRotationIsApplied() {
        // kept: y >= -999.884, |x| <= 1244.669, |z| <= 700.126
        ShadowCullingFrustum sweep = advanced(lookingDown(), UP);
        cube(sweep, KEPT, "above the camera", 0.0F, 50.0F, 0.0F);
        cube(sweep, KEPT, "in view", 0.0F, -20.0F, 0.0F);
        kept(sweep, CULLED, "past the far face", 0.0F, -1003.0F, 0.0F, 1.0F, -1002.0F, 1.0F);
        cube(sweep, KEPT, "inside the far rectangle's width", 1200.0F, 50.0F, 0.0F);
        cube(sweep, CULLED, "outside its width", 1300.0F, 50.0F, 0.0F);
        cube(sweep, KEPT, "inside its height, +z", 0.0F, 50.0F, 650.0F);
        cube(sweep, KEPT, "inside its height, -z", 0.0F, 50.0F, -650.0F);
        cube(sweep, CULLED, "outside its height, +z", 0.0F, 50.0F, 750.0F);
        cube(sweep, CULLED, "outside its height, -z", 0.0F, 50.0F, -750.0F);
    }

    /** The advanced mode keeps a box only when it meets the distance cube and the sweep keeps it. */
    @Test
    void distanceCubeAndSweepCombine() {
        ShadowCullingFrustum boxed = culling(Mode.ADVANCED, 64.0, 0.0, perspective(), EAST);
        drawn(boxed, KEPT, false, "touching the cube, toward the light", 64.0F, 0.0F, -21.0F, 65.0F, 1.0F, -20.0F);
        drawn(boxed, CULLED, false, "past the cube", 64.001F, 0.0F, -21.0F, 65.0F, 1.0F, -20.0F);
        drawn(boxed, CULLED, false, "in the cube, outside the sweep", -40.0F, 0.0F, -21.0F, -39.0F, 1.0F, -20.0F);
        drawn(boxed, CULLED, false, "past the cube, in the sweep", 100.0F, 0.0F, -20.0F, 101.0F, 1.0F, -19.0F);
        ShadowCullingFrustum point = culling(Mode.ADVANCED, 0.0, 0.0, perspective(), UP);
        drawn(point, KEPT, false, "around the camera", -1.0F, -1.0F, -1.0F, 1.0F, 1.0F, 1.0F);
        drawn(point, KEPT, false, "touching the camera, in front", -1.0F, 0.0F, -1.0F, 0.0F, 1.0F, 0.0F);
        drawn(point, CULLED, false, "touching the camera, behind", 0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F);
        drawn(advanced(perspective(), EAST), KEPT, false, "around the camera, no cube",
                -1.0F, -1.0F, -1.0F, 1.0F, 1.0F, 1.0F);
    }

    /** The safe zone is drawn whatever the view, inside the outer cube; past it the sweep decides. */
    @Test
    void safeZoneBoundaries() {
        ShadowCullingFrustum zone = culling(Mode.SAFE_ZONE, 128.0, 32.0, perspective(), EAST);
        drawn(zone, KEPT, true, "in the zone", -20.0F, 0.0F, -20.0F, -19.0F, 1.0F, -19.0F);
        drawn(zone, CULLED, false, "past the zone, away from the light", -60.0F, 0.0F, -20.0F, -59.0F, 1.0F, -19.0F);
        drawn(zone, CULLED, false, "past the outer cube", 200.0F, 0.0F, -20.0F, 201.0F, 1.0F, -19.0F);
        drawn(zone, KEPT, true, "touching the zone", -33.0F, 0.0F, -21.0F, -32.0F, 1.0F, -20.0F);
        drawn(zone, CULLED, false, "one ulp past the zone", -33.0F, 0.0F, -21.0F, Math.nextDown(-32.0F), 1.0F, -20.0F);
        drawn(zone, KEPT, true, "in the zone, behind the camera", -20.0F, 0.0F, 20.0F, -19.0F, 1.0F, 21.0F);
        drawn(zone, KEPT, false, "past the zone, toward the light", 100.0F, 0.0F, -21.0F, 101.0F, 1.0F, -20.0F);
        drawn(zone, KEPT, false, "touching the outer cube", 128.0F, 0.0F, -21.0F, 129.0F, 1.0F, -20.0F);
        drawn(zone, CULLED, false, "one ulp past it", Math.nextUp(128.0F), 0.0F, -21.0F, 129.0F, 1.0F, -20.0F);
        drawn(zone, KEPT, true, "touching the zone's corner", 32.0F, 0.0F, 31.0F, 33.0F, 1.0F, 32.0F);
        ShadowCullingFrustum wide = culling(Mode.SAFE_ZONE, 16.0, 64.0, perspective(), EAST);
        drawn(wide, CULLED, false, "in a zone wider than the cube", 40.0F, 0.0F, -21.0F, 41.0F, 1.0F, -20.0F);
        drawn(wide, KEPT, true, "in both, behind the camera", 10.0F, 0.0F, 10.0F, 11.0F, 1.0F, 11.0F);
        ShadowCullingFrustum open = culling(Mode.SAFE_ZONE, UNBOUNDED, 32.0, perspective(), EAST);
        drawn(open, KEPT, false, "no outer cube, toward the light", 5000.0F, 0.0F, -21.0F, 5001.0F, 1.0F, -20.0F);
        drawn(open, CULLED, false, "no outer cube, away from it", -5000.0F, 0.0F, -21.0F, -4999.0F, 1.0F, -20.0F);
    }

    /** The frustum reads the camera matrix and light once: later changes to them make no difference. */
    @Test
    void inputsAreSnapshotted() {
        Matrix4f camera = perspective();
        Vector3f light = new Vector3f(UP);
        ShadowCullingFrustum sweep = advanced(camera, light);
        assertEquals(perspective(), camera, "the camera matrix is left as it was");
        assertEquals(UP, light, "and so is the light");
        camera.identity();
        light.set(EAST);
        cube(sweep, KEPT, "above the view, still swept up", 0.0F, 50.0F, -20.0F);
        cube(sweep, CULLED, "beside the view, still not swept sideways", 200.0F, 0.0F, -20.0F);
    }

    /**
     * The modes compose: advanced is the distance cube and the unbounded sweep together, and safe zone is its
     * two cubes together, or else the outer cube and the sweep together.
     */
    @Test
    void modesCompose() {
        for (Matrix4f camera : List.of(perspective(), lookingDown())) {
            for (Vector3f light : List.of(UP, EAST, OBLIQUE, new Vector3f(0.0F, 0.0F, 1.0F))) {
                ShadowCullingFrustum sweep = advanced(camera, light);
                ShadowCullingFrustum boxed = culling(Mode.ADVANCED, 128.0, 0.0, camera, light);
                ShadowCullingFrustum zone = culling(Mode.SAFE_ZONE, 128.0, 32.0, camera, light);
                ShadowCullingFrustum outer = distance(128.0);
                ShadowCullingFrustum inner = distance(32.0);
                for (float x : GRID) {
                    for (float y : GRID) {
                        for (float z : GRID) {
                            boolean swept = sweep.visible(x, y, z, x + 1.0F, y + 1.0F, z + 1.0F);
                            boolean inOuter = outer.visible(x, y, z, x + 1.0F, y + 1.0F, z + 1.0F);
                            boolean inZone = inOuter && inner.visible(x, y, z, x + 1.0F, y + 1.0F, z + 1.0F);
                            String at = "cube at " + x + ", " + y + ", " + z + " with light " + light;
                            cube(boxed, inOuter && swept, at, x, y, z);
                            assertEquals(inZone, zone.forced(x, y, z, x + 1.0F, y + 1.0F, z + 1.0F), at);
                            cube(zone, inZone || (inOuter && swept), at, x, y, z);
                        }
                    }
                }
            }
        }
    }

    /** Only the safe-zone mode forces a box, even one around the camera. */
    @Test
    void onlyTheSafeZoneForces() {
        List<ShadowCullingFrustum> unforced = List.of(ShadowCullingFrustum.none(), distance(64.0),
                advanced(perspective(), UP), culling(Mode.ADVANCED, 64.0, 0.0, perspective(), EAST));
        for (ShadowCullingFrustum frustum : unforced) {
            for (float x : GRID) {
                assertFalse(frustum.forced(x, -1.0F, -1.0F, x + 1.0F, 1.0F, 1.0F), frustum.culler().describe());
            }
            assertFalse(frustum.forced(-1.0F, -1.0F, -1.0F, 1.0F, 1.0F, 1.0F), frustum.culler().describe());
        }
    }
}
