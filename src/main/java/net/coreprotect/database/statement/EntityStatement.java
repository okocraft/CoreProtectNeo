package net.coreprotect.database.statement;

import java.sql.PreparedStatement;

import net.coreprotect.utility.ErrorReporter;
import net.coreprotect.utility.ItemUtils;

/**
 * Writes entity data (kills, entity changes) into the {@code entity} table.
 *
 * <p>The original serialized the Bukkit entity with Java object streams; the port stores the entity
 * as NBT bytes (see {@link ItemUtils#convertByteData(Object)}).
 */
public class EntityStatement {

    private EntityStatement() {
        throw new IllegalStateException("Database class");
    }

    public static void insert(PreparedStatement preparedStmt, int batchCount, int time, Object data) {
        try {
            byte[] byteData = ItemUtils.convertByteData(data);
            preparedStmt.setInt(1, time);
            preparedStmt.setObject(2, byteData);
            preparedStmt.addBatch();

            if (batchCount > 0 && batchCount % 1000 == 0) {
                preparedStmt.executeBatch();
            }
        }
        catch (Exception e) {
            ErrorReporter.report(e);
            throw new IllegalStateException("Database write failed", e);
        }
    }
}
