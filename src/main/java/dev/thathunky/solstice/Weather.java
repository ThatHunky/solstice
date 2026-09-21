package dev.thathunky.solstice;

import java.util.Random;
import java.util.logging.Logger;
import org.bukkit.GameRule;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;

/** One world's weather by season. The vanilla weather cycle is switched off for that world. */
final class Weather implements Runnable {

    private final World world;
    private final SeasonState state;
    private final WeatherPlan plan;
    private final Random random = new Random();
    private long changeAt;
    private Season lastSeason;

    private static GameRule<Boolean> cycleRule;

    Weather(World world, SeasonState state, WeatherPlan plan) {
        this.world = world;
        this.state = state;
        this.plan = plan;
        GameRule<Boolean> rule = weatherCycleRule();
        if (rule != null) {
            world.setGameRule(rule, false);
        }
    }

    /**
     * The weather-cycle game rule: {@code advance_weather} from the game-rule registry (1.21.11+), else
     * the old {@code doWeatherCycle} by name. Both lookups go through reflection because the methods
     * behind them are deprecated for removal in newer APIs and missing in older ones.
     */
    @SuppressWarnings("unchecked")
    static synchronized GameRule<Boolean> weatherCycleRule() {
        if (cycleRule != null) {
            return cycleRule;
        }
        try {
            Object registry = Registry.class.getField("GAME_RULE").get(null);
            Object rule = registry.getClass().getMethod("get", NamespacedKey.class)
                    .invoke(registry, NamespacedKey.minecraft("advance_weather"));
            if (rule instanceof GameRule<?> r) {
                return cycleRule = (GameRule<Boolean>) r;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // older API: no game-rule registry
        }
        try {
            Object rule = GameRule.class.getMethod("getByName", String.class).invoke(null, "doWeatherCycle");
            if (rule instanceof GameRule<?> r) {
                return cycleRule = (GameRule<Boolean>) r;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // fall through
        }
        Logger.getLogger("Solstice").warning("could not find the weather-cycle game rule; vanilla weather keeps running alongside");
        return null;
    }

    void reset() {
        changeAt = 0;
    }

    /** Gives the weather back to vanilla (on disable and reload). */
    void release() {
        GameRule<Boolean> rule = weatherCycleRule();
        if (rule != null) {
            world.setGameRule(rule, true);
        }
    }

    @Override
    public void run() {
        long now = System.currentTimeMillis();
        Season season = state.current();
        if (season != lastSeason) {
            lastSeason = season;
            changeAt = 0;
        }
        if (now < changeAt) {
            return;
        }
        WeatherPlan.Decision d = plan.next(season, world.hasStorm(), random);
        int ticks = d.minutes() * 1200;
        world.setStorm(d.wet());
        world.setThundering(d.thunder());
        world.setWeatherDuration(ticks);
        if (d.thunder()) {
            world.setThunderDuration(ticks);
        }
        changeAt = now + d.minutes() * 60_000L;
    }
}
