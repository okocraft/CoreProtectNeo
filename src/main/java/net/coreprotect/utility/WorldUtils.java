package net.coreprotect.utility;

import net.coreprotect.config.ConfigHandler;
import net.coreprotect.consumer.Queue;
import net.minecraft.server.level.ServerLevel;

/**
 * World (dimension) helpers of the NeoForge port.
 *
 * <p>Bukkit identified worlds by name; on NeoForge the dimension id ("minecraft:overworld") is used
 * instead. The ids stored in the {@code world} table keep the same meaning.
 */
public final class WorldUtils {

    private WorldUtils() {
        throw new IllegalStateException("Utility class");
    }

    /** Dimension id of a level. */
    public static String getWorldName(ServerLevel level) {
        return level == null ? null : level.dimension().identifier().toString();
    }

    /** World name stored in the {@code world} table. */
    public static String getWorldName(int id) {
        String name = ConfigHandler.worldsReversed.get(id);
        if (name != null) {
            return name;
        }
        if (id == 0) {
            return null;
        }
        return ConfigHandler.reloadAndGetId(ConfigHandler.CacheType.WORLDS, String.valueOf(id)) > -1
                ? ConfigHandler.worldsReversed.get(id)
                : null;
    }

    /** World id of a dimension id, registering it when missing. */
    public static int getWorldId(String worldName) {
        if (worldName == null) {
            return 0;
        }
        return Queue.getWorldId(worldName);
    }
}
