package dev.mythicwardrobe.model;

import java.util.*;

public record ItemWardrobeMeta(
        String slotId,
        String texturePath,
        DisplayMountConfig displayOverride,
        String onEquipSkill,
        String onUnequipSkill,
        String mythicId,
        Set<String> blockedTargets,
        BlockBehaviorMode blockMode,
        CosmeticVariantConfig.CosmeticSkinDef inlineCosmeticSkin
) {
    public ItemWardrobeMeta {
        blockedTargets = normalizeBlockedTargets(blockedTargets);
    }

    /**
     * Backwards-compatible 8-arg constructor without inline multimodal variants.
     */
    public ItemWardrobeMeta(
            String slotId,
            String texturePath,
            DisplayMountConfig displayOverride,
            String onEquipSkill,
            String onUnequipSkill,
            String mythicId,
            Set<String> blockedTargets,
            BlockBehaviorMode blockMode
    ) {
        this(slotId, texturePath, displayOverride, onEquipSkill, onUnequipSkill, mythicId, blockedTargets, blockMode, null);
    }

    /**
     * Backwards-compatible 6-arg constructor with no slot/layer blocks.
     */
    public ItemWardrobeMeta(
            String slotId,
            String texturePath,
            DisplayMountConfig displayOverride,
            String onEquipSkill,
            String onUnequipSkill,
            String mythicId
    ) {
        this(slotId, texturePath, displayOverride, onEquipSkill, onUnequipSkill, mythicId, Set.of(), null, null);
    }

    public boolean hasInlineCosmeticSkin() {
        return inlineCosmeticSkin != null && !inlineCosmeticSkin.variants().isEmpty();
    }

    public boolean hasSlot() {
        return slotId != null && !slotId.isBlank();
    }

    public boolean hasTexture() {
        return texturePath != null && !texturePath.isBlank();
    }

    public boolean hasBlocks() {
        return blockedTargets != null && !blockedTargets.isEmpty();
    }

    /**
     * First configured slot ID (supports comma-separated multi-slot bindings like "chest_real,backpack_3d").
     */
    public String primarySlotId() {
        if (!hasSlot()) return "";
        int comma = slotId.indexOf(',');
        String first = comma >= 0 ? slotId.substring(0, comma) : slotId;
        return first.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Checks whether this item explicitly lists targetSlotId (supports single ID or comma-separated list).
     */
    public boolean matchesSlot(String targetSlotId) {
        if (!hasSlot() || targetSlotId == null || targetSlotId.isBlank()) return false;
        String target = targetSlotId.trim().toLowerCase(Locale.ROOT);
        for (String part : slotId.split(",")) {
            if (part.trim().toLowerCase(Locale.ROOT).equals(target)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Parses a comma/semicolon/whitespace separated block target list into a clean immutable Set.
     */
    public static Set<String> parseBlockedTargetsCsv(String raw) {
        if (raw == null || raw.isBlank()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (String token : raw.split("[,;\\s]+")) {
            String clean = token.trim().toLowerCase(Locale.ROOT);
            if (!clean.isEmpty()) {
                out.add(clean);
            }
        }
        return out.isEmpty() ? Set.of() : Collections.unmodifiableSet(out);
    }

    public static Set<String> normalizeBlockedTargets(Collection<String> raw) {
        if (raw == null || raw.isEmpty()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (String entry : raw) {
            if (entry == null || entry.isBlank()) continue;
            for (String token : entry.split("[,;]+")) {
                String clean = token.trim().toLowerCase(Locale.ROOT);
                if (!clean.isEmpty()) {
                    out.add(clean);
                }
            }
        }
        return out.isEmpty() ? Set.of() : Collections.unmodifiableSet(out);
    }
}
