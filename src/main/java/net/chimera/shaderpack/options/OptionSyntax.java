// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack.options;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.chimera.shaderpack.options.ShaderOption.Kind.CONST;
import static net.chimera.shaderpack.options.ShaderOption.Kind.DEFINE;

/**
 * The source line forms that declare an option or test a name. Each line is read on its own, trimmed, with
 * no knowledge of block comments, conditionals or continuations.
 *
 * Between tokens a form allows a gap: a whitespace character followed by any run of control characters
 * and spaces. Spaces outside ASCII (U+00A0, U+2003) never belong to a gap, so one breaks any form it sits
 * in. A comment marker is two or more slashes, and its text is the rest of the line, trimmed.
 */
final class OptionSyntax {
    private static final String GAP = "[\\t-\\r\\x1C-\\x20][\\x00-\\x20]*+";
    private static final String OPTIONAL_GAP = "(?:" + GAP + ")?";
    private static final String WORD = "[\\p{javaAlphabetic}\\p{javaDigit}_]++";

    /** {@code #ifdef NAME} or {@code #ifndef NAME}, alone on the line. */
    private static final Pattern TEST_LINE = form("#ifn?def" + GAP + "(?<name>" + WORD + ")" + OPTIONAL_GAP);

    /**
     * A define, perhaps commented out, followed by nothing or a comment (a toggle), or by a gap and the
     * text that must hold a value and a value list (a choice).
     */
    private static final Pattern DEFINE_LINE = form("(?<off>//+)?" + OPTIONAL_GAP + "#define" + GAP
            + "(?<name>" + WORD + ")(?:" + OPTIONAL_GAP + "//+(?<comment>.*)|" + GAP + "(?<value>.*))?");

    /** A const declaration up to its value. */
    private static final Pattern CONST_LINE = form("const" + GAP + "(?<type>int|float|bool)" + GAP
            + "(?<name>" + WORD + ")" + OPTIONAL_GAP + "=" + OPTIONAL_GAP + "(?<value>.*)");

    /** What may follow a define choice's value. */
    private static final Pattern DEFINE_TAIL = form(OPTIONAL_GAP + "//+(?<comment>.*)");

    /** What may follow a const value: the semicolon, then nothing but a comment. */
    private static final Pattern CONST_TAIL = form(OPTIONAL_GAP + ";" + OPTIONAL_GAP + "(?://+(?<comment>.*))?");

    /**
     * A number candidate: the longest run whose every character is a digit or comes right before one, then
     * an {@code f} or {@code F} when another character follows it. It counts only when it parses as a
     * float, so {@code 1.5e-3} stops at {@code 1.5} and {@code 0x10} is read as a word instead.
     */
    private static final Pattern NUMBER = form("(?:\\p{javaDigit}|.(?=\\p{javaDigit}))++(?:[fF](?=.))?");
    private static final Pattern WORD_TOKEN = form(WORD);

    /** The const names a pack may offer as options; any other const is left alone. */
    private static final Set<String> CONFIGURABLE = configurableNames();

    private OptionSyntax() {}

    /** The name an {@code #ifdef} or {@code #ifndef} line tests. */
    static Optional<String> testedName(String line) {
        Matcher test = TEST_LINE.matcher(line.trim());
        return test.matches() ? Optional.of(test.group("name")) : Optional.empty();
    }

    /** The option a line declares. */
    static Optional<ShaderOption> declared(String line) {
        String trimmed = line.trim();
        return constant(trimmed).or(() -> define(trimmed));
    }

    private static Optional<ShaderOption> define(String line) {
        Matcher define = DEFINE_LINE.matcher(line);
        if (!define.matches()) return Optional.empty();
        String name = define.group("name");
        boolean on = define.group("off") == null;
        String valueText = define.group("value");
        if (valueText == null) return Optional.of(new ShaderOption.Toggle(name, DEFINE, comment(define), on));
        // a commented-out define with a value is no option at all
        if (!on) return Optional.empty();
        return valueThen(valueText, DEFINE_TAIL).flatMap(value -> listed(name, DEFINE, value));
    }

