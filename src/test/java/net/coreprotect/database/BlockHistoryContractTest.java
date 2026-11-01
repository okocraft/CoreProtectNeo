package net.coreprotect.database;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;

import net.coreprotect.config.ConfigHandler;
import net.coreprotect.utility.BlockEntityData;
import net.coreprotect.utility.BlockUtils;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlockHistoryContractTest {

    @TempDir Path directory;

    @Test
    void storesStateAndBlockEntityAndSelectsOnlyChangesInRequestedRollbackState() throws Exception {
        ConfigHandler.path = directory.toString() + java.io.File.separator;
        ConfigHandler.sqlite = "history.db";
        ConfigHandler.prefix = "co_";
        ConfigHandler.worlds.clear();
        ConfigHandler.worldsReversed.clear();
        ConfigHandler.materialsReversed.clear();
        ConfigHandler.blockdataReversed.clear();
        ConfigHandler.worlds.put("minecraft:overworld", 1);
        ConfigHandler.worldsReversed.put(1, "minecraft:overworld");
        ConfigHandler.materialsReversed.put(3, "minecraft:chest");
        ConfigHandler.materialsReversed.put(4, "minecraft:stone");

        CompoundTag chest = new CompoundTag();
        chest.putString("id", "minecraft:chest");
        chest.putString("CustomName", "Stored goods");
        byte[] entity = BlockEntityData.encode(chest);

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("history.db"));
             var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE co_block (time INTEGER, user INTEGER, wid INTEGER, x INTEGER, y INTEGER, z INTEGER, type INTEGER, data INTEGER, meta BLOB, blockdata BLOB, action INTEGER, rolled_back INTEGER)");
            try (var insert = connection.prepareStatement("INSERT INTO co_block (time,user,wid,x,y,z,type,data,meta,blockdata,action,rolled_back) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
                insert.setInt(1, 100);
                insert.setInt(2, 9);
                insert.setInt(3, 1);
                insert.setInt(4, 8);
                insert.setInt(5, 64);
                insert.setInt(6, 8);
                insert.setInt(7, 3);
                insert.setInt(8, 0);
                insert.setBytes(9, entity);
                insert.setBytes(10, "minecraft:chest[facing=north,type=single,waterlogged=false]".getBytes(StandardCharsets.UTF_8));
                insert.setInt(11, 0);
                insert.setInt(12, 0);
                insert.executeUpdate();
                insert.setInt(7, 4);
                insert.setBytes(9, null);
                insert.setBytes(10, "minecraft:stone".getBytes(StandardCharsets.UTF_8));
                insert.setInt(11, 1);
                insert.executeUpdate();
            }
        }

        List<Lookup.BlockRow> rollback = query(false);
        assertEquals(List.of(2L, 1L), rollback.stream().map(Lookup.BlockRow::rowId).toList());
        assertEquals("minecraft:stone", rollback.getFirst().blockData());
        assertEquals("Stored goods", BlockEntityData.decode(rollback.get(1).blockEntity()).getStringOr("CustomName", ""));

        assertEquals(2, Lookup.markRolledBack(List.of(2L, 1L), false));
        assertTrue(query(false).isEmpty());
        assertEquals(0, Lookup.markRolledBack(List.of(2L, 1L), false));
        assertEquals(List.of(1L, 2L), query(true).stream().map(Lookup.BlockRow::rowId).toList());
        assertEquals(2, Lookup.markRolledBack(List.of(1L, 2L), true));
        assertTrue(query(true).isEmpty());
        assertEquals(2, query(false).size());
    }

    @Test
    void readsRawAndPreviouslyStoredNumericBlockProperties() {
        ConfigHandler.materialsReversed.clear();
        ConfigHandler.blockdataReversed.clear();
        ConfigHandler.materialsReversed.put(5, "minecraft:oak_log");
        ConfigHandler.blockdataReversed.put(7, "axis=y");
        assertEquals("minecraft:stone", BlockUtils.byteDataToString(
                BlockUtils.stringToByteData("minecraft:stone", 4), 4));
        assertEquals("minecraft:oak_log[axis=y]", BlockUtils.byteDataToString("7".getBytes(StandardCharsets.UTF_8), 5));
        assertEquals("minecraft:oak_log[axis=x]", BlockUtils.byteDataToString(
                BlockUtils.stringToByteData("minecraft:oak_log[axis=x]", 5), 5));
    }

    private List<Lookup.BlockRow> query(boolean restore) {
        return Lookup.performRollbackLookup(9, "minecraft:overworld", 0, 0,
                List.of(), 0, 8, 8, 100, restore);
    }
}
