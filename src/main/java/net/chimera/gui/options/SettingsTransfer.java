// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.gui.options;

import net.chimera.ChimeraMod;
import net.chimera.shaderpack.options.OptionFile;
import net.minecraft.client.resources.language.I18n;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The native file dialogs behind Import and Export. They open one at a time on their own thread,
 * as Iris runs them, starting at the pack's own option file and offering {@code .txt} files; a
 * click while one is open does nothing.
 */
final class SettingsTransfer {
    private static final String FILTER = "*.txt";
    private static final String FILTER_LABEL = "options.chimera.settingsFile.filter";
    private static final ExecutorService DIALOGS = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Chimera settings dialog");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean OPEN = new AtomicBoolean();

    private SettingsTransfer() {}

    /** Asks for a settings file to import and hands the choice to {@code chosen}, off the render thread. */
    static void chooseImport(Path packPath, Consumer<Path> chosen) {
        // the dialog runs off the render thread, so its text is read here
        String title = I18n.get("options.chimera.importSettings.dialog");
        String filterLabel = I18n.get(FILTER_LABEL);
        run(() -> {
            String file;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                file = TinyFileDialogs.tinyfd_openFileDialog(title, OptionFile.of(packPath).toString(),
                        filter(stack), filterLabel, false);
            }
            if (file != null) chosen.accept(Path.of(file));
        });
    }

    /**
     * Asks where to export and writes the pack's applied settings there: what its option file holds now, so
     * changes not yet applied stay out. An all-default pack exports an empty file. An option file that cannot
     * be parsed exports nothing, so the dialog's default target, that same file, is never emptied.
     */
    static void export(Path packPath) {
        String title = I18n.get("options.chimera.exportSettings.dialog");
        String filterLabel = I18n.get(FILTER_LABEL);
        run(() -> {
            String file;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                file = TinyFileDialogs.tinyfd_saveFileDialog(title, OptionFile.of(packPath).toString(),
                        filter(stack), filterLabel);
            }
            if (file == null) return;
            try {
                Path stored = OptionFile.of(packPath);
                Map<String, String> values = Files.isRegularFile(stored) ? OptionFile.readStrict(stored) : Map.of();
                OptionFile.export(Path.of(file), values);
            } catch (IOException failure) {
                ChimeraMod.LOGGER.error("[chimera] cannot export shader settings to {}", file, failure);
            }
        });
    }

    private static void run(Runnable dialog) {
        if (!OPEN.compareAndSet(false, true)) return;
        DIALOGS.execute(() -> {
            try {
                dialog.run();
            } finally {
                OPEN.set(false);
            }
        });
    }

    private static PointerBuffer filter(MemoryStack stack) {
        PointerBuffer patterns = stack.mallocPointer(1);
        patterns.put(stack.UTF8(FILTER));
        return patterns.flip();
    }
}
