// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A compute kernel lives for the pack session, and the player can raise VulkanMod's frame queue size
 * within it, so slots sized at build would be shared by two frames in flight.
 */
final class ComputeKernelSlotsTest {
    @Test
    void aKernelHasASlotForEveryFrameVulkanModCanHaveInFlight() {
        assertEquals(5, ComputeKernel.slotCount(2), "VulkanMod's frame queue reaches 5");
        assertEquals(5, ComputeKernel.slotCount(3));
        assertEquals(5, ComputeKernel.slotCount(0));
        // a kernel built at 2 frames in flight keeps its slots while the queue grows to 5
        int slotsBuiltAtTwo = ComputeKernel.slotCount(2);
        for (int frames = 2; frames <= 5; frames++) {
            int slots = slotsBuiltAtTwo;
            for (int frame = 0; frame < frames; frame++) {
                for (int other = frame + 1; other < frames; other++) {
                    assertEquals(false, frame % slots == other % slots, frames + " frames share no slot");
                }
            }
        }
    }
}
