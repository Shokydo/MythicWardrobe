package dev.mythicwardrobe.config;

import dev.mythicwardrobe.model.ArmorSection;
import dev.mythicwardrobe.model.MenuMode;
import dev.mythicwardrobe.model.MenuVisualDef.*;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.*;

/**
 * Pure YAML parser for menus.yml supporting full visual customization
 * (title, rows 1..6, filler, buttons/decorations, icons with custom-model-data/item-model/glint/hide-tooltip,
 * custom slot positions, and switchable COMPACT / TABBED / UNIFIED modes).
 */
public final class MenuConfigLoader {

    public record ParsedMenusConfig(
            MenuMode defaultMode,
            boolean allowPlayerSwitch,
            String backCommand,
            String backFallback,
            String inventoryTitle,
            MenuLayoutDef compactHub,
            Map<ArmorSection, MenuLayoutDef> compactMenus,
            MenuLayoutDef tabbedBase,
            Map<ArmorSection, String> tabbedSectionTitles,
            Map<ArmorSection, MenuLayoutDef> tabbedSections,
            MenuLayoutDef unifiedMenu
    ) {
        public ParsedMenusConfig(
                MenuMode defaultMode,
                boolean allowPlayerSwitch,
                String backCommand,
                String backFallback,
                String inventoryTitle,
                MenuLayoutDef compactHub,
                Map<ArmorSection, MenuLayoutDef> compactMenus,
                MenuLayoutDef tabbedBase,
                Map<ArmorSection, String> tabbedSectionTitles,
                MenuLayoutDef unifiedMenu
        ) {
            this(defaultMode, allowPlayerSwitch, backCommand, backFallback, inventoryTitle,
                    compactHub, compactMenus, tabbedBase, tabbedSectionTitles, Map.of(), unifiedMenu);
        }

        public ParsedMenusConfig(
                MenuMode defaultMode,
                boolean allowPlayerSwitch,
                String backCommand,
                MenuLayoutDef compactHub,
                Map<ArmorSection, MenuLayoutDef> compactMenus,
                MenuLayoutDef tabbedBase,
                Map<ArmorSection, String> tabbedSectionTitles,
                MenuLayoutDef unifiedMenu
        ) {
            this(defaultMode, allowPlayerSwitch, backCommand, "INVENTORY",
                    "<#1e293b>Инвентарь и Экипировка",
                    compactHub, compactMenus, tabbedBase, tabbedSectionTitles, Map.of(), unifiedMenu);
        }

        public boolean openInventoryOnBack() {
            return backFallback == null || !"CLOSE".equalsIgnoreCase(backFallback.trim());
        }

        public MenuLayoutDef getCompactMenu(ArmorSection section) {
            return compactMenus.getOrDefault(section, compactHub);
        }

        /**
         * Returns the per-section TABBED layout if configured under `tabbed.sections.<SECTION>`,
         * otherwise falls back to the shared `tabbedBase` layout.
         */
        public MenuLayoutDef getTabbedMenu(ArmorSection section) {
            if (section != null && tabbedSections != null && tabbedSections.containsKey(section)) {
                return tabbedSections.get(section);
            }
            return tabbedBase;
        }

        public String getTabbedTitle(ArmorSection section) {
            if (section != null && tabbedSections != null && tabbedSections.containsKey(section)) {
                MenuLayoutDef secLayout = tabbedSections.get(section);
                if (secLayout.title() != null && !secLayout.title().isBlank()) {
                    return secLayout.title().replace("<section>", section.getRussianTitle());
                }
            }
            if (section != null && tabbedSectionTitles.containsKey(section)) {
                return tabbedSectionTitles.get(section);
            }
            return tabbedBase.title().replace("<section>", section != null ? section.getRussianTitle() : "");
        }
    }

    private MenuConfigLoader() {
    }

