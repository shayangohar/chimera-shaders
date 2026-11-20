// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import java.util.regex.Pattern;

/**
 * Load-time proof that an authored program writes nothing, as packs switch a family off by discarding every
 * fragment. The fragment main must begin with an unconditional discard, and neither stage may have side
 * effects, which a discarded draw keeps.
 */
public final class AuthoredOutput {
    private static final Pattern DISCARDING_MAIN = Pattern.compile(
            "\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)\\s*\\{\\s*discard\\s*;");
    private static final Pattern SIDE_EFFECT = Pattern.compile(
            "\\b(?:imageStore|imageAtomic\\w*|atomic\\w*|buffer)\\b");
    private static final Pattern COMMENTS = Pattern.compile("(?s)/\\*.*?\\*/|//[^\\n]*");

    private AuthoredOutput() {}

    /** Prepared sources have their inactive preprocessor branches removed. */
    public static boolean producesNothing(String preparedVertex, String preparedFragment) {
        if (preparedFragment == null) return false;
        String fragment = strip(preparedFragment);
        return DISCARDING_MAIN.matcher(fragment).find()
                && !SIDE_EFFECT.matcher(fragment).find()
                && !SIDE_EFFECT.matcher(strip(preparedVertex)).find();
    }

    private static String strip(String source) {
        return source == null ? "" : COMMENTS.matcher(source).replaceAll(" ");
    }
}
