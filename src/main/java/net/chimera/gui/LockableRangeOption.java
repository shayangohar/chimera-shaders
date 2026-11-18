// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.gui;

import net.minecraft.network.chat.Component;
import net.vulkanmod.config.gui.widget.OptionWidget;
import net.vulkanmod.config.gui.widget.RangeOptionWidget;
import net.vulkanmod.config.option.RangeOption;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A slider that keeps its tooltip while greyed out, as Iris's locked Max Shadow Distance does.
 *
 * VulkanMod's OptionWidget treats an inactive widget as never under the mouse, so the screen finds no hovered
 * widget and draws no tooltip; this one counts as hovered while inactive. Clicks still need it active
 * (OptionWidget.mouseClicked). renderHovering checks isActive(), which option widgets leave true, so the
 * hover highlight is drawn only while active.
 */
final class LockableRangeOption extends RangeOption {
    LockableRangeOption(Component name, int min, int max, int step, Function<Integer, Component> translator,
                        Consumer<Integer> setter, Supplier<Integer> getter) {
        super(name, min, max, step, translator, setter, getter);
    }

    @Override
    protected OptionWidget<?> createWidget() {
        return new RangeOptionWidget(this, this.name) {
            @Override
            public boolean isMouseOver(double mouseX, double mouseY) {
                return this.visible && mouseX >= this.x && mouseY >= this.y
                        && mouseX < this.x + this.width && mouseY < this.y + this.height;
            }

            @Override
            protected void renderHovering(int xPadding, int yPadding) {
                if (this.active) super.renderHovering(xPadding, yPadding);
            }
        };
    }
}
