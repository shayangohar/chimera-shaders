// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Where a compile error sits, for a failure notice's Copy Info. shaderc names a line of the translated stage, so
 * the notice quotes it and names the few authored lines that read the same; there is no line map.
 */
public final class CompileErrorLocation {
    private static final Pattern ERROR = Pattern.compile("(?m)^(\\S+?):(\\d+): error: (.*)$");
    private static final List<String> SOURCE_EXTENSIONS = List.of(".glsl", ".fsh", ".vsh", ".gsh", ".csh",
            ".tcs", ".tes", ".inc", ".h");
    /** A shorter line matches too many authored ones to name any. */
    private static final int SHORTEST_MATCHED_LINE = 12;
    static final int MOST_MATCHES = 3;
    private static final int MOST_FILES = 4000;

    private CompileErrorLocation() {}

    public static String describe(String compilerMessage, String source, Path shadersDir) {
        String message = compilerMessage == null ? "" : compilerMessage.strip();
        StringBuilder text = new StringBuilder(message);
        Matcher error = ERROR.matcher(message);
        if (source == null || !error.find()) return text.toString();
        String line = line(source, Integer.parseInt(error.group(2)));
        if (line == null) return text.toString();
        text.append("\nTranslated line ").append(error.group(1)).append(':').append(error.group(2)).append(": ")
                .append(line.strip());
        String authored = authoredLine(line, shadersDir);
        if (authored != null) text.append("\nAuthored: ").append(authored);
        return text.toString();
    }

    /** Counted from 1; null past the end. */
    static String line(String source, int number) {
        if (number < 1) return null;
        return source.lines().skip(number - 1L).findFirst().orElse(null);
    }

    /** Null when none or more than {@link #MOST_MATCHES} match, or the line is too short to tell. */
    static String authoredLine(String translated, Path shadersDir) {
        return authoredLine(translated, shadersDir, MOST_FILES);
    }

    /** Reads the first {@code mostFiles} sources in path order. */
    static String authoredLine(String translated, Path shadersDir, int mostFiles) {
        String wanted = translated.strip();
        if (wanted.length() < SHORTEST_MATCHED_LINE || shadersDir == null || !Files.isDirectory(shadersDir)) {
            return null;
        }
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(shadersDir)) {
            for (Path file : files.filter(Files::isRegularFile).filter(CompileErrorLocation::isSource)
                    .sorted().limit(mostFiles).toList()) {
                List<String> lines = readLines(file);
                for (int index = 0; index < lines.size(); index++) {
                    if (!lines.get(index).strip().equals(wanted)) continue;
                    found.add(shadersDir.relativize(file).toString().replace('\\', '/') + ':' + (index + 1));
                    if (found.size() > MOST_MATCHES) return null;
                }
            }
        } catch (IOException | UncheckedIOException unreadable) {
            return null;
        }
        return found.isEmpty() ? null : String.join(" or ", found);
    }

    private static boolean isSource(Path file) {
        String name = file.getFileName().toString();
        return SOURCE_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.ISO_8859_1);
        } catch (IOException unreadable) {
            return List.of();
        }
    }
}
