package net.coreprotect.database;

import java.util.ArrayList;
import java.util.List;

import net.coreprotect.consumer.Queue;
import net.coreprotect.utility.BlockUtils;
import net.coreprotect.utility.BlockEntityData;
import net.coreprotect.utility.ErrorReporter;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Applies rollbacks and restores to the world.
 *
 * <p>Rows with action 1 (placed) are removed by a rollback, rows with action 0 (broken) are put back;
 * a restore does the exact opposite. Applied rows are marked as rolled back in the database.
 */
public final class Rollback {

    private Rollback() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * @param restore false for a rollback, true for a restore
     * @return the number of modified blocks and marked rows
     */
    public static Result performBlockRollback(ServerLevel level, List<Lookup.BlockRow> rows, boolean restore) {
        int modified = 0;
        final List<Long> marked = new ArrayList<>();
        final String dimension = level.dimension().identifier().toString();

        for (Lookup.BlockRow row : rows) {
            if (row.world() != null && !row.world().equals(dimension)) {
                continue;
            }

            final BlockState loggedState = BlockUtils.getBlockState(row.blockData());
            final BlockState target;
            if (row.action() == Queue.ACTION_PLACED) {
                // The block was placed: a rollback removes it, a restore places it again.
                target = restore ? loggedState : Blocks.AIR.defaultBlockState();
            }
            else if (row.action() == Queue.ACTION_REMOVED) {
                // The block was broken: a rollback puts it back, a restore removes it.
                target = restore ? Blocks.AIR.defaultBlockState() : loggedState;
            }
            else {
                continue;
            }

            if (target == null) {
                continue;
            }

            try {
                final BlockPos pos = new BlockPos(row.x(), row.y(), row.z());
                final BlockState expected = row.action() == Queue.ACTION_PLACED
                        ? (restore ? Blocks.AIR.defaultBlockState() : loggedState)
                        : (restore ? loggedState : Blocks.AIR.defaultBlockState());
                if (expected == null || !level.getBlockState(pos).equals(expected)) {
                    continue;
                }

                BlockEntity entity = null;
                if (target.hasBlockEntity()) {
                    final var tag = BlockEntityData.decode(row.blockEntity());
                    if (tag == null) {
                        continue;
                    }
                    entity = BlockEntity.loadStatic(pos, target, tag, level.registryAccess());
                    if (entity == null) {
                        continue;
                    }
                }
                if (!level.setBlock(pos, target, 3)) {
                    continue;
                }
                if (entity != null) {
                    level.setBlockEntity(entity);
                }
                modified++;
                marked.add(row.rowId());
            }
            catch (Exception e) {
                ErrorReporter.report(e);
            }
        }

        return new Result(modified, Lookup.markRolledBack(marked, restore));
    }

    /** Result of a rollback or restore. */
    public record Result(int blocksModified, int rowsMarked) {
    }
}