    public static ParsedMenusConfig load(YamlConfiguration yaml) {
        MenuMode defaultMode = MenuMode.fromString(yaml.getString("default-mode", "COMPACT"), MenuMode.COMPACT);
        boolean allowSwitch = yaml.getBoolean("allow-player-switch", true);
        String backCommand = yaml.getString("back-command", "");
        if (backCommand != null) backCommand = backCommand.trim();
        String backFallback = yaml.getString("back-fallback", "INVENTORY");
        if (backFallback != null) backFallback = backFallback.trim().toUpperCase(Locale.ROOT);
        String inventoryTitle = yaml.getString("inventory-title", "<#1e293b>Инвентарь и Экипировка");

        Map<String, MenuIconSpec> globalEmptySlots = parseEmptySlots(yaml.getConfigurationSection("empty-slots"));

        MenuLayoutDef compactHub = parseLayout(
                "compact_hub",
                yaml.getConfigurationSection("compact_hub"),
                "<#38bdf8>Гардероб <dark_gray>▪ <gray>Выберите слот",
                1,
                false,
                globalEmptySlots
        );

        Map<ArmorSection, MenuLayoutDef> compactMenus = new EnumMap<>(ArmorSection.class);
        ConfigurationSection compactRoot = yaml.getConfigurationSection("compact");
        for (ArmorSection sec : ArmorSection.values()) {
            ConfigurationSection secCfg = compactRoot != null ? compactRoot.getConfigurationSection(sec.name()) : null;
            if (secCfg == null && compactRoot != null) {
                secCfg = compactRoot.getConfigurationSection(sec.name().toLowerCase(Locale.ROOT));
            }
            compactMenus.put(sec, parseLayout(
                    "compact_" + sec.name().toLowerCase(Locale.ROOT),
                    secCfg,
                    "<#38bdf8>" + sec.getRussianTitle(),
                    1,
                    false,
                    globalEmptySlots
            ));
        }

        ConfigurationSection tabbedSec = yaml.getConfigurationSection("tabbed");
        MenuLayoutDef tabbedBase = parseLayout(
                "tabbed",
                tabbedSec,
                "<#1e293b>Гардероб <dark_gray>▪ <aqua><section>",
                3,
                true,
                globalEmptySlots
        );

        Map<ArmorSection, String> tabbedTitles = new EnumMap<>(ArmorSection.class);
        if (tabbedSec != null && tabbedSec.isConfigurationSection("section-titles")) {
            ConfigurationSection st = tabbedSec.getConfigurationSection("section-titles");
            for (ArmorSection sec : ArmorSection.values()) {
                String t = st.getString(sec.name());
                if (t == null) t = st.getString(sec.name().toLowerCase(Locale.ROOT));
                if (t != null && !t.isBlank()) {
                    tabbedTitles.put(sec, t);
                }
            }
        }

        // Optional fully-separated per-tab layouts under `tabbed.sections.<HEAD|CHEST|LEGS|FEET>`
        Map<ArmorSection, MenuLayoutDef> tabbedSections = new EnumMap<>(ArmorSection.class);
        if (tabbedSec != null && tabbedSec.isConfigurationSection("sections")) {
            ConfigurationSection secsRoot = tabbedSec.getConfigurationSection("sections");
            for (ArmorSection sec : ArmorSection.values()) {
                ConfigurationSection sCfg = secsRoot.getConfigurationSection(sec.name());
                if (sCfg == null) sCfg = secsRoot.getConfigurationSection(sec.name().toLowerCase(Locale.ROOT));
                if (sCfg == null) continue;
                String secTitle = sCfg.getString("title", tabbedTitles.getOrDefault(sec, tabbedBase.title()));
                MenuLayoutDef parsedSec = parseLayout(
                        "tabbed_" + sec.name().toLowerCase(Locale.ROOT),
                        sCfg,
                        secTitle,
                        tabbedBase.rows(),
                        tabbedBase.showEquippedIndicators(),
                        tabbedBase.emptySlots()
                );
                // Inherit base buttons/positions ONLY when section override does not declare its own!
                List<MenuButtonDef> effButtons = sCfg.isConfigurationSection("buttons") ? parsedSec.buttons() : tabbedBase.buttons();
                Map<String, Integer> effPositions = sCfg.isConfigurationSection("slot-positions")
                        ? parsedSec.slotPositions()
                        : tabbedBase.slotPositions();
                tabbedSections.put(sec, new MenuLayoutDef(
                        parsedSec.id(),
                        parsedSec.title(),
                        parsedSec.rows(),
                        sCfg.isConfigurationSection("filler") ? parsedSec.fillerEnabled() : tabbedBase.fillerEnabled(),
                        sCfg.isConfigurationSection("filler") ? parsedSec.filler() : tabbedBase.filler(),
                        sCfg.contains("show-equipped-indicators") ? parsedSec.showEquippedIndicators() : tabbedBase.showEquippedIndicators(),
                        Collections.unmodifiableMap(new LinkedHashMap<>(effPositions)),
                        effButtons,
                        parsedSec.emptySlots()
                ));
            }
        }

        MenuLayoutDef unified = parseLayout(
                "unified",
                yaml.getConfigurationSection("unified"),
                "<#0f172a>Полный Гардероб и Броня",
                5,
                false,
                globalEmptySlots
        );

        return new ParsedMenusConfig(
                defaultMode,
                allowSwitch,
                backCommand != null ? backCommand : "",
                backFallback != null && !backFallback.isBlank() ? backFallback : "INVENTORY",
                inventoryTitle != null && !inventoryTitle.isBlank() ? inventoryTitle : "<#1e293b>Инвентарь и Экипировка",
                compactHub,
                Collections.unmodifiableMap(compactMenus),
                tabbedBase,
                Collections.unmodifiableMap(tabbedTitles),
                Collections.unmodifiableMap(tabbedSections),
                unified
        );
    }

