package net.danh.sincebooster.utils;

import net.danh.sincebooster.SinceBooster;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.jetbrains.annotations.NotNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles legacy and MiniMessage color code conversions efficiently.
 */
public class ColorUtils {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([a-fA-F0-9]{6})");

    public static @NotNull Component parse(@NotNull String input) {
        if (input.isEmpty()) return Component.empty();
        return MINI_MESSAGE.deserialize(convertLegacyToMiniMessage(input));
    }

    public static @NotNull Component parseWithPrefix(@NotNull String input) {
        String prefix = SinceBooster.getPlugin().getMessagesFile().getString("prefix", "");
        return parse(prefix + input);
    }

    public static String convertLegacyToMiniMessage(String text) {
        if (text == null || text.isEmpty()) return "";
        text = text.replace("§", "&");
        Matcher matcher = HEX_PATTERN.matcher(text);
        StringBuilder buffer = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, "<#" + matcher.group(1) + ">");
        }
        matcher.appendTail(buffer);
        text = buffer.toString();
        if (!text.contains("&")) return text;
        return text.replace("&0", "<black>").replace("&1", "<dark_blue>").replace("&2", "<dark_green>")
                .replace("&3", "<dark_aqua>").replace("&4", "<dark_red>").replace("&5", "<dark_purple>")
                .replace("&6", "<gold>").replace("&7", "<gray>").replace("&8", "<dark_gray>")
                .replace("&9", "<blue>").replace("&a", "<green>").replace("&b", "<aqua>")
                .replace("&c", "<red>").replace("&d", "<light_purple>").replace("&e", "<yellow>")
                .replace("&f", "<white>").replace("&k", "<obfuscated>").replace("&l", "<bold>")
                .replace("&m", "<strikethrough>").replace("&n", "<underlined>").replace("&o", "<italic>")
                .replace("&r", "<reset>");
    }
}