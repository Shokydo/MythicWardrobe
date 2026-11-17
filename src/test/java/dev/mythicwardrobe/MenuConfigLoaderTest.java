package dev.mythicwardrobe;

import dev.mythicwardrobe.config.MenuConfigLoader;
import dev.mythicwardrobe.model.ArmorSection;
import dev.mythicwardrobe.model.MenuMode;
import dev.mythicwardrobe.model.MenuVisualDef.*;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MenuConfigLoaderTest {

    @Test
    void defaultMenusYmlParsesAllThreeModesWithoutCollisions() throws Exception {
        InputStream in = getClass().getClassLoader().getResourceAsStream("menus.yml");
        assertNotNull(in, "menus.yml resource must exist");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));

        MenuConfigLoader.ParsedMenusConfig cfg = MenuConfigLoader.load(yaml);
        assertEquals(MenuMode.COMPACT, cfg.defaultMode());
        assertTrue(cfg.allowPlayerSwitch());

        // 1. Compact hub & 4 separate 1-row armor menus (each with BACK button!)
        assertEquals(1, cfg.compactHub().rows());
        assertEquals(9, cfg.compactHub().inventorySize());
        assertFalse(cfg.compactHub().buttons().isEmpty());
        assertTrue(cfg.compactHub().buttons().stream().anyMatch(b -> b.action() == MenuAction.BACK),
                "compact_hub must have a BACK button");

        for (ArmorSection sec : ArmorSection.values()) {
            MenuLayoutDef compact = cfg.getCompactMenu(sec);
            assertNotNull(compact);
            assertEquals(1, compact.rows(), "Compact menu for " + sec + " should be 1 row");
            assertFalse(compact.slotPositions().isEmpty(), "Compact menu for " + sec + " must define slot positions");
            assertTrue(compact.buttons().stream().anyMatch(b -> b.action() == MenuAction.BACK),
                    "Compact menu for " + sec + " must have a BACK button");
            assertNoSlotButtonOverlap(compact);
        }

        // Verify LEGS has BOTH 2D layers (pants + belt_skin) AND 3D belt (belt_3d)!
        assertTrue(cfg.getCompactMenu(ArmorSection.LEGS).slotPositions().containsKey("pants"));
        assertTrue(cfg.getCompactMenu(ArmorSection.LEGS).slotPositions().containsKey("belt_skin"));
        assertTrue(cfg.getCompactMenu(ArmorSection.LEGS).slotPositions().containsKey("belt_3d"));

        // 2. Tabbed menu
        MenuLayoutDef tabbed = cfg.tabbedBase();
        assertEquals(3, tabbed.rows());
        assertTrue(tabbed.showEquippedIndicators());
        assertEquals(4, cfg.tabbedSectionTitles().size());
        assertTrue(tabbed.buttons().stream().anyMatch(b -> b.action() == MenuAction.BACK),
                "Tabbed menu must have a BACK button");
        boolean hasActiveGlintTab = tabbed.buttons().stream()
                .anyMatch(b -> b.action() == MenuAction.OPEN_HEAD && b.activeIcon() != null && b.activeIcon().glint());
        assertTrue(hasActiveGlintTab, "Tabbed menu should define active glint styling for selected tab");

        // 3. Unified menu — must contain all 21 individually separated slots from slots.yml with zero overlaps!
        InputStream slotsIn = getClass().getClassLoader().getResourceAsStream("slots.yml");
        assertNotNull(slotsIn, "slots.yml resource must exist");
        YamlConfiguration slotsYaml = YamlConfiguration.loadConfiguration(new InputStreamReader(slotsIn, StandardCharsets.UTF_8));
        Set<String> allDefinedSlots = slotsYaml.getConfigurationSection("slots").getKeys(false);
        assertEquals(21, allDefinedSlots.size(), "slots.yml must define all 21 individually separated slots");
        assertTrue(allDefinedSlots.containsAll(Set.of(
                "hat", "mask_skin", "hair_3d", "mask_3d", "helmet_real", "helmet_cosmetic",
                "undershirt", "jacket", "gloves", "chest_real", "chest_cosmetic", "backpack_3d",
                "pants", "belt_skin", "legs_real", "legs_cosmetic", "belt_3d",
                "socks", "boots_skin", "boots_real", "boots_cosmetic"
        )), "All 21 canonical separated slot IDs must exist in slots.yml");

        MenuLayoutDef unified = cfg.unifiedMenu();
        assertEquals(5, unified.rows());
        assertTrue(unified.buttons().stream().anyMatch(b -> b.action() == MenuAction.BACK),
                "Unified menu must have a BACK button");
        assertEquals(allDefinedSlots.size(), unified.slotPositions().size(),
                "Unified menu should map all " + allDefinedSlots.size() + " wardrobe slots from slots.yml");
        for (String slotId : allDefinedSlots) {
            ArmorSection sec = ArmorSection.valueOf(slotsYaml.getString("slots." + slotId + ".section").toUpperCase());
            assertTrue(unified.slotPositions().containsKey(slotId.toLowerCase()),
                    "Unified menu missing slot " + slotId);
            assertTrue(cfg.getTabbedMenu(sec).slotPositions().containsKey(slotId.toLowerCase()),
                    "Tabbed menu missing slot " + slotId);
            assertTrue(cfg.getCompactMenu(sec).slotPositions().containsKey(slotId.toLowerCase()),
                    "Compact menu (" + sec + ") missing slot " + slotId);
        }
        assertNoSlotButtonOverlap(unified);
    }

    @Test
    void supportsPerSectionTabbedOverridesAndLegacyFeetAliases() {
        String tabbedYaml = """
                default-mode: "TABBED"
                tabbed:
                  title: "Base <section>"
                  rows: 3
                  slot-positions:
                    helmet_real: 15
                    helmet_cosmetic: 16
                    boots_real: 14
                    boots_cosmetic: 15
                  buttons:
                    close:
                      slots: [26]
                      action: CLOSE
                      material: BARRIER
                  sections:
                    HEAD:
                      title: "Custom Head Tab"
                      rows: 4
                      slot-positions:
                        helmet_real: 11
                        helmet_cosmetic: 12
                """;
        YamlConfiguration yaml = new YamlConfiguration();
        assertDoesNotThrow(() -> yaml.loadFromString(tabbedYaml));

        MenuConfigLoader.ParsedMenusConfig cfg = MenuConfigLoader.load(yaml);
        assertEquals(MenuMode.TABBED, cfg.defaultMode());
        assertEquals("Custom Head Tab", cfg.getTabbedTitle(ArmorSection.HEAD));
        assertEquals(4, cfg.getTabbedMenu(ArmorSection.HEAD).rows());
        assertEquals(11, cfg.getTabbedMenu(ArmorSection.HEAD).slotPositions().get("helmet_real"));
        assertEquals(12, cfg.getTabbedMenu(ArmorSection.HEAD).slotPositions().get("helmet_cosmetic"));
        assertEquals(1, cfg.getTabbedMenu(ArmorSection.HEAD).buttons().size());
        assertEquals(3, cfg.getTabbedMenu(ArmorSection.FEET).rows());
        assertEquals(14, cfg.getTabbedMenu(ArmorSection.FEET).slotPositions().get("boots_real"));

        // Legacy FEET slot alias bridge (belt_real <-> boots_real, belt_cosmetic <-> boots_cosmetic)
        var legacyReal = new dev.mythicwardrobe.model.ItemWardrobeMeta("belt_real", null, null, null, null, null, null, null, null);
        assertTrue(legacyReal.matchesSlot("boots_real"));
        assertTrue(legacyReal.matchesSlot("belt_real"));
        assertFalse(legacyReal.matchesSlot("boots_cosmetic"));

        var newCosmetic = new dev.mythicwardrobe.model.ItemWardrobeMeta("boots_cosmetic", null, null, null, null, null, null, null, null);
        assertTrue(newCosmetic.matchesSlot("belt_cosmetic"));
        assertTrue(newCosmetic.matchesSlot("boots_cosmetic"));
        assertFalse(newCosmetic.matchesSlot("boots_real"));

        // Verify partial custom Studio layout keeps ONLY explicitly placed slots (no leaked slots!)
        // and verify wearable armor material detection for right-click hand equip blocking
        String partialUnified = """
                default-mode: "UNIFIED"
                unified:
                  title: "&8Полный Гардероб"
                  rows: 5
                  filler:
                    enabled: false
                  slot-positions:
                    mask_skin: 0
                    hat: 1
                    helmet_cosmetic: 3
                    mask_3d: 4
                    jacket: 9
                    undershirt: 10
                    chest_cosmetic: 12
                    backpack_3d: 13
                    pants: 18
                    belt_skin: 19
                    legs_cosmetic: 21
                    belt_3d: 22
                    boots_skin: 27
                    socks: 28
                    boots_cosmetic: 30
                """;
        YamlConfiguration partialYaml = new YamlConfiguration();
        assertDoesNotThrow(() -> partialYaml.loadFromString(partialUnified));
        var partialCfg = MenuConfigLoader.load(partialYaml);
        assertFalse(partialCfg.unifiedMenu().fillerEnabled());
        assertEquals(15, partialCfg.unifiedMenu().slotPositions().size());
        assertNull(partialCfg.unifiedMenu().slotPositions().get("chest_real"));
        assertNull(partialCfg.unifiedMenu().slotPositions().get("hair_3d"));

        // Hand right-click armor blocking material helper
        assertTrue(dev.mythicwardrobe.model.ItemWardrobeMeta.isWearableArmorMaterial(Material.DIAMOND_CHESTPLATE));
        assertTrue(dev.mythicwardrobe.model.ItemWardrobeMeta.isWearableArmorMaterial(Material.LEATHER_BOOTS));
        assertTrue(dev.mythicwardrobe.model.ItemWardrobeMeta.isWearableArmorMaterial(Material.TURTLE_HELMET));
        assertTrue(dev.mythicwardrobe.model.ItemWardrobeMeta.isWearableArmorMaterial(Material.ELYTRA));
        assertFalse(dev.mythicwardrobe.model.ItemWardrobeMeta.isWearableArmorMaterial(Material.PAPER));
        assertFalse(dev.mythicwardrobe.model.ItemWardrobeMeta.isWearableArmorMaterial(Material.CARVED_PUMPKIN));
        assertFalse(dev.mythicwardrobe.model.ItemWardrobeMeta.isWearableArmorMaterial(Material.PLAYER_HEAD));
    }

    @Test
    void customVisualOverridesParseProperly() {
        String customYaml = """
                default-mode: "UNIFIED"
                allow-player-switch: false
                unified:
                  title: "<gold>My Custom UI"
                  rows: 6
                  filler:
                    enabled: true
                    material: BLUE_STAINED_GLASS_PANE
                    name: " "
                    custom-model-data: 777
                    item-model: "mypack:ui_bg"
                    hide-tooltip: true
                  slot-positions:
                    helmet_real: 10
                  buttons:
                    deco:
                      slots: [0, 8]
                      action: NONE
                      material: LEATHER_HELMET
                      name: "<aqua>Decor"
                      color: "#FF5500"
                      glint: true
                      custom-model-data: 123
                      item-model: "mypack:icon_crown"
                """;
        YamlConfiguration yaml = new YamlConfiguration();
        assertDoesNotThrow(() -> yaml.loadFromString(customYaml));

        MenuConfigLoader.ParsedMenusConfig cfg = MenuConfigLoader.load(yaml);
        assertEquals(MenuMode.UNIFIED, cfg.defaultMode());
        assertFalse(cfg.allowPlayerSwitch());

        MenuLayoutDef unified = cfg.unifiedMenu();
        assertEquals("<gold>My Custom UI", unified.title());
        assertEquals(6, unified.rows());
        assertEquals(54, unified.inventorySize());
        assertEquals(Material.BLUE_STAINED_GLASS_PANE, unified.filler().material());
        assertEquals(777, unified.filler().customModelData());
        assertEquals("mypack:ui_bg", unified.filler().itemModel());
        assertTrue(unified.filler().hideTooltip());
        assertEquals(10, unified.slotPositions().get("helmet_real"));

        MenuButtonDef deco = unified.buttons().get(0);
        assertEquals(MenuAction.NONE, deco.action());
        assertEquals(2, deco.slots().size());
        assertEquals("#FF5500", deco.icon().colorHex());
        assertTrue(deco.icon().glint());
        assertEquals(123, deco.icon().customModelData());
        assertEquals("mypack:icon_crown", deco.icon().itemModel());
    }

    @Test
    void backCommandAndRunCommandActionParseProperly() {
        String customYaml = """
                default-mode: "COMPACT"
                back-command: "menu open main"
                compact_hub:
                  title: "<gold>Hub"
                  rows: 1
                  buttons:
                    back_btn:
                      slot: 0
                      action: BACK
                      material: ARROW
                      name: "<yellow>Назад"
                    custom_cmd_btn:
                      slot: 8
                      action: RUN_COMMAND
                      command: "console:say hi {player}"
                      material: COMPASS
                      name: "<aqua>Lobby"
                """;
        YamlConfiguration yaml = new YamlConfiguration();
        assertDoesNotThrow(() -> yaml.loadFromString(customYaml));

        MenuConfigLoader.ParsedMenusConfig cfg = MenuConfigLoader.load(yaml);
        assertEquals("menu open main", cfg.backCommand());

        MenuLayoutDef hub = cfg.compactHub();
        MenuButtonDef backBtn = hub.buttons().stream().filter(b -> b.id().equals("back_btn")).findFirst().orElseThrow();
        assertEquals(MenuAction.BACK, backBtn.action());
        assertNull(backBtn.command());

        MenuButtonDef cmdBtn = hub.buttons().stream().filter(b -> b.id().equals("custom_cmd_btn")).findFirst().orElseThrow();
        assertEquals(MenuAction.RUN_COMMAND, cmdBtn.action());
        assertEquals("console:say hi {player}", cmdBtn.command());
    }

    private static void assertNoSlotButtonOverlap(MenuLayoutDef layout) {
        Set<Integer> usedBySlots = new HashSet<>(layout.slotPositions().values());
        assertEquals(layout.slotPositions().size(), usedBySlots.size(),
                "Duplicate slot coordinates in " + layout.id());
        for (MenuButtonDef btn : layout.buttons()) {
            for (int pos : btn.slots()) {
                assertFalse(usedBySlots.contains(pos),
                        "Button '" + btn.id() + "' overlaps interactive slot at index " + pos + " in " + layout.id());
            }
        }
    }
}
