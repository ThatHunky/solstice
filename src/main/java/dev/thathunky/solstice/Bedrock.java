package dev.thathunky.solstice;

import java.lang.reflect.Method;
import java.util.UUID;
import org.bukkit.Bukkit;

/**
 * Tells Bedrock players (joined through Geyser) apart, for {@code particles.bedrock: false}. Asks the
 * Floodgate API, then the Geyser API, by reflection so neither is needed to build or run; with
 * neither installed, falls back to Floodgate's UUID shape (the upper half is zero).
 */
final class Bedrock {

    private final Object api;
    private final Method check;

    Bedrock() {
        Object foundApi = null;
        Method foundCheck = null;
        try {
            if (Bukkit.getPluginManager().getPlugin("floodgate") != null) {
                Class<?> c = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                foundApi = c.getMethod("getInstance").invoke(null);
                foundCheck = c.getMethod("isFloodgatePlayer", UUID.class);
            } else if (Bukkit.getPluginManager().getPlugin("Geyser-Spigot") != null) {
                Class<?> c = Class.forName("org.geysermc.geyser.api.GeyserApi");
                foundApi = c.getMethod("api").invoke(null);
                foundCheck = c.getMethod("isBedrockPlayer", UUID.class);
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            foundApi = null;
            foundCheck = null;
        }
        api = foundApi;
        check = foundCheck;
    }

    boolean is(UUID id) {
        if (check != null) {
            try {
                return (Boolean) check.invoke(api, id);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // fall through to the UUID shape
            }
        }
        return id.getMostSignificantBits() == 0;
    }
}
