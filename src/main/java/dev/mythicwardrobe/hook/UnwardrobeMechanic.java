package dev.mythicwardrobe.hook;

import dev.mythicwardrobe.manager.WardrobeManager;
import io.lumine.mythic.api.adapters.AbstractEntity;
import io.lumine.mythic.api.config.MythicLineConfig;
import io.lumine.mythic.api.skills.ITargetedEntitySkill;
import io.lumine.mythic.api.skills.SkillMetadata;
import io.lumine.mythic.api.skills.SkillResult;
import io.lumine.mythic.api.skills.placeholders.PlaceholderString;
import io.lumine.mythic.bukkit.BukkitAdapter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Dedicated MythicMobs custom mechanic for removing 2D skin layers & resetting bodytype — ideal for `~onUnequip`!
 *
 * Examples:
 *   - unwardrobe{type=jacket} @self ~onUnequip
 *   - unwardrobe{type=jacket;texture=leather_brown.png} @self ~onUnequip   (only removes if that exact texture is worn!)
 *   - unwardrobe{type=jacket,undershirt,gloves} @self ~onUnequip           (removes multiple layers at once)
 *   - unwardrobe{type=all} @self ~onUnequip                                (clears all skill layers)
 *   - unwardrobe{type=body_skin;resetbodytype=true} @self ~onUnequip       (removes base skin + resets slim/wide to auto)
 */
public final class UnwardrobeMechanic implements ITargetedEntitySkill {

    private final WardrobeManager wardrobeManager;
    private final PlaceholderString typePlaceholder;
    private final PlaceholderString texturePlaceholder;
    private final boolean resetBodyType;
    private final boolean immediate;

    public UnwardrobeMechanic(WardrobeManager wardrobeManager, MythicLineConfig config) {
        this.wardrobeManager = wardrobeManager;
        this.typePlaceholder = config.getPlaceholderString(new String[]{"type", "types", "slot", "layer", "category", "s"}, "all");
        this.texturePlaceholder = config.getPlaceholderString(new String[]{"texture", "tex", "file", "overlay", "only", "t"}, "");
        this.resetBodyType = config.getBoolean(new String[]{"resetbodytype", "reset_bodytype", "clearbodytype", "bodytype"}, false);
        this.immediate = config.getBoolean(new String[]{"immediate", "now"}, false);
    }

    @Override
    public SkillResult castAtEntity(SkillMetadata data, AbstractEntity target) {
        Entity bukkitEntity = BukkitAdapter.adapt(target);
        if (!(bukkitEntity instanceof Player player)) {
            return SkillResult.INVALID_TARGET;
        }

        String rawType = safe(typePlaceholder.get(data, target));
        String onlyTexture = safe(texturePlaceholder.get(data, target));

        if (rawType.isEmpty() || rawType.equalsIgnoreCase("all") || rawType.equals("*")) {
            wardrobeManager.clearSkillLayers(player, List.of("all"), onlyTexture, resetBodyType, immediate);
            return SkillResult.SUCCESS;
        }

        List<String> layerTypes = new ArrayList<>();
        for (String part : rawType.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                layerTypes.add(trimmed);
            }
        }
        if (layerTypes.isEmpty() && !resetBodyType) {
            return SkillResult.CONDITION_FAILED;
        }

        wardrobeManager.clearSkillLayers(player, layerTypes, onlyTexture, resetBodyType, immediate);
        return SkillResult.SUCCESS;
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }
}
