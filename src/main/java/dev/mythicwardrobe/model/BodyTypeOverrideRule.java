package dev.mythicwardrobe.model;

import java.util.Locale;

/**
 * Maps a base armor item (by MythicMobs ID or Equippable.Model key) to
 * body-type-specific 3D models/items for {@code slim} (3px arms) and {@code wide} (4px arms).
 */
public record BodyTypeOverrideRule(
        String id,
        String source,
        String slimTarget,
        String wideTarget
) {
    public boolean matches(String mythicId, String equippableModelKey) {
        if (source == null || source.isBlank()) return false;
        String srcNorm = source.trim().toLowerCase(Locale.ROOT);
        if (mythicId != null && !mythicId.isBlank() && srcNorm.equals(mythicId.trim().toLowerCase(Locale.ROOT))) {
            return true;
        }
        if (equippableModelKey != null && !equippableModelKey.isBlank()
                && srcNorm.equals(equippableModelKey.trim().toLowerCase(Locale.ROOT))) {
            return true;
        }
        return false;
    }

    public String targetFor(boolean slim) {
        String raw = slim ? slimTarget : wideTarget;
        return (raw == null || raw.isBlank()) ? null : raw.trim();
    }

    public static boolean isModelKey(String target) {
        return target != null && target.contains(":");
    }
}
