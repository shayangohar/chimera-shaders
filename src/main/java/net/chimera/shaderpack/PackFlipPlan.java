// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Iris's BufferFlipper, run once at load so every pass's flipped set is fixed before the first frame. A target in
 * a state is read from its alt side, and a post pass writes its draw buffers to the other side. A group's
 * {@code <group>_pre} entries flip as it opens and do not count as written; after each pass its unheld draw
 * buffers flip, then its {@code true} entries, so a true entry on a draw buffer cancels its flip.
 */
public final class PackFlipPlan {
    /** The pass groups in Iris's build order, by the window each runs in. */
    private static final List<PackFrameSchedulePlan.PostWindow> GROUPS = List.of(
            PackFrameSchedulePlan.PostWindow.BEGIN, PackFrameSchedulePlan.PostWindow.PREPARE,
            PackFrameSchedulePlan.PostWindow.EARLY, PackFrameSchedulePlan.PostWindow.LATE);

    /**
     * One pass. {@code marks} are the targets it marks flipped at least once; later passes of its group then
     * ignore those targets' stage texture overrides.
     */
    public record Step(String program, PackFrameSchedulePlan.PostWindow window, int before, int after,
                       List<Integer> drawBuffers, int marks) {
        public Step {
            drawBuffers = List.copyOf(drawBuffers);
        }

        public boolean readsAlt(int target) {
            return flipped(before, target);
        }

        /** The side this pass writes {@code target} to as a draw buffer, the one it does not read: 0 main, 1 alt. */
        public int writeSide(int target) {
            return readsAlt(target) ? 0 : 1;
        }
    }

    private final Map<PackFrameSchedulePlan.PostWindow, Integer> open;
    private final Map<PackFrameSchedulePlan.PostWindow, Integer> close;
    private final Map<PackFrameSchedulePlan.PostWindow, Integer> writtenInGroup;
    private final List<Step> steps;
    private final int sided;

    private PackFlipPlan(Map<PackFrameSchedulePlan.PostWindow, Integer> open,
                         Map<PackFrameSchedulePlan.PostWindow, Integer> close,
                         Map<PackFrameSchedulePlan.PostWindow, Integer> writtenInGroup, List<Step> steps) {
        this.open = Map.copyOf(open);
        this.close = Map.copyOf(close);
        this.writtenInGroup = Map.copyOf(writtenInGroup);
        this.steps = List.copyOf(steps);
        int mask = 0;
        for (int state : open.values()) mask |= state;
        for (Step step : steps) {
            mask |= step.before() | step.after();
            for (int target : step.drawBuffers()) mask |= bit(target);
        }
        this.sided = mask;
    }

    /** A plan in which nothing flips. */
    public static PackFlipPlan empty() {
        return of(List.of(), null);
    }

    /** The plan for each post program with a target plan, in Iris's order; final flips nothing. */
    public static PackFlipPlan of(List<PackProgramPlan> programs, PackConfig.PackConfigData config) {
        Map<String, List<Integer>> trues = config == null ? Map.of() : config.flips();
        Map<String, List<Integer>> cancels = config == null ? Map.of() : config.cancelledFlips();
        Map<String, List<Integer>> pre = config == null ? Map.of() : config.preFlips();
        List<PackProgramPlan> passes = programs == null ? List.of() : programs.stream()
                .filter(program -> program != null && program.targetPlan() != null
                        && !program.targetPlan().isFinal() && group(program.name()) != null)
                .sorted(Comparator.comparing(PackProgramPlan::name, PostTargetPlan.programComparator()))
                .toList();
        Map<PackFrameSchedulePlan.PostWindow, Integer> open = new EnumMap<>(PackFrameSchedulePlan.PostWindow.class);
        Map<PackFrameSchedulePlan.PostWindow, Integer> close = new EnumMap<>(PackFrameSchedulePlan.PostWindow.class);
        Map<PackFrameSchedulePlan.PostWindow, Integer> written = new EnumMap<>(PackFrameSchedulePlan.PostWindow.class);
        List<Step> steps = new ArrayList<>();
        int state = 0;
        String previous = null;
        for (PackFrameSchedulePlan.PostWindow window : GROUPS) {
            for (int target : pre.getOrDefault(preKey(window), List.of())) state = flip(state, target);
            open.put(window, state);
            int writtenSoFar = 0;
            for (PackProgramPlan program : passes) {
                if (group(program.name()) != window || program.name().equals(previous)) continue;
                previous = program.name();
                Step step = pass(program, window, state, trues.getOrDefault(program.name(), List.of()),
                        cancels.getOrDefault(program.name(), List.of()));
                state = step.after();
                writtenSoFar |= step.marks();
                steps.add(step);
            }
            close.put(window, state);
            written.put(window, writtenSoFar);
        }
        return new PackFlipPlan(open, close, written, steps);
    }

    /** One pass, as CompositeRenderer runs it: each unheld draw buffer flips, then each true entry; all are marked. */
    private static Step pass(PackProgramPlan program, PackFrameSchedulePlan.PostWindow window, int before,
                             List<Integer> trues, List<Integer> held) {
        List<Integer> drawBuffers = drawBuffers(program.targetPlan());
        int state = before;
        int marks = 0;
        for (int target : drawBuffers) {
            if (held.contains(target)) continue;
            state = flip(state, target);
            marks |= bit(target);
        }
        for (int target : trues) {
            state = flip(state, target);
            marks |= bit(target);
        }
        return new Step(program.name(), window, before, state, drawBuffers, marks);
    }

