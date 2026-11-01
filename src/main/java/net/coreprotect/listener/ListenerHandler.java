package net.coreprotect.listener;

import net.coreprotect.consumer.Queue;
import net.coreprotect.neoforge.NeoPlatform;
import net.coreprotect.utility.ItemUtils;
import net.coreprotect.utility.BlockEntityData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registers the NeoForge event handlers that feed the consumer queue.
 *
 * <p>Player block changes, sessions, chat, item tosses, entity deaths and container menus are
 * observed here. Mod-driven bulk edits require their own integration path.
 */
public class ListenerHandler {

    /** Session action ids used by the session table. */
    public static final int SESSION_LOGIN = 1;
    public static final int SESSION_LOGOUT = 0;

    /** Block position of the container a player is about to open. */
    private static final Map<UUID, BlockPos> CONTAINER_POS = new ConcurrentHashMap<>();
    /** Snapshot taken when a container was opened. */
    private static final Map<UUID, ContainerSnapshot> OPEN_CONTAINERS = new ConcurrentHashMap<>();
    /**
     * Game tick of the last inspector lookup per player.
     *
     * <p>NeoForge fires {@code RightClickBlock} once per hand (and again in some interaction
     * flows), so the inspector output is de-duplicated per tick.
     */
    private static final Map<UUID, Long> LAST_INSPECT_TICK = new ConcurrentHashMap<>();

    private record ContainerSnapshot(String world, BlockPos pos, ServerLevel level, ItemStack[] contents) {
    }

    public ListenerHandler(Object plugin) {
        NeoForge.EVENT_BUS.register(this);
    }

    public static void registerNetworking() {
        NeoPlatform.LOGGER.debug("[CoreProtect] Networking API is not available in the NeoForge port yet.");
    }

