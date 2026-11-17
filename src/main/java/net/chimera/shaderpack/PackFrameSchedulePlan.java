package net.chimera.shaderpack;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Load-time frame order of the stages a pack can see.
 *
 * The plan only names the seams. It owns no Vulkan resources and does not know whether a pipeline compiled; the
 * runtime target, depth and family owners act at these seams.
 */
public final class PackFrameSchedulePlan {
    public enum Phase {
        BEGIN,
        SHADOW,
        PREPARE,
        OPAQUE,
        DEPTH_TEX1,
        HAND,
        DEPTH_TEX2,
        EARLY_POST,
        TRANSLUCENT,
        PARTICLE,
        DEPTH_TEX0,
        LATE_POST,
        FINAL,
        GUI,
        PRESENT
    }

    public enum PostWindow {
        BEGIN,
        PREPARE,
        EARLY,
        LATE,
        FINAL
    }

    public record PostStage(String name, PostWindow window, boolean executable,
                             List<Integer> requiredColorInputs, List<Integer> outputTargets) {
        public PostStage {
            name = Objects.requireNonNull(name, "name");
            window = Objects.requireNonNull(window, "window");
            requiredColorInputs = requiredColorInputs == null
                    ? List.of() : requiredColorInputs.stream().distinct().sorted().toList();
            outputTargets = outputTargets == null
                    ? List.of() : outputTargets.stream().distinct().sorted().toList();
        }
    }

    private static final List<Phase> ORDER = List.of(Phase.values());
    private final List<Phase> phases;
    private final List<PostStage> postStages;
    private final boolean depthtex0;
    private final boolean depthtex1;
    private final boolean earlyDepthtex0;
    private final List<String> deviations;

    private PackFrameSchedulePlan(List<PostStage> postStages, boolean depthtex0, boolean depthtex1,
                                  boolean earlyDepthtex0, List<String> deviations) {
        this.postStages = postStages == null ? List.of() : List.copyOf(postStages);
        // the begin and prepare phases exist only with a pass of their window
        this.phases = ORDER.stream()
                .filter(phase -> phase != Phase.BEGIN || hasWindow(PostWindow.BEGIN))
                .filter(phase -> phase != Phase.PREPARE || hasWindow(PostWindow.PREPARE))
                .toList();
        this.depthtex0 = depthtex0;
        this.depthtex1 = depthtex1;
        this.earlyDepthtex0 = earlyDepthtex0;
        this.deviations = deviations == null ? List.of()
                : deviations.stream().filter(value -> value != null && !value.isBlank())
                .distinct().sorted().toList();
    }

    public static PackFrameSchedulePlan empty() {
        return new PackFrameSchedulePlan(List.of(), false, false, false, List.of());
    }

    public static PackFrameSchedulePlan build(List<PackProgramPlan> programs,
                                              PackTargetGraphPlan graph) {
        return build(programs, graph, Map.of());
    }

    /**
     * The schedule, with a stage for each pass of {@code computeDepthReads} that has no program. Iris runs such a
     * pass's computes in its place and draws nothing (CompositeRenderer.ComputeOnlyPass). A deferred pass whose
     * computes read depthtex0 needs it captured, as a deferred program does.
     */
    public static PackFrameSchedulePlan build(List<PackProgramPlan> programs, PackTargetGraphPlan graph,
                                              Map<String, Set<String>> computeDepthReads) {
        List<PostStage> stages = new ArrayList<>();
        List<String> deviations = new ArrayList<>();
        boolean earlyDepth0 = false;
        boolean depth0 = graph != null && graph.depth().depthtex0();
        boolean depth1 = graph != null && graph.depth().depthtex1();
        boolean depth2 = graph != null && graph.depth().depthtex2();

        for (PackProgramPlan program : postPrograms(programs)) {
            List<String> samplers = samplerNames(program);
            PostWindow window = postWindow(program, samplers, deviations);
            if (window == PostWindow.EARLY && samplers.contains("depthtex0")) {
                earlyDepth0 = true;
                deviations.add("SCHEDULE_DEPTH_TEX0_OPAQUE_AT_DEFERRED:" + program.name());
            }
            PostStage stage = scheduledStage(program, window, graph);
            stages.add(stage);
            if (!stage.executable()) {
                deviations.add("SCHEDULE_STAGE_UNAVAILABLE:" + program.name());
            }
        }
        addComputeOnlyStages(stages, computeDepthReads.keySet(), deviations);
        earlyDepth0 |= computesReadDepth0Early(stages, computeDepthReads, deviations);

        if (depth1) deviations.add("SCHEDULE_DEPTH_TEX1_BEFORE_TRANSLUCENT");
        if (depth2) deviations.add("SCHEDULE_DEPTH_TEX2_BEFORE_HAND");
        if (depth0) deviations.add("SCHEDULE_DEPTH_TEX0_BEFORE_COMPOSITE");
        if (stages.stream().anyMatch(stage -> stage.window() == PostWindow.EARLY)) {
            deviations.add("SCHEDULE_EARLY_POST_BEFORE_TRANSLUCENT");
        }
        if (stages.stream().anyMatch(stage -> stage.window() == PostWindow.LATE)) {
            deviations.add("SCHEDULE_LATE_POST_AFTER_WORLD");
        }
        deviations.add("HAND_PHASE_HOST_BOUNDARY");
        return new PackFrameSchedulePlan(stages, depth0, depth1, earlyDepth0, deviations);
    }

