package net.chimera.render;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A failure's report file, {@code <gameDir>/chimera/reports/<time>-<pack>.txt}: what failed and where, the
 * versions and the device it ran on, the pack and its options, and the conformance report. It is written to
 * be shared, so it names no user: the home folder reads as {@code ~}, and the system and player names as
 * {@code <user>}.
 */
final class PackFailureReport {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");
    private static final DateTimeFormatter TEXT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** How many of the newest reports the folder keeps. */
    static final int KEPT_REPORTS = 50;
    /** Pack file hashes by path, size and modified time, so a large pack is read once rather than per failure. */
    private static final Map<String, String> HASHES = new ConcurrentHashMap<>();

    /** The versions of Chimera and its host, and the system it runs on. */
    record Environment(String chimera, String vulkanMod, String neoForge, String minecraft,
                       String gpu, String driver, String vulkan, String os, String java) {}

    /** The pack the failure struck, as loaded. */
    record Pack(String name, String hash, String profile, Map<String, String> options, String conformanceJson) {}

    private PackFailureReport() {}

    /** The report's text, before redaction. */
    static String text(PackFailureNotices.Failure failure, Throwable cause, Environment environment, Pack pack,
                       LocalDateTime when) {
        StringBuilder text = new StringBuilder();
        text.append("Chimera failure report, ").append(TEXT_TIME.format(when)).append("\n\n");
        section(text, "Failure");
        text.append("Pack: ").append(failure.pack()).append('\n')
                .append("Program or pass: ").append(failure.subject()).append('\n')
                .append("Phase: ").append(failure.phase()).append('\n')
                .append("Outcome: ").append(failure.outcome()).append('\n')
                .append("Reason: ").append(failure.reason()).append("\n\n")
                .append(failure.info()).append("\n\n");
        if (cause != null) {
            section(text, "Stack trace");
            text.append(stackTrace(cause)).append("\n\n");
        }
        section(text, "Versions");
        text.append("Chimera: ").append(environment.chimera()).append('\n')
                .append("VulkanMod: ").append(environment.vulkanMod()).append('\n')
                .append("NeoForge: ").append(environment.neoForge()).append('\n')
                .append("Minecraft: ").append(environment.minecraft()).append("\n\n");
        section(text, "System");
        text.append("GPU: ").append(environment.gpu()).append('\n')
                .append("Driver: ").append(environment.driver()).append('\n')
                .append("Vulkan: ").append(environment.vulkan()).append('\n')
                .append("OS: ").append(environment.os()).append('\n')
                .append("Java: ").append(environment.java()).append("\n\n");
        if (pack != null) {
            section(text, "Pack");
            text.append("Name: ").append(pack.name()).append('\n')
                    .append("SHA-256: ").append(pack.hash()).append('\n')
                    .append("Profile: ").append(pack.profile()).append('\n')
                    .append("Changed options:");
            if (pack.options().isEmpty()) text.append(" none");
            new TreeMap<>(pack.options()).forEach((name, value) ->
                    text.append("\n  ").append(name).append('=').append(value));
            text.append("\n\n");
            section(text, "Conformance report");
            text.append(pack.conformanceJson()).append('\n');
        }
        return text.toString();
    }

    private static void section(StringBuilder text, String title) {
        text.append(title).append('\n').append("-".repeat(title.length())).append('\n');
    }

    private static String stackTrace(Throwable cause) {
        StringWriter out = new StringWriter();
        cause.printStackTrace(new PrintWriter(out));
        return out.toString().strip();
    }

    /**
     * A user folder left in any path: {@code X:\Users\<name>} or {@code Documents and Settings} with any run
     * of either slash, since JSON doubles backslashes, or {@code /home/<name>} and {@code /Users/<name>}. It
     * catches a short 8.3 name, as in the temporary folder's path, and a profile folder named unlike the user.
     * The name runs to the next slash, spaces included.
     */
    private static final Pattern USER_FOLDER = Pattern.compile(
            "(?i)((?:[A-Z]:[\\\\/]+(?:Users|Documents and Settings)|(?<![A-Za-z0-9])/(?:home|Users))[\\\\/]+)"
                    + "[^\\\\/\"'\\r\\n]+");
    /**
     * The same folder in a deviation, whose argument keeps only word characters: {@code C_Users_name_...}
     * (PackPlanBuilder.failureReason). The name runs to the next underscore.
     */
    private static final Pattern SANITIZED_USER_FOLDER = Pattern.compile(
            "(?i)(?<![A-Za-z0-9])([A-Z]_+(?:Users|Documents_and_Settings)_+)[A-Za-z0-9.~-]+");

