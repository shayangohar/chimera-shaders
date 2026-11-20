// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack.options;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** One source file as the option scan sees it; values are applied by editing the declaring lines in place. */
final class AnnotatedSource {
    private static final String CHANGED = " // changed by the shader pack options";
    private static final Pattern LEADING_SLASHES = Pattern.compile("^/+");

    private final List<String> lines;
    private final Map<Integer, ShaderOption> options;
    private final Set<String> testedNames;

    private AnnotatedSource(List<String> lines, Map<Integer, ShaderOption> options, Set<String> testedNames) {
        this.lines = lines;
        this.options = Collections.unmodifiableMap(options);
        this.testedNames = Set.copyOf(testedNames);
    }

    /** Reads {@code lines}, one per source line without its terminator. */
    static AnnotatedSource parse(List<String> lines) {
        Map<Integer, ShaderOption> options = new TreeMap<>();
        Set<String> tested = new HashSet<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            Optional<ShaderOption> option = OptionSyntax.declared(line);
            if (option.isPresent()) options.put(index, option.get());
            OptionSyntax.testedName(line).ifPresent(tested::add);
        }
        return new AnnotatedSource(List.copyOf(lines), options, tested);
    }

    List<String> lines() {
        return lines;
    }

    /** Declared options by zero-based line index, ascending. */
    Map<Integer, ShaderOption> options() {
        return options;
    }

    Set<String> testedNames() {
        return testedNames;
    }

    /**
     * The lines with {@code values} written into every declaration of a changed name, whichever survived;
     * {@link #lines()} itself when nothing changes.
     */
    List<String> apply(OptionValues values) {
        List<String> edited = new ArrayList<>(lines);
        options.forEach((index, option) -> changedValue(option, values)
                .ifPresent(value -> edited.set(index, rewrite(lines.get(index), option, value))));
        return edited.equals(lines) ? lines : Collections.unmodifiableList(edited);
    }

    /** The option's changed value as source text; a line reads the namespace of its own declaration. */
    private static Optional<String> changedValue(ShaderOption option, OptionValues values) {
        return switch (option) {
            case ShaderOption.Toggle toggle -> values.toggle(toggle.name()).map(String::valueOf);
            case ShaderOption.Choice choice -> values.choice(choice.name());
        };
    }

    private static String rewrite(String line, ShaderOption option, String value) {
        if (option.kind() == ShaderOption.Kind.CONST) {
            // the line's own default, which a conflict may have made differ from the surviving one
            String old = option.defaultText();
            int at = line.indexOf(old, line.indexOf('='));
            return line.substring(0, at) + value + line.substring(at + old.length());
        }
        if (option instanceof ShaderOption.Choice) return "#define " + option.name() + " " + value + CHANGED;
        return Boolean.parseBoolean(value) ? uncommented(line) : "//" + line;
    }

    /** A define line without the comment marker in front; an indented line is trimmed first. */
    private static String uncommented(String line) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("//")) return line;
        String start = line.startsWith("//") ? line : trimmed;
        return LEADING_SLASHES.matcher(start).replaceFirst("");
    }
}
