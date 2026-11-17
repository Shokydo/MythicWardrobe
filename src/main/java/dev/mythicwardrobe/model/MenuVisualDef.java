package dev.mythicwardrobe.model;

import org.bukkit.Material;

import java.util.List;
import java.util.Map;

/**
 * Full visual & layout definition of a single customizable GUI loaded from menus.yml.
 */
public final class MenuVisualDef {

    private MenuVisualDef() {
    }

    public enum MenuAction {
        OPEN_HEAD,
        OPEN_CHEST,
        OPEN_LEGS,
        OPEN_FEET,
        OPEN_HUB,
        OPEN_UNIFIED,
        OPEN_INVENTORY,
        BACK,
        RUN_COMMAND,
        CYCLE_MODE,
        REFRESH_SKIN,
        RESET_SKIN,
        OPEN_WEB,
        CLOSE,
        NONE
    }

    public record MenuIconSpec(
            Material material,
            String name,
            List<String> lore,
            Integer customModelData,
            String itemModel,
            boolean glint,
            boolean hideTooltip,
            String colorHex
    ) {
        public static MenuIconSpec simple(Material mat, String name, List<String> lore) {
            return new MenuIconSpec(mat, name, lore == null ? List.of() : lore, null, null, false, false, null);
        }

        public static MenuIconSpec filler(Material mat) {
            return new MenuIconSpec(mat, " ", List.of(), null, null, false, true, null);
        }
    }

    public record MenuButtonDef(
            String id,
            List<Integer> slots,
            MenuIconSpec icon,
            MenuIconSpec activeIcon,
            MenuAction action,
            String command
    ) {
        public MenuButtonDef(String id, List<Integer> slots, MenuIconSpec icon, MenuIconSpec activeIcon, MenuAction action) {
            this(id, slots, icon, activeIcon, action, null);
        }
    }

    public record MenuLayoutDef(
            String id,
            String title,
            int rows,
            boolean fillerEnabled,
            MenuIconSpec filler,
            boolean showEquippedIndicators,
            Map<String, Integer> slotPositions,
            List<MenuButtonDef> buttons,
            Map<String, MenuIconSpec> emptySlots
    ) {
        public MenuLayoutDef(
                String id,
                String title,
                int rows,
                boolean fillerEnabled,
                MenuIconSpec filler,
                boolean showEquippedIndicators,
                Map<String, Integer> slotPositions,
                List<MenuButtonDef> buttons
        ) {
            this(id, title, rows, fillerEnabled, filler, showEquippedIndicators, slotPositions, buttons, Map.of());
        }

        public int inventorySize() {
            return Math.max(1, Math.min(6, rows)) * 9;
        }
    }
}
