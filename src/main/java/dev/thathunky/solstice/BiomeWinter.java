package dev.thathunky.solstice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Winter counterparts of biomes, and packing a chunk's real biomes into its persistent data. */
final class BiomeWinter {

    record Packed(String palette, byte[] indices) {
    }

    private static final Map<String, String> DEFAULTS = new HashMap<>();

    static {
        put("minecraft:snowy_plains", "plains", "sunflower_plains", "meadow");
        put("minecraft:snowy_taiga", "forest", "flower_forest", "birch_forest", "old_growth_birch_forest", "dark_forest",
                "taiga", "old_growth_pine_taiga", "old_growth_spruce_taiga", "cherry_grove", "swamp", "mangrove_swamp");
        put("minecraft:frozen_river", "river");
        put("minecraft:frozen_ocean", "ocean", "cold_ocean", "lukewarm_ocean");
        put("minecraft:deep_frozen_ocean", "deep_ocean", "deep_cold_ocean", "deep_lukewarm_ocean");
        put("minecraft:snowy_beach", "beach");
    }

    private final Map<String, String> table;

    /** @param extra more biome → winter biome pairs from the config; they override the built-in table */
    BiomeWinter(Map<String, String> extra) {
        table = new HashMap<>(DEFAULTS);
        extra.forEach((k, v) -> table.put(normalize(k), v == null || v.isBlank() ? null : normalize(v)));
    }

    private static void put(String winter, String... summer) {
        for (String s : summer) {
            DEFAULTS.put("minecraft:" + s, winter);
        }
    }

    private static String normalize(String key) {
        String k = key.trim().toLowerCase(java.util.Locale.ROOT);
        return k.contains(":") ? k : "minecraft:" + k;
    }

    /** The winter biome's key, or null if this biome does not change in winter. */
    String winterOf(String key) {
        return table.get(key);
    }

    /** Cells → comma-separated palette plus one index byte per cell (a chunk holds at most 256 biomes). */
    static Packed pack(String[] cells) {
        List<String> palette = new ArrayList<>();
        Map<String, Integer> index = new HashMap<>();
        byte[] out = new byte[cells.length];
        for (int i = 0; i < cells.length; i++) {
            Integer at = index.get(cells[i]);
            if (at == null) {
                at = palette.size();
                if (at > 255) {
                    throw new IllegalStateException("more than 256 biomes in one chunk");
                }
                palette.add(cells[i]);
                index.put(cells[i], at);
            }
            out[i] = (byte) (int) at;
        }
        return new Packed(String.join(",", palette), out);
    }

    static String[] unpack(Packed packed) {
        String[] palette = packed.palette().split(",");
        String[] cells = new String[packed.indices().length];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = palette[packed.indices()[i] & 0xff];
        }
        return cells;
    }
}
