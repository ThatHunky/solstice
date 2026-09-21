package dev.thathunky.solstice;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;

/** config.yml, read once into plain values. Bad values fall back to the default with a warning. */
record Settings(
        String language, boolean perPlayerLanguage, List<String> worlds,
        String mode, ZoneId zone, boolean south, int daysPerSeason, Season startSeason,
        String[] seasonColors,
        boolean announce, List<String> announceWorlds, String sound, String subtitleColor,
        boolean dayLength, double[] dayRate, double[] nightRate,
        boolean sky, String datapackFolder, String namespace, int transitionDays, Map<String, List<String>> skyColors,
        boolean particles, int particleInterval, int particlesPerPlayer, int particleRadius, boolean particlesBedrock,
        boolean weather, WeatherPlan.Window[] weatherWindows,
        boolean crops, boolean animals, boolean fishing, HarvestRules.Numbers harvest,
        boolean winter, int chunksPerTick, int formedLimit, Map<String, String> extraBiomes,
        boolean tab, String tabFile, String tabAnimation, String tabLanguage,
        boolean json, String jsonFile) {

    static final String[] DEFAULT_SEASON_COLORS = {"#9fc3e6", "#f2a7c3", "#f5b942", "#d0562b"};
    static final double[] DEFAULT_DAY = {0.972, 0.583, 0.449, 0.583};
    static final double[] DEFAULT_NIGHT = {0.321, 0.417, 0.694, 0.417};
    static final Map<String, List<String>> DEFAULT_SKY = new LinkedHashMap<>();

    static {
        DEFAULT_SKY.put("sky_color", List.of("#d6e0ef", "#fbe6f0", "#fff5dc", "#f6dcc8"));
        DEFAULT_SKY.put("fog_color", List.of("#e6ecf5", "#fff0f5", "#fff3d6", "#f3d9c4"));
        DEFAULT_SKY.put("cloud_color", List.of("#dde3ea", "#fff4f8", "#ffffff", "#f5e6da"));
        DEFAULT_SKY.put("sunrise_sunset_color", List.of("#e8d8e8", "#ffc4dc", "#ffb870", "#e8704a"));
        DEFAULT_SKY.put("sky_light_color", List.of("#e4ecff", "#fff2f6", "#fff6e0", "#ffe8d4"));
    }

    static Settings from(ConfigurationSection c, Consumer<String> warn) {
        String mode = c.getString("calendar.mode", "real-date").trim().toLowerCase(java.util.Locale.ROOT);
        if (!mode.equals("real-date") && !mode.equals("game-days")) {
            warn.accept("calendar.mode \"" + mode + "\" is not real-date or game-days — using real-date");
            mode = "real-date";
        }
        ZoneId zone = ZoneId.systemDefault();
        String tz = c.getString("calendar.timezone", "").trim();
        if (!tz.isEmpty()) {
            try {
                zone = ZoneId.of(tz);
            } catch (DateTimeException e) {
                warn.accept("calendar.timezone \"" + tz + "\" is not a time zone — using the server's (" + zone + ")");
            }
        }
        String hemisphere = c.getString("calendar.hemisphere", "north").trim().toLowerCase(java.util.Locale.ROOT);
        if (!hemisphere.equals("north") && !hemisphere.equals("south")) {
            warn.accept("calendar.hemisphere \"" + hemisphere + "\" is not north or south — using north");
        }
        Season start = Season.byKey(c.getString("calendar.start-season", "spring"));
        if (start == null) {
            warn.accept("calendar.start-season is not a season — using spring");
            start = Season.SPRING;
        }

        String[] seasonColors = new String[4];
        for (Season s : Season.values()) {
            seasonColors[s.id()] = color(c.getString("colors." + s.key), DEFAULT_SEASON_COLORS[s.id()], "colors." + s.key, warn);
        }

        double[] day = new double[4];
        double[] night = new double[4];
        WeatherPlan.Window[] windows = new WeatherPlan.Window[4];
        for (Season s : Season.values()) {
            int i = s.id();
            day[i] = c.getDouble("day-length." + s.key + ".day", DEFAULT_DAY[i]);
            night[i] = c.getDouble("day-length." + s.key + ".night", DEFAULT_NIGHT[i]);
            WeatherPlan.Window d = WeatherPlan.DEFAULTS[i];
            int[] clear = range(c, "weather." + s.key + ".clear-minutes", d.clearMin(), d.clearMax());
            int[] rain = range(c, "weather." + s.key + ".rain-minutes", d.wetMin(), d.wetMax());
            windows[i] = new WeatherPlan.Window(clear[0], clear[1], rain[0], rain[1],
                    c.getDouble("weather." + s.key + ".thunder-chance", d.thunderChance()));
        }

        Map<String, List<String>> sky = new LinkedHashMap<>();
        ConfigurationSection skySection = c.getConfigurationSection("sky.colors");
        if (skySection == null) {
            sky.putAll(DEFAULT_SKY);
        } else {
            for (String attr : skySection.getKeys(false)) {
                List<String> four = skySection.getStringList(attr);
                List<String> fallback = DEFAULT_SKY.getOrDefault(attr, List.of("#ffffff", "#ffffff", "#ffffff", "#ffffff"));
                if (four.size() != 4) {
                    warn.accept("sky.colors." + attr + " needs four colours (winter, spring, summer, autumn) — using defaults");
                    four = fallback;
                }
                List<String> checked = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    checked.add(color(four.get(i), fallback.get(i), "sky.colors." + attr, warn));
                }
                sky.put(attr, checked);
            }
        }

        HarvestRules.Numbers d = HarvestRules.Numbers.defaults();
        HarvestRules.Numbers harvest = new HarvestRules.Numbers(
                perSeason(c, "crops.bonus-chance", d.cropBonus()),
                perSeason(c, "crops.open-sky-cancel-chance", d.cropCancel()),
                perSeason(c, "animals.breed-cancel-chance", d.breedCancel()),
                perSeason(c, "animals.baby-growth", d.babyGrowth()),
                perSeason(c, "fishing.wait-factor", d.fishWait()));

        Map<String, String> extraBiomes = new LinkedHashMap<>();
        ConfigurationSection extra = c.getConfigurationSection("winter.extra-biomes");
        if (extra != null) {
            for (String k : extra.getKeys(false)) {
                extraBiomes.put(k, extra.getString(k, ""));
            }
        }

        String namespace = c.getString("sky.namespace", "solstice").trim().toLowerCase(java.util.Locale.ROOT);
        if (!namespace.matches("[a-z0-9_.-]+")) {
            warn.accept("sky.namespace \"" + namespace + "\" is not a valid namespace — using solstice");
            namespace = "solstice";
        }
        String folder = c.getString("sky.datapack-folder", "solstice").trim();
        if (folder.isEmpty() || folder.contains("/") || folder.contains("\\") || folder.contains("..")) {
            warn.accept("sky.datapack-folder must be a plain folder name — using solstice");
            folder = "solstice";
        }

        return new Settings(
                c.getString("language", "en"), c.getBoolean("per-player-language", true), c.getStringList("worlds"),
                mode, zone, hemisphere.equals("south"), Math.max(1, c.getInt("calendar.days-per-season", 8)), start,
                seasonColors,
                c.getBoolean("announce.enabled", true), c.getStringList("announce.worlds"),
                c.getString("announce.sound", "block.amethyst_block.chime"),
                color(c.getString("announce.subtitle-color"), "#8a83c2", "announce.subtitle-color", warn),
                c.getBoolean("day-length.enabled", true), day, night,
                c.getBoolean("sky.enabled", true), folder, namespace, Math.max(0, c.getInt("sky.transition-days", 7)), sky,
                c.getBoolean("particles.enabled", true), Math.max(1, c.getInt("particles.interval-ticks", 10)),
                Math.max(0, c.getInt("particles.per-player", 24)), Math.max(1, c.getInt("particles.radius", 12)),
                c.getBoolean("particles.bedrock", true),
                c.getBoolean("weather.enabled", true), windows,
                c.getBoolean("crops.enabled", true), c.getBoolean("animals.enabled", true), c.getBoolean("fishing.enabled", true),
                harvest,
                c.getBoolean("winter.enabled", true), Math.max(1, c.getInt("winter.chunks-per-tick", 10)),
                Math.max(1, c.getInt("winter.formed-limit", 8192)), extraBiomes,
                c.getBoolean("integrations.tab.enabled", false),
                c.getString("integrations.tab.file", "plugins/TAB/animations.yml"),
                c.getString("integrations.tab.animation", "season"),
                c.getString("integrations.tab.language", ""),
                c.getBoolean("integrations.json-file.enabled", false),
                c.getString("integrations.json-file.path", "plugins/Solstice/season.json"));
    }

    private static double[] perSeason(ConfigurationSection c, String path, double[] defaults) {
        double[] out = new double[4];
        for (Season s : Season.values()) {
            out[s.id()] = c.getDouble(path + "." + s.key, defaults[s.id()]);
        }
        return out;
    }

    private static int[] range(ConfigurationSection c, String path, int min, int max) {
        List<Integer> v = c.getIntegerList(path);
        return v.size() == 2 ? new int[]{v.get(0), v.get(1)} : new int[]{min, max};
    }

    static String color(String value, String fallback, String path, Consumer<String> warn) {
        if (value == null) {
            return fallback;
        }
        String v = value.trim();
        if (!v.matches("#[0-9a-fA-F]{6}")) {
            warn.accept(path + ": \"" + value + "\" is not a #rrggbb colour — using " + fallback);
            return fallback;
        }
        return v.toLowerCase(java.util.Locale.ROOT);
    }
}
