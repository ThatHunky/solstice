package dev.thathunky.solstice;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The datapack behind the sky colours: a world clock ({@code <ns>:season}), a timeline on that clock
 * ({@code <ns>:year}) that tints the sky, fog, clouds, sunrise and sky light per season, and the tag
 * that attaches the timeline to the overworld. Needs world clocks, so Minecraft 26.1 or newer.
 *
 * <p>Output is text, formatted exactly like Python's {@code json.dumps(indent=2)}, so the files match
 * the hand-made pack the plugin was extracted from byte for byte (see TestMain).
 */
final class SkyPack {

    static final String MCMETA = "pack.mcmeta";

    /** What {@link #install} did. */
    enum Result {
        /** Everything on disk already matched. */
        UNCHANGED,
        /** Only pack.mcmeta was (re)written; the clock and colours on disk were already right. */
        META_ONLY,
        /** The clock, timeline or tag changed — the server has to restart to load them (registries). */
        NEEDS_RESTART
    }

    private SkyPack() {
    }

    /** Relative path inside the pack folder → file content. {@code pack.mcmeta} comes last. */
    static Map<String, String> files(String namespace, int transitionDays, Map<String, List<String>> colors, int dataFormat) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("data/" + namespace + "/world_clock/season.json", "{}\n");
        out.put("data/" + namespace + "/timeline/year.json", timeline(namespace, transitionDays, colors));
        out.put("data/minecraft/tags/timeline/in_overworld.json",
                "{\"replace\": false, \"values\": [\"" + namespace + ":year\"]}\n");
        out.put(MCMETA, mcmeta(dataFormat));
        return out;
    }

    /**
     * Writes {@code files} into {@code folder}, touching only files whose content differs. Files are
     * written through a temporary file and an atomic move, so a crash never leaves half a JSON file
     * for the next start to choke on.
     */
    static Result install(Path folder, Map<String, String> files) throws IOException {
        boolean meta = false;
        boolean data = false;
        for (Map.Entry<String, String> e : files.entrySet()) {
            Path target = folder.resolve(e.getKey());
            byte[] want = e.getValue().getBytes(StandardCharsets.UTF_8);
            if (Files.isRegularFile(target) && java.util.Arrays.equals(Files.readAllBytes(target), want)) {
                continue;
            }
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(tmp, want);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            if (e.getKey().equals(MCMETA)) {
                meta = true;
            } else {
                data = true;
            }
        }
        data |= removeOtherNamespaces(folder, files);
        return data ? Result.NEEDS_RESTART : meta ? Result.META_ONLY : Result.UNCHANGED;
    }

    /**
     * After {@code sky.namespace} changes, the old namespace's clock and timeline would stay in the
     * folder as unused registry entries. Removes exactly those two files, nothing else.
     */
    private static boolean removeOtherNamespaces(Path folder, Map<String, String> files) throws IOException {
        Path dataDir = folder.resolve("data");
        if (!Files.isDirectory(dataDir)) {
            return false;
        }
        boolean removed = false;
        try (var namespaces = Files.list(dataDir)) {
            for (Path ns : namespaces.toList()) {
                for (String rel : new String[]{"world_clock/season.json", "timeline/year.json"}) {
                    String key = "data/" + ns.getFileName() + "/" + rel;
                    if (!files.containsKey(key) && Files.deleteIfExists(ns.resolve(rel))) {
                        removed = true;
                    }
                }
            }
        }
        return removed;
    }

    /**
     * {@code min_format} is 26.1's format (the first with world clocks); {@code max_format} is this
     * server's own format, so the pack is never offered as compatible with a newer game whose timeline
     * format nobody has checked.
     */
    static String mcmeta(int dataFormat) {
        int max = Math.max(101, dataFormat);
        return "{\n  \"pack\": {\n    \"description\": \"Solstice — season sky colours (generated, do not edit)\",\n"
                + "    \"min_format\": [101, 0],\n    \"max_format\": [" + max + ", 2147483647]\n  }\n}\n";
    }

    static String timeline(String namespace, int transitionDays, Map<String, List<String>> colors) {
        long half = transitionDays * 24_000L;
        StringBuilder b = new StringBuilder();
        b.append("{\n  \"clock\": \"").append(namespace).append(":season\",\n");
        b.append("  \"period_ticks\": ").append(SeasonCalendar.YEAR_TICKS).append(",\n");
        b.append("  \"tracks\": {");
        boolean firstTrack = true;
        for (Map.Entry<String, List<String>> e : colors.entrySet()) {
            b.append(firstTrack ? "\n" : ",\n");
            firstTrack = false;
            b.append("    \"").append(attribute(e.getKey())).append("\": {\n");
            b.append("      \"modifier\": \"multiply\",\n      \"keyframes\": [");
            List<long[]> frames = keyframes(half, e.getValue());
            for (int i = 0; i < frames.size(); i++) {
                b.append(i == 0 ? "\n" : ",\n");
                b.append("        {\n          \"ticks\": ").append(frames.get(i)[0])
                        .append(",\n          \"value\": ").append(frames.get(i)[1]).append("\n        }");
            }
            b.append("\n      ]\n    }");
        }
        b.append(firstTrack ? "}\n}\n" : "\n  }\n}\n");
        return b.toString();
    }

    /** Plateau inside each season, a blend of ±half ticks around each boundary; sorted by ticks. */
    static List<long[]> keyframes(long half, List<String> seasonColors) {
        List<long[]> frames = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            long bound = SeasonCalendar.BOUNDS[i];
            frames.add(new long[]{Math.floorMod(bound - half, SeasonCalendar.YEAR_TICKS), argb(seasonColors.get((i + 3) % 4))});
            frames.add(new long[]{bound + half, argb(seasonColors.get(i))});
        }
        frames.sort(Comparator.comparingLong(f -> f[0]));
        return frames;
    }

    /** "sky_color" → "minecraft:visual/sky_color"; anything with a colon is used as given. */
    static String attribute(String key) {
        return key.contains(":") ? key : "minecraft:visual/" + key;
    }

    /**
     * An opaque colour as a signed 32-bit ARGB number, the way vanilla JSON writes colours
     * ({@code -1} is white). A plain {@code "#rrggbb"} string in a timeline stopped a 26.2 server
     * from starting during registry loading, so numbers are written instead.
     */
    static long argb(String hex) {
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        if (h.length() != 6) {
            throw new IllegalArgumentException("colour must be #rrggbb: " + hex);
        }
        return (int) (0xFF000000L | Long.parseLong(h, 16));
    }

    /**
     * The save's root folder, where datapacks/ lives. From 26.1 on, a world's folder is its dimension
     * folder, world/dimensions/<namespace>/<name>, while datapacks stay at world/datapacks.
     */
    static Path levelRoot(Path worldFolder) {
        Path p = worldFolder.toAbsolutePath().normalize();
        Path parent = p.getParent();
        Path grand = parent == null ? null : parent.getParent();
        if (grand != null && grand.getFileName() != null && grand.getFileName().toString().equals("dimensions")
                && grand.getParent() != null) {
            return grand.getParent();
        }
        return p;
    }
}
