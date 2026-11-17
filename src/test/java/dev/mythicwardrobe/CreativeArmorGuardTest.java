package dev.mythicwardrobe;

import dev.mythicwardrobe.visual.CreativeArmorGuard;
import dev.mythicwardrobe.visual.CreativeArmorGuard.Decision;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CreativeArmorGuardTest {

    @Test
    void mapsProtocolArmorSlotsAccurately() {
        assertEquals(EquipmentSlot.HEAD, CreativeArmorGuard.containerSlotToEquipmentSlot(5));
        assertEquals(EquipmentSlot.CHEST, CreativeArmorGuard.containerSlotToEquipmentSlot(6));
        assertEquals(EquipmentSlot.LEGS, CreativeArmorGuard.containerSlotToEquipmentSlot(7));
        assertEquals(EquipmentSlot.FEET, CreativeArmorGuard.containerSlotToEquipmentSlot(8));
        assertNull(CreativeArmorGuard.containerSlotToEquipmentSlot(-1));
        assertNull(CreativeArmorGuard.containerSlotToEquipmentSlot(0));
        assertNull(CreativeArmorGuard.containerSlotToEquipmentSlot(9));
        assertNull(CreativeArmorGuard.containerSlotToEquipmentSlot(36));
        assertNull(CreativeArmorGuard.containerSlotToEquipmentSlot(45));
    }

    @Test
    void preventsDeletingArmorWhenSlotHasVisualOverride() {
        for (int slot : new int[]{5, 6, 7, 8}) {
            assertEquals(
                    Decision.CANCEL_AND_RESYNC_ARMOR,
                    CreativeArmorGuard.evaluatePacketAction(slot, true, false, true),
                    "Empty action (click/destroy-all) on overridden armor slot " + slot + " must be cancelled"
            );
        }
    }

    @Test
    void preventsVisualProxyFromOverwritingArmorOrDuplicatingToInventory() {
        // Resending visual_proxy back onto armor slot 6
        assertEquals(
                Decision.CANCEL_AND_RESYNC_ARMOR,
                CreativeArmorGuard.evaluatePacketAction(6, false, true, true)
        );
        // Even if override was just removed, a stale visual_proxy must still be blocked
        assertEquals(
                Decision.CANCEL_AND_RESYNC_ARMOR,
                CreativeArmorGuard.evaluatePacketAction(5, false, true, false)
        );
        // Placing / middle-click cloning visual_proxy into hotbar or inventory
        assertEquals(
                Decision.CANCEL_AND_RESYNC_INVENTORY,
                CreativeArmorGuard.evaluatePacketAction(36, false, true, false)
        );
        assertEquals(
                Decision.CANCEL_AND_RESYNC_INVENTORY,
                CreativeArmorGuard.evaluatePacketAction(9, false, true, true)
        );
        // Dropping visual_proxy out of Creative inventory (slot -1)
        assertEquals(
                Decision.CANCEL_AND_RESYNC_INVENTORY,
                CreativeArmorGuard.evaluatePacketAction(-1, false, true, false)
        );
    }

    @Test
    void preservesOldRealArmorWhenEquippingNewRealItemOverVisualOverride() {
        assertEquals(
                Decision.ALLOW_REAL_EQUIP_PRESERVE_OLD,
                CreativeArmorGuard.evaluatePacketAction(6, false, false, true)
        );
        assertEquals(
                Decision.ALLOW_REAL_EQUIP_PRESERVE_OLD,
                CreativeArmorGuard.evaluatePacketAction(5, false, false, true)
        );
    }

    @Test
    void allowsNormalCreativeActionsOnUnoverriddenSlotsAndInventory() {
        // Normal inventory / hotbar edits
        assertEquals(Decision.ALLOW, CreativeArmorGuard.evaluatePacketAction(36, false, false, false));
        assertEquals(Decision.ALLOW, CreativeArmorGuard.evaluatePacketAction(9, true, false, false));
        assertEquals(Decision.ALLOW, CreativeArmorGuard.evaluatePacketAction(-1, false, false, false));

        // Armor slot WITHOUT any cosmetic/3D override behaves normally in Creative
        assertEquals(Decision.ALLOW, CreativeArmorGuard.evaluatePacketAction(6, true, false, false));
        assertEquals(Decision.ALLOW, CreativeArmorGuard.evaluatePacketAction(6, false, false, false));
    }
}
