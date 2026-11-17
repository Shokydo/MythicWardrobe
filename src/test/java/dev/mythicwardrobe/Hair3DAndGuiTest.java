package dev.mythicwardrobe;

import dev.mythicwardrobe.config.MenuConfigLoader;
import dev.mythicwardrobe.model.ArmorSection;
import dev.mythicwardrobe.model.Hair3DConfig;
import dev.mythicwardrobe.model.Hair3DConfig.*;
import dev.mythicwardrobe.model.MenuVisualDef.MenuIconSpec;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class Hair3DAndGuiTest {

    @Test
    void loadsBindingsAndPartialHelmetRulesFromYaml() {
        String yamlText = """
                enabled: true
                manual-slot-overrides-binding: true
                trigger-on-2d-hat: true
                default-helmet-list-mode: ALL
                bindings:
                  hair_blonde.png:
                    mythic-item: "PonytailHair3D"
                    color: "225,192,110"
                    helmet-mode: PARTIAL_MODEL
                    helmet-partial-item: "PonytailHair3DHelmetSafe"
                    helmet-list-mode: ALL
                    exceptions:
                      - "CrownHelmet"
                      - "hat:beanie_open.png"
                  hair_brown.png:
                    mythic-item: "SpikyHair3D"
                    helmet-mode: HIDE_ALL
                    helmet-list-mode: WHITELIST
                    helmets:
                      - "TacticalWardenHelmet"
                      - "*_HELMET"
                items:
                  PonytailHair3D:
                    helmet-mode: PARTIAL_MODEL
                    helmet-partial-item: "PonytailHair3DHelmetSafe"
                """;
        Hair3DConfig cfg = Hair3DConfig.load(YamlConfiguration.loadConfiguration(new StringReader(yamlText)));
        assertTrue(cfg.isEnabled());

        // Auto-binding matches with or without .png or subfolder
        Optional<Hair3DRule> blonde = cfg.findBindingFor2DHair("hairs/hair_blonde.png");
        assertTrue(blonde.isPresent());
        assertEquals("PonytailHair3D", blonde.get().mythicItem());
        assertEquals(HelmetBehavior.PARTIAL_MODEL, blonde.get().helmetMode());
        assertEquals("PonytailHair3DHelmetSafe", blonde.get().helmetPartialItem());
        assertEquals(Color.fromRGB(225, 192, 110), Hair3DConfig.parseColorSpec(blonde.get().colorSpec()));

        // Exception helmet keeps full ponytail
        assertFalse(cfg.isAffectedByHelmets(blonde.get(), List.of(HelmetDescriptor.armor("CrownHelmet", "GOLDEN_HELMET"))));
        assertFalse(cfg.isAffectedByHelmets(blonde.get(), List.of(HelmetDescriptor.hat2d("beanie_open.png"))));

        // Any other helmet or 2D hat triggers PARTIAL_MODEL (hides high ponytail, keeps front strands)
        assertTrue(cfg.isAffectedByHelmets(blonde.get(), List.of(HelmetDescriptor.armor("GoldWardenHelmet", "LEATHER_HELMET"))));
        assertTrue(cfg.isAffectedByHelmets(blonde.get(), List.of(HelmetDescriptor.hat2d("beanie_red.png"))));

        // Whitelist mode only hides on matching helmets/wildcards
        Hair3DRule brown = cfg.findBindingFor2DHair("hair_brown").orElseThrow();
        assertTrue(cfg.isAffectedByHelmets(brown, List.of(HelmetDescriptor.armor("TacticalWardenHelmet", "ECHO_SHARD"))));
        assertTrue(cfg.isAffectedByHelmets(brown, List.of(HelmetDescriptor.armor(null, "NETHERITE_HELMET"))));
        assertFalse(cfg.isAffectedByHelmets(brown, List.of(HelmetDescriptor.armor("SomeBandana", "ECHO_SHARD"))));

        // Manual item lookup
        assertTrue(cfg.findRuleForManualItem("PonytailHair3D").isPresent());
        assertTrue(cfg.findRuleForManualItem("SpikyHair3D").isPresent());
    }

    @Test
    void loadsPerMenuAndGlobalEmptySlotsWithCustomModelData() {
        String menusYaml = """
                default-mode: TABBED
                empty-slots:
                  hair_3d:
                    material: ECHO_SHARD
                    custom-model-data: 5001
                    name: "&fПустая 3D-причёска"
                    lore:
                      - "&7Положите причёску или выберите 2D-волосы"
                compact:
                  HEAD:
                    title: "&0Шлем — Монохром"
                    rows: 2
                    filler:
                      enabled: true
                      material: BLACK_STAINED_GLASS_PANE
                      custom-model-data: 9001
                      name: " "
                    slot-positions:
                      hair_3d: 2
                      helmet_cosmetic: 4
                    empty-slots:
                      helmet_cosmetic:
                        material: PAPER
                        custom-model-data: 5002
                        item-model: "mypack:slot_helmet"
                        name: "&fСлот шлема"
                        lore:
                          - "&7Кастомная иконка пустого слота"
                """;
        MenuConfigLoader.ParsedMenusConfig parsed = MenuConfigLoader.load(
                YamlConfiguration.loadConfiguration(new StringReader(menusYaml)));

        var headMenu = parsed.getCompactMenu(ArmorSection.HEAD);
        assertEquals(2, headMenu.rows());
        assertEquals(18, headMenu.inventorySize());
        assertEquals(9001, headMenu.filler().customModelData());

        MenuIconSpec inheritedHair = headMenu.emptySlots().get("hair_3d");
        assertNotNull(inheritedHair);
        assertEquals(Material.ECHO_SHARD, inheritedHair.material());
        assertEquals(5001, inheritedHair.customModelData());

        MenuIconSpec localHelmet = headMenu.emptySlots().get("helmet_cosmetic");
        assertNotNull(localHelmet);
        assertEquals(Material.PAPER, localHelmet.material());
        assertEquals(5002, localHelmet.customModelData());
        assertEquals("mypack:slot_helmet", localHelmet.itemModel());
        assertTrue(parsed.openInventoryOnBack());
    }

    @Test
    void supportsSimpleSwapModelWithoutColorAndBackInventoryFallback() {
        String hairYaml = """
                enabled: true
                bindings:
                  long_hair.png:
                    mythic-item: "LongHair3D"
                    helmet-mode: SWAP_MODEL
                    helmet-model: "LongHairUnderHelmet3D"
                """;
        Hair3DConfig cfg = Hair3DConfig.load(YamlConfiguration.loadConfiguration(new StringReader(hairYaml)));
        Hair3DRule rule = cfg.findBindingFor2DHair("long_hair.png").orElseThrow();
        assertEquals(HelmetBehavior.PARTIAL_MODEL, rule.helmetMode());
        assertEquals("LongHairUnderHelmet3D", rule.helmetPartialItem());
        assertNull(Hair3DConfig.parseColorSpec(rule.colorSpec()));
        assertTrue(cfg.isAffectedByHelmets(rule, List.of(HelmetDescriptor.armor("IronHelmet", "IRON_HELMET"))));
    }
}
