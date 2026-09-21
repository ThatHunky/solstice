package dev.thathunky.solstice;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Snowable;
import org.bukkit.block.data.type.Snow;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Winter in one world. A chunk's biomes switch to their winter counterparts when it loads (and, when
 * the season changes, the chunks that are already loaded are worked through a few per tick). The real
 * biomes and every snow layer or ice block that formed are written into the chunk's persistent data,
 * and in spring everything recorded goes back. No chunk that isn't loaded anyway is ever touched.
 */
final class WinterWorld implements Listener, Runnable {

    static final NamespacedKey WINTER = new NamespacedKey("solstice", "winter");
    static final NamespacedKey PALETTE = new NamespacedKey("solstice", "biomes_palette");
    static final NamespacedKey ORIGINAL = new NamespacedKey("solstice", "biomes_orig");
    static final NamespacedKey FORMED = new NamespacedKey("solstice", "formed");

    private final Plugin plugin;
    private final SeasonState state;
    private final World world;
    private final BiomeWinter table;
    private final int chunksPerTick;
    private final int formedLimit;
    private final ArrayDeque<Long> queue = new ArrayDeque<>();
    private final Map<String, Biome> biomes = new HashMap<>();
    private final Map<Biome, String> keys = new HashMap<>();
    private final Map<Biome, Biome> winters = new HashMap<>();
    private Season last;
    private int swept;
    private long sweptNanos;

    WinterWorld(Plugin plugin, SeasonState state, World world, BiomeWinter table, int chunksPerTick, int formedLimit) {
        this.plugin = plugin;
        this.state = state;
        this.world = world;
        this.table = table;
        this.chunksPerTick = chunksPerTick;
        this.formedLimit = formedLimit;
    }

