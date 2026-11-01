package net.coreprotect.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import net.coreprotect.config.ConfigHandler;
import net.coreprotect.utility.BlockUtils;
import net.coreprotect.utility.ErrorReporter;
import net.coreprotect.utility.MaterialUtils;
import net.coreprotect.utility.WorldUtils;

/**
 * Block lookup of the NeoForge port.
 *
 * <p>Queries the original {@code block} table (identical columns) and returns rows for the chat
 * output and for rollbacks.
 */
public final class Lookup {

    private Lookup() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * One block row of the database.
     *
     * @param rowId     primary key, used to mark the row as rolled back
     * @param action    block action (0 = broken, 1 = placed)
     */
    public record BlockRow(long rowId, int time, String user, String world, int x, int y, int z,
                           int typeId, String blockData, byte[] blockEntity, int action) {

        public String blockName() {
            final String name = MaterialUtils.getBlockName(typeId);
            return name == null ? "minecraft:air" : name;
        }
    }

    /**
     * Queries block changes.
     *
     * @param userId    user id filter, 0 for every player
     * @param worldName dimension id filter, null for every dimension
     * @param timeStart unix seconds of the oldest row, 0 for no limit
     * @param timeEnd   unix seconds of the newest row, 0 for no limit
     * @param actions   action ids to include, empty for break and place
     * @param radius    radius around the origin, 0 for no radius
     * @param limit     maximum number of rows
     */
    public static List<BlockRow> performBlockLookup(int userId, String worldName, long timeStart, long timeEnd,
                                                   List<Integer> actions, int radius, int originX, int originZ,
                                                   int limit, int offset) {
        return queryBlocks(userId, worldName, timeStart, timeEnd, actions, radius, originX, originZ,
                limit, offset, null);
    }

    /** Selects only changes in the state needed for rollback or restore. */
    public static List<BlockRow> performRollbackLookup(int userId, String worldName, long timeStart, long timeEnd,
                                                      List<Integer> actions, int radius, int originX, int originZ,
                                                      int limit, boolean restore) {
        return queryBlocks(userId, worldName, timeStart, timeEnd, actions, radius, originX, originZ,
                limit, 0, restore);
    }

