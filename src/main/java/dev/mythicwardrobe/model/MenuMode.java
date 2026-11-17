package dev.mythicwardrobe.model;

import java.util.Locale;

/**
 * Configurable GUI display modes for MythicWardrobe:
 * - COMPACT : Separate small 1-row menus per armor slot (HEAD, CHEST, LEGS, FEET) + compact hub
 * - TABBED  : Single menu with switchable tabs for HEAD / CHEST / LEGS / FEET
 * - UNIFIED : Single large menu displaying all 4 armor sections simultaneously
 */
public enum MenuMode {
    COMPACT("Компактное (1 строчка на слот)"),
    TABBED("С вкладками (переключение разделов)"),
    UNIFIED("Общее (всё в одном меню)");

    private final String russianLabel;

    MenuMode(String russianLabel) {
        this.russianLabel = russianLabel;
    }

    public String getRussianLabel() {
        return russianLabel;
    }

    public MenuMode next() {
        return switch (this) {
            case COMPACT -> TABBED;
            case TABBED -> UNIFIED;
            case UNIFIED -> COMPACT;
        };
    }

    public static MenuMode parseOrNull(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "compact", "single", "small", "row", "1row", "separate", "split" -> COMPACT;
            case "tabbed", "tabs", "switch", "pages" -> TABBED;
            case "unified", "all", "big", "large", "full", "combined" -> UNIFIED;
            default -> null;
        };
    }

    public static MenuMode fromString(String raw, MenuMode fallback) {
        MenuMode parsed = parseOrNull(raw);
        return parsed != null ? parsed : fallback;
    }
}
