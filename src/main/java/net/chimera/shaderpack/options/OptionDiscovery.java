// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack.options;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** A pack's discovered options and the source files that declare them. */
public final class OptionDiscovery {
    private final SourceGraph graph;
    private final OptionSet options;
    private final Set<String> ambiguous;

    private OptionDiscovery(SourceGraph graph, OptionSet options, Set<String> ambiguous) {
        this.graph = graph;
        this.options = options;
        this.ambiguous = ambiguous;
    }

    /** No sources and no options. */
    static OptionDiscovery none() {
        return new OptionDiscovery(SourceGraph.empty(), OptionSet.empty(), Set.of());
    }

    /**
     * Reads every program source and its includes, as Iris does: an {@code #ifdef} anywhere in the pack
     * confirms a toggle, and files merge in Iris's order, which decides the names a conflict drops.
     */
    public static OptionDiscovery discover(Path shadersRoot) {
        SourceGraph graph = SourceGraph.read(shadersRoot);
        Set<String> tested = new HashSet<>();
        graph.files().values().forEach(source -> tested.addAll(source.testedNames()));
        OptionSet.Builder builder = new OptionSet.Builder();
        for (String file : graph.irisOrder()) {
            OptionSet.Builder declared = new OptionSet.Builder();
            graph.files().get(file).options().forEach((line, option) -> {
                if (option instanceof ShaderOption.Choice || tested.contains(option.name())) {
                    declared.add(new OptionSet.Location(file, line), option);
                }
            });
            builder.addAll(declared);
        }
        return new OptionDiscovery(graph, builder.build(), Set.copyOf(builder.ambiguous()));
    }

    public OptionSet options() {
        return options;
    }

    /** Names dropped because two declarations disagreed on their default. */
    public Set<String> ambiguous() {
        return ambiguous;
    }

    /** Includes that could not be read. */
    public List<String> failures() {
        return graph.failures();
    }

    /** The files {@code values} changes, by pack-relative path, joined with {@code \n} as the pack loader does. */
    public Map<String, String> editedFiles(OptionValues values) {
        Map<String, String> edited = new TreeMap<>();
        graph.files().forEach((path, source) -> {
            List<String> lines = source.apply(values);
            if (lines != source.lines()) edited.put(path, String.join("\n", lines) + "\n");
        });
        return Collections.unmodifiableMap(edited);
    }
}
