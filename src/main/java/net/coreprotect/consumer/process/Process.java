package net.coreprotect.consumer.process;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

import net.coreprotect.config.ConfigHandler;
import net.coreprotect.consumer.ConsumerEntry;
import net.coreprotect.consumer.ProcessType;
import net.coreprotect.consumer.Queue;
import net.coreprotect.database.statement.BlockStatement;
import net.coreprotect.database.statement.ChatStatement;
import net.coreprotect.database.statement.CommandStatement;
import net.coreprotect.database.statement.ContainerStatement;
import net.coreprotect.database.statement.EntityStatement;
import net.coreprotect.database.statement.ItemStatement;
import net.coreprotect.database.statement.SessionStatement;
import net.coreprotect.database.statement.UserStatement;
import net.coreprotect.neoforge.NeoPlatform;
import net.coreprotect.utility.ErrorReporter;

/**
 * Writes queued actions into the database.
 *
 * <p>Each process type maps to one table of the CoreProtect schema, exactly like the original
 * plugin. The SQL statements are identical to the ones the plugin used, so existing databases stay
 * readable.
 */
public class Process {

    public static long lastLockUpdate = 0;

    private Process() {
        throw new IllegalStateException("Process class");
    }

    public static void process(Connection connection, List<ConsumerEntry> batch, int batchSize) throws Exception {
        final List<ConsumerEntry> worldInserts = new ArrayList<>();
        final List<ConsumerEntry> materialInserts = new ArrayList<>();
        final List<ConsumerEntry> blockDataInserts = new ArrayList<>();
        final List<ConsumerEntry> entityInserts = new ArrayList<>();
        final List<ConsumerEntry> blocks = new ArrayList<>();
        final List<ConsumerEntry> sessions = new ArrayList<>();
        final List<ConsumerEntry> chats = new ArrayList<>();
        final List<ConsumerEntry> commands = new ArrayList<>();
        final List<ConsumerEntry> containers = new ArrayList<>();
        final List<ConsumerEntry> items = new ArrayList<>();
        final List<ConsumerEntry> entityKills = new ArrayList<>();

        for (ConsumerEntry entry : batch) {
            switch (entry.type()) {
                case WORLD_INSERT -> worldInserts.add(entry);
                case MATERIAL_INSERT -> materialInserts.add(entry);
                case BLOCKDATA_INSERT -> blockDataInserts.add(entry);
                case ENTITY_INSERT -> entityInserts.add(entry);
                case BLOCK_BREAK, BLOCK_PLACE -> blocks.add(entry);
                case SESSION -> sessions.add(entry);
                case CHAT -> chats.add(entry);
                case COMMAND -> commands.add(entry);
                case CONTAINER -> containers.add(entry);
                case ITEM -> items.add(entry);
                case ENTITY_KILL -> entityKills.add(entry);
            }
        }

        insertMapEntries(connection, worldInserts, "world", "world");
        insertMapEntries(connection, materialInserts, "material_map", "material");
        insertMapEntries(connection, blockDataInserts, "blockdata_map", "data");
        insertMapEntries(connection, entityInserts, "entity_map", "entity");
        insertBlocks(connection, blocks, batchSize);
        insertSessions(connection, sessions, batchSize);
        insertMessages(connection, chats, "chat", batchSize);
        insertMessages(connection, commands, "command", batchSize);
        insertContainers(connection, containers, batchSize);
        insertItems(connection, items, batchSize);
        insertEntityKills(connection, entityKills, batchSize);
    }

