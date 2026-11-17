package dev.mythicwardrobe.gui;

import dev.mythicwardrobe.config.MenuConfigLoader;
import dev.mythicwardrobe.config.TextUtil;
import dev.mythicwardrobe.config.WardrobeConfig;
import dev.mythicwardrobe.manager.WardrobeItemResolver;
import dev.mythicwardrobe.manager.WardrobeManager;
import dev.mythicwardrobe.model.ArmorSection;
import dev.mythicwardrobe.model.MenuMode;
import dev.mythicwardrobe.model.MenuVisualDef.*;
import dev.mythicwardrobe.model.WardrobeSlotDef;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

/**
 * Renders and manages all three customizable GUI modes from menus.yml:
 *   1. COMPACT — 1-row per armor slot (HEAD / CHEST / LEGS / FEET) + 1-row hub
 *   2. TABBED  — single menu with switchable tabs
 *   3. UNIFIED — single large menu showing all 4 armor sections simultaneously
 */
public final class WardrobeGuiManager {

    private final JavaPlugin plugin;
    private final WardrobeConfig config;
    private final WardrobeManager wardrobeManager;
    private final WardrobeItemResolver itemResolver;
    private final NamespacedKey placeholderKey;

    private record NavPage(MenuMode mode, ArmorSection section, boolean hubView) {}
    private final Map<UUID, Deque<NavPage>> navHistory = new java.util.concurrent.ConcurrentHashMap<>();

    public WardrobeGuiManager(
            JavaPlugin plugin,
            WardrobeConfig config,
            WardrobeManager wardrobeManager,
            WardrobeItemResolver itemResolver
    ) {
        this.plugin = plugin;
        this.config = config;
        this.wardrobeManager = wardrobeManager;
        this.itemResolver = itemResolver;
        this.placeholderKey = new NamespacedKey(plugin, "ui_placeholder");
    }

    /**
     * Opens the appropriate default view for the player based on their active MenuMode:
     *   COMPACT -> compact_hub (1-row selector)
     *   TABBED  -> tabbed menu (CHEST)
     *   UNIFIED -> unified all-in-one menu
     */
    public void openDefault(Player player) {
        MenuMode mode = wardrobeManager.getEffectiveMenuMode(player);
        switch (mode) {
            case COMPACT -> openCompactHub(player);
            case TABBED -> openTabbed(player, ArmorSection.CHEST);
            case UNIFIED -> openUnified(player);
        }
    }

    /**
     * Opens a specific armor section respecting the player's current MenuMode:
     *   COMPACT -> 1-row menu for that section
     *   TABBED  -> tabbed menu switched to that section
     *   UNIFIED -> unified all-in-one menu
     */
    public void openSection(Player player, ArmorSection section) {
        ArmorSection target = section != null ? section : ArmorSection.CHEST;
        MenuMode mode = wardrobeManager.getEffectiveMenuMode(player);
        switch (mode) {
            case COMPACT -> openCompactSection(player, target);
            case TABBED -> openTabbed(player, target);
            case UNIFIED -> openUnified(player);
        }
    }