    public static MenuLayoutDef parseLayout(String id, ConfigurationSection sec, String defTitle, int defRows, boolean defIndicators) {
        return parseLayout(id, sec, defTitle, defRows, defIndicators, Map.of());
    }

    public static MenuLayoutDef parseLayout(String id, ConfigurationSection sec, String defTitle, int defRows, boolean defIndicators, Map<String, MenuIconSpec> inheritedEmptySlots) {
        if (sec == null) {
            return new MenuLayoutDef(
                    id,
                    defTitle,
                    clampRows(defRows),
                    true,
                    MenuIconSpec.filler(Material.GRAY_STAINED_GLASS_PANE),
                    defIndicators,
                    Map.of(),
                    List.of(),
                    inheritedEmptySlots == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(inheritedEmptySlots))
            );
        }

        String title = sec.getString("title", defTitle);
        int rows = clampRows(sec.getInt("rows", defRows));
        boolean showIndicators = sec.getBoolean("show-equipped-indicators", defIndicators);

        boolean fillerEnabled = true;
        MenuIconSpec fillerSpec = MenuIconSpec.filler(Material.GRAY_STAINED_GLASS_PANE);
        ConfigurationSection fillerSec = sec.getConfigurationSection("filler");
        if (fillerSec != null) {
            fillerEnabled = fillerSec.getBoolean("enabled", true);
            fillerSpec = parseIcon(fillerSec, Material.GRAY_STAINED_GLASS_PANE, " ", true);
        }

        Map<String, Integer> slotPositions = new LinkedHashMap<>();
        ConfigurationSection posSec = sec.getConfigurationSection("slot-positions");
        if (posSec != null) {
            int maxSlot = rows * 9 - 1;
            for (String slotKey : posSec.getKeys(false)) {
                int pos = posSec.getInt(slotKey, -1);
                if (pos >= 0 && pos <= maxSlot) {
                    slotPositions.put(slotKey.trim().toLowerCase(Locale.ROOT), pos);
                }
            }
        }

        List<MenuButtonDef> buttons = new ArrayList<>();
        ConfigurationSection buttonsSec = sec.getConfigurationSection("buttons");
        if (buttonsSec != null) {
            int maxSlot = rows * 9 - 1;
            for (String btnId : buttonsSec.getKeys(false)) {
                ConfigurationSection bSec = buttonsSec.getConfigurationSection(btnId);
                if (bSec == null) continue;
                List<Integer> slots = new ArrayList<>();
                if (bSec.isList("slots")) {
                    for (int s : bSec.getIntegerList("slots")) {
                        if (s >= 0 && s <= maxSlot) slots.add(s);
                    }
                } else if (bSec.contains("slot")) {
                    int s = bSec.getInt("slot", -1);
                    if (s >= 0 && s <= maxSlot) slots.add(s);
                }
                if (slots.isEmpty()) continue;

                String cmd = bSec.getString("command", "");
                if (cmd != null && cmd.isBlank()) cmd = null;
                MenuAction action = parseAction(
                        bSec.getString("action", cmd != null ? "RUN_COMMAND" : "NONE"),
                        bSec.getString("section", "")
                );
                MenuIconSpec icon = parseIcon(bSec, Material.STONE_BUTTON, btnId, false);
                MenuIconSpec activeIcon = null;
                if (bSec.isConfigurationSection("active")) {
                    ConfigurationSection actSec = bSec.getConfigurationSection("active");
                    activeIcon = parseIcon(
                            actSec,
                            icon.material(),
                            icon.name(),
                            icon.hideTooltip()
                    );
                }
                buttons.add(new MenuButtonDef(btnId, Collections.unmodifiableList(slots), icon, activeIcon, action, cmd));
            }
        }

