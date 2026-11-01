package net.coreprotect.consumer;

import net.coreprotect.config.Config;
import net.coreprotect.config.ConfigHandler;
import net.coreprotect.utility.MaterialUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Queue API used by the listeners.
 *
 * <p>Names and semantics follow the original plugin; the payloads carry the NeoForge equivalents
 * of the Bukkit objects the plugin used (block states, positions and dimension ids).
 */
public class Queue {

    /**
     * Block actions as stored in the {@code block.action} column.
     *
     * <p>Values follow the original plugin: {@code LookupActions.BLOCK_BREAK} = 0 (removed),
     * {@code LookupActions.BLOCK_PLACE} = 1 (placed).
     */
    public static final int ACTION_REMOVED = net.coreprotect.model.action.LookupActions.BLOCK_BREAK;
    /** Block action: the block was placed. */
    public static final int ACTION_PLACED = net.coreprotect.model.action.LookupActions.BLOCK_PLACE;

    protected Queue() {
        throw new IllegalStateException("Queue class");
    }

    public static void queueWorldInsert(int id, String worldName) {
        Consumer.add(ConsumerEntry.builder(ProcessType.WORLD_INSERT, currentTime())
                .position(worldName, 0, 0, 0)
                .material(id)
                .build());
    }

    public static void queueMaterialInsert(int id, String material) {
        Consumer.add(ConsumerEntry.builder(ProcessType.MATERIAL_INSERT, currentTime())
                .position(material, 0, 0, 0)
                .material(id)
                .build());
    }

    public static void queueBlockDataInsert(int id, String data) {
        Consumer.add(ConsumerEntry.builder(ProcessType.BLOCKDATA_INSERT, currentTime())
                .position(data, 0, 0, 0)
                .material(id)
                .build());
    }

    public static void queueEntityInsert(int id, String entity) {
        Consumer.add(ConsumerEntry.builder(ProcessType.ENTITY_INSERT, currentTime())
                .position(entity, 0, 0, 0)
                .material(id)
                .build());
    }

    /** Logs a block being broken by a player. */
    public static void queueBlockBreak(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state, byte[] blockEntity) {
        if (!Config.getGlobal().BLOCK_BREAK || state == null || MaterialUtils.isAir(state)) {
            return;
        }
        final int materialId = MaterialUtils.getMaterialId(MaterialUtils.getBlockName(state), true);
        Consumer.add(ConsumerEntry.builder(ProcessType.BLOCK_BREAK, currentTime())
                .user(playerName(player), playerUuid(player))
                .position(worldName(level), pos.getX(), pos.getY(), pos.getZ())
                .material(materialId)
                .blockData(MaterialUtils.getBlockDataStringWithName(state))
                .blob(blockEntity)
                .action(ACTION_REMOVED)
                .build());
    }

    /** Logs a block being placed by a player. */
    public static void queueBlockPlace(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state, byte[] blockEntity) {
        if (!Config.getGlobal().BLOCK_PLACE || state == null || MaterialUtils.isAir(state)) {
            return;
        }
        final int materialId = MaterialUtils.getMaterialId(MaterialUtils.getBlockName(state), true);
        Consumer.add(ConsumerEntry.builder(ProcessType.BLOCK_PLACE, currentTime())
                .user(playerName(player), playerUuid(player))
                .position(worldName(level), pos.getX(), pos.getY(), pos.getZ())
                .material(materialId)
                .blockData(MaterialUtils.getBlockDataStringWithName(state))
                .blob(blockEntity)
                .action(ACTION_PLACED)
                .build());
    }

    /** Logs a player session (1 = login, 0 = logout). */
    public static void queueSession(ServerPlayer player, ServerLevel level, BlockPos pos, int action) {
        if (!Config.getGlobal().PLAYER_SESSIONS) {
            return;
        }
        Consumer.add(ConsumerEntry.builder(ProcessType.SESSION, currentTime())
                .user(playerName(player), playerUuid(player))
                .position(worldName(level), pos.getX(), pos.getY(), pos.getZ())
                .action(action)
                .build());
    }

    public static void queuePlayerChat(ServerPlayer player, String message) {
        if (!Config.getGlobal().PLAYER_MESSAGES) {
            return;
        }
        Consumer.add(ConsumerEntry.builder(ProcessType.CHAT, currentTime())
                .user(playerName(player), playerUuid(player))
                .position(worldName(player.level()), player.getBlockX(), player.getBlockY(), player.getBlockZ())
                .message(message)
                .build());
    }

    public static void queuePlayerCommand(ServerPlayer player, String command) {
        if (!Config.getGlobal().PLAYER_COMMANDS) {
            return;
        }
        Consumer.add(ConsumerEntry.builder(ProcessType.COMMAND, currentTime())
                .user(playerName(player), playerUuid(player))
                .position(worldName(player.level()), player.getBlockX(), player.getBlockY(), player.getBlockZ())
                .message(command)
                .build());
    }