    private void recordCurrentPageForBack(Player player) {
        try {
            Inventory top = player.getOpenInventory().getTopInventory();
            if (top != null && top.getHolder() instanceof WardrobeGuiHolder cur) {
                NavPage page = new NavPage(cur.getMenuMode(), cur.getSection(), cur.isHubView());
                Deque<NavPage> stack = navHistory.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>());
                if (!page.equals(stack.peekFirst())) {
                    stack.addFirst(page);
                    while (stack.size() > 10) stack.removeLast();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public void clearNavHistory(UUID playerId) {
        navHistory.remove(playerId);
    }

    public void reopenSnapshot(Player player, WardrobeGuiHolder snapshot) {
        if (player == null || !player.isOnline() || snapshot == null) return;
        openNavPage(player, new NavPage(snapshot.getMenuMode(), snapshot.getSection(), snapshot.isHubView()), false);
    }

    private void openNavPage(Player player, NavPage page, boolean pushHistory) {
        if (pushHistory) recordCurrentPageForBack(player);
        switch (page.mode()) {
            case COMPACT -> {
                if (page.hubView() || page.section() == null) openCompactHubInternal(player);
                else openCompactSectionInternal(player, page.section());
            }
            case TABBED -> openTabbedInternal(player, page.section() != null ? page.section() : ArmorSection.CHEST);
            case UNIFIED -> openUnifiedInternal(player);
        }
    }

    public void openCompactHub(Player player) {
        recordCurrentPageForBack(player);
        openCompactHubInternal(player);
    }

    private void openCompactHubInternal(Player player) {
        MenuConfigLoader.ParsedMenusConfig menus = config.getMenusConfig();
        MenuLayoutDef layout = menus.compactHub();
        WardrobeGuiHolder holder = new WardrobeGuiHolder(player.getUniqueId(), MenuMode.COMPACT, null, true);
        Inventory inv = createInventory(player, holder, layout, layout.title(), null, MenuMode.COMPACT);
        populateDecorationsAndButtons(player, inv, holder, layout, null, MenuMode.COMPACT);
        player.openInventory(inv);
    }

    public void openCompactSection(Player player, ArmorSection section) {
        recordCurrentPageForBack(player);
        openCompactSectionInternal(player, section);
    }

    private void openCompactSectionInternal(Player player, ArmorSection section) {
        MenuConfigLoader.ParsedMenusConfig menus = config.getMenusConfig();
        MenuLayoutDef layout = menus.getCompactMenu(section);
        WardrobeGuiHolder holder = new WardrobeGuiHolder(player.getUniqueId(), MenuMode.COMPACT, section, false);
        Inventory inv = createInventory(player, holder, layout, layout.title(), section, MenuMode.COMPACT);
        populateDecorationsAndButtons(player, inv, holder, layout, section, MenuMode.COMPACT);
        populateInteractiveSlots(player, inv, holder, layout, config.getSlotsForSection(section));
        player.openInventory(inv);
    }

    public void openTabbed(Player player, ArmorSection section) {
        recordCurrentPageForBack(player);
        openTabbedInternal(player, section);
    }

    private void openTabbedInternal(Player player, ArmorSection section) {
        MenuConfigLoader.ParsedMenusConfig menus = config.getMenusConfig();
        MenuLayoutDef layout = menus.tabbedBase();
        String title = menus.getTabbedTitle(section);
        WardrobeGuiHolder holder = new WardrobeGuiHolder(player.getUniqueId(), MenuMode.TABBED, section, false);
        Inventory inv = createInventory(player, holder, layout, title, section, MenuMode.TABBED);
        populateDecorationsAndButtons(player, inv, holder, layout, section, MenuMode.TABBED);
        populateInteractiveSlots(player, inv, holder, layout, config.getSlotsForSection(section));
        player.openInventory(inv);
    }

    public void openUnified(Player player) {
        recordCurrentPageForBack(player);
        openUnifiedInternal(player);
    }

    private void openUnifiedInternal(Player player) {
        MenuConfigLoader.ParsedMenusConfig menus = config.getMenusConfig();
        MenuLayoutDef layout = menus.unifiedMenu();
        WardrobeGuiHolder holder = new WardrobeGuiHolder(player.getUniqueId(), MenuMode.UNIFIED, null, false);
        Inventory inv = createInventory(player, holder, layout, layout.title(), null, MenuMode.UNIFIED);
        populateDecorationsAndButtons(player, inv, holder, layout, null, MenuMode.UNIFIED);
        List<WardrobeSlotDef> allSlots = new ArrayList<>(config.getSlotsById().values());
        populateInteractiveSlots(player, inv, holder, layout, allSlots);
        player.openInventory(inv);
    }

    private Inventory createInventory(Player player, WardrobeGuiHolder holder, MenuLayoutDef layout,
                                      String rawTitle, ArmorSection section, MenuMode mode) {
        String resolvedTitle = applyPlaceholders(rawTitle, player, section, mode);
        Inventory inv = Bukkit.createInventory(holder, layout.inventorySize(), TextUtil.parse(resolvedTitle));
        holder.setInventory(inv);

        if (layout.fillerEnabled() && layout.filler() != null) {
            ItemStack filler = buildIconItem(layout.filler(), player, section, mode);
            for (int i = 0; i < inv.getSize(); i++) {
                inv.setItem(i, filler);
            }
        }
        return inv;
    }

    private void populateDecorationsAndButtons(Player player, Inventory inv, WardrobeGuiHolder holder,
                                               MenuLayoutDef layout, ArmorSection currentSection, MenuMode mode) {
        for (MenuButtonDef btn : layout.buttons()) {
            // If player mode switching is disabled in menus.yml, hide CYCLE_MODE buttons
            if (btn.action() == MenuAction.CYCLE_MODE && !config.isAllowPlayerMenuModeSwitch()) {
                continue;
            }
            boolean isActiveTab = isTabActive(btn.action(), currentSection, holder.isHubView(), mode);
            MenuIconSpec spec = (isActiveTab && btn.activeIcon() != null) ? btn.activeIcon() : btn.icon();
            ItemStack item = buildIconItem(spec, player, currentSection, mode);
            for (int slotIdx : btn.slots()) {
                if (slotIdx < 0 || slotIdx >= inv.getSize()) continue;
                inv.setItem(slotIdx, item);
                if (btn.action() != MenuAction.NONE) {
                    holder.registerButtonAction(slotIdx, btn.action(), btn.command());
                }
            }
        }
    }

    private void populateInteractiveSlots(Player player, Inventory inv, WardrobeGuiHolder holder,
                                          MenuLayoutDef layout, List<WardrobeSlotDef> slots) {
        int size = inv.getSize();
        for (WardrobeSlotDef def : slots) {
            Integer configuredPos = layout.slotPositions().get(def.id());
            int rawSlot = configuredPos != null ? configuredPos : def.guiSlot();
            if (rawSlot < 0 || rawSlot >= size) continue;

            holder.registerInteractiveSlot(rawSlot, def);
            renderSingleInteractiveSlot(player, inv, layout, rawSlot, def);
        }
    }

    public void refreshView(Player player, WardrobeGuiHolder holder) {
        Inventory inv = holder.getInventory();
        if (inv == null) return;
        MenuLayoutDef layout = resolveLayout(holder);
        for (Map.Entry<Integer, WardrobeSlotDef> entry : holder.getInteractiveSlots().entrySet()) {
            renderSingleInteractiveSlot(player, inv, layout, entry.getKey(), entry.getValue());
        }
    }

    private void renderSingleInteractiveSlot(Player player, Inventory inv, MenuLayoutDef layout,
                                             int rawSlot, WardrobeSlotDef def) {
        Optional<WardrobeManager.SlotBlockInfo> blocker = wardrobeManager.getBlockerFor(player, def.id());
        boolean isBlocked = blocker.isPresent() && !blocker.get().sourceId().equalsIgnoreCase(def.id());
        ItemStack equipped = wardrobeManager.getSlotItem(player, def);

        if (isBlocked) {
            inv.setItem(rawSlot, createBlockedSlotIcon(def, blocker.get(), equipped));
        } else if (equipped != null && !equipped.getType().isAir()) {
            inv.setItem(rawSlot, equipped);
        } else {
            inv.setItem(rawSlot, createSlotPlaceholder(layout, def, player));
        }

        // Optional status indicator pane directly underneath (only if enabled & slot below is free filler)
        if (layout != null && layout.showEquippedIndicators()) {
            int below = rawSlot + 9;
            if (below < inv.getSize()
                    && !isInteractiveOrButton(inv, below, layout)
                    && below < inv.getSize() - 9) {
                if (isBlocked) {
                    inv.setItem(below, buildIndicator(Material.RED_STAINED_GLASS_PANE, "<red>Заблокировано"));
                } else {
                    inv.setItem(below, equipped != null
                            ? buildIndicator(Material.LIME_STAINED_GLASS_PANE, "<green>Надето")
                            : buildIndicator(Material.BLACK_STAINED_GLASS_PANE, "<dark_gray>Пусто"));
                }
            }
        }
    }

    private ItemStack createBlockedSlotIcon(WardrobeSlotDef def, WardrobeManager.SlotBlockInfo blocker, ItemStack hiddenItem) {
        Material mat = config.getLockedSlotMaterial();
        if (mat == null || !mat.isItem()) mat = Material.BARRIER;
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(TextUtil.parse("<red>✖ Заблокировано: <gray>" + stripTags(def.displayName())));
            List<Component> lore = new ArrayList<>();
            lore.add(TextUtil.parse("<gray>Этот слот недоступен, пока надет предмет:"));
            lore.add(TextUtil.parse("<yellow>• " + blocker.blockerName()));
            if (hiddenItem != null && !hiddenItem.getType().isAir()) {
                String hiddenName = WardrobeManager.formatItemDisplayName(hiddenItem, def, null);
                lore.add(TextUtil.parse("<dark_gray>(Внутри скрыто: <gray>" + hiddenName + "<dark_gray>)"));
            }
            lore.add(TextUtil.parse("<dark_gray>Снимите блокирующий комплект, чтобы использовать слот."));
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            meta.getPersistentDataContainer().set(placeholderKey, PersistentDataType.BYTE, (byte) 1);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private boolean isInteractiveOrButton(Inventory inv, int slot, MenuLayoutDef layout) {
        if (inv.getHolder() instanceof WardrobeGuiHolder holder) {
            if (holder.getSlotAt(slot) != null || holder.getButtonActionAt(slot) != null) return true;
        }
        for (MenuButtonDef b : layout.buttons()) {
            if (b.slots().contains(slot)) return true;
        }
        return false;
    }

    private MenuLayoutDef resolveLayout(WardrobeGuiHolder holder) {
        MenuConfigLoader.ParsedMenusConfig menus = config.getMenusConfig();
        if (holder.getMenuMode() == MenuMode.UNIFIED) return menus.unifiedMenu();
        if (holder.getMenuMode() == MenuMode.TABBED) return menus.tabbedBase();
        if (holder.isHubView() || holder.getSection() == null) return menus.compactHub();
        return menus.getCompactMenu(holder.getSection());
    }

    /**
     * Handles clicks on configured GUI action buttons (tabs, hub, back, custom commands, mode switcher, refresh, reset, close).
     */
    public void handleButtonAction(Player player, WardrobeGuiHolder holder, MenuAction action) {
        handleButtonAction(player, holder, action, null);
    }

    public void handleButtonAction(Player player, WardrobeGuiHolder holder, MenuAction action, String customCommand) {
        if (action == null || action == MenuAction.NONE) return;
        switch (action) {
            case OPEN_HEAD -> {
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.15f);
                openSection(player, ArmorSection.HEAD);
            }
            case OPEN_CHEST -> {
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.15f);
                openSection(player, ArmorSection.CHEST);
            }
            case OPEN_LEGS -> {
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.15f);
                openSection(player, ArmorSection.LEGS);
            }
            case OPEN_FEET -> {
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.15f);
                openSection(player, ArmorSection.FEET);
            }
            case OPEN_HUB -> {
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
                openCompactHub(player);
            }
            case OPEN_UNIFIED -> {
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.1f);
                openUnified(player);
            }
            case BACK -> {
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 0.95f);
                if (customCommand != null && !customCommand.isBlank()) {
                    executeConfiguredCommand(player, customCommand);
                    return;
                }
                Deque<NavPage> stack = navHistory.get(player.getUniqueId());
                NavPage prev = (stack != null) ? stack.pollFirst() : null;
                if (prev != null) {
                    openNavPage(player, prev, false);
                    return;
                }
                // If inside a 1-row compact section menu, always go back to the compact hub first!
                if (holder.getMenuMode() == MenuMode.COMPACT && !holder.isHubView()) {
                    openCompactHubInternal(player);
                    return;
                }
                String globalBack = config.getMenusConfig() != null ? config.getMenusConfig().backCommand() : "";
                if (globalBack != null && !globalBack.isBlank()) {
                    executeConfiguredCommand(player, globalBack);
                } else if (config.getMenusConfig() == null || config.getMenusConfig().openInventoryOnBack()) {
                    openPlayerInventoryView(player);
                } else {
                    player.closeInventory();
                }
            }
            case OPEN_INVENTORY -> {
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
                openPlayerInventoryView(player);
            }
            case RUN_COMMAND -> {
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.05f);
                if (customCommand != null && !customCommand.isBlank()) {
                    executeConfiguredCommand(player, customCommand);
                }
            }
            case CYCLE_MODE -> {
                if (!config.isAllowPlayerMenuModeSwitch()) return;
                MenuMode next = wardrobeManager.getEffectiveMenuMode(player).next();
                wardrobeManager.setPlayerMenuMode(player, next);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.35f);
                player.sendMessage(TextUtil.parse(config.getPrefixedMessage("mode-switched",
                        "<green>Режим меню переключён на: <white><mode>").replace("<mode>", next.getRussianLabel())));
                ArmorSection currentSec = holder.getSection();
                if (next == MenuMode.COMPACT) {
                    if (currentSec != null) {
                        openCompactSection(player, currentSec);
                    } else {
                        openCompactHub(player);
                    }
                } else if (next == MenuMode.TABBED) {
                    openTabbed(player, currentSec != null ? currentSec : ArmorSection.CHEST);
                } else {
                    openUnified(player);
                }
            }
            case REFRESH_SKIN -> {
                wardrobeManager.getArmorVisualManager().refreshPlayerArmorVisuals(player);
                wardrobeManager.getDisplayAccessoryManager().syncPlayerDisplays(player);
                wardrobeManager.scheduleSkinRebuild(player, true);
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
                player.sendMessage(TextUtil.parse(config.getPrefixedMessage("skin-updating", "Обновляем ваш образ...")));
            }
            case RESET_SKIN -> {
                wardrobeManager.resetSkillLayersAndRefresh(player, true);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 0.9f);
                player.sendMessage(TextUtil.parse(config.getPrefixedMessage("skin-reset", "Слои скина сброшены.")));
            }
            case OPEN_WEB -> {
                player.closeInventory();
                player.performCommand("wardrobe web");
            }
            case CLOSE -> player.closeInventory();
            default -> {
            }
        }
    }

    /**
     * Opens a compact 1-row "Inventory & Equipment" screen when pressing BACK from the top wardrobe menu
     * so the player's inventory stays open and interactive (with live real-armor + offhand slots),
     * instead of fully closing the GUI. Pressing CLOSE still closes the menu completely.
     */
    public void openPlayerInventoryView(Player player) {
        MenuMode mode = wardrobeManager.getEffectiveMenuMode(player);
        String rawTitle = config.getMenusConfig() != null
                ? config.getMenusConfig().inventoryTitle()
                : "<#1e293b>Инвентарь и Экипировка";
        WardrobeGuiHolder holder = new WardrobeGuiHolder(player.getUniqueId(), mode, null, false, true);
        Inventory inv = Bukkit.createInventory(holder, 9, TextUtil.parse(rawTitle));
        holder.setInventory(inv);

        // Slot 0: return straight back to Wardrobe
        MenuAction backToWardrobe = switch (mode) {
            case UNIFIED -> MenuAction.OPEN_UNIFIED;
            case TABBED -> MenuAction.OPEN_CHEST;
            case COMPACT -> MenuAction.OPEN_HUB;
        };
        holder.registerButtonAction(0, backToWardrobe);
        holder.registerLiveEquipmentSlot(2, EquipmentSlot.HEAD);
        holder.registerLiveEquipmentSlot(3, EquipmentSlot.CHEST);
        holder.registerLiveEquipmentSlot(4, EquipmentSlot.LEGS);
        holder.registerLiveEquipmentSlot(5, EquipmentSlot.FEET);
        holder.registerLiveEquipmentSlot(6, EquipmentSlot.OFF_HAND);
        holder.registerButtonAction(8, MenuAction.CLOSE);

        refreshInventoryView(player, holder);
        player.openInventory(inv);
    }

    private void refreshInventoryView(Player player, WardrobeGuiHolder holder) {
        Inventory inv = holder.getInventory();
        if (inv == null) return;
        ItemStack filler = buildIconItem(MenuIconSpec.filler(Material.GRAY_STAINED_GLASS_PANE), player, null, holder.getMenuMode());
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);

        inv.setItem(0, buildIconItem(MenuIconSpec.simple(
                Material.ARMOR_STAND,
                "<white>Гардероб",
                List.of("<gray>Открыть меню гардероба")
        ), player, null, holder.getMenuMode()));

        setLiveEquipmentSlotIcon(inv, player, 2, EquipmentSlot.HEAD, Material.IRON_HELMET, "<white>Шлем <dark_gray>(пусто)");
        setLiveEquipmentSlotIcon(inv, player, 3, EquipmentSlot.CHEST, Material.IRON_CHESTPLATE, "<white>Нагрудник <dark_gray>(пусто)");
        setLiveEquipmentSlotIcon(inv, player, 4, EquipmentSlot.LEGS, Material.IRON_LEGGINGS, "<white>Поножи <dark_gray>(пусто)");
        setLiveEquipmentSlotIcon(inv, player, 5, EquipmentSlot.FEET, Material.IRON_BOOTS, "<white>Ботинки <dark_gray>(пусто)");
        setLiveEquipmentSlotIcon(inv, player, 6, EquipmentSlot.OFF_HAND, Material.SHIELD, "<white>Левая рука <dark_gray>(пусто)");

        inv.setItem(8, buildIconItem(MenuIconSpec.simple(
                Material.BARRIER,
                "<red>Закрыть",
                List.of("<gray>Закрыть меню полностью")
        ), player, null, holder.getMenuMode()));
    }

    private void setLiveEquipmentSlotIcon(Inventory inv, Player player, int slot, EquipmentSlot eqSlot, Material emptyMat, String emptyName) {
        ItemStack equipped = player.getInventory().getItem(eqSlot);
        if (equipped != null && !equipped.getType().isAir()) {
            inv.setItem(slot, equipped.clone());
        } else {
            inv.setItem(slot, buildIconItem(MenuIconSpec.simple(
                    emptyMat,
                    emptyName,
                    List.of("<gray>Кликните предметом, чтобы надеть", "<dark_gray>Ваш инвентарь открыт снизу")
            ), player, null, null));
        }
    }

    /**
     * Central InventoryClickEvent handler for all 3 GUI modes (COMPACT / TABBED / UNIFIED) + Inventory view.
     */
    public void handleClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory topInv = event.getView().getTopInventory();
        if (!(topInv.getHolder() instanceof WardrobeGuiHolder holder)) return;

        Inventory clickedInv = event.getClickedInventory();
        if (clickedInv == null) return;

        // Prevent collect-to-cursor pulling placeholders from top GUI
        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            event.setCancelled(true);
            return;
        }

        // Clicking inside player's own bottom inventory
        if (!clickedInv.equals(topInv)) {
            if (holder.isInventoryView()) {
                // Allow normal free inventory sorting/moving when viewing Inventory & Equipment!
                if (event.isShiftClick()) {
                    event.setCancelled(true);
                }
                return;
            }
            if (event.isShiftClick()) {
                event.setCancelled(true);
                ItemStack clicked = event.getCurrentItem();
                if (clicked != null && !clicked.getType().isAir()) {
                    tryShiftEquipFromPlayerInventory(player, holder, clicked, event.getSlot());
                }
            }
            return;
        }

        // Clicking inside top Wardrobe GUI -> always handled manually
        event.setCancelled(true);
        int rawSlot = event.getRawSlot();

        // Live player real-equipment slots inside the Inventory & Equipment screen
        EquipmentSlot liveEq = holder.getLiveEquipmentSlotAt(rawSlot);
        if (liveEq != null) {
            ItemStack current = player.getInventory().getItem(liveEq);
            ItemStack oldCopy = (current == null || current.getType().isAir()) ? null : current.clone();
            ItemStack cursor = event.getCursor();
            boolean hasCurrent = oldCopy != null;
            boolean hasCursor = cursor != null && !cursor.getType().isAir();
            ItemStack newItem = oldCopy;

            if (event.isShiftClick() && hasCurrent) {
                Map<Integer, ItemStack> overflow = player.getInventory().addItem(oldCopy.clone());
                if (overflow.isEmpty()) {
                    player.getInventory().setItem(liveEq, null);
                    newItem = null;
                    player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_GENERIC, 0.7f, 0.9f);
                }
            } else if (hasCursor) {
                newItem = cursor.clone();
                player.getInventory().setItem(liveEq, newItem.clone());
                player.setItemOnCursor(hasCurrent ? oldCopy.clone() : null);
                player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_GENERIC, 0.7f, 1.1f);
            } else if (hasCurrent) {
                player.setItemOnCursor(oldCopy.clone());
                player.getInventory().setItem(liveEq, null);
                newItem = null;
                player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_GENERIC, 0.7f, 0.9f);
            }
            if (liveEq != EquipmentSlot.OFF_HAND) {
                wardrobeManager.handleRealArmorTransition(player, oldCopy, newItem);
            }
            refreshInventoryView(player, holder);
            wardrobeManager.getArmorVisualManager().refreshPlayerArmorVisuals(player);
            wardrobeManager.getDisplayAccessoryManager().syncPlayerDisplays(player);
            return;
        }

        WardrobeSlotDef slotDef = holder.getSlotAt(rawSlot);
        if (slotDef != null) {
            // Check if this slot is locked by an active full-suit / blocking item
            Optional<WardrobeManager.SlotBlockInfo> blocker = wardrobeManager.getBlockerFor(player, slotDef.id());
            if (blocker.isPresent() && !blocker.get().sourceId().equalsIgnoreCase(slotDef.id())) {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.65f, 0.9f);
                player.sendMessage(TextUtil.parse(config.getPrefixedMessage("slot-blocked-by",
                                "<red>Слот <white><slot></white> <red>заблокирован предметом <yellow><blocker></yellow><red>!")
                        .replace("<slot>", stripTags(slotDef.displayName()))
                        .replace("<blocker>", blocker.get().blockerName())));
                return;
            }

            // Support Shift-Click inside top GUI to unequip straight to player inventory
            if (event.isShiftClick()) {
                ItemStack current = wardrobeManager.getSlotItem(player, slotDef);
                if (current != null && !current.getType().isAir()) {
                    Map<Integer, ItemStack> overflow = player.getInventory().addItem(current.clone());
                    if (overflow.isEmpty()) {
                        wardrobeManager.prepareAndEquipSlotItem(player, slotDef, null, true);
                        player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_GENERIC, 0.7f, 0.9f);
                        refreshView(player, holder);
                    } else {
                        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1.0f);
                    }
                }
                return;
            }
            handleWardrobeSlotClick(player, holder, slotDef, event.getCursor(), event.isRightClick());
            return;
        }

        MenuAction action = holder.getButtonActionAt(rawSlot);
        if (action != null && action != MenuAction.NONE) {
            handleButtonAction(player, holder, action, holder.getButtonCommandAt(rawSlot));
        }
    }

    private void executeConfiguredCommand(Player player, String rawCmd) {
        String cmd = rawCmd.trim()
                .replace("%player%", player.getName())
                .replace("<player>", player.getName());
        if (cmd.toLowerCase(Locale.ROOT).startsWith("console:")) {
            String consoleCmd = cmd.substring("console:".length()).trim();
            if (consoleCmd.startsWith("/")) consoleCmd = consoleCmd.substring(1);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), consoleCmd);
        } else {
            if (cmd.startsWith("/")) cmd = cmd.substring(1);
            player.performCommand(cmd);
        }
    }

    public void handleDrag(InventoryDragEvent event) {
        int topSize = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    public void handleClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            wardrobeManager.flushDirtySkin(player);
        }
    }

    /**
     * Handles clicking an interactive wardrobe slot inside ANY GUI mode (COMPACT / TABBED / UNIFIED).
     */
    public boolean handleWardrobeSlotClick(Player player, WardrobeGuiHolder holder, WardrobeSlotDef slotDef, ItemStack cursorItem, boolean rightClick) {
        Optional<WardrobeManager.SlotBlockInfo> blocker = wardrobeManager.getBlockerFor(player, slotDef.id());
        if (blocker.isPresent() && !blocker.get().sourceId().equalsIgnoreCase(slotDef.id())) {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.65f, 0.9f);
            player.sendMessage(TextUtil.parse(config.getPrefixedMessage("slot-blocked-by",
                            "<red>Слот <white><slot></white> <red>заблокирован предметом <yellow><blocker></yellow><red>!")
                    .replace("<slot>", stripTags(slotDef.displayName()))
                    .replace("<blocker>", blocker.get().blockerName())));
            return false;
        }

        ItemStack current = wardrobeManager.getSlotItem(player, slotDef);
        boolean cursorEmpty = (cursorItem == null || cursorItem.getType().isAir() || cursorItem.getAmount() <= 0);

        // Taking item out
        if (cursorEmpty) {
            if (current == null || current.getType().isAir()) {
                return false;
            }
            wardrobeManager.prepareAndEquipSlotItem(player, slotDef, null, true);
            player.setItemOnCursor(current.clone());
            player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_GENERIC, 0.7f, 0.9f);
            refreshView(player, holder);
            return true;
        }

        // Validate item for this slot
        if (!itemResolver.canPlaceInSlot(cursorItem, slotDef)) {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1.0f);
            player.sendMessage(TextUtil.parse(config.getPrefixedMessage("invalid-slot-item",
                    "<red>Этот предмет нельзя надеть в слот <white><slot><red>.")
                    .replace("<slot>", stripTags(slotDef.displayName()))));
            return false;
        }

        // Split single item if cursor stack > 1
        ItemStack toEquip = cursorItem.clone();
        toEquip.setAmount(1);

        if (cursorItem.getAmount() > 1 && current != null && !current.getType().isAir()) {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1.0f);
            return false;
        }

        WardrobeManager.EquipAttemptResult attempt = wardrobeManager.prepareAndEquipSlotItem(player, slotDef, toEquip, true);
        if (!attempt.allowed()) {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.65f, 0.9f);
            if (attempt.errorMessage() != null) {
                player.sendMessage(TextUtil.parse(attempt.errorMessage()));
            }
            return false;
        }

        if (cursorItem.getAmount() > 1) {
            ItemStack remainder = cursorItem.clone();
            remainder.setAmount(cursorItem.getAmount() - 1);
            player.setItemOnCursor(remainder);
        } else {
            player.setItemOnCursor(current != null ? current.clone() : null);
        }

        if (attempt.unequippedConflicts() > 0) {
            player.sendMessage(TextUtil.parse(config.getPrefixedMessage("equip-auto-unequipped",
                            "<yellow>Снято конфликтующих вещей: <white><count></white> <gray>(возвращены в инвентарь).")
                    .replace("<count>", String.valueOf(attempt.unequippedConflicts()))));
        }

        player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.8f, 1.1f);
        refreshView(player, holder);
        return true;
    }

    /**
     * Handles shift-click from the player's own inventory into ANY open wardrobe GUI
     * (works across 1-row COMPACT, TABBED, and UNIFIED menus!).
     */
    public boolean tryShiftEquipFromPlayerInventory(Player player, WardrobeGuiHolder holder, ItemStack clickedItem, int playerInvSlot) {
        if (clickedItem == null || clickedItem.getType().isAir()) return false;

        // Order candidate slots sensibly: for Equippable armor models try ARMOR_REAL first (standalone),
        // then DISPLAY_3D/ARMOR_OVERLAY (simultaneous second layer), then ARMOR_COSMETIC!
        List<WardrobeSlotDef> ordered = new ArrayList<>(holder.getInteractiveSlots().values());
        if (itemResolver.isEquippableArmorModel(clickedItem)) {
            ordered.sort(Comparator.comparingInt(def -> {
                if (def.isRealArmor()) return 0;
                if (def.isDisplay3D()) return 1;
                if (def.isCosmeticArmor()) return 2;
                return 3;
            }));
        }

        String blockedReason = null;
        for (WardrobeSlotDef def : ordered) {
            if (!itemResolver.canPlaceInSlot(clickedItem, def)) continue;

            Optional<WardrobeManager.SlotBlockInfo> blocker = wardrobeManager.getBlockerFor(player, def.id());
            if (blocker.isPresent() && !blocker.get().sourceId().equalsIgnoreCase(def.id())) {
                blockedReason = config.getPrefixedMessage("slot-blocked-by",
                                "<red>Слот <white><slot></white> <red>заблокирован предметом <yellow><blocker></yellow><red>!")
                        .replace("<slot>", stripTags(def.displayName()))
                        .replace("<blocker>", blocker.get().blockerName());
                continue;
            }

            ItemStack existing = wardrobeManager.getSlotItem(player, def);
            if (existing != null && !existing.getType().isAir()) continue;

            ItemStack single = clickedItem.clone();
            single.setAmount(1);
            WardrobeManager.EquipAttemptResult attempt = wardrobeManager.prepareAndEquipSlotItem(player, def, single, true);
            if (!attempt.allowed()) {
                blockedReason = attempt.errorMessage();
                continue;
            }

            if (clickedItem.getAmount() > 1) {
                ItemStack rem = clickedItem.clone();
                rem.setAmount(clickedItem.getAmount() - 1);
                player.getInventory().setItem(playerInvSlot, rem);
            } else {
                player.getInventory().setItem(playerInvSlot, null);
            }

            if (attempt.unequippedConflicts() > 0) {
                player.sendMessage(TextUtil.parse(config.getPrefixedMessage("equip-auto-unequipped",
                                "<yellow>Снято конфликтующих вещей: <white><count></white> <gray>(возвращены в инвентарь).")
                        .replace("<count>", String.valueOf(attempt.unequippedConflicts()))));
            }

            player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.8f, 1.15f);
            refreshView(player, holder);
            return true;
        }

        if (blockedReason != null) {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.65f, 0.9f);
            player.sendMessage(TextUtil.parse(blockedReason));
        }
        return false;
    }

    public boolean isPlaceholder(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(placeholderKey, PersistentDataType.BYTE);
    }

    private boolean isTabActive(MenuAction action, ArmorSection currentSection, boolean hubView, MenuMode mode) {
        if (currentSection == null) return false;
        return switch (action) {
            case OPEN_HEAD -> currentSection == ArmorSection.HEAD;
            case OPEN_CHEST -> currentSection == ArmorSection.CHEST;
            case OPEN_LEGS -> currentSection == ArmorSection.LEGS;
            case OPEN_FEET -> currentSection == ArmorSection.FEET;
            default -> false;
        };
    }

    private ItemStack createSlotPlaceholder(MenuLayoutDef layout, WardrobeSlotDef def, Player player) {
        MenuIconSpec custom = (layout != null && layout.emptySlots() != null)
                ? layout.emptySlots().get(def.id().toLowerCase(Locale.ROOT))
                : null;
        if (custom != null) {
            String effectiveName = (custom.name() != null && !custom.name().isEmpty())
                    ? custom.name()
                    : def.displayName();
            List<String> effectiveLore = (custom.lore() != null && !custom.lore().isEmpty())
                    ? custom.lore()
                    : def.lore();
            MenuIconSpec merged = new MenuIconSpec(
                    custom.material(),
                    effectiveName,
                    effectiveLore,
                    custom.customModelData() != null ? custom.customModelData() : def.placeholderCustomModelData(),
                    custom.itemModel() != null ? custom.itemModel() : def.placeholderItemModel(),
                    custom.glint(),
                    custom.hideTooltip(),
                    custom.colorHex()
            );
            return buildIconItem(merged, player, def.section(), wardrobeManager.getEffectiveMenuMode(player));
        }

        MenuIconSpec fallback = new MenuIconSpec(
                def.placeholderMaterial(),
                def.displayName(),
                def.lore(),
                def.placeholderCustomModelData(),
                def.placeholderItemModel(),
                false,
                false,
                null
        );
        return buildIconItem(fallback, player, def.section(), wardrobeManager.getEffectiveMenuMode(player));
    }

    private ItemStack buildIndicator(Material mat, String name) {
        return buildIconItem(MenuIconSpec.simple(mat, name, List.of()), null, null, null);
    }

    private ItemStack buildIconItem(MenuIconSpec spec, Player player, ArmorSection section, MenuMode mode) {
        ItemStack stack = new ItemStack(spec.material());
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (spec.hideTooltip()) {
                try {
                    meta.setHideTooltip(true);
                } catch (Throwable ignored) {
                }
            }
            if (spec.name() != null && !spec.name().isEmpty()) {
                meta.displayName(TextUtil.parse(applyPlaceholders(spec.name(), player, section, mode)));
            }
            if (spec.lore() != null && !spec.lore().isEmpty()) {
                List<Component> lore = new ArrayList<>(spec.lore().size());
                for (String line : spec.lore()) {
                    lore.add(TextUtil.parse(applyPlaceholders(line, player, section, mode)));
                }
                meta.lore(lore);
            }
            if (spec.customModelData() != null) {
                meta.setCustomModelData(spec.customModelData());
            }
            if (spec.itemModel() != null && !spec.itemModel().isBlank()) {
                NamespacedKey key = NamespacedKey.fromString(spec.itemModel().trim());
                if (key != null) {
                    try {
                        meta.setItemModel(key);
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (spec.glint()) {
                try {
                    meta.setEnchantmentGlintOverride(true);
                } catch (Throwable ignored) {
                }
            }
            if (spec.colorHex() != null && meta instanceof LeatherArmorMeta leather) {
                Color c = parseHexColor(spec.colorHex());
                if (c != null) {
                    leather.setColor(c);
                }
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            meta.getPersistentDataContainer().set(placeholderKey, PersistentDataType.BYTE, (byte) 1);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static String applyPlaceholders(String text, Player player, ArmorSection section, MenuMode mode) {
        if (text == null) return "";
        String out = text;
        if (mode != null) {
            out = out.replace("<mode>", mode.getRussianLabel());
        }
        if (section != null) {
            out = out.replace("<section>", section.getRussianTitle());
        }
        if (player != null) {
            out = out.replace("<player>", player.getName());
        }
        return out;
    }

    private static Color parseHexColor(String hex) {
        try {
            String clean = hex.trim();
            if (clean.startsWith("#")) clean = clean.substring(1);
            if (clean.length() != 6) return null;
            return Color.fromRGB(Integer.parseInt(clean, 16));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String stripTags(String text) {
        return text == null ? "" : text.replaceAll("<[^>]+>", "");
    }
}
