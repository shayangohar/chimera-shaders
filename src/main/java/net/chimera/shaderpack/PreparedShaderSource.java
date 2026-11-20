// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import java.util.List;

/** One stage's source after the pack preprocessor; the path is relative to the shader root, with forward slashes. */
public record PreparedShaderSource(
        String stage,
        String relativePath,
        String source,
        List<String> dependencies,
        List<String> deviations
) {
    public PreparedShaderSource {
        stage = stage == null ? "" : stage;
        relativePath = relativePath == null ? "" : relativePath.replace('\\', '/');
        dependencies = dependencies == null ? List.of() : dependencies.stream().distinct().sorted().toList();
        deviations = deviations == null ? List.of() : deviations.stream().distinct().sorted().toList();
    }

    public boolean successful() {
        return source != null && deviations.stream().noneMatch(value ->
                value.startsWith("SOURCE_")
                        || (value.startsWith("PREPROCESSOR_")
                        && !ShaderSourcePreprocessor.isWarning(value)));
    }
}
