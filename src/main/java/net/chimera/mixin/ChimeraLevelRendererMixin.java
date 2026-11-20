// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.chimera.render.ChimeraHandRenderer;
import net.chimera.render.ChimeraMainPass;
import net.chimera.render.ChimeraRenderer;
import net.chimera.render.NonCullingFrustum;
import net.chimera.render.PreSkyPasses;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.ChimeraSkyBridge;
import net.chimera.render.shader.FamilyBufferSource;
import net.chimera.render.shader.PackUniformProvider;
import net.chimera.shaderpack.PackRenderingPhase;
import net.chimera.shaderpack.ParticleDirectives;
import net.chimera.shaderpack.WeatherDirectives;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.render.chunk.TerrainRenderState;
import net.vulkanmod.render.chunk.WorldRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Predicate;

/**
 * Drives Chimera's world and output split inside LevelRenderer.renderLevel. The world clear opens the HDR
 * world target; RETURN resolves it into the output target, where the host hand and the GUI draw.
 */
@Mixin(LevelRenderer.class)
public abstract class ChimeraLevelRendererMixin {
    private static final String PARTICLES = "Lnet/minecraft/client/particle/ParticleEngine;render("
            + "Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;F"
            + "Lnet/minecraft/client/renderer/culling/Frustum;Ljava/util/function/Predicate;)V";
    private static final String RENDER_ENTITY = "Lnet/minecraft/client/renderer/LevelRenderer;renderEntity("
            + "Lnet/minecraft/world/entity/Entity;DDDFLcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/MultiBufferSource;)V";
    private static final String RENDER_SKY = "Lnet/minecraft/client/renderer/LevelRenderer;renderSky("
            + "Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FLnet/minecraft/client/Camera;ZLjava/lang/Runnable;)V";

    /** Whether the last frame culled nothing (chimera$frustumCulling). */
    @Unique
    private boolean chimera$culledNothing;

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void chimera$beginFrame(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera,
                                    GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f modelView,
                                    Matrix4f projection, CallbackInfo ci) {
        ChimeraHandRenderer.beginFrame();
        FamilyBufferSource.beginLevel();
        PreSkyPasses.beginFrame();
        boolean cullNothing = false;
        if (ChimeraRenderer.beginLevelSegments()) {
            ChimeraEntityBridge.beginWorldWindow();
            PackUniformProvider.beginFrame(camera, deltaTracker.getGameTimeDeltaPartialTick(false),
                    modelView, projection);
            cullNothing = ChimeraRenderer.packDisablesFrustumCulling();
        }
        chimera$frustumCulling(cullNothing);
    }

    /**
     * A pack's frustum.culling=false culls nothing, as in Iris. VulkanMod rebuilds its visible sections only
     * when the camera moves, so a change either way schedules a rebuild.
     */
    @Unique
    private void chimera$frustumCulling(boolean cullNothing) {
        if (cullNothing) ((ChimeraLevelRendererAccessor) this).chimera$setCullingFrustum(NonCullingFrustum.INSTANCE);
        if (cullNothing == this.chimera$culledNothing) return;
        this.chimera$culledNothing = cullNothing;
        WorldRenderer sections = WorldRenderer.getInstance();
        if (sections != null) sections.scheduleGraphUpdate();
    }

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V"), require = 1)
    private void chimera$openWorld(int mask, boolean checkError, Operation<Void> original,
                                   @Local(argsOnly = true) Camera camera,
                                   @Local(argsOnly = true, ordinal = 0) Matrix4f modelView,
                                   @Local(argsOnly = true, ordinal = 1) Matrix4f projection) {
        ChimeraMainPass pass = ChimeraRenderer.segmentsActive() ? ChimeraRenderer.getMainPass() : null;
        if (pass == null) {
            original.call(mask, checkError);
            return;
        }
        float[] fog = RenderSystem.getShaderFogColor();
        PackUniformProvider.latchFogColor(fog);
        Vec3 position = camera.getPosition();
        pass.openLevelSegment(position.x, position.y, position.z, new float[] {fog[0], fog[1], fog[2]});
        chimera$horizon(pass, modelView, projection);
    }

