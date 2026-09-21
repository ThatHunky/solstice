package dev.thathunky.solstice;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/** /season for everyone, /solstice (alias /seasons) for admins. */
final class Commands implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of("status", "preview", "set", "probe", "chunk", "reload");
    private static final List<String> SEASON_WORDS = List.of("winter", "spring", "summer", "autumn");

    private final Solstice plugin;

    Commands(Solstice plugin) {
        this.plugin = plugin;
    }

    static boolean isAdmin(CommandSender s) {
        // matsuri.seasons.admin: the node of the plugin Solstice was extracted from, kept so existing grants still work
        return s.hasPermission("solstice.admin") || s.hasPermission("matsuri.seasons.admin");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Solstice.Running r = plugin.running();
        if (r == null) {
            return true;
        }
        Messages m = r.messages();
        if (command.getName().equals("season")) {
            Season s = r.state().current();
            r.texts().sendSeason(sender, s, r.state().position(), r.state().preview() != null);
            return true;
        }
        if (!isAdmin(sender)) {
            m.send(sender, "admin.no-permission");
            return true;
        }
        if (args.length == 0) {
            m.sendPlain(sender, "admin.usage");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> {
                SeasonState st = r.state();
                String preview = st.preview() == null ? "" : m.raw(m.lang(sender), "admin.status-preview").replace("{season}", st.preview().key);
                m.send(sender, "admin.status", Map.of("season", st.calendarSeason().key, "preview", preview,
                        "ticks", String.valueOf(st.clockTicks())));
                m.send(sender, "admin.status-details", Map.of("version", plugin.getPluginMeta().getVersion(),
                        "mode", st.calendar().mode(), "minecraft", r.features().version.toString(),
                        "features", r.features().summary()));
            }
            case "preview" -> {
                if (args.length < 2) {
                    m.sendPlain(sender, "admin.usage");
                    return true;
                }
                Season s = "off".equalsIgnoreCase(args[1]) ? null : Season.byKey(args[1]);
                if (s == null && !"off".equalsIgnoreCase(args[1])) {
                    m.sendPlain(sender, "admin.usage");
                    return true;
                }
                r.state().preview(s);
                r.clock().forceClock();
                r.weather().forEach(Weather::reset);
                if (s == null) {
                    m.send(sender, "admin.preview-off");
                } else {
                    m.send(sender, "admin.preview-set", Map.of("season", s.key));
                }
            }
            case "set" -> {
                Season s = args.length < 2 ? null : Season.byKey(args[1]);
                if (s == null) {
                    m.sendPlain(sender, "admin.usage");
                    return true;
                }
                if (r.counter() == null || !(r.state().calendar() instanceof GameDaysCalendar g)) {
                    m.sendPlain(sender, "admin.set-real-date");
                    return true;
                }
                r.counter().set(g.startOf(s, r.counter().elapsed()));
                plugin.saveState();
                r.clock().forceClock();
                r.weather().forEach(Weather::reset);
                m.send(sender, "admin.set", Map.of("season", s.key));
            }
            case "probe" -> {
                // probe <x> <y> <z> <season> [night] — where particles would appear near a point, without a player
                Season s = args.length < 5 ? null : Season.byKey(args[4]);
                if (s == null) {
                    m.sendPlain(sender, "admin.usage");
                    return true;
                }
                if (r.ambience() == null || r.ambience().particleFor(s) == null) {
                    m.send(sender, "admin.feature-off", Map.of("feature", "particles"));
                    return true;
                }
                World world = sender instanceof Player p && r.worlds().contains(p.getWorld()) ? p.getWorld() : r.worlds().getFirst();
                Location at;
                try {
                    at = new Location(world, Double.parseDouble(args[1]), Double.parseDouble(args[2]), Double.parseDouble(args[3]));
                } catch (NumberFormatException e) {
                    m.sendPlain(sender, "admin.usage");
                    return true;
                }
                Map<String, Integer> hits = r.ambience().probe(at, s, args.length > 5 && "night".equalsIgnoreCase(args[5]), 200);
                m.send(sender, "admin.probe", Map.of("rounds", "200", "hits", hits.toString()));
            }
            case "chunk" -> {
                // chunk <x> <z> — a chunk's winter state, by block coordinates
                if (args.length < 3) {
                    m.sendPlain(sender, "admin.usage");
                    return true;
                }
                if (r.winter().isEmpty()) {
                    m.send(sender, "admin.feature-off", Map.of("feature", "winter"));
                    return true;
                }
                World world = sender instanceof Player p && r.winterByWorld().containsKey(p.getWorld()) ? p.getWorld() : r.worlds().getFirst();
                WinterWorld w = r.winterByWorld().getOrDefault(world, r.winter().getFirst());
                Map<String, String> info;
                try {
                    info = w.describe(Integer.parseInt(args[1]), Integer.parseInt(args[2]));
                } catch (NumberFormatException e) {
                    m.sendPlain(sender, "admin.usage");
                    return true;
                }
                if (info == null) {
                    m.send(sender, "admin.chunk-unloaded");
                } else {
                    m.send(sender, "admin.chunk", info);
                }
            }
            case "reload" -> {
                plugin.reload();
                plugin.running().messages().send(sender, "admin.reload");
            }
            default -> m.sendPlain(sender, "admin.usage");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equals("season") || !isAdmin(sender)) {
            return List.of();
        }
        if (args.length == 1) {
            return starting(SUBCOMMANDS, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && (sub.equals("preview") || sub.equals("set"))) {
            List<String> words = new ArrayList<>(SEASON_WORDS);
            if (sub.equals("preview")) {
                words.add("off");
            }
            return starting(words, args[1]);
        }
        if (sub.equals("probe") && args.length == 5) {
            return starting(SEASON_WORDS, args[4]);
        }
        if (sub.equals("probe") && args.length == 6) {
            return starting(List.of("night"), args[5]);
        }
        return List.of();
    }

    private static List<String> starting(List<String> options, String prefix) {
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.startsWith(prefix.toLowerCase(Locale.ROOT))) {
                out.add(o);
            }
        }
        return out;
    }
}
