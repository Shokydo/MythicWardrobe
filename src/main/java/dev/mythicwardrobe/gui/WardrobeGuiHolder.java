package dev.mythicwardrobe.gui;

import dev.mythicwardrobe.model.ArmorSection;
import dev.mythicwardrobe.model.MenuMode;
import dev.mythicwardrobe.model.MenuVisualDef.MenuAction;
import dev.mythicwardrobe.model.WardrobeSlotDef;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class WardrobeGuiHolder implements InventoryHolder {

    private final UUID ownerId;
    private final MenuMode menuMode;
    private final ArmorSection section; // null for compact_hub and unified
    private final boolean hubView;
    private final boolean inventoryView;
    private final Map<Integer, WardrobeSlotDef> interactiveSlots = new LinkedHashMap<>();
    private final Map<Integer, EquipmentSlot> liveEquipmentSlots = new LinkedHashMap<>();
    private final Map<Integer, MenuAction> buttonActions = new LinkedHashMap<>();
    private final Map<Integer, String> buttonCommands = new LinkedHashMap<>();
    private Inventory inventory;

    public WardrobeGuiHolder(UUID ownerId, MenuMode menuMode, ArmorSection section, boolean hubView) {
        this(ownerId, menuMode, section, hubView, false);
    }

    public WardrobeGuiHolder(UUID ownerId, MenuMode menuMode, ArmorSection section, boolean hubView, boolean inventoryView) {
        this.ownerId = ownerId;
        this.menuMode = menuMode;
        this.section = section;
        this.hubView = hubView;
        this.inventoryView = inventoryView;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public MenuMode getMenuMode() {
        return menuMode;
    }

    public ArmorSection getSection() {
        return section;
    }

    public boolean isHubView() {
        return hubView;
    }

    public boolean isInventoryView() {
        return inventoryView;
    }

    public void registerInteractiveSlot(int rawSlot, WardrobeSlotDef slotDef) {
        interactiveSlots.put(rawSlot, slotDef);
    }

    public void registerLiveEquipmentSlot(int rawSlot, EquipmentSlot eqSlot) {
        liveEquipmentSlots.put(rawSlot, eqSlot);
    }

    public EquipmentSlot getLiveEquipmentSlotAt(int rawSlot) {
        return liveEquipmentSlots.get(rawSlot);
    }

    public void registerButtonAction(int rawSlot, MenuAction action) {
        registerButtonAction(rawSlot, action, null);
    }

    public void registerButtonAction(int rawSlot, MenuAction action, String command) {
        buttonActions.put(rawSlot, action);
        if (command != null && !command.isBlank()) {
            buttonCommands.put(rawSlot, command.trim());
        }
    }

    public WardrobeSlotDef getSlotAt(int rawSlot) {
        return interactiveSlots.get(rawSlot);
    }

    public MenuAction getButtonActionAt(int rawSlot) {
        return buttonActions.get(rawSlot);
    }

    public String getButtonCommandAt(int rawSlot) {
        return buttonCommands.get(rawSlot);
    }

    public Map<Integer, WardrobeSlotDef> getInteractiveSlots() {
        return Collections.unmodifiableMap(interactiveSlots);
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
