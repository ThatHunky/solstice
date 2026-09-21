package dev.thathunky.solstice.stats;

import org.bstats.bukkit.Metrics;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Starts standard bStats metrics (no custom charts). Lives outside {@code src/main/java} on purpose:
 * only the Gradle build compiles and shades it, so the local {@code build.sh} jar never needs
 * {@code org.bstats} and never bundles a metrics reporter. {@link dev.thathunky.solstice.Solstice}
 * loads this class by reflection and does nothing if it is absent.
 *
 * <p>The plugin id is a placeholder. Register the plugin at
 * <a href="https://bstats.org/what-is-my-plugin-id">bstats.org</a> and put the id in {@link #PLUGIN_ID}.
 * Server owners can turn reporting off globally in {@code plugins/bStats/config.yml}.
 */
public final class PluginMetrics {

    private static final int PLUGIN_ID = 0; // TODO: replace with the id from bstats.org before release

    @SuppressWarnings("unused") // constructed reflectively by Solstice
    public PluginMetrics(JavaPlugin plugin) {
        if (PLUGIN_ID <= 0) {
            plugin.getLogger().info("bStats plugin id is still the placeholder (0) — metrics not started.");
            return;
        }
        new Metrics(plugin, PLUGIN_ID);
    }
}
