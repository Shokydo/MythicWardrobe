package dev.mythicwardrobe.model;

import java.util.Locale;

/**
 * Defines how MythicWardrobe behaves when equipping an item that blocks other wardrobe slots/layers
 * (for example, a full-body Hazmat Suit in the `jacket` slot blocking `pants`, `undershirt`, `gloves`, etc.):
 * <ul>
 *     <li>{@link #UNEQUIP} — Automatically unequips conflicting items from blocked slots and returns them to the player's inventory.</li>
 *     <li>{@link #PREVENT} — Refuses to equip the blocking suit until the player manually removes the conflicting items.</li>
 *     <li>{@link #HIDE} — Leaves conflicting items in their slots, locks those slots against edits, and visually suppresses/hides them until the suit is removed.</li>
 * </ul>
 */
public enum BlockBehaviorMode {
    UNEQUIP,
    PREVENT,
    HIDE;

    public static BlockBehaviorMode parseOrNull(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String norm = raw.trim().toUpperCase(Locale.ROOT);
        return switch (norm) {
            case "UNEQUIP", "AUTO_UNEQUIP", "REMOVE", "RETURN", "EJECT" -> UNEQUIP;
            case "PREVENT", "DENY", "BLOCK", "REQUIRE_EMPTY", "STRICT" -> PREVENT;
            case "HIDE", "SUPPRESS", "OVERRIDE", "MASK" -> HIDE;
            default -> null;
        };
    }

    public static BlockBehaviorMode parseOrDefault(String raw, BlockBehaviorMode fallback) {
        BlockBehaviorMode parsed = parseOrNull(raw);
        return parsed != null ? parsed : (fallback != null ? fallback : UNEQUIP);
    }
}
