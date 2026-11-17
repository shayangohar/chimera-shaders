package net.chimera.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Files dropped on the pack list: what reaches the shaderpacks folder, and the one notice about it. */
final class PackDropTest {
    private static final String KEYS = "options.chimera.shaderPackSelection.";

    @Test
    void lonePackIsCopiedAndPicked(@TempDir Path temp) throws IOException {
        Path root = temp.resolve("shaderpacks");
        Path pack = Files.createFile(temp.resolve("A.zip"));
        Path notes = Files.createFile(temp.resolve("notes.txt"));
        PackDrop.Result result = PackDrop.copy(root, List.of(pack, notes));
        assertTrue(Files.isRegularFile(root.resolve("A.zip")), "the pack is copied, creating the folder");
        assertFalse(Files.exists(root.resolve("notes.txt")), "other files are skipped");
        assertTrue(Files.exists(pack), "the original stays");
        assertNotice(result, ChatFormatting.YELLOW, "addedPack", "A.zip");
        assertEquals("A.zip", result.added());
    }

    @Test
    void severalPacksAreCounted(@TempDir Path temp) throws IOException {
        Path root = Files.createDirectory(temp.resolve("shaderpacks"));
        Path zip = Files.createFile(temp.resolve("A.zip"));
        Path folder = Files.createDirectories(temp.resolve("Pack").resolve("shaders")).getParent();
        Files.writeString(folder.resolve("shaders").resolve("final.fsh"), "void main() {}");
        PackDrop.Result result = PackDrop.copy(root, List.of(zip, folder));
        assertTrue(Files.isRegularFile(root.resolve("Pack").resolve("shaders").resolve("final.fsh")),
                "a folder pack is copied whole");
        assertNotice(result, ChatFormatting.YELLOW, "addedPacks", 2);
        assertNull(result.added(), "only a lone pack is picked");
    }

    @Test
    void firstFailureEndsTheRun(@TempDir Path temp) throws IOException {
        Path root = Files.createDirectory(temp.resolve("shaderpacks"));
        Files.createFile(root.resolve("B.zip"));
        List<Path> dropped = List.of(Files.createFile(temp.resolve("A.zip")),
                Files.createFile(temp.resolve("B.zip")), Files.createFile(temp.resolve("C.zip")));
        PackDrop.Result result = PackDrop.copy(root, dropped);
        assertTrue(Files.exists(root.resolve("A.zip")), "packs copied before the failure stay");
        assertFalse(Files.exists(root.resolve("C.zip")), "packs after the failure are not tried");
        assertNotice(result, ChatFormatting.RED, "copyErrorAlreadyExists", "B.zip");
        assertNull(result.added());
    }

    @Test
    void dropsWithoutPacksNameALoneFile(@TempDir Path temp) throws IOException {
        Path root = temp.resolve("shaderpacks");
        Path notes = Files.createFile(temp.resolve("notes.txt"));
        Path image = Files.createFile(temp.resolve("img.png"));
        assertNotice(PackDrop.copy(root, List.of(notes)), ChatFormatting.RED, "failedAddSingle", "notes.txt");
        assertNotice(PackDrop.copy(root, List.of(notes, image)), ChatFormatting.RED, "failedAdd");
        assertFalse(Files.exists(root), "nothing is copied, so the folder is not even created");
    }

    private static void assertNotice(PackDrop.Result result, ChatFormatting color, String key, Object... args) {
        TranslatableContents contents = (TranslatableContents) result.notice().getContents();
        assertEquals(KEYS + key, contents.getKey());
        assertArrayEquals(args, contents.getArgs());
        assertTrue(result.notice().getStyle().isItalic(), "notices are italic");
        assertEquals(TextColor.fromLegacyFormat(color), result.notice().getStyle().getColor());
    }
}
