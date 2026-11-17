package dev.mythicwardrobe.hook;

import dev.mythicwardrobe.config.WardrobeConfig;
import dev.mythicwardrobe.manager.WardrobeManager;
import dev.mythicwardrobe.model.BlockBehaviorMode;
import dev.mythicwardrobe.model.CosmeticVariantConfig;
import dev.mythicwardrobe.model.DisplayMountConfig;
import dev.mythicwardrobe.model.ItemWardrobeMeta;
import dev.mythicwardrobe.model.WardrobeSlotDef;
import io.lumine.mythic.api.adapters.AbstractEntity;
import io.lumine.mythic.api.config.MythicConfig;
import io.lumine.mythic.api.skills.SkillCaster;
import io.lumine.mythic.api.skills.SkillTrigger;
import io.lumine.mythic.bukkit.BukkitAdapter;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.bukkit.events.MythicConditionLoadEvent;
import io.lumine.mythic.bukkit.events.MythicMechanicLoadEvent;
import io.lumine.mythic.bukkit.events.MythicReloadedEvent;
import io.lumine.mythic.core.items.MythicItem;
import io.lumine.mythic.core.skills.SkillExecutor;
import io.lumine.mythic.core.skills.SkillMechanic;
import io.lumine.mythic.core.skills.SkillMetadataImpl;
import io.lumine.mythic.core.skills.SkillTriggers;
import org.bukkit.Bukkit;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Integrates MythicWardrobe with MythicMobs 5.13.0+ (and MythicArmors / MythicCrucible items):
 *  - Registers `wardrobe` / `skinlayer` and `unwardrobe` custom mechanics
 *  - Registers `haswardrobelayer` / `hasbodytype` custom conditions
 *  - Reads `Wardrobe:` config sections directly from MythicMobs item YAML files
 *  - Resolves MythicItem IDs & generates combo MythicArmors ItemStacks
 *  - Executes item `Skills:` with `~onEquip` / `~onUnequip` (as well as `Wardrobe.OnEquipSkill` / `OnUnequipSkill`)
 *    when items are equipped or removed in MythicWardrobe GUI menus
 */
public final class MythicMobsHook implements Listener {

    private final JavaPlugin plugin;
    private final WardrobeConfig config;
    private WardrobeManager wardrobeManager;

    private record CompiledItemSkills(List<SkillMechanic> equipMechanics, List<SkillMechanic> unequipMechanics) {
        static final CompiledItemSkills EMPTY = new CompiledItemSkills(List.of(), List.of());
    }

    private final Map<String, CompiledItemSkills> itemSkillsCache = new ConcurrentHashMap<>();
    private final ThreadLocal<Set<String>> activeExecutions = ThreadLocal.withInitial(HashSet::new);

