package net.chimera.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Set;

/**
 * Level renderer internals: entity drawing and global block entities for the shadow casters, the culling
 * frustums for the section setup before the sky, and the blindness and darkness check.
 */
@Mixin(LevelRenderer.class)
public interface ChimeraLevelRendererAccessor {
    /** The frustum the debug key froze, or null. */
    @Accessor("capturedFrustum")
    Frustum chimera$capturedFrustum();

    @Accessor("cullingFrustum")
    Frustum chimera$cullingFrustum();

    /** GameRenderer prepares a new one before each renderLevel, so a frustum set here lasts one frame. */
    @Accessor("cullingFrustum")
    void chimera$setCullingFrustum(Frustum frustum);

    /** Guard reads with the set's own monitor, as LevelRenderer does. */
    @Accessor("globalBlockEntities")
    Set<BlockEntity> chimera$globalBlockEntities();

    @Invoker("renderEntity")
    void chimera$renderEntity(Entity entity, double cameraX, double cameraY, double cameraZ, float partialTick,
                              PoseStack poses, MultiBufferSource buffers);

    /** Whether the camera's entity has blindness or darkness, which hide the sky (ChimeraSunriseColorMixin). */
    @Invoker("doesMobEffectBlockSky")
    boolean chimera$doesMobEffectBlockSky(Camera camera);
}
