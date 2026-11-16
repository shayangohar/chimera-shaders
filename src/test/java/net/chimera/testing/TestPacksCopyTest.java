package net.chimera.testing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** OptionSweepTest's pack copies: a folder pack arrives whole, and the player's option file stays behind. */
final class TestPacksCopyTest {
    @Test
    void aFolderPackIsCopiedWholeAndAnOlderCopyIsReplaced(@TempDir Path root) throws IOException {
        Path pack = root.resolve("shaderpacks/Folder Pack + Patches");
        Files.createDirectories(pack.resolve("shaders/lib"));
        Files.writeString(pack.resolve("shaders/final.fsh"), "void main() {}\n");
        Files.writeString(pack.resolve("shaders/lib/common.glsl"), "float common = 1.0;\n");
        Files.writeString(root.resolve("shaderpacks/Folder Pack + Patches.txt"), "SHADOWS=false\n");
        Path sweep = root.resolve("sweep");
        Files.writeString(TestPacks.copyWithoutOptions(pack, sweep).resolve("shaders/leftover.glsl"), "");

        Path copy = TestPacks.copyWithoutOptions(pack, sweep);

        assertEquals(sweep.resolve("Folder Pack + Patches"), copy);
        assertEquals("float common = 1.0;\n", Files.readString(copy.resolve("shaders/lib/common.glsl")));
        assertEquals("void main() {}\n", Files.readString(copy.resolve("shaders/final.fsh")));
        assertFalse(Files.exists(copy.resolve("shaders/leftover.glsl")), "an older copy is replaced");
        assertFalse(Files.exists(sweep.resolve("Folder Pack + Patches.txt")), "the option file stays behind");
    }

    @Test
    void aZipPackIsCopiedAsAFileOverAnOlderCopy(@TempDir Path root) throws IOException {
        Path zip = Files.write(root.resolve("Pack.zip"), new byte[]{1, 2, 3});
        Path sweep = root.resolve("sweep");
        Files.createDirectories(sweep);
        Files.write(sweep.resolve("Pack.zip"), new byte[]{9});

        Path copy = TestPacks.copyWithoutOptions(zip, sweep);

        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(copy));
    }
}