    /**
     * Logs the difference between two container snapshots, one row per changed item.
     *
     * @param actionRemoved the action id used for items taken out of the container
     */
    public static void queueContainerTransaction(ServerPlayer player, ServerLevel level, BlockPos pos,
                                                 net.minecraft.world.item.ItemStack[] oldStacks,
                                                 net.minecraft.world.item.ItemStack[] newStacks) {
        if (!Config.getGlobal().ITEM_TRANSACTIONS || level == null || pos == null) {
            return;
        }

        final int max = Math.max(oldStacks.length, newStacks.length);
        for (int i = 0; i < max; i++) {
            final net.minecraft.world.item.ItemStack oldStack = i < oldStacks.length ? oldStacks[i] : net.minecraft.world.item.ItemStack.EMPTY;
            final net.minecraft.world.item.ItemStack newStack = i < newStacks.length ? newStacks[i] : net.minecraft.world.item.ItemStack.EMPTY;
            final int oldCount = oldStack.getCount();
            final int newCount = newStack.getCount();

            if (oldCount == newCount && net.minecraft.world.item.ItemStack.isSameItemSameComponents(oldStack, newStack)) {
                continue;
            }

            if (oldCount > newCount) {
                queueContainerEntry(player, level, pos, oldStack, oldCount - newCount, ACTION_REMOVED);
            }
            else if (newCount > oldCount) {
                queueContainerEntry(player, level, pos, newStack, newCount - oldCount, ACTION_PLACED);
            }
        }
    }

    private static void queueContainerEntry(ServerPlayer player, ServerLevel level, BlockPos pos,
                                            net.minecraft.world.item.ItemStack stack, int amount, int action) {
        final String itemName = MaterialUtils.getItemName(stack);
        if (itemName == null) {
            return;
        }
        final int materialId = MaterialUtils.getMaterialId(itemName, true);
        Consumer.add(ConsumerEntry.builder(ProcessType.CONTAINER, currentTime())
                .user(playerName(player), playerUuid(player))
                .position(worldName(level), pos.getX(), pos.getY(), pos.getZ())
                .material(materialId)
                .amount(amount)
                .action(action)
                .blob(net.coreprotect.utility.ItemUtils.convertByteData(stack.copy()))
                .build());
    }

    /** Logs an item transaction outside of a container (drops and pickups). */
    public static void queueItemTransaction(ServerPlayer player, ServerLevel level, BlockPos pos,
                                            net.minecraft.world.item.ItemStack stack, int amount, int action) {
        if (!Config.getGlobal().ITEM_DROPS && action == ACTION_REMOVED) {
            return;
        }
        if (!Config.getGlobal().ITEM_PICKUPS && action == ACTION_PLACED) {
            return;
        }
        final String itemName = MaterialUtils.getItemName(stack);
        if (itemName == null || amount < 1) {
            return;
        }
        Consumer.add(ConsumerEntry.builder(ProcessType.ITEM, currentTime())
                .user(playerName(player), playerUuid(player))
                .position(worldName(level), pos.getX(), pos.getY(), pos.getZ())
                .material(MaterialUtils.getMaterialId(itemName, true))
                .amount(amount)
                .action(action)
                .blob(net.coreprotect.utility.ItemUtils.convertByteData(stack.copy()))
                .build());
    }

    /** Logs the death of an entity (player kills, mob kills and natural deaths). */
    public static void queueEntityKill(String user, String uuid, ServerLevel level, net.minecraft.world.entity.Entity entity) {
        if (!Config.getGlobal().ENTITY_KILLS || entity == null || level == null) {
            return;
        }
        Consumer.add(ConsumerEntry.builder(ProcessType.ENTITY_KILL, currentTime())
                .user(user, uuid)
                .position(worldName(level), entity.getBlockX(), entity.getBlockY(), entity.getBlockZ())
                .blob(net.coreprotect.utility.ItemUtils.convertEntityByteData(entity))
                .build());
    }

    /** Resolves (and creates) the world id of a dimension. */
    public static int getWorldId(String worldName) {
        Integer id = ConfigHandler.worlds.get(worldName);
        if (id != null) {
            return id;
        }
        synchronized (ConfigHandler.worlds) {
            Integer existing = ConfigHandler.worlds.get(worldName);
            if (existing != null) {
                return existing;
            }
            int newId = ++ConfigHandler.worldId;
            ConfigHandler.worlds.put(worldName, newId);
            ConfigHandler.worldsReversed.put(newId, worldName);
            queueWorldInsert(newId, worldName);
            return newId;
        }
    }

    static int currentTime() {
        return (int) (System.currentTimeMillis() / 1000L);
    }

    static String playerName(ServerPlayer player) {
        return player == null ? null : player.getGameProfile().name();
    }

    static String playerUuid(ServerPlayer player) {
        return player == null ? null : player.getUUID().toString();
    }

    static String worldName(ServerLevel level) {
        return level == null ? null : level.dimension().identifier().toString();
    }
}
