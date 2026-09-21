package dev.thathunky.solstice;

import io.papermc.paper.datapack.Datapack;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

public final class Solstice extends JavaPlugin {

    /** Everything one start (or reload) set up; replaced as a whole on reload. */
    record Running(Settings settings, Features features, Messages messages, Texts texts, SeasonState state,
                   List<World> worlds, GameDayCounter counter, SeasonClock clock, List<Weather> weather,
                   Ambience ambience, List<WinterWorld> winter, Map<World, WinterWorld> winterByWorld,
                   boolean dayRate) {
    }

    private Running running;
    private Season keptPreview;

    Running running() {
        return running;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        start();
        Commands commands = new Commands(this);
        for (String name : List.of("season", "solstice")) {
            PluginCommand c = Objects.requireNonNull(getCommand(name), name);
            c.setExecutor(commands);
            c.setTabCompleter(commands);
        }
    }

    @Override
    public void onDisable() {
        stop();
    }

    void reload() {
        keptPreview = running == null ? null : running.state().preview();
        stop();
        reloadConfig();
        start();
    }

    private void start() {
        var log = getLogger();
        Settings s = Settings.from(getConfig(), log::warning);
        Features features = new Features(log);
        Messages messages = new Messages(getDataFolder(), s.language(), s.perPlayerLanguage(), log);

        World main = Bukkit.getWorlds().getFirst();
        List<World> worlds = new ArrayList<>();
        for (String name : s.worlds()) {
            World w = Bukkit.getWorld(name);
            if (w == null) {
                log.warning("world \"" + name + "\" from config.yml is not loaded — skipped");
            } else if (w.getEnvironment() != World.Environment.NORMAL) {
                log.warning("world \"" + name + "\" is not an overworld — seasons only act in overworlds, skipped");
            } else {
                worlds.add(w);
            }
        }
        if (worlds.isEmpty()) {
            log.warning("no usable world in config.yml's worlds — using the main world " + main.getName());
            worlds.add(main);
        }

        GameDayCounter counter = null;
        SeasonCalendar calendar;
        if (s.mode().equals("game-days")) {
            YamlConfiguration saved = YamlConfiguration.loadConfiguration(stateFile());
            long start = (long) s.startSeason().id() * s.daysPerSeason() * GameDaysCalendar.DAY;
            GameDayCounter c = new GameDayCounter(saved.getLong("game-days.elapsed-ticks", start));
            counter = c;
            calendar = new GameDaysCalendar(s.daysPerSeason(), c::elapsed);
        } else {
            calendar = new RealDateCalendar(s.zone(), s.south());
        }
        SeasonState state = new SeasonState(calendar);
        state.preview(keptPreview);

        boolean clock = setUpSky(s, features, main);
        boolean dayRate = s.dayLength() && features.worldClocks;
        features.note(log, "sky", clock, !s.sky() ? "config" : !features.worldClocks
                ? "needs Minecraft 26.1+ (world clocks), this is " + features.version : "datapack not loaded yet, restart the server");
        features.note(log, "day-length", dayRate, !s.dayLength() ? "config" : "needs Minecraft 26.1+ (world clocks), this is " + features.version);

        Bedrock bedrock = new Bedrock();
        Ambience ambience = null;
        if (s.particles()) {
            ambience = new Ambience(worlds, state, features, bedrock, s);
            getServer().getScheduler().runTaskTimer(this, ambience, 40L, s.particleInterval());
        }
        features.note(log, "particles", s.particles(), "config");
        for (Season season : Season.values()) {
            if (s.particles() && ambience.particleFor(season) == null) {
                features.note(log, "particles-" + season.key, false, "this Minecraft version has no particle for it (needs 1.21.5+)");
            }
        }

        List<Weather> weather = new ArrayList<>();
        if (s.weather()) {
            WeatherPlan plan = new WeatherPlan(s.weatherWindows());
            for (World w : worlds) {
                Weather wx = new Weather(w, state, plan);
                weather.add(wx);
                getServer().getScheduler().runTaskTimer(this, wx, 200L, 20L);
            }
        }
        features.note(log, "weather", s.weather(), "config");

        if (s.crops() || s.animals() || s.fishing()) {
            getServer().getPluginManager().registerEvents(new Harvest(state, new HashSet<>(worlds),
                    new HarvestRules(s.harvest()), s.crops(), s.animals(), s.fishing()), this);
        }
        features.note(log, "crops", s.crops(), "config");
        features.note(log, "animals", s.animals(), "config");
        features.note(log, "fishing", s.fishing(), "config");

        List<WinterWorld> winter = new ArrayList<>();
        Map<World, WinterWorld> winterByWorld = new LinkedHashMap<>();
        if (s.winter()) {
            BiomeWinter table = new BiomeWinter(s.extraBiomes());
            for (World w : worlds) {
                WinterWorld ww = new WinterWorld(this, state, w, table, s.chunksPerTick(), s.formedLimit());
                winter.add(ww);
                winterByWorld.put(w, ww);
                getServer().getPluginManager().registerEvents(ww, this);
                getServer().getScheduler().runTaskTimer(this, ww, 1L, 1L);
            }
        }
        features.note(log, "winter", s.winter(), "config");

        Ambience amb = ambience;
        Texts texts = new Texts(messages, s, f -> switch (f) {
            case "winter" -> s.winter();
            case "crops" -> s.crops();
            case "animals" -> s.animals();
            case "fishing" -> s.fishing();
            case "weather" -> s.weather();
            case "particles-spring" -> amb != null && amb.particleFor(Season.SPRING) != null;
            case "particles-summer" -> amb != null && amb.particleFor(Season.SUMMER) != null;
            case "particles-autumn" -> amb != null && amb.particleFor(Season.AUTUMN) != null;
            default -> false;
        });
        SeasonClock seasonClock = new SeasonClock(this, state, s, messages, texts, main, counter, clock, dayRate);
        getServer().getScheduler().runTaskTimer(this, seasonClock, 100L, 20L);
        if (counter != null) {
            getServer().getScheduler().runTaskTimer(this, this::saveState, 1200L, 1200L);
        }

        running = new Running(s, features, messages, texts, state, List.copyOf(worlds), counter, seasonClock,
                weather, ambience, winter, winterByWorld, dayRate);
        log.info("season: " + state.calendarSeason().key + " (" + calendar.mode() + "), clock ticks: " + state.clockTicks()
                + ", Minecraft " + features.version);
        log.info("features: " + features.summary());
    }

