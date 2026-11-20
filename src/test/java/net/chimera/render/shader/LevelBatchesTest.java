// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render.shader;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import net.minecraft.client.renderer.MultiBufferSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * After a mid-frame fallback switches the family bridge off, the level sources are still found through
 * their own list and stay usable. A held batch needs a bootstrapped game, so this test holds none.
 */
final class LevelBatchesTest {
    @Test
    void theLevelSourcesAreFoundWithoutRenderBuffers() {
        FamilyBufferSource closed;
        try (ByteBufferBuilder hostMemory = new ByteBufferBuilder(256);
             FamilyBufferSource level = new FamilyBufferSource(MultiBufferSource.immediate(hostMemory), true);
             FamilyBufferSource own = new FamilyBufferSource(MultiBufferSource.immediate(hostMemory))) {
            assertTrue(FamilyBufferSource.levelSources().contains(level), "a level source is found without routing");
            assertFalse(FamilyBufferSource.levelSources().contains(own), "a renderer's own source holds nothing back");
            ChimeraEntityBridge.setEnabled(false);
            assertTrue(FamilyBufferSource.levelSources().contains(level), "switching off keeps the source live");
            closed = level;
        }
        assertFalse(FamilyBufferSource.levelSources().contains(closed), "a closed source is no longer listed");
    }
}
