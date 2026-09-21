package dev.thathunky.solstice;

import java.time.Instant;
import java.util.function.LongSupplier;

/**
 * Seasons that each last a fixed number of in-game days. The count lives in the plugin's state
 * ({@link GameDayCounter}), not in the world time, so it survives restarts and /time set.
 */
final class GameDaysCalendar implements SeasonCalendar {

    static final long DAY = 24_000L;

    private final int daysPerSeason;
    private final LongSupplier elapsedTicks;

    GameDaysCalendar(int daysPerSeason, LongSupplier elapsedTicks) {
        this.daysPerSeason = Math.max(1, daysPerSeason);
        this.elapsedTicks = elapsedTicks;
    }

    long seasonTicks() {
        return daysPerSeason * DAY;
    }

    long yearTicks() {
        return 4 * seasonTicks();
    }

    /** Elapsed ticks that put the calendar at the very start of a season, in the same cycle. */
    long startOf(Season s, long elapsed) {
        long cycle = Math.floorDiv(elapsed, yearTicks());
        return cycle * yearTicks() + s.id() * seasonTicks();
    }

    @Override
    public String mode() {
        return "game-days";
    }

    @Override
    public Position at(Instant now) {
        long elapsed = Math.max(0, elapsedTicks.getAsLong());
        long inYear = elapsed % yearTicks();
        Season season = Season.values()[(int) (inYear / seasonTicks())];
        long inSeason = inYear % seasonTicks();
        long ticks = SeasonCalendar.ticksIn(season, (double) inSeason / seasonTicks());
        long daysLeft = (seasonTicks() - inSeason + DAY - 1) / DAY;
        return new Position(season, ticks, (int) (elapsed / yearTicks()), null, null, daysLeft);
    }
}
