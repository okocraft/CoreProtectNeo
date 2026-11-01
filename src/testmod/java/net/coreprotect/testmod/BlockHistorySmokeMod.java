package net.coreprotect.testmod;

import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import net.coreprotect.consumer.Consumer;
import net.coreprotect.config.ConfigHandler;
import net.coreprotect.database.Lookup;
import net.coreprotect.database.Rollback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Packaged only into the isolated dedicated-server smoke test. */
@Mod("coreprotect_test")
public final class BlockHistorySmokeMod {
    private static final BlockPos CHEST_POS = new BlockPos(8192, 100, 8192);
    private static final BlockPos LOG_POS = new BlockPos(8200, 100, 8192);
    private static final BlockPos REPLACED_POS = new BlockPos(8210, 100, 8192);
    private static final BlockPos DENIED_BREAK_POS = new BlockPos(8220, 100, 8192);
    private static final BlockPos DENIED_PLACE_POS = new BlockPos(8230, 100, 8192);
    private static final BlockPos OUTAGE_POS = new BlockPos(8240, 100, 8192);
    private boolean ran;
    private int ticks;

    public BlockHistorySmokeMod() {
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void denyBreak(BreakBlockEvent event) {
        if (event.getPos().equals(DENIED_BREAK_POS)) event.setCanceled(true);
    }

    @SubscribeEvent
    public void denyPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getPos().equals(DENIED_PLACE_POS)) event.setCanceled(true);
    }

    @SubscribeEvent
    public void onTick(ServerTickEvent.Post event) {
        if (ran || ++ticks < 20) return;
        ran = true;
        try {
            if (levelHasPersistedFixture(event.getServer().overworld())) {
                verifyRestart(event.getServer().overworld());
                System.out.println("COREPROTECT_RESTART_PASSED");
            }
            else {
                exercise(event.getServer().overworld());
                System.out.println("COREPROTECT_SMOKE_PASSED");
            }
        }
        catch (Throwable failure) {
            System.err.println("COREPROTECT_SMOKE_FAILED: " + failure);
            failure.printStackTrace(System.err);
            event.getServer().halt(false);
        }
    }

    private static boolean levelHasPersistedFixture(ServerLevel level) {
        return level.getBlockState(REPLACED_POS).is(Blocks.STONE);
    }

    private static void verifyRestart(ServerLevel level) {
        expect(level.getBlockState(LOG_POS).is(Blocks.OAK_LOG)
                && level.getBlockState(LOG_POS).getValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS)
                == Direction.Axis.X, "Log state was not saved across restart");
        expect(level.getBlockState(REPLACED_POS).is(Blocks.STONE), "Replacement was not saved across restart");
        expect(Lookup.performPositionLookup(level.dimension().identifier().toString(),
                CHEST_POS.getX(), CHEST_POS.getY(), CHEST_POS.getZ(), 10).size() == 1,
                "Chest history was not loaded across restart");
        expect(Lookup.performPositionLookup(level.dimension().identifier().toString(),
                REPLACED_POS.getX(), REPLACED_POS.getY(), REPLACED_POS.getZ(), 10).size() == 2,
                "Replacement history was not loaded across restart");
        expect(Lookup.performPositionLookup(level.dimension().identifier().toString(),
                DENIED_BREAK_POS.getX(), DENIED_BREAK_POS.getY(), DENIED_BREAK_POS.getZ(), 10).isEmpty(),
                "Canceled break appeared after restart");
    }

    private static void exercise(ServerLevel level) {
        if (!level.getBlockState(CHEST_POS).isAir() || !level.getBlockState(LOG_POS).isAir()
                || !level.getBlockState(REPLACED_POS).isAir()) {
            throw new AssertionError("Isolated fixture positions are occupied");
        }
        FakePlayer player = FakePlayerFactory.get(level,
                new GameProfile(UUID.fromString("de5ef1dc-5dbc-40d7-b4f4-9fbfa1abf462"), "cp_smoke"));
        Consumer.stopConsumer();

        level.setBlockAndUpdate(CHEST_POS, Blocks.CHEST.defaultBlockState());
        Container chest = (Container) level.getBlockEntity(CHEST_POS);
        chest.setItem(0, new ItemStack(Items.DIAMOND, 4));
        BreakBlockEvent breakEvent = new BreakBlockEvent(level, CHEST_POS, level.getBlockState(CHEST_POS), player);
        NeoForge.EVENT_BUS.post(breakEvent);
        if (breakEvent.isCanceled()) throw new AssertionError("Chest break was canceled");
        level.setBlockAndUpdate(CHEST_POS, Blocks.AIR.defaultBlockState());
        Consumer.flush();

        List<Lookup.BlockRow> changes = changes(level, CHEST_POS, false);
        expect(changes.size() == 1 && changes.getFirst().action() == 0, "Chest break not logged");
        Rollback.Result rolled = Rollback.performBlockRollback(level, changes, false);
        expect(rolled.blocksModified() == 1 && rolled.rowsMarked() == 1, "Chest rollback did not apply");
        expect(level.getBlockState(CHEST_POS).is(Blocks.CHEST), "Chest state was not restored");
        Container restored = (Container) level.getBlockEntity(CHEST_POS);
        expect(restored != null && restored.getItem(0).is(Items.DIAMOND)
                && restored.getItem(0).getCount() == 4, "Chest contents were not restored");
        expect(changes(level, CHEST_POS, false).isEmpty(), "Chest rollback remained selectable");
        Rollback.Result restoredResult = Rollback.performBlockRollback(level, changes(level, CHEST_POS, true), true);
        expect(restoredResult.blocksModified() == 1 && restoredResult.rowsMarked() == 1, "Chest restore did not apply");
        expect(level.getBlockState(CHEST_POS).isAir(), "Chest restore did not remove the chest");

        BlockSnapshot snapshot = BlockSnapshot.create(level.dimension(), level, LOG_POS);
        var placed = Blocks.OAK_LOG.defaultBlockState().setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS, Direction.Axis.X);
        level.setBlockAndUpdate(LOG_POS, placed);
        BlockEvent.EntityPlaceEvent placeEvent = new BlockEvent.EntityPlaceEvent(snapshot, placed, player);
        NeoForge.EVENT_BUS.post(placeEvent);
        expect(!placeEvent.isCanceled(), "Log placement was canceled");
        Consumer.flush();
        changes = changes(level, LOG_POS, false);
        expect(changes.size() == 1 && changes.getFirst().action() == 1, "Log placement not logged");
        rolled = Rollback.performBlockRollback(level, changes, false);
        expect(rolled.blocksModified() == 1 && rolled.rowsMarked() == 1, "Log rollback did not apply");
        expect(level.getBlockState(LOG_POS).isAir(), "Log rollback did not remove the log");
        restoredResult = Rollback.performBlockRollback(level, changes(level, LOG_POS, true), true);
        expect(restoredResult.blocksModified() == 1 && restoredResult.rowsMarked() == 1, "Log restore did not apply");
        expect(level.getBlockState(LOG_POS).equals(placed), "Log axis was not restored");

        level.setBlockAndUpdate(REPLACED_POS, Blocks.CHEST.defaultBlockState());
        ((Container) level.getBlockEntity(REPLACED_POS)).setItem(0, new ItemStack(Items.EMERALD, 2));
        BlockSnapshot replacedSnapshot = BlockSnapshot.create(level.dimension(), level, REPLACED_POS);
        level.setBlockAndUpdate(REPLACED_POS, Blocks.STONE.defaultBlockState());
        BlockEvent.EntityPlaceEvent replacement = new BlockEvent.EntityPlaceEvent(
                replacedSnapshot, Blocks.STONE.defaultBlockState(), player);
        NeoForge.EVENT_BUS.post(replacement);
        expect(!replacement.isCanceled(), "Replacement was canceled");
        Consumer.flush();
        changes = changes(level, REPLACED_POS, false);
        expect(changes.size() == 2 && changes.get(0).action() == 1 && changes.get(1).action() == 0,
                "Replacement did not record both sides in order");
        rolled = Rollback.performBlockRollback(level, changes, false);
        expect(rolled.blocksModified() == 2 && rolled.rowsMarked() == 2,
                "Replacement rollback did not apply both changes");
        Container original = (Container) level.getBlockEntity(REPLACED_POS);
        expect(level.getBlockState(REPLACED_POS).is(Blocks.CHEST) && original != null
                && original.getItem(0).is(Items.EMERALD) && original.getItem(0).getCount() == 2,
                "Replacement rollback did not restore original chest and contents");
        restoredResult = Rollback.performBlockRollback(level, changes(level, REPLACED_POS, true), true);
        expect(restoredResult.blocksModified() == 2 && restoredResult.rowsMarked() == 2,
                "Replacement restore did not apply both changes");
        expect(level.getBlockState(REPLACED_POS).is(Blocks.STONE),
                "Replacement restore did not put back the stone");

        level.setBlockAndUpdate(DENIED_BREAK_POS, Blocks.STONE.defaultBlockState());
        BreakBlockEvent deniedBreak = new BreakBlockEvent(level, DENIED_BREAK_POS,
                level.getBlockState(DENIED_BREAK_POS), player);
        NeoForge.EVENT_BUS.post(deniedBreak);
        expect(deniedBreak.isCanceled(), "Break denial fixture did not cancel");
        BlockSnapshot deniedSnapshot = BlockSnapshot.create(level.dimension(), level, DENIED_PLACE_POS);
        BlockEvent.EntityPlaceEvent deniedPlace = new BlockEvent.EntityPlaceEvent(
                deniedSnapshot, Blocks.STONE.defaultBlockState(), player);
        NeoForge.EVENT_BUS.post(deniedPlace);
        expect(deniedPlace.isCanceled(), "Place denial fixture did not cancel");
        Consumer.flush();
        expect(changes(level, DENIED_BREAK_POS, false).isEmpty(), "Canceled break was logged");
        expect(changes(level, DENIED_PLACE_POS, false).isEmpty(), "Canceled placement was logged");

        level.setBlockAndUpdate(OUTAGE_POS, Blocks.STONE.defaultBlockState());
        BreakBlockEvent duringOutage = new BreakBlockEvent(level, OUTAGE_POS,
                level.getBlockState(OUTAGE_POS), player);
        NeoForge.EVENT_BUS.post(duringOutage);
        expect(!duringOutage.isCanceled(), "Outage fixture break was canceled");
        level.setBlockAndUpdate(OUTAGE_POS, Blocks.AIR.defaultBlockState());
        String databasePath = ConfigHandler.path;
        try {
            ConfigHandler.path = databasePath + "missing-parent/no-directory/";
            Consumer.flush();
            expect(Consumer.queueSize() > 0, "Failed database write discarded queued block change");
        }
        finally {
            ConfigHandler.path = databasePath;
        }
        Consumer.flush();
        expect(Consumer.queueSize() == 0 && changes(level, OUTAGE_POS, false).size() == 1,
                "Queued block change was not written after database recovery");
    }

    private static List<Lookup.BlockRow> changes(ServerLevel level, BlockPos pos, boolean restore) {
        return Lookup.performRollbackLookup(0, level.dimension().identifier().toString(), 0, 0,
                List.of(), 1, pos.getX(), pos.getZ(), 10, restore);
    }

    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
