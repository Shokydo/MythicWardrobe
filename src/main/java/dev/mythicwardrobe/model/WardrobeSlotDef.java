package dev.mythicwardrobe.model;

import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;

import java.util.List;
import java.util.Set;

public record WardrobeSlotDef(
        String id,
        ArmorSection section,
        SlotKind kind,
        EquipmentSlot equipmentSlot,
        int guiSlot,
        int priority,
        String folder,
        String displayName,
        Material placeholderMaterial,
        List<String> lore,
        Set<String> clearOuterRegions,
        DisplayMountConfig displayMount,
        Integer placeholderCustomModelData,
        String placeholderItemModel
) {
    public WardrobeSlotDef(
            String id,
            ArmorSection section,
            SlotKind kind,
            EquipmentSlot equipmentSlot,
            int guiSlot,
            int priority,
            String folder,
            String displayName,
            Material placeholderMaterial,
            List<String> lore,
            Set<String> clearOuterRegions,
            DisplayMountConfig displayMount
    ) {
        this(id, section, kind, equipmentSlot, guiSlot, priority, folder, displayName,
                placeholderMaterial, lore, clearOuterRegions, displayMount, null, null);
    }
    public boolean isSkinLayer() {
        return kind == SlotKind.SKIN_LAYER;
    }

    public boolean isRealArmor() {
        return kind == SlotKind.ARMOR_REAL;
    }

    public boolean isCosmeticArmor() {
        return kind == SlotKind.ARMOR_COSMETIC;
    }

    public boolean isDisplay3D() {
        return kind == SlotKind.DISPLAY_3D || kind == SlotKind.ARMOR_OVERLAY;
    }

    public boolean isArmorOverlay() {
        return kind == SlotKind.ARMOR_OVERLAY;
    }
}
