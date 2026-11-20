// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render.vertex;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.chimera.shaderpack.TerrainMaterialPlan;
import net.vulkanmod.render.vertex.CustomVertexFormat;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_UINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16_SINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;

/** Chimera's own vertex formats: the extended terrain layouts and the widened family layouts. */
public final class ChimeraVertexFormats {
    public static final VertexFormatElement MATERIAL_ID =
            new VertexFormatElement(3, 0, VertexFormatElement.Type.INT,
                    VertexFormatElement.Usage.GENERIC, 1);
    public static final VertexFormatElement RENDER_TYPE =
            new VertexFormatElement(4, 0, VertexFormatElement.Type.INT,
                    VertexFormatElement.Usage.GENERIC, 1);

    /**
     * Four unsigned shorts declared as one short and six bytes of padding; ChimeraEntityVertexInputMixin gives
     * Vulkan the full input at the same offset.
     */
    public static final VertexFormatElement ENTITY_IDS =
            VertexFormatElement.register(16, 0, VertexFormatElement.Type.SHORT,
                    VertexFormatElement.Usage.GENERIC, 1);
    public static final VertexFormatElement MID_TEX_COORD =
            VertexFormatElement.register(17, 0, VertexFormatElement.Type.FLOAT,
                    VertexFormatElement.Usage.GENERIC, 1);
    public static final VertexFormatElement TANGENT =
            VertexFormatElement.register(18, 0, VertexFormatElement.Type.INT,
                    VertexFormatElement.Usage.GENERIC, 1);

    /** Two unsigned words in 1/32768 steps. */
    public static final VertexFormatElement TERRAIN_MID_TEX_COORD =
            new VertexFormatElement(5, 0, VertexFormatElement.Type.USHORT,
                    VertexFormatElement.Usage.UV, 2);
    /** Signed bytes in 1/64 blocks, with the light emission in the fourth byte. */
    public static final VertexFormatElement TERRAIN_MID_BLOCK =
            new VertexFormatElement(6, 0, VertexFormatElement.Type.INT,
                    VertexFormatElement.Usage.GENERIC, 1);
    /** The packed face normal, with the tangent frame in the fourth byte (TerrainTangentCodec). */
    public static final VertexFormatElement TERRAIN_TANGENT_FRAME =
            new VertexFormatElement(7, 0, VertexFormatElement.Type.BYTE,
                    VertexFormatElement.Usage.NORMAL, 4);

    /** VulkanMod's COMPRESSED_TERRAIN prefix, then the material attributes: 24 bytes. */
    public static final VertexFormat EXTENDED_COMPRESSED_TERRAIN =
            VertexFormat.builder()
                    .add("Position", CustomVertexFormat.ELEMENT_POSITION)
                    .add("Color", CustomVertexFormat.ELEMENT_COLOR)
                    .add("UV0", CustomVertexFormat.ELEMENT_UV0)
                    .add("MaterialId", MATERIAL_ID)
                    .add("RenderType", RENDER_TYPE)
                    .build();

    /** 36 bytes; separateAo needs no word, since brightness rides in the colour's alpha. */
    public static final VertexFormat MODERN_COMPRESSED_TERRAIN =
            VertexFormat.builder()
                    .add("Position", CustomVertexFormat.ELEMENT_POSITION)
                    .add("Color", CustomVertexFormat.ELEMENT_COLOR)
                    .add("UV0", CustomVertexFormat.ELEMENT_UV0)
                    .add("MaterialId", MATERIAL_ID)
                    .add("RenderType", RENDER_TYPE)
                    .add("MidTexCoord", TERRAIN_MID_TEX_COORD)
                    .add("MidBlock", TERRAIN_MID_BLOCK)
                    .add("Tangent", TERRAIN_TANGENT_FRAME)
                    .build();

    public static final VertexFormat EXTENDED_ENTITY = extendedEntityFormat();

    public static final VertexFormat EXTENDED_PARTICLE = extendedParticleFormat();

    public static VertexFormat terrainFormat(TerrainMaterialPlan plan) {
        return plan != null && plan.modern() ? MODERN_COMPRESSED_TERRAIN : EXTENDED_COMPRESSED_TERRAIN;
    }

    public static boolean isTerrainFormat(VertexFormat format) {
        return format == EXTENDED_COMPRESSED_TERRAIN || format == MODERN_COMPRESSED_TERRAIN;
    }

    private static VertexFormat extendedEntityFormat() {
        VertexFormat.Builder builder = VertexFormat.builder();
        List<VertexFormatElement> elements = DefaultVertexFormat.NEW_ENTITY.getElements();
        List<String> names = DefaultVertexFormat.NEW_ENTITY.getElementAttributeNames();
        int hostElementBytes = 0;
        for (int index = 0; index < elements.size(); index++) {
            builder.add(names.get(index), elements.get(index));
            hostElementBytes += elements.get(index).byteSize();
        }
        if (DefaultVertexFormat.NEW_ENTITY.getVertexSize() > hostElementBytes) {
            builder.padding(DefaultVertexFormat.NEW_ENTITY.getVertexSize() - hostElementBytes);
        }
        builder.add("EntityIds", ENTITY_IDS);
        builder.padding(6);
        builder.add("MidTexCoord", MID_TEX_COORD);
        builder.padding(4);
        builder.add("Tangent", TANGENT);
        return builder.build();
    }

