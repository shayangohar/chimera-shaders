package net.chimera.gui;

import net.minecraft.network.chat.Component;
import net.vulkanmod.config.gui.widget.OptionWidget;
import net.vulkanmod.config.gui.widget.RangeOptionWidget;
import net.vulkanmod.config.option.RangeOption;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A slider that still shows its tooltip while greyed out, as Iris's locked Max Shadow Distance
 * does. VulkanMod's OptionWidget counts an inactive widget as never under the mouse, so the
 * options screen finds no hovered widget and draws no tooltip. This widget counts as hovered
 * while inactive. Clicks still need it active (OptionWidget.mouseClicked). Its hover highlight
 * would show too, since renderHovering asks isActive(), which an option widget never overrides
 * (it reads true); this widget draws the highlight only while active.
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
