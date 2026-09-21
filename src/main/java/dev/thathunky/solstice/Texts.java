package dev.thathunky.solstice;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.command.CommandSender;

/** Season texts assembled from the language files: names, "until", and what each season changes. */
final class Texts {

    /** Which toggles an effect line depends on; the line is shown if any of them is on. */
    record Effect(String key, String... needs) {
    }

    static final Map<Season, List<Effect>> EFFECTS = Map.of(
            Season.WINTER, List.of(new Effect("biomes", "winter"), new Effect("crops", "crops"),
                    new Effect("animals", "animals"), new Effect("melt", "winter")),
            Season.SPRING, List.of(new Effect("crops", "crops"), new Effect("animals", "animals"),
                    new Effect("particles", "particles-spring")),
            Season.SUMMER, List.of(new Effect("crops", "crops"), new Effect("weather", "weather"),
                    new Effect("particles", "particles-summer")),
            Season.AUTUMN, List.of(new Effect("fish", "fishing"), new Effect("weather", "weather", "particles-autumn")));

    private final Messages messages;
    private final Settings settings;
    private final java.util.function.Predicate<String> featureOn;

    Texts(Messages messages, Settings settings, java.util.function.Predicate<String> featureOn) {
        this.messages = messages;
        this.settings = settings;
        this.featureOn = featureOn;
    }

    TextColor color(Season s) {
        return TextColor.fromHexString(settings.seasonColors()[s.id()]);
    }

    /** "❄ Winter" — the season's display name in this language. */
    String name(String lang, Season s) {
        return messages.raw(lang, "seasons." + s.key + ".name");
    }

    /** "until November 30" or "in-game days left: 3". */
    String until(String lang, SeasonCalendar.Position p) {
        if (p.lastDay() != null) {
            return Messages.fill(messages.raw(lang, "until.date"), Map.of("date", messages.date(lang, p.lastDay())));
        }
        return Messages.fill(messages.raw(lang, "until.days"), Map.of("days", String.valueOf(p.daysLeft())));
    }

    List<String> effects(String lang, Season s, SeasonCalendar.Position p) {
        List<String> out = new ArrayList<>();
        for (Effect e : EFFECTS.get(s)) {
            boolean on = false;
            for (String need : e.needs()) {
                on |= featureOn.test(need);
            }
            if (!on) {
                continue;
            }
            String key = "effects." + s.key + "." + e.key();
            if (s == Season.WINTER && e.key().equals("melt") && p.lastDay() != null && p.season() == Season.WINTER) {
                out.add(Messages.fill(messages.raw(lang, key + "-date"),
                        Map.of("date", messages.date(lang, p.lastDay().plusDays(1)))));
            } else {
                out.add(messages.raw(lang, key));
            }
        }
        return out;
    }

    /** The /season reply. */
    void sendSeason(CommandSender to, Season s, SeasonCalendar.Position p, boolean preview) {
        String lang = messages.lang(to);
        Component header = Component.empty().color(color(s))
                .append(messages.get(lang, "season.header", Map.of("season", name(lang, s))));
        String tail = preview ? messages.raw(lang, "season.preview")
                : Messages.fill(messages.raw(lang, "season.until"), Map.of("until", until(lang, p)));
        to.sendMessage(header.append(Component.empty().color(NamedTextColor.GRAY).append(Messages.parse(tail))));
        for (String effect : effects(lang, s, p)) {
            to.sendMessage(Component.empty().color(NamedTextColor.GRAY)
                    .append(messages.get(lang, "season.bullet", Map.of("effect", effect))));
        }
    }
}
