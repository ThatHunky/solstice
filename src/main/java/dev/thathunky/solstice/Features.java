package dev.thathunky.solstice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;

/**
 * What this server's Minecraft version can do. Everything version-specific is detected here once at
 * startup; a feature that is missing is switched off with one log line and the rest keeps working.
 *
 * <ul>
 *   <li>World clocks (the season clock, the sky-colour datapack, the day-length rate): 26.1+.
 *       There is no API for clocks, only the {@code /time of <clock>} command, so this goes by version.</li>
 *   <li>Particles and blocks are looked up by name in the registries: {@code tinted_leaves},
 *       {@code firefly} and {@code firefly_bush} arrived in 1.21.5.</li>
 * </ul>
 */
final class Features {

    final McVersion version;
    final boolean worldClocks;
    final Particle cherryLeaves;
    final Particle tintedLeaves;
    final Particle snowflake;
    final Particle firefly;
    final Material fireflyBush;
    /** Feature → "on" or why it is off, for /solstice status. */
    final Map<String, String> report = new LinkedHashMap<>();

    Features(Logger log) {
        String raw = Bukkit.getMinecraftVersion();
        McVersion parsed = McVersion.parse(raw);
        if (parsed == null) {
            log.warning("could not read the Minecraft version from \"" + raw + "\" — assuming the newest");
            parsed = McVersion.NEWEST_VERIFIED;
        }
        version = parsed;
        worldClocks = version.atLeast(McVersion.V26_1);
        cherryLeaves = particle("cherry_leaves");
        tintedLeaves = particle("tinted_leaves");
        snowflake = particle("snowflake");
        firefly = particle("firefly");
        fireflyBush = Material.matchMaterial("FIREFLY_BUSH");
    }

    static Particle particle(String key) {
        try {
            return Registry.PARTICLE_TYPE.get(NamespacedKey.minecraft(key));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Records a feature as on, or off with a reason, logging the reason once. */
    void note(Logger log, String feature, boolean enabled, String offReason) {
        if (enabled) {
            report.put(feature, "on");
        } else {
            report.put(feature, "off (" + offReason + ")");
            if (!offReason.equals("config")) {
                log.info(feature + " is off: " + offReason);
            }
        }
    }

    String summary() {
        StringBuilder b = new StringBuilder();
        report.forEach((k, v) -> b.append(b.isEmpty() ? "" : ", ").append(k).append('=').append(v));
        return b.toString();
    }
}
