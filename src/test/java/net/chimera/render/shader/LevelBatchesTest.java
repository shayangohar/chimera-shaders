// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render.shader;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import net.minecraft.client.renderer.MultiBufferSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A pack fallback mid-frame switches the family bridge off, after which RenderBuffers hands out vanilla's
 * sources. The level sources are still found through their own list, and switching the bridge off drops
 * what they hold.
 *
 * A held batch needs a RenderType, which only a bootstrapped game can make (NeoForge's FeatureFlags reads
 * the loaded mod list), so this checks the lookup and that switching off leaves the sources usable.
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
