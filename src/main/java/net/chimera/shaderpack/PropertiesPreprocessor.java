// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A pack's properties file other than shaders.properties, read as Iris 1.21.1 reads it: its PropertiesPreprocessor
 * runs the text through JCPP ({@link #preprocess}), then OrderBackedProperties loads it ({@link #load}). The id maps
 * and dimension.properties go through both.
 */
final class PropertiesPreprocessor {
    /** A {@code #} line JCPP reads as a directive: {@code #} and a name with nothing between. */
    private static final Pattern DIRECTIVE = Pattern.compile("^#([A-Za-z_]\\w*)(.*)$");
    /** A {@code #define} argument; JCPP also takes a {@code $} or non-ASCII name, which Chimera ignores. */
    private static final Pattern MACRO = Pattern.compile("^([A-Za-z_]\\w*)(\\()?\\s*(.*?)\\s*$");
    private static final Set<String> CONDITIONALS = Set.of("if", "ifdef", "ifndef", "elif", "else", "endif");
    /** JCPP 1.4.14's directive names; Iris keeps a {@code #} line starting with one and empties any other. */
    private static final List<String> JCPP_DIRECTIVES = List.of("define", "elif", "else", "endif", "error",
            "if", "ifdef", "ifndef", "include", "line", "pragma", "undef", "warning", "include_next", "import");
    /** Stands in for a backslash while a line expands, as Iris hides backslashes from JCPP. */
    private static final String BACKSLASH = "CHIMERA_KEPT_BACKSLASH";

    private PropertiesPreprocessor() {}

    /**
     * The preprocessed text. {@code failed} when JCPP reports a conditional's error; {@code open} when one is still
     * open at the end.
     */
    record Preprocessed(String text, boolean failed, boolean open) {}

    /**
     * The file as PropertiesPreprocessor hands it on: JCPP reads the line pass's output as C, so block comments span
     * lines. An active branch keeps its comment text, as under KEEPCOMMENTS, and expands the macros defined before
     * it; directive lines and inactive branches are left empty.
     */
    static Preprocessed preprocess(String text, Map<String, String> macros) {
        PackConditionals.State conditions = new PackConditionals.State(macros);
        Map<String, String> expansions = new HashMap<>(macros == null ? Map.of() : macros);
        StringBuilder out = new StringBuilder(text.length());
        boolean failed = false;
        boolean takeNext = false;
        for (ShaderSourcePreprocessor.LogicalLine line : ShaderSourcePreprocessor.logicalLines(jcppInput(text))) {
            int start = line.directiveStart();
            Matcher directive = start < 0 ? null : DIRECTIVE.matcher(line.text().substring(start));
            if (takeNext) {
                // an #ifdef with no name takes the first line with text, past the emptied ones
                takeNext = line.text().isEmpty();
            } else if (directive != null && directive.matches()) {
                // a comment that closes before the directive on its line stays as text, as under KEEPCOMMENTS
                if (conditions.active()) out.append(line.text(), 0, start);
                Conditional applied = apply(directive.group(1), stripComments(directive.group(2)), conditions,
                        expansions);
                failed |= applied != Conditional.APPLIED;
                takeNext = applied == Conditional.TAKES_NEXT_LINE;
            } else if (conditions.active()) {
                out.append(PackConditionals.expandOutsideComments(line.text(), line.inComment(), expansions));
            }
            out.append('\n');
        }
        return new Preprocessed(out.toString().replace(BACKSLASH, "\\"), failed, conditions.open());
    }

    /** How a conditional applied: as written, or with an error JCPP reports, which may take the next line too. */
    enum Conditional { APPLIED, REPORTED, TAKES_NEXT_LINE }

    /**
     * Applies a conditional directive as JCPP does in a properties file, reporting an error and reading on. An #if
     * Chimera cannot evaluate reads false; a stray #else or #elif turns the rest of the file off, and a stray #endif
     * is ignored. An #ifdef with no identifier leaves its branch on and, with no argument, also takes the next line
     * with text.
     *
     * REPORTED where Chimera reads false an expression JCPP gives a value, or where it read an #ifdef's error.
     */
    static Conditional applyConditional(PackConditionals.State conditions, String name, String argument) {
        String directive = name.toLowerCase(Locale.ROOT);
        boolean closing = directive.equals("else") || directive.equals("elif");
        if (closing && conditions.afterElse()) return Conditional.APPLIED;
        if ((directive.equals("ifdef") || directive.equals("ifndef")) && conditions.active()) {
            return applyIfdef(conditions, directive.equals("ifdef"), argument);
        }
        try {
            if (!conditions.apply(directive, argument) && closing) conditions.endAll();
            return Conditional.APPLIED;
        } catch (RuntimeException unsupported) {
            conditions.apply(directive.equals("elif") ? "elif" : "if", "0");
            return Conditional.REPORTED;
        }
    }

    /**
     * An #ifdef or #ifndef in an active branch, its name read as JCPP reads it: a Java identifier, {@code $} and
     * non-ASCII letters included.
     */
    private static Conditional applyIfdef(PackConditionals.State conditions, boolean ifdef, String argument) {
        String tested = PackConditionals.withoutComments(argument).trim();
        if (tested.isEmpty() || !Character.isJavaIdentifierStart(tested.charAt(0))) {
            conditions.apply("if", "1");
            return tested.isEmpty() ? Conditional.TAKES_NEXT_LINE : Conditional.REPORTED;
        }
        int end = 1;
        while (end < tested.length() && Character.isJavaIdentifierPart(tested.charAt(end))) end++;
        boolean defined = conditions.macros().containsKey(tested.substring(0, end));
        conditions.apply("if", defined == ifdef ? "1" : "0");
        return Conditional.APPLIED;
    }

    /**
     * The entries {@code text} loads into OrderBackedProperties, each key where it first appears with its last
     * value. Throws IllegalArgumentException for a malformed unicode escape, as Properties.load does.
     */
    static Map<String, String> load(String text) throws IOException {
        OrderedProperties properties = new OrderedProperties();
        properties.load(new StringReader(text));
        return Collections.unmodifiableMap(properties.entries);
    }

    /**
     * One line of a properties file as JCPP lexes it: a physical line, or a directive line with the lines its open
     * block comment takes. {@code inComment} when it starts inside a block comment, so a {@code #} is no directive.
     */
    record Line(String text, boolean inComment) {}

    /**
     * {@code physical}'s lines as JCPP lexes them after the line pass. A directive may follow the close of the
     * comment its line starts in; a directive line that leaves a comment open takes the lines up to its close,
     * joined with spaces. shaders.properties is read this way.
     */
    static List<Line> commentLines(List<String> physical) {
        List<Line> result = new ArrayList<>(physical.size());
        boolean inComment = false;
        boolean atLineStart = true;
        StringBuilder directive = null;
        for (String raw : physical) {
            String seen = jcppLine(raw);
            if (directive != null) {
                directive.append(' ').append(raw.strip());
                if (ShaderSourcePreprocessor.inCommentAfter(seen, true)) continue;
                result.add(new Line(directive.toString(), false));
                directive = null;
                inComment = false;
                continue;
            }
            int start = ShaderSourcePreprocessor.directiveStart(seen, inComment, atLineStart);
            boolean openAfter = ShaderSourcePreprocessor.inCommentAfter(seen, inComment);
            // the comment text before such a directive reads as a # comment line once loaded
            String text = start > 0 ? seen.substring(start).replace(BACKSLASH, "\\") : raw;
            if (start >= 0 && openAfter) {
                directive = new StringBuilder(text.strip());
                continue;
            }
            result.add(new Line(text, inComment && start < 0));
            if (openAfter) atLineStart = ShaderSourcePreprocessor.openedAtLineStart(seen, inComment, atLineStart);
            inComment = openAfter;
        }
        if (directive != null) result.add(new Line(directive.toString(), false));
        return result;
    }

    /**
     * The text PropertiesPreprocessor gives JCPP: one line per non-blank line, each backslash hidden so no line ends
     * in one and none splices.
     */
    private static String jcppInput(String text) {
        StringJoiner lines = new StringJoiner("\n");
        for (String raw : text.split("\\R")) {
            // Iris trims first, so a line of control characters is blank too
            if (!raw.trim().isBlank()) lines.add(jcppLine(raw));
        }
        return lines.toString();
    }

    /** Whether Iris's line pass leaves nothing of {@code raw}: a blank line or a non-directive {@code #} line. */
    static boolean emptiedByLinePass(String raw) {
        return jcppLine(raw).isEmpty();
    }

    /**
     * What PropertiesPreprocessor gives JCPP for one trimmed line: a {@code #} line only when it starts with a
     * directive's name, any other line without its {@code #} signs. Backslashes are hidden.
     */
    private static String jcppLine(String raw) {
        String line = raw.trim();
        String kept;
        if (!line.startsWith("#")) {
            kept = line.replace("#", "");
        } else {
            kept = JCPP_DIRECTIVES.stream().anyMatch(name -> line.startsWith("#" + name)) ? line : "";
        }
        return kept.replace("\\", BACKSLASH);
    }

    /**
     * Applies one directive; {@code #define} and {@code #undef} apply only in an active branch, and no other
     * directive changes the macros.
     */
    private static Conditional apply(String name, String argument, PackConditionals.State conditions,
                                     Map<String, String> expansions) {
        if (CONDITIONALS.contains(name)) return applyConditional(conditions, name, argument);
        if (!conditions.active()) return Conditional.APPLIED;
        if (name.equals("define")) {
            Matcher macro = MACRO.matcher(argument);
            // a function-like macro is not expanded here
            if (!macro.matches() || macro.group(2) != null) return Conditional.APPLIED;
            conditions.define(macro.group(1), macro.group(3));
            // a macro defined with no body expands to nothing
            expansions.put(macro.group(1), macro.group(3));
        } else if (name.equals("undef") && !argument.isEmpty()) {
            String macro = argument.split("\\s+")[0];
            conditions.undefine(macro);
            expansions.remove(macro);
        }
        return Conditional.APPLIED;
    }

    /**
     * A properties directive's argument without its C comments, as JCPP reads it with backslashes hidden: in
     * {@code "a\" // b"} the string closes at the backslash's quote, so the value is {@code "a\"}.
     */
    static String withoutComments(String argument) {
        return PackConditionals.withoutComments(argument.replace("\\", BACKSLASH)).replace(BACKSLASH, "\\");
    }

    /** A directive's argument without its C comments, which JCPP skips, trimmed. */
    private static String stripComments(String argument) {
        return PackConditionals.withoutComments(argument == null ? "" : argument).trim();
    }

    /** java.util.Properties that also keeps each key where it first appeared, as OrderBackedProperties does. */
    private static final class OrderedProperties extends Properties {
        private final transient Map<String, String> entries = new LinkedHashMap<>();

        @Override
        public synchronized Object put(Object key, Object value) {
            entries.put((String) key, (String) value);
            return super.put(key, value);
        }
    }
}
