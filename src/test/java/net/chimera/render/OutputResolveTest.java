package net.chimera.render;

import net.chimera.config.ColorSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The world resolve converts to the Color Space setting only where Iris's converter does. */
final class OutputResolveTest {
    @Test
    void theOutputConvertsOnlyWhereIrisDoes() {
        assertEquals(ColorSpace.DISPLAY_P3, OutputResolvePipelines.outputSpace(true, false, ColorSpace.DISPLAY_P3),
                "a running pack's image converts to the setting");
        assertEquals(ColorSpace.SRGB, OutputResolvePipelines.outputSpace(false, false, ColorSpace.DISPLAY_P3),
                "no pack, or a rejected one: Iris has no pipeline, so nothing converts");
        assertEquals(ColorSpace.SRGB, OutputResolvePipelines.outputSpace(true, true, ColorSpace.REC2020),
                "a supportsColorCorrection pack converts itself from currentColorSpace; Iris's converter is a no-op");
        assertEquals(ColorSpace.SRGB, OutputResolvePipelines.outputSpace(true, false, ColorSpace.SRGB),
                "sRGB converts nothing");
    }
}
