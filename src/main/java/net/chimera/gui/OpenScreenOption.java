package net.chimera.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.vulkanmod.config.gui.render.GuiRenderer;
import net.vulkanmod.config.gui.widget.OptionWidget;
import net.vulkanmod.config.option.Option;

import java.util.function.Function;

/**
 * A row of VulkanMod's options screen that opens another screen, as Iris's "Shader Pack List...
 * Open >" button on Sodium's page. It holds no value, so it never counts as a pending change; its
 * displayed value is the "Open >" label, since VulkanMod reads every option's value through its
 * translator each frame.
 */
final class OpenScreenOption extends Option<Boolean> {
    private static final Component OPEN = Component.translatable("options.chimera.open")
            .withStyle(ChatFormatting.UNDERLINE);
    private final Function<Screen, Screen> screen;

    /** {@code screen} builds the screen to open from the options screen it returns to. */
    OpenScreenOption(Component name, Function<Screen, Screen> screen) {
        super(name, value -> {}, () -> Boolean.FALSE, value -> OPEN);
        this.screen = screen;
    }

    @Override
    protected OptionWidget<?> createWidget() {
        return new Widget(this);
    }

    private static final class Widget extends OptionWidget<OpenScreenOption> {
        private final Function<Screen, Screen> screen;

        Widget(OpenScreenOption option) {
            super(option, option.getName());
            this.screen = option.screen;
        }

        /** The label at the right of the control, gold while hovered, as Iris's link turns. */
        @Override
        protected void renderControls(double mouseX, double mouseY) {
            var font = Minecraft.getInstance().font;
            int color = !this.active ? 0xFFA0A0A0 : this.controlHovered ? 0xFFFFAA00 : 0xFFFFFFFF;
            int textX = this.controlX + this.controlWidth - font.width(this.displayedValue) - 8;
            GuiRenderer.drawString(font, this.displayedValue, textX, this.y + (this.height - 9) / 2, color);
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.setScreen(this.screen.apply(minecraft.screen));
        }

        @Override
        public void onRelease(double mouseX, double mouseY) {}

        @Override
        protected void onDrag(double mouseX, double mouseY, double deltaX, double deltaY) {}
    }
}
