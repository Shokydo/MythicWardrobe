package dev.mythicwardrobe.manager;

import dev.mythicwardrobe.config.WardrobeConfig;
import dev.mythicwardrobe.hook.MythicMobsHook;
import dev.mythicwardrobe.model.ArmorSection;
import dev.mythicwardrobe.model.BlockBehaviorMode;
import dev.mythicwardrobe.model.CosmeticVariantConfig;
import dev.mythicwardrobe.model.ItemWardrobeMeta;
import dev.mythicwardrobe.model.SlotKind;
import dev.mythicwardrobe.model.WardrobeSlotDef;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Equippable;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves wardrobe metadata for any ItemStack (PDC tags -> MythicItem YAML Wardrobe section -> items.yml -> Paper Equippable/vanilla armor).
 */
public final class WardrobeItemResolver {

    public final NamespacedKey keySlot;
    public final NamespacedKey keyTexture;
    public final NamespacedKey keyEquipSkill;
    public final NamespacedKey keyUnequipSkill;
    public final NamespacedKey keyBlockedSlots;
    public final NamespacedKey keyBlockMode;

    private final WardrobeConfig config;
    private final MythicMobsHook mythicHook;

    public WardrobeItemResolver(JavaPlugin plugin, WardrobeConfig config, MythicMobsHook mythicHook) {
        this.config = config;
        this.mythicHook = mythicHook;
        this.keySlot = new NamespacedKey(plugin, "slot");
        this.keyTexture = new NamespacedKey(plugin, "texture");
        this.keyEquipSkill = new NamespacedKey(plugin, "equip_skill");
        this.keyUnequipSkill = new NamespacedKey(plugin, "unequip_skill");
        this.keyBlockedSlots = new NamespacedKey(plugin, "blocked_slots");
        this.keyBlockMode = new NamespacedKey(plugin, "block_mode");
    }

    public ItemWardrobeMeta resolve(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return new ItemWardrobeMeta("", "", null, "", "", null);
        }

        String mythicId = mythicHook != null ? mythicHook.getMythicItemId(item) : null;

        // 1. Check explicit PDC tags written by /wardrobe tag, /wardrobe block, or custom plugins
        Set<String> pdcBlocks = Set.of();
        BlockBehaviorMode pdcBlockMode = null;
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            String pdcSlot = pdc.get(keySlot, PersistentDataType.STRING);
            String pdcTex = pdc.get(keyTexture, PersistentDataType.STRING);
            String pdcEquip = pdc.get(keyEquipSkill, PersistentDataType.STRING);
            String pdcUnequip = pdc.get(keyUnequipSkill, PersistentDataType.STRING);
            String pdcBlocksRaw = pdc.get(keyBlockedSlots, PersistentDataType.STRING);
            pdcBlocks = ItemWardrobeMeta.parseBlockedTargetsCsv(pdcBlocksRaw);
            pdcBlockMode = BlockBehaviorMode.parseOrNull(pdc.get(keyBlockMode, PersistentDataType.STRING));

