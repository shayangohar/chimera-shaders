package net.chimera.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shadowcomp passes live as long as the game, so their once-only failure log re-arms per pack session. */
final class PackShadowCompositesTest {
    @Test
    void eachPackSessionLogsItsFirstFailedPass() {
        PackShadowComposites composites = new PackShadowComposites(null);
        assertTrue(composites.firstFailure());
        assertFalse(composites.firstFailure(), "a pass failing every frame logs once");
        composites.resetSession();
        assertTrue(composites.firstFailure(), "the next pack's first failure is logged");
    }
}
