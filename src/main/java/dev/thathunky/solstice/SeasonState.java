package dev.thathunky.solstice;

import java.time.Instant;

/** The current season: from the calendar, or overridden by /solstice preview for testing. */
final class SeasonState {

    private final SeasonCalendar calendar;
    private volatile Season preview;

    SeasonState(SeasonCalendar calendar) {
        this.calendar = calendar;
    }

    SeasonCalendar calendar() {
        return calendar;
    }

    SeasonCalendar.Position position() {
        return calendar.at(Instant.now());
    }

    /** The calendar's season, ignoring any preview. */
    Season calendarSeason() {
        return position().season();
    }

    Season current() {
        Season p = preview;
        return p != null ? p : calendarSeason();
    }

    long clockTicks() {
        Season p = preview;
        return p != null ? SeasonCalendar.midTicks(p) : position().clockTicks();
    }

    Season preview() {
        return preview;
    }

    void preview(Season season) {
        preview = season;
    }
}
