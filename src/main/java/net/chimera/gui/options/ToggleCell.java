// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.gui.options;

import net.chimera.shaderpack.options.ShaderOption;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.navigation.CommonInputs;
import net.minecraft.network.chat.Component;

import java.util.Optional;

/** An on/off option; the value is coloured only away from the default, so a default Off does not read as a fault. */
final class ToggleCell extends ValueCell {
    private final ShaderOption.Toggle option;

    ToggleCell(OptionContext context, ShaderOption.Toggle option) {
        super(context);
        this.option = option;
    }

    private boolean value() {
        return context.editor().pending().toggleOrDefault(option.name());
    }

    @Override
    String label() {
        return context.text().optionName(option.name());
    }

    @Override
    Component valueText() {
        boolean on = value();
        Component text = Component.translatable(on ? "label.chimera.true" : "label.chimera.false");
        if (on == option.defaultValue()) return text;
        return text.copy().withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    @Override
    int minValueWidth() {
        return 28;
    }

    @Override
    boolean modified() {
        return context.editor().modified(option.name());
    }

    @Override
    boolean press(int button) {
        if (button > 1) return false;
        if (shiftDown()) {
            context.editor().reset(option.name());
        } else {
            context.editor().setToggle(option.name(), !value());
        }
        click();
        return true;
    }

    @Override
    boolean key(int key) {
        if (!CommonInputs.selected(key)) return false;
        if (controlDown()) {
            context.editor().reset(option.name());
        } else {
            context.editor().setToggle(option.name(), !value());
        }
        click();
        return true;
    }

    @Override
    Optional<OptionOverlay.Comment> comment() {
        return comment(label(), "option." + option.name() + ".comment");
    }
}
