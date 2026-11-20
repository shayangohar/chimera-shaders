// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render.shader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Reforged draws an immediate mesh through its immediate VertexBuffer, so both seams reach PackHostDraws
 * for one mesh: what the outer call opened serves it, and the inner call runs it as it is.
 */
final class PackHostDrawsTest {
    @Test
    void aDrawInsideAServedOneRunsAsItIs() {
        int[] runs = new int[2];
        PackHostDraws.draw(null, null, () -> {
            assertTrue(PackHostDraws.serving());
            PackHostDraws.draw(null, null, () -> runs[1]++, () -> fail("the inner draw is the served mesh"));
            runs[0]++;
        }, () -> fail("no pack drops this mesh"));
        assertArrayEquals(new int[] {1, 1}, runs);
        assertFalse(PackHostDraws.serving());

        assertThrows(IllegalStateException.class, () -> PackHostDraws.draw(null, null, () -> {
            throw new IllegalStateException("a failed draw");
        }, () -> {}));
        assertFalse(PackHostDraws.serving(), "a draw that throws ends its service");
    }
}
