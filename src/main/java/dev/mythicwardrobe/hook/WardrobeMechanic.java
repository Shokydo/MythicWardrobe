package dev.mythicwardrobe.hook;

import dev.mythicwardrobe.manager.WardrobeManager;
import dev.mythicwardrobe.model.ItemWardrobeMeta;
import dev.mythicwardrobe.model.SkinModelVariant;
import io.lumine.mythic.api.adapters.AbstractEntity;
import io.lumine.mythic.api.config.MythicLineConfig;
import io.lumine.mythic.api.skills.ITargetedEntitySkill;
import io.lumine.mythic.api.skills.SkillMetadata;
import io.lumine.mythic.api.skills.SkillResult;
import io.lumine.mythic.api.skills.placeholders.PlaceholderString;
import io.lumine.mythic.bukkit.BukkitAdapter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Set;

/**
 * MythicMobs custom mechanic:
 *   - wardrobe{type=jacket;texture=demo_jacket.png} @self ~onEquip
 *   - wardrobe{type=jacket;texture=hazmat_suit.png;blocks=pants,undershirt,belt_skin,gloves,boots_skin} @self ~onEquip
 *   - wardrobe{type=body_skin;texture=demo_light.png;bodytype=slim} @self
 *   - wardrobe{bodytype=wide} @self
 *   - wardrobe{type=bodytype;value=slim} @self
 *   - wardrobe{type=jacket;action=clear} @self ~onUnequip
 *   - wardrobe{action=reset} @self
 *   - wardrobe{action=refresh} @self
 */
public final class WardrobeMechanic implements ITargetedEntitySkill {

    private final WardrobeManager wardrobeManager;
    private final PlaceholderString typePlaceholder;
    private final PlaceholderString texturePlaceholder;
    private final PlaceholderString bodyTypePlaceholder;
    private final PlaceholderString blocksPlaceholder;
    private final String action;
    private final boolean immediate;
    private final boolean force;

    public WardrobeMechanic(WardrobeManager wardrobeManager, MythicLineConfig config) {
        this.wardrobeManager = wardrobeManager;
        this.typePlaceholder = config.getPlaceholderString(new String[]{"type", "slot", "layer", "category", "s"}, "");
        this.texturePlaceholder = config.getPlaceholderString(new String[]{"texture", "tex", "file", "overlay", "value", "t"}, "");
        this.bodyTypePlaceholder = config.getPlaceholderString(new String[]{"bodytype", "body_type", "arms", "armtype", "model", "variant"}, "");
        this.blocksPlaceholder = config.getPlaceholderString(new String[]{"blocks", "block", "blockslots", "blocklayers", "lock", "locked"}, "");
        this.action = config.getString(new String[]{"action", "mode", "a"}, "set").trim().toLowerCase(Locale.ROOT);
        this.immediate = config.getBoolean(new String[]{"immediate", "now"}, false);
        this.force = config.getBoolean(new String[]{"force", "bypass", "ignoreblocks"}, false);
    }

    @Override
    public SkillResult castAtEntity(SkillMetadata data, AbstractEntity target) {
        Entity bukkitEntity = BukkitAdapter.adapt(target);
        if (!(bukkitEntity instanceof Player player)) {
            return SkillResult.INVALID_TARGET;
        }

        String layerType = safe(typePlaceholder.get(data, target));
        String texture = safe(texturePlaceholder.get(data, target));
        String bodyTypeStr = safe(bodyTypePlaceholder.get(data, target));
        Set<String> blockedTargets = ItemWardrobeMeta.parseBlockedTargetsCsv(safe(blocksPlaceholder.get(data, target)));

        // Support `- wardrobe{type=bodytype;value=slim}` / `- wardrobe{type=bodytype;texture=wide}`
        if (layerType.equalsIgnoreCase("bodytype") || layerType.equalsIgnoreCase("body_type")) {
            bodyTypeStr = texture;
            layerType = "";
            texture = "";
        }

        boolean didWork = false;

        // 1. Handle bodytype=slim | wide | classic | auto
        if (!bodyTypeStr.isEmpty()) {
            if (bodyTypeStr.equalsIgnoreCase("auto") || bodyTypeStr.equalsIgnoreCase("reset") || bodyTypeStr.equalsIgnoreCase("default")) {
                wardrobeManager.setBodyType(player, null, immediate);
                didWork = true;
            } else {
                SkinModelVariant parsed = SkinModelVariant.parseOrNull(bodyTypeStr);
                if (parsed == null) {
                    return SkillResult.CONDITION_FAILED;
                }
                wardrobeManager.setBodyType(player, parsed, immediate);
                didWork = true;
            }
        }

        // 2. Handle layer action
        switch (action) {
            case "reset", "clearall" -> {
                wardrobeManager.resetSkillLayersAndRefresh(player, immediate);
                return SkillResult.SUCCESS;
            }
            case "refresh", "rebuild", "apply" -> {
                wardrobeManager.scheduleSkinRebuild(player, immediate);
                return SkillResult.SUCCESS;
            }
            case "clear", "remove", "unwear", "unset" -> {
                if (layerType.isEmpty()) {
                    return didWork ? SkillResult.SUCCESS : SkillResult.CONDITION_FAILED;
                }
                wardrobeManager.clearSkillLayer(player, layerType, immediate);
                return SkillResult.SUCCESS;
            }
            default -> {
                if (layerType.isEmpty()) {
                    return didWork ? SkillResult.SUCCESS : SkillResult.CONDITION_FAILED;
                }
                if (texture.isEmpty() || texture.equalsIgnoreCase("none") || texture.equalsIgnoreCase("clear")) {
                    wardrobeManager.clearSkillLayer(player, layerType, immediate);
                } else {
                    boolean applied = wardrobeManager.setSkillLayer(player, layerType, texture, blockedTargets, force, immediate);
                    if (!applied) {
                        return SkillResult.CONDITION_FAILED;
                    }
                }
                return SkillResult.SUCCESS;
            }
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }
}
