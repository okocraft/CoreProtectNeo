package net.coreprotect.utility;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Encodes NeoForge block entity snapshots in the existing block.meta column. */
public final class BlockEntityData {

    private static final String FORMAT = "coreprotectneo:block_entity:1";

    private BlockEntityData() {
    }

    public static byte[] capture(BlockEntity entity, HolderLookup.Provider registries) {
        return entity == null ? null : encode(entity.saveWithFullMetadata(registries));
    }

    public static byte[] encode(CompoundTag snapshot) {
        if (snapshot == null) {
            return null;
        }
        CompoundTag wrapper = new CompoundTag();
        wrapper.putString("format", FORMAT);
        wrapper.put("block_entity", snapshot);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            NbtIo.writeCompressed(wrapper, output);
            return output.toByteArray();
        }
        catch (IOException e) {
            throw new IllegalStateException("Could not encode block entity", e);
        }
    }

    /** A null value means no snapshot. Unknown metadata is rejected before changing the world. */
    public static CompoundTag decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            CompoundTag wrapper = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.create(2_097_152));
            if (!FORMAT.equals(wrapper.getStringOr("format", ""))) {
                throw new IllegalArgumentException("Unsupported block entity metadata format");
            }
            return wrapper.getCompound("block_entity")
                    .orElseThrow(() -> new IllegalArgumentException("Missing block entity snapshot"));
        }
        catch (IOException e) {
            throw new IllegalArgumentException("Could not read block entity metadata", e);
        }
    }
}
