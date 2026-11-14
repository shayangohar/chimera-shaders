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
 * The persisted shader settings, {@code config/chimera.properties}, written with Iris's keys:
 * {@code shaderPack} names a pack in the shaderpacks folder and {@code enableShaders} says
 * whether it runs; {@code maxShadowRenderDistance} and {@code colorSpace} are Iris's mod-wide
 * video settings (ChimeraSettingsPage). The selector screen, the settings page and the pack
 * commands write it; startup reads it.
 */
public final class ChimeraConfig {
    /** Iris IrisVideoSettings.shadowDistance's default and its slider's top, in chunks. */
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

    /** The saved shadow distance, kept to the slider's 0 to 32 chunks; Iris's 32 when unset or unreadable. */
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

    /** The saved colour space by Iris's name; sRGB when unset or unknown. */
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
     * The player's Max Shadow Distance in chunks, 0 to 32: how far the shadow pass draws when the
     * pack does not set its own distance, and no shadow pass at 0 (Iris IrisVideoSettings.shadowDistance).
     */
    public int maxShadowRenderDistance() {
        return maxShadowRenderDistance;
    }

    /** The colour space the finished world image is converted to (Iris IrisVideoSettings.colorSpace). */
    public ColorSpace colorSpace() {
        return colorSpace;
    }

    /** Records the selection and writes it; a failed write is logged and never thrown. */
    public synchronized void setSelection(String pack, boolean enabled) {
        this.shaderPack = pack == null || pack.isBlank() ? null : pack;
        this.enableShaders = enabled;
        save();
    }

    /** Records the Max Shadow Distance, kept to 0 to 32 chunks, and writes it; a failed write is logged. */
    public synchronized void setMaxShadowRenderDistance(int chunks) {
        this.maxShadowRenderDistance = Math.clamp(chunks, 0, MAX_SHADOW_RENDER_DISTANCE);
        save();
    }

    /** Records the Color Space and writes it; a failed write is logged and never thrown. */
    public synchronized void setColorSpace(ColorSpace space) {
        this.colorSpace = space == null ? ColorSpace.SRGB : space;
        save();
    }

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
