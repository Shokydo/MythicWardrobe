package dev.mythicwardrobe;

import dev.mythicwardrobe.hook.ItemEquipTriggerHelper;
import dev.mythicwardrobe.hook.ItemEquipTriggerHelper.EquipTriggerKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ItemEquipTriggerTest {

    @Test
    void detectsAllEquipTriggerVariantsInSkillLines() {
        assertEquals(EquipTriggerKind.EQUIP,
                ItemEquipTriggerHelper.detectFromSkillLine("sound{s=item.armor.equip_iron} @self ~onEquip"));
        assertEquals(EquipTriggerKind.EQUIP,
                ItemEquipTriggerHelper.detectFromSkillLine("wardrobe{type=mask;file=respirator.png} @self ~onequip ?sneaking"));
        assertEquals(EquipTriggerKind.EQUIP,
                ItemEquipTriggerHelper.detectFromSkillLine("potion{t=NIGHT_VISION;d=99999;l=0} @self ~onEquipArmor 1.0"));
        assertEquals(EquipTriggerKind.EQUIP,
                ItemEquipTriggerHelper.detectFromSkillLine("skill{s=GiveCosmeticBuff} @self ~onArmorEquip"));
        assertEquals(EquipTriggerKind.EQUIP,
                ItemEquipTriggerHelper.detectFromSkillLine("message{m=\"Equipped\"} @self ~onWear"));

        assertTrue(ItemEquipTriggerHelper.matchesSkillLine("sound{s=ui.button.click} @self ~onEquip", true));
        assertFalse(ItemEquipTriggerHelper.matchesSkillLine("sound{s=ui.button.click} @self ~onEquip", false));
    }

    @Test
    void detectsAllUnequipTriggerVariantsInSkillLines() {
        assertEquals(EquipTriggerKind.UNEQUIP,
                ItemEquipTriggerHelper.detectFromSkillLine("unwardrobe{type=mask;file=respirator.png} @self ~onUnequip"));
        assertEquals(EquipTriggerKind.UNEQUIP,
                ItemEquipTriggerHelper.detectFromSkillLine("potionclear{t=NIGHT_VISION} @self ~onUnEquip"));
        assertEquals(EquipTriggerKind.UNEQUIP,
                ItemEquipTriggerHelper.detectFromSkillLine("skill{s=RemoveCosmeticBuff} @self ~onUnequipArmor"));
        assertEquals(EquipTriggerKind.UNEQUIP,
                ItemEquipTriggerHelper.detectFromSkillLine("sound{s=item.armor.equip_leather} @self ~onArmorUnequip"));
        assertEquals(EquipTriggerKind.UNEQUIP,
                ItemEquipTriggerHelper.detectFromSkillLine("message{m=\"Removed\"} @self ~onUnwear"));

        assertTrue(ItemEquipTriggerHelper.matchesSkillLine("potionclear{t=SPEED} @self ~onUnEquip", false));
        assertFalse(ItemEquipTriggerHelper.matchesSkillLine("potionclear{t=SPEED} @self ~onUnEquip", true));
    }

    @Test
    void ignoresNonEquipTriggersAndBlankLines() {
        assertEquals(EquipTriggerKind.NONE, ItemEquipTriggerHelper.detectFromSkillLine(null));
        assertEquals(EquipTriggerKind.NONE, ItemEquipTriggerHelper.detectFromSkillLine("   "));
        assertEquals(EquipTriggerKind.NONE,
                ItemEquipTriggerHelper.detectFromSkillLine("damage{a=5} @target ~onAttack"));
        assertEquals(EquipTriggerKind.NONE,
                ItemEquipTriggerHelper.detectFromSkillLine("skill{s=TickAura} @self ~onTimer:20"));
        assertEquals(EquipTriggerKind.NONE,
                ItemEquipTriggerHelper.detectFromSkillLine("shootfireball @target ~onRightClick"));
        assertEquals(EquipTriggerKind.NONE,
                ItemEquipTriggerHelper.detectFromSkillLine("wardrobe{type=jacket;file=hoodie.png} @self"));
    }

    @Test
    void classifiesNativeMythicMobsArmorTriggers() {
        assertEquals(EquipTriggerKind.EQUIP, ItemEquipTriggerHelper.classifyTriggerName("EQUIP"));
        assertEquals(EquipTriggerKind.EQUIP, ItemEquipTriggerHelper.classifyTriggerName("EQUIPARMOR"));
        assertEquals(EquipTriggerKind.EQUIP, ItemEquipTriggerHelper.classifyTriggerName("ARMOR_EQUIP"));
        assertEquals(EquipTriggerKind.UNEQUIP, ItemEquipTriggerHelper.classifyTriggerName("UNEQUIP"));
        assertEquals(EquipTriggerKind.UNEQUIP, ItemEquipTriggerHelper.classifyTriggerName("UNEQUIPARMOR"));
        assertEquals(EquipTriggerKind.UNEQUIP, ItemEquipTriggerHelper.classifyTriggerName("ARMOR_UNEQUIP"));
    }
}