    public MythicMobsHook(JavaPlugin plugin, WardrobeConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void attachManager(WardrobeManager manager) {
        this.wardrobeManager = manager;
    }

    public void clearSkillCache() {
        itemSkillsCache.clear();
    }

    public boolean isMythicCruciblePresent() {
        try {
            return Bukkit.getPluginManager().isPluginEnabled("MythicCrucible");
        } catch (Throwable t) {
            return false;
        }
    }

    @EventHandler
    public void onMythicReloaded(MythicReloadedEvent event) {
        clearSkillCache();
    }

    @EventHandler
    public void onMechanicLoad(MythicMechanicLoadEvent event) {
        if (wardrobeManager == null) return;
        String name = event.getMechanicName().toLowerCase(Locale.ROOT);
        if (name.equals("wardrobe") || name.equals("skinlayer") || name.equals("wardrobeskin")) {
            event.register(new WardrobeMechanic(wardrobeManager, event.getConfig()));
        } else if (name.equals("unwardrobe") || name.equals("removewardrobe") || name.equals("clearwardrobe")
                || name.equals("unwear") || name.equals("unskinlayer")) {
            event.register(new UnwardrobeMechanic(wardrobeManager, event.getConfig()));
        }
    }

    @EventHandler
    public void onConditionLoad(MythicConditionLoadEvent event) {
        if (wardrobeManager == null) return;
        String name = event.getConditionName().toLowerCase(Locale.ROOT);
        if (name.equals("haswardrobelayer") || name.equals("wardrobelayer") || name.equals("wearingwardrobe")) {
            event.register(new HasWardrobeLayerCondition(wardrobeManager, event.getConfig()));
        } else if (name.equals("hasbodytype") || name.equals("bodytype") || name.equals("wardrobebodytype")) {
            event.register(new HasBodyTypeCondition(wardrobeManager, event.getConfig()));
        }
    }

    public String getMythicItemId(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        try {
            return MythicBukkit.inst().getItemManager().getMythicTypeFromItem(item);
        } catch (Throwable t) {
            return null;
        }
    }

    public ItemStack generateMythicItem(String mythicId) {
        if (mythicId == null || mythicId.isBlank()) return null;
        try {
            return MythicBukkit.inst().getItemManager().getItemStack(mythicId);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Failed to generate MythicItem '" + mythicId + "'", t);
            return null;
        }
    }

    /**
     * Reads an optional `Wardrobe:` section directly from a MythicItem's YAML definition:
     *   Winter_Jacket:
     *     Id: LEATHER_CHESTPLATE
     *     Wardrobe:
     *       Slot: jacket
     *       Texture: jackets/demo_jacket.png
     *       OnEquipSkill: ColdResistOn
     *       OnUnequipSkill: ColdResistOff
     */
    public Optional<ItemWardrobeMeta> readMythicItemWardrobeMeta(String mythicId) {
        if (mythicId == null || mythicId.isBlank()) return Optional.empty();
        try {
            Optional<MythicItem> opt = MythicBukkit.inst().getItemManager().getItem(mythicId);
            if (opt.isEmpty()) return Optional.empty();
            MythicConfig mc = opt.get().getConfig();
            if (mc == null || !mc.isSet("Wardrobe")) return Optional.empty();

            MythicConfig wCfg = mc.getNestedConfig("Wardrobe");
            if (wCfg == null) return Optional.empty();

            String slot = wCfg.getString(new String[]{"Slot", "slot", "Type", "type"}, "");
            String texture = wCfg.getString(new String[]{"Texture", "texture", "File", "file"}, "");
            String onEquip = wCfg.getString(new String[]{"OnEquipSkill", "on-equip-skill", "EquipSkill"}, "");
            String onUnequip = wCfg.getString(new String[]{"OnUnequipSkill", "on-unequip-skill", "UnequipSkill"}, "");
            java.util.Set<String> blockedTargets = readMythicBlockedTargets(wCfg);
            BlockBehaviorMode blockMode = BlockBehaviorMode.parseOrNull(
                    wCfg.getString(new String[]{"BlockMode", "block-mode", "BlockingMode", "LockMode"}, ""));

            DisplayMountConfig displayOverride = null;
            if (wCfg.isSet("Display")) {
                MythicConfig dCfg = wCfg.getNestedConfig("Display");
                if (dCfg != null) {
                    WardrobeSlotDef def = config.getSlot(slot);
                    DisplayMountConfig base = def != null && def.displayMount() != null
                            ? def.displayMount()
                            : DisplayMountConfig.defaultBack();
                    displayOverride = parseMythicDisplay(dCfg, base);
                }
            }

            CosmeticVariantConfig.CosmeticSkinDef inlineSkin = readInlineCosmeticSkin(mythicId, slot, wCfg);

            return Optional.of(new ItemWardrobeMeta(
                    slot == null ? "" : slot.trim().toLowerCase(Locale.ROOT),
                    texture == null ? "" : texture.trim(),
                    displayOverride,
                    onEquip == null ? "" : onEquip.trim(),
                    onUnequip == null ? "" : onUnequip.trim(),
                    mythicId,
                    blockedTargets,
                    blockMode,
                    inlineSkin
            ));
        } catch (Throwable t) {
            if (config.isDebug()) {
                plugin.getLogger().log(Level.WARNING, "Error reading Wardrobe config for MythicItem " + mythicId, t);
            }
            return Optional.empty();
        }
    }

    /**
     * Reads an optional inline multimodal cosmetic block inside a MythicItem's `Wardrobe:` section:
     *   Wardrobe:
     *     Slot: chest_cosmetic
     *     UnmatchedMode: KEEP_REAL
     *     EmptyArmorMode: KEEP_EMPTY
     *     Variants:
     *       LightMilitaryVest: LightMilitaryVest_White
     *       HeavyMilitaryVest: HeavyMilitaryVest_White
     */
    private static CosmeticVariantConfig.CosmeticSkinDef readInlineCosmeticSkin(
            String mythicId,
            String slotCsv,
            MythicConfig wCfg
    ) {
        String variantsKey = null;
        for (String k : new String[]{"Variants", "variants", "CosmeticVariants", "cosmetic-variants", "ArmorVariants", "armor-variants"}) {
            if (wCfg.isSet(k)) {
                variantsKey = k;
                break;
            }
        }
        if (variantsKey == null) return null;

        List<CosmeticVariantConfig.VariantRule> rules = new ArrayList<>();
        if (wCfg.isConfigurationSection(variantsKey)) {
            MythicConfig vCfg = wCfg.getNestedConfig(variantsKey);
            if (vCfg != null) {
                for (String key : vCfg.getKeys()) {
                    if (vCfg.isConfigurationSection(key)) {
                        MythicConfig sub = vCfg.getNestedConfig(key);
                        if (sub == null) continue;
                        EquipmentSlot eq = parseEquipSlot(sub.getString(new String[]{"EquipmentSlot", "equipment-slot", "Slot", "slot"}, ""));
                        List<String> matchArmor = readMythicList(sub, "MatchArmor", "match-armor", "MatchMythicItem", "match-mythic-item", "Armor", "armor");
                        List<String> matchMats = readMythicList(sub, "MatchMaterials", "match-materials", "MatchMaterial", "match-material", "Material", "material");
                        List<String> matchModels = readMythicList(sub, "MatchModels", "match-models", "MatchModel", "match-model");
                        Set<Integer> matchCmds = new java.util.LinkedHashSet<>();
                        for (String raw : readMythicList(sub, "MatchCustomModelData", "match-custom-model-data", "MatchCmd", "match-cmd")) {
                            try { matchCmds.add(Integer.parseInt(raw)); } catch (NumberFormatException ignored) {}
                        }
                        if (matchArmor.isEmpty() && matchMats.isEmpty() && matchCmds.isEmpty() && matchModels.isEmpty()) {
                            matchArmor = List.of(key);
                        }
                        String resultItem = sub.getString(new String[]{"ResultMythicItem", "result-mythic-item", "ResultItem", "result-item", "MythicItem", "mythic-item", "ModelItem", "model-item"}, "");
                        String dyeColor = sub.getString(new String[]{"DyeColor", "dye-color", "Color", "color"}, "");
                        String eqModel = sub.getString(new String[]{"EquippableModel", "equippable-model"}, "");
                        String itemModel = sub.getString(new String[]{"ItemModel", "item-model"}, "");
                        Integer cmd = null;
                        for (String ck : new String[]{"CustomModelData", "custom-model-data"}) {
                            if (sub.isInt(ck)) {
                                cmd = sub.getInt(ck, 0);
                                break;
                            }
                        }
                        CosmeticVariantConfig.VariantRule rule = new CosmeticVariantConfig.VariantRule(
                                key, eq, matchArmor, matchMats, matchCmds, matchModels,
                                resultItem, dyeColor, eqModel, itemModel, cmd
                        );
                        if (rule.hasAnyVisualEffect()) rules.add(rule);
                    } else {
                        String val = vCfg.getString(key, "");
                        CosmeticVariantConfig.VariantRule rule = CosmeticVariantConfig.parseShorthandVariant(key, val);
                        if (rule != null && rule.hasAnyVisualEffect()) rules.add(rule);
                    }
                }
            }
        }
        if (rules.isEmpty()) return null;

        Set<String> allowedSlots = new java.util.LinkedHashSet<>();
        if (slotCsv != null && !slotCsv.isBlank()) {
            for (String s : slotCsv.split("[,;]+")) {
                if (!s.isBlank()) allowedSlots.add(s.trim().toLowerCase(Locale.ROOT));
            }
        }
        for (String s : readMythicList(wCfg, "AllowedSlots", "allowed-slots", "Slots", "slots")) {
            allowedSlots.add(s.trim().toLowerCase(Locale.ROOT));
        }

        CosmeticVariantConfig.UnmatchedMode unmatchedMode = CosmeticVariantConfig.UnmatchedMode.parse(
                wCfg.getString(new String[]{"UnmatchedMode", "unmatched-mode", "FallbackMode", "fallback-mode"}, "KEEP_REAL"),
                CosmeticVariantConfig.UnmatchedMode.KEEP_REAL
        );
        CosmeticVariantConfig.EmptyArmorMode emptyMode = CosmeticVariantConfig.EmptyArmorMode.parse(
                wCfg.getString(new String[]{"EmptyArmorMode", "empty-armor-mode", "NoArmorMode", "no-armor-mode"}, "KEEP_EMPTY"),
                CosmeticVariantConfig.EmptyArmorMode.KEEP_EMPTY
        );
        String fallbackItem = wCfg.getString(new String[]{"FallbackMythicItem", "fallback-mythic-item", "FallbackItem", "fallback-item"}, "");
        String emptyItem = wCfg.getString(new String[]{"EmptyArmorMythicItem", "empty-armor-mythic-item", "EmptyItem", "empty-item"}, "");

        return new CosmeticVariantConfig.CosmeticSkinDef(
                mythicId,
                List.of(mythicId),
                allowedSlots,
                unmatchedMode,
                emptyMode,
                fallbackItem,
                emptyItem,
                rules
        );
    }

    private static EquipmentSlot parseEquipSlot(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "HEAD", "HELMET" -> EquipmentSlot.HEAD;
            case "CHEST", "CHESTPLATE", "BODY" -> EquipmentSlot.CHEST;
            case "LEGS", "LEGGINGS" -> EquipmentSlot.LEGS;
            case "FEET", "BOOTS" -> EquipmentSlot.FEET;
            default -> null;
        };
    }

