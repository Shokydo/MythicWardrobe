package dev.mythicwardrobe.model;

import org.bukkit.inventory.EquipmentSlot;

public enum ArmorSection {
    HEAD(EquipmentSlot.HEAD, 39, 5, "Шлем и Голова"),
    CHEST(EquipmentSlot.CHEST, 38, 6, "Нагрудник и Торс"),
    LEGS(EquipmentSlot.LEGS, 37, 7, "Поножи и Ноги"),
    FEET(EquipmentSlot.FEET, 36, 8, "Ботинки и Пояс");

    private final EquipmentSlot equipmentSlot;
    private final int bukkitArmorSlot;
    private final int playerContainerSlot;
    private final String russianTitle;

    ArmorSection(EquipmentSlot equipmentSlot, int bukkitArmorSlot, int playerContainerSlot, String russianTitle) {
        this.equipmentSlot = equipmentSlot;
        this.bukkitArmorSlot = bukkitArmorSlot;
        this.playerContainerSlot = playerContainerSlot;
        this.russianTitle = russianTitle;
    }

    public String getRussianTitle() {
        return russianTitle;
    }

    public EquipmentSlot getEquipmentSlot() {
        return equipmentSlot;
    }

    public int getBukkitArmorSlot() {
        return bukkitArmorSlot;
    }

    public int getPlayerContainerSlot() {
        return playerContainerSlot;
    }

    public static ArmorSection fromEquipmentSlot(EquipmentSlot slot) {
        if (slot == null) return null;
        return switch (slot) {
            case HEAD -> HEAD;
            case CHEST -> CHEST;
            case LEGS -> LEGS;
            case FEET -> FEET;
            default -> null;
        };
    }

    public static ArmorSection fromBukkitArmorSlot(int slot) {
        for (ArmorSection s : values()) {
            if (s.bukkitArmorSlot == slot) return s;
        }
        return null;
    }

    public static ArmorSection fromName(String name) {
        if (name == null) return null;
        return switch (name.trim().toUpperCase()) {
            case "HEAD", "HELMET", "ШЛЕМ", "ГОЛОВА" -> HEAD;
            case "CHEST", "CHESTPLATE", "TORSO", "НАГРУДНИК", "ТОРС" -> CHEST;
            case "LEGS", "LEGGINGS", "PANTS", "ПОНОЖИ", "НОГИ" -> LEGS;
            case "FEET", "BOOTS", "BELT", "БОТИНКИ", "ПОЯС", "ОБУВЬ" -> FEET;
            default -> null;
        };
    }
}
