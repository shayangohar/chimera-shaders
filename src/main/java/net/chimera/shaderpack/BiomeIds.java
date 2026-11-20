// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * One table for both the {@code biome} input and the {@code BIOME_*} constants, so they always agree. Iris
 * numbers vanilla biomes in Biomes' registration order, and packs also write the numbers directly.
 */
public final class BiomeIds {
    private static final String PREFIX = "BIOME_";
    private static final String VANILLA_NAMESPACE = "minecraft";

    /** Object2IntMap.getInt's default in Iris, also the_void's number. */
    public static final int UNKNOWN = 0;

    /** In registration order, which gives their numbers. */
    private static final List<String> VANILLA = List.of(
            "the_void", "plains", "sunflower_plains", "snowy_plains", "ice_spikes", "desert", "swamp",
            "mangrove_swamp", "forest", "flower_forest", "birch_forest", "dark_forest",
            "old_growth_birch_forest", "old_growth_pine_taiga", "old_growth_spruce_taiga", "taiga",
            "snowy_taiga", "savanna", "savanna_plateau", "windswept_hills", "windswept_gravelly_hills",
            "windswept_forest", "windswept_savanna", "jungle", "sparse_jungle", "bamboo_jungle",
            "badlands", "eroded_badlands", "wooded_badlands", "meadow", "cherry_grove", "grove",
            "snowy_slopes", "frozen_peaks", "jagged_peaks", "stony_peaks", "river", "frozen_river",
            "beach", "snowy_beach", "stony_shore", "warm_ocean", "lukewarm_ocean", "deep_lukewarm_ocean",
            "ocean", "deep_ocean", "cold_ocean", "deep_cold_ocean", "frozen_ocean", "deep_frozen_ocean",
            "mushroom_fields", "dripstone_caves", "lush_caves", "deep_dark", "nether_wastes",
            "warped_forest", "crimson_forest", "soul_sand_valley", "basalt_deltas", "the_end",
            "end_highlands", "end_midlands", "small_end_islands", "end_barrens");
    /**
     * Later biomes packs name. Iris 1.21.1 drops such a uniform, which reads 0; a number no 1.21.1 biome reads
     * gives the same 0 without refusing the program.
     */
    private static final List<String> LATER = List.of("pale_garden");

    private static final Map<String, Integer> IDS = ids();
    /** Iris's IrisDefines symbols. */
    private static final Map<String, Integer> SYMBOLS = symbols();
    private static final PackExpression.Constants CONSTANTS = buildConstants();
    private static final Map<String, String> MACROS = buildMacros();

    private BiomeIds() {}

    public static PackExpression.Constants constants() {
        return CONSTANTS;
    }

    /**
     * As Iris defines them in every file it preprocesses. Only 1.21.1 biomes, so an {@code #ifdef} of a later
     * one stays false, as in Iris.
     */
    public static Map<String, String> macros() {
        return MACROS;
    }

    /** {@link #UNKNOWN} when no constant names it, modded biomes included. */
    public static int id(ResourceLocation key) {
        if (key == null || !VANILLA_NAMESPACE.equals(key.getNamespace())) {
            return UNKNOWN;
        }
        Integer id = IDS.get(key.getPath());
        return id == null ? UNKNOWN : id;
    }

    private static Map<String, Integer> ids() {
        Map<String, Integer> result = new TreeMap<>();
        int id = 0;
        for (String name : VANILLA) {
            result.put(name, id++);
        }
        for (String name : LATER) {
            result.put(name, id++);
        }
        return Map.copyOf(result);
    }

    /** In Iris's {@code BiomeCategories} order; the {@code CAT_*} constants are these ordinals. */
    public enum Category {
        NONE, TAIGA, EXTREME_HILLS, JUNGLE, MESA, PLAINS, SAVANNA, ICY, THE_END, BEACH, FOREST,
        OCEAN, DESERT, RIVER, SWAMP, MUSHROOM, NETHER, MOUNTAIN, UNDERGROUND
    }

    /**
     * Iris 1.21.1's getBiomeCategory order: the first matching tag wins. Iris never answers {@code SAVANNA} on
     * this version.
     */
    private static final List<Map.Entry<TagKey<Biome>, Category>> CATEGORY_TAGS = List.of(
            Map.entry(BiomeTags.WITHOUT_WANDERING_TRADER_SPAWNS, Category.NONE),
            Map.entry(BiomeTags.HAS_VILLAGE_SNOWY, Category.ICY),
            Map.entry(BiomeTags.IS_HILL, Category.EXTREME_HILLS),
            Map.entry(BiomeTags.IS_TAIGA, Category.TAIGA),
            Map.entry(BiomeTags.IS_OCEAN, Category.OCEAN),
            Map.entry(BiomeTags.IS_JUNGLE, Category.JUNGLE),
            Map.entry(BiomeTags.IS_FOREST, Category.FOREST),
            Map.entry(BiomeTags.IS_BADLANDS, Category.MESA),
            Map.entry(BiomeTags.IS_NETHER, Category.NETHER),
            Map.entry(BiomeTags.IS_END, Category.THE_END),
            Map.entry(BiomeTags.IS_BEACH, Category.BEACH),
            Map.entry(BiomeTags.HAS_DESERT_PYRAMID, Category.DESERT),
            Map.entry(BiomeTags.IS_RIVER, Category.RIVER),
            Map.entry(BiomeTags.HAS_CLOSER_WATER_FOG, Category.SWAMP),
            Map.entry(BiomeTags.PLAYS_UNDERWATER_MUSIC, Category.UNDERGROUND),
            Map.entry(BiomeTags.WITHOUT_ZOMBIE_SIEGES, Category.MUSHROOM),
            Map.entry(BiomeTags.IS_MOUNTAIN, Category.MOUNTAIN));

    /** {@code PLAINS} when no tag matches; {@code NONE} for no biome. */
    public static Category category(Holder<Biome> biome) {
        if (biome == null) return Category.NONE;
        for (Map.Entry<TagKey<Biome>, Category> tag : CATEGORY_TAGS) {
            if (biome.is(tag.getKey())) return tag.getValue();
        }
        return Category.PLAINS;
    }

    private static String symbol(String biome) {
        return PREFIX + biome.toUpperCase(Locale.ROOT);
    }

    private static Map<String, Integer> symbols() {
        Map<String, Integer> result = new TreeMap<>();
        VANILLA.forEach(name -> result.put(symbol(name), IDS.get(name)));
        for (Category category : Category.values()) {
            result.put("CAT_" + category.name(), category.ordinal());
        }
        result.put("PPT_NONE", 0);
        result.put("PPT_RAIN", 1);
        result.put("PPT_SNOW", 2);
        return Map.copyOf(result);
    }

    /** Iris's symbols plus {@link #LATER}. */
    private static PackExpression.Constants buildConstants() {
        Map<String, Double> values = new TreeMap<>();
        SYMBOLS.forEach((name, value) -> values.put(name, (double) value));
        LATER.forEach(name -> values.put(symbol(name), (double) IDS.get(name)));
        return name -> {
            Double value = values.get(name);
            return value == null ? Double.NaN : value;
        };
    }

    private static Map<String, String> buildMacros() {
        Map<String, String> result = new TreeMap<>();
        SYMBOLS.forEach((name, value) -> result.put(name, Integer.toString(value)));
        return Collections.unmodifiableMap(result);
    }
}
