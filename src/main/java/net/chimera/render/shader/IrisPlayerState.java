// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render.shader;

import net.chimera.config.ChimeraConfig;
import net.chimera.mixin.ChimeraLocalPlayerAccessor;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AmbientSoundHandler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.vulkan.util.MappedBuffer;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Iris's player, HUD, clock and Distant Horizons uniforms, captured once per frame. The {@code dh*} values are
 * the ones Iris serves without Distant Horizons.
 */
final class IrisPlayerState implements FrameValueSource {
    /** Matrices stay with the frame state. */
    static final Set<String> NAMES = Set.of(
            "hideGUI", "isRightHanded", "firstPersonCamera", "isSpectator",
            "currentPlayerHealth", "maxPlayerHealth", "currentPlayerHunger", "maxPlayerHunger",
            "currentPlayerArmor", "maxPlayerArmor", "currentPlayerAir", "maxPlayerAir",
            "playerMood", "constantMood", "pi", "lastFrameTime", "currentColorSpace",
            "playerBodyVector",
            "currentDate", "currentTime", "currentYearTime",
            "dhNearPlane", "dhFarPlane", "dhRenderDistance", "blockEntityId", "currentRenderedItemId", "entityId",
            "is_burning", "is_hurt", "is_invisible", "is_on_ground", "is_sneaking", "is_sprinting");


    /** DHCompat's planes without Distant Horizons. */
    static final float DH_ABSENT_PLANE = 0.01f;

    private boolean hideGui;
    private boolean rightHanded = true;
    private boolean firstPerson = true;
    private boolean spectator;
    private float health = -1.0f;
    private float maxHealth = -1.0f;
    private float hunger = -1.0f;
    private float armor = -1.0f;
    private float air = -1.0f;
    private float maxAir = -1.0f;
    private float mood;
    private float constantMood;
    private float lastFrameTime;
    private int renderDistance;
    private int colorSpace;
    private final float[] bodyVector = new float[3];
    private final int[] date = new int[3];
    private final int[] time = new int[3];
    private final int[] yearTime = new int[2];
    private final Map<String, Boolean> playerFlags = new HashMap<>();

    void capture(Minecraft minecraft, float frameTime) {
        this.lastFrameTime = frameTime;
        captureOptions(minecraft);
        capturePlayerStats(minecraft);
        Entity cameraEntity = minecraft.getCameraEntity();
        set(this.bodyVector, cameraEntity == null ? null : cameraEntity.getForward());
        this.mood = cameraEntity instanceof LocalPlayer local
                ? Math.clamp(local.getCurrentMood(), 0.0f, 1.0f) : 0.0f;
        this.constantMood = cameraEntity instanceof LocalPlayer local ? constantMood(local) : 0.0f;
        captureClock();
    }

    /** The biome ambience handler's mood, 0 without one. */
    private static float constantMood(LocalPlayer player) {
        for (AmbientSoundHandler handler : ((ChimeraLocalPlayerAccessor) player).chimera$ambientSoundHandlers()) {
            if (handler instanceof AmbientMoodAccess ambience) {
                return Math.clamp(ambience.chimera$constantMood(), 0.0f, 1.0f);
            }
        }
        return 0.0f;
    }

    private void captureOptions(Minecraft minecraft) {
        this.hideGui = minecraft.options.hideGui;
        this.rightHanded = minecraft.options.mainHand().get() == HumanoidArm.RIGHT;
        CameraType camera = minecraft.options.getCameraType();
        this.firstPerson = camera != CameraType.THIRD_PERSON_BACK && camera != CameraType.THIRD_PERSON_FRONT;
        this.renderDistance = minecraft.options.getEffectiveRenderDistance();
        this.colorSpace = ChimeraConfig.get().colorSpace().ordinal();
        this.spectator = minecraft.gameMode != null
                && minecraft.gameMode.getPlayerMode() == GameType.SPECTATOR;
    }

    /** Player stats read -1 outside survival, as Iris reports them. */
    private void capturePlayerStats(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        boolean survival = player != null && minecraft.gameMode != null
                && minecraft.gameMode.getPlayerMode().isSurvival();
        this.health = survival ? player.getHealth() / player.getMaxHealth() : -1.0f;
        this.maxHealth = survival ? player.getMaxHealth() : -1.0f;
        this.hunger = survival ? player.getFoodData().getFoodLevel() / 20.0f : -1.0f;
        this.armor = survival ? player.getArmorValue() / 50.0f : -1.0f;
        this.air = survival ? (float) player.getAirSupply() / (float) player.getMaxAirSupply() : -1.0f;
        this.maxAir = survival ? player.getMaxAirSupply() : -1.0f;
        capturePlayerFlags(player);
    }