    /** A post pass's draw buffers in range: its directive's, or colortex0 without one, as Iris defaults them. */
    private static List<Integer> drawBuffers(PostTargetPlan plan) {
        List<Integer> slots = plan.targetSlots().isEmpty() ? List.of(0) : plan.targetSlots();
        return slots.stream().filter(PackFlipPlan::inRange).toList();
    }

    /** The pass group a program belongs to by name, or null for final and any non-post program. */
    static PackFrameSchedulePlan.PostWindow group(String program) {
        if (program == null) return null;
        if (program.matches("begin\\d*")) return PackFrameSchedulePlan.PostWindow.BEGIN;
        if (program.matches("prepare\\d*")) return PackFrameSchedulePlan.PostWindow.PREPARE;
        if (program.matches("deferred\\d*")) return PackFrameSchedulePlan.PostWindow.EARLY;
        if (program.matches("composite\\d*")) return PackFrameSchedulePlan.PostWindow.LATE;
        return null;
    }

    private static String preKey(PackFrameSchedulePlan.PostWindow window) {
        return switch (window) {
            case BEGIN -> "begin_pre";
            case PREPARE -> "prepare_pre";
            case EARLY -> "deferred_pre";
            case LATE -> "composite_pre";
            case FINAL -> "final_pre";
        };
    }

    private static boolean inRange(int target) {
        return target >= 0 && target <= PostTargetPlan.MAX_TARGET;
    }

    private static int bit(int target) {
        return inRange(target) ? 1 << target : 0;
    }

    private static int flip(int state, int target) {
        return state ^ bit(target);
    }

    /** Whether {@code state} reads {@code target} from its alt side. */
    public static boolean flipped(int state, int target) {
        return (state & bit(target)) != 0;
    }

    /** The state as {@code window}'s group opens, after its pre-flips; FINAL opens on the final state. */
    public int open(PackFrameSchedulePlan.PostWindow window) {
        return window == PackFrameSchedulePlan.PostWindow.FINAL ? finalState() : open.getOrDefault(window, 0);
    }

    /** The state after {@code window}'s group: BEGIN's is the shadow pass's, PREPARE's the opaque world's. */
    public int close(PackFrameSchedulePlan.PostWindow window) {
        return window == PackFrameSchedulePlan.PostWindow.FINAL ? finalState() : close.getOrDefault(window, 0);
    }

    /** Whether {@code window}'s pre-flips change the state; Iris builds the group even with no passes. */
    public boolean preFlips(PackFrameSchedulePlan.PostWindow window) {
        int index = GROUPS.indexOf(window);
        if (index < 0) return false;
        int before = index == 0 ? 0 : close(GROUPS.get(index - 1));
        return open(window) != before;
    }

    /** What opaque gbuffers, the sky and the solid hand read and write: Iris's flippedAfterPrepare. */
    public int opaqueWorld() {
        return close(PackFrameSchedulePlan.PostWindow.PREPARE);
    }

    /** What translucent gbuffers and the translucent hand read and write: Iris's flippedAfterTranslucent. */
    public int translucentWorld() {
        return close(PackFrameSchedulePlan.PostWindow.EARLY);
    }

    /** What final reads, and the targets the frame ends flipped. */
    public int finalState() {
        return close.getOrDefault(PackFrameSchedulePlan.PostWindow.LATE, 0);
    }

    /** The targets that need an alt side: flipped in some state, or written by a pass. */
    public int sided() {
        return sided;
    }

    public boolean sided(int target) {
        return flipped(sided, target);
    }

    public List<Step> steps() {
        return steps;
    }

    public Step step(String program) {
        for (Step step : steps) if (step.program().equals(program)) return step;
        return null;
    }

    /**
     * Iris's flippedAtLeastOnce snapshot before {@code pass}. Final reads the composite group's whole set, and a
     * compute-only pass, which is no step, takes its slot's snapshot.
     */
    public Set<Integer> writtenBefore(String pass) {
        if ("final".equals(pass)) return targets(writtenInGroup.getOrDefault(PackFrameSchedulePlan.PostWindow.LATE, 0));
        PackFrameSchedulePlan.PostWindow window = group(pass);
        if (window == null) return Set.of();
        Comparator<String> order = PostTargetPlan.programComparator();
        int mask = 0;
        for (Step step : steps) {
            if (step.window() == window && order.compare(step.program(), pass) < 0) mask |= step.marks();
        }
        return targets(mask);
    }

    /** The targets in {@code mask}, ascending: snapshots and fingerprints must not depend on hash order. */
    private static Set<Integer> targets(int mask) {
        TreeSet<Integer> result = new TreeSet<>();
        for (int target = 0; target <= PostTargetPlan.MAX_TARGET; target++) {
            if (flipped(mask, target)) result.add(target);
        }
        return Collections.unmodifiableSortedSet(result);
    }

    /** Each group's open and close states and its steps' transitions, for tests and the graph snapshot. */
    public String snapshot() {
        StringBuilder result = new StringBuilder();
        for (PackFrameSchedulePlan.PostWindow window : GROUPS) {
            result.append(window).append(" open=").append(targets(open(window)));
            for (Step step : steps) {
                if (step.window() != window) continue;
                result.append(' ').append(step.program()).append(targets(step.before()))
                        .append("->").append(targets(step.after()));
            }
            result.append(" close=").append(targets(close(window))).append(';');
        }
        return result.toString();
    }
}
