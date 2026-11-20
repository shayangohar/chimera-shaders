// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Which post stages threw this session; each then runs as identity while the chain goes on. A device-level
 * failure, or a third failing stage, turns the pack off.
 */
final class PostStageFailures {
    /** The distinct failing stages at which the session stops isolating and falls back to vanilla. */
    static final int VANILLA_AT = 3;
    /** VkResult codes for out of host memory, out of device memory and device lost. */
    private static final Set<String> DEVICE_RESULTS = Set.of("-1", "-2", "-4");

    private final Set<String> skipped = new LinkedHashSet<>();

    /** True when {@code stage} failed earlier this session and now runs as identity. */
    boolean skipped(String stage) {
        return this.skipped.contains(stage);
    }

    /** Records {@code stage}'s failure; false when the session must fall back to vanilla. */
    boolean isolate(String stage, Throwable failure) {
        if (deviceLevel(failure)) return false;
        this.skipped.add(stage);
        return this.skipped.size() < VANILLA_AT;
    }

    int count() {
        return this.skipped.size();
    }

    void clear() {
        this.skipped.clear();
    }

    /**
     * True when the failure or a cause is the device's: out of memory, or device lost. VulkanMod names the
     * result in the message; Chimera's own checks end it with the code.
     */
    static boolean deviceLevel(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof OutOfMemoryError) return true;
            String message = cause.getMessage();
            if (message == null) continue;
            String upper = message.toUpperCase(Locale.ROOT);
            if (upper.contains("VK_ERROR_DEVICE_LOST") || upper.contains("VK_ERROR_OUT_OF_DEVICE_MEMORY")
                    || upper.contains("VK_ERROR_OUT_OF_HOST_MEMORY")) {
                return true;
            }
            int colon = message.lastIndexOf(':');
            if (colon >= 0 && DEVICE_RESULTS.contains(message.substring(colon + 1).trim())) return true;
        }
        return false;
    }
}
