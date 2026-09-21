package dev.thathunky.solstice;

import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Chunk and player data written by this plugin's predecessor (MatsuriSeasons) under the
 * {@code matsuri:} and {@code matsuriseasons:} namespaces. Read once and rewritten under
 * {@code solstice:}; the new key wins if both exist.
 */
final class LegacyKeys {

    static final NamespacedKey WINTER = NamespacedKey.fromString("matsuri:winter");
    static final NamespacedKey PALETTE = NamespacedKey.fromString("matsuri:biomes_palette");
    static final NamespacedKey ORIGINAL = NamespacedKey.fromString("matsuri:biomes_orig");
    static final NamespacedKey FORMED = NamespacedKey.fromString("matsuri:formed");
    /** Player key: the season id a player has already been shown. */
    static final NamespacedKey SEEN = NamespacedKey.fromString("matsuriseasons:season_seen");

    private LegacyKeys() {
    }

    static void migrate(PersistentDataContainer pdc) {
        move(pdc, WINTER, WinterWorld.WINTER, PersistentDataType.INTEGER);
        move(pdc, PALETTE, WinterWorld.PALETTE, PersistentDataType.STRING);
        move(pdc, ORIGINAL, WinterWorld.ORIGINAL, PersistentDataType.BYTE_ARRAY);
        move(pdc, FORMED, WinterWorld.FORMED, PersistentDataType.INTEGER_ARRAY);
    }

    static <T> void move(PersistentDataContainer pdc, NamespacedKey from, NamespacedKey to, PersistentDataType<?, T> type) {
        if (!pdc.has(from, type)) {
            return;
        }
        T value = pdc.get(from, type);
        if (value != null && !pdc.has(to, type)) {
            pdc.set(to, type, value);
        }
        pdc.remove(from);
    }
}