    /**
     * {@code text} with no user in it. Each of {@code homes}, with either slash or as a deviation spells it,
     * reads as {@code ~}; any other user folder as {@code <user>}; and each of {@code names}, as a whole word
     * in any case, as {@code <user>}.
     */
    static String redact(String text, List<String> homes, List<String> names) {
        String redacted = text;
        List<String> folders = new ArrayList<>();
        for (String home : homes) {
            if (home == null || home.isBlank()) continue;
            folders.add(home);
            folders.add(home.replace('\\', '/'));
            folders.add(home.replace('/', '\\'));
            folders.add(home.replaceAll("[^A-Za-z0-9_.-]+", "_"));
        }
        folders.sort(Comparator.comparingInt(String::length).reversed());
        for (String folder : folders) {
            redacted = Pattern.compile(Pattern.quote(folder), Pattern.CASE_INSENSITIVE).matcher(redacted)
                    .replaceAll(Matcher.quoteReplacement("~"));
        }
        redacted = USER_FOLDER.matcher(redacted).replaceAll("$1<user>");
        redacted = SANITIZED_USER_FOLDER.matcher(redacted).replaceAll("$1<user>");
        for (String name : names) {
            // a name of one or two letters would blot out words, and names no one in a shader log
            if (name == null || name.strip().length() < 3) continue;
            redacted = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(name) + "(?![A-Za-z0-9_])",
                    Pattern.CASE_INSENSITIVE).matcher(redacted).replaceAll(Matcher.quoteReplacement("<user>"));
        }
        return redacted;
    }

    /**
     * Writes {@code text} as a new report under {@code gameDir} and returns its path. The folder keeps the
     * newest {@link #KEPT_REPORTS}, since each carries a conformance report of hundreds of kilobytes and a
     * skip is told again each game.
     */
    static Path write(Path gameDir, String pack, String text, LocalDateTime when) throws IOException {
        Path folder = gameDir.resolve("chimera").resolve("reports");
        Files.createDirectories(folder);
        String stem = FILE_TIME.format(when) + "-" + fileSafe(pack);
        Path file = folder.resolve(stem + ".txt");
        for (int count = 2; Files.exists(file); count++) file = folder.resolve(stem + "-" + count + ".txt");
        Files.writeString(file, text, StandardCharsets.UTF_8);
        try {
            prune(folder);
        } catch (IOException | RuntimeException kept) {
            // a report open elsewhere stays; the new one is written either way
        }
        return file;
    }

    /** Deletes all but the newest {@link #KEPT_REPORTS} reports, oldest written first. */
    private static void prune(Path folder) throws IOException {
        List<Path> reports;
        try (Stream<Path> files = Files.list(folder)) {
            reports = files.filter(file -> file.getFileName().toString().endsWith(".txt"))
                    .sorted(Comparator.comparingLong(PackFailureReport::writtenAt)
                            .thenComparing(file -> file.getFileName().toString()))
                    .toList();
        }
        for (int index = 0; index < reports.size() - KEPT_REPORTS; index++) Files.deleteIfExists(reports.get(index));
    }

    private static long writtenAt(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException unreadable) {
            return Long.MAX_VALUE;
        }
    }

    /** {@code name} with only the characters every file system takes. */
    static String fileSafe(String name) {
        String safe = (name == null ? "pack" : name).replaceAll("[^A-Za-z0-9._-]+", "_");
        if (safe.length() > 80) safe = safe.substring(0, 80);
        return safe.isEmpty() ? "pack" : safe;
    }

    /**
     * The SHA-256 of a pack file, read once a game per path, size and modified time. A folder pack's files
     * are hashed one by one in its conformance report.
     */
    static String hash(Path pack) {
        if (pack == null) return "none";
        if (!Files.isRegularFile(pack)) return "folder (each file's hash is in the conformance report)";
        String key;
        try {
            key = pack.toAbsolutePath() + ":" + Files.size(pack) + ":" + Files.getLastModifiedTime(pack).toMillis();
        } catch (IOException unreadable) {
            return "unreadable (" + unreadable.getClass().getSimpleName() + ")";
        }
        return HASHES.computeIfAbsent(key, ignored -> digest(pack));
    }

    private static String digest(Path pack) {
        try (InputStream in = Files.newInputStream(pack)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 16];
            for (int read; (read = in.read(buffer)) > 0; ) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException unreadable) {
            return "unreadable (" + unreadable.getClass().getSimpleName() + ")";
        }
    }
}
