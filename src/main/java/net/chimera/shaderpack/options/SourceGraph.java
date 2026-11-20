// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack.options;

import net.chimera.shaderpack.DimensionProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The pack's program sources and everything they {@code #include}, as the option scan sees them. Includes
 * are followed whatever condition surrounds them, since they resolve before the preprocessor runs. A target
 * with a leading slash resolves from the shaders root, any other from the including file's folder.
 */
final class SourceGraph {
    private final Map<String, AnnotatedSource> files;
    private final List<String> irisOrder;
    private final List<String> failures;

    private SourceGraph(Map<String, AnnotatedSource> files, List<String> irisOrder, List<String> failures) {
        this.files = files;
        this.irisOrder = List.copyOf(irisOrder);
        this.failures = List.copyOf(failures);
    }

    static SourceGraph empty() {
        return new SourceGraph(Map.of(), List.of(), List.of());
    }

    static SourceGraph read(Path shadersRoot) {
        Map<String, AnnotatedSource> files = new TreeMap<>();
        Map<PackPathKey, String> visited = new HashMap<>();
        List<String> failures = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>(ProgramSourceNames.starts(shadersRoot));
        Set<String> seen = new LinkedHashSet<>(queue);
        while (!queue.isEmpty()) {
            String path = queue.removeLast();
            List<String> lines = readLines(shadersRoot, path);
            if (lines == null) {
                failures.add("SOURCE_INCLUDE_UNREADABLE:" + path);
                continue;
            }
            files.put(path, AnnotatedSource.parse(lines));
            visited.put(new PackPathKey(path), path);
            for (String line : lines) {
                String target = includeTarget(path, line);
                if (target != null && !target.equals(path) && seen.add(target)) queue.add(target);
            }
        }
        return new SourceGraph(files, List.copyOf(visited.values()), failures);
    }

    /** Every readable file, by pack-relative path. */
    Map<String, AnnotatedSource> files() {
        return files;
    }

    /**
     * The files in the order Iris merges their options: a {@code HashMap} keyed by pack path, filled in scan
     * order. The order decides which conflicting names survive.
     */
    List<String> irisOrder() {
        return irisOrder;
    }

    List<String> failures() {
        return failures;
    }

    /** A pack path hashed as Iris hashes its pack paths, {@code Objects.hash(path)}. */
    private record PackPathKey(String path) {
        @Override
        public int hashCode() {
            return Objects.hash(path);
        }
    }

    /** The pack path an {@code #include} line names, resolved against {@code from}; null for other lines. */
    static String includeTarget(String from, String line) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("#include") || trimmed.length() <= "#include ".length()) return null;
        String target = trimmed.substring("#include ".length()).trim();
        if (target.startsWith("\"")) target = target.substring(1);
        if (target.endsWith("\"")) target = target.substring(0, target.length() - 1);
        String base = target.startsWith("/") ? "" : from.substring(0, from.lastIndexOf('/'));
        return normalize(base + "/" + target);
    }

    /** Collapses {@code .} and {@code ..} segments; a {@code ..} at the shaders root stays there, as in Iris. */
    private static String normalize(String path) {
        Deque<String> segments = new ArrayDeque<>();
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) continue;
            if (segment.equals("..")) {
                if (!segments.isEmpty()) segments.removeLast();
            } else {
                segments.addLast(segment);
            }
        }
        return "/" + String.join("/", segments);
    }

    private static List<String> readLines(Path shadersRoot, String path) {
        try {
            Path file = shadersRoot.resolve(path.substring(1)).normalize();
            if (!file.startsWith(shadersRoot.normalize()) || !Files.isRegularFile(file)) return null;
            return List.of(Files.readString(file, StandardCharsets.UTF_8).split("\\R"));
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** Iris's program file names, looked for in the shaders root and each dimension folder. */
    static final class ProgramSourceNames {
        private static final List<String> STAGES = List.of(".vsh", ".tcs", ".tes", ".gsh", ".fsh");
        private static final List<String> CANDIDATES = candidates();

        private ProgramSourceNames() {}

        static List<String> starts(Path shadersRoot) {
            List<String> starts = new ArrayList<>();
            addPresent(shadersRoot, "", starts);
            for (String folder : dimensionFolders(shadersRoot)) addPresent(shadersRoot, folder, starts);
            return starts;
        }

        private static void addPresent(Path shadersRoot, String folder, List<String> starts) {
            Path directory;
            try {
                directory = folder.isEmpty() ? shadersRoot : shadersRoot.resolve(folder);
            } catch (InvalidPathException invalid) {
                // a dimension.properties folder name that is no valid path here (a*b on Windows) names nothing
                return;
            }
            if (!Files.isDirectory(directory)) return;
            Set<String> present = new HashSet<>();
            try (Stream<Path> listing = Files.list(directory)) {
                listing.forEach(entry -> present.add(entry.getFileName().toString()));
            } catch (IOException unreadable) {
                return;
            }
            String prefix = folder.isEmpty() ? "/" : "/" + folder + "/";
            for (String candidate : CANDIDATES) {
                if (present.contains(candidate)) starts.add(prefix + candidate);
            }
        }

        /**
         * The folders dimension.properties names, else the world folders that exist. As in Iris, a
         * dimension.properties that loads any property at all turns that fallback off.
         */
        private static List<String> dimensionFolders(Path shadersRoot) {
            Optional<List<String>> named = DimensionProperties.folderNames(shadersRoot);
            if (named.isPresent()) return named.get();
            List<String> folders = new ArrayList<>();
            for (String world : List.of("world0", "world-1", "world1")) {
                if (Files.isDirectory(shadersRoot.resolve(world))) folders.add(world);
            }
            return folders;
        }

        private static List<String> candidates() {
            List<String> names = new ArrayList<>();
            for (String array : List.of("setup", "begin", "shadowcomp", "prepare", "deferred", "composite")) {
                for (int index = 0; index < 100; index++) addComputeStarts(names, array + (index == 0 ? "" : index));
            }
            addComputeStarts(names, "shadow");
            for (String shadow : List.of("solid", "cutout", "water", "entities", "lightning", "block")) {
                addStarts(names, "shadow_" + shadow);
            }
            for (String gbuffer : List.of("basic", "line", "textured", "textured_lit", "skybasic", "skytextured",
                    "clouds", "terrain", "terrain_solid", "terrain_cutout", "damagedblock", "block",
                    "block_translucent", "beaconbeam", "item", "entities", "entities_translucent", "lightning",
                    "particles", "particles_translucent", "entities_glowing", "armor_glint", "spidereyes", "hand",
                    "weather", "water", "hand_water")) {
                addStarts(names, "gbuffers_" + gbuffer);
            }
            for (String dh : List.of("terrain", "water", "generic", "shadow")) addStarts(names, "dh_" + dh);
            addComputeStarts(names, "final");
            return List.copyOf(names);
        }

        private static void addStarts(List<String> names, String base) {
            for (String stage : STAGES) names.add(base + stage);
        }

        /** The stages, the compute shader and its 26 lettered variants. */
        private static void addComputeStarts(List<String> names, String base) {
            addStarts(names, base);
            names.add(base + ".csh");
            for (char letter = 'a'; letter <= 'z'; letter++) names.add(base + "_" + letter + ".csh");
        }
    }
}
