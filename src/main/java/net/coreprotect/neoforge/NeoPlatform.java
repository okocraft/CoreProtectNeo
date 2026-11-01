package net.coreprotect.neoforge;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Small platform facade replacing the Bukkit server references of the original plugin.
 */
public final class NeoPlatform {

    public static final String MOD_ID = "coreprotect";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static volatile MinecraftServer server;

    private NeoPlatform() {
        throw new IllegalStateException("Utility class");
    }

    public static void setServer(MinecraftServer minecraftServer) {
        server = minecraftServer;
    }

    public static MinecraftServer server() {
        return server;
    }

    public static boolean isServerRunning() {
        return server != null;
    }

    /** Data directory of the mod (equivalent of the plugin's data folder). */
    public static Path dataPath() {
        return FMLPaths.GAMEDIR.get().resolve("coreprotect");
    }

    public static List<ServerPlayer> onlinePlayers() {
        MinecraftServer current = server;
        if (current == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(current.getPlayerList().getPlayers());
    }

    /** Dimension ids of every loaded level, in the same order the server reports them. */
    public static List<String> worldNames() {
        MinecraftServer current = server;
        if (current == null) {
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<>();
        for (ServerLevel level : current.getAllLevels()) {
            names.add(level.dimension().identifier().toString());
        }
        return names;
    }

    public static boolean isServerThread() {
        MinecraftServer current = server;
        return current == null || current.isSameThread();
    }

    public static String playerName(ServerPlayer player) {
        return player.getGameProfile().name();
    }

    public static String playerUuid(ServerPlayer player) {
        return player.getUUID().toString();
    }

    /** Executes a task on the server thread. */
    public static void runSync(Runnable task) {
        MinecraftServer current = server;
        if (current == null) {
            task.run();
            return;
        }
        current.execute(task);
    }

    /** Executes a task on a background thread (database work must never block the server thread). */
    public static void runAsync(Runnable task) {
        Thread thread = new Thread(task, "CoreProtect-Neo Task");
        thread.setDaemon(true);
        thread.start();
    }

    public static void console(Component component) {
        MinecraftServer current = server;
        if (current != null) {
            current.sendSystemMessage(component);
        }
        LOGGER.info(component.getString());
    }

}
