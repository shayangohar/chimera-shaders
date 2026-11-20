// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static net.chimera.shaderpack.GlslLexer.stripComments;

/** Converts programs drawn on the host's own vertex layouts, as Iris draws them natively. */
final class HostLayoutVertexBridge {
    private HostLayoutVertexBridge() {}

    /**
     * Converts the shadow program for a shadow-pass key Iris draws on the host's own layout, throwing its first
     * concrete failure. It is the shadow_entities bridge on that layout's inputs; {@code consumerSource} is the
     * fragment, or a geometry stage when {@code geometryNext}.
     */
    public static LegacyGlslConverter.TerrainVertexConversion convertShadowLaneVertexChecked(
            FamilyAdapterPlan.VertexContract contract,
            String source,
            String consumerSource,
            UniformRegistry.ProgramInterface interfacePlan,
            boolean allowStorageBuffers,
            boolean geometryNext
    ) {
        return TerrainVertexBridge.convertModernTerrainVertexOrThrow(source, null, consumerSource,
                GlslVertexPreambles.shadowLanePreamble(contract), interfacePlan, true, true, allowStorageBuffers,
                geometryNext);
    }

    /** The one host sky format that provides the legacy inputs the source reads. */
    public static FamilyAdapterPlan.VertexContract skyVertexContract(
            String source, boolean textured
    ) {
        String stripped = stripComments(source == null ? "" : source);
        boolean color = GlslTokenRewriter.containsIdentifier(stripped, "gl_Color");
        if (textured) {
            return color ? FamilyAdapterPlan.VertexContract.SKY_POSITION_COLOR_UV
                    : FamilyAdapterPlan.VertexContract.SKY_POSITION_UV;
        }
        return color ? FamilyAdapterPlan.VertexContract.SKY_POSITION_COLOR
                : FamilyAdapterPlan.VertexContract.SKY_POSITION;
    }

    /** Converts a sky vertex onto the sky format its inputs need; null when it cannot convert. */
    public static LegacyGlslConverter.TerrainVertexConversion convertSkyVertex(
            String source, Path sourceFile, String fragmentSource, boolean textured
    ) {
        return convertSkyVertex(source, sourceFile, fragmentSource,
                skyVertexContract(source, textured));
    }

    /** Converts a sky vertex onto {@code contract}'s format; null when it cannot convert. */
    public static LegacyGlslConverter.TerrainVertexConversion convertSkyVertex(
            String source, Path sourceFile, String fragmentSource,
            FamilyAdapterPlan.VertexContract contract
    ) {
        return convertSkyVertex(source, sourceFile, fragmentSource, contract, Map.of(), false, List.of());
    }

