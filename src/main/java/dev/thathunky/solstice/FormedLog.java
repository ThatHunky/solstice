package dev.thathunky.solstice;

import java.util.Arrays;

/** Positions of snow and ice formed in winter: x and z inside the chunk (0–15), y from the world bottom. */
final class FormedLog {

    private FormedLog() {
    }

    static int encode(int localX, int yFromMin, int localZ) {
        return yFromMin << 8 | localZ << 4 | localX;
    }

    static int x(int code) {
        return code & 15;
    }

    static int z(int code) {
        return code >> 4 & 15;
    }

    static int y(int code) {
        return code >>> 8;
    }

    /** The same array if the position is already there; null if the log is full; else a new array with it. */
    static int[] add(int[] log, int code, int limit) {
        for (int c : log) {
            if (c == code) {
                return log;
            }
        }
        if (log.length >= limit) {
            return null;
        }
        int[] next = Arrays.copyOf(log, log.length + 1);
        next[log.length] = code;
        return next;
    }
}
