package dev.thathunky.solstice;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Seasons from the real calendar: meteorological seasons, whole months, in the given time zone.
 * North: winter is December–February. South: everything shifts by half a year (winter is June–August).
 * The fraction of the season is measured between real instants (the subtraction happens in UTC), so a
 * daylight-saving change does not make the clock jump.
 */
final class RealDateCalendar implements SeasonCalendar {

    record Bounds(Season season, LocalDate start, LocalDate end) {
    }

    private final ZoneId zone;
    private final boolean south;

    RealDateCalendar(ZoneId zone, boolean south) {
        this.zone = zone;
        this.south = south;
    }

    ZoneId zone() {
        return zone;
    }

    @Override
    public String mode() {
        return "real-date";
    }

    /** The season that contains this date, with its first day and the day after its last. */
    Bounds bounds(LocalDate d) {
        int m = d.getMonthValue();
        // Seasons start on the first of March, June, September and December.
        int startMonth = m == 12 || m <= 2 ? 12 : m / 3 * 3;
        int startYear = m <= 2 ? d.getYear() - 1 : d.getYear();
        LocalDate start = LocalDate.of(startYear, startMonth, 1);
        return new Bounds(seasonStartingIn(startMonth), start, start.plusMonths(3));
    }

    private Season seasonStartingIn(int month) {
        int northIndex = switch (month) {
            case 12 -> 0;
            case 3 -> 1;
            case 6 -> 2;
            default -> 3;
        };
        return Season.values()[south ? (northIndex + 2) % 4 : northIndex];
    }

    /** The year in which the current season year's winter began (north: 1 Dec, south: 1 Jun). */
    int seasonYear(LocalDate d) {
        int winterMonth = south ? 6 : 12;
        return d.getMonthValue() >= winterMonth ? d.getYear() : d.getYear() - 1;
    }

    long ticksAt(Instant moment) {
        Bounds b = bounds(moment.atZone(zone).toLocalDate());
        Instant t0 = b.start().atStartOfDay(zone).toInstant();
        Instant t1 = b.end().atStartOfDay(zone).toInstant();
        double frac = (double) Duration.between(t0, moment).toMillis() / Duration.between(t0, t1).toMillis();
        return SeasonCalendar.ticksIn(b.season(), frac);
    }

    @Override
    public Position at(Instant now) {
        LocalDate today = now.atZone(zone).toLocalDate();
        Bounds b = bounds(today);
        long ticks = ticksAt(now);
        return new Position(b.season(), ticks, seasonYear(today), b.start(), b.end().minusDays(1), -1);
    }
}
