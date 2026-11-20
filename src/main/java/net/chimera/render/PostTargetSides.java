// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import net.chimera.shaderpack.PackFlipPlan;
import net.chimera.shaderpack.PackFrameSchedulePlan;
import net.chimera.shaderpack.PostTargetPlan;

import java.util.Comparator;
import java.util.List;

/**
 * Which image holds each colour target's main (0) and alt (1) side through a frame, following the load-time
 * flip plan; as in Iris, every frame starts unflipped. It makes no Vulkan calls. colortex0 has a third image,
 * the HDR image the world draws into, which stands in for the side the world's flip state names and is written
 * back before a pass needs that side.
 */
final class PostTargetSides {
    /** An image: a target's graph side, or the HDR image (target 0, side {@link #HDR}). */
    record Image(int target, int side) {
        boolean hdr() {
            return side == HDR;
        }
    }

    /** The copies and clears a side change needs, recorded in order. */
    interface Ops {
        void copy(Image from, Image to);

        void clear(Image image);
    }

    static final int HDR = 2;
    private static final int TARGET_COUNT = PostTargetPlan.MAX_TARGET + 1;

    private final PackFlipPlan plan;
    private final int allocated;
    private final int cleared;
    private final List<PackFlipPlan.Step> steps;
    /** Currently flipped targets, as a mask; a flipped target is read from alt. */
    private int state;
    /** Index of the next plan step to run or pass through. */
    private int cursor;
    /** The colortex0 side the HDR image stands in for, or -1 when it holds neither. */
    private int hdrSide = -1;
    /** Whether the HDR image may hold content its side's graph image lacks. */
    private boolean hdrNewer;
    private boolean fullClearDue = true;

    /**
     * @param allocated the targets the session made images for, as a mask
     * @param cleared   the targets cleared every frame (colortexNClear, default true), as a mask
     */
    PostTargetSides(PackFlipPlan plan, int allocated, int cleared) {
        this.plan = plan == null ? PackFlipPlan.empty() : plan;
        this.allocated = allocated;
        this.cleared = cleared;
        this.steps = this.plan.steps();
    }

    /** Whether a target has an alt image: the plan flips or writes it. */
    boolean sided(int target) {
        return allocated(target) && plan.sided(target);
    }

    boolean allocated(int target) {
        return target >= 0 && target < TARGET_COUNT && (allocated & (1 << target)) != 0;
    }

    boolean clearedEachFrame(int target) {
        return allocated(target) && (cleared & (1 << target)) != 0;
    }

    /** Whether the next frame clears every side of every target, as Iris does on its first frame. */
    boolean fullClearDue() {
        return fullClearDue;
    }

    /**
     * Opens a frame: the first clears every side, later ones copy each flipped, uncleared target back into main
     * and clear the cleared targets, as Iris does. The HDR image then stands in for the opaque world's
     * colortex0 side.
     */
    void beginFrame(Ops ops) {
        boolean full = fullClearDue;
        fullClearDue = false;
        int world = opaqueWorldSide();
        if (!full) {
            for (int target = 1; target < TARGET_COUNT; target++) {
                if (sided(target) && !clearedEachFrame(target) && PackFlipPlan.flipped(state, target)) {
                    ops.copy(new Image(target, 1), new Image(target, 0));
                }
            }
            if (carriesColortex0()) carryColortex0(ops, world);
        }
        for (int target = 0; target < TARGET_COUNT; target++) {
            if (!allocated(target) || !full && !clearedEachFrame(target)) continue;
            ops.clear(new Image(target, 0));
            if (sided(target)) ops.clear(new Image(target, 1));
        }
        if (full && carriesColortex0()) ops.copy(new Image(0, world), new Image(0, HDR));
        state = 0;
        cursor = 0;
        hdrSide = world;
        hdrNewer = true;
    }

    /**
     * The colortex0 side Iris's gbuffers draw on until the translucents; it holds from the frame's start, as
     * Iris builds its gbuffer framebuffers once.
     */
    private int opaqueWorldSide() {
        return sided(0) && PackFlipPlan.flipped(plan.opaqueWorld(), 0) ? 1 : 0;
    }

    /** The HDR image was recreated empty between frames, so it stands in for no side and nothing is written back. */
    void hdrReplaced() {
        hdrSide = -1;
        hdrNewer = false;
    }

    /** Whether colortex0 is not cleared every frame: the HDR image carries it into the next frame. */
    boolean carriesColortex0() {
        return allocated(0) && !clearedEachFrame(0);
    }

    /**
     * Carries colortex0 into a new frame: main takes the last frame's final side, as Iris's copy-back leaves it,
     * and the HDR image takes {@code world}'s content. When {@code world} is main, the HDR image stays the newer
     * copy and is written back only when a pass needs main.
     */
    private void carryColortex0(Ops ops, int world) {
        int last = readSide(0);
        if (world == 0) {
            if (hdrSide == 1) writeBackHdr(ops, 1);
            if (hdrSide != last) ops.copy(new Image(0, last), new Image(0, HDR));
            return;
        }
        if (last == 1) {
            ops.copy(hdrSide == 1 ? new Image(0, HDR) : new Image(0, 1), new Image(0, 0));
        } else if (hdrSide == 0) {
            writeBackHdr(ops, 0);
        }
        if (hdrSide != 1) ops.copy(new Image(0, 1), new Image(0, HDR));
    }

