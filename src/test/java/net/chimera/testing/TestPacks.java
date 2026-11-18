// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.testing;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The packs the tests read: fixture packs and baselines checked into {@code testpacks}, and the
 * locked shader packs outside the repository. Gradle passes every {@code chimera.*} project
 * property through as a system property, so {@code -Pchimera.pack.complementary=<zip>} reaches
 * {@link #complementary()}.
 */
public final class TestPacks {
    private TestPacks() {}

    /** The checked-in fixture root; {@code chimera.fixtureRoot} overrides it. */
    public static Path fixtures() {
        return Path.of(System.getProperty("chimera.fixtureRoot", "testpacks"));
    }

    public static Path fixture(String name) {
        return fixtures().resolve(name);
    }

    /** The recorded baseline {@code testpacks/baselines/<name>.json}. */
    public static Path baseline(String name) {
        return fixtures().resolve("baselines").resolve(name + ".json");
    }

    /** The locked Complementary Reimagined pack. */
    public static Path complementary() {
        return path("chimera.pack.complementary");
    }

    /** The locked BSL pack. */
    public static Path bsl() {
        return path("chimera.pack.bsl");
    }

    /** Further packs, {@code chimera.pack.extra=<pack>;<pack>}; empty when none are named. */
    public static List<Path> extra() {
        return Arrays.stream(System.getProperty("chimera.pack.extra", "").split(";"))
                .map(String::strip).filter(value -> !value.isEmpty()).map(TestPacks::existing).toList();
    }

    /** Every pack the pack tests cover: Complementary and BSL, then {@link #extra}. */
    public static List<Path> corpus() {
        List<Path> packs = new ArrayList<>(List.of(complementary(), bsl()));
        packs.addAll(extra());
        return packs;
    }

    /**
     * The packs shard {@code shard} of {@code shards} takes: largest first, each to the shard with
     * the fewest bytes so far, so the shards' work is about even and a pack always lands in the same
     * shard. Every pack lands in exactly one shard.
     */
    public static List<Path> shard(List<Path> packs, int shard, int shards) {
        List<Path> sorted = new ArrayList<>(packs);
        sorted.sort(Comparator.comparingLong(TestPacks::size).reversed()
                .thenComparing(path -> path.getFileName().toString()));
        long[] load = new long[shards];
        List<Path> mine = new ArrayList<>();
        for (Path pack : sorted) {
            int target = 0;
            for (int index = 1; index < shards; index++) if (load[index] < load[target]) target = index;
            load[target] += Math.max(1L, size(pack));
            if (target == shard) mine.add(pack);
        }
        return mine;
    }

    /**
     * A copy of {@code pack} in {@code folder} with no {@code <pack>.txt} beside it, so it loads at the
     * pack's defaults. A zip is copied as a file; a folder pack is copied whole, following linked
     * folders as loading it in place would. An older copy is replaced, not merged into.
     */
    public static Path copyWithoutOptions(Path pack, Path folder) throws IOException {
        Path copy = folder.resolve(pack.getFileName().toString());
        if (Files.exists(copy, LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> paths = Files.walk(copy)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        Files.createDirectories(folder);
        if (!Files.isDirectory(pack)) return Files.copy(pack, copy);
        try (Stream<Path> paths = Files.walk(pack, FileVisitOption.FOLLOW_LINKS)) {
            for (Path path : paths.toList()) {
                Path destination = copy.resolve(pack.relativize(path).toString());
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination);
            }
        }
        return copy;
    }

    private static long size(Path pack) {
        try {
            return Files.isRegularFile(pack) ? Files.size(pack) : 0L;
        } catch (IOException unreadable) {
            return 0L;
        }
    }

    /** A path the run must name; fails with the flag to set when it is missing. */
    public static Path path(String property) {
        return existing(text(property));
    }

    /** A value the run must name, such as a pack version. */
    public static String text(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Set -P" + property + "=<value>");
        }
        return value;
    }

    /** True when {@code -Pchimera.printBaselines=true} asks tests to print what they would record. */
    public static boolean printBaselines() {
        return Boolean.getBoolean("chimera.printBaselines");
    }

    private static Path existing(String value) {
        Path path = Path.of(value);
        if (!Files.exists(path)) {
            throw new IllegalStateException("No such pack or file: " + path);
        }
        return path;
    }
}
