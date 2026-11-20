// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render.shader;

import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.shader.Uniforms;
import net.vulkanmod.vulkan.util.MappedBuffer;

/**
 * Values Chimera's terrain and shadow vertex shaders read beside MVP. VulkanMod rounds each chunk area's ChunkOffset
 * to float on its own, so a shared corner lands a float step apart in each and leaves seam pixels unfilled; with
 * {@value #CAMERA_FRACTION} the shaders recover whole-block offsets exactly and subtract the fraction last.
 * {@value #TEXCOORD_SHRINK} takes back encodeTexture's inward step less one sub-texel, as Iris's u_TexCoordShrink,
 * so a lookup stays off the neighbouring sprite.
 */
public final class TerrainVertexUniforms {
    public static final String CAMERA_FRACTION = "ChimeraCameraFraction";
    public static final String TEXCOORD_SHRINK = "ChimeraTexCoordShrink";
    private static final float TEXCOORD_STEP = 1.0F / 32768.0F;
    private static final MappedBuffer FRACTION = new MappedBuffer(16);
    private static final MappedBuffer SHRINK = new MappedBuffer(16);
    private static int subTexelBits = 8;

    private TerrainVertexUniforms() {}

    /** Must run before any pipeline that declares them is built. */
    public static void register() {
        subTexelBits = DeviceManager.deviceProperties.limits().subTexelPrecisionBits();
        Uniforms.vec4f_uniformMap.put(CAMERA_FRACTION, () -> FRACTION);
        Uniforms.vec4f_uniformMap.put(TEXCOORD_SHRINK, () -> SHRINK);
    }

    /** Called before renderSectionLayer uploads the layer's UBOs. */
    public static void update(double cameraX, double cameraY, double cameraZ) {
        FRACTION.putFloat(0, fraction(cameraX));
        FRACTION.putFloat(4, fraction(cameraY));
        FRACTION.putFloat(8, fraction(cameraZ));
        int[] atlas = AtlasSizes.blocks();
        SHRINK.putFloat(0, texcoordShrink(atlas[0], subTexelBits));
        SHRINK.putFloat(4, texcoordShrink(atlas[1], subTexelBits));
    }

    static float fraction(double coordinate) {
        return (float) (coordinate - Math.floor(coordinate));
    }

    /** A whole step for no atlas. */
    public static float texcoordShrink(int size, int subTexelBits) {
        return size <= 0 ? TEXCOORD_STEP : (float) (TEXCOORD_STEP - 1.0 / size / (1 << subTexelBits));
    }
}
