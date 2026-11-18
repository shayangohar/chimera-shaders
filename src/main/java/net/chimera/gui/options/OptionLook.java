// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.gui.options;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;

/**
 * The settings' look, matching Iris's: flat translucent controls drawn as a 1 px border, a 1 px inner ring
 * and a fill, and dark panels for tooltips and comments. The layers never overlap, so each alpha blends only
 * with the world behind.
 */
public final class OptionLook {
    /** Normal, hovered or focused, or inset for disabled controls, value boxes and slider tracks. */
    public enum State {
        NORMAL(0x8AE0E0E0, 0x36050505, 0x36232323),
        HOVERED(0xFFFFFFFF, 0x638C8C8C, 0x63A3A3A3),
        INSET(0x8AB3B3B3, 0x73000000, 0x73000000);

        private final int border;
        private final int ring;
        private final int fill;

        State(int border, int ring, int fill) {
            this.border = border;
            this.ring = ring;
            this.fill = fill;
        }
    }

    /** Label colour for a pending value not applied yet. */
    static final int MODIFIED = 0xFFFFC94A;
    /** Choice and slider values. */
    static final int VALUE = 0x6688FF;
    static final int WHITE = 0xFFFFFFFF;
    static final int INACTIVE = 0xFFA0A0A0;
    static final int HEADER_LINE = 0x66BEBEBE;
    static final int LIST_BACKGROUND = 0x40000000;
    private static final int PANEL_BORDER = 0xDEDEDEDE;
    private static final int PANEL_FILL = 0xDE000000;

    private OptionLook() {}

    public static void control(GuiGraphics graphics, int x, int y, int width, int height, State state) {
        frame(graphics, RenderType.gui(), x, y, width, height, state.border);
        frame(graphics, RenderType.gui(), x + 1, y + 1, width - 2, height - 2, state.ring);
        graphics.fill(x + 2, y + 2, x + width - 2, y + height - 2, state.fill);
    }

    /**
     * The dark panel behind tooltips and comments, on the overlay type without depth test, as Iris's
     * GuiUtil.drawPanel draws it. Text sits 0.03 in front of its widget, Font's shadow offset, so a
     * depth-tested panel would hide a button's face but let its label show through.
     */
    static void panel(GuiGraphics graphics, int x, int y, int width, int height) {
        frame(graphics, RenderType.guiOverlay(), x, y, width, height, PANEL_BORDER);
        graphics.fill(RenderType.guiOverlay(), x + 1, y + 1, x + width - 1, y + height - 1, PANEL_FILL);
    }

    /** A 1 px outline whose edges do not overlap at the corners. */
    private static void frame(GuiGraphics graphics, RenderType type, int x, int y, int width, int height, int color) {
        if (width <= 0 || height <= 0) return;
        graphics.fill(type, x, y, x + width, y + 1, color);
        if (height > 1) graphics.fill(type, x, y + height - 1, x + width, y + height, color);
        if (height > 2) {
            graphics.fill(type, x, y + 1, x + 1, y + height - 1, color);
            if (width > 1) graphics.fill(type, x + width - 1, y + 1, x + width, y + height - 1, color);
        }
    }
}
