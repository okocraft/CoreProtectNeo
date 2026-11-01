package net.coreprotect;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import net.coreprotect.config.Config;
import net.coreprotect.config.ConfigHandler;
import net.coreprotect.consumer.Consumer;
import net.coreprotect.language.Language;
import net.coreprotect.language.Phrase;
import net.coreprotect.listener.ListenerHandler;
import net.coreprotect.neoforge.NeoPlatform;
import net.coreprotect.utility.Chat;
import net.coreprotect.utility.ErrorReporter;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * CoreProtect for NeoForge 26.2.
 *
 * <p>The lifecycle mirrors the original plugin: language phrases are loaded, the listener handler
 * registers the event handlers, the configuration and database are initialised and the consumer
 * queue is started. {@link #getInstance()} and {@link #getDataFolder()} keep the same meaning as
 * they had in the Bukkit plugin so the ported classes stay unchanged.
 */
@Mod(CoreProtect.MOD_ID)
public final class CoreProtect {

    public static final String MOD_ID = "coreprotect";

    private static CoreProtect instance;

    public CoreProtect(IEventBus modEventBus, ModContainer modContainer) {
        instance = this;

        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(net.coreprotect.command.CommandHandler::register);
    }

    /** The mod instance (equivalent of the plugin instance of the original). */
    public static CoreProtect getInstance() {
        return instance;
    }

    /** Data directory of the mod. */
    public File getDataFolder() {
        final Path path = NeoPlatform.dataPath();
        try {
            Files.createDirectories(path);
        }
        catch (Exception e) {
            ErrorReporter.report(e);
        }
        return path.toFile();
    }

    private void onServerStarting(ServerStartingEvent event) {
        NeoPlatform.setServer(event.getServer());
        ConfigHandler.path = getDataFolder().getPath() + File.separator;

        // Load the language phrases (identical strings to the original plugin).
        Language.loadPhrases();

        try {
            Consumer.initialize();
            new ListenerHandler(this);
            boolean started = ConfigHandler.performInitialization(true);
            if (started) {
                displayStartupMessages();
                Consumer.startConsumer();
            }
            else {
                Chat.console(Phrase.build(Phrase.ENABLE_FAILED, ConfigHandler.EDITION_NAME));
            }
        }
        catch (Exception e) {
            ErrorReporter.report(e);
            Chat.console(Phrase.build(Phrase.ENABLE_FAILED, ConfigHandler.EDITION_NAME));
        }
    }

    private void onServerStopping(ServerStoppingEvent event) {
        try {
            Consumer.stopConsumer();
            ConfigHandler.performDisable();
        }
        catch (Exception e) {
            ErrorReporter.report(e);
        }
        NeoPlatform.setServer(null);
    }

    private void displayStartupMessages() {
        Chat.console(Phrase.build(Phrase.ENABLE_SUCCESS, ConfigHandler.EDITION_NAME));
        if (Config.getGlobal().MYSQL) {
            Chat.console(Phrase.build(Phrase.USING_MYSQL));
        }
        else {
            Chat.console(Phrase.build(Phrase.USING_SQLITE));
        }
        Chat.console("--------------------");
        Chat.console(Phrase.build(Phrase.ENJOY_COREPROTECT, "CoreProtect"));
        Chat.console(Phrase.build(Phrase.LINK_DISCORD, "www.coreprotect.net/discord/"));
        Chat.console("--------------------");
    }
}
