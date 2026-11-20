// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render.shader;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Iris's batch draw order (batchedentityrendering's GraphTranslucencyRenderOrderManager): by transparency kind,
 * then reverse depth-first finishing order over a graph whose edges join consecutive uses within one group.
 * Unrelated types draw in reverse first-use order, so block entities draw before entities. Iris removes a
 * minimum feedback arc set from a cycle; here the search breaks it where it meets it.
 */
final class IrisBatchOrder<T> {
    private final EnumMap<IrisTransparency, Map<T, Set<T>>> graphs = new EnumMap<>(IrisTransparency.class);
    private final EnumMap<IrisTransparency, T> previous = new EnumMap<>(IrisTransparency.class);
    private boolean inGroup;

    void use(T type, IrisTransparency kind) {
        Map<T, Set<T>> graph = graphs.computeIfAbsent(kind, unused -> new LinkedHashMap<>());
        graph.computeIfAbsent(type, unused -> new LinkedHashSet<>());
        if (!inGroup) return;
        T before = previous.put(kind, type);
        if (before != null && !before.equals(type)) graph.get(before).add(type);
    }

    /** Opens a group unless one is open; true when this call opened it, and must close it. */
    boolean startGroup() {
        if (inGroup) return false;
        previous.clear();
        inGroup = true;
        return true;
    }

    void endGroup() {
        previous.clear();
        inGroup = false;
    }

    void reset() {
        graphs.clear();
        endGroup();
    }

    Map<T, Integer> ranks() {
        Map<T, Integer> ranks = new HashMap<>();
        for (IrisTransparency kind : IrisTransparency.values()) {
            Map<T, Set<T>> graph = graphs.get(kind);
            if (graph == null) continue;
            List<T> finished = new ArrayList<>();
            Set<T> discovered = new HashSet<>();
            for (T vertex : graph.keySet()) visit(graph, vertex, discovered, finished);
            for (int index = finished.size() - 1; index >= 0; index--) ranks.put(finished.get(index), ranks.size());
        }
        return ranks;
    }

    private static <T> void visit(Map<T, Set<T>> graph, T vertex, Set<T> discovered, List<T> finished) {
        if (!discovered.add(vertex)) return;
        for (T next : graph.get(vertex)) visit(graph, next, discovered, finished);
        finished.add(vertex);
    }
}
