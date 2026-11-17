package dev.mythicwardrobe.listener;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import dev.mythicwardrobe.config.TextUtil;
import dev.mythicwardrobe.config.WardrobeConfig;
import dev.mythicwardrobe.gui.WardrobeGuiHolder;
import dev.mythicwardrobe.gui.WardrobeGuiManager;
import dev.mythicwardrobe.manager.WardrobeItemResolver;
import dev.mythicwardrobe.manager.WardrobeManager;
import dev.mythicwardrobe.model.ArmorSection;
import dev.mythicwardrobe.model.ItemWardrobeMeta;
import dev.mythicwardrobe.model.WardrobeSlotDef;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

public final class WardrobeListener implements Listener {

    private final JavaPlugin plugin;
    private final WardrobeConfig config;
    private final WardrobeManager manager;
    private final WardrobeGuiManager guiManager;
    private final WardrobeItemResolver resolver;

    public WardrobeListener(
            JavaPlugin plugin,
            WardrobeConfig config,
            WardrobeManager manager,
            WardrobeGuiManager guiManager,
            WardrobeItemResolver resolver
    ) {
        this.plugin = plugin;
        this.config = config;
        this.manager = manager;
        this.guiManager = guiManager;
        this.resolver = resolver;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof WardrobeGuiHolder) {
            guiManager.handleClick(event);
            return;
        }

