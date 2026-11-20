// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import net.vulkanmod.render.chunk.frustum.VFrustum;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A pack's frustum.culling=false: nothing is culled, by vanilla's checks or by VulkanMod's sections. */
final class NonCullingFrustumTest {
    /** A box behind a camera at the origin looking down -Z. */
    private static final float[] BEHIND = {-1, -1, 999, 1, 1, 1001};

    @Test
    void everyBoxIsVisibleToVanillaAndToVulkanModsSections() {
        AABB behind = new AABB(BEHIND[0], BEHIND[1], BEHIND[2], BEHIND[3], BEHIND[4], BEHIND[5]);
        assertTrue(NonCullingFrustum.INSTANCE.isVisible(behind), "entities, particles and block entities");

        // VulkanMod's camera frustum culls the box, so the checks below see a frustum that would
        VFrustum camera = new VFrustum();
        camera.calculateFrustum(new Matrix4f(), new Matrix4f().perspective(1.2F, 16F / 9F, 0.05F, 2048F));
        camera.setCamOffset(0, 0, 0);
        assertTrue(camera.cubeInFrustum(BEHIND[0], BEHIND[1], BEHIND[2], BEHIND[3], BEHIND[4], BEHIND[5]) >= 0
                && !camera.testFrustum(BEHIND[0], BEHIND[1], BEHIND[2], BEHIND[3], BEHIND[4], BEHIND[5]));

        // SectionGraph.update offsets the frustum it reads before testing areas and sections
        VFrustum sections = NonCullingFrustum.INSTANCE.customFrustum().offsetToFullyIncludeCameraCube(8);
        assertSame(NonCullingFrustum.INSTANCE.customFrustum(), sections);
        assertEquals(FrustumIntersection.INSIDE,
                sections.cubeInFrustum(BEHIND[0], BEHIND[1], BEHIND[2], BEHIND[3], BEHIND[4], BEHIND[5]));
        assertTrue(sections.testFrustum(BEHIND[0], BEHIND[1], BEHIND[2], BEHIND[3], BEHIND[4], BEHIND[5]),
                "sections and VulkanMod's block entity culling");
        assertTrue(sections.isVisible(behind));
    }

    /**
     * Vanilla's LevelRenderer.offsetFrustum still runs under VulkanMod, and on identity planes a camera
     * off the 8-block grid would loop it forever, so preparing the frustum keeps no camera.
     */
    @Test
    void vanillasFrustumOffsetEndsAfterAPrepare() {
        NonCullingFrustum.INSTANCE.prepare(5.3, 70.2, -3.7);
        assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> new Frustum(NonCullingFrustum.INSTANCE).offsetToFullyIncludeCameraCube(8));
    }
}
