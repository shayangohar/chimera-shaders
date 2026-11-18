// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render.shader;

import net.vulkanmod.vulkan.texture.VulkanImage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One pack geometry window is open at a time, and it reports the attachments it was opened with. */
final class PackGeometryContextTest {
    private final VulkanImage target = image();
    private final VulkanImage auxiliary = image();
    private final VulkanImage depth = image();

    @AfterEach
    void closeWindow() {
        PackGeometryContext.close();
    }

    @Test
    void aGeometryWindowReportsItsAttachments() {
        PackGeometryContext.beginGeometry(List.of(this.target, this.auxiliary), this.depth);
        assertTrue(PackGeometryContext.active());
        assertSame(this.depth, PackGeometryContext.depth());
        assertEquals(List.of(this.target, this.auxiliary), PackGeometryContext.colors());
    }

    @Test
    void oneWindowAtATimeAndCloseForgetsIt() {
        PackGeometryContext.beginGeometry(List.of(this.target, this.auxiliary), this.depth);
        assertThrows(IllegalStateException.class, () -> PackGeometryContext.beginGeometry(List.of(this.target),
                this.depth));
        PackGeometryContext.close();
        assertFalse(PackGeometryContext.active());
        assertNull(PackGeometryContext.depth());
        assertEquals(List.of(), PackGeometryContext.colors());
        PackGeometryContext.beginGeometry(List.of(this.target), this.depth);
        assertTrue(PackGeometryContext.active(), "a closed window can be followed by another");
    }

    @Test
    void incompleteAttachmentsOpenNothing() {
        assertThrows(IllegalArgumentException.class, () -> PackGeometryContext.beginGeometry(List.of(), this.depth));
        assertThrows(IllegalArgumentException.class, () -> PackGeometryContext.beginGeometry(
                Arrays.asList(this.target, null), this.depth));
        assertThrows(IllegalArgumentException.class, () -> PackGeometryContext.beginGeometry(List.of(this.target),
                null));
        assertThrows(IllegalArgumentException.class, () -> PackGeometryContext.beginShadow(List.of(this.target),
                this.depth, List.of()));
        assertFalse(PackGeometryContext.active());
        PackGeometryContext.beginShadow(List.of(this.target, this.auxiliary), this.depth,
                Arrays.asList(new float[4], null));
        assertTrue(PackGeometryContext.active(), "a shadow window may load a colour");
    }

    private static VulkanImage image() {
        try {
            var constructor = VulkanImage.class.getDeclaredConstructor(VulkanImage.Builder.class);
            constructor.setAccessible(true);
            return constructor.newInstance(VulkanImage.builder(16, 16));
        } catch (ReflectiveOperationException failure) {
            throw new RuntimeException(failure);
        }
    }
}