    /** The post programs with a target plan, in Iris's pass order. */
    private static List<PackProgramPlan> postPrograms(List<PackProgramPlan> programs) {
        if (programs == null) return List.of();
        return programs.stream()
                .filter(Objects::nonNull)
                .filter(program -> program.targetPlan() != null
                        && PostTargetPlan.isPostProgramName(program.name()))
                .sorted(Comparator.comparing(PackProgramPlan::name,
                        PostTargetPlan.programComparator()))
                .toList();
    }

    private static List<String> samplerNames(PackProgramPlan program) {
        return program.interfacePlan() == null ? List.of()
                : program.interfacePlan().effective(UniformRegistry.Stage.POST)
                .samplers().stream()
                .map(UniformRegistry.SamplerBinding::name).toList();
    }

    /** The window a post pass runs in, recording a pass that must wait for the late depth. */
    private static PostWindow postWindow(PackProgramPlan program, List<String> samplers, List<String> deviations) {
        PostTargetPlan target = program.targetPlan();
        // Iris runs every deferred pass in order between opaque and translucent geometry (beginTranslucents),
        // after beginHand has copied depthtex2, so depthtex0 there is the opaque depth
        // a deferred pass never moves late: Bliss's deferred computes the sky light its deferred1/2 and composites read
        // begin and prepare passes run before the world, so they never move late either
        boolean requiresLateDepth = passWindow(program.name()) == PostWindow.LATE
                && (samplers.contains("depthtex0") || samplers.contains("depthtex2"));
        if (requiresLateDepth && !target.isFinal()) {
            deviations.add("SCHEDULE_POST_AFTER_DEPTH:" + program.name());
        }
        return target.isFinal() ? PostWindow.FINAL : passWindow(program.name());
    }

    /**
     * The window a begin, prepare, deferred or composite pass runs in, by its name.
     *
     * Iris runs the begin passes each frame at the level's start (IrisRenderingPipeline.beginLevelRendering), as
     * Chimera's opening windows do. It runs the prepare passes after the shadow pass, just before the sky
     * (IrisRenderingPipeline.renderShadows), as PreSkyPasses does.
     */
    private static PostWindow passWindow(String pass) {
        if (pass.startsWith("begin")) return PostWindow.BEGIN;
        return pass.startsWith("prepare") ? PostWindow.PREPARE
                : pass.startsWith("deferred") ? PostWindow.EARLY : PostWindow.LATE;
    }

    /** Whether a pass of the deferred window has computes that read depthtex0, recording each one. */
    private static boolean computesReadDepth0Early(List<PostStage> stages, Map<String, Set<String>> computeDepthReads,
                                                   List<String> deviations) {
        boolean early = false;
        for (PostStage stage : stages) {
            if (stage.window() == PostWindow.EARLY
                    && computeDepthReads.getOrDefault(stage.name(), Set.of()).contains("depthtex0")) {
                early = true;
                deviations.add("SCHEDULE_DEPTH_TEX0_OPAQUE_AT_DEFERRED:" + stage.name());
            }
        }
        return early;
    }

    /** Adds a stage for each compute pass no program scheduled, in its name's window, then sorts into pass order. */
    private static void addComputeOnlyStages(List<PostStage> stages, Collection<String> computePasses,
                                             List<String> deviations) {
        for (String pass : computePasses) {
            if (stages.stream().anyMatch(stage -> stage.name().equals(pass))) continue;
            stages.add(new PostStage(pass, passWindow(pass), true, List.of(), List.of()));
            deviations.add("SCHEDULE_COMPUTE_ONLY_PASS:" + pass);
        }
        stages.sort(Comparator.comparing(PostStage::name, PostTargetPlan.programComparator()));
    }

    /** A pass's stage in the schedule: the targets its graph step reads and writes, and whether it runs. */
    private static PostStage scheduledStage(PackProgramPlan program, PostWindow window, PackTargetGraphPlan graph) {
        PostTargetPlan target = program.targetPlan();
        TargetStep step = graph == null ? null : graph.step(program.name());
        List<Integer> inputs = step == null ? List.of() : step.readTargets();
        List<Integer> outputs = step == null
                ? target.targetSlots() : step.outputTargets();
        boolean executable = program.executable() && target.executable()
                && (step == null || step.executable());
        return new PostStage(program.name(), window, executable, inputs, outputs);
    }

    public List<Phase> phases() { return phases; }
    public List<PostStage> postStages() { return postStages; }
    public boolean requiresDepthtex0() { return depthtex0; }
    public boolean requiresDepthtex1() { return depthtex1; }
    /** A deferred pass or its computes read depthtex0, the opaque depth at that seam. */
    public boolean earlyReadsDepthtex0() { return earlyDepthtex0; }
    public List<String> deviations() { return deviations; }

    public List<PostStage> stages(PostWindow window) {
        return postStages.stream().filter(stage -> stage.window() == window).toList();
    }

    public boolean hasWindow(PostWindow window) {
        for (PostStage stage : postStages) {
            if (stage.window() == window) return true;
        }
        return false;
    }

    public PostStage postStage(String name) {
        for (PostStage stage : postStages) {
            if (stage.name().equals(name)) return stage;
        }
        return null;
    }

    public int phaseIndex(Phase phase) {
        return phases.indexOf(phase);
    }

    public String snapshot() {
        return "phases=" + phases + ";posts=" + postStages
                + ";depth=" + List.of(depthtex0, depthtex1)
                + ";earlyDepth0=" + earlyDepthtex0
                + ";deviations=" + deviations;
    }

    public String fingerprint() {
        return ConformanceReport.sha256(snapshot().getBytes(StandardCharsets.UTF_8));
    }
}
