// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render.shader;

import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.material.FogType;
import net.vulkanmod.vulkan.util.MappedBuffer;

import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Iris's fog uniforms (FogUniforms PER_VERTEX, iris_FogStart and iris_FogEnd) and cloudTime. fogMode and
 * fogDensity follow each FogRenderer.setupFog; density is water fog's, -1 for other fog (Iris MixinFogRenderer).
 * fogShape and the gl_Fog range are PER_FRAME in Iris, latched at a program's first draw in a frame, so the sky
 * disc reads the Float.MAX_VALUE start setupNoFog left last frame. Packs that fog their sky with gl_Fog.start
 * depend on it.
 */
final class IrisWorldState implements FrameValueSource {
    static final Set<String> NAMES = Set.of("fogMode", "fogShape", "fogDensity", "iris_FogStart", "iris_FogEnd",
            "cloudTime");

    private static final int GL_LINEAR = 0x2601;
    private static final int GL_EXP2 = 0x0801;

    private volatile float fogDensity;
    private volatile int fogShape;
    /** Keyed by pack pipeline, which stands for Iris's per-shader-key program. */
    private final Map<Object, FrameFog> programFog = new WeakHashMap<>();
    /** What the bindings were last written with. */
    private final FrameFog drawFog = new FrameFog();
    private long frame;
    private float cloudTime;

    /** Taken as FogRenderer's setup begins. */
    void captureFogDensity(Camera camera) {
        if (camera.getFluidInCamera() != FogType.WATER) {
            fogDensity = -1.0F;
            return;
        }
        float density = 0.05F;
        if (camera.getEntity() instanceof LocalPlayer player) {
            float vision = player.getWaterVision();
            density -= vision * vision * 0.03F;
            if (player.level().getBiome(player.blockPosition()).is(BiomeTags.HAS_CLOSER_WATER_FOG)) {
                density += 0.005F;
            }
        }
        fogDensity = density;
    }

    void captureFogShape() {
        fogShape = RenderSystem.getShaderFogShape() == FogShape.CYLINDER ? 1 : 0;
    }

    /** The captured fog stays, as Iris's CapturedRenderingState keeps it across a reload. */
    void resetProgramFog() {
        programFog.clear();
    }

    void beginFrame() {
        frame++;
    }

    /**
     * A null program, a compute dispatch, reads the current values, as Iris runs each compute once a frame.
     * True when the served values changed.
     */
    boolean latchProgramFog(Object program, PackFrameState live) {
        FrameFog latched = program == null ? new FrameFog()
                : programFog.computeIfAbsent(program, key -> new FrameFog());
        if (latched.frame != frame) {
            latched.set(frame, (float) live.value("fogStart", -1), (float) live.value("fogEnd", -1), fogShape);
        }
        return drawFog.set(latched.frame, latched.start, latched.end, latched.shape);
    }

    void capture(Minecraft minecraft, float partialTick) {
        int ticks = minecraft.levelRenderer == null ? 0 : minecraft.levelRenderer.getTicks();
        this.cloudTime = (ticks + partialTick) * 0.03F;
    }

    @Override
    public Set<String> names() {
        return NAMES;
    }

    @Override
    public double value(String name, int component) {
        if (component > 0) return Double.NaN;
        return switch (name) {
            case "fogMode" -> fogDensity < 0.0F ? GL_LINEAR : GL_EXP2;
            case "fogShape" -> drawFog.shape;
            case "fogDensity" -> Math.max(0.0F, fogDensity);
            case "iris_FogStart" -> drawFog.start;
            case "iris_FogEnd" -> drawFog.end;
            case "cloudTime" -> this.cloudTime;
            default -> Double.NaN;
        };
    }

    @Override
    public void write(String name, String type, MappedBuffer target) {
        double value = value(name, -1);
        if (type.startsWith("i") || type.equals("bool")) {
            target.putInt(0, (int) value);
        } else {
            target.putFloat(0, (float) value);
        }
    }

    private static final class FrameFog {
        private long frame = -1L;
        private float start;
        private float end;
        private int shape;

        /** True when the values changed. */
        boolean set(long frame, float start, float end, int shape) {
            boolean changed = Float.compare(this.start, start) != 0 || Float.compare(this.end, end) != 0
                    || this.shape != shape;
            this.frame = frame;
            this.start = start;
            this.end = end;
            this.shape = shape;
            return changed;
        }
    }
}
