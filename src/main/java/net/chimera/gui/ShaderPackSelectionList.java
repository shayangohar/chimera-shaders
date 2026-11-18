// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.gui;

import net.chimera.ChimeraMod;
import net.chimera.config.ShaderpackDirectory;
import net.chimera.gui.options.OptionLook;
import net.chimera.render.PackFailureNotices;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.navigation.CommonInputs;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.List;

/**
 * The pack list on {@link ShaderPackScreen}: a shaders on/off row, the packs in the shaderpacks folder and a
 * hint about dropping files. It only records the choice, the picked pack and the toggle; the screen applies
 * them. The folder is watched, and a change seen while the list is drawn rescans it.
 *
 * The picked pack is whatever pack row vanilla's selection last landed on, by click, arrow keys or a call
 * from the screen. Moving the selection to any other row leaves it alone.
 */
public final class ShaderPackSelectionList extends ObjectSelectionList<ShaderPackSelectionList.Row> {
    private static final int ROW_HEIGHT = 20;
    private static final int MAX_ROW_WIDTH = 308;
    private static final String DOWNLOAD_URL = "https://modrinth.com/shaders";
    private static final int WHITE = 0xFFFFFFFF;
    private static final int RUNNING_COLOR = 0xFFFFF263;
    private static final int IDLE_COLOR = 0xFFA2A2A2;
    private static final Component HINT = Component.translatable("pack.chimera.list.label")
            .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
    private static final Component READ_ERROR = Component.translatable(
            "options.chimera.shaderPackSelection.readError").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);

    private final ShaderPackScreen screen;
    private final Path root;
    private @Nullable WatchService watcher;
    private boolean shadersEnabled;
    private @Nullable String applied;
    private @Nullable String picked;
    private boolean packsPresent;

    ShaderPackSelectionList(ShaderPackScreen screen, Minecraft minecraft, int width, int height, int top, Path root,
                            boolean shadersEnabled, @Nullable String applied) {
        super(minecraft, width, height, top, ROW_HEIGHT);
        this.screen = screen;
        this.root = root;
        this.shadersEnabled = shadersEnabled;
        this.applied = applied;
        this.watcher = watch(root);
        scan();
    }

    @Nullable String selectedPack() {
        return picked;
    }

    boolean shadersEnabled() {
        return shadersEnabled;
    }

    /** Selects the pack row with exactly this name, if there is one, without scrolling. */
    void select(@Nullable String name) {
        PackRow row = packRow(children(), name);
        if (row != null) setSelected(row);
    }

    void refresh() {
        scan();
    }

    /** The pack shown as running: it is drawn yellow, with its failure label, while shaders are on. */
    void setApplied(@Nullable String name) {
        applied = name;
    }

    void showRunning(@Nullable String name, boolean enabled) {
        applied = name;
        shadersEnabled = enabled;
        if (name != null) select(name);
    }

    void close() {
        WatchService service = watcher;
        watcher = null;
        if (service != null) release(service);
    }

    /** Every route by which the selection reaches a pack row picks that pack. */
    @Override
    public void setSelected(@Nullable Row row) {
        super.setSelected(row);
        if (row instanceof PackRow pack) picked = pack.name;
    }

    @Override
    public int getRowWidth() {
        return Math.min(MAX_ROW_WIDTH, width - 50);
    }

    @Override
    protected int getScrollbarPosition() {
        return getRight() - SCROLLBAR_WIDTH;
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        if (folderChanged()) scan();
        super.renderWidget(graphics, mouseX, mouseY, delta);
    }

    /**
     * Rebuilds the rows from the folder and selects the picked pack, or the applied one when nothing is picked.
     * A picked pack that has gone leaves nothing picked. An unreadable folder shows only an error and keeps
     * the pick.
     */
    private void scan() {
        String preferred = picked != null ? picked : applied;
        List<String> packs;
        try {
            packs = ShaderpackDirectory.list(root);
        } catch (IOException | UncheckedIOException failure) {
            // the unchecked form comes from a read that fails part way through the listing
            ChimeraMod.LOGGER.error("[chimera] cannot list the shader packs in {}", root, failure);
            showRows(List.of(new Caption(READ_ERROR)), null);
            return;
        }
        packsPresent = !packs.isEmpty();
        List<Row> rows = new ArrayList<>();
        rows.add(new ToggleRow());
        if (!packsPresent) rows.add(new DownloadRow());
        packs.forEach(name -> rows.add(new PackRow(name)));
        rows.add(new Caption(HINT));
        PackRow found = packRow(rows, preferred);
        picked = null;
        showRows(rows, found);
        // brings the row's bottom up to the list's bottom edge; a row on the first page leaves the list at the top
        if (found != null) setScrollAmount(getScrollAmount() + getRowBottom(rows.indexOf(found)) - getBottom());
    }

    private void showRows(List<Row> rows, @Nullable Row selected) {
        replaceEntries(rows);
        setSelected(selected);
        // keyboard focus must not stay on a row that is gone
        if (getFocused() != null) setFocused(selected);
        clampScrollAmount();
    }

    private static @Nullable PackRow packRow(List<Row> rows, @Nullable String name) {
        for (Row row : rows) {
            if (row instanceof PackRow pack && pack.name.equals(name)) return pack;
        }
        return null;
    }

    /** Creates the folder if needed and watches it, or returns null after a warning: refresh() still works. */
    private static @Nullable WatchService watch(Path root) {
        WatchService service = null;
        try {
            Files.createDirectories(root);
            service = root.getFileSystem().newWatchService();
            root.register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
            return service;
        } catch (IOException | UnsupportedOperationException failure) {
            ChimeraMod.LOGGER.warn("[chimera] cannot watch {}; the pack list will not follow changes", root, failure);
            if (service != null) release(service);
            return null;
        }
    }

    /** Drains the pending changes; a watch gone invalid, as when the folder is deleted, ends after this batch. */
    private boolean folderChanged() {
        boolean changed = false;
        WatchKey key;
        while (watcher != null && (key = watcher.poll()) != null) {
            // an overflow only reports lost events
            changed |= key.pollEvents().stream().anyMatch(event -> event.kind() != StandardWatchEventKinds.OVERFLOW);
            if (!key.reset()) close();
        }
        return changed;
    }

    private static void release(WatchService service) {
        try {
            service.close();
        } catch (IOException failure) {
            ChimeraMod.LOGGER.warn("[chimera] cannot stop watching the shaderpacks folder", failure);
        }
    }

    /** Cuts text too wide for the row, measuring it bold so the cut does not move when hovering bolds it. */
    private String fit(String text, int rowWidth) {
        Font font = minecraft.font;
        if (font.width(Component.literal(text).withStyle(ChatFormatting.BOLD)) <= rowWidth - 3) return text;
        return font.plainSubstrByWidth(text, rowWidth - 8) + "...";
    }

    /** A row of text centred on the list; a click or Enter, Space or keypad Enter on it calls activate(). */
    abstract class Row extends ObjectSelectionList.Entry<Row> {
        /** What the row says, and what the narrator reads. */
        abstract Component label();

        /** Acts on the row; false when it does nothing, which leaves a click unhandled. */
        boolean activate() {
            return false;
        }

        void drawText(GuiGraphics graphics, Component text, int left, int top, int width, int color) {
            graphics.drawCenteredString(minecraft.font, text, left + width / 2 - 2, top + 2, color);
        }

        @Override
        public Component getNarration() {
            return label();
        }

        /** The list passes only left clicks. */
        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            return activate();
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            return CommonInputs.selected(keyCode) && activate();
        }
    }

    /** A row framed as a button, lit while the pointer is over it, inset when it cannot be used. */
    private abstract class ButtonRow extends Row {
        boolean usable() {
            return true;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovered, float delta) {
            OptionLook.State look = !usable() ? OptionLook.State.INSET
                    : hovered ? OptionLook.State.HOVERED : OptionLook.State.NORMAL;
            OptionLook.control(graphics, left - 2, top - 2, width, height + 2, look);
            drawText(graphics, label(), left, top, width, WHITE);
        }

        void click() {
            playDownSound(minecraft.getSoundManager());
        }
    }

    /** Shaders on or off; it cannot be turned on while the folder holds no packs. */
    private final class ToggleRow extends ButtonRow {
        @Override
        boolean usable() {
            return packsPresent;
        }

        @Override
        Component label() {
            if (!packsPresent) {
                return Component.translatable("options.chimera.shaders.nonePresent").withStyle(ChatFormatting.GRAY);
            }
            return Component.translatable(shadersEnabled ? "options.chimera.shaders.enabled"
                    : "options.chimera.shaders.disabled");
        }

        @Override
        boolean activate() {
            if (!packsPresent) return false;
            shadersEnabled = !shadersEnabled;
            click();
            return true;
        }
    }

    /** Offers the shader page on Modrinth when the folder is empty; the link screen returns to this screen. */
    private final class DownloadRow extends ButtonRow {
        @Override
        Component label() {
            return Component.translatable("options.chimera.downloadShaders");
        }

        @Override
        boolean activate() {
            click();
            ConfirmLinkScreen.confirmLinkNow(screen, DOWNLOAD_URL, true);
            return true;
        }
    }

    /**
     * One pack, framed and bold only under the pointer. The running pack is yellow, with its failure label,
     * while shaders are on; with shaders off every pack is grey until hovered.
     */
    private final class PackRow extends Row {
        private final String name;

        PackRow(String name) {
            this.name = name;
        }

        @Override
        Component label() {
            return Component.literal(name);
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovered, float delta) {
            boolean running = shadersEnabled && name.equals(applied);
            if (hovered) OptionLook.control(graphics, left - 2, top - 2, width, height + 4, OptionLook.State.HOVERED);
            String text = fit(running ? name + PackFailureNotices.label(name) : name, width);
            int color = running ? RUNNING_COLOR : shadersEnabled || hovered ? WHITE : IDLE_COLOR;
            Component shown = Component.literal(text).withStyle(style -> style.withBold(hovered));
            drawText(graphics, shown, left, top, width, color);
        }

        /** Picking a pack turns shaders on and moves focus down, so Enter then applies it. */
        @Override
        boolean activate() {
            shadersEnabled = true;
            setSelected(this);
            screen.focusBottomRow();
            return true;
        }
    }

    /** Text only: the drop hint, or the read error. */
    private final class Caption extends Row {
        private final Component text;

        Caption(Component text) {
            this.text = text;
        }

        @Override
        Component label() {
            return text;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovered, float delta) {
            drawText(graphics, text, left, top, width, WHITE);
        }
    }
}
