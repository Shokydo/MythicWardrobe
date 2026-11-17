package dev.mythicwardrobe;

import dev.mythicwardrobe.model.*;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CosmeticVariantConfigTest {

    private static WardrobeSlotDef slot(String id, ArmorSection section, SlotKind kind) {
        return new WardrobeSlotDef(
                id,
                section,
                kind,
                section.getEquipmentSlot(),
                10,
                10,
                id,
                id,
                Material.GRAY_STAINED_GLASS_PANE,
                List.of(),
                Set.of(),
                DisplayMountConfig.defaultBack()
        );
    }

    @Test
    void singlePaintItemAdaptsToLightAndHeavyMilitaryVests() {
        String yamlText = """
                enabled: true
                skins:
                  white_vest_paint:
                    cosmetic-item: "WhiteVestPaint"
                    allowed-slots:
                      - "chest_cosmetic"
                    unmatched-mode: "KEEP_REAL"
                    empty-armor-mode: "KEEP_EMPTY"
                    variants:
                      light:
                        match-armor: ["LightMilitaryVest", "LightMilitaryVest_Mk2"]
                        result-mythic-item: "LightMilitaryVest_White"
                      heavy:
                        match-armor: ["HeavyMilitaryVest"]
                        result-mythic-item: "HeavyMilitaryVest_White"
                """;
        CosmeticVariantConfig cfg = CosmeticVariantConfig.load(YamlConfiguration.loadConfiguration(new StringReader(yamlText)));

        // 1. Light military vest -> LightMilitaryVest_White
        var r1 = cfg.resolve("WhiteVestPaint", null, EquipmentSlot.CHEST,
                CosmeticVariantConfig.ArmorDescriptor.of("LightMilitaryVest", "LEATHER_CHESTPLATE"));
        assertEquals(CosmeticVariantConfig.ResolutionAction.APPLY_VARIANT, r1.action());
        assertEquals("LightMilitaryVest_White", r1.mythicItemId());

        // 2. Mk2 light vest -> also LightMilitaryVest_White
        var r2 = cfg.resolve("WhiteVestPaint", null, EquipmentSlot.CHEST,
                CosmeticVariantConfig.ArmorDescriptor.of("LightMilitaryVest_Mk2", "NETHERITE_CHESTPLATE"));
        assertEquals(CosmeticVariantConfig.ResolutionAction.APPLY_VARIANT, r2.action());
        assertEquals("LightMilitaryVest_White", r2.mythicItemId());

        // 3. Heavy military vest -> HeavyMilitaryVest_White (same 1 cosmetic item!)
        var r3 = cfg.resolve("WhiteVestPaint", null, EquipmentSlot.CHEST,
                CosmeticVariantConfig.ArmorDescriptor.of("HeavyMilitaryVest", "NETHERITE_CHESTPLATE"));
        assertEquals(CosmeticVariantConfig.ResolutionAction.APPLY_VARIANT, r3.action());
        assertEquals("HeavyMilitaryVest_White", r3.mythicItemId());

        // 4. Empty real armor slot -> KEEP_REAL (do NOT equip WHITE_DYE on bare torso)
        var rEmpty = cfg.resolve("WhiteVestPaint", null, EquipmentSlot.CHEST, CosmeticVariantConfig.ArmorDescriptor.EMPTY);
        assertEquals(CosmeticVariantConfig.ResolutionAction.KEEP_REAL, rEmpty.action());

        // 5. Unmatched armor -> KEEP_REAL (leave iron chestplate untouched)
        var rUnmatched = cfg.resolve("WhiteVestPaint", null, EquipmentSlot.CHEST,
                CosmeticVariantConfig.ArmorDescriptor.of(null, "IRON_CHESTPLATE"));
        assertEquals(CosmeticVariantConfig.ResolutionAction.KEEP_REAL, rUnmatched.action());

        // 6. Regular non-multimodal cosmetic item -> USE_SELF
        var rRegular = cfg.resolve("GoldWardenChestplate", null, EquipmentSlot.CHEST,
                CosmeticVariantConfig.ArmorDescriptor.of("HeavyMilitaryVest", "NETHERITE_CHESTPLATE"));
        assertEquals(CosmeticVariantConfig.ResolutionAction.USE_SELF, rRegular.action());
    }

    @Test
    void universalCamoWorksAcrossAllFourArmorSlotsWithoutCrossMatching() {
        String yamlText = """
                enabled: true
                skins:
                  universal_white:
                    cosmetic-item: "WhiteUniversalCamo"
                    allowed-slots: [helmet_cosmetic, chest_cosmetic, legs_cosmetic, boots_cosmetic]
                    variants:
                      head_heavy:
                        equipment-slot: HEAD
                        match-armor: ["HeavyMilitary*"]
                        result-mythic-item: "HeavyMilitaryHelmet_White"
                      chest_heavy:
                        equipment-slot: CHEST
                        match-armor: ["HeavyMilitary*"]
                        result-mythic-item: "HeavyMilitaryVest_White"
                """;
        CosmeticVariantConfig cfg = CosmeticVariantConfig.load(YamlConfiguration.loadConfiguration(new StringReader(yamlText)));

        var headRes = cfg.resolve("WhiteUniversalCamo", null, EquipmentSlot.HEAD,
                CosmeticVariantConfig.ArmorDescriptor.of("HeavyMilitaryHelmet", "LEATHER_HELMET"));
        assertEquals("HeavyMilitaryHelmet_White", headRes.mythicItemId());

        var chestRes = cfg.resolve("WhiteUniversalCamo", null, EquipmentSlot.CHEST,
                CosmeticVariantConfig.ArmorDescriptor.of("HeavyMilitaryVest", "LEATHER_CHESTPLATE"));
        assertEquals("HeavyMilitaryVest_White", chestRes.mythicItemId());

        // Legs has no rule -> KEEP_REAL
        var legsRes = cfg.resolve("WhiteUniversalCamo", null, EquipmentSlot.LEGS,
                CosmeticVariantConfig.ArmorDescriptor.of("HeavyMilitaryLegs", "LEATHER_LEGGINGS"));
        assertEquals(CosmeticVariantConfig.ResolutionAction.KEEP_REAL, legsRes.action());
    }

    @Test
    void fallbackAndEmptyModesWorkAsConfigured() {
        String yamlText = """
                enabled: true
                skins:
                  adaptive_skin:
                    cosmetic-item: "AdaptiveSkin"
                    unmatched-mode: "FALLBACK_MODEL"
                    empty-armor-mode: "FALLBACK_MODEL"
                    fallback-mythic-item: "DefaultArmorSkin"
                    empty-armor-mythic-item: "LightTunicSkin"
                    variants:
                      LightMilitaryVest: LightMilitaryVest_White
                """;
        CosmeticVariantConfig cfg = CosmeticVariantConfig.load(YamlConfiguration.loadConfiguration(new StringReader(yamlText)));

        var emptyRes = cfg.resolve("AdaptiveSkin", null, EquipmentSlot.CHEST, CosmeticVariantConfig.ArmorDescriptor.EMPTY);
        assertEquals(CosmeticVariantConfig.ResolutionAction.APPLY_MYTHIC_ITEM, emptyRes.action());
        assertEquals("LightTunicSkin", emptyRes.mythicItemId());

        var unmatchedRes = cfg.resolve("AdaptiveSkin", null, EquipmentSlot.CHEST,
                CosmeticVariantConfig.ArmorDescriptor.of("SomeOtherVest", "DIAMOND_CHESTPLATE"));
        assertEquals(CosmeticVariantConfig.ResolutionAction.APPLY_MYTHIC_ITEM, unmatchedRes.action());
        assertEquals("DefaultArmorSkin", unmatchedRes.mythicItemId());

        var matchedRes = cfg.resolve("AdaptiveSkin", null, EquipmentSlot.CHEST,
                CosmeticVariantConfig.ArmorDescriptor.of("LightMilitaryVest", "IRON_CHESTPLATE"));
        assertEquals(CosmeticVariantConfig.ResolutionAction.APPLY_VARIANT, matchedRes.action());
        assertEquals("LightMilitaryVest_White", matchedRes.mythicItemId());
    }

    @Test
    void matchesMaterialsCustomModelDataAndEquippableModelWithDyeAndModelMutations() {
        String yamlText = """
                enabled: true
                skins:
                  advanced_paint:
                    cosmetic-item: "AdvancedPaint"
                    variants:
                      by_model:
                        match-models: ["mythicarmor:specops_*"]
                        equippable-model: "mythicarmor:specops_white"
                        dye-color: "#F5F5F5"
                      by_cmd:
                        match-custom-model-data: [4201, 4202]
                        item-model: "my_pack:white_helmet"
                        custom-model-data: 9901
                      by_mat:
                        match-materials: ["*_CHESTPLATE"]
                        dye-color: "240,245,250"
                """;
        CosmeticVariantConfig cfg = CosmeticVariantConfig.load(YamlConfiguration.loadConfiguration(new StringReader(yamlText)));

        var m1 = cfg.resolve("AdvancedPaint", null, EquipmentSlot.CHEST,
                new CosmeticVariantConfig.ArmorDescriptor(null, "LEATHER_CHESTPLATE", null, null, "mythicarmor:specops_heavy"));
        assertEquals(CosmeticVariantConfig.ResolutionAction.APPLY_VARIANT, m1.action());
        assertEquals("mythicarmor:specops_white", m1.matchedVariant().equippableModel());
        Color c1 = CosmeticVariantConfig.parseColorSpec(m1.matchedVariant().dyeColor());
        assertNotNull(c1);
        assertEquals(Color.fromRGB(0xF5, 0xF5, 0xF5), c1);

        var m2 = cfg.resolve("AdvancedPaint", null, EquipmentSlot.HEAD,
                new CosmeticVariantConfig.ArmorDescriptor(null, "CARVED_PUMPKIN", 4202, null, null));
        assertEquals(CosmeticVariantConfig.ResolutionAction.APPLY_VARIANT, m2.action());
        assertEquals("my_pack:white_helmet", m2.matchedVariant().itemModel());
        assertEquals(9901, m2.matchedVariant().customModelData());

        var m3 = cfg.resolve("AdvancedPaint", null, EquipmentSlot.CHEST,
                new CosmeticVariantConfig.ArmorDescriptor(null, "DIAMOND_CHESTPLATE", null, null, null));
        assertEquals(CosmeticVariantConfig.ResolutionAction.APPLY_VARIANT, m3.action());
        assertEquals(Color.fromRGB(240, 245, 250), CosmeticVariantConfig.parseColorSpec(m3.matchedVariant().dyeColor()));
    }

    @Test
    void shorthandVariantsAndInlineSkinFallbackWork() {
        String itemYaml = """
                slot: chest_cosmetic
                unmatched-mode: KEEP_REAL
                empty-armor-mode: KEEP_EMPTY
                variants:
                  LightMilitaryVest: LightMilitaryVest_White
                  HeavyMilitaryVest: HeavyMilitaryVest_White
                  "material:LEATHER_CHESTPLATE": "#FAFAFA"
                """;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(new StringReader(itemYaml));
        CosmeticVariantConfig.CosmeticSkinDef inlineDef = CosmeticVariantConfig.parseSkinSection("WhiteVestPaint", yml, "WhiteVestPaint");
        assertNotNull(inlineDef);
        assertEquals(3, inlineDef.variants().size());

        CosmeticVariantConfig emptyGlobal = CosmeticVariantConfig.empty();
        var r1 = emptyGlobal.resolve("WhiteVestPaint", inlineDef, EquipmentSlot.CHEST,
                CosmeticVariantConfig.ArmorDescriptor.of("HeavyMilitaryVest", "NETHERITE_CHESTPLATE"));
        assertEquals(CosmeticVariantConfig.ResolutionAction.APPLY_VARIANT, r1.action());
        assertEquals("HeavyMilitaryVest_White", r1.mythicItemId());

        var r2 = emptyGlobal.resolve("WhiteVestPaint", inlineDef, EquipmentSlot.CHEST,
                CosmeticVariantConfig.ArmorDescriptor.of(null, "LEATHER_CHESTPLATE"));
        assertEquals(CosmeticVariantConfig.ResolutionAction.APPLY_VARIANT, r2.action());
        assertEquals("#FAFAFA", r2.matchedVariant().dyeColor());
    }

    @Test
    void allowedSlotsValidationProtectsRealSlotsAndAllowsPaintInCosmeticSlots() {
        String yamlText = """
                enabled: true
                skins:
                  vest_paint:
                    cosmetic-item: "WhiteVestPaint"
                    allowed-slots: ["chest_cosmetic"]
                    variants:
                      LightMilitaryVest: LightMilitaryVest_White
                  auto_slot_paint:
                    cosmetic-item: "AutoChestPaint"
                    variants:
                      chest_only:
                        equipment-slot: CHEST
                        match-armor: ["LightMilitaryVest"]
                        result-mythic-item: "LightMilitaryVest_White"
                """;
        CosmeticVariantConfig cfg = CosmeticVariantConfig.load(YamlConfiguration.loadConfiguration(new StringReader(yamlText)));

        WardrobeSlotDef chestCos = slot("chest_cosmetic", ArmorSection.CHEST, SlotKind.ARMOR_COSMETIC);
        WardrobeSlotDef chestReal = slot("chest_real", ArmorSection.CHEST, SlotKind.ARMOR_REAL);
        WardrobeSlotDef helmCos = slot("helmet_cosmetic", ArmorSection.HEAD, SlotKind.ARMOR_COSMETIC);

        assertTrue(cfg.canPlaceInSlot("WhiteVestPaint", null, chestCos));
        assertFalse(cfg.canPlaceInSlot("WhiteVestPaint", null, chestReal));
        assertFalse(cfg.canPlaceInSlot("WhiteVestPaint", null, helmCos));

        assertTrue(cfg.canPlaceInSlot("AutoChestPaint", null, chestCos));
        assertFalse(cfg.canPlaceInSlot("AutoChestPaint", null, chestReal));
        assertFalse(cfg.canPlaceInSlot("AutoChestPaint", null, helmCos));
    }

    @Test
    void bundledDefaultCosmeticVariantsYamlLoadsCleanly() {
        var stream = getClass().getClassLoader().getResourceAsStream("cosmetic-variants.yml");
        assertNotNull(stream, "cosmetic-variants.yml resource must be packaged in JAR");
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        CosmeticVariantConfig cfg = CosmeticVariantConfig.load(yml);
        assertTrue(cfg.isEnabled());
        assertTrue(cfg.getSkins().size() >= 2, "Default config should include at least 2 example skins");

        var resLight = cfg.resolve("WhiteVestPaint", null, EquipmentSlot.CHEST,
                CosmeticVariantConfig.ArmorDescriptor.of("LightMilitaryVest", "LEATHER_CHESTPLATE"));
        assertEquals("LightMilitaryVest_White", resLight.mythicItemId());

        var resHeavy = cfg.resolve("WhiteVestPaint", null, EquipmentSlot.CHEST,
                CosmeticVariantConfig.ArmorDescriptor.of("HeavyMilitaryVest", "NETHERITE_CHESTPLATE"));
        assertEquals("HeavyMilitaryVest_White", resHeavy.mythicItemId());
    }

    @Test
    void disabledConfigAlwaysFallsBackToUseSelf() {
        String yamlText = """
                enabled: false
                skins:
                  white_vest_paint:
                    cosmetic-item: "WhiteVestPaint"
                    variants:
                      LightMilitaryVest: LightMilitaryVest_White
                """;
        CosmeticVariantConfig cfg = CosmeticVariantConfig.load(YamlConfiguration.loadConfiguration(new StringReader(yamlText)));
        var res = cfg.resolve("WhiteVestPaint", null, EquipmentSlot.CHEST,
                CosmeticVariantConfig.ArmorDescriptor.of("LightMilitaryVest", "LEATHER_CHESTPLATE"));
        assertEquals(CosmeticVariantConfig.ResolutionAction.USE_SELF, res.action());
    }
}
