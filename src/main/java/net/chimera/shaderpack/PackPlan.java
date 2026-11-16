package net.chimera.shaderpack;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** The load-time plan for the active pack variant. */
public record PackPlan(
        PackConfig.PackConfigData config,
        List<PackProgramPlan> programs,
        PackEntityIdResolver entityIds,
        PackSettingsPlan settings,
        PackResolutionPlan resolution,
        PackResourcePlan resources,
        TerrainMaterialPlan terrainMaterial,
        PackAdvancedResourcePlan advancedResources
) {
    public PackPlan(PackConfig.PackConfigData config, List<PackProgramPlan> programs) {
        this(config, programs, PackEntityIdResolver.empty(),
                PackSettingsPlan.empty(), PackResolutionPlan.empty(), PackResourcePlan.empty(), null,
                PackAdvancedResourcePlan.empty());
    }

    public PackPlan(
            PackConfig.PackConfigData config,
            List<PackProgramPlan> programs,
            PackEntityIdResolver entityIds,
            PackSettingsPlan settings,
            PackResolutionPlan resolution,
            PackResourcePlan resources,
            PackAdvancedResourcePlan advancedResources
    ) {
        this(config, programs, entityIds, settings, resolution, resources, null, advancedResources);
    }

    public PackPlan {
        programs = programs == null ? List.of() : programs.stream()
                .filter(value -> value != null)
                .sorted(Comparator.comparing(PackProgramPlan::name))
                .toList();
        entityIds = entityIds == null ? PackEntityIdResolver.empty() : entityIds;
        settings = settings == null ? PackSettingsPlan.empty() : settings;
        resolution = resolution == null ? PackResolutionPlan.empty() : resolution;
        resources = resources == null ? PackResourcePlan.empty() : resources;
        terrainMaterial = terrainMaterial == null ? deriveTerrainMaterial(programs) : terrainMaterial;
        advancedResources = advancedResources == null
                ? PackAdvancedResourcePlan.empty() : advancedResources;
    }

    public Map<String, PackProgramPlan> byName() {
        Map<String, PackProgramPlan> result = new TreeMap<>();
        for (PackProgramPlan program : programs) {
            result.putIfAbsent(program.name(), program);
        }
        return Collections.unmodifiableMap(result);
    }

    public PackProgramPlan program(String name) {
        for (PackProgramPlan program : programs) {
            if (program.name().equals(name)) {
                return program;
            }
        }
        return null;
    }

    /** Whether to build {@code name}'s pipeline; the probe and the runtime both ask here, so they agree. */
    public boolean shouldAttempt(String name) {
        PackProgramPlan program = program(name);
        return program != null && program.executable() && resolution.shouldAttempt(name)
                && resources.programAllowed(name)
                && !writesUnsupportedFormat(program)
                && !advancedResources.declaresUnplannedResource(name)
                && advancedResources.imagesServable(name)
                && (!advancedResources.bufferDependentPrograms().contains(name)
                || advancedResources.storageBufferProgramSupported(name))
                && (!advancedResources.customImageFeaturePrograms().contains(name)
                || advancedResources.capabilityPossible());
    }

    /**
     * Whether the program writes a colortex target whose declared format has no Vulkan image here, as the report
     * reads it; the target keeps the default format, which the program's output does not fit. Shadow-pass
     * programs and shadowcomp passes write shadowcolor targets, which a colortex format never concerns.
     */
    private boolean writesUnsupportedFormat(PackProgramPlan program) {
        PostTargetPlan targets = program.targetPlan() != null ? program.targetPlan()
                : program.geometryOutputPlan() != null ? program.geometryOutputPlan().targetPlan() : null;
        if (targets == null || config == null || targets.shadowTargets()) return false;
        return config.deviations().stream()
                .filter(deviation -> deviation.startsWith("POST_TARGET_FORMAT_UNSUPPORTED:"))
                .map(PackProbe::targetIndex)
                .anyMatch(target -> target != null && targets.targetSlots().contains(target));
    }

    public String selectedDimension() {
        return resolution.selectedDimension();
    }

    public String selectedSourceFolder() {
        return resolution.selectedSourceFolder();
    }

    public int aliasCount() {
        return resolution.aliases().size();
    }

    public int disabledProgramCount() {
        return resolution.disabledPrograms().size();
    }

    public int missingProgramCount() {
        return resolution.missingPrograms().size();
    }

    public String settingsFingerprint() {
        return settings.fingerprint();
    }

    public String resolutionFingerprint() {
        return resolution.fingerprint();
    }

    /** Smoothing and custom scalar settings for the pack session. */
    public PackRuntimeSettings runtimeSettings() {
        return settings.runtimeSettings();
    }

    /** The settings plan's preprocessor defines, for sources read after the plan is built: pass computes, id maps. */
    public Map<String, String> settingsPreprocessorDefines() {
        return settings.preprocessorDefines();
    }

    /** The {@code iris.features.required} flags Chimera cannot provide; Iris refuses such a pack. */
    public Set<String> unsupportedRequiredFeatures() {
        return settings.unsupportedRequiredFeatures();
    }

    /** Option names a {@code chimera.option.*} JVM property sets, whose values override the source defaults. */
    public Set<String> settingsOverriddenNames() {
        return settings.overriddenNames();
    }

    /** The defines a prepared source keeps as {@code #define} lines (PackSettingsPlan.lockedDefineNames). */
    public Set<String> settingsLockedDefineNames() {
        return settings.lockedDefineNames();
    }

    /**
     * Whether the pack switches a program or pass off. A pass of computes alone has no resolved program, so its
     * switch is read directly; Iris's ShaderPack then reads none of its files and the pass runs nothing. Shrimple
     * switches off its compute-only composite1 this way.
     */
    public boolean isProgramDisabled(String name) {
        PackProgramResolution value = resolution.resolution(name);
        if (value != null) return !value.enabled();
        return !settings.enabled(selectedSourceFolder(), name);
    }

    /**
     * Whether {@code name} runs as a cloud program whose selected branch draws nothing. The runtime then builds no
     * pipeline and skips the host cloud draw, as Iris's program draws no fragment. A disabled, aliased or
     * resource-refused program falls back instead.
     */
    public boolean cloudRunsEmpty(String name) {
        PackProgramPlan program = program(name);
        return program != null && program.cloudDrawsNothing() && !isProgramDisabled(name)
                && !isProgramAlias(name) && resources().programAllowed(name);
    }

    /**
     * Iris's {@code clouds} directive: "off", "fast" or "fancy", or null when the pack leaves the player's setting
     * alone; Iris logs and ignores other values. "false" also means "off". Iris and OptiFine ignore it, but RedHat
     * writes it to turn the vanilla clouds off, as it draws its own.
     */
    public String cloudSetting() {
        String value = settings.propertyValues().get("clouds");
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.equals("false")) return "off";
        return trimmed.equals("off") || trimmed.equals("fast") || trimmed.equals("fancy") ? trimmed : null;
    }

    /** Which parts of Minecraft's sky the pack keeps (Iris sun, moon, stars and sky, all default on). */
    public SkyDirectives skyDirectives() {
        return SkyDirectives.fromProperties(settings.propertyValues());
    }

    /** Whether the pack's rain and snow write depth (Iris rain.depth, default off). */
    public WeatherDirectives weatherDirectives() {
        return WeatherDirectives.fromProperties(settings.propertyValues());
    }

    /** Which entities and block entities the pack lets cast shadows (Iris shadowEntities and related keys). */
    public ShadowCasterDirectives shadowCasters() {
        return ShadowCasterDirectives.fromProperties(settings.propertyValues());
    }

    /** How terrain vertex colours carry block lighting (Iris oldLighting, separateAo, ambientOcclusionLevel). */
    public TerrainShadingDirectives terrainShading() {
        return TerrainShadingDirectives.fromProperties(settings.propertyValues(),
                (config == null ? PackDirectives.DEFAULTS : config.directives()).ambientOcclusionLevel());
    }

    /** Whether heldBlockLightValue takes the brighter hand (Iris oldHandLight, default on). */
    public HandLightDirectives handLight() {
        return HandLightDirectives.fromProperties(settings.propertyValues());
    }

    /** Which of Minecraft's screen overlays the pack keeps (Iris vignette and underwaterOverlay, default off). */
    public ScreenOverlayDirectives screenOverlays() {
        return ScreenOverlayDirectives.fromProperties(settings.propertyValues());
    }

    /** Whether the camera culls what lies outside its view frustum (Iris frustum.culling, default on). */
    public CullingDirectives cullingDirectives() {
        return CullingDirectives.fromProperties(settings.propertyValues());
    }

    /** Where particles draw against the deferred passes (Iris particles.ordering and its older keys). */
    public ParticleDirectives particleDirectives() {
        return settings.particleDirectives();
    }

    /**
     * Whether the pack converts to the player's colour space itself (supportsColorCorrection). It reads
     * currentColorSpace, and Iris's converter leaves its image alone.
     */
    public boolean supportsColorCorrection() {
        return settings.supportsColorCorrection();
    }

    /**
     * How the shadow pass culls its casters, from Iris's shadow.culling, the shadow distances and whether the
     * shadow program has a geometry stage.
     */
    public ShadowCullingPlan shadowCulling() {
        PackConfig.ShadowSettings shadow = config != null ? config.shadowSettings()
                : new PackConfig.ShadowSettings(PackConfig.DEFAULT_SHADOW_MAP_RESOLUTION,
                        PackConfig.DEFAULT_SHADOW_DISTANCE, Map.of(), List.of());
        PackProgramPlan program = program("shadow");
        return ShadowCullingPlan.of(settings.propertyValues(), shadow,
                program != null && program.stages().containsKey("geometry"));
    }

    /**
     * The programs that take part in the frame, which the schedule and target graph are built from. Iris removes a
     * program the pack switches off ({@code program.<name>.enabled}) from the pipeline, so it neither runs nor
     * counts as a producer.
     */
    public List<PackProgramPlan> activePrograms() {
        return programs().stream().filter(program -> !isProgramDisabled(program.name())).toList();
    }

    public boolean isProgramAlias(String name) {
        PackProgramResolution value = resolution.resolution(name);
        return value != null && !value.requestedProgram().equals(value.selectedProgram());
    }

    public PackResourcePlan resources() {
        return resources;
    }

    /**
     * The shadowcolor targets the shadow pass's programs sample, by index: each executable shadow program's
     * sampler that reads the target itself rather than a pack texture in its place.
     * ChimeraShadowMap.snapshotPassReads copies them as the pass opens.
     */
    public Set<Integer> shadowPassColorReads() {
        Set<Integer> reads = new TreeSet<>();
        for (PackProgramPlan program : programs) {
            if (!program.executable() || program.interfacePlan() == null
                    || !FamilyAdapterRegistry.isShadowFamily(program.name())) {
                continue;
            }
            List<String> unread = program.interfacePlan().deviations();
            Set<String> sampled = new HashSet<>();
            program.interfacePlan().samplers().stream().map(UniformRegistry.SamplerBinding::name)
                    .filter(name -> !unread.contains("SAMPLER_DECLARATION_UNUSED:" + name)).forEach(sampled::add);
            for (PackResourceBinding binding : resources.bindings(program.name())) {
                int index = PackResourcePlan.shadowColorIndex(binding.resourceKey());
                if (binding.kind() == PackResourceKind.SHADOW_COLOR && index >= 0
                        && sampled.contains(binding.sampler())) {
                    reads.add(index);
                }
            }
        }
        return Collections.unmodifiableSet(reads);
    }

    public PackAdvancedResourcePlan advancedResources() {
        return advancedResources;
    }

    /** The one terrain layout selected for every chunk buffer in this session. */
    public TerrainMaterialPlan terrainMaterial() {
        return terrainMaterial;
    }

    private static TerrainMaterialPlan deriveTerrainMaterial(List<PackProgramPlan> values) {
        TerrainMaterialPlan result = TerrainMaterialPlan.legacy();
        if (values != null) {
            for (PackProgramPlan value : values) {
                if (value != null) {
                    result = result.merge(value.terrainMaterial());
                }
            }
        }
        return result;
    }
}
