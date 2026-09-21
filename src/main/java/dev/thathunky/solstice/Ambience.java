package dev.thathunky.solstice;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.entity.Player;

/**
 * Season particles only where they belong. Random blocks near each player are probed; a particle
 * appears only under natural leaves (or above grass at night) and only where the sky is open above.
 * Nothing in caves or under roofs. Only that player sees the particles, so crowds don't multiply them.
 */
final class Ambience implements Runnable {

    private final List<World> worlds;
    private final SeasonState state;
    private final Features features;
    private final Bedrock bedrock;
    private final int perPlayer;
    private final int radius;
    private final boolean toBedrock;
    private final Color autumn;
    private final Random random = new Random();

    Ambience(List<World> worlds, SeasonState state, Features features, Bedrock bedrock, Settings s) {
        this.worlds = worlds;
        this.state = state;
        this.features = features;
        this.bedrock = bedrock;
        this.perPlayer = s.particlesPerPlayer();
        this.radius = s.particleRadius();
        this.toBedrock = s.particlesBedrock();
        this.autumn = Color.fromRGB(Integer.parseInt(s.seasonColors()[Season.AUTUMN.id()].substring(1), 16));
    }

    /** The particle for a season on this server, or null when this version doesn't have it. */
    Particle particleFor(Season season) {
        return switch (season) {
            case SPRING -> features.cherryLeaves;
            case AUTUMN -> features.tintedLeaves;
            case WINTER -> features.snowflake;
            case SUMMER -> features.firefly;
        };
    }

    @Override
    public void run() {
        Season season = state.current();
        Particle particle = particleFor(season);
        if (particle == null) {
            return;
        }
        for (World world : worlds) {
            boolean night = !SeasonCalendar.isDay(world.getTime());
            if (season == Season.SUMMER && !night) {
                continue;
            }
            for (Player p : world.getPlayers()) {
                if (p.getGameMode() == GameMode.SPECTATOR || !toBedrock && bedrock.is(p.getUniqueId())) {
                    continue;
                }
                Location at = p.getLocation();
                for (int i = 0; i < perPlayer; i++) {
                    Block spot = spot(sample(world, at), season, night);
                    if (spot == null) {
                        continue;
                    }
                    switch (season) {
                        case AUTUMN -> p.spawnParticle(particle, jitter(world, spot), 1, 0, 0, 0, 0, autumn);
                        case SUMMER -> p.spawnParticle(particle, spot.getX() + 0.5, spot.getY() + 0.3, spot.getZ() + 0.5,
                                1, 0.3, 0.2, 0.3, 0);
                        default -> p.spawnParticle(particle, jitter(world, spot), 1, 0, 0, 0, 0);
                    }
                }
            }
        }
    }

    /** /solstice probe: how many particles each x,z column near a point would get over {@code rounds} passes. */
    Map<String, Integer> probe(Location at, Season season, boolean night, int rounds) {
        Map<String, Integer> hits = new TreeMap<>();
        for (int i = 0; i < rounds * perPlayer; i++) {
            Block spot = spot(sample(at.getWorld(), at), season, night);
            if (spot != null) {
                hits.merge(spot.getX() + " " + spot.getZ(), 1, Integer::sum);
            }
        }
        return hits;
    }

    private Block sample(World world, Location at) {
        int x = at.getBlockX() + random.nextInt(radius * 2 + 1) - radius;
        int y = Math.clamp(at.getBlockY() + random.nextInt(radius * 2 + 1) - radius, world.getMinHeight(), world.getMaxHeight() - 2);
        int z = at.getBlockZ() + random.nextInt(radius * 2 + 1) - radius;
        return world.isChunkLoaded(x >> 4, z >> 4) ? world.getBlockAt(x, y, z) : null;
    }

    /** The air block where this season's particle appears, or null. */
    private Block spot(Block b, Season season, boolean night) {
        if (b == null) {
            return null;
        }
        return switch (season) {
            case SPRING -> naturalLeaves(b, Material.CHERRY_LEAVES, Material.FLOWERING_AZALEA_LEAVES) ? below(b) : null;
            case AUTUMN -> b.getBlockData() instanceof Leaves l && !l.isPersistent() ? below(b) : null;
            case WINTER -> random.nextInt(3) == 0 && naturalLeaves(b, Material.SPRUCE_LEAVES) ? below(b) : null;
            case SUMMER -> night && meadow(b) ? above(b) : null;
        };
    }

    private static boolean naturalLeaves(Block b, Material... types) {
        for (Material t : types) {
            if (b.getType() == t) {
                return b.getBlockData() instanceof Leaves l && !l.isPersistent();
            }
        }
        return false;
    }

    /** The block under a leaf, if it is air under open sky (not a roof, not a cave). */
    private static Block below(Block leaf) {
        Block below = leaf.getRelative(BlockFace.DOWN);
        return below.getType().isAir() && Sky.open(below) ? below : null;
    }

    /** The air above a plant, under open sky. */
    private static Block above(Block plant) {
        Block up = plant.getRelative(BlockFace.UP);
        return up.getType().isAir() && Sky.open(up) ? up : null;
    }

    private Location jitter(World world, Block spot) {
        return new Location(world, spot.getX() + random.nextDouble(), spot.getY() + 0.9, spot.getZ() + random.nextDouble());
    }

    /** Grass, flowers, a firefly bush — not a bare grass block, or fireflies would cover every meadow. */
    private boolean meadow(Block plant) {
        Material m = plant.getType();
        return m == Material.SHORT_GRASS || m == Material.TALL_GRASS || features.fireflyBush != null && m == features.fireflyBush
                || Tag.FLOWERS.isTagged(m);
    }
}
