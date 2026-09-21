package dev.thathunky.solstice;

import java.util.Random;

/** Weather by season: clear after rain, rain after clear; durations and thunder chance per season. */
final class WeatherPlan {

    record Window(int clearMin, int clearMax, int wetMin, int wetMax, double thunderChance) {
    }

    record Decision(boolean wet, boolean thunder, int minutes) {
    }

    /** The defaults, per season id. */
    static final Window[] DEFAULTS = {
            new Window(10, 20, 10, 30, 0.0),  // winter
            new Window(8, 20, 3, 8, 0.10),    // spring
            new Window(20, 45, 3, 6, 0.45),   // summer
            new Window(8, 15, 10, 25, 0.10),  // autumn
    };

    private final Window[] windows;

    WeatherPlan(Window[] windows) {
        this.windows = windows.clone();
    }

    Window of(Season s) {
        return windows[s.id()];
    }

    Decision next(Season s, boolean wetNow, Random random) {
        Window w = of(s);
        if (wetNow) {
            return new Decision(false, false, between(random, w.clearMin(), w.clearMax()));
        }
        return new Decision(true, random.nextDouble() < w.thunderChance(), between(random, w.wetMin(), w.wetMax()));
    }

    private static int between(Random random, int min, int max) {
        int lo = Math.max(1, Math.min(min, max));
        int hi = Math.max(lo, max);
        return lo + random.nextInt(hi - lo + 1);
    }
}
