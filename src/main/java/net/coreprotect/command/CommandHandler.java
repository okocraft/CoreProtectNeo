package net.coreprotect.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.mojang.brigadier.arguments.StringArgumentType;

import net.coreprotect.config.Config;
import net.coreprotect.config.ConfigHandler;
import net.coreprotect.consumer.Consumer;
import net.coreprotect.database.Lookup;
import net.coreprotect.database.Rollback;
import net.coreprotect.language.Language;
import net.coreprotect.language.Phrase;
import net.coreprotect.model.action.LookupActions;
import net.coreprotect.utility.Chat;
import net.coreprotect.utility.Color;
import net.coreprotect.utility.VersionUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /co} command of the NeoForge port.
 *
 * <p>The command names, parameters ({@code u:}, {@code t:}, {@code r:}, {@code a:}) and messages
 * follow the original plugin; the time and action parameters are parsed by the ported
 * {@link net.coreprotect.command.parser.TimeParser} and
 * {@link net.coreprotect.command.parser.ActionParser}.
 */
public final class CommandHandler {

    /** Permission required for administrative commands (the previous level 2). */
    private static final net.minecraft.server.permissions.Permission ADMIN_PERMISSION = Permissions.COMMANDS_GAMEMASTER;

    private CommandHandler() {
        throw new IllegalStateException("Utility class");
    }

    public static void register(RegisterCommandsEvent event) {
        for (String name : new String[] { "co", "core", "coreprotect" }) {
            event.getDispatcher().register(Commands.literal(name)
                    .executes(context -> help(context.getSource()))
                    .then(Commands.literal("help")
                            .executes(context -> help(context.getSource())))
                    .then(Commands.literal("version")
                            .executes(context -> version(context.getSource())))
                    .then(Commands.literal("status")
                            .requires(source -> source.permissions().hasPermission(ADMIN_PERMISSION))
                            .executes(context -> status(context.getSource())))
                    .then(Commands.literal("reload")
                            .requires(source -> source.permissions().hasPermission(ADMIN_PERMISSION))
                            .executes(context -> reload(context.getSource())))
                    .then(Commands.literal("lookup")
                            .requires(source -> source.permissions().hasPermission(ADMIN_PERMISSION))
                            .then(Commands.argument("params", StringArgumentType.greedyString())
                                    .executes(context -> lookup(context.getSource(),
                                            StringArgumentType.getString(context, "params")))))
                    .then(Commands.literal("l")
                            .requires(source -> source.permissions().hasPermission(ADMIN_PERMISSION))
                            .then(Commands.argument("params", StringArgumentType.greedyString())
                                    .executes(context -> lookup(context.getSource(),
                                            StringArgumentType.getString(context, "params")))))
                    .then(Commands.literal("rollback")
                            .requires(source -> source.permissions().hasPermission(ADMIN_PERMISSION))
                            .then(Commands.argument("params", StringArgumentType.greedyString())
                                    .executes(context -> applyRollback(context.getSource(),
                                            StringArgumentType.getString(context, "params"), false))))
                    .then(Commands.literal("rb")
                            .requires(source -> source.permissions().hasPermission(ADMIN_PERMISSION))
                            .then(Commands.argument("params", StringArgumentType.greedyString())
                                    .executes(context -> applyRollback(context.getSource(),
                                            StringArgumentType.getString(context, "params"), false))))
                    .then(Commands.literal("restore")
                            .requires(source -> source.permissions().hasPermission(ADMIN_PERMISSION))
                            .then(Commands.argument("params", StringArgumentType.greedyString())
                                    .executes(context -> applyRollback(context.getSource(),
                                            StringArgumentType.getString(context, "params"), true))))
                    .then(Commands.literal("rs")
                            .requires(source -> source.permissions().hasPermission(ADMIN_PERMISSION))
                            .then(Commands.argument("params", StringArgumentType.greedyString())
                                    .executes(context -> applyRollback(context.getSource(),
                                            StringArgumentType.getString(context, "params"), true))))
                    .then(Commands.literal("inspect")
                            .requires(source -> source.permissions().hasPermission(ADMIN_PERMISSION))
                            .executes(context -> toggleInspect(context.getSource()))
                            .then(Commands.literal("on")
                                    .executes(context -> setInspect(context.getSource(), true)))
                            .then(Commands.literal("off")
                                    .executes(context -> setInspect(context.getSource(), false))))
                    .then(Commands.literal("i")
                            .requires(source -> source.permissions().hasPermission(ADMIN_PERMISSION))
                            .executes(context -> toggleInspect(context.getSource())))
                    .then(Commands.literal("purge")
                            .requires(source -> source.permissions().hasPermission(ADMIN_PERMISSION))
                            .executes(context -> purge(context.getSource(), ""))
                            .then(Commands.argument("params", StringArgumentType.greedyString())
                                    .executes(context -> purge(context.getSource(),
                                            StringArgumentType.getString(context, "params")))))
                    .then(Commands.literal("tp")
                            .requires(source -> source.permissions().hasPermission(ADMIN_PERMISSION))
                            .then(Commands.argument("params", StringArgumentType.greedyString())
                                    .executes(context -> teleport(context.getSource(),
                                            StringArgumentType.getString(context, "params")))))
                    .then(Commands.literal("teleport")
                            .requires(source -> source.permissions().hasPermission(ADMIN_PERMISSION))
                            .then(Commands.argument("params", StringArgumentType.greedyString())
                                    .executes(context -> teleport(context.getSource(),
                                            StringArgumentType.getString(context, "params"))))));
        }
    }