    public static void unregisterNetworking() {
        // Nothing to unregister yet.
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onBlockBreak(BreakBlockEvent event) {
        if (event.isCanceled() || !(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        final BlockPos pos = event.getPos();
        final BlockState state = event.getState();
        Queue.queueBlockBreak(player, level, pos, state,
                BlockEntityData.capture(level.getBlockEntity(pos), level.registryAccess()));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        final BlockPos pos = event.getPos();
        final BlockState replaced = event.getBlockSnapshot().getState();
        if (!replaced.isAir()) {
            Queue.queueBlockBreak(player, level, pos, replaced,
                    BlockEntityData.encode(event.getBlockSnapshot().getTag()));
        }
        Queue.queueBlockPlace(player, level, pos, event.getPlacedBlock(),
                BlockEntityData.capture(level.getBlockEntity(pos), level.registryAccess()));
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        Queue.queueSession(player, level, player.blockPosition(), SESSION_LOGIN);
    }

    @SubscribeEvent
    public void onPlayerQuit(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        Queue.queueSession(player, level, player.blockPosition(), SESSION_LOGOUT);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onServerChat(ServerChatEvent event) {
        if (event.isCanceled()) {
            return;
        }
        final ServerPlayer player = event.getPlayer();
        if (player == null) {
            return;
        }
        Queue.queuePlayerChat(player, event.getRawText());
    }

    /** Remembers which container block the player right-clicks, so the open/close diff knows the position. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.isCanceled()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player) || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        // Only the main hand: the event also fires for the off hand.
        if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) {
            return;
        }
        final BlockPos pos = event.getPos();

        // Inspector: right-clicking a block prints its history.
        final String inspectorKey = player.getGameProfile().name().toLowerCase(java.util.Locale.ROOT);
        if (Boolean.TRUE.equals(net.coreprotect.config.ConfigHandler.inspecting.get(inspectorKey))) {
            final long gameTime = level.getGameTime();
            final Long lastTick = LAST_INSPECT_TICK.get(player.getUUID());
            if (lastTick == null || lastTick != gameTime) {
                LAST_INSPECT_TICK.put(player.getUUID(), gameTime);
                showBlockHistory(player, level, pos);
            }
        }

        if (level.getBlockEntity(pos) instanceof Container) {
            CONTAINER_POS.put(player.getUUID(), pos.immutable());
        }
    }

    /** Prints the block history of a position for the inspector. */
    private void showBlockHistory(ServerPlayer player, ServerLevel level, BlockPos pos) {
        final var rows = net.coreprotect.database.Lookup.performPositionLookup(
                level.dimension().identifier().toString(), pos.getX(), pos.getY(), pos.getZ(), 20);

        net.coreprotect.utility.Chat.sendMessage(player, net.coreprotect.utility.Color.WHITE
                + "----- " + net.coreprotect.utility.Color.DARK_AQUA
                + net.coreprotect.language.Phrase.build(net.coreprotect.language.Phrase.LOOKUP_HEADER, "CoreProtect")
                + net.coreprotect.utility.Color.WHITE + " (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
                + ") -----");

        if (rows.isEmpty()) {
            net.coreprotect.utility.Chat.sendMessage(player, net.coreprotect.utility.Color.WHITE
                    + net.coreprotect.language.Phrase.build(net.coreprotect.language.Phrase.NO_DATA_LOCATION));
            return;
        }

        for (var row : rows) {
            net.coreprotect.utility.Chat.sendMessage(player, net.coreprotect.utility.Color.GREY
                    + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(row.time() * 1000L))
                    + net.coreprotect.utility.Color.WHITE + " | " + row.user()
                    + net.coreprotect.utility.Color.GREY + " | "
                    + net.coreprotect.model.action.LookupActions.getActionString(row.action())
                    + " " + row.blockName());
        }
    }

    @SubscribeEvent
    public void onContainerOpen(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        final BlockPos pos = CONTAINER_POS.get(player.getUUID());
        if (pos == null || !(level.getBlockEntity(pos) instanceof Container container)) {
            return;
        }
        OPEN_CONTAINERS.put(player.getUUID(), new ContainerSnapshot(
                level.dimension().identifier().toString(), pos, level, readContainer(container)));
    }

    @SubscribeEvent
    public void onContainerClose(PlayerContainerEvent.Close event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        final ContainerSnapshot snapshot = OPEN_CONTAINERS.remove(player.getUUID());
        CONTAINER_POS.remove(player.getUUID());
        if (snapshot == null) {
            return;
        }
        final ServerLevel level = snapshot.level();
        if (!(level.getBlockEntity(snapshot.pos()) instanceof Container container)) {
            return;
        }
        final ItemStack[] after = readContainer(container);
        Queue.queueContainerTransaction(player, level, snapshot.pos(), snapshot.contents(), after);
    }

    /** Logs items a player throws on the ground. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onItemToss(ItemTossEvent event) {
        if (event.isCanceled()) {
            return;
        }
        final net.minecraft.world.entity.player.Player tossedBy = event.getPlayer();
        final ItemEntity itemEntity = event.getEntity();
        if (!(tossedBy instanceof ServerPlayer player) || itemEntity == null
                || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        final ItemStack stack = itemEntity.getItem();
        Queue.queueItemTransaction(player, level, itemEntity.blockPosition(), stack,
                stack.getCount(), Queue.ACTION_REMOVED);
    }

    /** Logs entity deaths (player kills, mob kills and natural deaths). */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onLivingDeath(LivingDeathEvent event) {
        if (event.isCanceled()) {
            return;
        }
        final LivingEntity entity = event.getEntity();
        if (entity == null || entity.level().isClientSide() || !(entity.level() instanceof ServerLevel level)) {
            return;
        }
        if (entity instanceof ServerPlayer) {
            return; // Player deaths are logged through the session/kill handling of the port.
        }

        String user = null;
        String uuid = null;
        final net.minecraft.world.entity.Entity killer = event.getSource().getEntity();
        if (killer instanceof ServerPlayer player) {
            user = NeoPlatform.playerName(player);
            uuid = NeoPlatform.playerUuid(player);
        }
        else if (killer instanceof LivingEntity living) {
            user = "#" + net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                    .getKey(living.getType()).getPath();
        }
        else if (event.getSource().getDirectEntity() == null) {
            user = "#natural";
        }

        Queue.queueEntityKill(user, uuid, level, entity);
    }

    /** Logs items a player picks up. */
    @SubscribeEvent
    public void onItemPickup(net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent.Pre event) {
        final net.minecraft.world.entity.player.Player picker = event.getPlayer();
        final ItemEntity itemEntity = event.getItemEntity();
        if (!(picker instanceof ServerPlayer player) || itemEntity == null
                || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        final ItemStack stack = itemEntity.getItem();
        Queue.queueItemTransaction(player, level, player.blockPosition(), stack, stack.getCount(),
                Queue.ACTION_PLACED);
    }

    /** Copies the contents of a container block entity. */
    private static ItemStack[] readContainer(Container container) {
        final ItemStack[] stacks = new ItemStack[container.getContainerSize()];
        for (int i = 0; i < stacks.length; i++) {
            stacks[i] = container.getItem(i).copy();
        }
        return ItemUtils.copyStacks(stacks);
    }
}
