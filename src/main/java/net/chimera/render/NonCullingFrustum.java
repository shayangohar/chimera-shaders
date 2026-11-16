package net.chimera.render;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import net.vulkanmod.interfaces.FrustumMixed;
import net.vulkanmod.render.chunk.frustum.VFrustum;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;

/**
 * The camera frustum of a pack that sets {@code frustum.culling=false}: every box is visible, as with Iris's
 * NonCullingFrustum, which its MixinLevelRenderer puts in LevelRenderer.cullingFrustum. Entities, particles
 * and NeoForge's block entity check ask {@link #isVisible}.
 *
 * VulkanMod culls sections and its block entities through the VFrustum its FrustumMixin gives every Frustum
 * ({@link FrustumMixed#customFrustum}). Built from this frustum's identity matrices, that one would keep only
 * a box around the camera, so this frustum hands out one that culls nothing.
 */
public final class NonCullingFrustum extends Frustum implements FrustumMixed {
    /** Holds no camera state, so one serves every frame. */
    public static final NonCullingFrustum INSTANCE = new NonCullingFrustum();

    private final VFrustum sections = new Everything();

    private NonCullingFrustum() {
        super(new Matrix4f(), new Matrix4f());
    }

    /**
     * Keeps the camera at the origin. VulkanMod leaves vanilla's setupRender body running, which copies this
     * frustum and moves the copy until its camera cube is inside the planes (LevelRenderer.offsetFrustum).
     * With identity planes and a camera off the 8-block grid that loop would never end; at the origin it ends
     * at once. Nothing here reads the camera.
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

    /** A VFrustum with every box inside, so the section graph, its areas and block entities draw everything. */
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