    private static List<String> readMythicList(MythicConfig cfg, String... keys) {
        List<String> out = new ArrayList<>();
        for (String key : keys) {
            if (!cfg.isSet(key)) continue;
            try {
                List<String> list = cfg.getStringList(key);
                if (list != null && !list.isEmpty()) {
                    for (String s : list) {
                        if (s == null) continue;
                        for (String tok : s.split("[,;]+")) {
                            if (!tok.isBlank()) out.add(tok.trim());
                        }
                    }
                    if (!out.isEmpty()) return out;
                }
            } catch (Throwable ignored) {
            }
            String raw = cfg.getString(key, "");
            if (raw != null && !raw.isBlank()) {
                for (String tok : raw.split("[,;]+")) {
                    if (!tok.isBlank()) out.add(tok.trim());
                }
                if (!out.isEmpty()) return out;
            }
        }
        return out;
    }

    private static java.util.Set<String> readMythicBlockedTargets(MythicConfig wCfg) {
        String[] keys = {"Blocks", "blocks", "BlockSlots", "block-slots", "BlockedSlots", "blocked-slots",
                "BlockLayers", "block-layers", "LockedSlots", "locked-slots", "LockSlots", "lock-slots", "Block", "block"};
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String key : keys) {
            if (!wCfg.isSet(key)) continue;
            try {
                java.util.List<String> list = wCfg.getStringList(key);
                if (list != null && !list.isEmpty()) {
                    out.addAll(ItemWardrobeMeta.normalizeBlockedTargets(list));
                    continue;
                }
            } catch (Throwable ignored) {
            }
            String csv = wCfg.getString(key, "");
            if (csv != null && !csv.isBlank()) {
                out.addAll(ItemWardrobeMeta.parseBlockedTargetsCsv(csv));
            }
        }
        return out.isEmpty() ? java.util.Set.of() : java.util.Collections.unmodifiableSet(out);
    }

    public void castSkill(Player player, String skillName) {
        if (player == null || !player.isOnline() || skillName == null || skillName.isBlank()) return;
        try {
            MythicBukkit.inst().getAPIHelper().castSkill(player, skillName);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Failed to cast MythicSkill '" + skillName + "' for " + player.getName(), t);
        }
    }

    /**
     * Executes all `Skills:` lines on the item's MythicItem definition whose trigger is
     * `~onEquip` / `~onEquipArmor` (when {@code equip == true}) or `~onUnequip` / `~onUnequipArmor`
     * (when {@code equip == false}).
     *
     * Works for both MythicMobs and MythicCrucible items placed into MythicWardrobe GUI slots.
     *
     * @return number of skill mechanics executed
     */
    public int triggerItemEquipSkills(Player player, ItemStack itemStack, boolean equip) {
        if (player == null || !player.isOnline() || itemStack == null || itemStack.getType().isAir()) {
            return 0;
        }
        String mythicId = getMythicItemId(itemStack);
        if (mythicId == null || mythicId.isBlank()) {
            return 0;
        }

        String guardKey = player.getUniqueId() + ":" + mythicId.toLowerCase(Locale.ROOT) + ":" + (equip ? "E" : "U");
        Set<String> running = activeExecutions.get();
        if (!running.add(guardKey)) {
            return 0; // Prevent accidental recursive loops if a skill manipulates the wardrobe
        }

        try {
            CompiledItemSkills compiled = itemSkillsCache.computeIfAbsent(
                    mythicId.toLowerCase(Locale.ROOT),
                    k -> compileItemEquipSkills(mythicId)
            );
            List<SkillMechanic> mechanics = equip ? compiled.equipMechanics() : compiled.unequipMechanics();
            if (mechanics.isEmpty()) {
                return 0;
            }

            SkillExecutor skillManager = MythicBukkit.inst().getSkillManager();
            AbstractEntity abstractPlayer = BukkitAdapter.adapt(player);
            SkillCaster caster = skillManager.getCaster(abstractPlayer);
            if (caster == null) {
                return 0;
            }

            SkillTrigger<?> trigger = equip ? SkillTriggers.ARMOR_EQUIP : SkillTriggers.ARMOR_UNEQUIP;
            SkillMetadataImpl baseMeta = new SkillMetadataImpl(trigger, caster, abstractPlayer);
            baseMeta.setOrigin(BukkitAdapter.adapt(player.getLocation()));
            baseMeta.setCasterItem(BukkitAdapter.adapt(itemStack));

            int executed = 0;
            for (SkillMechanic mechanic : mechanics) {
                try {
                    SkillMetadataImpl metaCopy = baseMeta.deepClone();
                    if (mechanic.isUsableFromCaster(metaCopy)) {
                        mechanic.execute(metaCopy);
                        executed++;
                    }
                } catch (Throwable t) {
                    plugin.getLogger().log(Level.WARNING,
                            "Error executing " + (equip ? "~onEquip" : "~onUnequip")
                                    + " skill '" + mechanic.getConfigLine() + "' on item '" + mythicId + "'",
                            t);
                }
            }

            if (config.isDebug() && executed > 0) {
                plugin.getLogger().info("[Debug] Executed " + executed + " " + (equip ? "~onEquip" : "~onUnequip")
                        + " skill(s) for " + player.getName() + " from item " + mythicId);
            }
            return executed;
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to run item " + (equip ? "~onEquip" : "~onUnequip") + " skills for '" + mythicId + "'",
                    t);
            return 0;
        } finally {
            running.remove(guardKey);
        }
    }

    private CompiledItemSkills compileItemEquipSkills(String mythicId) {
        try {
            Optional<MythicItem> opt = MythicBukkit.inst().getItemManager().getItem(mythicId);
            if (opt.isEmpty()) {
                return CompiledItemSkills.EMPTY;
            }
            MythicItem mythicItem = opt.get();
            MythicConfig mc = mythicItem.getConfig();
            if (mc == null) {
                return CompiledItemSkills.EMPTY;
            }

            List<String> rawSkills = new ArrayList<>();
            if (mc.isSet("Skills")) {
                List<String> list = mc.getStringList("Skills");
                if (list != null) rawSkills.addAll(list);
            }
            if (rawSkills.isEmpty() && mc.isSet("skills")) {
                List<String> list = mc.getStringList("skills");
                if (list != null) rawSkills.addAll(list);
            }
            if (rawSkills.isEmpty()) {
                return CompiledItemSkills.EMPTY;
            }

            SkillExecutor skillManager = MythicBukkit.inst().getSkillManager();
            List<SkillMechanic> equipList = new ArrayList<>();
            List<SkillMechanic> unequipList = new ArrayList<>();

            for (String rawLine : rawSkills) {
                if (rawLine == null || rawLine.isBlank()) continue;

                // Quick pre-filter: only compile lines that relate to equip / unequip triggers
                ItemEquipTriggerHelper.EquipTriggerKind lineKind = ItemEquipTriggerHelper.detectFromSkillLine(rawLine);
                if (lineKind == ItemEquipTriggerHelper.EquipTriggerKind.NONE) {
                    continue;
                }

                SkillMechanic mechanic = skillManager.getMechanic(mythicItem.getFile(), rawLine);
                if (mechanic == null) {
                    continue;
                }

                // Confirm via parsed SkillTrigger when available, otherwise use line classification
                SkillTrigger<?> parsedTrigger = mechanic.getTrigger();
                ItemEquipTriggerHelper.EquipTriggerKind kind = lineKind;
                if (parsedTrigger != null) {
                    if (parsedTrigger.equals(SkillTriggers.ARMOR_EQUIP)) {
                        kind = ItemEquipTriggerHelper.EquipTriggerKind.EQUIP;
                    } else if (parsedTrigger.equals(SkillTriggers.ARMOR_UNEQUIP)) {
                        kind = ItemEquipTriggerHelper.EquipTriggerKind.UNEQUIP;
                    } else {
                        ItemEquipTriggerHelper.EquipTriggerKind byName = ItemEquipTriggerHelper.classifyTriggerName(parsedTrigger.name());
                        if (byName != ItemEquipTriggerHelper.EquipTriggerKind.NONE) {
                            kind = byName;
                        }
                    }
                }

                // Ensure mechanic's trigger aligns with SkillTriggers.ARMOR_EQUIP / ARMOR_UNEQUIP
                // so mechanic.checkSkillTrigger(meta) succeeds even for aliases like ~onWear / ~onUnwear
                if (kind == ItemEquipTriggerHelper.EquipTriggerKind.EQUIP) {
                    if (parsedTrigger == null || !parsedTrigger.equals(SkillTriggers.ARMOR_EQUIP)) {
                        mechanic.setTrigger(SkillTriggers.ARMOR_EQUIP);
                    }
                    equipList.add(mechanic);
                } else if (kind == ItemEquipTriggerHelper.EquipTriggerKind.UNEQUIP) {
                    if (parsedTrigger == null || !parsedTrigger.equals(SkillTriggers.ARMOR_UNEQUIP)) {
                        mechanic.setTrigger(SkillTriggers.ARMOR_UNEQUIP);
                    }
                    unequipList.add(mechanic);
                }
            }

            if (equipList.isEmpty() && unequipList.isEmpty()) {
                return CompiledItemSkills.EMPTY;
            }
            return new CompiledItemSkills(
                    Collections.unmodifiableList(equipList),
                    Collections.unmodifiableList(unequipList)
            );
        } catch (Throwable t) {
            if (config.isDebug()) {
                plugin.getLogger().log(Level.WARNING, "Failed to compile item skills for '" + mythicId + "'", t);
            }
            return CompiledItemSkills.EMPTY;
        }
    }

    private static DisplayMountConfig parseMythicDisplay(MythicConfig d, DisplayMountConfig def) {
        DisplayMountConfig.MountPoint mp = d.getEnum(new String[]{"Mount", "mount"}, DisplayMountConfig.MountPoint.class, def.mountPoint());
        float ox = d.getFloat(new String[]{"OffsetX", "offset-x"}, def.offsetX());
        float oy = d.getFloat(new String[]{"OffsetY", "offset-y"}, def.offsetY());
        float oz = d.getFloat(new String[]{"OffsetZ", "offset-z"}, def.offsetZ());
        float sx = d.getFloat(new String[]{"ScaleX", "scale-x"}, def.scaleX());
        float sy = d.getFloat(new String[]{"ScaleY", "scale-y"}, def.scaleY());
        float sz = d.getFloat(new String[]{"ScaleZ", "scale-z"}, def.scaleZ());
        float rx = d.getFloat(new String[]{"RotX", "rot-x"}, def.rotX());
        float ry = d.getFloat(new String[]{"RotY", "rot-y"}, def.rotY());
        float rz = d.getFloat(new String[]{"RotZ", "rot-z"}, def.rotZ());
        boolean pitch = d.getBoolean(new String[]{"PitchFollow", "pitch-follow"}, def.pitchFollow());
        boolean hideWearer = d.getBoolean(new String[]{"HideFromWearer", "hide-from-wearer"}, def.hideFromWearer());
        ItemDisplay.ItemDisplayTransform tr = d.getEnum(new String[]{"Transform", "transform"}, ItemDisplay.ItemDisplayTransform.class, def.transform());
        return new DisplayMountConfig(mp, ox, oy, oz, sx, sy, sz, rx, ry, rz, pitch, hideWearer, tr);
    }
}
