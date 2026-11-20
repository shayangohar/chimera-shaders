// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A vector compared with a number, such as {@code mc_Entity==1.} on a vec3. NVIDIA's GL compiler widens
 * the number and compares whole values, so Iris loads it; glslang refuses it. The number is written as
 * that vector only when each side is the comparison's whole operand, so source that already compiles
 * stays as written. An integer vector takes only an integer: NVIDIA compares {@code ivec2(0) == 0.5}
 * as floats, where {@code ivec2(0.5)} would truncate.
 */
final class VectorScalarComparisons {
    private VectorScalarComparisons() {}

    private static final Pattern VECTOR = Pattern.compile("[iud]?vec[234]");
    private static final Pattern INT_LITERAL = Pattern.compile("0[xX][0-9a-fA-F]+|\\d+");
    private static final Pattern UINT_LITERAL = Pattern.compile("(?:0[xX][0-9a-fA-F]+|\\d+)[uU]?");
    /**
     * Tokens that bound a comparison's operand, since == and != bind tighter than these. GlslLexer
     * reads ^^ as two ^ tokens, and &=, |= and ^= as the operator then =.
     */
    private static final Set<String> BEFORE = Set.of("(", ",", "=", "?", ":", "&", "^", "|", "&&", "||", ";", "{",
            "}", "return");
    private static final Set<String> AFTER = Set.of(")", ",", ";", "?", ":", "&", "^", "|", "&&", "||");

    /** One side of a comparison: its first and last tokens, and its vector type when it is a vector. */
    private record Operand(int first, int last, String vector) {}

    /** {@code source} with each number compared to a vector written as that vector. */
    static String widen(String source) {
        if (source == null || !source.contains("==") && !source.contains("!=")) return source;
        try {
            return widenAll(GlslTree.parse(source));
        } catch (RuntimeException unreadable) {
            // source the parser cannot follow goes to the compiler as written
            return source;
        }
    }

    /** The tree's source with each number compared to a vector written as that vector. */
    private static String widenAll(GlslTree tree) {
        String source = tree.source();
        List<GlslLexer.Token> tokens = tree.tokens();
        List<GlslTree.Edit> edits = new ArrayList<>();
        for (int index = 0; index < tokens.size(); index++) {
            if (!tokens.get(index).symbol("==") && !tokens.get(index).symbol("!=")) continue;
            GlslTree.Edit edit = widened(tree, index);
            if (edit != null) edits.add(edit);
        }
        return edits.isEmpty() ? source : tree.print(edits);
    }

    /** The number of the comparison at {@code operator} written as the vector it meets, or null. */
    private static GlslTree.Edit widened(GlslTree tree, int operator) {
        List<GlslLexer.Token> tokens = tree.tokens();
        int left = GlslLexer.previousSignificant(tokens, operator);
        int right = GlslLexer.nextSignificant(tokens, operator);
        if (left < 0 || right < 0) return null;
        // a vector then a number, or a number then a vector
        Operand before = vectorEndingAt(tree, left);
        Operand after = before != null ? numberFrom(tokens, right) : vectorStartingAt(tree, right);
        if (before == null) before = numberTo(tokens, left);
        if (!whole(tokens, before, after)) return null;
        Operand number = before.vector() == null ? before : after;
        String vector = before.vector() == null ? after.vector() : before.vector();
        if (!fits(vector, tokens.get(number.last()).text())) return null;
        GlslTree.Range range = new GlslTree.Range(number.first(), number.last() + 1);
        return new GlslTree.Edit(range, vector + "(" + tree.text(range) + ")");
    }

    /** Whether the literal widens unchanged: ivec takes an integer, uvec also unsigned, only dvec a double. */
    private static boolean fits(String vector, String literal) {
        return switch (vector.charAt(0)) {
            case 'i' -> INT_LITERAL.matcher(literal).matches();
            case 'u' -> UINT_LITERAL.matcher(literal).matches();
            case 'd' -> true;
            default -> !literal.toLowerCase(Locale.ROOT).endsWith("lf");
        };
    }

    /** Whether nothing before the left operand or after the right one joins it into a longer expression. */
    private static boolean whole(List<GlslLexer.Token> tokens, Operand before, Operand after) {
        return before != null && after != null
                && joins(tokens, GlslLexer.previousSignificant(tokens, before.first()), BEFORE)
                && joins(tokens, GlslLexer.nextSignificant(tokens, after.last()), AFTER);
    }

