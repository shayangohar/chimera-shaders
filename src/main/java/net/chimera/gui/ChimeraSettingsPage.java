// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.gui;

import net.chimera.ChimeraMod;
import net.chimera.config.ChimeraConfig;
import net.chimera.config.ColorSpace;
import net.chimera.render.ChimeraRenderer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.vulkanmod.config.gui.ModSettingsEntry;
import net.vulkanmod.config.gui.ModSettingsRegistry;
import net.vulkanmod.config.gui.OptionBlock;
import net.vulkanmod.config.option.CyclingOption;
import net.vulkanmod.config.option.Option;
import net.vulkanmod.config.option.OptionPage;
import net.vulkanmod.config.option.PerformanceImpact;
import net.vulkanmod.config.option.RangeOption;

import java.util.List;
import java.util.OptionalInt;

/** Chimera's entry in VulkanMod's options screen, with Iris's Max Shadow Distance and Color Space. */
public final class ChimeraSettingsPage {
    private static final ResourceLocation ICON =
            ResourceLocation.fromNamespaceAndPath(ChimeraMod.MOD_ID, "textures/gui/settings_icon.png");

    private ChimeraSettingsPage() {}

    public static void register() {
        Component name = Component.literal("Chimera").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
        ModSettingsRegistry.INSTANCE.addModEntry(new ModSettingsEntry(name, () -> ICON,
                ChimeraSettingsPage::pages, () -> {}));
    }

    /** Built each time the screen opens, so the texts follow the language and the lock follows the pack. */
    private static List<OptionPage> pages() {
        OptionBlock[] blocks = {
                new OptionBlock("", new Option<?>[]{shadowDistance()}),
                new OptionBlock("", new Option<?>[]{colorSpace()})};
        String page = Component.translatable("options.chimera.page.shaders").getString();
        return List.of(new OptionPage(page, blocks));
    }

    /** Iris's Max Shadow Distance, 0 to 32 chunks; a pack that sets shadowDistanceRenderMul locks it, as in Iris. */
    private static Option<Integer> shadowDistance() {
        ChimeraConfig config = ChimeraConfig.get();
        OptionalInt forced = ChimeraRenderer.forcedShadowDistance();
        boolean locked = forced.isPresent();
        // a negative multiplier forces no distance; Iris's culling still reads the player's
        int inForce = locked && forced.getAsInt() >= 0 ? forced.getAsInt() : config.maxShadowRenderDistance();
        RangeOption option = new LockableRangeOption(Component.translatable("options.chimera.shadowDistance"),
                0, ChimeraConfig.MAX_SHADOW_RENDER_DISTANCE, 1, chunks -> chunks(locked ? inForce : chunks),
                chunks -> {
                    // VulkanMod replays a change made before a pack locked the slider (restoreOptionDrafts)
                    if (!locked) config.setMaxShadowRenderDistance(chunks);
                },
                () -> Math.min(inForce, ChimeraConfig.MAX_SHADOW_RENDER_DISTANCE));
        option.setActivationFn(() -> !locked);
        option.setTooltip(chunks -> locked
                ? Component.translatable("options.chimera.shadowDistance.locked",
                        chunks(inForce).withStyle(ChatFormatting.GOLD))
                : Component.translatable("options.chimera.shadowDistance.tooltip"));
        option.setImpact(PerformanceImpact.HIGH);
        return option;
    }

    private static MutableComponent chunks(int chunks) {
        return chunks == 0 ? Component.translatable("options.off") : Component.translatable("options.chunks", chunks);
    }

    /** Iris's Color Space: the display colour space the world image is converted to after final. */
    private static Option<ColorSpace> colorSpace() {
        ChimeraConfig config = ChimeraConfig.get();
        CyclingOption<ColorSpace> option = new CyclingOption<>(Component.translatable("options.chimera.colorSpace"),
                ColorSpace.values(), config::setColorSpace, config::colorSpace);
        option.setTranslator(space -> Component.literal(space.displayName()));
        option.setTooltip(Component.translatable("options.chimera.colorSpace.tooltip"));
        option.setImpact(PerformanceImpact.LOW);
        return option;
    }
}
