package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** packReport takes a ;-separated pack list on every platform, as its usage says, not the path separator. */
final class PackReportToolTest {
    @Test
    void packsSplitOnSemicolons() {
        assertEquals(List.of(Path.of("BSL.zip"), Path.of("packs/Complementary.zip")),
                PackReportTool.packPaths("BSL.zip; packs/Complementary.zip"));
    }
}
