package dev.mythicwardrobe.model;

import java.util.List;
import java.util.Set;

/**
 * Config-driven skin layer definition loaded from plugins/MythicWardrobe/layers.yml
 * (and merged with SKIN_LAYER slots from slots.yml).
 */
public record SkinLayerDef(
        String id,
        int priority,
        String folder,
        boolean replacesBaseSkin,
        Set<String> clearOuterRegions,
        List<String> aliases
) {
}
