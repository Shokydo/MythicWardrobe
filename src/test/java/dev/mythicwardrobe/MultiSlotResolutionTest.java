package dev.mythicwardrobe;

import dev.mythicwardrobe.model.DisplayMountConfig;
import dev.mythicwardrobe.model.ItemWardrobeMeta;
import dev.mythicwardrobe.model.SlotKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MultiSlotResolutionTest {

    @Test
    void supportsSingleAndCommaSeparatedMultiSlotBindings() {
        ItemWardrobeMeta single = new ItemWardrobeMeta("backpack_3d", "", null, "", "", "TacticalBackpackArmor");
        assertTrue(single.hasSlot());
        assertEquals("backpack_3d", single.primarySlotId());
        assertTrue(single.matchesSlot("backpack_3d"));
        assertTrue(single.matchesSlot("BACKPACK_3D"));
        assertFalse(single.matchesSlot("chest_real"));

        ItemWardrobeMeta multi = new ItemWardrobeMeta("backpack_3d, chest_real, chest_cosmetic", "", null, "", "", "TacticalBackpackArmor");
        assertEquals("backpack_3d", multi.primarySlotId());
        assertTrue(multi.matchesSlot("backpack_3d"));
        assertTrue(multi.matchesSlot("chest_real"));
        assertTrue(multi.matchesSlot("chest_cosmetic"));
        assertFalse(multi.matchesSlot("mask_3d"));
    }

    @Test
    void armorOverlaySlotKindExistsAndDefaultHeadVisibleToWearer() {
        assertEquals(SlotKind.ARMOR_OVERLAY, SlotKind.valueOf("ARMOR_OVERLAY"));
        assertEquals(SlotKind.DISPLAY_3D, SlotKind.valueOf("DISPLAY_3D"));
        assertEquals(DisplayMountConfig.MountPoint.WAIST, DisplayMountConfig.MountPoint.valueOf("WAIST"));
        assertFalse(DisplayMountConfig.defaultHead().hideFromWearer());
        assertFalse(DisplayMountConfig.defaultBack().hideFromWearer());
    }
}