    private static List<BlockRow> queryBlocks(int userId, String worldName, long timeStart, long timeEnd,
                                              List<Integer> actions, int radius, int originX, int originZ,
                                              int limit, int offset, Boolean restore) {
        final List<BlockRow> rows = new ArrayList<>();
        final StringBuilder sql = new StringBuilder(
                "SELECT rowid,time,user,wid,x,y,z,type,data,meta,blockdata,action FROM ")
                .append(ConfigHandler.prefix).append("block WHERE 1=1");

        if (userId > 0) {
            sql.append(" AND user = ?");
        }
        if (worldName != null) {
            if (ConfigHandler.worlds.get(worldName) == null) {
                return rows;
            }
            sql.append(" AND wid = ?");
        }
        if (timeStart > 0) {
            sql.append(" AND time >= ?");
        }
        if (timeEnd > 0) {
            sql.append(" AND time <= ?");
        }
        if (radius > 0) {
            sql.append(" AND x >= ? AND x <= ? AND z >= ? AND z <= ?");
        }
        if (actions != null && !actions.isEmpty()) {
            sql.append(" AND (");
            for (int i = 0; i < actions.size(); i++) {
                sql.append(i == 0 ? "" : " OR ").append("action = ?");
            }
            sql.append(")");
        }
        if (restore != null) {
            sql.append(" AND rolled_back = ?");
        }
        sql.append(restore != null && restore ? " ORDER BY time ASC, rowid ASC" : " ORDER BY time DESC, rowid DESC")
                .append(" LIMIT ? OFFSET ?");

        try (Connection connection = Database.getConnection(false)) {
            if (connection == null) {
                return rows;
            }
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                int index = 1;
                if (userId > 0) {
                    statement.setInt(index++, userId);
                }
                if (worldName != null) {
                    statement.setInt(index++, ConfigHandler.worlds.get(worldName));
                }
                if (timeStart > 0) {
                    statement.setLong(index++, timeStart);
                }
                if (timeEnd > 0) {
                    statement.setLong(index++, timeEnd);
                }
                if (radius > 0) {
                    statement.setInt(index++, originX - radius);
                    statement.setInt(index++, originX + radius);
                    statement.setInt(index++, originZ - radius);
                    statement.setInt(index++, originZ + radius);
                }
                if (actions != null) {
                    for (Integer action : actions) {
                        statement.setInt(index++, action);
                    }
                }
                if (restore != null) {
                    statement.setInt(index++, restore ? 1 : 0);
                }
                statement.setInt(index++, limit);
                statement.setInt(index, offset);

                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        final int typeId = resultSet.getInt("type");
                        rows.add(new BlockRow(
                                resultSet.getLong("rowid"),
                                resultSet.getInt("time"),
                                ConfigHandler.playerIdCacheReversed.getOrDefault(resultSet.getInt("user"), "#unknown"),
                                WorldUtils.getWorldName(resultSet.getInt("wid")),
                                resultSet.getInt("x"),
                                resultSet.getInt("y"),
                                resultSet.getInt("z"),
                                typeId,
                                BlockUtils.byteDataToString(resultSet.getBytes("blockdata"), typeId),
                                resultSet.getBytes("meta"),
                                resultSet.getInt("action")));
                    }
                }
            }
        }
        catch (Exception e) {
            ErrorReporter.report(e);
        }
        return rows;
    }

    /**
     * Block history of a single position (used by the inspector).
     */
    public static List<BlockRow> performPositionLookup(String worldName, int x, int y, int z, int limit) {
        final List<BlockRow> rows = new ArrayList<>();
        final Integer worldId = worldName == null ? null : ConfigHandler.worlds.get(worldName);
        if (worldId == null) {
            return rows;
        }

        final String sql = "SELECT rowid,time,user,wid,x,y,z,type,data,meta,blockdata,action FROM "
                + ConfigHandler.prefix + "block WHERE wid = ? AND x = ? AND y = ? AND z = ?"
                + " ORDER BY time DESC LIMIT ?";

        try (Connection connection = Database.getConnection(false)) {
            if (connection == null) {
                return rows;
            }
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, worldId);
                statement.setInt(2, x);
                statement.setInt(3, y);
                statement.setInt(4, z);
                statement.setInt(5, limit);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        final int typeId = resultSet.getInt("type");
                        rows.add(new BlockRow(
                                resultSet.getLong("rowid"),
                                resultSet.getInt("time"),
                                ConfigHandler.playerIdCacheReversed.getOrDefault(resultSet.getInt("user"), "#unknown"),
                                WorldUtils.getWorldName(resultSet.getInt("wid")),
                                resultSet.getInt("x"),
                                resultSet.getInt("y"),
                                resultSet.getInt("z"),
                                typeId,
                                BlockUtils.byteDataToString(resultSet.getBytes("blockdata"), typeId),
                                resultSet.getBytes("meta"),
                                resultSet.getInt("action")));
                    }
                }
            }
        }
        catch (Exception e) {
            ErrorReporter.report(e);
        }
        return rows;
    }

    /** Changes only rows in the expected state, allowing rollback and restore to be repeated safely. */
    public static int markRolledBack(List<Long> rowIds, boolean restore) {
        if (rowIds == null || rowIds.isEmpty()) {
            return 0;
        }
        try (Connection connection = Database.getConnection(false)) {
            if (connection == null) {
                return 0;
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE " + ConfigHandler.prefix + "block SET rolled_back = ? WHERE rowid = ? AND rolled_back = ?")) {
                for (Long rowId : rowIds) {
                    statement.setInt(1, restore ? 0 : 1);
                    statement.setLong(2, rowId);
                    statement.setInt(3, restore ? 1 : 0);
                    statement.addBatch();
                }
                int updated = 0;
                for (int count : statement.executeBatch()) {
                    updated += count == java.sql.Statement.SUCCESS_NO_INFO ? 1 : Math.max(0, count);
                }
                return updated;
            }
        }
        catch (Exception e) {
            ErrorReporter.report(e);
            return 0;
        }
    }
}