    public static LegacyGlslConverter.TerrainVertexConversion convertSkyVertex(
            String source, Path sourceFile, String fragmentSource,
            FamilyAdapterPlan.VertexContract contract, Map<String, Integer> locations,
            boolean allowStorageBuffers, List<UniformRegistry.UniformDeclaration> uniforms
    ) {
        try {
            return convertSkyVertexChecked(source, sourceFile, fragmentSource, contract, locations,
                    allowStorageBuffers, uniforms, null);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /**
     * Converts a sky vertex onto one host sky format, throwing its first concrete failure; {@code samplers} is
     * null without a plan.
     */
    public static LegacyGlslConverter.TerrainVertexConversion convertSkyVertexChecked(
            String source, Path sourceFile, String fragmentSource,
            FamilyAdapterPlan.VertexContract contract, Map<String, Integer> locations,
            boolean allowStorageBuffers, List<UniformRegistry.UniformDeclaration> uniforms,
            EntityVertexBridge.VertexSamplerPlan samplers
    ) {
        return EntityVertexBridge.convertEntityVertexOrThrow(unboundMaterialAttributes(source), sourceFile,
                fragmentSource, locations, GlslVertexPreambles.skyPreamble(contract), allowStorageBuffers,
                uniforms, samplers);
    }

    /** {@code converted} with its sky preamble swapped for {@code contract}'s, so one program runs on each sky mesh. */
    public static String skyVertexForContract(String converted, FamilyAdapterPlan.VertexContract contract) {
        for (var source : SKY_CONTRACTS) {
            String preamble = GlslVertexPreambles.skyPreamble(source);
            if (converted != null && converted.contains(preamble)) {
                return converted.replace(preamble, GlslVertexPreambles.skyPreamble(contract));
            }
        }
        throw new IllegalArgumentException("sky program lacks its native input contract");
    }

    public static LegacyGlslConverter.TerrainVertexConversion convertCloudVertex(
            String source, Path sourceFile, String fragmentSource
    ) {
        return convertCloudVertex(source, sourceFile, fragmentSource, Map.of(), false, List.of());
    }

    /** Converts a cloud vertex onto vanilla's cloud mesh with Iris's cloud inputs; null when it cannot convert. */
    public static LegacyGlslConverter.TerrainVertexConversion convertCloudVertex(
            String source, Path sourceFile, String fragmentSource, Map<String, Integer> locations,
            boolean allowStorageBuffers, List<UniformRegistry.UniformDeclaration> uniforms
    ) {
        try {
            return convertCloudVertexChecked(source, sourceFile, fragmentSource, locations, allowStorageBuffers,
                    uniforms, null);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /**
     * {@link #convertCloudVertex}, throwing its first concrete failure; {@code samplers} binds the vertex
     * stage's samplers as {@link #convertSkyVertexChecked} does.
     */
    public static LegacyGlslConverter.TerrainVertexConversion convertCloudVertexChecked(
            String source, Path sourceFile, String fragmentSource, Map<String, Integer> locations,
            boolean allowStorageBuffers, List<UniformRegistry.UniformDeclaration> uniforms,
            EntityVertexBridge.VertexSamplerPlan samplers
    ) {
        return EntityVertexBridge.convertEntityVertexOrThrow(unboundMaterialAttributes(source), sourceFile,
                fragmentSource, locations, GlslVertexPreambles.CLOUD_VERTEX_PREAMBLE, allowStorageBuffers,
                uniforms, samplers);
    }

    /**
     * Converts a host-layout vertex onto its layout, after IrisCoreProfile's prepareLineVertex,
     * prepareGlintVertex or prepareHostLayoutSource, and prepareHostLayoutIdentity.
     */
    public static LegacyGlslConverter.TerrainVertexConversion convertHostLayoutVertexChecked(
            FamilyAdapterPlan.VertexContract contract, String source, String fragmentSource,
            Map<String, Integer> sharedLocations, boolean allowStorageBuffers,
            List<UniformRegistry.UniformDeclaration> uniforms,
            EntityVertexBridge.VertexSamplerPlan samplerPlan
    ) {
        String preamble = switch (contract) {
            case HOST_LINE -> GlslVertexPreambles.LINE_VERTEX_PREAMBLE;
            case HOST_GLINT -> GlslVertexPreambles.GLINT_VERTEX_PREAMBLE;
            case HOST_LIGHTNING -> GlslVertexPreambles.LIGHTNING_VERTEX_PREAMBLE;
            case HOST_BEACON -> GlslVertexPreambles.BEACON_VERTEX_PREAMBLE;
            case HOST_LEASH, HOST_TEXT_BACKGROUND -> GlslVertexPreambles.LEASH_VERTEX_PREAMBLE;
            case HOST_POSITION_TEX -> GlslVertexPreambles.TEXTURED_VERTEX_PREAMBLE;
            case HOST_POSITION_TEX_COLOR -> GlslVertexPreambles.TEXTURED_COLOR_VERTEX_PREAMBLE;
            default -> throw new IllegalArgumentException("not a host-layout contract: " + contract);
        };
        if (contract != FamilyAdapterPlan.VertexContract.HOST_BEACON) {
            return EntityVertexBridge.convertEntityVertexOrThrow(unboundMaterialAttributes(source), null,
                    fragmentSource, sharedLocations, preamble, allowStorageBuffers, uniforms, samplerPlan);
        }
        // the beam's widened layout carries the material attributes, and its mc_Entity is a moving block's
        var beam = EntityVertexBridge.convertEntityVertexOrThrow(source, null, fragmentSource,
                sharedLocations, preamble, allowStorageBuffers, uniforms, samplerPlan);
        return new LegacyGlslConverter.TerrainVertexConversion(EntityVertexBridge.movingBlockEntity(beam.source()),
                beam.layout());
    }

    private static final Set<String> MATERIAL_ATTRIBUTES = Set.of("mc_midTexCoord", "at_tangent");

    /**
     * {@code source} with mc_midTexCoord and at_tangent read as Iris reads them on a host layout without them:
     * GL's unbound default cut to the declared type. A parameter or local of the same name keeps its own.
     */
    static String unboundMaterialAttributes(String source) {
        GlslTree tree = GlslTree.parse(source);
        Map<String, String> defaults = new HashMap<>();
        List<GlslDeclarations.TextEdit> removals = new ArrayList<>();
        for (GlslDeclarations.Variable variable : GlslDeclarations.variables(tree)) {
            String unbound = UnboundAttributes.defaultValue(variable.type());
            if (!MATERIAL_ATTRIBUTES.contains(variable.name()) || unbound == null || !variable.plain()
                    || !variable.qualified("attribute") && !variable.qualified("in")) {
                continue;
            }
            defaults.put(variable.name(), unbound);
            removals.add(GlslDeclarations.remove(tree, variable.declaration()));
        }
        if (defaults.isEmpty()) return source;
        return GlslScopedReferences.replace(GlslDeclarations.apply(source, removals), defaults);
    }

    public static final List<FamilyAdapterPlan.VertexContract> SKY_CONTRACTS = List.of(
            FamilyAdapterPlan.VertexContract.SKY_POSITION,
            FamilyAdapterPlan.VertexContract.SKY_POSITION_COLOR,
            FamilyAdapterPlan.VertexContract.SKY_POSITION_UV,
            FamilyAdapterPlan.VertexContract.SKY_POSITION_COLOR_UV);
}
