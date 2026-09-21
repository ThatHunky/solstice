package dev.thathunky.solstice;

/**
 * Counts in-game time for the game-days calendar from samples of the world's full time. Forward
 * steps are added (a skipped night counts, it is part of the day); a step back (/time set to an
 * earlier time) or an implausibly big jump only moves the baseline, so commands cannot fast-forward
 * or rewind the seasons by accident.
 */
final class GameDayCounter {

    /** Largest step between two samples that still counts: two whole days. */
    static final long MAX_STEP = 48_000L;

    private long elapsed;
    private long last = Long.MIN_VALUE;

    GameDayCounter(long elapsed) {
        this.elapsed = Math.max(0, elapsed);
    }

    /** Feeds one sample of the world's full time (ticks). */
    void sample(long fullTime) {
        if (last != Long.MIN_VALUE) {
            long step = fullTime - last;
            if (step > 0 && step <= MAX_STEP) {
                elapsed += step;
            }
        }
        last = fullTime;
    }

    long elapsed() {
        return elapsed;
    }

    void set(long elapsed) {
        this.elapsed = Math.max(0, elapsed);
    }
}
