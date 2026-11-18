// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import net.vulkanmod.vulkan.memory.MemoryManager;

import java.util.function.Consumer;

/**
 * Chimera's one rule for destroying a Vulkan handle it owns once it may be in use: never while work that may
 * have recorded it can still run on the GPU.
 *
 * In a teardown that directly follows an idle wait ({@link #whileIdle}: a pack change, a dimension variant, the
 * final cleanup) nothing can, and the handle goes at once, so a pack change never holds the old pack's images
 * beside the new pack's.
 *
 * Otherwise it goes in a VulkanMod frame op, which runs when this frame slot comes round again, after its fence,
 * so after every frame recorded so far. At shutdown Vulkan.cleanUp runs the pending ops (MemoryManager.cleanUp)
 * before it destroys the device. A rollback, a dimension restore or renderVanilla cleans up after a load that
 * may have submitted uploads it did not wait for (PackRawTexture), so their handles wait too.
 *
 * Never retire from inside a frame op: an op queued while VulkanMod runs a slot's ops runs in the same pass,
 * before the other slots' frames finish.
 */
final class HandleRetirement {
    /** Render thread only, like every caller. */
    private static boolean idle;
    /** Where deferred destroys go: VulkanMod's frame ops, swapped out by tests. */
    static Consumer<Runnable> frameOps = destroy -> MemoryManager.getInstance().addFrameOp(destroy);

    private HandleRetirement() {}

    /**
     * Runs {@code teardown}, which must directly follow an idle wait (Vulkan.waitIdle) with nothing
     * recorded or submitted since: the handles it retires go at once.
     */
    static void whileIdle(Runnable teardown) {
        boolean outer = idle;
        idle = true;
        try {
            teardown.run();
        } finally {
            idle = outer;
        }
    }

    /** Destroys a handle by {@code destroy} now if no work can use it, else once every recorded frame has run. */
    static void retire(Runnable destroy) {
        if (idle) {
            destroy.run();
        } else {
            frameOps.accept(destroy);
        }
    }
}
