// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.config;

import net.chimera.ChimeraMod;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.Properties;

/**
 * Shader settings in {@code config/chimera.properties}, under Iris's keys.
 *
 * {@code shaderPack} names a pack in the shaderpacks folder and {@code enableShaders} says whether it runs.
 * {@code maxShadowRenderDistance} and {@code colorSpace} are Iris's mod-wide video settings, shown on
 * ChimeraSettingsPage.
 */
public final class ChimeraConfig {
    /** Iris's default shadow distance and the slider's top, in chunks (IrisVideoSettings.shadowDistance). */
    public static final int MAX_SHADOW_RENDER_DISTANCE = 32;
    private static final String SHADER_PACK = "shaderPack";
    private static final String ENABLE_SHADERS = "enableShaders";
    private static final String SHADOW_DISTANCE = "maxShadowRenderDistance";
    private static final String COLOR_SPACE = "colorSpace";
    private static ChimeraConfig instance;

    private final Path file;
    private String shaderPack;
    private boolean enableShaders;
    private volatile int maxShadowRenderDistance = MAX_SHADOW_RENDER_DISTANCE;
    private volatile ColorSpace colorSpace = ColorSpace.SRGB;

    private ChimeraConfig(Path file) {
        this.file = file;
    }

    public static synchronized ChimeraConfig get() {
        if (instance == null) {
            instance = load(FMLPaths.CONFIGDIR.get().resolve("chimera.properties"));
        }
        return instance;
    }

    static ChimeraConfig load(Path file) {
        ChimeraConfig config = new ChimeraConfig(file);
        if (Files.isRegularFile(file)) {
            Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
            } catch (IOException failure) {
                ChimeraMod.LOGGER.warn("[chimera] cannot read {}; starting with no pack", file, failure);
            }
            String pack = properties.getProperty(SHADER_PACK, "").trim();
            config.shaderPack = pack.isEmpty() ? null : pack;
            config.enableShaders = Boolean.parseBoolean(properties.getProperty(ENABLE_SHADERS, "false"));
            config.maxShadowRenderDistance = shadowDistance(properties.getProperty(SHADOW_DISTANCE), file);
            config.colorSpace = colorSpace(properties.getProperty(COLOR_SPACE), file);
        }
        return config;
    }

    /** Saved shadow distance clamped to the slider's 0 to 32 chunks; 32, as in Iris, when unset or unreadable. */
    private static int shadowDistance(String value, Path file) {
        if (value == null) return MAX_SHADOW_RENDER_DISTANCE;
        try {
            return Math.clamp(Integer.parseInt(value.trim()), 0, MAX_SHADOW_RENDER_DISTANCE);
        } catch (NumberFormatException invalid) {
            ChimeraMod.LOGGER.warn("[chimera] {}: {}={} is not a number; using {}", file, SHADOW_DISTANCE, value,
                    MAX_SHADOW_RENDER_DISTANCE);
            return MAX_SHADOW_RENDER_DISTANCE;
        }
    }

    /** Saved colour space by Iris's enum name; sRGB when unset or unknown. */
    private static ColorSpace colorSpace(String value, Path file) {
        if (value == null) return ColorSpace.SRGB;
        try {
            return ColorSpace.valueOf(value.trim());
        } catch (IllegalArgumentException invalid) {
            ChimeraMod.LOGGER.warn("[chimera] {}: {}={} is not a colour space; using SRGB", file, COLOR_SPACE, value);
            return ColorSpace.SRGB;
        }
    }

    public Optional<String> shaderPack() {
        return Optional.ofNullable(shaderPack);
    }

    public boolean shadersEnabled() {
        return enableShaders;
    }

    /**
     * The player's Max Shadow Distance in chunks, 0 to 32: how far the shadow pass draws when the pack sets
     * no distance of its own; 0 means no shadow pass (IrisVideoSettings.shadowDistance).
     */
    public int maxShadowRenderDistance() {
        return maxShadowRenderDistance;
    }

    /** The colour space the finished world image is converted to (IrisVideoSettings.colorSpace). */
    public ColorSpace colorSpace() {
        return colorSpace;
    }

    public synchronized void setSelection(String pack, boolean enabled) {
        this.shaderPack = pack == null || pack.isBlank() ? null : pack;
        this.enableShaders = enabled;
        save();
    }

    public synchronized void setMaxShadowRenderDistance(int chunks) {
        this.maxShadowRenderDistance = Math.clamp(chunks, 0, MAX_SHADOW_RENDER_DISTANCE);
        save();
    }

    public synchronized void setColorSpace(ColorSpace space) {
        this.colorSpace = space == null ? ColorSpace.SRGB : space;
        save();
    }

    /** Writes the file through a temporary sibling; a failed write is logged, never thrown. */
    private void save() {
        Properties properties = new Properties();
        properties.setProperty(SHADER_PACK, shaderPack == null ? "" : shaderPack);
        properties.setProperty(ENABLE_SHADERS, Boolean.toString(enableShaders));
        properties.setProperty(SHADOW_DISTANCE, Integer.toString(maxShadowRenderDistance));
        properties.setProperty(COLOR_SPACE, colorSpace.name());
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(temporary)) {
                properties.store(out, "Chimera shader settings");
            }
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException failure) {
            ChimeraMod.LOGGER.warn("[chimera] cannot write {}", file, failure);
        }
    }
}
