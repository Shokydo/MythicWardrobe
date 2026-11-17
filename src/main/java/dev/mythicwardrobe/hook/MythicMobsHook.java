package dev.mythicwardrobe.hook;

import dev.mythicwardrobe.config.WardrobeConfig;
import dev.mythicwardrobe.manager.WardrobeManager;
import dev.mythicwardrobe.model.BlockBehaviorMode;
import dev.mythicwardrobe.model.DisplayMountConfig;
import dev.mythicwardrobe.model.ItemWardrobeMeta;
import dev.mythicwardrobe.model.WardrobeSlotDef;
import io.lumine.mythic.api.config.MythicConfig;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.bukkit.events.MythicConditionLoadEvent;
import io.lumine.mythic.bukkit.events.MythicMechanicLoadEvent;
import io.lumine.mythic.core.items.MythicItem;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Optional;
import java.util.logging.Level;

/**
 * Integrates MythicWardrobe with MythicMobs 5.13.0+ (and MythicArmors items created via MythicMobs):
 *  - Registers `wardrobe` / `skinlayer` custom mechanic
 *  - Registers `haswardrobelayer` / `wardrobelayer` custom condition
 *  - Reads `Wardrobe:` config sections directly from MythicMobs item YAML files
 *  - Resolves MythicItem IDs & generates combo MythicArmors ItemStacks
 *  - Triggers OnEquipSkill / OnUnequipSkill on custom wardrobe GUI slots
 */
public final class MythicMobsHook implements Listener {

    private final JavaPlugin plugin;
    private final WardrobeConfig config;
    private WardrobeManager wardrobeManager;

    public MythicMobsHook(JavaPlugin plugin, WardrobeConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void attachManager(WardrobeManager manager) {
        this.wardrobeManager = manager;
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

            return Optional.of(new ItemWardrobeMeta(
                    slot == null ? "" : slot.trim().toLowerCase(Locale.ROOT),
                    texture == null ? "" : texture.trim(),
                    displayOverride,
                    onEquip == null ? "" : onEquip.trim(),
                    onUnequip == null ? "" : onUnequip.trim(),
                    mythicId,
                    blockedTargets,
                    blockMode
            ));
        } catch (Throwable t) {
            if (config.isDebug()) {
                plugin.getLogger().log(Level.WARNING, "Error reading Wardrobe config for MythicItem " + mythicId, t);
            }
            return Optional.empty();
        }
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
