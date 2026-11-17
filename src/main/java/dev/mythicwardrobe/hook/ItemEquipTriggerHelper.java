package dev.mythicwardrobe.hook;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure helper for detecting MythicMobs / MythicCrucible equip & unequip triggers
 * (`~onEquip`, `~onUnequip`, `~onUnEquip`, `~onEquipArmor`, `~onUnequipArmor`, etc.)
 * on item `Skills:` lines and parsed {@code SkillTrigger} names.
 */
public final class ItemEquipTriggerHelper {

    public enum EquipTriggerKind {
        NONE,
        EQUIP,
        UNEQUIP
    }

    /**
     * Matches MythicMobs skill trigger tokens such as `~onEquip`, `~onUnEquip`, `~onEquipArmor:1`, etc.
     */
    private static final Pattern TRIGGER_TOKEN_PATTERN =
            Pattern.compile("(?<![\\w-])~(?:on)?([a-zA-Z_]+)(?::[^\\s]+)?(?=\\s|$)");

    private ItemEquipTriggerHelper() {
    }

    /**
     * Inspects a raw MythicMobs `Skills:` line and determines whether it declares an equip or unequip trigger.
     */
    public static EquipTriggerKind detectFromSkillLine(String rawSkillLine) {
        if (rawSkillLine == null || rawSkillLine.isBlank()) {
            return EquipTriggerKind.NONE;
        }
        Matcher matcher = TRIGGER_TOKEN_PATTERN.matcher(rawSkillLine);
        while (matcher.find()) {
            EquipTriggerKind kind = classifyTriggerName(matcher.group(1));
            if (kind != EquipTriggerKind.NONE) {
                return kind;
            }
        }
        return EquipTriggerKind.NONE;
    }

    /**
     * Classifies a MythicMobs trigger name (with or without leading `~` / `on` prefix and underscores).
     */
    public static EquipTriggerKind classifyTriggerName(String triggerName) {
        if (triggerName == null || triggerName.isBlank()) {
            return EquipTriggerKind.NONE;
        }
        String norm = triggerName.trim().toLowerCase(Locale.ROOT);
        if (norm.startsWith("~")) {
            norm = norm.substring(1);
        }
        if (norm.startsWith("on")) {
            norm = norm.substring(2);
        }
        norm = norm.replace("_", "").replace("-", "");

        return switch (norm) {
            case "equip", "equiparmor", "armorequip", "wear" -> EquipTriggerKind.EQUIP;
            case "unequip", "unequiparmor", "armorunequip", "unwear" -> EquipTriggerKind.UNEQUIP;
            default -> EquipTriggerKind.NONE;
        };
    }

    /**
     * Checks whether a given skill line matches the requested direction ({@code equip == true} -> ~onEquip,
     * {@code equip == false} -> ~onUnequip).
     */
    public static boolean matchesSkillLine(String rawSkillLine, boolean equip) {
        EquipTriggerKind kind = detectFromSkillLine(rawSkillLine);
        return equip ? kind == EquipTriggerKind.EQUIP : kind == EquipTriggerKind.UNEQUIP;
    }
}
