// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * One round of Iris's setup computes, run in order after each pipeline build or resize. A compute that skips
 * a frame holds the rest of the round, for at most {@link #TRIES} frames.
 */
final class SetupRound {
    static final int TRIES = 8;

    private final Set<String> done = new HashSet<>();
    private boolean due;
    /** Frames the round's next compute has skipped. */
    private int tries;

    /** Starts a round, due when there is a setup compute to run. */
    void start(boolean any) {
        tries = 0;
        done.clear();
        due = any;
    }

    boolean due() {
        return due;
    }

    /**
     * Runs the round's remaining computes in order through {@code dispatch}, which returns false when a compute
     * skips this frame, and stops at a skip unless that compute is out of tries. Returns the names given up on.
     */
    List<String> advance(List<String> names, Predicate<String> dispatch) {
        List<String> abandoned = new ArrayList<>();
        for (String name : names) {
            if (done.contains(name)) continue;
            if (!dispatch.test(name)) {
                if (++tries < TRIES) return abandoned;
                abandoned.add(name);
            }
            done.add(name);
            tries = 0;
        }
        due = false;
        return abandoned;
    }
}
