package dev.mythicwardrobe.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class TextUtil {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private TextUtil() {
    }

    public static Component parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        Component comp;
        if (raw.indexOf('<') >= 0 && raw.indexOf('>') >= 0) {
            comp = MINI_MESSAGE.deserialize(raw);
        } else if (raw.indexOf('&') >= 0) {
            comp = LEGACY.deserialize(raw);
        } else {
            comp = Component.text(raw);
        }
        return comp.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static Component parse(String raw, Map<String, String> placeholders) {
        if (raw == null || raw.isEmpty()) return Component.empty();
        String result = raw;
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                result = result.replace("<" + entry.getKey() + ">", entry.getValue() == null ? "" : entry.getValue());
            }
        }
        return parse(result);
    }

    public static List<Component> parseLore(List<String> lines) {
        List<Component> out = new ArrayList<>();
        if (lines == null) return out;
        for (String line : lines) {
            out.add(parse(line));
        }
        return out;
    }
}