    /**
     * Writes the sky-colour datapack into the main world's datapacks folder when it is missing or out
     * of date. Returns whether the season clock can be driven right now, which is only when that pack
     * was already loaded when the server started — the clock and timeline are registries.
     */
    private boolean setUpSky(Settings s, Features features, World main) {
        if (!s.sky() || !features.worldClocks) {
            return false;
        }
        var log = getLogger();
        if (features.version.compareTo(McVersion.NEWEST_VERIFIED) > 0) {
            log.warning("Minecraft " + features.version + " is newer than the newest version the sky datapack was checked on ("
                    + McVersion.NEWEST_VERIFIED + "). If the server fails to load registries after a restart, delete world/datapacks/"
                    + s.datapackFolder() + " or set sky.enabled: false.");
        }
        Path folder = main.getWorldFolder().toPath().resolve("datapacks").resolve(s.datapackFolder());
        int format = features.version.dataPackFormat();
        try {
            SkyPack.Result result = SkyPack.install(folder, SkyPack.files(s.namespace(), s.transitionDays(), s.skyColors(),
                    format == 0 ? 121 : format));
            if (result == SkyPack.Result.NEEDS_RESTART) {
                log.warning("wrote the sky-colour datapack to " + folder + " — restart the server to load it (the season clock"
                        + " and timeline are registries and only load at startup)");
            } else if (result == SkyPack.Result.META_ONLY) {
                log.info("updated " + folder.resolve(SkyPack.MCMETA) + " (no restart needed)");
            }
        } catch (IOException | RuntimeException e) {
            log.warning("could not write the sky-colour datapack to " + folder + ": " + e);
            return false;
        }
        Datapack pack = Bukkit.getDatapackManager().getPack("file/" + s.datapackFolder());
        if (pack == null || !pack.isEnabled()) {
            return false;
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "time of " + s.namespace() + ":season pause");
        return true;
    }

    private void stop() {
        Running r = running;
        if (r == null) {
            return;
        }
        getServer().getScheduler().cancelTasks(this);
        HandlerList.unregisterAll(this);
        r.weather().forEach(Weather::release);
        if (r.dayRate()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "time of minecraft:overworld rate 1");
        }
        saveState();
        running = null;
    }

    private File stateFile() {
        return new File(getDataFolder(), "state.yml");
    }

    /** Saves the game-days count; the real-date calendar has no state. */
    void saveState() {
        Running r = running;
        if (r == null || r.counter() == null) {
            return;
        }
        YamlConfiguration y = new YamlConfiguration();
        y.set("game-days.elapsed-ticks", r.counter().elapsed());
        try {
            getDataFolder().mkdirs();
            y.save(stateFile());
        } catch (IOException e) {
            getLogger().warning("could not save state.yml: " + e);
        }
    }
}