    private static void insertMapEntries(Connection connection, List<ConsumerEntry> entries, String table, String column) {
        if (entries.isEmpty()) {
            return;
        }
        final String sql = "INSERT INTO " + ConfigHandler.prefix + table + " (id, " + column + ") VALUES (?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (ConsumerEntry entry : entries) {
                statement.setInt(1, entry.materialId());
                statement.setString(2, entry.world());
                statement.addBatch();
            }
            statement.executeBatch();
        }
        catch (Exception e) {
            ErrorReporter.report(e);
            throw new IllegalStateException("Database write failed", e);
        }
    }

    private static void insertBlocks(Connection connection, List<ConsumerEntry> entries, int batchSize) {
        if (entries.isEmpty()) {
            return;
        }
        final String sql = "INSERT INTO " + ConfigHandler.prefix
                + "block (time,user,wid,x,y,z,type,data,meta,blockdata,action,rolled_back) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int count = 0;
            for (ConsumerEntry entry : entries) {
                final int userId = resolveUser(connection, entry);
                final int worldId = Queue.getWorldId(entry.world());
                BlockStatement.insert(statement, count++, entry.time(), userId, worldId,
                        entry.x(), entry.y(), entry.z(), entry.materialId(), 0, entry.blob(),
                        entry.blockData(), entry.action(), 0);
            }
            statement.executeBatch();
        }
        catch (Exception e) {
            ErrorReporter.report(e);
            throw new IllegalStateException("Database write failed", e);
        }
    }

    private static void insertSessions(Connection connection, List<ConsumerEntry> entries, int batchSize) {
        if (entries.isEmpty()) {
            return;
        }
        final String sql = "INSERT INTO " + ConfigHandler.prefix
                + "session (time,user,wid,x,y,z,action) VALUES (?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int count = 0;
            for (ConsumerEntry entry : entries) {
                final int userId = resolveUser(connection, entry);
                final int worldId = Queue.getWorldId(entry.world());
                SessionStatement.insert(statement, count++, entry.time(), userId, worldId,
                        entry.x(), entry.y(), entry.z(), entry.action());
            }
            statement.executeBatch();
        }
        catch (Exception e) {
            ErrorReporter.report(e);
            throw new IllegalStateException("Database write failed", e);
        }
    }

    private static void insertMessages(Connection connection, List<ConsumerEntry> entries, String table, int batchSize) {
        if (entries.isEmpty()) {
            return;
        }
        final String sql = "INSERT INTO " + ConfigHandler.prefix + table
                + " (time,user,wid,x,y,z,message) VALUES (?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int count = 0;
            for (ConsumerEntry entry : entries) {
                final int userId = resolveUser(connection, entry);
                final int worldId = Queue.getWorldId(entry.world());
                if ("chat".equals(table)) {
                    ChatStatement.insert(statement, count++, entry.time(), userId, worldId,
                            entry.x(), entry.y(), entry.z(), entry.message());
                }
                else {
                    CommandStatement.insert(statement, count++, entry.time(), userId, worldId,
                            entry.x(), entry.y(), entry.z(), entry.message());
                }
            }
            statement.executeBatch();
        }
        catch (Exception e) {
            ErrorReporter.report(e);
            throw new IllegalStateException("Database write failed", e);
        }
    }

    /** Writes container transactions (one row per changed item stack). */
    private static void insertContainers(Connection connection, List<ConsumerEntry> entries, int batchSize) {
        if (entries.isEmpty()) {
            return;
        }
        final String sql = "INSERT INTO " + ConfigHandler.prefix
                + "container (time,user,wid,x,y,z,type,data,amount,metadata,action,rolled_back) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int count = 0;
            for (ConsumerEntry entry : entries) {
                final int userId = resolveUser(connection, entry);
                final int worldId = Queue.getWorldId(entry.world());
                ContainerStatement.insert(statement, count++, entry.time(), userId, worldId,
                        entry.x(), entry.y(), entry.z(), entry.materialId(), entry.dataId(),
                        entry.amount(), entry.blob(), entry.action(), 0);
            }
            statement.executeBatch();
        }
        catch (Exception e) {
            ErrorReporter.report(e);
            throw new IllegalStateException("Database write failed", e);
        }
    }

    /** Writes item transactions (drops and pickups). */
    private static void insertItems(Connection connection, List<ConsumerEntry> entries, int batchSize) {
        if (entries.isEmpty()) {
            return;
        }
        final String sql = "INSERT INTO " + ConfigHandler.prefix
                + "item (time,user,wid,x,y,z,type,data,amount,action,rolled_back) VALUES (?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int count = 0;
            for (ConsumerEntry entry : entries) {
                final int userId = resolveUser(connection, entry);
                final int worldId = Queue.getWorldId(entry.world());
                ItemStatement.insert(statement, count++, entry.time(), userId, worldId,
                        entry.x(), entry.y(), entry.z(), entry.materialId(), entry.blob(),
                        entry.amount(), entry.action());
            }
            statement.executeBatch();
        }
        catch (Exception e) {
            ErrorReporter.report(e);
            throw new IllegalStateException("Database write failed", e);
        }
    }

    /** Writes entity data (kills and entity changes). */
    private static void insertEntityKills(Connection connection, List<ConsumerEntry> entries, int batchSize) {
        if (entries.isEmpty()) {
            return;
        }
        final String sql = "INSERT INTO " + ConfigHandler.prefix + "entity (time, data) VALUES (?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int count = 0;
            for (ConsumerEntry entry : entries) {
                EntityStatement.insert(statement, count++, entry.time(), entry.blob());
            }
            statement.executeBatch();
        }
        catch (Exception e) {
            ErrorReporter.report(e);
            throw new IllegalStateException("Database write failed", e);
        }
    }

    /** Resolves the user id, creating the user row when needed (0 = unknown cause). */
    private static int resolveUser(Connection connection, ConsumerEntry entry) {
        if (entry.user() == null) {
            return 0;
        }
        try {
            final String uuid = entry.uuid() == null ? entry.user() : entry.uuid();
            int userId = UserStatement.loadId(connection, entry.user(), uuid);
            if (userId < 1) {
                userId = UserStatement.insert(connection, entry.user());
            }
            if (userId < 1) {
                throw new IllegalStateException("Could not resolve user id for " + entry.user());
            }
            return userId;
        }
        catch (Exception e) {
            ErrorReporter.report(e);
            throw new IllegalStateException("Database write failed", e);
        }
    }

    /** Used by the maintenance commands of the port. */
    public static void logPending(ProcessType type) {
        NeoPlatform.LOGGER.debug("[CoreProtect] Process type {} is not implemented yet", type);
    }
}
