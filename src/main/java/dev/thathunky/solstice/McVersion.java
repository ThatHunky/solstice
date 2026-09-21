package dev.thathunky.solstice;

/**
 * A Minecraft version such as {@code 1.21.4} or {@code 26.1.2}, compared part by part. The year-based
 * scheme (26.x) sorts after 1.x on its own, because 26 is greater than 1.
 */
record McVersion(int major, int minor, int patch) implements Comparable<McVersion> {

    static final McVersion V1_21_5 = new McVersion(1, 21, 5);
    static final McVersion V26_1 = new McVersion(26, 1, 0);
    /** The newest version whose datapack formats this plugin was checked against. */
    static final McVersion NEWEST_VERIFIED = new McVersion(26, 3, 0);

    /** Parses the leading numbers of strings like "26.3", "1.21.11" or "26.3-pre-2"; null if there are none. */
    static McVersion parse(String text) {
        if (text == null) {
            return null;
        }
        int[] parts = new int[3];
        int part = 0;
        int digits = 0;
        for (int i = 0; i < text.length() && part < 3; i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                parts[part] = parts[part] * 10 + (c - '0');
                digits++;
            } else if (c == '.' && digits > 0) {
                part++;
                digits = 0;
            } else {
                break;
            }
        }
        if (part == 0 && digits == 0) {
            return null;
        }
        return new McVersion(parts[0], parts[1], parts[2]);
    }

    boolean atLeast(McVersion other) {
        return compareTo(other) >= 0;
    }

    /**
     * The data pack format the vanilla server of this version declares (its {@code version.json}),
     * or 0 when unknown. Only versions with world clocks are listed: that is where Solstice writes a
     * datapack at all.
     */
    int dataPackFormat() {
        if (major == 26 && minor == 1) {
            return 101;
        }
        if (major == 26 && minor == 2) {
            return 107;
        }
        if (major == 26 && minor == 3) {
            return 121;
        }
        return 0;
    }

    @Override
    public int compareTo(McVersion o) {
        if (major != o.major) {
            return Integer.compare(major, o.major);
        }
        if (minor != o.minor) {
            return Integer.compare(minor, o.minor);
        }
        return Integer.compare(patch, o.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + (patch > 0 ? "." + patch : "");
    }
}
