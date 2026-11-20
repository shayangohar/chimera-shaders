// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The clip range a world stage writes. Host matrices map clip depth onto Vulkan's [0, w], but the pack's
 * projection uniforms carry GL's [-w, w], as under Iris; mixed, every position rebuilt from depth lands at
 * half its distance. A stage that reads a host clip built-in and no pack projection keeps the host's range
 * ({@link #HOST}). Any other runs in GL's ({@link #GL}), as under Iris, and converts its clip depth once
 * after the authored entry point; ftransform() stays exact there for window depths from 0.25 on (Sterbenz).
 */
enum WorldClipRange {
    HOST,
    GL;

    /** The pack's camera projections and their inverses, in GL's clip range; an inverse read alone counts too. */
    private static final Set<String> PACK_PROJECTIONS = Set.of(
            "gbufferProjection", "gbufferProjectionInverse", "gbufferPreviousProjection", "dhProjection",
            "dhProjectionInverse", "dhPreviousProjection");
    /** The legacy built-ins that carry the host's clip depth. */
    private static final List<String> CLIP_BUILTINS = List.of("gl_ProjectionMatrix", "gl_ModelViewProjectionMatrix");
    /** What a stage reads to take the host's clip depth: the clip built-ins and ftransform(). */
    private static final Set<String> HOST_CLIP_SOURCES = Set.of("ftransform", "gl_ProjectionMatrix",
            "gl_ModelViewProjectionMatrix");
    /** Name the authored entry point keeps once the conversion claims {@code main}. */
    static final String AUTHORED_ENTRY = "chimeraAuthoredWorldMain";
    /** Takes a host clip position, or a matrix that produces one, into GL's range: z becomes 2z - w. */
    private static final String HELPERS = """
            vec4 chimeraGlClip(vec4 clip) {
                return vec4(clip.xy, 2.0 * clip.z - clip.w, clip.w);
            }
            mat4 chimeraGlClip(mat4 projection) {
                return mat4(chimeraGlClip(projection[0]), chimeraGlClip(projection[1]),
                        chimeraGlClip(projection[2]), chimeraGlClip(projection[3]));
            }
            """;

    /** The range of a stage that reads {@code source}, without comments or unreachable functions. */
    static WorldClipRange of(String source) {
        return GlslResourceUsage.referencedValueIdentifiers(source, PACK_PROJECTIONS).isEmpty()
                && GlslTokenRewriter.containsIdentifier(source, HOST_CLIP_SOURCES::contains) ? HOST : GL;
    }

    /** {@code host}'s built-in replacements, each clip built-in's expression in this range. */
    Map<String, String> builtins(Map<String, String> host) {
        if (this == HOST) return host;
        Map<String, String> converted = new HashMap<>(host);
        CLIP_BUILTINS.forEach(name -> converted.computeIfPresent(name, (key, value) -> "chimeraGlClip(" + value + ")"));
        return converted;
    }

    /** The replacement for {@code ftransform()}: the host's {@code transform}, in this range. */
    String ftransform(String transform) {
        return this == HOST ? transform : "chimeraGlClip(" + transform + ")";
    }

    /** The converted stage, and in GL's range the helpers ahead of it and its clip depth converted after it. */
    String convert(String converted) {
        if (this == HOST) return converted;
        return HELPERS + ClipDepth.afterEntry(converted, AUTHORED_ENTRY);
    }

    /** A stage another follows before the fragment stage: GL's helpers added, its clip depth left for that one. */
    String withHelpers(String converted) {
        return this == HOST ? converted : HELPERS + converted;
    }

    /**
     * The range a modern vertex stage runs in, from its source without comments. A shadow stage gets
     * {@link #HOST} because ShadowStageBuiltins converts it; one ahead of tessellation or a geometry
     * stage runs in GL's, so the next stage reads GL's clip position, as under Iris.
     */
    static WorldClipRange forVertex(String stripped, boolean shadowStage, boolean laterStageConverts) {
        if (shadowStage) return HOST;
        return laterStageConverts ? GL : of(stripped);
    }

    /**
     * A converted vertex stage with its clip depth converted, unless a later stage converts it. One that
     * feeds another stage but writes no position writes a zero one, since Vulkan pairs the next stage's
     * gl_in input with a written output.
     */
    String vertexBody(String converted, boolean shadowStage, boolean laterStageConverts) {
        String body = laterStageConverts && !GlslTokenRewriter.containsIdentifier(converted, "gl_Position")
                ? LegacyGlslConverter.injectMainPrologue(converted, "gl_Position = vec4(0.0);") : converted;
        if (shadowStage) return ShadowStageBuiltins.vertexClipRange(body, laterStageConverts);
        return laterStageConverts ? withHelpers(body) : convert(body);
    }

    /** Whether {@code converted} is a stage {@link #convert} took into GL's range. */
    static boolean converts(String converted) {
        return GlslTokenRewriter.containsIdentifier(converted, AUTHORED_ENTRY);
    }
}
