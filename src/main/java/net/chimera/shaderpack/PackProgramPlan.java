// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Load-time plan for one pack program. The probe and pipeline construction read the same instance, so what the
 * report calls executable is what runs. {@code shadowAttachmentFormats} is empty outside the shadow stages.
 */
public record PackProgramPlan(
        PackProgram program,
        Map<String, PreparedShaderSource> stages,
        UniformRegistry.ProgramInterfacePlan interfacePlan,
        Map<String, GlslInterfaceScanner.StageInterface> stageInterfaces,
        Map<String, Integer> varyingLocations,
        PostTargetPlan targetPlan,
        String convertedFragment,
        String convertedVertex,
        LegacyGlslConverter.TerrainVaryingLayout vertexLayout,
        List<String> deviations,
        boolean executable,
        FamilyAdapterPlan familyAdapter,
        TerrainMaterialPlan terrainMaterial,
        GeometryOutputPlan geometryOutputPlan,
        PackAlphaTestPlan alphaTestPlan,
        PackBlendPlan blendPlan,
        List<Integer> shadowAttachmentFormats,
        BetweenStages betweenStages
) {
    public PackProgramPlan {
        stages = immutableStages(stages);
        stageInterfaces = stageInterfaces == null
                ? Map.of()
                : Collections.unmodifiableMap(new TreeMap<>(stageInterfaces));
        varyingLocations = varyingLocations == null
                ? Map.of()
                : Collections.unmodifiableMap(new TreeMap<>(varyingLocations));
        deviations = deviations == null ? List.of() : deviations.stream().distinct().sorted().toList();
        familyAdapter = familyAdapter == null
                ? FamilyAdapterRegistry.forProgram(program == null ? "" : program.name())
                : familyAdapter;
        terrainMaterial = terrainMaterial == null ? TerrainMaterialPlan.legacy() : terrainMaterial;
        alphaTestPlan = alphaTestPlan == null
                ? PackAlphaTestPlan.forProgram(program == null ? "" : program.name(), PackSettingsPlan.empty())
                : alphaTestPlan;
        blendPlan = blendPlan == null ? PackBlendPlan.empty() : blendPlan;
        shadowAttachmentFormats = shadowAttachmentFormats == null ? List.of() : List.copyOf(shadowAttachmentFormats);
        betweenStages = betweenStages == null ? BetweenStages.NONE : betweenStages;
    }

    /** A plan with the default family adapter, terrain material, alpha test and blend for its program. */
    public PackProgramPlan(
            PackProgram program,
            Map<String, PreparedShaderSource> stages,
            UniformRegistry.ProgramInterfacePlan interfacePlan,
            Map<String, GlslInterfaceScanner.StageInterface> stageInterfaces,
            Map<String, Integer> varyingLocations,
            PostTargetPlan targetPlan,
            String convertedFragment,
            String convertedVertex,
            LegacyGlslConverter.TerrainVaryingLayout vertexLayout,
            List<String> deviations,
            boolean executable
    ) {
        this(program, stages, interfacePlan, stageInterfaces, varyingLocations, targetPlan,
                convertedFragment, convertedVertex, vertexLayout, deviations, executable, null, null, null, null, null,
                null, null);
    }

    /** The converted geometry stage, or null. */
    public String convertedGeometry() {
        return betweenStages.geometry();
    }

    /** The converted tessellation control stage, or null; never without its evaluation stage. */
    public String convertedTessControl() {
        return betweenStages.tessControl();
    }

    /** The converted tessellation evaluation stage, or null; never without its control stage. */
    public String convertedTessEvaluation() {
        return betweenStages.tessEvaluation();
    }

    /** Deviation for an authored cloud program that provably draws nothing (AuthoredOutput). */
    public static final String CLOUD_AUTHORED_NO_OUTPUT = "CLOUD_AUTHORED_NO_OUTPUT";

    public String name() {
        return program == null ? "" : program.name();
    }

    public boolean cloudDrawsNothing() {
        return deviations.contains(CLOUD_AUTHORED_NO_OUTPUT);
    }

    public String stageSource(String stage) {
        PreparedShaderSource prepared = stages.get(stage);
        return prepared == null ? null : prepared.source();
    }

    public String executableFragmentSource() {
        return convertedFragment != null
                ? convertedFragment
                : stageSource("fragment");
    }

    public String executableVertexSource() {
        return convertedVertex != null
                ? convertedVertex
                : stageSource("vertex");
    }

    private static Map<String, PreparedShaderSource> immutableStages(
            Map<String, PreparedShaderSource> values
    ) {
        return values == null
                ? Map.of()
                : Collections.unmodifiableMap(new TreeMap<>(values));
    }
}
