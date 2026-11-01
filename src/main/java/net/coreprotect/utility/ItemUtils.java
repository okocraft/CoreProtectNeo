package net.coreprotect.utility;

import java.io.ByteArrayOutputStream;
import java.util.List;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.coreprotect.neoforge.NeoPlatform;

/**
 * Item helpers of the NeoForge port.
 *
 * <p>The original plugin serialized items with Bukkit's Java object streams. NeoForge cannot read
 * that format, so container/item metadata is stored as NBT bytes instead. All other columns and
 * tables are identical to the original database layout.
 */
public final class ItemUtils {

    private ItemUtils() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * Serializes an item stack (or an array/list of stacks) into the bytes stored in the database.
     */
    public static byte[] convertByteData(Object data) {
        if (data == null) {
            return null;
        }
        if (data instanceof byte[] bytes) {
            return bytes;
        }
        try {
            CompoundTag tag = new CompoundTag();
            if (data instanceof ItemStack stack) {
                tag.put("item", encodeStack(stack));
            }
            else if (data instanceof ItemStack[] stacks) {
                tag.put("items", encodeStacks(stacks));
            }
            else if (data instanceof List<?> list) {
                tag.put("items", encodeStacks(list.toArray(new ItemStack[0])));
            }
            else {
                return null;
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, output);
            return output.toByteArray();
        }
        catch (Exception e) {
            ErrorReporter.report(e);
            return null;
        }
    }

    /**
     * Deserializes bytes written by {@link #convertByteData(Object)}.
     */
    public static Object convertBytesData(byte[] data, RegistryAccess registryAccess, boolean single) {
        if (data == null || data.length == 0) {
            return single ? ItemStack.EMPTY : new ItemStack[0];
        }
        try {
            CompoundTag tag = NbtIo.readCompressed(new java.io.ByteArrayInputStream(data), NbtAccounter.unlimitedHeap());
            if (single && tag.contains("item")) {
                return decodeStack(tag.getCompoundOrEmpty("item"), registryAccess);
            }
            if (tag.contains("items")) {
                return decodeStacks(tag.getListOrEmpty("items"), registryAccess);
            }
        }
        catch (Exception e) {
            ErrorReporter.report(e);
        }
        return single ? ItemStack.EMPTY : new ItemStack[0];
    }

    private static CompoundTag encodeStack(ItemStack stack) {
        RegistryFriendlyByteBuf buffer = newBuffer();
        if (buffer == null) {
            return new CompoundTag();
        }
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, stack);
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.readBytes(bytes);
        CompoundTag tag = new CompoundTag();
        tag.putByteArray("stream", bytes);
        return tag;
    }

    private static ItemStack decodeStack(CompoundTag tag, RegistryAccess registryAccess) {
        byte[] bytes = tag.getByteArray("stream").orElseGet(() -> new byte[0]);
        if (bytes.length == 0) {
            return ItemStack.EMPTY;
        }
        RegistryFriendlyByteBuf buffer = newBuffer();
        if (buffer == null) {
            return ItemStack.EMPTY;
        }
        buffer.writeBytes(bytes);
        buffer.resetReaderIndex();
        return ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer);
    }

    /** Registry aware buffer used by the item stream codec. */
    private static RegistryFriendlyByteBuf newBuffer() {
        net.minecraft.server.MinecraftServer server = NeoPlatform.server();
        if (server == null) {
            return null;
        }
        return new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), server.registryAccess());
    }

    private static net.minecraft.nbt.ListTag encodeStacks(ItemStack[] stacks) {
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        for (ItemStack stack : stacks) {
            list.add(encodeStack(stack == null ? ItemStack.EMPTY : stack));
        }
        return list;
    }

    private static ItemStack[] decodeStacks(net.minecraft.nbt.ListTag list, RegistryAccess registryAccess) {
        ItemStack[] stacks = new ItemStack[list.size()];
        for (int i = 0; i < list.size(); i++) {
            stacks[i] = decodeStack(list.getCompoundOrEmpty(i), registryAccess);
        }
        return stacks;
    }

    /**
     * Container contents are collected once container listeners are ported.
     */
    public static ItemStack[] getContainerContents(Object type, Object container, Object location) {
        return null;
    }

    /** Serializes a live entity into the bytes stored in the {@code entity} table. */
    public static byte[] convertEntityByteData(net.minecraft.world.entity.Entity entity) {
        if (entity == null) {
            return null;
        }
        try {
            TagValueOutput outputTag = TagValueOutput.createWithContext(ProblemReporter.DISCARDING,
                    entity.level().registryAccess());
            outputTag.putString("type", net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                    .getKey(entity.getType()).toString());
            entity.saveWithoutId(outputTag);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            NbtIo.writeCompressed(outputTag.buildResult(), output);
            return output.toByteArray();
        }
        catch (Exception e) {
            ErrorReporter.report(e);
            return null;
        }
    }

    /** Copies a container snapshot so later mutations of the live inventory do not affect it. */
    public static ItemStack[] copyStacks(ItemStack[] stacks) {
        if (stacks == null) {
            return new ItemStack[0];
        }
        ItemStack[] copy = new ItemStack[stacks.length];
        for (int i = 0; i < stacks.length; i++) {
            copy[i] = stacks[i] == null ? ItemStack.EMPTY : stacks[i].copy();
        }
        return copy;
    }
}