            if ((pdcSlot != null && !pdcSlot.isBlank()) || (pdcTex != null && !pdcTex.isBlank())) {
                return new ItemWardrobeMeta(
                        pdcSlot == null ? "" : pdcSlot.trim().toLowerCase(Locale.ROOT),
                        pdcTex == null ? "" : pdcTex.trim(),
                        null,
                        pdcEquip == null ? "" : pdcEquip.trim(),
                        pdcUnequip == null ? "" : pdcUnequip.trim(),
                        mythicId,
                        pdcBlocks,
                        pdcBlockMode
                );
            }
        }

        // 2. Check MythicMobs item YAML `Wardrobe:` block
        ItemWardrobeMeta resolved = null;
        if (mythicId != null && mythicHook != null) {
            Optional<ItemWardrobeMeta> fromMythic = mythicHook.readMythicItemWardrobeMeta(mythicId);
            if (fromMythic.isPresent()) {
                resolved = fromMythic.get();
            } else {
                // 3. Check plugins/MythicWardrobe/items.yml
                resolved = config.getConfiguredItem(mythicId);
            }
        }

        if (resolved != null) {
            if (!pdcBlocks.isEmpty() || pdcBlockMode != null) {
                return new ItemWardrobeMeta(
                        resolved.slotId(),
                        resolved.texturePath(),
                        resolved.displayOverride(),
                        resolved.onEquipSkill(),
                        resolved.onUnequipSkill(),
                        resolved.mythicId(),
                        !pdcBlocks.isEmpty() ? pdcBlocks : resolved.blockedTargets(),
                        pdcBlockMode != null ? pdcBlockMode : resolved.blockMode(),
                        resolved.inlineCosmeticSkin()
                );
            }
            return resolved;
        }

        if (!pdcBlocks.isEmpty() || pdcBlockMode != null) {
            return new ItemWardrobeMeta("", "", null, "", "", mythicId, pdcBlocks, pdcBlockMode);
        }

        return new ItemWardrobeMeta("", "", null, "", "", mythicId);
    }

    public boolean canPlaceInSlot(ItemStack item, WardrobeSlotDef slot) {
        if (item == null || item.getType().isAir()) return true;
        if (slot == null) return false;

        ItemWardrobeMeta meta = resolve(item);

        // 1. 2D Skin-layer slots ONLY accept items explicitly bound to that skin slot
        if (slot.isSkinLayer()) {
            return meta.hasSlot() && meta.matchesSlot(slot.id());
        }

        // 2. Explicit slot match (supports single slot OR comma-separated multi-slot list!)
        if (meta.hasSlot()) {
            if (meta.matchesSlot(slot.id())) {
                return true;
            }
            // Multimodal cosmetics can also declare additional allowed ARMOR_COSMETIC slots in cosmetic-variants.yml
            CosmeticVariantConfig variantCfg = config.getCosmeticVariantConfig();
            if (variantCfg != null && variantCfg.isMultimodalCosmetic(meta.mythicId(), meta.inlineCosmeticSkin())
                    && variantCfg.canPlaceInSlot(meta.mythicId(), meta.inlineCosmeticSkin(), slot)) {
                return true;
            }
            // Strictly keep explicitly bound items separated to their declared slot(s):
            // e.g. a chest_cosmetic item cannot slip into chest_real, and a chest_real item cannot slip into backpack_3d!
            return false;
        }

        // 2b. Multimodal cosmetic skin (cosmetic-variants.yml without explicit Wardrobe.Slot):
        //     Allows ANY item material (e.g. WHITE_DYE, PAPER, BRUSH) to be placed into its
        //     allowed ARMOR_COSMETIC slots while preventing paint items from entering ARMOR_REAL!
        CosmeticVariantConfig variantCfg = config.getCosmeticVariantConfig();
        if (variantCfg != null && variantCfg.isMultimodalCosmetic(meta.mythicId(), meta.inlineCosmeticSkin())) {
            if (variantCfg.canPlaceInSlot(meta.mythicId(), meta.inlineCosmeticSkin(), slot)) {
                return true;
            }
            if (slot.kind() == SlotKind.ARMOR_REAL) {
                return false;
            }
        }

        // 3. Unbound vanilla / MythicArmors items (without explicit Wardrobe.Slot):
        //    Accepted into ARMOR_REAL / ARMOR_COSMETIC / DISPLAY_3D / ARMOR_OVERLAY slots matching EquipmentSlot.
        EquipmentSlot targetEq = slot.equipmentSlot() != null
                ? slot.equipmentSlot()
                : (slot.section() != null ? slot.section().getEquipmentSlot() : null);
        if (targetEq == null) return false;

        EquipmentSlot detected = detectEquipmentSlot(item);
        return detected == targetEq;
    }

    /**
     * Finds the best cosmetic slot for a multimodal cosmetic item (optionally constrained to a section).
     * Used by Smart Cursor Equip & right-click quick-equip for paint/camo items like WhiteVestPaint.
     */
    public WardrobeSlotDef findMultimodalTargetSlot(ItemStack item, ArmorSection sectionFilter) {
        if (item == null || item.getType().isAir()) return null;
        CosmeticVariantConfig variantCfg = config.getCosmeticVariantConfig();
        if (variantCfg == null || !variantCfg.isEnabled()) return null;
        ItemWardrobeMeta meta = resolve(item);
        if (!variantCfg.isMultimodalCosmetic(meta.mythicId(), meta.inlineCosmeticSkin())) {
            return null;
        }
        Collection<WardrobeSlotDef> candidates = sectionFilter != null
                ? config.getSlotsForSection(sectionFilter)
                : config.getSlotsById().values();
        for (WardrobeSlotDef slot : candidates) {
            if (slot.kind() == SlotKind.ARMOR_COSMETIC
                    && variantCfg.canPlaceInSlot(meta.mythicId(), meta.inlineCosmeticSkin(), slot)) {
                return slot;
            }
        }
        return null;
    }

    private boolean isBoundToSkinLayerSlot(ItemWardrobeMeta meta) {
        if (!meta.hasSlot()) return false;
        for (String part : meta.slotId().split(",")) {
            WardrobeSlotDef bound = config.getSlot(part.trim());
            if (bound != null && bound.isSkinLayer()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true when right-clicking this item in hand (main or off-hand) would equip armor
     * (vanilla armor, Elytra, or any MythicArmors / 1.21+ Equippable 3D armor/accessory piece).
     * Plain placeable blocks (e.g. untagged CARVED_PUMPKIN or PLAYER_HEAD without Equippable component)
     * return false so normal block placement is never broken.
     */
    public boolean isHandRightClickArmor(ItemStack item, ItemWardrobeMeta meta) {
        if (item == null || item.getType().isAir()) return false;

        // 1. Explicit 1.21.4+ Equippable component on armor slots (covers ALL MythicArmors 3D armor,
        //    masks, backpacks, belts, hair, AND vanilla armor items that carry Equippable data)
        try {
            Equippable eq = item.getData(DataComponentTypes.EQUIPPABLE);
            if (eq != null && eq.slot() != null) {
                EquipmentSlot s = eq.slot();
                if (s == EquipmentSlot.HEAD || s == EquipmentSlot.CHEST
                        || s == EquipmentSlot.LEGS || s == EquipmentSlot.FEET) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }

        // 2. Vanilla armor materials & Elytra (excluding plain placeable heads/skulls/pumpkins)
        Material mat = item.getType();
        if (ItemWardrobeMeta.isWearableArmorMaterial(mat)) {
            return true;
        }

        // 3. Explicit Wardrobe 3D/armor slot tag (helmet/chest/legs/boots real/cosmetic, mask_3d, hair_3d, backpack_3d, belt_3d)
        if (meta != null && meta.hasSlot()) {
            for (String part : meta.slotId().split(",")) {
                WardrobeSlotDef bound = config.getSlot(part.trim());
                if (bound != null && !bound.isSkinLayer()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns true when this item should be rendered via a synchronized invisible ArmorStand
     * (full 3D MythicArmors armor shader + vanilla armor) rather than a flat ItemDisplay.
     */
    public boolean isEquippableArmorModel(ItemStack item) {
        return detectEquipmentSlot(item) != null;
    }

    public EquipmentSlot detectEquipmentSlot(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;

        // Paper 1.21.4+ / 1.21.8+ Equippable data component (used by MythicArmors & MythicMobs Equippable:)
        try {
            Equippable eq = item.getData(DataComponentTypes.EQUIPPABLE);
            if (eq != null && eq.slot() != null) {
                return eq.slot();
            }
        } catch (Throwable ignored) {
        }

        Material mat = item.getType();
        if (mat.isItem()) {
            EquipmentSlot matSlot = mat.getEquipmentSlot();
            if (matSlot == EquipmentSlot.HEAD || matSlot == EquipmentSlot.CHEST
                    || matSlot == EquipmentSlot.LEGS || matSlot == EquipmentSlot.FEET) {
                return matSlot;
            }
        }

        String name = mat.name();
        if (name.endsWith("_HELMET") || mat == Material.CARVED_PUMPKIN || name.endsWith("_HEAD") || name.endsWith("_SKULL")) {
            return EquipmentSlot.HEAD;
        }
        if (name.endsWith("_CHESTPLATE") || mat == Material.ELYTRA) {
            return EquipmentSlot.CHEST;
        }
        if (name.endsWith("_LEGGINGS")) {
            return EquipmentSlot.LEGS;
        }
        if (name.endsWith("_BOOTS")) {
            return EquipmentSlot.FEET;
        }
        return null;
    }

    public void tagItem(ItemStack item, String slotId, String texturePath) {
        tagItem(item, slotId, texturePath, null, null);
    }

    public void tagItem(ItemStack item, String slotId, String texturePath, Collection<String> blockedTargets, BlockBehaviorMode blockMode) {
        if (item == null || item.getType().isAir()) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(keySlot, PersistentDataType.STRING, slotId.trim().toLowerCase(Locale.ROOT));
        if (texturePath != null && !texturePath.isBlank()) {
            pdc.set(keyTexture, PersistentDataType.STRING, texturePath.trim());
        } else {
            pdc.remove(keyTexture);
        }
        if (blockedTargets != null) {
            Set<String> norm = ItemWardrobeMeta.normalizeBlockedTargets(blockedTargets);
            if (norm.isEmpty()) {
                pdc.remove(keyBlockedSlots);
            } else {
                pdc.set(keyBlockedSlots, PersistentDataType.STRING, String.join(",", norm));
            }
        }
        if (blockMode != null) {
            pdc.set(keyBlockMode, PersistentDataType.STRING, blockMode.name());
        }
        item.setItemMeta(meta);
    }

    public void setItemBlocks(ItemStack item, Collection<String> blockedTargets, BlockBehaviorMode blockMode) {
        if (item == null || item.getType().isAir()) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        Set<String> norm = ItemWardrobeMeta.normalizeBlockedTargets(blockedTargets);
        if (norm.isEmpty()) {
            pdc.remove(keyBlockedSlots);
            pdc.remove(keyBlockMode);
        } else {
            pdc.set(keyBlockedSlots, PersistentDataType.STRING, String.join(",", norm));
            if (blockMode != null) {
                pdc.set(keyBlockMode, PersistentDataType.STRING, blockMode.name());
            } else {
                pdc.remove(keyBlockMode);
            }
        }
        item.setItemMeta(meta);
    }
}