    private void capturePlayerFlags(LocalPlayer player) {
        this.playerFlags.clear();
        if (player == null) return;
        this.playerFlags.put("is_burning", player.isOnFire());
        this.playerFlags.put("is_hurt", player.hurtTime > 0);
        this.playerFlags.put("is_invisible", player.isInvisible());
        this.playerFlags.put("is_on_ground", player.onGround());
        this.playerFlags.put("is_sneaking", player.isCrouching());
        this.playerFlags.put("is_sprinting", player.isSprinting());
    }

    private void captureClock() {
        LocalDateTime now = LocalDateTime.now();
        this.date[0] = now.getYear();
        this.date[1] = now.getMonthValue();
        this.date[2] = now.getDayOfMonth();
        this.time[0] = now.getHour();
        this.time[1] = now.getMinute();
        this.time[2] = now.getSecond();
        int elapsed = (now.getDayOfYear() - 1) * 86400 + now.getHour() * 3600 + now.getMinute() * 60
                + now.getSecond();
        this.yearTime[0] = elapsed;
        this.yearTime[1] = now.toLocalDate().lengthOfYear() * 86400 - elapsed;
    }

    @Override
    public Set<String> names() {
        return NAMES;
    }

    public double value(String name, int component) {
        int c = Math.max(component, 0);
        return switch (name) {
            case "hideGUI" -> flag(this.hideGui);
            case "isRightHanded" -> flag(this.rightHanded);
            case "firstPersonCamera" -> flag(this.firstPerson);
            case "isSpectator" -> flag(this.spectator);
            case "pi" -> (float) Math.PI;
            case "lastFrameTime" -> this.lastFrameTime;
            // a supportsColorCorrection pack converts to it
            case "currentColorSpace" -> this.colorSpace;
            case "playerBodyVector" -> c < 3 ? this.bodyVector[c] : Double.NaN;
            case "currentDate" -> c < 3 ? this.date[c] : Double.NaN;
            case "currentTime" -> c < 3 ? this.time[c] : Double.NaN;
            case "currentYearTime" -> c < 2 ? this.yearTime[c] : Double.NaN;
            case "dhNearPlane", "dhFarPlane" -> DH_ABSENT_PLANE;
            case "dhRenderDistance" -> this.renderDistance;
            // Iris's values between draws; entity-layout programs read these per vertex (EntityDeclarations)
            case "blockEntityId", "currentRenderedItemId" -> -1;
            case "entityId" -> 0;
            default -> playerStat(name);
        };
    }

    private double playerStat(String name) {
        return switch (name) {
            case "currentPlayerHealth" -> this.health;
            case "maxPlayerHealth" -> this.maxHealth;
            case "currentPlayerHunger" -> this.hunger;
            case "maxPlayerHunger" -> 20.0;
            case "currentPlayerArmor" -> this.armor;
            case "maxPlayerArmor" -> 50.0;
            case "currentPlayerAir" -> this.air;
            case "maxPlayerAir" -> this.maxAir;
            case "playerMood" -> this.mood;
            case "constantMood" -> this.constantMood;
            default -> playerFlag(name);
        };
    }

    private double playerFlag(String name) {
        return NAMES.contains(name) ? flag(this.playerFlags.getOrDefault(name, false)) : Double.NaN;
    }

    @Override
    public void write(String name, String type, MappedBuffer target) {
        int count = componentCount(type);
        boolean integral = type.startsWith("i") || type.equals("bool");
        for (int component = 0; component < count; component++) {
            double value = value(name, count == 1 ? -1 : component);
            if (integral) {
                target.putInt(component * 4, (int) value);
            } else {
                target.putFloat(component * 4, (float) value);
            }
        }
    }

    private static int componentCount(String type) {
        return switch (type) {
            case "vec2", "ivec2" -> 2;
            case "vec3", "ivec3" -> 3;
            case "vec4", "ivec4" -> 4;
            default -> 1;
        };
    }

    private static double flag(boolean value) {
        return value ? 1.0 : 0.0;
    }

    private static void set(float[] destination, Vec3 value) {
        destination[0] = value == null ? 0.0f : (float) value.x;
        destination[1] = value == null ? 0.0f : (float) value.y;
        destination[2] = value == null ? 0.0f : (float) value.z;
    }
}