    /**
     * Iris's horizon from beginLevelRendering, drawn before the sky's fog setup so the sky program reads last
     * frame's fog, as under Iris. Only for a normal sky or a dimension with skylight.
     */
    @Unique
    private static void chimera$horizon(ChimeraMainPass pass, Matrix4f modelView, Matrix4f projection) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || (level.effects().skyType() != DimensionSpecialEffects.SkyType.NORMAL
                && !level.dimensionType().hasSkyLight())) {
            return;
        }
        var previous = PackUniformProvider.setRenderingPhase(PackRenderingPhase.SKY);
        ChimeraSkyBridge.beginSky();
        try {
            // the state VulkanMod sets for the world at the head of renderSky
            TerrainRenderState.prepareWorldTerrainState();
            pass.rebindMainTarget();
            pass.drawPackHorizon(modelView, projection);
        } finally {
            ChimeraSkyBridge.endSky();
            PackUniformProvider.setRenderingPhase(previous);
        }
    }

    /** Iris's shadow and prepare passes, just before the sky, with the frustum renderLevel passes setupRender. */
    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = RENDER_SKY), require = 1)
    private void chimera$beforeSky(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera,
                                   GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f modelView,
                                   Matrix4f projection, CallbackInfo ci) {
        ChimeraMainPass pass = ChimeraRenderer.segmentsActive() ? ChimeraRenderer.getMainPass() : null;
        if (pass == null) return;
        var renderer = (ChimeraLevelRendererAccessor) this;
        Frustum captured = renderer.chimera$capturedFrustum();
        Player player = Minecraft.getInstance().player;
        PreSkyPasses.render(pass, camera, captured != null ? captured : renderer.chimera$cullingFrustum(),
                captured != null, player != null && player.isSpectator());
    }

    /** Makes one entity's render a batch group, as Iris's MixinLevelRenderer does (IrisBatchOrder). */
    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = RENDER_ENTITY), require = 1)
    private void chimera$entityGroup(LevelRenderer renderer, Entity entity, double cameraX, double cameraY,
                                     double cameraZ, float partialTick, PoseStack poses, MultiBufferSource buffers,
                                     Operation<Void> original) {
        boolean opened = FamilyBufferSource.startLevelGroup();
        try {
            original.call(renderer, entity, cameraX, cameraY, cameraZ, partialTick, poses, buffers);
        } finally {
            if (opened) FamilyBufferSource.endLevelGroup();
        }
    }

    /**
     * Iris's translucent boundary, in Iris's order: entity batches, the centre-depth sample, then the solid
     * hand, so depthtex2 holds every entity.
     */
    @Inject(method = "renderLevel", at = @At(value = "CONSTANT", args = "stringValue=translucent"), require = 1)
    private void chimera$translucentBoundary(CallbackInfo callback) {
        ChimeraMainPass pass = ChimeraRenderer.segmentsActive() ? ChimeraRenderer.getMainPass() : null;
        FamilyBufferSource.endLevelBatchesAtBoundary(pass);
        if (pass != null) pass.sampleCenterDepthBeforeHand();
        ChimeraHandRenderer.drawSolid();
    }

    /** The Fabulous path's particle call, which Iris leaves in place too. */
    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = PARTICLES, ordinal = 0), require = 1)
    private void chimera$fabulousParticles(ParticleEngine particles, LightTexture lightTexture, Camera camera,
                                           float partialTick, Frustum frustum, Predicate<ParticleRenderType> types,
                                           Operation<Void> original) {
        chimera$drawParticles(particles, lightTexture, camera, partialTick, frustum, types, original);
    }

    /** NeoForge's early particle call: opaque particles, before translucent terrain and the deferred passes. */
    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = PARTICLES, ordinal = 1), require = 1)
    private void chimera$earlyParticles(ParticleEngine particles, LightTexture lightTexture, Camera camera,
                                        float partialTick, Frustum frustum, Predicate<ParticleRenderType> types,
                                        Operation<Void> original) {
        chimera$orderParticles(true, particles, lightTexture, camera, partialTick, frustum, types, original);
    }

    /** NeoForge's late particle call: translucent particles, after translucent terrain. */
    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = PARTICLES, ordinal = 2), require = 1)
    private void chimera$lateParticles(ParticleEngine particles, LightTexture lightTexture, Camera camera,
                                       float partialTick, Frustum frustum, Predicate<ParticleRenderType> types,
                                       Operation<Void> original) {
        chimera$orderParticles(false, particles, lightTexture, camera, partialTick, frustum, types, original);
    }

    /**
     * Moves particles between NeoForge's two calls as the pack's ordering asks, as Iris on NeoForge does;
     * mixed keeps NeoForge's split.
     */
    private static void chimera$orderParticles(boolean early, ParticleEngine particles, LightTexture lightTexture,
                                               Camera camera, float partialTick, Frustum frustum,
                                               Predicate<ParticleRenderType> types, Operation<Void> original) {
        ParticleDirectives.Ordering ordering = ChimeraRenderer.packParticleOrdering();
        if (ordering == null) {
            chimera$drawParticles(particles, lightTexture, camera, partialTick, frustum, types, original);
        } else if (ordering.drawsIn(early)) {
            Predicate<ParticleRenderType> ordered = type -> ordering.drawsSheet(early, type.isTranslucent());
            chimera$drawParticles(particles, lightTexture, camera, partialTick, frustum, ordered, original);
        }
    }

    /** One particle call inside the particle lanes; the world has drawn once it returns. */
    private static void chimera$drawParticles(ParticleEngine particles, LightTexture lightTexture, Camera camera,
                                              float partialTick, Frustum frustum,
                                              Predicate<ParticleRenderType> types, Operation<Void> original) {
        ChimeraEntityBridge.beginParticles();
        try {
            original.call(particles, lightTexture, camera, partialTick, frustum, types);
        } finally {
            ChimeraEntityBridge.endParticles();
            ChimeraMainPass pass = ChimeraRenderer.segmentsActive() ? ChimeraRenderer.getMainPass() : null;
            if (pass != null) pass.markWorldDrawn();
        }
    }

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderSnowAndRain("
                    + "Lnet/minecraft/client/renderer/LightTexture;FDDD)V"), require = 1)
    private void chimera$weatherLane(LevelRenderer renderer, LightTexture lightTexture, float partialTick,
                                     double x, double y, double z, Operation<Void> original) {
        ChimeraEntityBridge.beginWeather();
        try {
            original.call(renderer, lightTexture, partialTick, x, y, z);
        } finally {
            ChimeraEntityBridge.endWeather();
        }
    }

    /**
     * Iris's rain.depth: forces renderSnowAndRain's first depthMask to true, so later passes see weather in
     * depthtex0. VulkanMod sets its weather state at the method's head, before this call.
     */
    @ModifyArg(method = "renderSnowAndRain", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;depthMask(Z)V", ordinal = 0), require = 1)
    private boolean chimera$rainDepth(boolean depthMask) {
        WeatherDirectives weather = ChimeraRenderer.packWeatherDirectives();
        return depthMask || weather != null && weather.rainDepth();
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void chimera$resolveWorld(CallbackInfo ci) {
        if (ChimeraRenderer.segmentsActive()) {
            ChimeraMainPass pass = ChimeraRenderer.getMainPass();
            // family batches a mod's render-stage drawing left behind, crumbling included
            FamilyBufferSource.endHeldLevelBatches(pass);
            if (pass != null) {
                ChimeraHandRenderer.drawTranslucent();
                pass.finishLevelSegment();
            }
            ChimeraEntityBridge.endWorldWindow();
        }
    }
}