    int state() {
        return state;
    }

    /** The side {@code target} is read from now; the world also writes it there. */
    int readSide(int target) {
        return sided(target) && PackFlipPlan.flipped(state, target) ? 1 : 0;
    }

    /** The image a sampler reads {@code target} from now: for colortex0, the HDR image while it is newer. */
    Image read(int target) {
        int side = readSide(target);
        if (target == 0 && hdrSide == side && hdrNewer) return new Image(0, HDR);
        return new Image(target, side);
    }

    /**
     * The up-to-date graph image of {@code target}'s read side, for a mip chain or a colorimgN store; a store
     * makes the HDR image stale.
     */
    Image readGraph(int target, boolean store, Ops ops) {
        int side = readSide(target);
        if (target == 0 && hdrSide == side) {
            writeBackHdr(ops, side);
            if (store) hdrSide = -1;
        }
        return new Image(target, side);
    }

    /**
     * Opens {@code window}'s group. Every earlier step that did not run passes through, then the state becomes
     * the group's opening state, after its pre-flips.
     */
    void openWindow(PackFrameSchedulePlan.PostWindow window, Ops ops) {
        passThroughBefore(window, ops);
        state = plan.open(window);
    }

    /**
     * Brings the frame up to {@code pass}, passing through earlier steps that did not run. Returns its step, or
     * null for a compute-only pass.
     */
    PackFlipPlan.Step advanceTo(String pass, Ops ops) {
        Comparator<String> order = PostTargetPlan.programComparator();
        while (cursor < steps.size() && order.compare(steps.get(cursor).program(), pass) < 0) {
            passThrough(steps.get(cursor), ops);
        }
        if (cursor < steps.size() && steps.get(cursor).program().equals(pass)) {
            PackFlipPlan.Step step = steps.get(cursor);
            state = step.before();
            return step;
        }
        return null;
    }

    /**
     * The side a running step writes {@code target} to, the one it does not read. The HDR image is first written
     * back there, as the base a blend or discard keeps.
     */
    Image write(PackFlipPlan.Step step, int target, Ops ops) {
        int side = sided(target) ? step.writeSide(target) : 0;
        if (target == 0 && hdrSide == side) {
            writeBackHdr(ops, side);
            hdrSide = -1;
        }
        return new Image(target, side);
    }

    /** {@code step} ran: its flips take effect. */
    void finish(PackFlipPlan.Step step) {
        state = step.after();
        cursor = Math.max(cursor, steps.indexOf(step) + 1);
    }

    /** Passes a step that did not run through as an identity pass, then flips as the plan says. */
    private void passThrough(PackFlipPlan.Step step, Ops ops) {
        state = step.before();
        for (int target : step.drawBuffers()) {
            if (!sided(target)) continue;
            Image from = read(target);
            Image to = write(step, target, ops);
            if (!from.equals(to)) ops.copy(from, to);
        }
        finish(step);
    }

    private void passThroughBefore(PackFrameSchedulePlan.PostWindow window, Ops ops) {
        while (cursor < steps.size() && steps.get(cursor).window().ordinal() < window.ordinal()) {
            passThrough(steps.get(cursor), ops);
        }
    }

    /**
     * The world draws again after {@code window}'s group: its remaining steps pass through, and the HDR image
     * takes the colortex0 side Iris's gbuffers write next.
     */
    void enterWorld(PackFrameSchedulePlan.PostWindow window, Ops ops) {
        passThroughBefore(next(window), ops);
        state = plan.close(window);
        int side = window == PackFrameSchedulePlan.PostWindow.BEGIN ? opaqueWorldSide() : readSide(0);
        if (hdrSide != side) {
            if (hdrSide >= 0) writeBackHdr(ops, hdrSide);
            ops.copy(new Image(0, side), new Image(0, HDR));
            hdrSide = side;
        }
        hdrNewer = true;
    }

    /** The composite group is over: final reads the plan's final state, which the next frame copies back. */
    void enterFinal(Ops ops) {
        passThroughBefore(PackFrameSchedulePlan.PostWindow.FINAL, ops);
        state = plan.finalState();
    }

    private static PackFrameSchedulePlan.PostWindow next(PackFrameSchedulePlan.PostWindow window) {
        PackFrameSchedulePlan.PostWindow[] windows = PackFrameSchedulePlan.PostWindow.values();
        return windows[Math.min(window.ordinal() + 1, windows.length - 1)];
    }

    /** Copies the HDR image back to colortex0's graph {@code side} if it stands in for that side and is newer. */
    private void writeBackHdr(Ops ops, int side) {
        if (hdrSide == side && hdrNewer) {
            ops.copy(new Image(0, HDR), new Image(0, side));
            hdrNewer = false;
        }
    }
}
