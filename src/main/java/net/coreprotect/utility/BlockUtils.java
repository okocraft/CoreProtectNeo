package net.coreprotect.utility;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import net.coreprotect.config.ConfigHandler;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Block-state encoding helpers of the NeoForge port.
 *
 * <p>New rows store the complete registry name and properties. Existing rows that use numeric
 * property ids from {@code blockdata_map} remain readable.
 */
public final class BlockUtils {

    private BlockUtils() {
        throw new IllegalStateException("Utility class");
    }

    public static byte[] stringToByteData(String string, int type) {
        if (string == null) {
            return null;
        }

        return string.getBytes(StandardCharsets.UTF_8);
    }

    public static String byteDataToString(byte[] data, int type) {
        if (data == null || data.length == 0) {
            return null;
        }
        String decoded = new String(data, StandardCharsets.UTF_8);
        return decoded.indexOf(':') >= 0 ? decoded : decodeBlockData(decoded, type);
    }

    /**
     * Converts the stored property ids back into "minecraft:oak_log[axis=y]".
     */
    private static String decodeBlockData(String string, int type) {
        String blockKey = MaterialUtils.getBlockName(type);
        if (blockKey == null) {
            return string;
        }

        ArrayList<String> properties = new ArrayList<>();
        for (String id : string.split(",")) {
            if (id.isEmpty()) {
                continue;
            }
            int propertyId;
            try {
                propertyId = Integer.parseInt(id);
            }
            catch (NumberFormatException e) {
                properties.add(id);
                continue;
            }
            String property = MaterialUtils.getBlockdataName(propertyId);
            if (property != null) {
                properties.add(property);
            }
        }
        if (properties.isEmpty()) {
            return blockKey;
        }
        return blockKey + "[" + String.join(",", properties) + "]";
    }

    /** Block data string of a live block state (registry name plus properties). */
    public static String getBlockDataString(BlockState state) {
        return MaterialUtils.getBlockDataStringWithName(state);
    }

    public static boolean isAir(BlockState state) {
        return MaterialUtils.isAir(state);
    }

    /** Rebuilds a block state from the stored block data string. */
    public static BlockState getBlockState(String blockData) {
        if (blockData == null || blockData.isEmpty()) {
            return null;
        }
        String name = blockData;
        String properties = null;
        int bracket = blockData.indexOf('[');
        if (bracket > -1 && blockData.endsWith("]")) {
            name = blockData.substring(0, bracket);
            properties = blockData.substring(bracket + 1, blockData.length() - 1);
        }

        Block block = MaterialUtils.getType(MaterialUtils.getMaterialId(name, false));
        if (block == null) {
            net.minecraft.resources.Identifier key = net.minecraft.resources.Identifier.tryParse(name);
            if (key == null) {
                return null;
            }
            block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(key);
            if (block == null || (block == net.minecraft.world.level.block.Blocks.AIR && !"minecraft:air".equals(name))) {
                return null;
            }
        }

        BlockState state = block.defaultBlockState();
        if (properties == null) {
            return state;
        }
        for (String property : properties.split(",")) {
            int split = property.indexOf('=');
            if (split < 0) {
                continue;
            }
            state = applyProperty(state, property.substring(0, split), property.substring(split + 1));
        }
        return state;
    }

    @SuppressWarnings("unchecked")
    private static BlockState applyProperty(BlockState state, String name, String value) {
        Property<?> property = state.getBlock().getStateDefinition().getProperty(name);
        if (property == null) {
            return state;
        }
        java.util.Optional<? extends Comparable<?>> parsed = ((Property<?>) property).getValue(value);
        if (parsed.isEmpty()) {
            return state;
        }
        return setPropertyUnchecked(state, (Property) property, (Comparable) parsed.get());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState setPropertyUnchecked(BlockState state, Property property, Comparable value) {
        return state.setValue(property, value);
    }

    /** Used by the container helpers of later stages. */
    public static int getSignData(boolean frontGlowing, boolean backGlowing) {
        int data = 0;
        if (frontGlowing) {
            data |= 1;
        }
        if (backGlowing) {
            data |= 2;
        }
        return data;
    }

    public static boolean isSideGlowing(boolean isFront, int data) {
        return isFront ? (data & 1) == 1 : (data & 2) == 2;
    }

    public static int getMaterialId(String blockName) {
        return MaterialUtils.getMaterialId(blockName, true);
    }

    public static String getMaterialName(int id) {
        return ConfigHandler.materialsReversed.get(id);
    }
}