    /** Whether the token at {@code index}, or the source's edge when it is -1, cannot join an operand. */
    private static boolean joins(List<GlslLexer.Token> tokens, int index, Set<String> bounds) {
        return index < 0 || bounds.contains(tokens.get(index).text());
    }

    /** A number starting at {@code index}, a sign before its digits allowed; null if none starts there. */
    private static Operand numberFrom(List<GlslLexer.Token> tokens, int index) {
        GlslLexer.Token token = tokens.get(index);
        if (token.kind() == GlslLexer.Kind.NUMBER) return new Operand(index, index, null);
        if (!token.symbol("-") && !token.symbol("+")) return null;
        int digits = GlslLexer.nextSignificant(tokens, index);
        return digits >= 0 && tokens.get(digits).kind() == GlslLexer.Kind.NUMBER
                ? new Operand(index, digits, null) : null;
    }

    /**
     * A number ending at {@code index}, a sign before it included; null if none ends there. The
     * operand's bound before it keeps the sign unary: (-1. == v), never (x - 1. == v).
     */
    private static Operand numberTo(List<GlslLexer.Token> tokens, int index) {
        if (tokens.get(index).kind() != GlslLexer.Kind.NUMBER) return null;
        int sign = GlslLexer.previousSignificant(tokens, index);
        boolean signed = sign >= 0 && (tokens.get(sign).symbol("-") || tokens.get(sign).symbol("+"));
        return new Operand(signed ? sign : index, index, null);
    }

    /** A vector variable's name, or a vector constructor's closing parenthesis, at {@code index}; else null. */
    private static Operand vectorEndingAt(GlslTree tree, int index) {
        List<GlslLexer.Token> tokens = tree.tokens();
        if (!tokens.get(index).symbol(")")) {
            String type = variableVector(tree, index);
            return type == null ? null : new Operand(index, index, type);
        }
        int open = opening(tokens, index);
        int name = open < 0 ? -1 : GlslLexer.previousSignificant(tokens, open);
        String type = name < 0 ? null : constructor(tokens, name);
        return type == null ? null : new Operand(name, index, type);
    }

    /** A vector variable's name, or a whole vector constructor, starting at {@code index}; else null. */
    private static Operand vectorStartingAt(GlslTree tree, int index) {
        List<GlslLexer.Token> tokens = tree.tokens();
        String type = constructor(tokens, index);
        int open = type == null ? -1 : GlslLexer.nextSignificant(tokens, index);
        if (open >= 0 && tokens.get(open).symbol("(")) {
            int close = GlslLexer.matching(tokens, open, "(", ")");
            return close < 0 ? null : new Operand(index, close, type);
        }
        type = variableVector(tree, index);
        return type == null ? null : new Operand(index, index, type);
    }

    /** The vector type named at {@code name}, a constructor's when a call follows; else null. */
    private static String constructor(List<GlslLexer.Token> tokens, int name) {
        GlslLexer.Token token = tokens.get(name);
        return token.kind() == GlslLexer.Kind.IDENTIFIER && VECTOR.matcher(token.text()).matches()
                ? token.text() : null;
    }

    /** The {@code (} that the {@code )} at {@code close} closes, or -1. */
    private static int opening(List<GlslLexer.Token> tokens, int close) {
        int depth = 0;
        for (int index = close; index >= 0; index--) {
            if (tokens.get(index).symbol(")")) {
                depth++;
            } else if (tokens.get(index).symbol("(") && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    /** The vector type of the variable the name at {@code token} resolves to, or null. */
    private static String variableVector(GlslTree tree, int token) {
        GlslTree.Binding binding = tree.resolve(token);
        if (binding == null) return null;
        GlslTree.TypeSpec type = switch (binding.node()) {
            case GlslTree.Declarator declarator -> declarator.arrays().isEmpty() && binding.declaration() != null
                    ? binding.declaration().type() : null;
            case GlslTree.Parameter parameter -> parameter.arrays().isEmpty() ? parameter.type() : null;
            default -> null;
        };
        if (type == null || type.struct() != null || !type.arrays().isEmpty()) return null;
        String name = tree.text(type.name());
        return VECTOR.matcher(name).matches() ? name : null;
    }
}
