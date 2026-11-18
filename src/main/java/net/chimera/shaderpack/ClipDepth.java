// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import java.util.Map;

/**
 * Converts a clip position's depth from GL's range, [-w, w], to Vulkan's, [0, w].
 *
 * A path to the rasterizer converts exactly once when its stages work in GL's range, and never when they
 * keep the host's range, whose matrices already produce Vulkan's depth. The last stage on the path converts:
 * <ul>
 *   <li>a vertex stage with nothing after it, after its authored main: a world stage projecting in GL's
 *   range (WorldClipRange.convert), a shadow stage (ShadowStageBuiltins), the hand, sky and hand-glint
 *   draws (EntityVertexBridge), and a post pass's authored vertex stage (PostVertexBridge.convertPostVertex),
 *   which has no depth to keep but is clipped below z = 0;</li>
 *   <li>a geometry stage, on each vertex it emits (GeometryStageBridge.emitClipRange);</li>
 *   <li>a tessellation evaluation stage, after its main (TessellationStageBridge).</li>
 * </ul>
 * Stages ahead of a geometry or tessellation stage hand on GL's range.
 *
 * Converting twice halves every depth a later pass rebuilds; missing it puts the near half of the view in
 * front of the near plane.
 */
final class ClipDepth {
    /** The conversion of the stage's final clip position. */
    static final String TO_VULKAN = "gl_Position.z = 0.5 * (gl_Position.z + gl_Position.w);";

    private ClipDepth() {}

    /**
     * {@code source} with every identifier {@code main} renamed {@code entry}, and a main that calls
     * it, then converts the clip depth.
     */
    static String afterEntry(String source, String entry) {
        return GlslTokenRewriter.replaceIdentifiers(source, Map.of("main", entry))
                + "\nvoid main() {\n"
                + "    " + entry + "();\n"
                + "    " + TO_VULKAN + "\n"
                + "}\n";
    }

    /**
     * {@code source}, whose main the entity bridge already renamed {@code entry}, and a one-line main
     * that calls it, then converts the clip depth: the hand, sky and hand-glint draws.
     */
    static String afterRenamedEntry(String source, String entry) {
        return source + "\nvoid main() { " + entry + "(); " + TO_VULKAN + " }\n";
    }

    /** How many conversions {@code source} writes; a stage on a path to the rasterizer writes at most one. */
    static int count(String source) {
        if (source == null) return 0;
        int count = 0;
        for (int at = source.indexOf(TO_VULKAN); at >= 0; at = source.indexOf(TO_VULKAN, at + 1)) count++;
        return count;
    }
}
