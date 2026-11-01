package net.coreprotect.utility;

import java.util.Locale;

import net.coreprotect.config.ConfigHandler;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Block/material id mapping of the NeoForge port.
 *
 * <p>The original plugin keyed its caches on Bukkit {@code Material} values; the port keys them on
 * block/item registry names ("minecraft:stone"), which keeps the database layout and the lookup
 * output identical in meaning.
 */
public final class MaterialUtils {

    private MaterialUtils() {
        throw new IllegalStateException("Utility class");
    }

    /** Registry name of a block, e.g. "minecraft:stone". */
    public static String getBlockName(BlockState state) {
        if (state == null) {
            return null;
        }
        Identifier key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return key == null ? null : key.toString();
    }

    public static String getBlockName(int type) {
        return ConfigHandler.materialsReversed.get(type);
    }

    public static Block getType(int type) {
        String name = ConfigHandler.materialsReversed.get(type);
        if (name == null) {
            return null;
        }
        Identifier key = Identifier.tryParse(name);
        if (key == null || !BuiltInRegistries.BLOCK.containsKey(key)) {
            return null;
        }
        return BuiltInRegistries.BLOCK.getValue(key);
    }

    public static Item getItem(String name) {
        Identifier key = Identifier.tryParse(name);
        if (key == null || !BuiltInRegistries.ITEM.containsKey(key)) {
            return null;
        }
        return BuiltInRegistries.ITEM.getValue(key);
    }

    /** Item registry name of a stack, e.g. "minecraft:diamond_pickaxe". */
    public static String getItemName(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key == null ? null : key.toString();
    }

    /**
     * Resolves (and caches) the material id of a registry name.
     *
     * @param name   registry name such as "minecraft:stone"
     * @param insert whether a missing entry should be queued for insertion
     * @return the material id, or -1 when unknown
     */
    public static int getMaterialId(String name, boolean insert) {
        if (name == null) {
            return -1;
        }
        Integer id = ConfigHandler.materials.get(name);
        if (id != null) {
            return id;
        }
        if (ConfigHandler.materials.isEmpty() && !ConfigHandler.databaseReachable) {
            return -1;
        }
        id = ConfigHandler.reloadAndGetId(ConfigHandler.CacheType.MATERIALS, name);
        if (id > -1) {
            return id;
        }
        if (!insert) {
            return -1;
        }
        synchronized (ConfigHandler.materials) {
            Integer existing = ConfigHandler.materials.get(name);
            if (existing != null) {
                return existing;
            }
            int newId = ++ConfigHandler.materialId;
            ConfigHandler.materials.put(name, newId);
            ConfigHandler.materialsReversed.put(newId, name);
            net.coreprotect.consumer.Queue.queueMaterialInsert(newId, name);
            return newId;
        }
    }

    /** Resolves (and caches) the id of a single block state property, e.g. "axis=y". */
    public static int getBlockdataId(String data, boolean insert) {
        if (data == null) {
            return -1;
        }
        String key = data.toLowerCase(Locale.ROOT);
        Integer id = ConfigHandler.blockdata.get(key);
        if (id != null) {
            return id;
        }
        id = ConfigHandler.reloadAndGetId(ConfigHandler.CacheType.BLOCKDATA, key);
        if (id > -1) {
            return id;
        }
        if (!insert) {
            return -1;
        }
        synchronized (ConfigHandler.blockdata) {
            Integer existing = ConfigHandler.blockdata.get(key);
            if (existing != null) {
                return existing;
            }
            int newId = ++ConfigHandler.blockdataId;
            ConfigHandler.blockdata.put(key, newId);
            ConfigHandler.blockdataReversed.put(newId, key);
            net.coreprotect.consumer.Queue.queueBlockDataInsert(newId, key);
            return newId;
        }
    }

    public static String getBlockdataName(int id) {
        return ConfigHandler.blockdataReversed.get(id);
    }

    /** Human readable property list of a block state, e.g. "axis=y,waterlogged=false". */
    public static String getBlockDataString(BlockState state) {
        if (state == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (Property<?> property : state.getProperties()) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(property.getName()).append('=').append(getPropertyValue(state, property));
        }
        return builder.length() == 0 ? null : builder.toString();
    }

    private static <T extends Comparable<T>> String getPropertyValue(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    /** CoreProtect block data string, e.g. "minecraft:oak_log[axis=y]". */
    public static String getBlockDataStringWithName(BlockState state) {
        String name = getBlockName(state);
        if (name == null) {
            return null;
        }
        String properties = getBlockDataString(state);
        return properties == null ? name : name + "[" + properties + "]";
    }

    public static boolean isAir(BlockState state) {
        return state == null || state.isAir() || state.getBlock() == Blocks.AIR || state.getBlock() == Blocks.CAVE_AIR || state.getBlock() == Blocks.VOID_AIR;
    }
}
