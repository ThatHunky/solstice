package dev.thathunky.solstice;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.configuration.file.YamlConfiguration;

/** Server-free checks. Run from the repository root: build.sh and `./gradlew check` both do. */
public final class TestMain {

    private static int passed;
    private static int failed;

    static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");
    static final RealDateCalendar NORTH = new RealDateCalendar(KYIV, false);
    static final RealDateCalendar SOUTH = new RealDateCalendar(KYIV, true);

    static void check(boolean condition, String name) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }

    public static void main(String[] args) throws Exception {
        calendar();
        southernHemisphere();
        gameDays();
        animation();
        dayRate();
        weather();
        harvest();
        winter();
        skyPack();
        skyInstall();
        settings();
        versions();
        langFiles();
        messages();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    static Instant kyiv(int y, int m, int d, int h, int min, int s) {
        return LocalDateTime.of(y, m, d, h, min, s).atZone(KYIV).toInstant();
    }

    static Instant kyiv(int y, int m, int d) {
        return kyiv(y, m, d, 0, 0, 0);
    }

    // Ported from MatsuriSeasons' TestMain (itself the former season.py --selftest).
    private static void calendar() {
        check(NORTH.ticksAt(kyiv(2026, 12, 1)) == 0, "1 Dec = 0");
        check(NORTH.ticksAt(kyiv(2027, 3, 1)) == 2_160_000, "1 Mar");
        check(NORTH.ticksAt(kyiv(2027, 6, 1)) == 4_368_000, "1 Jun");
        check(NORTH.ticksAt(kyiv(2027, 9, 1)) == 6_576_000, "1 Sep");

        long leap = NORTH.ticksAt(kyiv(2028, 2, 29, 12, 0, 0));
        check(leap > 0 && leap < 2_160_000 && SeasonCalendar.ofTicks(leap) == Season.WINTER, "leap day in winter");
        check(NORTH.ticksAt(kyiv(2028, 3, 1)) == 2_160_000, "1 Mar leap year");

        long last = NORTH.ticksAt(kyiv(2026, 11, 30, 23, 59, 59));
        check(last > 6_576_000 && last < 8_760_000, "last moment below period");

        // 25.10.2026 in Kyiv at 04:00 the clocks go back to 03:00
        long a = NORTH.ticksAt(LocalDateTime.of(2026, 10, 24, 23, 0).toInstant(ZoneOffset.UTC));
        long b = NORTH.ticksAt(LocalDateTime.of(2026, 10, 25, 0, 30).toInstant(ZoneOffset.UTC));
        long c = NORTH.ticksAt(LocalDateTime.of(2026, 10, 25, 1, 30).toInstant(ZoneOffset.UTC));
        check(a < b && b < c, "DST night monotonic");

        long[] edges = {0, 2_159_999, 2_160_000, 4_367_999, 4_368_000, 6_575_999, 6_576_000, 8_759_999};
        Season[] expect = {Season.WINTER, Season.WINTER, Season.SPRING, Season.SPRING,
                Season.SUMMER, Season.SUMMER, Season.AUTUMN, Season.AUTUMN};
        boolean edgesOk = true;
        for (int i = 0; i < edges.length; i++) {
            edgesOk &= SeasonCalendar.ofTicks(edges[i]) == expect[i];
        }
        check(edgesOk, "ofTicks edges");

        RealDateCalendar.Bounds dec = NORTH.bounds(LocalDate.of(2026, 12, 15));
        RealDateCalendar.Bounds jan = NORTH.bounds(LocalDate.of(2027, 1, 15));
        check(dec.equals(jan) && dec.start().equals(LocalDate.of(2026, 12, 1)) && dec.end().equals(LocalDate.of(2027, 3, 1)),
                "Dec and Jan share winter");

        check(Publish.seasonJson(NORTH.at(kyiv(2026, 9, 11, 22, 0, 0)))
                        .equals("{\"id\": 3, \"key\": \"autumn\", \"from\": \"2026-09-01\", \"to\": \"2026-11-30\"}"),
                "season json autumn");
        check(Publish.seasonJson(NORTH.at(kyiv(2027, 12, 5))).contains("\"to\": \"2028-02-29\""), "winter json leap aware");

        check(SeasonCalendar.midTicks(Season.SUMMER) == 5_472_000, "mid summer");
        check(Season.byKey("autumn") == Season.AUTUMN && Season.byKey("nope") == null, "byKey");
        check(Season.AUTUMN.next() == Season.WINTER, "autumn is followed by winter");

        check(NORTH.seasonYear(LocalDate.of(2026, 12, 1)) == 2026
                && NORTH.seasonYear(LocalDate.of(2027, 2, 28)) == 2026
                && NORTH.seasonYear(LocalDate.of(2026, 11, 30)) == 2025, "season year starts 1 Dec");
        check(NORTH.at(kyiv(2027, 1, 10)).cycle() == 2026, "position cycle is the season year");

        SeasonCalendar.Position p = NORTH.at(kyiv(2026, 9, 21, 12, 0, 0));
        check(p.season() == Season.AUTUMN && p.lastDay().equals(LocalDate.of(2026, 11, 30)) && p.daysLeft() == -1,
                "position in real-date mode");

        // Every date of two years (2028 is a leap year), five hours a day
        boolean inYear = true;
        boolean matches = true;
        boolean grows = true;
        long prev = -1;
        for (LocalDate d = LocalDate.of(2027, 12, 1); d.isBefore(LocalDate.of(2029, 12, 1)); d = d.plusDays(1)) {
            for (int hour : new int[]{0, 6, 12, 18, 23}) {
                long t = NORTH.ticksAt(LocalDateTime.of(d, LocalTime.of(hour, 0)).atZone(KYIV).toInstant());
                inYear &= t >= 0 && t < 8_760_000;
                matches &= SeasonCalendar.ofTicks(t) == NORTH.bounds(d).season();
                boolean newYear = d.getMonthValue() == 12 && d.getDayOfMonth() == 1 && hour == 0;
                grows &= newYear || t > prev;
                prev = t;
            }
        }
        check(inYear, "selftest: ticks inside year");
        check(matches, "selftest: season matches date");
        check(grows, "selftest: ticks grow");
    }

    private static void southernHemisphere() {
        check(SOUTH.bounds(LocalDate.of(2027, 7, 15)).season() == Season.WINTER, "south: July is winter");
        check(SOUTH.bounds(LocalDate.of(2027, 1, 15)).season() == Season.SUMMER, "south: January is summer");
        check(SOUTH.bounds(LocalDate.of(2027, 10, 1)).season() == Season.SPRING, "south: October is spring");
        check(SOUTH.bounds(LocalDate.of(2027, 4, 30)).season() == Season.AUTUMN, "south: April is autumn");
        RealDateCalendar.Bounds w = SOUTH.bounds(LocalDate.of(2027, 8, 31));
        check(w.start().equals(LocalDate.of(2027, 6, 1)) && w.end().equals(LocalDate.of(2027, 9, 1)), "south: winter is Jun–Aug");
        RealDateCalendar.Bounds s = SOUTH.bounds(LocalDate.of(2028, 2, 29));
        check(s.season() == Season.SUMMER && s.start().equals(LocalDate.of(2027, 12, 1)) && s.end().equals(LocalDate.of(2028, 3, 1)),
                "south: summer spans the new year");

        check(SOUTH.ticksAt(kyiv(2027, 6, 1)) == 0, "south: 1 Jun = 0");
        check(SOUTH.ticksAt(kyiv(2027, 9, 1)) == 2_160_000, "south: 1 Sep starts spring on the clock");
        check(SOUTH.ticksAt(kyiv(2027, 12, 1)) == 4_368_000, "south: 1 Dec starts summer on the clock");
        check(SOUTH.ticksAt(kyiv(2028, 3, 1)) == 6_576_000, "south: 1 Mar starts autumn on the clock");

        check(SOUTH.seasonYear(LocalDate.of(2027, 6, 1)) == 2027 && SOUTH.seasonYear(LocalDate.of(2027, 5, 31)) == 2026
                && SOUTH.seasonYear(LocalDate.of(2028, 1, 10)) == 2027, "south: season year starts 1 Jun");
        check(Publish.seasonJson(SOUTH.at(kyiv(2027, 7, 4)))
                .equals("{\"id\": 0, \"key\": \"winter\", \"from\": \"2027-06-01\", \"to\": \"2027-08-31\"}"), "south: json");

        // The same selftest as the north, for the south
        boolean inYear = true;
        boolean matches = true;
        boolean grows = true;
        long prev = -1;
        for (LocalDate d = LocalDate.of(2027, 6, 1); d.isBefore(LocalDate.of(2029, 6, 1)); d = d.plusDays(1)) {
            for (int hour : new int[]{0, 6, 12, 18, 23}) {
                long t = SOUTH.ticksAt(LocalDateTime.of(d, LocalTime.of(hour, 0)).atZone(KYIV).toInstant());
                inYear &= t >= 0 && t < 8_760_000;
                matches &= SeasonCalendar.ofTicks(t) == SOUTH.bounds(d).season();
                boolean newYear = d.getMonthValue() == 6 && d.getDayOfMonth() == 1 && hour == 0;
                grows &= newYear || t > prev;
                prev = t;
            }
        }
        check(inYear, "south selftest: ticks inside year");
        check(matches, "south selftest: season matches date");
        check(grows, "south selftest: ticks grow");

        // Same instant, opposite seasons
        Instant mid = kyiv(2027, 1, 15, 12, 0, 0);
        check(NORTH.at(mid).season() == Season.WINTER && SOUTH.at(mid).season() == Season.SUMMER, "north and south are opposite");
    }

    private static void gameDays() {
        long day = GameDaysCalendar.DAY;
        long[] elapsed = {0};
        GameDaysCalendar g = new GameDaysCalendar(8, () -> elapsed[0]);
        Instant now = Instant.EPOCH;

        SeasonCalendar.Position p = g.at(now);
        check(p.season() == Season.WINTER && p.clockTicks() == 0 && p.daysLeft() == 8 && p.cycle() == 0, "game-days: start of winter");
        check(p.firstDay() == null && p.lastDay() == null, "game-days: no dates");

        elapsed[0] = 4 * day;
        p = g.at(now);
        check(p.season() == Season.WINTER && p.clockTicks() == 1_080_000 && p.daysLeft() == 4, "game-days: middle of winter");

        elapsed[0] = 8 * day;
        p = g.at(now);
        check(p.season() == Season.SPRING && p.clockTicks() == 2_160_000 && p.daysLeft() == 8, "game-days: day 8 is spring");

        elapsed[0] = 8 * day - 1;
        p = g.at(now);
        check(p.season() == Season.WINTER && p.daysLeft() == 1, "game-days: last tick of winter");

        elapsed[0] = 3 * 8 * day + day / 2;
        p = g.at(now);
        check(p.season() == Season.AUTUMN && p.daysLeft() == 8, "game-days: half a day into autumn still counts 8 left");

        elapsed[0] = 32 * day + 1;
        p = g.at(now);
        check(p.season() == Season.WINTER && p.cycle() == 1, "game-days: second year is a new winter cycle");

        check(g.startOf(Season.SUMMER, 33 * day) == 32 * day + 16 * day, "game-days: startOf stays in the cycle");
        check(new GameDaysCalendar(0, () -> 0).seasonTicks() == day, "game-days: at least one day per season");

        // Every tick-day of a year: ticks grow and match the season
        boolean ok = true;
        long prev = -1;
        for (long e = 0; e < 32 * day; e += day / 4) {
            elapsed[0] = e;
            SeasonCalendar.Position q = g.at(now);
            ok &= q.clockTicks() > prev && SeasonCalendar.ofTicks(q.clockTicks()) == q.season();
            prev = q.clockTicks();
        }
        check(ok, "game-days: clock grows through the year and matches the season");

        GameDayCounter c = new GameDayCounter(100);
        c.sample(1000);
        check(c.elapsed() == 100, "counter: first sample only sets the baseline");
        c.sample(1600);
        check(c.elapsed() == 700, "counter: forward step counts");
        c.sample(1600 + 12_000);
        check(c.elapsed() == 12_700, "counter: a skipped night counts");
        c.sample(500);
        check(c.elapsed() == 12_700, "counter: /time set back does not rewind");
        c.sample(600);
        check(c.elapsed() == 12_800, "counter: counts again from the new baseline");
        c.sample(600 + 1_000_000);
        check(c.elapsed() == 12_800, "counter: a huge jump is ignored");
        c.set(-5);
        check(c.elapsed() == 0, "counter: never negative");
    }

    private static void animation() {
        String text = "welcome:\n  change-interval: 400\n  texts:\n  - 'hi'\n";
        String out = Publish.rewriteAnimation(text, "season", "&#d0562b🍁 Багрянець");
        check(out.startsWith(text), "animation keeps others");
        check(out.contains(Publish.BEGIN_LINE + "\nseason:\n") && out.endsWith(Publish.END + "\n"), "animation block");
        check(out.contains("  - '&#d0562b🍁 Багрянець'"), "animation frame");

        String once = Publish.rewriteAnimation("a: 1\n", "season", "&#9fc3e6❄ Сніг");
        check(Publish.rewriteAnimation(once, "season", "&#9fc3e6❄ Сніг").equals(once), "animation idempotent");
        String swapped = Publish.rewriteAnimation(once, "season", "&#f2a7c3🌸 Сакура");
        check(swapped.contains("Сакура") && !swapped.contains("Сніг")
                && swapped.indexOf(Publish.BEGIN) == swapped.lastIndexOf(Publish.BEGIN), "animation swap");

        // A file written by the predecessor keeps its own opening line
        String old = "x: 1\n\n# >>> season (tools/season.py)\nseason:\n  change-interval: 60000\n  texts:\n  - '&#d0562b🍁 Багрянець'\n# <<< season\n";
        check(Publish.rewriteAnimation(old, "season", "&#d0562b🍁 Багрянець").equals(old), "animation: old block is left byte-identical");
        check(Publish.rewriteAnimation(old, "season", "&#9fc3e6❄ Сніг").contains("# >>> season (tools/season.py)\nseason:"),
                "animation: old opening line kept");
        check(Publish.rewriteAnimation("a: 1\n", "season", "it's").contains("'it''s'"), "animation: quotes escaped");

        boolean refused = false;
        try {
            Publish.rewriteAnimation(Publish.BEGIN + "\nseason: x\n", "season", "x");
        } catch (IllegalStateException e) {
            refused = true;
        }
        check(refused, "animation refuses half markers");
    }

    private static void dayRate() {
        Settings s = Settings.from(new YamlConfiguration(), w -> { });
        check(s.dayRate()[Season.WINTER.id()] == 0.972 && s.nightRate()[Season.WINTER.id()] == 0.321, "winter rate");
        check(s.dayRate()[Season.SUMMER.id()] == 0.449 && s.nightRate()[Season.SUMMER.id()] == 0.694, "summer rate");
        check(s.dayRate()[Season.SPRING.id()] == 0.583 && s.nightRate()[Season.AUTUMN.id()] == 0.417, "spring/autumn rate");
        check(SeasonCalendar.isDay(0) && SeasonCalendar.isDay(12_999) && SeasonCalendar.isDay(23_000)
                && !SeasonCalendar.isDay(13_000) && !SeasonCalendar.isDay(22_999) && SeasonCalendar.isDay(48_000), "isDay");
    }

    private static void weather() {
        WeatherPlan plan = new WeatherPlan(WeatherPlan.DEFAULTS);
        Random r = new Random(7);
        WeatherPlan.Decision afterRain = plan.next(Season.AUTUMN, true, r);
        check(!afterRain.wet() && !afterRain.thunder() && afterRain.minutes() >= 8 && afterRain.minutes() <= 15, "autumn clear after rain");
        WeatherPlan.Decision afterClear = plan.next(Season.AUTUMN, false, r);
        check(afterClear.wet() && afterClear.minutes() >= 10 && afterClear.minutes() <= 25, "autumn rain after clear");

        boolean winterNeverThunders = true;
        int summerThunder = 0;
        for (int i = 0; i < 2000; i++) {
            winterNeverThunders &= !plan.next(Season.WINTER, false, r).thunder();
            if (plan.next(Season.SUMMER, false, r).thunder()) {
                summerThunder++;
            }
        }
        check(winterNeverThunders, "winter never thunders");
        check(summerThunder > 800 && summerThunder < 1000, "summer thunder about 45%");

        WeatherPlan.Window spring = plan.of(Season.SPRING);
        check(spring.clearMin() == 8 && spring.clearMax() == 20 && spring.wetMin() == 3 && spring.wetMax() == 8, "spring window");

        WeatherPlan odd = new WeatherPlan(new WeatherPlan.Window[]{new WeatherPlan.Window(5, 2, 0, 0, 0),
                WeatherPlan.DEFAULTS[1], WeatherPlan.DEFAULTS[2], WeatherPlan.DEFAULTS[3]});
        boolean sane = true;
        for (int i = 0; i < 100; i++) {
            sane &= odd.next(Season.WINTER, true, r).minutes() >= 1 && odd.next(Season.WINTER, false, r).minutes() >= 1;
        }
        check(sane, "weather: reversed or zero ranges from a config still give at least a minute");
    }

    private static void harvest() {
        HarvestRules h = new HarvestRules(HarvestRules.Numbers.defaults());
        check(h.crop(Season.SPRING, true, 0.10) == HarvestRules.Crop.BONUS, "spring bonus under 25%");
        check(h.crop(Season.SUMMER, false, 0.24) == HarvestRules.Crop.BONUS, "summer bonus in greenhouse too");
        check(h.crop(Season.SUMMER, true, 0.25) == HarvestRules.Crop.NORMAL, "summer normal at 25%");
        check(h.crop(Season.AUTUMN, true, 0.0) == HarvestRules.Crop.NORMAL, "autumn crops as vanilla");
        check(h.crop(Season.WINTER, true, 0.59) == HarvestRules.Crop.CANCEL, "winter open sky cancels under 60%");
        check(h.crop(Season.WINTER, true, 0.60) == HarvestRules.Crop.NORMAL, "winter open sky passes at 60%");
        check(h.crop(Season.WINTER, false, 0.0) == HarvestRules.Crop.NORMAL, "winter greenhouse grows");

        check(h.cancelBreed(Season.WINTER, 0.39) && !h.cancelBreed(Season.WINTER, 0.40), "winter breed 40%");
        check(!h.cancelBreed(Season.SPRING, 0.0) && !h.cancelBreed(Season.AUTUMN, 0.0), "breed ok outside winter");

        check(h.babyAge(Season.SPRING, -24000) == -12000, "spring baby half age");
        check(h.babyAge(Season.SUMMER, -24000) == -24000, "summer baby vanilla");
        check(h.babyAge(Season.SPRING, 6000) == 6000, "adult age untouched");

        check(h.fishWait(Season.AUTUMN) == 0.75 && h.fishWait(Season.WINTER) == 1.0, "fish wait factor");
    }

    private static void winter() {
        BiomeWinter table = new BiomeWinter(Map.of());
        check("minecraft:snowy_plains".equals(table.winterOf("minecraft:meadow")), "meadow -> snowy plains");
        check("minecraft:snowy_taiga".equals(table.winterOf("minecraft:cherry_grove")), "cherry grove -> snowy taiga");
        check("minecraft:snowy_taiga".equals(table.winterOf("minecraft:mangrove_swamp")), "mangrove -> snowy taiga");
        check("minecraft:deep_frozen_ocean".equals(table.winterOf("minecraft:deep_lukewarm_ocean")), "deep lukewarm -> deep frozen");
        check("minecraft:frozen_river".equals(table.winterOf("minecraft:river"))
                && "minecraft:snowy_beach".equals(table.winterOf("minecraft:beach")), "river and beach");
        check(table.winterOf("minecraft:desert") == null && table.winterOf("minecraft:windswept_hills") == null
                && table.winterOf("minecraft:snowy_plains") == null && table.winterOf("terralith:alpha_islands") == null,
                "unlisted biomes untouched");

        BiomeWinter extra = new BiomeWinter(Map.of("terralith:lush_valley", "snowy_plains", "minecraft:beach", ""));
        check("minecraft:snowy_plains".equals(extra.winterOf("terralith:lush_valley")), "extra biome from config");
        check(extra.winterOf("minecraft:beach") == null, "config can keep a built-in biome unchanged");

        String[] cells = new String[1536];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = i < 700 ? "minecraft:plains" : i % 3 == 0 ? "minecraft:river" : "minecraft:dripstone_caves";
        }
        BiomeWinter.Packed packed = BiomeWinter.pack(cells);
        check(packed.indices().length == 1536 && packed.palette().split(",").length == 3, "pack palette of 3");
        check(java.util.Arrays.equals(BiomeWinter.unpack(packed), cells), "pack roundtrip");

        int code = FormedLog.encode(15, 383, 7);
        check(FormedLog.x(code) == 15 && FormedLog.y(code) == 383 && FormedLog.z(code) == 7, "formed encode roundtrip");
        int[] empty = new int[0];
        int[] one = FormedLog.add(empty, code, 2);
        check(one != null && one.length == 1 && one[0] == code, "formed add new");
        check(FormedLog.add(one, code, 2) == one, "formed add duplicate keeps array");
        int[] two = FormedLog.add(one, FormedLog.encode(0, 0, 0), 2);
        check(two != null && two.length == 2 && FormedLog.add(two, FormedLog.encode(1, 1, 1), 2) == null, "formed limit refuses");

        check(WinterWorld.WINTER.toString().equals("solstice:winter") && LegacyKeys.WINTER.toString().equals("matsuri:winter")
                && LegacyKeys.FORMED.toString().equals("matsuri:formed") && LegacyKeys.SEEN.toString().equals("matsuriseasons:season_seen"),
                "chunk keys: new solstice:*, legacy matsuri:*");
    }

    private static void skyPack() throws IOException {
        Map<String, String> files = SkyPack.files("matsuri", 7, Settings.DEFAULT_SKY, 107);
        String live = Files.readString(resource("matsuri-year.json"), StandardCharsets.UTF_8);
        check(files.get("data/matsuri/timeline/year.json").equals(live), "sky: timeline matches Matsuri's hand-made year.json byte for byte");
        check(files.get("data/matsuri/world_clock/season.json").equals("{}\n"), "sky: world clock file");
        check(files.get("data/minecraft/tags/timeline/in_overworld.json").equals("{\"replace\": false, \"values\": [\"matsuri:year\"]}\n"),
                "sky: overworld tag matches Matsuri's");
        check(List.copyOf(files.keySet()).getLast().equals(SkyPack.MCMETA), "sky: pack.mcmeta is written last");

        Map<String, String> fresh = SkyPack.files("solstice", 7, Settings.DEFAULT_SKY, 121);
        check(fresh.get("data/solstice/timeline/year.json").contains("\"clock\": \"solstice:season\""), "sky: default namespace");
        check(fresh.get(SkyPack.MCMETA).contains("\"min_format\": [101, 0]") && fresh.get(SkyPack.MCMETA).contains("\"max_format\": [121, 2147483647]"),
                "sky: pack format from 26.1 up to this server's");
        check(SkyPack.mcmeta(0).contains("\"max_format\": [101,"), "sky: unknown format never goes below 26.1's");

        check(SkyPack.argb("#ffffff") == -1 && SkyPack.argb("#000000") == -16777216 && SkyPack.argb("d6e0ef") == -2694929, "argb");
        check(SkyPack.attribute("fog_color").equals("minecraft:visual/fog_color") && SkyPack.attribute("x:y").equals("x:y"), "attribute names");

        List<long[]> frames = SkyPack.keyframes(168_000, List.of("#000001", "#000002", "#000003", "#000004"));
        boolean sorted = true;
        for (int i = 1; i < frames.size(); i++) {
            sorted &= frames.get(i)[0] > frames.get(i - 1)[0];
        }
        check(frames.size() == 8 && sorted && frames.getFirst()[0] == 168_000 && frames.getLast()[0] == 8_592_000
                && frames.getLast()[1] == SkyPack.argb("#000004") && frames.getFirst()[1] == SkyPack.argb("#000001"), "keyframes: plateaus and blends around each boundary");
        check(SkyPack.timeline("x", 7, Map.of()).equals("{\n  \"clock\": \"x:season\",\n  \"period_ticks\": 8760000,\n  \"tracks\": {}\n}\n"),
                "timeline with no tracks is still valid JSON");
    }

    private static void skyInstall() throws IOException {
        Path dir = Files.createTempDirectory("solstice-pack");
        try {
            Path pack = dir.resolve("matsuri_seasons");
            Map<String, String> files = SkyPack.files("matsuri", 7, Settings.DEFAULT_SKY, 107);
            check(SkyPack.install(pack, files) == SkyPack.Result.NEEDS_RESTART, "install: fresh pack needs a restart");
            check(SkyPack.install(pack, files) == SkyPack.Result.UNCHANGED, "install: second run changes nothing");

            // A hand-made pack with identical data but another pack.mcmeta (the Matsuri migration case)
            Files.writeString(pack.resolve(SkyPack.MCMETA), "{\"pack\": {\"description\": \"old\"}}\n");
            check(SkyPack.install(pack, files) == SkyPack.Result.META_ONLY, "install: only pack.mcmeta differs — no restart");

            Map<String, String> renamed = SkyPack.files("solstice", 7, Settings.DEFAULT_SKY, 107);
            check(SkyPack.install(pack, renamed) == SkyPack.Result.NEEDS_RESTART, "install: namespace change needs a restart");
            check(!Files.exists(pack.resolve("data/matsuri/timeline/year.json"))
                    && !Files.exists(pack.resolve("data/matsuri/world_clock/season.json"))
                    && Files.exists(pack.resolve("data/solstice/timeline/year.json")), "install: old namespace's clock and timeline removed");
            check(Files.readString(pack.resolve("data/minecraft/tags/timeline/in_overworld.json")).contains("solstice:year"),
                    "install: tag points to the new timeline");
            try (var walk = Files.walk(pack)) {
                check(walk.noneMatch(f -> f.getFileName().toString().endsWith(".tmp")), "install: no temporary files left");
            }
        } finally {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
            }
        }
    }

    private static void settings() {
        YamlConfiguration file = YamlConfiguration.loadConfiguration(new File("config.yml"));
        List<String> warnings = new ArrayList<>();
        Settings s = Settings.from(file, warnings::add);
        Settings d = Settings.from(new YamlConfiguration(), w -> { });
        check(warnings.isEmpty(), "config.yml: no warnings " + warnings);
        check(s.language().equals("en") && s.perPlayerLanguage() && s.worlds().equals(List.of("world")), "config.yml: language and worlds");
        check(s.mode().equals("real-date") && !s.south() && s.daysPerSeason() == 8 && s.startSeason() == Season.SPRING, "config.yml: calendar");
        check(java.util.Arrays.equals(s.seasonColors(), Settings.DEFAULT_SEASON_COLORS), "config.yml: season colours equal code defaults");
        check(java.util.Arrays.equals(s.dayRate(), d.dayRate()) && java.util.Arrays.equals(s.nightRate(), d.nightRate()),
                "config.yml: day length equals code defaults");
        check(s.skyColors().equals(Settings.DEFAULT_SKY), "config.yml: sky colours equal code defaults");
        check(s.namespace().equals("solstice") && s.datapackFolder().equals("solstice") && s.transitionDays() == 7, "config.yml: sky names");
        boolean windows = true;
        for (int i = 0; i < 4; i++) {
            windows &= s.weatherWindows()[i].equals(WeatherPlan.DEFAULTS[i]);
        }
        check(windows, "config.yml: weather equals code defaults");
        HarvestRules.Numbers n = HarvestRules.Numbers.defaults();
        check(java.util.Arrays.equals(s.harvest().cropBonus(), n.cropBonus()) && java.util.Arrays.equals(s.harvest().cropCancel(), n.cropCancel())
                && java.util.Arrays.equals(s.harvest().breedCancel(), n.breedCancel())
                && java.util.Arrays.equals(s.harvest().babyGrowth(), n.babyGrowth())
                && java.util.Arrays.equals(s.harvest().fishWait(), n.fishWait()), "config.yml: crops, animals, fishing equal code defaults");
        check(s.particlesPerPlayer() == 24 && s.particleRadius() == 12 && s.particleInterval() == 10 && s.particlesBedrock(), "config.yml: particles");
        check(s.chunksPerTick() == 10 && s.formedLimit() == 8192 && s.extraBiomes().isEmpty(), "config.yml: winter");
        check(!s.tab() && !s.json() && s.tabFile().equals("plugins/TAB/animations.yml") && s.tabAnimation().equals("season")
                && s.tabLanguage().isEmpty(), "config.yml: integrations off by default");
        check(s.announceWorlds().equals(List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"))
                && s.subtitleColor().equals("#8a83c2"), "config.yml: announce");

        YamlConfiguration bad = new YamlConfiguration();
        bad.set("calendar.mode", "lunar");
        bad.set("calendar.timezone", "Mars/Olympus");
        bad.set("calendar.hemisphere", "east");
        bad.set("colors.winter", "blue");
        bad.set("sky.namespace", "Bad Name");
        bad.set("sky.datapack-folder", "../escape");
        bad.set("sky.colors.sky_color", List.of("#ffffff"));
        List<String> w = new ArrayList<>();
        Settings b = Settings.from(bad, w::add);
        check(b.mode().equals("real-date") && !b.south() && b.seasonColors()[0].equals("#9fc3e6") && b.namespace().equals("solstice")
                && b.datapackFolder().equals("solstice") && b.skyColors().get("sky_color").equals(Settings.DEFAULT_SKY.get("sky_color")),
                "bad config values fall back to defaults");
        check(w.size() == 7, "each bad value warns once (" + w.size() + ")");

        YamlConfiguration matsuri = new YamlConfiguration();
        matsuri.set("calendar.timezone", "Europe/Kyiv");
        matsuri.set("calendar.hemisphere", "south");
        matsuri.set("calendar.mode", "game-days");
        Settings m = Settings.from(matsuri, x -> { });
        check(m.zone().equals(KYIV) && m.south() && m.mode().equals("game-days"), "calendar options are read");
    }

    private static void versions() {
        check(McVersion.parse("1.21.4").equals(new McVersion(1, 21, 4)), "parse 1.21.4");
        check(McVersion.parse("26.3").equals(new McVersion(26, 3, 0)), "parse 26.3");
        check(McVersion.parse("26.1.2").equals(new McVersion(26, 1, 2)), "parse 26.1.2");
        check(McVersion.parse("26.3-pre-2").equals(new McVersion(26, 3, 0)), "parse a pre-release");
        check(McVersion.parse("garbage") == null && McVersion.parse(null) == null, "parse garbage");
        check(new McVersion(26, 1, 0).atLeast(McVersion.V26_1) && !new McVersion(1, 21, 11).atLeast(McVersion.V26_1),
                "world clocks from 26.1, not 1.21.11");
        check(new McVersion(1, 21, 5).atLeast(McVersion.V1_21_5) && !new McVersion(1, 21, 4).atLeast(McVersion.V1_21_5), "1.21.5 boundary");
        check(new McVersion(1, 21, 11).compareTo(new McVersion(1, 21, 8)) > 0, "1.21.11 is after 1.21.8");
        // Formats read from each vanilla server jar's version.json (see README)
        check(new McVersion(26, 1, 2).dataPackFormat() == 101 && new McVersion(26, 2, 0).dataPackFormat() == 107
                && new McVersion(26, 3, 0).dataPackFormat() == 121 && new McVersion(1, 21, 11).dataPackFormat() == 0, "data pack formats");
    }

    static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z-]+)}");

    private static Set<String> placeholders(String text) {
        Set<String> out = new TreeSet<>();
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static void langFiles() {
        YamlConfiguration en = YamlConfiguration.loadConfiguration(new File("lang/en.yml"));
        Set<String> enKeys = leafKeys(en);
        check(enKeys.size() > 40, "en.yml has keys (" + enKeys.size() + ")");
        for (String code : Messages.BUNDLED) {
            File f = new File("lang/" + code + ".yml");
            check(f.isFile(), "lang file exists: " + code);
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            Set<String> keys = leafKeys(y);
            Set<String> missing = new TreeSet<>(enKeys);
            missing.removeAll(keys);
            Set<String> extra = new TreeSet<>(keys);
            extra.removeAll(enKeys);
            check(missing.isEmpty() && extra.isEmpty(), code + ".yml has the same keys as en.yml (missing " + missing + ", extra " + extra + ")");
            boolean sameHolders = true;
            for (String k : enKeys) {
                if (y.isString(k) && !placeholders(y.getString(k)).equals(placeholders(en.getString(k)))) {
                    sameHolders = false;
                    System.out.println("  " + code + ": placeholders differ in " + k);
                }
            }
            check(sameHolders, code + ".yml uses the same {placeholders} as en.yml");
            boolean dateOk;
            try {
                String formatted = DateTimeFormatter.ofPattern(y.getString("date-format"), Messages.locale(code)).format(LocalDate.of(2027, 2, 28));
                dateOk = formatted.contains("28");
            } catch (IllegalArgumentException e) {
                dateOk = false;
            }
            check(dateOk, code + ".yml date-format is a valid pattern");
            check(!f.getName().equals("ru.yml"), "no ru");
        }
        check(!new File("lang/ru.yml").exists(), "there is no ru.yml");
        for (Map.Entry<Season, List<Texts.Effect>> e : Texts.EFFECTS.entrySet()) {
            for (Texts.Effect effect : e.getValue()) {
                String key = "effects." + e.getKey().key + "." + effect.key();
                check(en.isString(key), "en.yml has " + key);
            }
        }
        check(en.isString("effects.winter.melt-date"), "en.yml has the dated melt line");

        // The Ukrainian text is Matsuri's, word for word
        YamlConfiguration uk = YamlConfiguration.loadConfiguration(new File("lang/uk.yml"));
        check(uk.getString("seasons.winter.name").equals("❄ Сніг") && uk.getString("seasons.spring.name").equals("🌸 Сакура")
                && uk.getString("seasons.summer.name").equals("☀ Спека") && uk.getString("seasons.autumn.name").equals("🍁 Багрянець"),
                "uk season names are Matsuri's");
        check(uk.getString("effects.winter.biomes").equals("Біоми сніжні: сніг замість дощу, ріки й ставки замерзають (біля ріллі — ні)")
                && uk.getString("effects.autumn.weather").equals("Довгі дощі; під кронами падає руде листя")
                && uk.getString("announce.winter.subtitle").equals("Ночі довгі. Тримайтеся світла.")
                && uk.getString("announce.hint").equals("Що пора міняє в грі — /season"), "uk effects and titles are Matsuri's");
        String header = Messages.fill(uk.getString("season.header"), Map.of("season", "❄ Сніг"))
                + Messages.fill(uk.getString("season.until"), Map.of("until",
                Messages.fill(uk.getString("until.date"), Map.of("date",
                        DateTimeFormatter.ofPattern(uk.getString("date-format"), Messages.locale("uk")).format(LocalDate.of(2026, 11, 30))))));
        check(header.equals("Пора: ❄ Сніг — до 30 листопада"), "uk /season header reads as before: " + header);
        String melt = Messages.fill(uk.getString("effects.winter.melt-date"), Map.of("date",
                DateTimeFormatter.ofPattern(uk.getString("date-format"), Messages.locale("uk")).format(LocalDate.of(2027, 3, 1))));
        check(melt.equals("1 березня природний сніг і лід розтануть, поставлене вами лишиться"), "uk melt line reads as before");
    }

    private static Set<String> leafKeys(YamlConfiguration y) {
        Set<String> out = new TreeSet<>();
        for (String k : y.getKeys(true)) {
            if (!y.isConfigurationSection(k)) {
                out.add(k);
            }
        }
        return out;
    }

    private static void messages() throws IOException {
        Set<String> available = Set.of("en", "uk", "de", "pt_BR", "zh_CN");
        check(Messages.resolve("uk_UA", available, "en").equals("uk"), "resolve: uk_UA -> uk");
        check(Messages.resolve("pt_br", available, "en").equals("pt_BR"), "resolve: pt_br -> pt_BR");
        check(Messages.resolve("pt_PT", available, "en").equals("pt_BR"), "resolve: pt_PT -> any Portuguese");
        check(Messages.resolve("zh_TW", available, "en").equals("zh_CN"), "resolve: zh_TW -> zh_CN");
        check(Messages.resolve("ru_RU", available, "en").equals("en"), "resolve: a language without a file -> the fallback");
        check(Messages.resolve("", available, "en").equals("en") && Messages.resolve(null, available, "en").equals("en"), "resolve: empty");
        check(Messages.resolve("de-DE", available, "en").equals("de"), "resolve: dash form");
        check(Messages.fill("a {x} {y}", Map.of("x", "1")).equals("a 1 {y}"), "fill leaves unknown braces");

        Path dir = Files.createTempDirectory("solstice-lang");
        try {
            Files.createDirectories(dir.resolve("lang"));
            // An owner's partial override: one key changed, one new language
            Files.writeString(dir.resolve("lang/uk.yml"), "announce:\n  winter:\n    chat: \"Зима на Матсурі — {until}.\"\n");
            Files.writeString(dir.resolve("lang/eo.yml"), "seasons:\n  winter:\n    name: \"❄ Vintro\"\n");
            Logger log = Logger.getLogger("test");
            log.setUseParentHandlers(false);
            Messages m = new Messages(dir.toFile(), "en", true, log);
            check(Files.isRegularFile(dir.resolve("lang/en.yml")), "messages: the default language file is extracted");
            check(m.raw("uk", "announce.winter.chat").equals("Зима на Матсурі — {until}."), "messages: owner's file wins");
            check(m.raw("uk", "announce.spring.chat").equals("Весна — {until}."), "messages: bundled copy fills keys the owner's file lacks");
            check(m.raw("eo", "seasons.winter.name").equals("❄ Vintro") && m.raw("eo", "seasons.spring.name").equals("🌸 Spring"),
                    "messages: a new language from the folder, English for the rest");
            check(m.raw("de", "no.such.key").equals("no.such.key"), "messages: unknown key shows the key");
            check(m.date("uk", LocalDate.of(2026, 11, 30)).equals("30 листопада"), "messages: Ukrainian date");
            check(m.date("en", LocalDate.of(2026, 11, 30)).equals("November 30"), "messages: English date");
            check(m.date("de", LocalDate.of(2027, 3, 1)).equals("1. März"), "messages: German date");
            check(m.list("en", "nothing").isEmpty(), "messages: missing list is empty");
            check(m.defaultLang().equals("en"), "messages: default language");
            Messages fallbackUk = new Messages(dir.toFile(), "xx", false, log);
            check(fallbackUk.defaultLang().equals("en"), "messages: unknown default language falls back to en");
        } finally {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
            }
        }
        check(Locale.forLanguageTag("pt-BR").getCountry().equals("BR") && Messages.locale("zh_CN").getCountry().equals("CN"), "locale codes");
    }

    private static Path resource(String name) {
        Path p = Path.of("src/test/resources", name);
        return p;
    }
}
