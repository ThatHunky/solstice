package dev.thathunky.solstice;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;

/**
 * Runs once a second: counts in-game days (game-days mode), sets the season clock, sets the day speed,
 * shows the season title to players who haven't seen it, and publishes the season to TAB and the
 * JSON file when it changes.
 */
final class SeasonClock implements Runnable {

    static final NamespacedKey SEEN = new NamespacedKey("solstice", "season_seen");

    private final JavaPlugin plugin;
    private final SeasonState state;
    private final Settings settings;
    private final Messages messages;
    private final Texts texts;
    private final World main;
    private final GameDayCounter counter;
    private final boolean clock;
    private final boolean dayRate;
    private final long clockEveryMs;
    private final Set<String> announceWorlds;
    private final Sound sound;
    private final TextColor subtitleColor;
    private final Path tabFile;
    private final Path jsonFile;
    private final Path publishedFile;

    private long nextClockAt;
    private Season rateSeason;
    private Boolean rateDay;

    SeasonClock(JavaPlugin plugin, SeasonState state, Settings settings, Messages messages, Texts texts, World main,
                GameDayCounter counter, boolean clock, boolean dayRate) {
        this.plugin = plugin;
        this.state = state;
        this.settings = settings;
        this.messages = messages;
        this.texts = texts;
        this.main = main;
        this.counter = counter;
        this.clock = clock;
        this.dayRate = dayRate;
        // A game-days season is a few hours long, so the clock moves in finer steps there.
        this.clockEveryMs = counter != null ? 60_000 : 600_000;
        this.announceWorlds = Set.copyOf(settings.announceWorlds());
        this.sound = Sound.sound(soundKey(settings.sound(), plugin), Sound.Source.MASTER, 0.8f, 1.0f);
        this.subtitleColor = TextColor.fromHexString(settings.subtitleColor());
        Path root = plugin.getServer().getWorldContainer().toPath();
        this.tabFile = settings.tab() ? root.resolve(settings.tabFile()) : null;
        this.jsonFile = settings.json() ? root.resolve(settings.jsonFile()) : null;
        this.publishedFile = plugin.getDataFolder().toPath().resolve("published-season.txt");
    }

    private static Key soundKey(String name, JavaPlugin plugin) {
        try {
            return Key.key(name.contains(":") ? name : "minecraft:" + name);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("announce.sound \"" + name + "\" is not a sound id — using block.amethyst_block.chime");
            return Key.key("minecraft:block.amethyst_block.chime");
        }
    }

    void forceClock() {
        nextClockAt = 0;
        rateSeason = null;
    }

    @Override
    public void run() {
        if (counter != null) {
            counter.sample(main.getFullTime());
        }
        long now = System.currentTimeMillis();
        if (now >= nextClockAt) {
            nextClockAt = now + clockEveryMs;
            if (clock) {
                command("time of " + settings.namespace() + ":season set " + state.clockTicks() + "t");
            }
            publishIfChanged();
        }
        Season season = state.current();
        if (dayRate) {
            boolean day = SeasonCalendar.isDay(main.getTime());
            if (season != rateSeason || rateDay == null || rateDay != day) {
                rateSeason = season;
                rateDay = day;
                double rate = day ? settings.dayRate()[season.id()] : settings.nightRate()[season.id()];
                command(String.format(Locale.ROOT, "time of minecraft:overworld rate %.3f", rate));
            }
        }
        if (settings.announce()) {
            announce(season);
        }
    }

    private void announce(Season season) {
        SeasonCalendar.Position position = state.position();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!announceWorlds.contains(p.getWorld().getKey().toString())) {
                continue; // a player in a login lobby doesn't "use up" the title there
            }
            var pdc = p.getPersistentDataContainer();
            Integer seen = pdc.get(SEEN, PersistentDataType.INTEGER);
            if (seen == null) {
                seen = legacySeen(p);
                if (seen != null) {
                    pdc.set(SEEN, PersistentDataType.INTEGER, seen);
                    pdc.remove(LegacyKeys.SEEN);
                }
            }
            if (seen != null && seen == season.id()) {
                continue;
            }
            String lang = messages.lang(p);
            TextColor color = texts.color(season);
            String key = "announce." + season.key;
            p.showTitle(Title.title(Component.text(texts.name(lang, season), color),
                    Component.empty().color(subtitleColor).append(messages.get(lang, key + ".subtitle", Map.of())),
                    Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofSeconds(1))));
            String until = state.preview() != null ? messages.raw(lang, "season.preview").strip() : texts.until(lang, position);
            p.sendMessage(Component.empty().color(color).append(messages.get(lang, key + ".chat", Map.of("until", until))));
            p.sendMessage(Component.empty().color(NamedTextColor.GRAY).append(messages.get(lang, "announce.hint", Map.of())));
            p.playSound(sound);
            pdc.set(SEEN, PersistentDataType.INTEGER, season.id());
        }
    }

    /**
     * What the predecessor recorded: its own player key, or before that the {@code ms_seen} scoreboard
     * of the datapack it replaced. Players who already saw this season there don't get the title again.
     */
    private static Integer legacySeen(Player p) {
        Integer old = p.getPersistentDataContainer().get(LegacyKeys.SEEN, PersistentDataType.INTEGER);
        if (old != null) {
            return old;
        }
        Objective o = Bukkit.getScoreboardManager().getMainScoreboard().getObjective("ms_seen");
        if (o == null) {
            return null;
        }
        Score score = o.getScore(p.getName());
        return score.isScoreSet() ? score.getScore() : null;
    }

    private void publishIfChanged() {
        if (state.preview() != null || tabFile == null && jsonFile == null) {
            return; // a preview is only for testing: TAB and the file show the calendar
        }
        SeasonCalendar.Position position = state.position();
        Season season = position.season();
        try {
            String stamp = season.id() + " " + position.cycle();
            String prev = Files.exists(publishedFile) ? Files.readString(publishedFile).strip() : "";
            // The first version wrote just the id; treat that as the same season
            if (prev.equals(stamp) || prev.equals(String.valueOf(season.id())) && counter == null) {
                return;
            }
            if (tabFile != null && Files.exists(tabFile)) {
                String lang = settings.tabLanguage().isBlank() ? messages.defaultLang() : settings.tabLanguage();
                String frame = "&" + settings.seasonColors()[season.id()] + texts.name(lang, season);
                writeAtomic(tabFile, Publish.rewriteAnimation(Files.readString(tabFile), settings.tabAnimation(), frame));
                if (Bukkit.getPluginManager().getPlugin("TAB") != null) {
                    command("tab reload");
                }
            }
            if (jsonFile != null) {
                Files.createDirectories(jsonFile.toAbsolutePath().getParent());
                writeAtomic(jsonFile, Publish.seasonJson(position) + "\n");
            }
            Files.createDirectories(publishedFile.getParent());
            writeAtomic(publishedFile, stamp + "\n");
            plugin.getLogger().info("season published: " + season.key);
        } catch (IOException | IllegalStateException e) {
            plugin.getLogger().warning("could not publish the season: " + e);
        }
    }

    private static void writeAtomic(Path target, String data) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, data);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void command(String cmd) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
    }
}
