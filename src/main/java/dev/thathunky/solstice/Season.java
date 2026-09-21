package dev.thathunky.solstice;

/** The four seasons, in the order of the season year: it starts with winter. */
enum Season {
    WINTER("winter"), SPRING("spring"), SUMMER("summer"), AUTUMN("autumn");

    final String key;

    Season(String key) {
        this.key = key;
    }

    int id() {
        return ordinal();
    }

    Season next() {
        return values()[(ordinal() + 1) % 4];
    }

    static Season byKey(String key) {
        for (Season s : values()) {
            if (s.key.equalsIgnoreCase(key)) {
                return s;
            }
        }
        return null;
    }
}