    /** Every tick: when the season changes (and on the first run, for chunks loaded before the plugin), sweep loaded chunks. */
    @Override
    public void run() {
        Season now = state.current();
        if (now != last) {
            last = now;
            queue.clear();
            for (Chunk c : world.getLoadedChunks()) {
                queue.add((long) c.getZ() << 32 | c.getX() & 0xffffffffL);
            }
        }
        if (queue.isEmpty()) {
            return;
        }
        long start = System.nanoTime();
        for (int i = 0; i < chunksPerTick && !queue.isEmpty(); i++) {
            long key = queue.poll();
            int x = (int) key;
            int z = (int) (key >>> 32);
            if (world.isChunkLoaded(x, z)) {
                settle(world.getChunkAt(x, z));
                swept++;
            }
        }
        sweptNanos += System.nanoTime() - start;
        if (queue.isEmpty()) {
            plugin.getLogger().info(String.format(Locale.ROOT, "chunk sweep in %s (%s): %d chunks in %.0f ms, %.2f ms per chunk",
                    world.getName(), last.key, swept, sweptNanos / 1e6, swept == 0 ? 0 : sweptNanos / 1e6 / swept));
            swept = 0;
            sweptNanos = 0;
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (event.getWorld() == world) {
            settle(event.getChunk());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        Block b = event.getBlock();
        Material to = event.getNewState().getType();
        if (b.getWorld() != world || to != Material.SNOW && to != Material.ICE) {
            return;
        }
        PersistentDataContainer pdc = b.getChunk().getPersistentDataContainer();
        LegacyKeys.migrate(pdc);
        if (state.current() != Season.WINTER) {
            // The chunk is still wintry (waiting for its sweep after a start or a season change) — unrecorded snow would stay forever
            if (pdc.has(WINTER, PersistentDataType.INTEGER)) {
                event.setCancelled(true);
            }
            return;
        }
        if (to == Material.ICE && irrigates(b)) {
            event.setCancelled(true);
            return;
        }
        int[] had = pdc.getOrDefault(FORMED, PersistentDataType.INTEGER_ARRAY, new int[0]);
        int[] next = FormedLog.add(had, FormedLog.encode(b.getX() & 15, b.getY() - world.getMinHeight(), b.getZ() & 15), formedLimit);
        if (next == null) {
            event.setCancelled(true);
        } else if (next != had) {
            pdc.set(FORMED, PersistentDataType.INTEGER_ARRAY, next);
        }
    }

    /** /solstice chunk: a chunk's winter state, by block coordinates. Null if the chunk isn't loaded. */
    Map<String, String> describe(int blockX, int blockZ) {
        if (!world.isChunkLoaded(blockX >> 4, blockZ >> 4)) {
            return null;
        }
        PersistentDataContainer pdc = world.getChunkAt(blockX >> 4, blockZ >> 4).getPersistentDataContainer();
        LegacyKeys.migrate(pdc);
        return Map.of(
                "winter", String.valueOf(pdc.get(WINTER, PersistentDataType.INTEGER)),
                "palette", String.valueOf(pdc.get(PALETTE, PersistentDataType.STRING)),
                "formed", String.valueOf(pdc.getOrDefault(FORMED, PersistentDataType.INTEGER_ARRAY, new int[0]).length),
                "biome", world.getBiome(blockX, world.getHighestBlockYAt(blockX, blockZ), blockZ).getKey().toString());
    }

    private void settle(Chunk chunk) {
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        LegacyKeys.migrate(pdc);
        Integer mark = pdc.get(WINTER, PersistentDataType.INTEGER);
        boolean changed = false;
        if (state.current() == Season.WINTER) {
            int cycle = state.position().cycle();
            if (mark != null && mark == cycle) {
                return;
            }
            if (mark != null) {
                changed = restoreBiomes(chunk);
            }
            changed |= winterize(chunk, cycle);
        } else {
            if (mark != null) {
                changed = restoreBiomes(chunk);
            }
            if (pdc.has(FORMED, PersistentDataType.INTEGER_ARRAY)) {
                int cx = chunk.getX();
                int cz = chunk.getZ();
                // Don't change blocks in the middle of a chunk load — next tick
                plugin.getServer().getScheduler().runTask(plugin, () -> melt(cx, cz));
            }
        }
        if (changed && !chunk.getPlayersSeeingChunk().isEmpty()) {
            world.refreshChunk(chunk.getX(), chunk.getZ());
        }
    }

    private boolean winterize(Chunk chunk, int cycle) {
        int minY = world.getMinHeight();
        int cellsY = (world.getMaxHeight() - minY) / 4;
        int bx = chunk.getX() << 4;
        int bz = chunk.getZ() << 4;
        // A snapshot reads biomes without a chunk lookup per cell: 1.15 → ~0.45 ms per chunk (measured 2026-09-14)
        ChunkSnapshot snap = chunk.getChunkSnapshot(false, true, false);
        String[] original = new String[cellsY * 16];
        boolean changed = false;
        for (int qy = 0; qy < cellsY; qy++) {
            for (int qz = 0; qz < 4; qz++) {
                for (int qx = 0; qx < 4; qx++) {
                    int y = minY + qy * 4;
                    Biome now = snap.getBiome(qx * 4, y, qz * 4);
                    original[(qy * 4 + qz) * 4 + qx] = key(now);
                    Biome winter = winterOf(now);
                    if (winter != null) {
                        world.setBiome(bx + qx * 4, y, bz + qz * 4, winter);
                        changed = true;
                    }
                }
            }
        }
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        if (changed) {
            BiomeWinter.Packed packed = BiomeWinter.pack(original);
            pdc.set(PALETTE, PersistentDataType.STRING, packed.palette());
            pdc.set(ORIGINAL, PersistentDataType.BYTE_ARRAY, packed.indices());
        }
        pdc.set(WINTER, PersistentDataType.INTEGER, cycle);
        return changed;
    }

    /** Puts the real biome back only where its winter counterpart is still there (manual edits are left alone). */
    private boolean restoreBiomes(Chunk chunk) {
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        String palette = pdc.get(PALETTE, PersistentDataType.STRING);
        byte[] indices = pdc.get(ORIGINAL, PersistentDataType.BYTE_ARRAY);
        pdc.remove(WINTER);
        pdc.remove(PALETTE);
        pdc.remove(ORIGINAL);
        int minY = world.getMinHeight();
        int cellsY = (world.getMaxHeight() - minY) / 4;
        if (palette == null || indices == null || indices.length != cellsY * 16) {
            return false;
        }
        String[] original = BiomeWinter.unpack(new BiomeWinter.Packed(palette, indices));
        int bx = chunk.getX() << 4;
        int bz = chunk.getZ() << 4;
        ChunkSnapshot snap = chunk.getChunkSnapshot(false, true, false);
        boolean changed = false;
        for (int qy = 0; qy < cellsY; qy++) {
            for (int qz = 0; qz < 4; qz++) {
                for (int qx = 0; qx < 4; qx++) {
                    Biome was = biome(original[(qy * 4 + qz) * 4 + qx]);
                    Biome winter = winterOf(was);
                    int y = minY + qy * 4;
                    if (winter != null && winter == snap.getBiome(qx * 4, y, qz * 4)) {
                        world.setBiome(bx + qx * 4, y, bz + qz * 4, was);
                        changed = true;
                    }
                }
            }
        }
        return changed;
    }

    /** Recorded snow (1–2 layers) becomes air, recorded ice becomes water; the log is cleared. */
    private void melt(int cx, int cz) {
        if (!world.isChunkLoaded(cx, cz) || state.current() == Season.WINTER) {
            return;
        }
        Chunk chunk = world.getChunkAt(cx, cz);
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        int[] log = pdc.get(FORMED, PersistentDataType.INTEGER_ARRAY);
        if (log == null) {
            return;
        }
        int minY = world.getMinHeight();
        for (int code : log) {
            Block b = chunk.getBlock(FormedLog.x(code), minY + FormedLog.y(code), FormedLog.z(code));
            if (b.getBlockData() instanceof Snow snow && snow.getLayers() <= 2) {
                b.setType(Material.AIR, false);
                Block below = b.getRelative(BlockFace.DOWN);
                if (below.getBlockData() instanceof Snowable ground && ground.isSnowy()) {
                    ground.setSnowy(false);
                    below.setBlockData(ground, false);
                }
            } else if (b.getType() == Material.ICE) {
                b.setType(Material.WATER, false);
            }
        }
        pdc.remove(FORMED);
    }

    /** Water that irrigates farmland: farmland within 4 blocks horizontally, level with the water or one above. */
    private boolean irrigates(Block water) {
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                if (!world.isChunkLoaded(water.getX() + dx >> 4, water.getZ() + dz >> 4)) {
                    continue;
                }
                for (int dy = 0; dy <= 1; dy++) {
                    if (water.getRelative(dx, dy, dz).getType() == Material.FARMLAND) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private Biome biome(String key) {
        NamespacedKey k = NamespacedKey.fromString(key);
        return biomes.computeIfAbsent(key, x -> k == null ? null : Registry.BIOME.get(k));
    }

    private String key(Biome b) {
        return keys.computeIfAbsent(b, k -> k.getKey().toString());
    }

    /** The winter biome, or null; cached per biome, so no strings per cell. */
    private Biome winterOf(Biome b) {
        if (b == null) {
            return null;
        }
        if (!winters.containsKey(b)) {
            String w = table.winterOf(key(b));
            winters.put(b, w == null ? null : biome(w));
        }
        return winters.get(b);
    }
}
