package net.coreprotect.utility;

import java.util.ArrayList;
import java.util.List;

import net.coreprotect.neoforge.NeoPlatform;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

/**
 * NeoForge replacement for the Bukkit-based chat helper of the original plugin.
 *
 * <p>Legacy color codes ('&amp;' and the section sign) are translated the same way as the original,
 * so all phrases keep their original look.
 */
public final class Chat {

    public static final String COMPONENT_TAG_OPEN = "<COMPONENT>";
    public static final String COMPONENT_TAG_CLOSE = "</COMPONENT>";
    public static final String COMPONENT_COMMAND = "COMMAND";
    public static final String COMPONENT_POPUP = "POPUP";
    public static final String COMPONENT_PIPE = "<PIPE/>";

    private Chat() {
        throw new IllegalStateException("Utility class");
    }

    public static String translateColorCodes(String text) {
        if (text == null) {
            return null;
        }
        return Color.translateAlternateColorCodes('&', text);
    }

    /**
     * Converts a legacy formatted string into a Minecraft text component.
     */
    public static Component toComponent(String legacy) {
        String text = translateColorCodes(stripComponentTags(legacy));
        MutableComponent result = Component.empty();
        StringBuilder buffer = new StringBuilder();
        ChatFormatting color = null;
        List<ChatFormatting> formats = new ArrayList<>();

        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (current == Color.COLOR_CHAR && i + 1 < text.length()) {
                ChatFormatting formatting = ChatFormatting.getByCode(text.charAt(i + 1));
                if (formatting != null) {
                    if (buffer.length() > 0) {
                        result.append(styled(buffer.toString(), color, formats));
                        buffer.setLength(0);
                    }
                    if (formatting == ChatFormatting.RESET) {
                        color = null;
                        formats.clear();
                    }
                    else if (formatting == ChatFormatting.OBFUSCATED || formatting == ChatFormatting.BOLD
                            || formatting == ChatFormatting.STRIKETHROUGH || formatting == ChatFormatting.UNDERLINE
                            || formatting == ChatFormatting.ITALIC) {
                        if (!formats.contains(formatting)) {
                            formats.add(formatting);
                        }
                    }
                    else {
                        color = formatting;
                        formats.clear();
                    }
                    i++;
                    continue;
                }
            }
            buffer.append(current);
        }

        if (buffer.length() > 0) {
            result.append(styled(buffer.toString(), color, formats));
        }
        return result;
    }

    private static MutableComponent styled(String text, ChatFormatting color, List<ChatFormatting> formats) {
        MutableComponent component = Component.literal(text);
        if (color != null) {
            component = component.withStyle(color);
        }
        for (ChatFormatting formatting : formats) {
            component = component.withStyle(formatting);
        }
        return component;
    }

    /**
     * Removes the {@code <COMPONENT>} markup used by the original plugin for clickable text.
     * Interactive components are implemented in a later stage of the port.
     */
    private static String stripComponentTags(String message) {
        if (message == null || message.indexOf('<') == -1) {
            return message;
        }
        String result = message;
        while (true) {
            int start = result.indexOf(COMPONENT_TAG_OPEN);
            if (start == -1) {
                break;
            }
            int end = result.indexOf(COMPONENT_TAG_CLOSE, start);
            if (end == -1) {
                break;
            }
            String inner = result.substring(start + COMPONENT_TAG_OPEN.length(), end);
            int textIndex = inner.indexOf(':');
            String text = textIndex == -1 ? inner : inner.substring(0, textIndex);
            result = result.substring(0, start) + text + result.substring(end + COMPONENT_TAG_CLOSE.length());
        }
        return result;
    }

    public static void sendMessage(CommandSourceStack sender, String message) {
        if (sender == null || message == null) {
            return;
        }
        sender.sendSuccess(() -> toComponent(message), false);
    }

    public static void sendMessage(ServerPlayer player, String message) {
        if (player == null || message == null) {
            return;
        }
        player.sendSystemMessage(toComponent(message));
    }

    public static void sendConsoleMessage(String string) {
        NeoPlatform.console(toComponent(string));
    }

    public static void console(String string) {
        String message = translateColorCodes(string);
        if (message.startsWith("-") || message.startsWith("[")) {
            NeoPlatform.LOGGER.info(Color.stripColor(message));
        }
        else {
            NeoPlatform.LOGGER.info("[CoreProtect] " + Color.stripColor(message));
        }
    }

    public static void sendGlobalMessage(CommandSourceStack user, String string) {
        if (string == null) {
            return;
        }
        String message = translateColorCodes(string);
        sendConsoleMessage(Color.DARK_AQUA + "[CoreProtect] " + Color.WHITE + Color.stripColor(message));

        for (ServerPlayer player : NeoPlatform.onlinePlayers()) {
            if (player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
                if (user != null && user.getEntity() == player) {
                    continue;
                }
                sendMessage(player, Color.DARK_AQUA + "CoreProtect " + Color.WHITE + "- " + message);
            }
        }

        if (user != null) {
            sendMessage(user, Color.DARK_AQUA + "CoreProtect " + Color.WHITE + "- " + message);
        }
    }
}