        if (!config.isInventoryTriggerEnabled()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (player.getGameMode() == GameMode.CREATIVE) return;
        if (!player.hasPermission("mythicwardrobe.use")) return;

        // Player's own survival inventory has top inventory type CRAFTING
        if (event.getView().getTopInventory().getType() != InventoryType.CRAFTING) return;
        if (event.getSlotType() != InventoryType.SlotType.ARMOR) return;

        ArmorSection section = ArmorSection.fromBukkitArmorSlot(event.getSlot());
        if (section == null) return;

        ItemStack cursor = event.getCursor();
        boolean hasCursor = cursor != null && !cursor.getType().isAir();

        // Smart cursor equip: if holding a clothing/3D item belonging to this section, directly equip it!
        if (hasCursor && config.isSmartCursorEquip()) {
            ItemWardrobeMeta meta = resolver.resolve(cursor);
            if (meta.hasSlot()) {
                WardrobeSlotDef targetSlot = config.getSlot(meta.primarySlotId());
                if (targetSlot != null && targetSlot.section() == section && resolver.canPlaceInSlot(cursor, targetSlot)) {
                    event.setCancelled(true);
                    ItemStack previous = manager.getSlotItem(player, targetSlot);
                    if (cursor.getAmount() > 1 && previous != null && !previous.getType().isAir()) {
                        return; // Avoid losing remainder when swapping stacked item
                    }
                    ItemStack toEquip = cursor.clone();
                    toEquip.setAmount(1);
                    WardrobeManager.EquipAttemptResult attempt = manager.prepareAndEquipSlotItem(player, targetSlot, toEquip, false);
                    if (!attempt.allowed()) {
                        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.65f, 0.9f);
                        if (attempt.errorMessage() != null) {
                            player.sendMessage(TextUtil.parse(attempt.errorMessage()));
                        }
                        return;
                    }
                    if (cursor.getAmount() > 1) {
                        ItemStack rem = cursor.clone();
                        rem.setAmount(cursor.getAmount() - 1);
                        player.setItemOnCursor(rem);
                    } else {
                        player.setItemOnCursor(previous);
                    }
                    if (attempt.unequippedConflicts() > 0) {
                        player.sendMessage(TextUtil.parse(config.getPrefixedMessage("equip-auto-unequipped",
                                        "<yellow>Снято конфликтующих вещей: <white><count></white> <gray>(возвращены в инвентарь).")
                                .replace("<count>", String.valueOf(attempt.unequippedConflicts()))));
                    }
                    player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_GENERIC, 0.8f, 1.1f);
                    player.sendMessage(TextUtil.parse(
                            config.getPrefixedMessage("quick-equipped", "Экипировано в слот <slot>!"),
                            Map.of("slot", targetSlot.displayName())
                    ));
                    return;
                }
            }
        }

        // Guard real vanilla armor slot if blocked by a full-suit item!
        if (hasCursor) {
            WardrobeSlotDef realSlot = config.findSlotByKindAndEquipment(dev.mythicwardrobe.model.SlotKind.ARMOR_REAL, section.getEquipmentSlot());
            if (realSlot != null) {
                var blocker = manager.getBlockerFor(player, realSlot.id());
                if (blocker.isPresent() && !blocker.get().sourceId().equalsIgnoreCase(realSlot.id())) {
                    event.setCancelled(true);
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.65f, 0.9f);
                    player.sendMessage(TextUtil.parse(config.getPrefixedMessage("slot-blocked-by",
                                    "<red>Слот <white><slot></white> <red>заблокирован предметом <yellow><blocker></yellow><red>!")
                            .replace("<slot>", realSlot.displayName().replaceAll("<[^>]+>", ""))
                            .replace("<blocker>", blocker.get().blockerName())));
                    return;
                }
            }
        }

        boolean shouldOpen = switch (config.getClickMode()) {
            case ALWAYS -> true;
            case RIGHT_CLICK_ONLY -> event.isRightClick();
            case EMPTY_OR_RIGHT -> !hasCursor || event.isRightClick();
        };

        if (!shouldOpen) return;

        event.setCancelled(true);
        if (hasCursor) {
            // Safely return cursor item to player inventory before opening GUI
            ItemStack curCopy = cursor.clone();
            player.setItemOnCursor(null);
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(curCopy);
            overflow.values().forEach(rem -> player.getWorld().dropItemNaturally(player.getLocation(), rem));
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                guiManager.openSection(player, section);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof WardrobeGuiHolder) {
            guiManager.handleDrag(event);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof WardrobeGuiHolder) {
            guiManager.handleClose(event);
        }
        if (event.getPlayer() instanceof Player player) {
            manager.onAnyInventoryClosed(player);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!config.isRightClickQuickEquip()) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR) return;

        Player player = event.getPlayer();
        if (!player.hasPermission("mythicwardrobe.quickequip")) return;

        ItemStack item = event.getItem();
        if (item == null || item.getType().isAir()) return;

        ItemWardrobeMeta meta = resolver.resolve(item);
        WardrobeSlotDef targetSlot = null;

        // If item is an Equippable 3D armor model (e.g. mask, backpack, helmet, vest):
        //   - When the real vanilla armor slot is EMPTY, let vanilla equip it standalone immediately!
        //   - When the real armor slot is ALREADY OCCUPIED, smartly quick-equip it into an empty
        //     3D overlay slot (mask_3d / backpack_3d) so BOTH 3D models wear simultaneously!
        EquipmentSlot eq = resolver.detectEquipmentSlot(item);
        if (eq != null && (!meta.hasSlot() || !isSkinLayerMeta(meta))) {
            ItemStack currentlyWorn = player.getInventory().getItem(eq);
            if (currentlyWorn == null || currentlyWorn.getType().isAir()) {
                // Guard vanilla right-click armor equip if the real armor slot is blocked by a suit!
                WardrobeSlotDef realSlot = config.findSlotByKindAndEquipment(dev.mythicwardrobe.model.SlotKind.ARMOR_REAL, eq);
                if (realSlot != null) {
                    var blocker = manager.getBlockerFor(player, realSlot.id());
                    if (blocker.isPresent() && !blocker.get().sourceId().equalsIgnoreCase(realSlot.id())) {
                        event.setCancelled(true);
                        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.65f, 0.9f);
                        player.sendMessage(TextUtil.parse(config.getPrefixedMessage("slot-blocked-by",
                                        "<red>Слот <white><slot></white> <red>заблокирован предметом <yellow><blocker></yellow><red>!")
                                .replace("<slot>", realSlot.displayName().replaceAll("<[^>]+>", ""))
                                .replace("<blocker>", blocker.get().blockerName())));
                        return;
                    }
                }
                return; // Let vanilla equip standalone onto player's real armor slot!
            }
            for (WardrobeSlotDef candidate : config.getDisplay3DSlots()) {
                if (candidate.section() != null && candidate.section().getEquipmentSlot() == eq
                        && resolver.canPlaceInSlot(item, candidate)) {
                    if (manager.getBlockerFor(player, candidate.id()).isPresent()) continue;
                    ItemStack cur = manager.getSlotItem(player, candidate);
                    if (cur == null || cur.getType().isAir()) {
                        targetSlot = candidate;
                        break;
                    }
                }
            }
            if (targetSlot == null) return;
        } else {
            if (!meta.hasSlot()) return;
            targetSlot = config.getSlot(meta.primarySlotId());
            if (targetSlot == null || (!targetSlot.isSkinLayer() && !targetSlot.isDisplay3D())) return;
            if (!resolver.canPlaceInSlot(item, targetSlot)) return;
        }

        event.setCancelled(true);
        ItemStack previous = manager.getSlotItem(player, targetSlot);
        ItemStack toEquip = item.clone();
        toEquip.setAmount(1);

        WardrobeManager.EquipAttemptResult attempt = manager.prepareAndEquipSlotItem(player, targetSlot, toEquip, false);
        if (!attempt.allowed()) {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.65f, 0.9f);
            if (attempt.errorMessage() != null) {
                player.sendMessage(TextUtil.parse(attempt.errorMessage()));
            }
            return;
        }

        if (item.getAmount() > 1) {
            item.setAmount(item.getAmount() - 1);
            if (previous != null && !previous.getType().isAir()) {
                Map<Integer, ItemStack> overflow = player.getInventory().addItem(previous);
                overflow.values().forEach(rem -> player.getWorld().dropItemNaturally(player.getLocation(), rem));
            }
        } else {
            player.getInventory().setItemInMainHand(previous);
        }

        if (attempt.unequippedConflicts() > 0) {
            player.sendMessage(TextUtil.parse(config.getPrefixedMessage("equip-auto-unequipped",
                            "<yellow>Снято конфликтующих вещей: <white><count></white> <gray>(возвращены в инвентарь).")
                    .replace("<count>", String.valueOf(attempt.unequippedConflicts()))));
        }

        player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_GENERIC, 0.8f, 1.15f);
        player.sendMessage(TextUtil.parse(
                config.getPrefixedMessage("quick-equipped", "Экипировано в слот <slot>!"),
                Map.of("slot", targetSlot.displayName())
        ));
    }

    private boolean isSkinLayerMeta(ItemWardrobeMeta meta) {
        if (!meta.hasSlot()) return false;
        for (String part : meta.slotId().split(",")) {
            WardrobeSlotDef def = config.getSlot(part.trim());
            if (def != null && def.isSkinLayer()) return true;
        }
        return false;
    }

    @EventHandler
    public void onPlayerArmorChange(PlayerArmorChangeEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            manager.getArmorVisualManager().refreshPlayerArmorVisuals(player);
            manager.getDisplayAccessoryManager().syncPlayerDisplays(player);
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        manager.handleJoin(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        guiManager.clearNavHistory(event.getPlayer().getUniqueId());
        manager.handleQuit(event.getPlayer());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        manager.handleDeath(event.getEntity(), event.getDrops());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        manager.handleRespawnOrWorldChange(event.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        manager.handleRespawnOrWorldChange(event.getPlayer());
    }

    @EventHandler
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            if (player.getGameMode() == GameMode.CREATIVE) {
                // Rebuild snapshot (clears Survival-only self-promotion) and restore true inventory view
                manager.getArmorVisualManager().rebuildSnapshot(player);
                player.updateInventory();
            } else {
                manager.getArmorVisualManager().refreshPlayerArmorVisuals(player);
            }
            manager.getDisplayAccessoryManager().syncPlayerDisplays(player);
        }, 2L);
    }
}
