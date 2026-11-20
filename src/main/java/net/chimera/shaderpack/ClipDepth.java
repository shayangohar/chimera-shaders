// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import java.util.Map;

/**
 * Converts clip depth from GL's [-w, w] to Vulkan's [0, w]. A path to the rasterizer working in GL's range
 * converts exactly once, in its last stage; one keeping the host's range never does. Converting twice halves
 * every depth a later pass rebuilds; missing it puts the near half of the view in front of the near plane.
 */
final class ClipDepth {
    static final String TO_VULKAN = "gl_Position.z = 0.5 * (gl_Position.z + gl_Position.w);";

    private ClipDepth() {}

    /** Renames every {@code main} to {@code entry} and adds a main that calls it, then converts. */
    static String afterEntry(String source, String entry) {
        return GlslTokenRewriter.replaceIdentifiers(source, Map.of("main", entry))
                + "\nvoid main() {\n"
                + "    " + entry + "();\n"
                + "    " + TO_VULKAN + "\n"
                + "}\n";
    }

    /** For a source whose main the entity bridge already renamed. */
    static String afterRenamedEntry(String source, String entry) {
        return source + "\nvoid main() { " + entry + "(); " + TO_VULKAN + " }\n";
    }

    /** A stage on a path to the rasterizer writes at most one. */
    static int count(String source) {
        if (source == null) return 0;
        int count = 0;
        for (int at = source.indexOf(TO_VULKAN); at >= 0; at = source.indexOf(TO_VULKAN, at + 1)) count++;
        return count;
    }
}
