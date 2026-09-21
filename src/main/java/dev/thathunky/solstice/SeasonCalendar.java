package dev.thathunky.solstice;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Where in the season year we are. Two implementations: {@link RealDateCalendar} (the real calendar)
 * and {@link GameDaysCalendar} (a fixed number of in-game days per season).
 *
 * <p>The season year is also mapped onto the season clock: 8,760,000 ticks (365 days × 24,000), split
 * at the same boundaries the sky-colour timeline uses. Winter starts at 0.
 */
interface SeasonCalendar {

    long YEAR_TICKS = 8_760_000L;
    /** Clock ticks where each season starts, plus the end of the year. */
    long[] BOUNDS = {0L, 2_160_000L, 4_368_000L, 6_576_000L, YEAR_TICKS};

    /**
     * @param season    the season right now
     * @param clockTicks position on the season clock, 0 until {@link #YEAR_TICKS}
     * @param cycle     which winter this season year belongs to; changes once a year, marks winter chunks
     * @param firstDay  first day of the season (real-date mode only, else null)
     * @param lastDay   last day of the season (real-date mode only, else null)
     * @param daysLeft  in-game days left in the season (game-days mode only, else -1)
     */
    record Position(Season season, long clockTicks, int cycle, LocalDate firstDay, LocalDate lastDay, long daysLeft) {
    }

    Position at(Instant now);

    /** "real-date" or "game-days", for status output. */
    String mode();

    static Season ofTicks(long ticks) {
        int i = 0;
        while (i < 3 && ticks >= BOUNDS[i + 1]) {
            i++;
        }
        return Season.values()[i];
    }

    /** The middle of a season on the clock — what /solstice preview shows. */
    static long midTicks(Season s) {
        return (BOUNDS[s.id()] + BOUNDS[s.id() + 1]) / 2;
    }

    /** The clock position for a fraction (0..1) of the way through a season. */
    static long ticksIn(Season s, double fraction) {
        int i = s.id();
        return BOUNDS[i] + (long) (fraction * (BOUNDS[i + 1] - BOUNDS[i]));
    }

    /** Day: 23000–12999 ticks of the day, night: 13000–22999. */
    static boolean isDay(long worldTime) {
        long t = Math.floorMod(worldTime, 24_000L);
        return t >= 23_000 || t <= 12_999;
    }
}
