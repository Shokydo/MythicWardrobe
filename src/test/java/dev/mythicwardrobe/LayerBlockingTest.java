package dev.mythicwardrobe;

import dev.mythicwardrobe.config.WardrobeConfig;
import dev.mythicwardrobe.manager.WardrobeManager;
import dev.mythicwardrobe.model.*;
import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class LayerBlockingTest {

    @Test
    void parsesBlockedTargetsAndBehaviorModes() {
        Set<String> csv = ItemWardrobeMeta.parseBlockedTargetsCsv("pants, undershirt; belt_skin  gloves");
        assertEquals(Set.of("pants", "undershirt", "belt_skin", "gloves"), csv);

        ItemWardrobeMeta meta = new ItemWardrobeMeta(
                "jacket", "hazmat_suit.png", null, "", "", "WardrobeHazmatSuit",
                ItemWardrobeMeta.normalizeBlockedTargets(List.of("pants,undershirt", "belt_skin", "gloves")),
                BlockBehaviorMode.UNEQUIP
        );
        assertTrue(meta.hasBlocks());
        assertEquals(4, meta.blockedTargets().size());
        assertEquals(BlockBehaviorMode.UNEQUIP, meta.blockMode());

        assertEquals(BlockBehaviorMode.UNEQUIP, BlockBehaviorMode.parseOrNull("unequip"));
        assertEquals(BlockBehaviorMode.PREVENT, BlockBehaviorMode.parseOrNull("STRICT"));
        assertEquals(BlockBehaviorMode.HIDE, BlockBehaviorMode.parseOrNull("suppress"));
        assertNull(BlockBehaviorMode.parseOrNull("invalid"));
    }

    @Test
    void expandsShortcutsAliasesAndNeverBlocksSelfOrBodySkin() {
        Map<String, WardrobeSlotDef> slots = new LinkedHashMap<>();
        slots.put("jacket", makeSkinSlot("jacket", ArmorSection.CHEST));
        slots.put("undershirt", makeSkinSlot("undershirt", ArmorSection.CHEST));
        slots.put("pants", makeSkinSlot("pants", ArmorSection.LEGS));
        slots.put("belt_skin", makeSkinSlot("belt_skin", ArmorSection.LEGS));
        slots.put("gloves", makeSkinSlot("gloves", ArmorSection.CHEST));
        slots.put("boots_skin", makeSkinSlot("boots_skin", ArmorSection.FEET));
        slots.put("backpack_3d", new WardrobeSlotDef(
                "backpack_3d", ArmorSection.CHEST, SlotKind.ARMOR_OVERLAY, EquipmentSlot.CHEST,
                15, 0, "", "Backpack", Material.BARREL, List.of(), Set.of(), DisplayMountConfig.defaultBack()
        ));

        Map<String, SkinLayerDef> layers = new LinkedHashMap<>();
        layers.put("body_skin", new SkinLayerDef("body_skin", 0, "body_skin", true, Set.of(), List.of("body")));
        layers.put("pants", new SkinLayerDef("pants", 15, "pants", false, Set.of("RIGHT_LEG_OUTER", "LEFT_LEG_OUTER"), List.of()));
        layers.put("belt_skin", new SkinLayerDef("belt_skin", 18, "belts", false, Set.of(), List.of("belt")));
        layers.put("jacket", new SkinLayerDef("jacket", 30, "jackets", false, Set.of("BODY_OUTER"), List.of("jackets")));

        Map<String, String> aliases = Map.of("jackets", "jacket", "belt", "belt_skin", "body", "body_skin");

        // 1. Hazmat suit in `jacket` blocking `all_clothing` + `all_3d`
        Set<String> expanded = WardrobeConfig.expandBlockedTargets(
                "jackets", List.of("all_clothing", "all_3d"), slots, layers, aliases);
        assertFalse(expanded.contains("jacket"), "Source slot must never block itself");
        assertFalse(expanded.contains("body_skin"), "Wildcard must never block body_skin");
        assertTrue(expanded.contains("undershirt"));
        assertTrue(expanded.contains("pants"));
        assertTrue(expanded.contains("belt_skin"));
        assertTrue(expanded.contains("gloves"));
        assertTrue(expanded.contains("boots_skin"));
        assertTrue(expanded.contains("backpack_3d"));

        // 2. Alias resolution on individual target (`belt` -> `belt_skin`)
        Set<String> aliasExpanded = WardrobeConfig.expandBlockedTargets(
                "jacket", List.of("pants", "belt"), slots, layers, aliases);
        assertEquals(Set.of("pants", "belt_skin"), aliasExpanded);

        // 3. `suit` / `all_body` shortcut
        Set<String> suitExpanded = WardrobeConfig.expandBlockedTargets(
                "jacket", List.of("suit"), slots, layers, aliases);
        assertTrue(suitExpanded.containsAll(List.of("undershirt", "pants", "belt_skin", "socks", "gloves", "boots_skin")));
        assertFalse(suitExpanded.contains("jacket"));
    }

    @Test
    void skillLayerBlocksClearAutomaticallyOnUnwardrobe() {
        PlayerWardrobeState state = new PlayerWardrobeState(UUID.randomUUID());
        state.setSkillLayer("jacket", "hazmat_suit.png");
        state.setSkillLayerBlocks("jacket", Set.of("pants", "undershirt", "belt_skin"));
        assertEquals(Set.of("pants", "undershirt", "belt_skin"), state.getSkillLayerBlocks("jacket"));

        int removed = WardrobeManager.applySkillLayerRemoval(state, false, List.of("jacket"), "hazmat_suit.png");
        assertEquals(1, removed);
        assertNull(state.getSkillLayer("jacket"));
        assertTrue(state.getSkillLayerBlocks("jacket").isEmpty(), "Removing blocking layer must release its blocks");
    }

    @Test
    void hazmatSuitInheritsClearOuterRegionsFromBlockedLayers() {
        Map<String, SkinLayerDef> layers = Map.of(
                "pants", new SkinLayerDef("pants", 15, "pants", false, Set.of("RIGHT_LEG_OUTER", "LEFT_LEG_OUTER"), List.of()),
                "hat", new SkinLayerDef("hat", 70, "hats", false, Set.of("HEAD_OUTER"), List.of()),
                "jacket", new SkinLayerDef("jacket", 30, "jackets", false, Set.of("BODY_OUTER"), List.of())
        );
        Map<String, WardrobeManager.SlotBlockInfo> activeBlocked = Map.of(
                "pants", new WardrobeManager.SlotBlockInfo("jacket", "Костюм химзащиты", BlockBehaviorMode.UNEQUIP),
                "hat", new WardrobeManager.SlotBlockInfo("jacket", "Костюм химзащиты", BlockBehaviorMode.UNEQUIP)
        );

        Set<String> inherited = WardrobeManager.computeInheritedClearOuterRegions("jacket", activeBlocked, layers);
        assertEquals(Set.of("RIGHT_LEG_OUTER", "LEFT_LEG_OUTER", "HEAD_OUTER"), inherited);
    }

    private static WardrobeSlotDef makeSkinSlot(String id, ArmorSection sec) {
        return new WardrobeSlotDef(
                id, sec, SlotKind.SKIN_LAYER, null, 10, 20, id, id,
                Material.LEATHER_CHESTPLATE, List.of(), Set.of(), null
        );
    }
}
