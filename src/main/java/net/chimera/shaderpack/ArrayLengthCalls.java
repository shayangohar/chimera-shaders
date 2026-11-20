// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Adds the parentheses to a bare {@code array.length}, which NVIDIA's GL compiler accepts and glslang refuses
 * ("'length' : incomplete method syntax"). Only names that resolve to arrays change, so a struct's {@code length}
 * field stays as written.
 */
final class ArrayLengthCalls {
    private ArrayLengthCalls() {}

    /** A source without one is not parsed. */
    private static final Pattern BARE_LENGTH = Pattern.compile("\\.\\s*length\\b(?!\\s*\\()");

    static String complete(String source) {
        if (source == null || !BARE_LENGTH.matcher(source).find()) return source;
        try {
            return completeAll(GlslTree.parse(source));
        } catch (RuntimeException unreadable) {
            // a source the parser cannot follow goes to the compiler as written
            return source;
        }
    }

    private static String completeAll(GlslTree tree) {
        String source = tree.source();
        List<GlslLexer.Token> tokens = tree.tokens();
        List<GlslTree.Edit> edits = new ArrayList<>();
        for (int index = 0; index < tokens.size(); index++) {
            if (!tokens.get(index).identifier("length")) continue;
            int next = GlslLexer.nextSignificant(tokens, index);
            if (next >= 0 && tokens.get(next).symbol("(")) continue;
            int dot = GlslLexer.previousSignificant(tokens, index);
            if (dot < 0 || !tokens.get(dot).symbol(".")) continue;
            int name = GlslLexer.previousSignificant(tokens, dot);
            if (name >= 0 && array(tree.resolve(name))) {
                edits.add(new GlslTree.Edit(new GlslTree.Range(index + 1, index + 1), "()"));
            }
        }
        return edits.isEmpty() ? source : tree.print(edits);
    }

    /** {@code vec3 name[5]}, {@code vec3[5] name}, or such a parameter. */
    private static boolean array(GlslTree.Binding binding) {
        if (binding == null) return false;
        return switch (binding.node()) {
            case GlslTree.Declarator declarator -> !declarator.arrays().isEmpty()
                    || binding.declaration() != null && binding.declaration().type() != null
                    && !binding.declaration().type().arrays().isEmpty();
            case GlslTree.Parameter parameter -> !parameter.arrays().isEmpty()
                    || parameter.type() != null && !parameter.type().arrays().isEmpty();
            default -> false;
        };
    }
}
