package dev.thathunky.solstice;

import java.util.Random;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Animals;
import org.bukkit.entity.FishHook;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.player.PlayerFishEvent;

/** Crops, animals and fishing by season, in the configured worlds only. Decisions are in HarvestRules. */
final class Harvest implements Listener {

    private final SeasonState state;
    private final Set<World> worlds;
    private final HarvestRules rules;
    private final boolean crops;
    private final boolean animals;
    private final boolean fishing;
    private final Random random = new Random();

    Harvest(SeasonState state, Set<World> worlds, HarvestRules rules, boolean crops, boolean animals, boolean fishing) {
        this.state = state;
        this.worlds = worlds;
        this.rules = rules;
        this.crops = crops;
        this.animals = animals;
        this.fishing = fishing;
    }

    @EventHandler(ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) {
        Block block = event.getBlock();
        BlockState next = event.getNewState();
        if (!crops || !worlds.contains(block.getWorld()) || !(next.getBlockData() instanceof Ageable age)
                || block.getRelative(BlockFace.DOWN).getType() != Material.FARMLAND) {
            return;
        }
        switch (rules.crop(state.current(), Sky.open(block), random.nextDouble())) {
            case CANCEL -> event.setCancelled(true);
            case BONUS -> {
                // The pitcher plant is two blocks tall — an extra stage breaks its upper half
                if (next.getType() != Material.PITCHER_CROP && age.getAge() < age.getMaximumAge()) {
                    age.setAge(age.getAge() + 1);
                    next.setBlockData(age);
                }
            }
            case NORMAL -> {
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        if (!animals || !worlds.contains(event.getEntity().getWorld()) || !(event.getMother() instanceof Animals)) {
            return;
        }
        Season season = state.current();
        if (rules.cancelBreed(season, random.nextDouble())) {
            event.setCancelled(true);
        } else if (event.getEntity() instanceof org.bukkit.entity.Ageable baby) {
            baby.setAge(rules.babyAge(season, baby.getAge()));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (!fishing || event.getState() != PlayerFishEvent.State.FISHING || !worlds.contains(event.getPlayer().getWorld())) {
            return;
        }
        double factor = rules.fishWait(state.current());
        if (factor == 1.0) {
            return;
        }
        FishHook hook = event.getHook();
        hook.setWaitTime(Math.max(1, (int) (hook.getMinWaitTime() * factor)), Math.max(1, (int) (hook.getMaxWaitTime() * factor)));
    }
}
