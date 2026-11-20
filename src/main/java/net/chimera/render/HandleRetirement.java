// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import net.vulkanmod.vulkan.memory.MemoryManager;

import java.util.function.Consumer;

/**
 * Chimera's rule for destroying a Vulkan handle it owns: never while work that may have recorded it can still
 * run on the GPU. Right after an idle wait ({@link #whileIdle}) it goes at once; otherwise a VulkanMod frame op
 * destroys it after this frame slot's fence. Never retire from inside a frame op, which would run the destroy
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
