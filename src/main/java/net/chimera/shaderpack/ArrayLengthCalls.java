package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * An array's length read without its parentheses: Pixel Perfect's Fake GI and object outlines loop
 * to {@code superSampleOffsetsCross.length}. NVIDIA's GL compiler takes it as {@code length()}, so
 * Iris loads those options; glslang refuses it ("'length' : incomplete method syntax"). The call
 * gets its parentheses only where the name resolves to an array, so a struct's {@code length}
 * field, and every source that already compiled, stays as written.
 */
final class ArrayLengthCalls {
    private ArrayLengthCalls() {}

    /** A {@code .length} with no call after it, spaces allowed: the only text worth parsing for. */
    private static final Pattern BARE_LENGTH = Pattern.compile("\\.\\s*length\\b(?!\\s*\\()");

    /** {@code source} with each {@code array.length} not followed by a call written {@code array.length()}. */
    static String complete(String source) {
        if (source == null || !BARE_LENGTH.matcher(source).find()) return source;
        try {
            return completeAll(GlslTree.parse(source));
        } catch (RuntimeException unreadable) {
            // A source the parser cannot follow goes to the compiler as written.
            return source;
        }
    }

    /** The tree's source with each array's bare length written as a call. */
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

    /** Whether a name declares an array: {@code vec3 name[5]}, {@code vec3[5] name}, or such a parameter. */
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