        Map<String, MenuIconSpec> mergedEmptySlots = new LinkedHashMap<>();
        if (inheritedEmptySlots != null) {
            mergedEmptySlots.putAll(inheritedEmptySlots);
        }
        mergedEmptySlots.putAll(parseEmptySlots(sec.getConfigurationSection("empty-slots")));

        return new MenuLayoutDef(
                id,
                title,
                rows,
                fillerEnabled,
                fillerSpec,
                showIndicators,
                Collections.unmodifiableMap(slotPositions),
                Collections.unmodifiableList(buttons),
                Collections.unmodifiableMap(mergedEmptySlots)
        );
    }

    private static Map<String, MenuIconSpec> parseEmptySlots(ConfigurationSection emptySec) {
        if (emptySec == null) return Map.of();
        Map<String, MenuIconSpec> result = new LinkedHashMap<>();
        for (String slotKey : emptySec.getKeys(false)) {
            ConfigurationSection s = emptySec.getConfigurationSection(slotKey);
            if (s == null) continue;
            result.put(
                    slotKey.trim().toLowerCase(Locale.ROOT),
                    parseIcon(s, Material.GRAY_STAINED_GLASS_PANE, "", false)
            );
        }
        return result;
    }

    private static MenuIconSpec parseIcon(ConfigurationSection sec, Material defaultMat, String defaultName, boolean defaultHideTooltip) {
        Material mat = Material.matchMaterial(sec.getString("material", defaultMat.name()));
        if (mat == null) mat = defaultMat;
        String name = sec.getString("name", defaultName);
        List<String> lore = sec.getStringList("lore");
        Integer cmd = sec.contains("custom-model-data") ? sec.getInt("custom-model-data") : null;
        String itemModel = sec.getString("item-model", "");
        if (itemModel != null && itemModel.isBlank()) itemModel = null;
        boolean glint = sec.getBoolean("glint", false);
        boolean hideTooltip = sec.getBoolean("hide-tooltip", defaultHideTooltip);
        String colorHex = sec.getString("color", "");
        if (colorHex != null && colorHex.isBlank()) colorHex = null;
        return new MenuIconSpec(mat, name, Collections.unmodifiableList(lore), cmd, itemModel, glint, hideTooltip, colorHex);
    }

    private static MenuAction parseAction(String raw, String sectionHint) {
        if (raw == null || raw.isBlank()) return MenuAction.NONE;
        String norm = raw.trim().toUpperCase(Locale.ROOT);
        if ("DECOR".equals(norm) || "NONE".equals(norm)) return MenuAction.NONE;
        if ("SWITCH_MODE".equals(norm)) return MenuAction.CYCLE_MODE;
        if ("INVENTORY".equals(norm) || "OPEN_INVENTORY".equals(norm)) return MenuAction.OPEN_INVENTORY;
        if ("SWITCH_TAB".equals(norm) || "OPEN_SECTION".equals(norm)) {
            ArmorSection sec = ArmorSection.fromName(sectionHint);
            if (sec != null) {
                return switch (sec) {
                    case HEAD -> MenuAction.OPEN_HEAD;
                    case CHEST -> MenuAction.OPEN_CHEST;
                    case LEGS -> MenuAction.OPEN_LEGS;
                    case FEET -> MenuAction.OPEN_FEET;
                };
            }
            return MenuAction.OPEN_HUB;
        }
        try {
            return MenuAction.valueOf(norm);
        } catch (IllegalArgumentException ignored) {
            return MenuAction.NONE;
        }
    }

    private static int clampRows(int rows) {
        return Math.max(1, Math.min(6, rows));
    }
}
