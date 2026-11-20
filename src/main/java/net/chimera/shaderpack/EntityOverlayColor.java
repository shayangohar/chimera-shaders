// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serves {@code entityColor} per vertex from the overlay texel UV1 points at, as Iris's EntityPatcher does; one
 * uniform would make every mob flash together. A texelFetch keeps the red tint row from blending with the white
 * flash row.
 */
final class EntityOverlayColor {
    static final String UV_VARYING = "chimeraOverlayUV";

    private static final String OVERLAY_FETCH = fetch(UV_VARYING);
    /** The primitive's first vertex, as Iris reads entityColor[0]. */
    private static final String GEOMETRY_FETCH = fetch(UV_VARYING + "[0]");
    private static final Pattern UV_READ = Pattern.compile("\\b" + UV_VARYING + "\\b");
    /** entityColor comes out (0, 0, 0, 0). */
    private static final String NO_OVERLAY_TEXEL = "vec4 chimeraOverlayTexel = vec4(0.0, 0.0, 0.0, 1.0);";
    /**
     * The overlay stores how much of the mob shows through, so the tint's strength is one minus alpha; with none,
     * the whole colour is zero.
     */
    private static final String OVERLAY_COLOR =
            "\n    entityColor = vec4(chimeraOverlayTexel.rgb, 1.0 - chimeraOverlayTexel.a);"
                    + "\n    if (entityColor.a == 0.0) entityColor = vec4(0.0);";

    private static final Pattern DECLARATION =
            Pattern.compile("(?m)^[ \\t]*uniform\\s+vec4\\s+entityColor\\s*;");
    private static final Pattern READ = Pattern.compile("\\bentityColor\\b");
    /** Matched with comments masked, so a commented-out main never takes the inserted text. */
    private static final Pattern MAIN =
            Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)\\s*\\{");

    private EntityOverlayColor() {}

    /**
     * GL's empty texel (0, 0, 0, 1) outside the bound image. Types with no overlay keep UV1 at NO_OVERLAY (0, 10)
     * over a 1x1 stand-in; Vulkan leaves that fetch undefined and NVIDIA reads zero, giving opaque black.
     */
    private static String fetch(String uv) {
        String sampler = PackResourcePlan.OVERLAY_SAMPLER;
        return "vec4 chimeraOverlayTexel = all(lessThan(uvec2(" + uv + "), uvec2(textureSize(" + sampler
                + ", 0)))) ? texelFetch(" + sampler + ", " + uv + ", 0) : vec4(0.0, 0.0, 0.0, 1.0);";
    }

    /** A fragment without {@code uniform vec4 entityColor} is unchanged. */
    static String inject(String fragment) {
        return inject(fragment, OVERLAY_FETCH, "");
    }

    /** {@code input} is declared just before main. */
    private static String inject(String source, String fetch, String input) {
        if (source == null) return null;
        // match outside comments, at the offsets of the source as written
        Matcher declaration = DECLARATION.matcher(GlslLexer.maskComments(source));
        if (!declaration.find()) return source;
        StringBuilder rewritten = new StringBuilder(source.substring(0, declaration.start()))
                .append("uniform sampler2D ").append(PackResourcePlan.OVERLAY_SAMPLER).append(";\nvec4 entityColor;");
        int kept = declaration.end();
        while (declaration.find()) {
            rewritten.append(source, kept, declaration.start());
            kept = declaration.end();
        }
        String declared = rewritten.append(source.substring(kept)).toString();
        Matcher main = MAIN.matcher(GlslLexer.maskComments(declared));
        if (!main.find()) return source;
        String prologue = "\n    " + fetch + OVERLAY_COLOR;
        return declared.substring(0, main.start()) + input + declared.substring(main.start(), main.end()) + prologue
                + declared.substring(main.end());
    }

    record ShadowStages(String vertex, String geometry, String fragment) {}

    /**
     * Shadow entity casters, which Iris patches as it does gbuffers_entities. A running geometry stage reads
     * the fetch; where its vertex or fragment stage also reads the value, the uniform stays and reads zero.
     */
    static ShadowStages injectShadow(String vertex, String geometry, String fragment, boolean geometryRuns) {
        ShadowStages unchanged = new ShadowStages(vertex, geometry, fragment);
        if (!geometryRuns) {
            String injected = inject(fragment);
            return injected == null || injected.equals(fragment) ? unchanged
                    : new ShadowStages(forwardOverlayUv(withoutUnreadVertexDeclaration(vertex), injected), geometry,
                            injected);
        }
        String injected = inject(geometry, GEOMETRY_FETCH, "flat in ivec2 " + UV_VARYING + "[];\n");
        String servedVertex = withoutUnreadVertexDeclaration(vertex);
        String servedFragment = withoutUnreadVertexDeclaration(fragment);
        if (injected == null || injected.equals(geometry) || declares(servedVertex) || declares(servedFragment)) {
            return unchanged;
        }
        return new ShadowStages(forwardOverlayUv(servedVertex, injected), injected, servedFragment);
    }

    static String forwardOverlayUv(String vertex, String consumer) {
        if (vertex == null || consumer == null || !UV_READ.matcher(GlslLexer.stripComments(consumer)).find()
                || UV_READ.matcher(GlslLexer.stripComments(vertex)).find()) {
            return vertex;
        }
        Matcher main = MAIN.matcher(GlslLexer.maskComments(vertex));
        if (!main.find()) return vertex;
        return vertex.substring(0, main.start()) + "flat out ivec2 " + UV_VARYING + ";\n"
                + vertex.substring(main.start(), main.end()) + "\n    " + UV_VARYING + " = UV1;"
                + vertex.substring(main.end());
    }

    private static boolean declares(String source) {
        return source != null && DECLARATION.matcher(GlslLexer.maskComments(source)).find();
    }

    /**
     * Iris deletes the uniform from every stage; a declaration shared with the fragment would otherwise meet its
     * global in the shared uniform block. A vertex stage whose main reaches a read keeps it.
     */
    static String withoutUnreadVertexDeclaration(String vertex) {
        if (vertex == null) return null;
        // match outside comments, as inject does
        Matcher declaration = DECLARATION.matcher(GlslLexer.maskComments(vertex));
        StringBuilder kept = new StringBuilder();
        int from = 0;
        while (declaration.find()) {
            kept.append(vertex, from, declaration.start());
            from = declaration.end();
        }
        String removed = kept.append(vertex.substring(from)).toString();
        if (!READ.matcher(GlslLexer.stripComments(removed)).find()) return removed;
        String pruned = GlslTokenRewriter.removeUnreachableFunctions(removed, GlslResourceUsage.analyze(removed));
        return READ.matcher(GlslLexer.stripComments(pruned)).find() ? vertex : pruned;
    }

    /**
     * For a layout without UV1, where Iris leaves entityColor zero. These draws bind no overlay, so a fetch would
     * read whatever slot 1 last held.
     */
    static String withoutOverlay(String fragment) {
        return fragment == null ? null
                : fragment.replace(OVERLAY_FETCH, NO_OVERLAY_TEXEL).replace(GEOMETRY_FETCH, NO_OVERLAY_TEXEL);
    }
}