    private static Optional<ShaderOption> constant(String line) {
        Matcher constant = CONST_LINE.matcher(line);
        if (!constant.matches() || !CONFIGURABLE.contains(constant.group("name"))) return Optional.empty();
        String name = constant.group("name");
        Optional<Value> value = valueThen(constant.group("value"), CONST_TAIL);
        if (constant.group("type").equals("bool")) {
            return value.filter(bool -> bool.token().equals("true") || bool.token().equals("false"))
                    .map(bool -> new ShaderOption.Toggle(name, CONST, bool.comment(),
                            Boolean.parseBoolean(bool.token())));
        }
        return value.flatMap(number -> listed(name, CONST, number));
    }

    /** A value token and the comment after it. */
    private record Value(String token, Optional<String> comment) {}

    /** The value token {@code text} starts with, when {@code tail} matches everything after it. */
    private static Optional<Value> valueThen(String text, Pattern tail) {
        String token = valueToken(text);
        if (token == null) return Optional.empty();
        Matcher after = tail.matcher(text.substring(token.length()));
        return after.matches() ? Optional.of(new Value(token, comment(after))) : Optional.empty();
    }

    /** A number when one parses at the start of {@code text}, else a word; null when there is neither. */
    private static String valueToken(String text) {
        Matcher number = NUMBER.matcher(text);
        if (number.lookingAt() && parsesAsFloat(number.group())) return number.group();
        Matcher word = WORD_TOKEN.matcher(text);
        return word.lookingAt() ? word.group() : null;
    }

    private static boolean parsesAsFloat(String text) {
        try {
            Float.parseFloat(text);
            return true;
        } catch (NumberFormatException notANumber) {
            return false;
        }
    }

    private static Optional<String> comment(Matcher matched) {
        return Optional.ofNullable(matched.group("comment")).map(String::trim);
    }

    /**
     * A choice when the comment holds a value list: the text from its first {@code [} to the next {@code ]},
     * split on every single space. The default joins the end of the list when missing, and the option's
     * comment is what surrounds the list.
     */
    private static Optional<ShaderOption> listed(String name, ShaderOption.Kind kind, Value value) {
        String comment = value.comment().orElse("");
        int open = comment.indexOf('[');
        int close = open < 0 ? -1 : comment.indexOf(']', open);
        if (close < 0) return Optional.empty();
        List<String> values = new ArrayList<>(List.of(comment.substring(open + 1, close).split(" ")));
        if (!values.contains(value.token())) values.add(value.token());
        String rest = (comment.substring(0, open) + comment.substring(close + 1)).trim();
        return Optional.of(new ShaderOption.Choice(name, kind, Optional.of(rest), value.token(), values));
    }

    private static Set<String> configurableNames() {
        Set<String> names = new HashSet<>(List.of("ambientOcclusionLevel", "centerDepthHalflife", "drynessHalflife",
                "entityShadowDistanceMul", "eyeBrightnessHalflife", "generateShadowColorMipmap",
                "generateShadowMipmap", "noiseTextureResolution", "shadow0MinMagNearest", "shadow1MinMagNearest",
                "shadowDistance", "shadowDistanceRenderMul", "shadowHardwareFiltering", "shadowIntervalSize",
                "shadowMapResolution", "shadowtex0Mipmap", "shadowtex0Nearest", "shadowtex1Mipmap",
                "shadowtex1Nearest", "shadowtexMipmap", "shadowtexNearest", "sunPathRotation", "superSamplingLevel",
                "voxelDistance", "wetnessHalflife"));
        for (int index = 0; index <= 7; index++) {
            for (String target : List.of("shadowcolor", "shadowColor")) {
                for (String filter : List.of("Mipmap", "Nearest", "MinMagNearest")) names.add(target + index + filter);
            }
            names.add("shadowHardwareFiltering" + index);
        }
        return Set.copyOf(names);
    }

    private static Pattern form(String regex) {
        return Pattern.compile(regex, Pattern.DOTALL);
    }
}
