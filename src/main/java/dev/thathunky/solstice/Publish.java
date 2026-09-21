package dev.thathunky.solstice;

/** Text formats for the optional integrations: the TAB animation block and the season JSON file. */
final class Publish {

    /** Any line starting with this opens the block (the first version wrote "(tools/season.py)" after it). */
    static final String BEGIN = "# >>> season";
    static final String BEGIN_LINE = "# >>> season (Solstice)";
    static final String END = "# <<< season";

    private Publish() {
    }

    /**
     * Rewrites only the block between the markers, keeping the existing opening line as it is; with no
     * markers, appends a new block at the end. {@code frame} is TAB's text, e.g. {@code &#9fc3e6❄ Winter}.
     */
    static String rewriteAnimation(String text, String animation, String frame) {
        String body = animation + ":\n  change-interval: 60000\n  texts:\n  - '" + frame.replace("'", "''") + "'\n" + END + "\n";
        int begin = text.indexOf(BEGIN);
        int end = text.indexOf(END);
        if (begin >= 0 && end > begin) {
            int lineEnd = text.indexOf('\n', begin);
            String opening = lineEnd < 0 || lineEnd > end ? BEGIN_LINE : text.substring(begin, lineEnd);
            String tail = text.substring(end + END.length()).replaceFirst("^\n+", "");
            return text.substring(0, begin) + opening + "\n" + body + tail;
        }
        if (begin >= 0 || end >= 0) {
            throw new IllegalStateException("animations file: only one season marker, or they are out of order — fix it by hand");
        }
        return text.replaceFirst("\n+$", "") + "\n\n" + BEGIN_LINE + "\n" + body;
    }

    /** The season JSON: {@code {"id": 3, "key": "autumn", "from": "2026-09-01", "to": "2026-11-30"}}. */
    static String seasonJson(SeasonCalendar.Position p) {
        return "{\"id\": " + p.season().id() + ", \"key\": \"" + p.season().key + "\", \"from\": "
                + quoted(p.firstDay()) + ", \"to\": " + quoted(p.lastDay()) + "}";
    }

    private static String quoted(Object value) {
        return value == null ? "null" : "\"" + value + "\"";
    }
}
