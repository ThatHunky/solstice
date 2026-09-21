package dev.thathunky.solstice;

import org.bukkit.HeightMap;
import org.bukkit.block.Block;

/**
 * Only air and leaves above this block, all the way up. Uses the no-leaves heightmap rather than sky
 * light: under a dense canopy sky light drops below 12, while through a window it gets into a house.
 */
final class Sky {

    private Sky() {
    }

    static boolean open(Block b) {
        return b.getWorld().getHighestBlockYAt(b.getX(), b.getZ(), HeightMap.MOTION_BLOCKING_NO_LEAVES) < b.getY();
    }
}
