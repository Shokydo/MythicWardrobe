package dev.mythicwardrobe.model;

import org.bukkit.inventory.EquipmentSlot;

public record ComboOverrideRule(
        String id,
        EquipmentSlot equipmentSlot,
        String primaryMythicItem,
        String secondarySlotId,
        String secondaryMythicItem,
        String resultMythicItem,
        boolean hideSecondaryDisplay
) {
    public boolean matches(String actualPrimaryId, String actualSecondaryId) {
        if (actualPrimaryId == null || actualSecondaryId == null) return false;
        boolean primaryOk = "*".equals(primaryMythicItem) || primaryMythicItem.equalsIgnoreCase(actualPrimaryId);
        boolean secondaryOk = "*".equals(secondaryMythicItem) || secondaryMythicItem.equalsIgnoreCase(actualSecondaryId);
        return primaryOk && secondaryOk;
    }
}
