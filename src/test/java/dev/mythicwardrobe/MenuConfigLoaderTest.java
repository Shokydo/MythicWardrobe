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

        // 3. Unified menu — must contain every single slot defined in slots.yml with zero overlaps!
        InputStream slotsIn = getClass().getClassLoader().getResourceAsStream("slots.yml");
        assertNotNull(slotsIn, "slots.yml resource must exist");
        YamlConfiguration slotsYaml = YamlConfiguration.loadConfiguration(new InputStreamReader(slotsIn, StandardCharsets.UTF_8));
        Set<String> allDefinedSlots = slotsYaml.getConfigurationSection("slots").getKeys(false);

        MenuLayoutDef unified = cfg.unifiedMenu();
        assertEquals(5, unified.rows());
        assertTrue(unified.buttons().stream().anyMatch(b -> b.action() == MenuAction.BACK),
                "Unified menu must have a BACK button");
        assertEquals(allDefinedSlots.size(), unified.slotPositions().size(),
                "Unified menu should map all " + allDefinedSlots.size() + " wardrobe slots from slots.yml");
        for (String slotId : allDefinedSlots) {
            assertTrue(unified.slotPositions().containsKey(slotId.toLowerCase()),
                    "Unified menu missing slot " + slotId);
            assertTrue(tabbed.slotPositions().containsKey(slotId.toLowerCase()),
                    "Tabbed menu missing slot " + slotId);
        }
        assertNoSlotButtonOverlap(unified);
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