    private static VertexFormat extendedParticleFormat() {
        return appendMaterialInputs(DefaultVertexFormat.PARTICLE);
    }

    /** Iris's GLYPH: text gains a normal, computed per glyph quad. */
    public static final VertexFormat EXTENDED_GLYPH = appendMaterialInputs(
            DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP, true);
    /**
     * Falling blocks and moving pistons, which Iris extends to TERRAIN outside chunk meshes. VertexFormat.equals
     * cannot tell it from EXTENDED_GLYPH; formats compare by identity, and a map that merges them does no harm.
     */
    public static final VertexFormat EXTENDED_BLOCK = appendMaterialInputs(DefaultVertexFormat.BLOCK);
    private static final VertexFormat EXTENDED_HAND_MAP_BACKGROUND = appendMaterialInputs(
            DefaultVertexFormat.POSITION_TEX_COLOR);
    private static final Map<VertexFormat, VertexFormat> HAND_FORMATS = handFormatsInOrder();

    /** The order is fixed because each hand variant is named by its position, as pack_gbuffers_hand_N. */
    public static Map<VertexFormat, VertexFormat> handFormats() {
        return HAND_FORMATS;
    }

    /** Not Map.of, whose order varies per JVM start. */
    private static Map<VertexFormat, VertexFormat> handFormatsInOrder() {
        Map<VertexFormat, VertexFormat> formats = new LinkedHashMap<>();
        formats.put(DefaultVertexFormat.NEW_ENTITY, EXTENDED_ENTITY);
        formats.put(DefaultVertexFormat.PARTICLE, EXTENDED_PARTICLE);
        formats.put(DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP, EXTENDED_GLYPH);
        formats.put(DefaultVertexFormat.POSITION_TEX_COLOR, EXTENDED_HAND_MAP_BACKGROUND);
        return Collections.unmodifiableMap(formats);
    }

    /** Null when nothing widens the host layout. */
    public static VertexFormat extendedLayout(VertexFormat host) {
        return host == DefaultVertexFormat.BLOCK ? EXTENDED_BLOCK : HAND_FORMATS.get(host);
    }

    /** No host program reads these layouts. */
    public static boolean isFamilyFormat(VertexFormat format) {
        return format == EXTENDED_BLOCK || HAND_FORMATS.containsValue(format);
    }

    /** By location; Reforged's translator rejects the family scalars, so ChimeraEntityVertexInputMixin reads this. */
    public static int[] vulkanInputFormats(VertexFormat format) {
        return format.getElementAttributeNames().stream().mapToInt(ChimeraVertexFormats::vulkanInputFormat)
                .toArray();
    }

    private static int vulkanInputFormat(String attribute) {
        return switch (attribute) {
            case "Position" -> VK_FORMAT_R32G32B32_SFLOAT;
            case "Color" -> VK_FORMAT_R8G8B8A8_UNORM;
            case "UV0", "MidTexCoord" -> VK_FORMAT_R32G32_SFLOAT;
            case "UV1", "UV2" -> VK_FORMAT_R16G16_SINT;
            case "EntityIds" -> VK_FORMAT_R16G16B16A16_UINT;
            // the normal is three bytes plus a pad byte; the tangent frame packs four
            case "Normal", "Tangent" -> VK_FORMAT_R8G8B8A8_SNORM;
            default -> throw new IllegalArgumentException("unsupported family attribute " + attribute);
        };
    }

    /** GLYPH's normal is appended, as Iris injects it, because the host writes none. */
    public static boolean appendsNormal(VertexFormat format) {
        return format == EXTENDED_GLYPH;
    }

    /** The layouts Iris extends while the level renders (MixinBufferBuilder); a triangle keeps its normals. */
    public static boolean takesFaceNormal(VertexFormat format, boolean renderingLevel) {
        return renderingLevel && (format == EXTENDED_BLOCK || format == EXTENDED_ENTITY || format == EXTENDED_GLYPH);
    }

    public static VertexFormat handHostFormat(VertexFormat format) {
        for (var entry : handFormats().entrySet()) {
            if (format == entry.getKey() || format == entry.getValue()) return entry.getKey();
        }
        return null;
    }

    private static VertexFormat appendMaterialInputs(VertexFormat host) {
        return appendMaterialInputs(host, false);
    }

    private static VertexFormat appendMaterialInputs(VertexFormat host, boolean computedNormal) {
        VertexFormat.Builder builder = VertexFormat.builder();
        List<VertexFormatElement> elements = host.getElements();
        List<String> names = host.getElementAttributeNames();
        int hostElementBytes = 0;
        for (int index = 0; index < elements.size(); index++) {
            builder.add(names.get(index), elements.get(index));
            hostElementBytes += elements.get(index).byteSize();
        }
        if (host.getVertexSize() > hostElementBytes) {
            builder.padding(host.getVertexSize() - hostElementBytes);
        }
        if (computedNormal) {
            builder.add("Normal", VertexFormatElement.NORMAL);
            builder.padding(1);
        }
        builder.add("EntityIds", ENTITY_IDS);
        builder.padding(6);
        builder.add("MidTexCoord", MID_TEX_COORD);
        builder.padding(4);
        builder.add("Tangent", TANGENT);
        return builder.build();
    }

    private ChimeraVertexFormats() {}
}
