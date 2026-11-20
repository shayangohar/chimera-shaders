// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import java.util.List;

/**
 * The targets one post program reads and writes, as scheduled. {@code mipmapTargets} are regenerated before the
 * step runs, as in Iris; {@code unwrittenTargets} are directive targets the shader never writes, flipped all the
 * same.
 */
public record TargetStep(
        String programName,
        List<Integer> readTargets,
        List<Integer> outputTargets,
        List<Integer> mipmapTargets,
        int width,
        int height,
        boolean finalStage,
        boolean executable,
        List<String> deviations,
        List<Integer> unwrittenTargets
) {
    public TargetStep {
        programName = programName == null ? "" : programName;
        readTargets = readTargets == null ? List.of() : readTargets.stream().distinct().sorted().toList();
        outputTargets = outputTargets == null ? List.of() : List.copyOf(outputTargets);
        mipmapTargets = mipmapTargets == null ? List.of() : mipmapTargets.stream().distinct().sorted().toList();
        deviations = deviations == null ? List.of() : deviations.stream().distinct().sorted().toList();
        unwrittenTargets = unwrittenTargets == null ? List.of() : List.copyOf(unwrittenTargets);
    }

    public boolean reads(int target) {
        return readTargets.contains(target);
    }

    public boolean writes(int target) {
        return outputTargets.contains(target);
    }
}
