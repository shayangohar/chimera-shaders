// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render.vertex;

import net.chimera.mixin.ChimeraTerrainBufferBuilderAccessor;
import net.chimera.render.LightEmission;
import net.chimera.shaderpack.PackMaterialResolver;
import net.chimera.shaderpack.TerrainMaterialPlan;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.render.vertex.TerrainBufferBuilder;

/** VulkanMod's terrain builder, writing the current block's or fluid's material identity into each vertex. */
public final class ChimeraExtTerrainBuilder extends TerrainBufferBuilder {
    private final PackMaterialResolver materialResolver;
    private final ChimeraExtVertexBuilder extendedVertexBuilder;

    /** The host builder accepts only its own formats, so it starts on COMPRESSED_TERRAIN, a shared prefix. */
    public ChimeraExtTerrainBuilder(int size, PackMaterialResolver materialResolver,
                                    TerrainMaterialPlan materialPlan) {
        super(size, CustomVertexFormat.COMPRESSED_TERRAIN);
        TerrainMaterialPlan plan = materialPlan == null ? TerrainMaterialPlan.legacy() : materialPlan;
        var builder = (ChimeraTerrainBufferBuilderAccessor) (Object) this;
        builder.chimera$setFormat(ChimeraVertexFormats.terrainFormat(plan));
        this.materialResolver = materialResolver == null ? PackMaterialResolver.empty() : materialResolver;
        this.extendedVertexBuilder = new ChimeraExtVertexBuilder(plan);
        this.vertexBuilder = this.extendedVertexBuilder;
    }

    /** Called after the host's setBlockAttributes, which passes the state only. */
    public void beginBlock(int localX, int localY, int localZ) {
        extendedVertexBuilder.setBlockPosition(localX, localY, localZ);
    }

    /** Iris's voxelizeLightBlocks quad ({@link ChimeraExtVertexBuilder#lightBlockQuad}). */
    public void lightBlockQuad(BlockState blockState, int localX, int localY, int localZ) {
        ensureCapacity();
        extendedVertexBuilder.lightBlockQuad(getPtr(), materialResolver.resolve(blockState),
                LightEmission.of(blockState), localX, localY, localZ);
        for (int corner = 0; corner < 4; corner++) {
            endVertex();
        }
    }

    @Override
    public void setBlockAttributes(BlockState blockState) {
        extendedVertexBuilder.setBlockData(materialResolver.resolve(blockState),
                ChimeraExtVertexBuilder.BLOCK_RENDER_TYPE, LightEmission.of(blockState));
    }

    /** As in Iris, the material is the fluid's own block but the light emission is the holding block's. */
    public void setFluidBlockAttributes(BlockState blockState, FluidState fluidState,
                                        int localX, int localY, int localZ) {
        extendedVertexBuilder.setBlockData(materialResolver.resolve(fluidState.createLegacyBlock()),
                ChimeraExtVertexBuilder.FLUID_RENDER_TYPE, LightEmission.of(blockState));
        extendedVertexBuilder.setBlockPosition(localX, localY, localZ);
    }
}
