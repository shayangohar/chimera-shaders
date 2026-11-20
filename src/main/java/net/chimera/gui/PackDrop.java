// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.gui;

import net.chimera.ChimeraMod;
import net.chimera.config.ShaderpackDirectory;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.util.List;

/**
 * Copies packs dropped on the pack list into the shaderpacks folder. Other files are skipped quietly when a pack
 * came with them; the first failed copy ends the run, keeping earlier copies.
 */
final class PackDrop {
    /** The notice to show, and the pack to pick when exactly one was added. */
    record Result(Component notice, @Nullable String added) {}

    private PackDrop() {}

    static Result copy(Path root, List<Path> dropped) {
        List<Path> packs = dropped.stream().filter(ShaderpackDirectory::isValidPack).toList();
        if (packs.isEmpty()) {
            return dropped.size() == 1
                    ? failed("options.chimera.shaderPackSelection.failedAddSingle", nameOf(dropped.getFirst()))
                    : failed("options.chimera.shaderPackSelection.failedAdd");
        }
        for (Path pack : packs) {
            try {
                ShaderpackDirectory.copyInto(root, pack);
            } catch (FileAlreadyExistsException taken) {
                return failed("options.chimera.shaderPackSelection.copyErrorAlreadyExists", nameOf(pack));
            } catch (IOException failure) {
                ChimeraMod.LOGGER.warn("[chimera] cannot copy the dropped shader pack {}", pack, failure);
                return failed("options.chimera.shaderPackSelection.copyError", nameOf(pack));
            }
        }
        if (packs.size() > 1) {
            return new Result(notice(ChatFormatting.YELLOW, "options.chimera.shaderPackSelection.addedPacks",
                    packs.size()), null);
        }
        String name = nameOf(packs.getFirst());
        return new Result(notice(ChatFormatting.YELLOW, "options.chimera.shaderPackSelection.addedPack", name), name);
    }

    private static Result failed(String key, Object... args) {
        return new Result(notice(ChatFormatting.RED, key, args), null);
    }

    private static Component notice(ChatFormatting color, String key, Object... args) {
        return Component.translatable(key, args).withStyle(ChatFormatting.ITALIC, color);
    }

    /** A drive root has no file name, so it is named by its whole path. */
    private static String nameOf(Path path) {
        Path name = path.getFileName();
        return name != null ? name.toString() : path.toString();
    }
}
