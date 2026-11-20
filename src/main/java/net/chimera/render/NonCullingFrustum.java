// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import net.vulkanmod.interfaces.FrustumMixed;
import net.vulkanmod.render.chunk.frustum.VFrustum;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;

/**
 * The camera frustum of a pack that sets {@code frustum.culling=false}: every box is visible, as with Iris's
 * NonCullingFrustum. VulkanMod culls sections through {@link FrustumMixed#customFrustum}, which from this
 * frustum's identity matrices would keep only a box around the camera, so it gets one that culls nothing.
 */
public final class NonCullingFrustum extends Frustum implements FrustumMixed {
    /** Holds no camera state, so one serves every frame. */
    public static final NonCullingFrustum INSTANCE = new NonCullingFrustum();

    private final VFrustum sections = new Everything();

    private NonCullingFrustum() {
        super(new Matrix4f(), new Matrix4f());
    }

    /**
     * Keeps the camera at the origin: with identity planes and a camera off the 8-block grid, vanilla's
     * LevelRenderer.offsetFrustum loop would never end.
     */
    @Override
    public void prepare(double camX, double camY, double camZ) {
    }

    @Override
    public boolean isVisible(AABB box) {
        return true;
    }

    @Override
    public VFrustum customFrustum() {
        return sections;
    }

    /** A VFrustum that culls nothing. */
    private static final class Everything extends VFrustum {
        @Override
        public VFrustum offsetToFullyIncludeCameraCube(int offset) {
            return this;
        }

        @Override
        public int cubeInFrustum(float x1, float y1, float z1, float x2, float y2, float z2) {
            return FrustumIntersection.INSIDE;
        }

        @Override
        public boolean testFrustum(float x1, float y1, float z1, float x2, float y2, float z2) {
            return true;
        }

        @Override
        public boolean isVisible(AABB box) {
            return true;
        }
    }
}
