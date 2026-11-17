package dev.mythicwardrobe.model;

import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory state of one online player's wardrobe.
 * Note: Real armor slots (ARMOR_REAL) are NEVER duplicated here;
 * their single source of truth is Bukkit PlayerInventory.
 */
public final class PlayerWardrobeState {

    private final UUID playerId;
    private final Map<String, ItemStack> slotItems = new ConcurrentHashMap<>();
    private final Map<String, String> skillLayers = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> skillLayerBlocks = new ConcurrentHashMap<>();
    private volatile SignedSkinData baseSkin;
    private volatile SkinModelVariant bodyTypeOverride;
    private volatile MenuMode menuModeOverride;
    private volatile String lastAppliedSkinHash;
    private volatile boolean dirtySkin;

    public PlayerWardrobeState(UUID playerId) {
        this.playerId = playerId;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public Map<String, ItemStack> getSlotItems() {
        return Collections.unmodifiableMap(slotItems);
    }

    public ItemStack getSlotItem(String slotId) {
        ItemStack stack = slotItems.get(slotId.toLowerCase());
        return stack == null ? null : stack.clone();
    }

    public ItemStack peekSlotItem(String slotId) {
        return slotItems.get(slotId.toLowerCase());
    }

    public void setSlotItem(String slotId, ItemStack item) {
        String key = slotId.toLowerCase();
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
            slotItems.remove(key);
        } else {
            slotItems.put(key, item.clone());
        }
    }

    public ItemStack removeSlotItem(String slotId) {
        return slotItems.remove(slotId.toLowerCase());
    }

    public void clearAllSlotItems() {
        slotItems.clear();
    }

    public Map<String, String> getSkillLayers() {
        return Collections.unmodifiableMap(skillLayers);
    }

    public String getSkillLayer(String layerType) {
        return skillLayers.get(layerType.toLowerCase());
    }

    public void setSkillLayer(String layerType, String texturePath) {
        String key = layerType.toLowerCase();
        if (texturePath == null || texturePath.isBlank()) {
            skillLayers.remove(key);
            skillLayerBlocks.remove(key);
        } else {
            skillLayers.put(key, texturePath.trim());
        }
    }

    public void removeSkillLayer(String layerType) {
        String key = layerType.toLowerCase();
        skillLayers.remove(key);
        skillLayerBlocks.remove(key);
    }

    public void clearSkillLayers() {
        skillLayers.clear();
        skillLayerBlocks.clear();
    }

    public Map<String, Set<String>> getSkillLayerBlocks() {
        return Collections.unmodifiableMap(skillLayerBlocks);
    }

    public Set<String> getSkillLayerBlocks(String layerType) {
        return skillLayerBlocks.getOrDefault(layerType.toLowerCase(), Set.of());
    }

    public void setSkillLayerBlocks(String layerType, Set<String> blockedTargets) {
        String key = layerType.toLowerCase();
        if (blockedTargets == null || blockedTargets.isEmpty()) {
            skillLayerBlocks.remove(key);
        } else {
            skillLayerBlocks.put(key, ItemWardrobeMeta.normalizeBlockedTargets(blockedTargets));
        }
    }

    public SignedSkinData getBaseSkin() {
        return baseSkin;
    }

    public void setBaseSkin(SignedSkinData baseSkin) {
        this.baseSkin = baseSkin;
    }

    public SkinModelVariant getBodyTypeOverride() {
        return bodyTypeOverride;
    }

    public void setBodyTypeOverride(SkinModelVariant bodyTypeOverride) {
        this.bodyTypeOverride = bodyTypeOverride;
    }

    public MenuMode getMenuModeOverride() {
        return menuModeOverride;
    }

    public void setMenuModeOverride(MenuMode menuModeOverride) {
        this.menuModeOverride = menuModeOverride;
    }

    public String getLastAppliedSkinHash() {
        return lastAppliedSkinHash;
    }

    public void setLastAppliedSkinHash(String lastAppliedSkinHash) {
        this.lastAppliedSkinHash = lastAppliedSkinHash;
    }

    public boolean isDirtySkin() {
        return dirtySkin;
    }

    public void setDirtySkin(boolean dirtySkin) {
        this.dirtySkin = dirtySkin;
    }
}
