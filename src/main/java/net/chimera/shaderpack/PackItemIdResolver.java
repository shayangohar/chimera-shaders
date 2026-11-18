// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The pack's item.properties ids, read as Iris's IdMap.parseItemIdMap reads them ({@link IdMapProperties}).
 *
 * Each {@code item.<n>} key lists item names, in {@code minecraft} when no namespace is given. A later line for
 * the same key replaces an earlier one, as in java.util.Properties. Names with a state selector are skipped, and
 * an unlisted item resolves to -1.
 */
public final class PackItemIdResolver {
    /** Iris's id-map default for an unlisted item. */
    public static final int UNMAPPED = -1;

    private static final PackItemIdResolver EMPTY = new PackItemIdResolver(Map.of());

    private final Map<String, Integer> ids;

    private PackItemIdResolver(Map<String, Integer> ids) {
        this.ids = Collections.unmodifiableMap(new TreeMap<>(ids));
    }

    public record ParseResult(PackItemIdResolver resolver, boolean present, List<String> deviations) {
        public ParseResult {
            resolver = resolver == null ? empty() : resolver;
            deviations = List.copyOf(new TreeSet<>(deviations));
        }
    }

    public static PackItemIdResolver empty() {
        return EMPTY;
    }

    public static ParseResult parse(Path shadersDir, Map<String, String> macros) {
        IdMapProperties.Loaded file = IdMapProperties.read(shadersDir, "item.properties", macros);
        if (!file.present()) {
            return new ParseResult(empty(), false, List.of());
        }
        boolean conditionReadFalse = "CONDITION_UNSUPPORTED".equals(file.failure());
        if (file.failure() != null && !conditionReadFalse) {
            return new ParseResult(empty(), true, List.of("ITEM_PROPERTIES_" + file.failure()));
        }
        List<String> deviations = new ArrayList<>();
        // a conditional Chimera cannot evaluate reads as false; the rest of the file still maps
        if (conditionReadFalse) deviations.add("ITEM_PROPERTIES_CONDITION_UNSUPPORTED");
        List<String> invalidKeys = new ArrayList<>();
        Map<String, Integer> ids = IdMapProperties.ids(file.entries(), "item.", invalidKeys);
        invalidKeys.forEach(key -> deviations.add("ITEM_ID_KEY_INVALID:" + key));
        return new ParseResult(new PackItemIdResolver(ids), true, List.copyOf(deviations));
    }

    /** The pack id of an item name such as {@code minecraft:torch}, or {@link #UNMAPPED}. */
    public int resolve(String namespacedId) {
        return namespacedId == null ? UNMAPPED
                : ids.getOrDefault(IdMapProperties.namespaced(namespacedId), UNMAPPED);
    }

    /**
     * Iris's currentRenderedItemId for an item that is not a block item: its listed id, else {@link #UNMAPPED},
     * or 0 without a readable item.properties, as Iris's empty map reads.
     */
    public int renderedItemId(String namespacedId) {
        return this == EMPTY ? 0 : resolve(namespacedId);
    }

    public Map<String, Integer> mappings() {
        return ids;
    }
}
