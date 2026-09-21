package dev.thathunky.solstice;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/**
 * Every player-visible string comes from {@code lang/<code>.yml}. The language is the player's client
 * locale when a file exists for it (and {@code per-player-language} is on), otherwise {@code language}
 * from config.yml, otherwise English.
 *
 * <p>Lookup, key by key: the file in the plugin folder's {@code lang/} (edited by the server owner, or
 * a whole new language they added) wins; then the copy bundled in the jar, which covers keys added by
 * a later version; then the configured default language; then English, which logs a warning once
 * per key. Values are MiniMessage strings with {@code {placeholders}}.
 */
final class Messages {

    static final List<String> BUNDLED = List.of("en", "uk", "de", "es", "fr", "pl", "pt_BR", "ja", "zh_CN");
    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** One language: the on-disk file over the bundled copy. */
    private record Layers(YamlConfiguration disk, YamlConfiguration jar) {
        String string(String key) {
            String v = disk == null ? null : disk.getString(key);
            return v != null ? v : jar == null ? null : jar.getString(key);
        }

        List<String> list(String key) {
            if (disk != null && disk.isList(key)) {
                return disk.getStringList(key);
            }
            return jar != null && jar.isList(key) ? jar.getStringList(key) : null;
        }
    }

    private final Map<String, Layers> languages = new HashMap<>();
    private final String fallback;
    private final boolean perPlayer;
    private final Logger log;
    private final Set<String> warned = new HashSet<>();

    Messages(File dataFolder, String language, boolean perPlayer, Logger log) {
        this.log = log;
        this.perPlayer = perPlayer;
        File dir = new File(dataFolder, "lang");
        extract(new File(dir, language + ".yml"), language, log);
        Set<String> codes = new TreeSet<>(BUNDLED);
        File[] own = dir.listFiles((d, name) -> name.endsWith(".yml"));
        if (own != null) {
            for (File f : own) {
                codes.add(f.getName().substring(0, f.getName().length() - 4));
            }
        }
        for (String code : codes) {
            File f = new File(dir, code + ".yml");
            languages.put(code, new Layers(f.isFile() ? YamlConfiguration.loadConfiguration(f) : null, bundled(code, log)));
        }
        if (!languages.containsKey(language)) {
            log.warning("language \"" + language + "\" has no file (bundled: " + String.join(", ", BUNDLED) + ") — using en");
        }
        this.fallback = languages.containsKey(language) ? language : "en";
    }

    /** The language code for this sender: client locale for players, the default for the console. */
    String lang(CommandSender sender) {
        if (perPlayer && sender instanceof Player p) {
            Locale l = p.locale();
            return resolve(l.getLanguage() + (l.getCountry().isEmpty() ? "" : "_" + l.getCountry()), languages.keySet(), fallback);
        }
        return fallback;
    }

    String defaultLang() {
        return fallback;
    }

    /**
     * Picks a file for a client locale such as {@code pt_br} or {@code zh_TW}: exact match, then the bare
     * language ({@code pt}), then any file of that language ({@code zh_CN}), then the fallback.
     */
    static String resolve(String locale, Set<String> available, String fallback) {
        if (locale == null || locale.isBlank()) {
            return fallback;
        }
        String[] parts = locale.replace('-', '_').split("_");
        String language = parts[0].toLowerCase(Locale.ROOT);
        if (parts.length > 1) {
            String exact = language + "_" + parts[1].toUpperCase(Locale.ROOT);
            if (available.contains(exact)) {
                return exact;
            }
        }
        if (available.contains(language)) {
            return language;
        }
        for (String code : new TreeSet<>(available)) {
            if (code.startsWith(language + "_")) {
                return code;
            }
        }
        return fallback;
    }

    /** The raw template: chosen language, then the default language, then English, then the key itself. */
    String raw(String lang, String key) {
        for (String code : chain(lang)) {
            Layers l = languages.get(code);
            String v = l == null ? null : l.string(key);
            if (v != null) {
                if (code.equals("en") && !lang.equals("en") && warned.add(lang + ":" + key)) {
                    log.warning("lang/" + lang + ".yml has no \"" + key + "\" — using English");
                }
                return v;
            }
        }
        return key;
    }

    List<String> list(String lang, String key) {
        for (String code : chain(lang)) {
            Layers l = languages.get(code);
            List<String> v = l == null ? null : l.list(key);
            if (v != null) {
                return v;
            }
        }
        return List.of();
    }

    private List<String> chain(String lang) {
        List<String> chain = new ArrayList<>(3);
        chain.add(lang);
        if (!chain.contains(fallback)) {
            chain.add(fallback);
        }
        if (!chain.contains("en")) {
            chain.add("en");
        }
        return chain;
    }

    Component get(String lang, String key, Map<String, String> placeholders) {
        return MM.deserialize(fill(raw(lang, key), placeholders));
    }

    /** A ready template (already filled) as a component. */
    static Component parse(String template) {
        return MM.deserialize(template);
    }

    Component get(CommandSender to, String key, Map<String, String> placeholders) {
        return get(lang(to), key, placeholders);
    }

    void send(CommandSender to, String key, Map<String, String> placeholders) {
        to.sendMessage(get(to, key, placeholders));
    }

    /** Sends a template as plain text, for strings full of angle brackets like command usage. */
    void sendPlain(CommandSender to, String key) {
        to.sendMessage(Component.text(raw(lang(to), key)));
    }

    void send(CommandSender to, String key) {
        send(to, key, Map.of());
    }

    /** A date in the language's own {@code date-format}, e.g. "30 листопада" or "November 30". */
    String date(String lang, LocalDate day) {
        String pattern = raw(lang, "date-format");
        try {
            return DateTimeFormatter.ofPattern(pattern, locale(lang)).format(day);
        } catch (IllegalArgumentException e) {
            return day.toString();
        }
    }

    static Locale locale(String code) {
        return Locale.forLanguageTag(code.replace('_', '-'));
    }

    /** Replaces every {key} the map knows; unknown braces are left alone. */
    static String fill(String template, Map<String, String> placeholders) {
        String out = template;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue());
        }
        return out;
    }

    private static void extract(File file, String language, Logger log) {
        if (file.isFile()) {
            return;
        }
        try (InputStream in = Messages.class.getClassLoader().getResourceAsStream("lang/" + language + ".yml")) {
            if (in != null) {
                file.getParentFile().mkdirs();
                Files.copy(in, file.toPath());
            }
        } catch (IOException e) {
            log.warning("could not extract lang/" + language + ".yml: " + e);
        }
    }

    private static YamlConfiguration bundled(String code, Logger log) {
        try (InputStream in = Messages.class.getClassLoader().getResourceAsStream("lang/" + code + ".yml")) {
            return in == null ? null : YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warning("could not read the bundled lang/" + code + ".yml: " + e);
            return null;
        }
    }
}