    /** {@code /co tp <world> <x> <y> <z>} — teleports the sender to a lookup location. */
    private static int teleport(CommandSourceStack source, String params) {
        final ServerPlayer player = source.getPlayer();
        if (player == null) {
            Chat.sendMessage(source, Color.RED + Phrase.build(Phrase.COMMAND_CONSOLE));
            return 0;
        }
        final String[] parts = params.trim().split("\\s+");
        if (parts.length < 4) {
            Chat.sendMessage(source, Color.WHITE + Phrase.build(Phrase.HELP_TELEPORT));
            return 0;
        }

        final String world = parts[0];
        try {
            final double x = Double.parseDouble(parts[1]);
            final double y = Double.parseDouble(parts[2]);
            final double z = Double.parseDouble(parts[3]);

            final var key = net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION,
                    net.minecraft.resources.Identifier.parse(world));
            final var level = source.getServer().getLevel(key);
            if (level == null) {
                Chat.sendMessage(source, Color.RED + Phrase.build(Phrase.WORLD_NOT_FOUND));
                return 0;
            }

            player.teleportTo(level, x, y, z, java.util.Set.of(), player.getYRot(), player.getXRot(), false);
            Chat.sendMessage(source, Color.DARK_AQUA + "CoreProtect " + Color.WHITE + "- Teleported to "
                    + x + ", " + y + ", " + z + " in " + world + ".");
            return 1;
        }
        catch (NumberFormatException e) {
            Chat.sendMessage(source, Color.WHITE + Phrase.build(Phrase.HELP_TELEPORT));
            return 0;
        }
    }

    private static int toggleInspect(CommandSourceStack source) {
        final ServerPlayer player = source.getPlayer();
        if (player == null) {
            Chat.sendMessage(source, Color.RED + Phrase.build(Phrase.COMMAND_CONSOLE));
            return 0;
        }
        final String key = player.getGameProfile().name().toLowerCase(Locale.ROOT);
        return setInspect(source, !Boolean.TRUE.equals(ConfigHandler.inspecting.get(key)));
    }

    private static int setInspect(CommandSourceStack source, boolean enabled) {
        final ServerPlayer player = source.getPlayer();
        if (player == null) {
            Chat.sendMessage(source, Color.RED + Phrase.build(Phrase.COMMAND_CONSOLE));
            return 0;
        }
        ConfigHandler.inspecting.put(player.getGameProfile().name().toLowerCase(Locale.ROOT), enabled);
        Chat.sendMessage(source, Color.DARK_AQUA + "CoreProtect " + Color.WHITE + "- "
                + (enabled ? "Inspector enabled. Right-click a block to view its history."
                           : "Inspector disabled."));
        return 1;
    }

    /** {@code /co purge t:<time> [r:<world>]} */
    private static int purge(CommandSourceStack source, String params) {
        final String[] args = params == null || params.isBlank() ? new String[0] : params.trim().split("\\s+");
        final long[] time = net.coreprotect.command.parser.TimeParser.parseTime(args);
        if (time[0] <= 0 && time[1] <= 0) {
            Chat.sendMessage(source, Color.WHITE + Phrase.build(Phrase.PURGE_MINIMUM_TIME));
            return 0;
        }

        final long now = System.currentTimeMillis() / 1000L;
        final long cutoff = now - (time[0] > 0 ? time[0] : time[1]);
        final String world = readParameter(args, "r:");

        Chat.sendMessage(source, Color.WHITE + Phrase.build(Phrase.PURGE_STARTED) + Color.GREY + " ("
                + net.coreprotect.command.parser.TimeParser.parseTimeString(args) + ")");

        final long removed = net.coreprotect.database.Purge.purge(cutoff, world);
        Chat.sendMessage(source, Color.WHITE + Phrase.build(Phrase.PURGE_ROWS, Long.toString(removed)));
        Chat.sendMessage(source, Color.WHITE + Phrase.build(Phrase.PURGE_SUCCESS));
        return (int) Math.min(removed, Integer.MAX_VALUE);
    }

    private static int help(CommandSourceStack source) {
        Chat.sendMessage(source, Color.WHITE + "----- " + Color.DARK_AQUA
                + Phrase.build(Phrase.HELP_HEADER, "CoreProtect") + Color.WHITE + " -----");
        Chat.sendMessage(source, Color.DARK_AQUA + "/co inspect " + Color.WHITE + "- "
                + Phrase.build(Phrase.HELP_INSPECT_1));
        Chat.sendMessage(source, Color.DARK_AQUA + "/co lookup " + Color.GREY + "<params> " + Color.WHITE + "- "
                + Phrase.build(Phrase.HELP_LOOKUP_1));
        Chat.sendMessage(source, Color.DARK_AQUA + "/co rollback " + Color.GREY + "<params> " + Color.WHITE + "- "
                + Phrase.build(Phrase.HELP_PARAMS_1, net.coreprotect.language.Selector.SECOND));
        Chat.sendMessage(source, Color.DARK_AQUA + "/co restore " + Color.GREY + "<params> " + Color.WHITE + "- "
                + Phrase.build(Phrase.HELP_PARAMS_1, net.coreprotect.language.Selector.THIRD));
        Chat.sendMessage(source, Color.DARK_AQUA + "| " + Color.GREY + "u:<users> " + Color.WHITE + "- "
                + Phrase.build(Phrase.HELP_PARAMS_2, net.coreprotect.language.Selector.FIRST));
        Chat.sendMessage(source, Color.DARK_AQUA + "| " + Color.GREY + "t:<time> " + Color.WHITE + "- "
                + Phrase.build(Phrase.HELP_PARAMS_3, net.coreprotect.language.Selector.FIRST));
        Chat.sendMessage(source, Color.DARK_AQUA + "| " + Color.GREY + "r:<radius> " + Color.WHITE + "- "
                + Phrase.build(Phrase.HELP_PARAMS_4, net.coreprotect.language.Selector.FIRST));
        Chat.sendMessage(source, Color.DARK_AQUA + "| " + Color.GREY + "a:<action> " + Color.WHITE + "- "
                + Phrase.build(Phrase.HELP_PARAMS_5, net.coreprotect.language.Selector.FIRST));
        Chat.sendMessage(source, Color.DARK_AQUA + "/co status " + Color.WHITE + "- "
                + Phrase.build(Phrase.HELP_STATUS));
        Chat.sendMessage(source, Color.DARK_AQUA + "/co reload " + Color.WHITE + "- "
                + Phrase.build(Phrase.HELP_RELOAD_COMMAND));
        return 1;
    }

    private static int version(CommandSourceStack source) {
        Chat.sendMessage(source, Color.DARK_AQUA + "CoreProtect " + Color.WHITE
                + VersionUtils.getPluginVersion() + Color.GREY + " (NeoForge 1.21.1, "
                + ConfigHandler.EDITION_NAME + ")");
        return 1;
    }

    private static int status(CommandSourceStack source) {
        Chat.sendMessage(source, Color.WHITE + "----- " + Color.DARK_AQUA + "CoreProtect Status"
                + Color.WHITE + " -----");
        Chat.sendMessage(source, Color.DARK_AQUA + "Version: " + Color.WHITE + VersionUtils.getPluginVersion()
                + Color.GREY + " (" + ConfigHandler.EDITION_NAME + ")");
        Chat.sendMessage(source, Color.DARK_AQUA + "Database: " + Color.WHITE
                + (Config.getGlobal().MYSQL ? "MySQL" : "SQLite"));
        Chat.sendMessage(source, Color.DARK_AQUA + "Queue: " + Color.WHITE + Consumer.queueSize() + " entries");
        Chat.sendMessage(source, Color.DARK_AQUA + "Players logged: " + Color.WHITE
                + ConfigHandler.playerIdCache.size());
        Chat.sendMessage(source, Color.DARK_AQUA + "Worlds tracked: " + Color.WHITE + ConfigHandler.worlds.size());
        return 1;
    }

    private static int reload(CommandSourceStack source) {
        try {
            Consumer.flush();
            Language.loadPhrases();
            Config.init();
            Chat.sendMessage(source, Color.DARK_AQUA + "CoreProtect " + Color.WHITE
                    + "configuration reloaded.");
        }
        catch (Exception e) {
            Chat.sendMessage(source, Color.RED + "Reload failed: " + e.getMessage());
        }
        return 1;
    }

    private static int lookup(CommandSourceStack source, String params) {
        final String[] args = params.trim().split("\\s+");
        final List<Integer> actions = net.coreprotect.command.parser.ActionParser.parseAction(args);
        final long[] time = net.coreprotect.command.parser.TimeParser.parseTime(args);
        final int now = (int) (System.currentTimeMillis() / 1000L);
        final long timeStart = time[0] > 0 ? now - time[0] : 0;
        final long timeEnd = time[1] > 0 ? now - time[1] : 0;

        final String userFilter = readParameter(args, "u:");
        final int radius = readRadius(args);
        final String world = source.getLevel() != null ? source.getLevel().dimension().identifier().toString() : null;
        final int userId = userFilter == null ? 0
                : ConfigHandler.playerIdCache.getOrDefault(userFilter.toLowerCase(Locale.ROOT), 0);
        if (userFilter != null && userId == 0) {
            Chat.sendMessage(source, Color.WHITE + Phrase.build(Phrase.NO_RESULTS));
            return 0;
        }
        final int originX = source.getPlayer() != null ? source.getPlayer().getBlockX() : 0;
        final int originZ = source.getPlayer() != null ? source.getPlayer().getBlockZ() : 0;
        final int page = readPage(args);
        final int limit = 100;
        final int offset = (page - 1) * limit;

        Chat.sendMessage(source, Color.WHITE + Phrase.build(Phrase.LOOKUP_SEARCHING)
                + Color.GREY + " (" + net.coreprotect.command.parser.TimeParser.parseTimeString(args) + ")");

        final List<Lookup.BlockRow> rows = Lookup.performBlockLookup(userId, world, timeStart, timeEnd,
                actions, radius, originX, originZ, limit, offset);

        if (rows.isEmpty()) {
            Chat.sendMessage(source, Color.WHITE + (page > 1
                    ? Phrase.build(Phrase.NO_RESULTS_PAGE)
                    : Phrase.build(Phrase.NO_RESULTS)));
            return 0;
        }

        Chat.sendMessage(source, Color.DARK_AQUA + "----- " + Color.WHITE
                + Phrase.build(Phrase.LOOKUP_ROWS_FOUND, Integer.toString(rows.size())) + Color.DARK_AQUA
                + " -----");
        for (Lookup.BlockRow row : rows) {
            final String actionName = LookupActions.getActionString(row.action());
            Chat.sendMessage(source, Color.WHITE + formatTime(row.time()) + Color.GREY + " | "
                    + Color.WHITE + row.user() + Color.GREY + " | " + Color.DARK_AQUA + actionName + " "
                    + Color.WHITE + row.blockName() + Color.GREY + " (" + row.x() + ", " + row.y() + ", "
                    + row.z() + ") " + row.world());
        }
        Chat.sendMessage(source, Color.GREY + Phrase.build(Phrase.LOOKUP_PAGE, Integer.toString(page)));
        return rows.size();
    }

    private static int applyRollback(CommandSourceStack source, String params, boolean restore) {
        final ServerPlayer player = source.getPlayer();
        if (player == null) {
            Chat.sendMessage(source, Color.RED + Phrase.build(Phrase.COMMAND_CONSOLE));
            return 0;
        }

        final String[] args = params.trim().split("\\s+");
        final List<Integer> actions = net.coreprotect.command.parser.ActionParser.parseAction(args);
        final long[] time = net.coreprotect.command.parser.TimeParser.parseTime(args);
        final int now = (int) (System.currentTimeMillis() / 1000L);
        final long timeStart = time[0] > 0 ? now - time[0] : 0;
        final long timeEnd = time[1] > 0 ? now - time[1] : 0;

        final String userFilter = readParameter(args, "u:");
        final int radius = readRadius(args);
        final String world = player.level().dimension().identifier().toString();
        final int userId = userFilter == null ? 0
                : ConfigHandler.playerIdCache.getOrDefault(userFilter.toLowerCase(Locale.ROOT), 0);
        if ((userFilter != null && userId == 0) || (userFilter == null && timeStart == 0 && radius == 0)) {
            Chat.sendMessage(source, Color.WHITE + Phrase.build(Phrase.NO_RESULTS));
            return 0;
        }

        final List<Lookup.BlockRow> rows = Lookup.performRollbackLookup(userId, world, timeStart, timeEnd,
                actions, radius, player.getBlockX(), player.getBlockZ(), 5000, restore);

        if (rows.isEmpty()) {
            Chat.sendMessage(source, Color.WHITE + Phrase.build(Phrase.NO_RESULTS));
            return 0;
        }

        final Rollback.Result result = Rollback.performBlockRollback(player.level(), rows, restore);
        // The original uses one phrase with selectors for "rolled back" / "restored".
        final String selector = restore
                ? net.coreprotect.language.Selector.SECOND
                : net.coreprotect.language.Selector.FIRST;
        Chat.sendMessage(source, Color.WHITE
                + Phrase.build(Phrase.ROLLBACK_COMPLETED, selector, Integer.toString(result.blocksModified())));
        Chat.sendMessage(source, Color.GREY
                + Phrase.build(Phrase.ROLLBACK_LENGTH, selector, Integer.toString(result.rowsMarked())));
        return result.blocksModified();
    }

    private static String readParameter(String[] args, String prefix) {
        for (String argument : args) {
            final String lower = argument.toLowerCase(Locale.ROOT);
            if (lower.startsWith(prefix) && argument.length() > prefix.length()) {
                return argument.substring(prefix.length());
            }
            if (prefix.equals("u:") && lower.startsWith("user:") && argument.length() > 5) {
                return argument.substring(5);
            }
        }
        return null;
    }

    private static int readRadius(String[] args) {
        final String value = readParameter(args, "r:");
        if (value == null) {
            return 0;
        }
        try {
            return Math.min(Integer.parseInt(value.replaceAll("[^0-9]", "")), Config.getGlobal().MAX_RADIUS);
        }
        catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Reads {@code page:N} / {@code p:N}; defaults to page 1. */
    private static int readPage(String[] args) {
        String value = readParameter(args, "page:");
        if (value == null) {
            value = readParameter(args, "p:");
        }
        if (value == null) {
            return 1;
        }
        try {
            return Math.max(Integer.parseInt(value.replaceAll("[^0-9]", "")), 1);
        }
        catch (NumberFormatException e) {
            return 1;
        }
    }

    private static String formatTime(int unixTime) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(unixTime * 1000L));
    }

    /** Actions supported by the lookup of this stage. */
    public static List<Integer> defaultActions() {
        final List<Integer> actions = new ArrayList<>();
        actions.add(LookupActions.BLOCK_BREAK);
        actions.add(LookupActions.BLOCK_PLACE);
        return actions;
    }
}
