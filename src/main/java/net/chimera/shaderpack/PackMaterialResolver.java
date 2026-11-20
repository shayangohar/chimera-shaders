// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The block.properties mappings, read as Iris's IdMap reads the file. A state takes the first entry, in key order,
 * that matches it, as Iris's BlockMaterialMapping uses putIfAbsent, so a plain name wins over a later predicate
 * entry for that block. Tag entries come after every block entry, as Iris adds them last.
 */
public final class PackMaterialResolver {
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_./-]+");

    /** One block entry; as in Iris's checkState, a predicate on a property the block lacks is ignored. */
    record Entry(int id, Map<String, String> predicates) {
        boolean matches(Function<String, String> stateValue) {
            for (Map.Entry<String, String> predicate : predicates.entrySet()) {
                String value = stateValue.apply(predicate.getKey());
                if (value != null && !value.equals(predicate.getValue())) {
                    return false;
                }
            }
            return true;
        }
    }

    /** One {@code %tag} entry: the block tag it names, and the entry its states then match. */
    record TagEntry(TagKey<Block> key, Entry entry) {}

    /** The chunk render layer a {@code layer.<name>} line puts its blocks in; that layer's program draws them. */
    public enum Layer {
        SOLID, CUTOUT, CUTOUT_MIPPED, TRANSLUCENT;

        /** The layer a {@code layer.<name>} key names, or null for another name. */
        static Layer named(String name) {
            return switch (name) {
                case "solid" -> SOLID;
                case "cutout" -> CUTOUT;
                case "cutout_mipped" -> CUTOUT_MIPPED;
                case "translucent" -> TRANSLUCENT;
                default -> null;
            };
        }
    }

    private final Map<String, Integer> ids;
    private final Map<String, List<Entry>> entries;
    private final List<TagEntry> tags;
    private final Map<String, Layer> layers;
    private final List<String> deviations;
    private final Map<BlockState, Integer> stateCache = new ConcurrentHashMap<>();
    private final Map<Block, Optional<Layer>> layerCache = new ConcurrentHashMap<>();

    private PackMaterialResolver(Collected collected, Map<String, Layer> layers) {
        this.ids = Collections.unmodifiableMap(new TreeMap<>(collected.ids()));
        Map<String, List<Entry>> copy = new TreeMap<>();
        collected.entries().forEach((name, list) -> copy.put(name, List.copyOf(list)));
        this.entries = Collections.unmodifiableMap(copy);
        this.tags = List.copyOf(collected.tags());
        this.layers = Collections.unmodifiableMap(new TreeMap<>(layers));
        this.deviations = List.copyOf(new TreeSet<>(collected.deviations()));
    }

    /** What one block.properties read collects, entries and layer lines in key order. */
    private record Collected(Map<String, Integer> ids, Map<String, List<Entry>> entries, List<TagEntry> tags,
                             Map<Layer, String> layerLines, Set<String> deviations) {
        Collected() {
            this(new TreeMap<>(), new TreeMap<>(), new ArrayList<>(), new LinkedHashMap<>(), new TreeSet<>());
        }
    }

    public record ParseResult(
            PackMaterialResolver resolver,
            boolean present,
            String sourceHash,
            List<String> deviations
    ) {
        public ParseResult {
            resolver = resolver == null ? empty() : resolver;
            deviations = List.copyOf(new TreeSet<>(deviations));
        }
    }

    public static ParseResult parse(Path shadersDir) {
        return parse(shadersDir, PackEngineDefines.standard());
    }

    /**
     * Reads block.properties as Iris's IdMap does, preprocessed with the id-map {@code macros}. Two
     * {@code block.<id>} keys for one id keep the later value where the first appeared, as in IdMap.parseBlockMap.
     */
    public static ParseResult parse(Path shadersDir, Map<String, String> macros) {
        IdMapProperties.Loaded file = IdMapProperties.read(shadersDir, "block.properties", macros);
        if (!file.present()) {
            return new ParseResult(legacy(), false, null, List.of("BLOCK_PROPERTIES_MISSING"));
        }
        if ("READ_FAILED".equals(file.failure())) {
            return new ParseResult(legacy(), true, null, List.of("BLOCK_PROPERTIES_READ_FAILED"));
        }
        Collected collected = new Collected();
        if (file.failure() != null) {
            collected.deviations().add("BLOCK_PROPERTIES_" + file.failure());
        }
        Map<Integer, String> blocks = new LinkedHashMap<>();
        file.entries().forEach((key, value) -> {
            if (key.startsWith("layer.")) {
                collectLayer(key, value, collected);
            } else if (key.startsWith("block.")) {
                Integer id = mappingId(key.substring("block.".length()));
                if (id == null) {
                    collected.deviations().add("BLOCK_PROPERTIES_INVALID");
                } else {
                    blocks.put(id, value);
                }
            }
        });
        blocks.forEach((id, value) -> collectBlocks(id, value, collected));
        Map<String, Layer> layers = layers(collected.layerLines(), collected.deviations());
        return new ParseResult(new PackMaterialResolver(collected, layers), true, file.sourceHash(),
                List.copyOf(collected.deviations()));
    }

    /**
     * The one resolver with no mapping. ChimeraTerrainPipelines tells resolvers apart by identity, so a new empty
     * one would count as a change and rebuild the live level.
     */
    public static PackMaterialResolver empty() {
        return Empty.RESOLVER;
    }

    /** Built on first use, after the class's own constants. */
    private static final class Empty {
        static final PackMaterialResolver RESOLVER = new PackMaterialResolver(new Collected(), Map.of());
    }

    private static final List<String> LEGACY_COLORS = List.of("white", "orange", "magenta", "light_blue", "yellow",
            "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");
    private static final List<String> LEGACY_WOODS = List.of("oak", "birch", "jungle", "spruce", "acacia", "dark_oak");

    /**
     * The ids Iris gives a pack without a readable block.properties (LegacyIdMap.addLegacyValues). Some names, such
     * as {@code grass}, no longer exist and map nothing, as in Iris.
     */
    static PackMaterialResolver legacy() {
        Map<Integer, String> ids = new LinkedHashMap<>();
        ids.put(1, "stone granite diorite andesite");
        ids.put(2, "grass_block");
        ids.put(4, "cobblestone");
        ids.put(50, "torch");
        ids.put(89, "glowstone");
        ids.put(124, "redstone_lamp");
        ids.put(12, "sand");
        ids.put(24, "sandstone");
        ids.put(41, "gold_block");
        ids.put(42, "iron_block");
        ids.put(57, "diamond_block");
        ids.put(-123, "emerald_block");
        ids.put(35, suffixed(LEGACY_COLORS, "_wool"));
        ids.put(9, "water");
        ids.put(11, "lava");
        ids.put(79, "ice");
        ids.put(18, suffixed(LEGACY_WOODS, "_leaves"));
        ids.put(95, suffixed(LEGACY_COLORS, "_stained_glass"));
        ids.put(160, suffixed(LEGACY_COLORS, "_stained_glass_pane"));
        ids.put(31, "grass seagrass sweet_berry_bush");
        ids.put(59, "wheat carrots potatoes");
        ids.put(37, "dandelion poppy blue_orchid allium azure_bluet red_tulip pink_tulip white_tulip orange_tulip"
                + " oxeye_daisy cornflower lily_of_the_valley wither_rose");
        ids.put(175, "sunflower lilac tall_grass large_fern rose_bush peony tall_seagrass");
        ids.put(51, "fire");
        ids.put(111, "lily_pad");
        Collected collected = new Collected();
        ids.forEach((id, names) -> collectBlocks(id, names, collected));
        return new PackMaterialResolver(collected, Map.of());
    }

    private static String suffixed(List<String> prefixes, String suffix) {
        return String.join(" ", prefixes.stream().map(prefix -> prefix + suffix).toList());
    }

    /**
     * Each block's {@code layer.<name>} layer, by normalised block name. As in Iris, the later key wins and a tag or
     * state selector names no block.
     */
    private static Map<String, Layer> layers(Map<Layer, String> layerLines, Set<String> deviations) {
        Map<String, Layer> layers = new TreeMap<>();
        layerLines.forEach((layer, value) -> {
            for (String token : value.trim().split("\\s+")) {
                if (token.isBlank()) continue;
                String normalized = token.startsWith("%") || token.indexOf('=') >= 0 ? null : normalizeName(token);
                if (normalized == null) {
                    deviations.add(token.startsWith("%") ? "BLOCK_TAG_UNSUPPORTED" : "BLOCK_LAYER_INVALID");
                    continue;
                }
                layers.put(normalized, layer);
            }
        });
        return layers;
    }

    /** The layer the pack's {@code layer.*} lines put this block state's block in; null when none does. */
    public Layer layer(BlockState state) {
        if (state == null || layers.isEmpty()) {
            return null;
        }
        return layerCache.computeIfAbsent(state.getBlock(), block -> {
            try {
                return Optional.ofNullable(layers.get(BuiltInRegistries.BLOCK.getKey(block).toString()));
            } catch (RuntimeException ignored) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    /** The {@code layer.*} overrides, by normalised block name. */
    public Map<String, Layer> layers() {
        return layers;
    }

    public int resolve(BlockState state) {
        if (state == null) {
            return -1;
        }
        return stateCache.computeIfAbsent(state, this::resolveUncached);
    }

    private int resolveUncached(BlockState state) {
        try {
            String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            Function<String, String> stateValue = property -> stateValue(state, property);
            int id = resolveState(name, stateValue);
            return id != -1 ? id : resolveTags(state::is, stateValue);
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    /** The id of the first tag entry, in file order, whose tag holds the state and whose predicates match; else -1. */
    int resolveTags(Predicate<TagKey<Block>> holds, Function<String, String> stateValue) {
        for (TagEntry tag : tags) {
            if (holds.test(tag.key()) && tag.entry().matches(stateValue)) return tag.entry().id();
        }
        return -1;
    }

    private static String stateValue(BlockState state, String propertyName) {
        Property<?> property = state.getBlock().getStateDefinition().getProperty(propertyName);
        return property == null ? null : valueName(state, property);
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    /** A plain-name lookup: the id of the block's first unconditioned mapping. */
    public int resolveName(String name) {
        String normalized = normalizeName(name);
        return normalized == null ? -1 : ids.getOrDefault(normalized, -1);
    }

    /**
     * The id of one block state: the first of the block's entries, in file order, that matches it.
     * {@code stateValue} gives a property's value, or null when the block has no such property.
     */
    public int resolveState(String name, Function<String, String> stateValue) {
        String normalized = normalizeName(name);
        if (normalized == null) {
            return -1;
        }
        for (Entry entry : entries.getOrDefault(normalized, List.of())) {
            if (entry.matches(stateValue)) {
                return entry.id();
            }
        }
        return -1;
    }

    public Map<String, Integer> mappings() {
        return ids;
    }

    public List<String> deviations() {
        return deviations;
    }

    /** Maps each whitespace-separated name or {@code %tag} of one {@code block.<id>} value to the id. */
    private static void collectBlocks(int id, String value, Collected collected) {
        for (String token : value.split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            if (token.startsWith("%")) {
                addTag(token, id, collected);
            } else {
                addEntry(token, id, collected);
            }
        }
    }

    /** Keeps a {@code layer.<name>} key's value under its layer, in key order. */
    private static void collectLayer(String key, String value, Collected collected) {
        Layer layer = Layer.named(key.substring("layer.".length()));
        if (layer == null) {
            collected.deviations().add("BLOCK_LAYER_INVALID");
            return;
        }
        collected.layerLines().put(layer, value);
    }

    /** A {@code block.<id>} key's id as IdMap.parseBlockMap reads it, or null when not an integer in short range. */
    private static Integer mappingId(String text) {
        int id;
        try {
            id = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return null;
        }
        return id < Short.MIN_VALUE || id > Short.MAX_VALUE ? null : id;
    }

    /**
     * One {@code [namespace:]block[:prop=value...]} token, split as Iris's BlockEntry.parse splits it. A predicate
     * not of the form key=value is skipped, and a key's last value wins.
     */
    private static void addEntry(String token, int id, Collected collected) {
        Token parsed = parseToken(token, false, collected.deviations());
        if (parsed == null) return;
        collected.entries().computeIfAbsent(parsed.name(), key -> new ArrayList<>())
                .add(new Entry(id, parsed.predicates()));
        if (parsed.hasPredicates()) return;
        Integer previous = collected.ids().putIfAbsent(parsed.name(), id);
        if (previous != null && previous != id) {
            collected.deviations().add("BLOCK_MAPPING_CONFLICT");
        }
    }

    /**
     * One {@code %[namespace:]tag[:prop=value...]} token. Its name is lower-cased, as BlockMaterialMapping.addTag
     * ignores case, but its predicates compare exactly.
     */
    private static void addTag(String token, int id, Collected collected) {
        Token parsed = parseToken(token.replace("%", ""), true, collected.deviations());
        if (parsed == null) return;
        TagKey<Block> key = TagKey.create(Registries.BLOCK, ResourceLocation.parse(parsed.name()));
        collected.tags().add(new TagEntry(key, new Entry(id, parsed.predicates())));
    }

    /** A token's normalised name and predicates, and whether it named any predicate segment. */
    private record Token(String name, Map<String, String> predicates, boolean hasPredicates) {}

    /**
     * Splits a token as BlockEntry.parse does; null, with a deviation, when its name is invalid.
     * {@code anyCase} reads the name ignoring case.
     */
    private static Token parseToken(String token, boolean anyCase, Set<String> deviations) {
        String[] segments = token.split(":");
        int first = segments.length > 1 && segments[1].indexOf('=') >= 0 ? 1 : Math.min(segments.length, 2);
        String name = first == 1 ? segments[0] : segments[0] + ":" + segments[1];
        String normalized = normalizeName(anyCase ? name.toLowerCase(Locale.ROOT) : name);
        if (normalized == null) {
            deviations.add("BLOCK_PROPERTIES_INVALID");
            return null;
        }
        Map<String, String> predicates = new TreeMap<>();
        for (int index = first; index < segments.length; index++) {
            String[] parts = segments[index].split("=");
            if (parts.length != 2) {
                deviations.add("BLOCK_PROPERTIES_INVALID");
                continue;
            }
            predicates.put(parts[0], parts[1]);
        }
        return new Token(normalized, Collections.unmodifiableMap(predicates), first < segments.length);
    }

    private static String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        int colon = name.indexOf(':');
        if (colon < 0) {
            if (!PATH.matcher(name).matches()) {
                return null;
            }
            return "minecraft:" + name;
        }
        if (colon != name.lastIndexOf(':')) {
            return null;
        }
        String namespace = name.substring(0, colon);
        String path = name.substring(colon + 1);
        if (!NAMESPACE.matcher(namespace).matches() || !PATH.matcher(path).matches()) {
            return null;
        }
        return namespace + ":" + path;
    }
}
