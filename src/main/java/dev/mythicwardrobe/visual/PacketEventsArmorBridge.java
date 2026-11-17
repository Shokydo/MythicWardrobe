package dev.mythicwardrobe.visual;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.nbt.NBTByte;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCreativeInventoryAction;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetCursorItem;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import dev.mythicwardrobe.config.WardrobeConfig;
import dev.mythicwardrobe.model.ArmorSection;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Netty-thread-safe PacketEvents 2.x interceptor for cosmetic & combo MythicArmors.
 * Reads ONLY immutable precomputed PlayerVisualSnapshot objects on Netty threads.
 *
 * Works identically in Survival AND Creative mode: visual self-override packets carry an
 * invisible visual_proxy marker in minecraft:custom_data, and incoming CREATIVE_INVENTORY_ACTION
 * packets are filtered by {@link CreativeArmorGuard} so real armor is never deleted or overwritten
 * and visual proxy items can never be duplicated into inventories.
 */
public final class PacketEventsArmorBridge {

    private final JavaPlugin plugin;
    private final WardrobeConfig config;
    private final ArmorVisualManager visualManager;
    private PacketListenerCommon registeredListener;

    // Coalesces bursts of cancelled Creative packets (e.g. Shift+Click Destroy Item across slots 5..8)
    // into a single main-thread resync per player.
    private final Set<UUID> pendingArmorResync = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingFullResync = ConcurrentHashMap.newKeySet();

    public PacketEventsArmorBridge(JavaPlugin plugin, WardrobeConfig config, ArmorVisualManager visualManager) {
        this.plugin = plugin;
        this.config = config;
        this.visualManager = visualManager;
    }

    public void register() {
        PacketListenerAbstract listener = new PacketListenerAbstract(PacketListenerPriority.NORMAL) {
            @Override
            public void onPacketSend(PacketSendEvent event) {
                if (!config.isCosmeticArmorEnabled()) return;

                if (event.getPacketType() == PacketType.Play.Server.ENTITY_EQUIPMENT) {
                    handleEntityEquipment(event);
                } else if (event.getPacketType() == PacketType.Play.Server.SPAWN_ENTITY) {
                    handleSpawnEntity(event);
                } else if (config.isShowCosmeticToSelf() && event.getPacketType() == PacketType.Play.Server.SET_SLOT) {
                    handleSetSlot(event);
                } else if (config.isShowCosmeticToSelf() && event.getPacketType() == PacketType.Play.Server.WINDOW_ITEMS) {
                    handleWindowItems(event);
                }
            }

            @Override
            public void onPacketReceive(PacketReceiveEvent event) {
                if (event.getPacketType() == PacketType.Play.Client.CREATIVE_INVENTORY_ACTION) {
                    handleCreativeInventoryAction(event);
                }
            }
        };
        this.registeredListener = PacketEvents.getAPI().getEventManager().registerListener(listener);
    }

    public void unregister() {
        if (registeredListener != null) {
            try {
                PacketEvents.getAPI().getEventManager().unregisterListener(registeredListener);
            } catch (Throwable ignored) {
            }
            registeredListener = null;
        }
        pendingArmorResync.clear();
        pendingFullResync.clear();
    }

    private void handleEntityEquipment(PacketSendEvent event) {
        WrapperPlayServerEntityEquipment wrapper = new WrapperPlayServerEntityEquipment(event);
        ArmorVisualManager.PlayerVisualSnapshot snap = visualManager.getSnapshotByEntityId(wrapper.getEntityId());
        if (snap == null || !snap.hasOverrides()) return;

        List<Equipment> list = new ArrayList<>(wrapper.getEquipment());
        Set<EquipmentSlot> presentSlots = EnumSet.noneOf(EquipmentSlot.class);
        boolean modified = false;

        for (Equipment eq : list) {
            EquipmentSlot bukkitSlot = toBukkitSlot(eq.getSlot());
            if (bukkitSlot == null) continue;
            presentSlots.add(bukkitSlot);
            ItemStack override = snap.overrides().get(bukkitSlot);
            if (override != null) {
                eq.setItem(SpigotConversionUtil.fromBukkitItemStack(override));
                modified = true;
            }
        }

        // Append overridden armor slots even if the real armor slot was empty (so cosmetic armor renders over bare skin!)
        for (Map.Entry<EquipmentSlot, ItemStack> entry : snap.overrides().entrySet()) {
            if (!presentSlots.contains(entry.getKey())) {
                list.add(new Equipment(
                        toPacketSlot(entry.getKey()),
                        SpigotConversionUtil.fromBukkitItemStack(entry.getValue())
                ));
                modified = true;
            }
        }

        if (modified) {
            wrapper.setEquipment(list);
            event.markForReEncode(true);
        }
    }

    private void handleSpawnEntity(PacketSendEvent event) {
        WrapperPlayServerSpawnEntity wrapper = new WrapperPlayServerSpawnEntity(event);
        ArmorVisualManager.PlayerVisualSnapshot snap = visualManager.getSnapshotByEntityId(wrapper.getEntityId());
        if (snap == null || !snap.hasOverrides()) return;

        User user = event.getUser();
        if (user == null || user.getUUID() == null) return;
        UUID viewerUuid = user.getUUID();
        UUID targetUuid = snap.playerId();
        if (viewerUuid.equals(targetUuid)) return;

        // Schedule a 2-tick follow-up equipment packet so newly tracked players always see cosmetic armor
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player viewer = Bukkit.getPlayer(viewerUuid);
            Player target = Bukkit.getPlayer(targetUuid);
            if (viewer != null && target != null && viewer.isOnline() && target.isOnline()) {
                sendArmorToViewer(viewer, target);
            }
        }, 2L);
    }

    private void handleSetSlot(PacketSendEvent event) {
        User user = event.getUser();
        if (user == null || user.getUUID() == null) return;
        ArmorVisualManager.PlayerVisualSnapshot snap = visualManager.getSnapshotByUuid(user.getUUID());
        if (snap == null || !snap.hasOverrides() || snap.wardrobeGuiOpen()) return;

        WrapperPlayServerSetSlot wrapper = new WrapperPlayServerSetSlot(event);
        if (wrapper.getWindowId() != 0) return;

        EquipmentSlot eqSlot = CreativeArmorGuard.containerSlotToEquipmentSlot(wrapper.getSlot());
        if (eqSlot == null) return;

        ItemStack override = snap.overrides().get(eqSlot);
        if (override != null) {
            wrapper.setItem(toMarkedSelfPacketItem(override));
            event.markForReEncode(true);
        }
    }

    private void handleWindowItems(PacketSendEvent event) {
        User user = event.getUser();
        if (user == null || user.getUUID() == null) return;
        ArmorVisualManager.PlayerVisualSnapshot snap = visualManager.getSnapshotByUuid(user.getUUID());
        if (snap == null || !snap.hasOverrides() || snap.wardrobeGuiOpen()) return;

        WrapperPlayServerWindowItems wrapper = new WrapperPlayServerWindowItems(event);
        if (wrapper.getWindowId() != 0) return;

        List<com.github.retrooper.packetevents.protocol.item.ItemStack> items = wrapper.getItems();
        if (items == null || items.size() <= 8) return;

        boolean modified = false;
        for (ArmorSection sec : ArmorSection.values()) {
            ItemStack override = snap.overrides().get(sec.getEquipmentSlot());
            if (override != null) {
                items.set(sec.getPlayerContainerSlot(), toMarkedSelfPacketItem(override));
                modified = true;
            }
        }
        if (modified) {
            wrapper.setItems(items);
            event.markForReEncode(true);
        }
    }

    private void handleCreativeInventoryAction(PacketReceiveEvent event) {
        User user = event.getUser();
        if (user == null || user.getUUID() == null) return;
        UUID uuid = user.getUUID();

        WrapperPlayClientCreativeInventoryAction wrapper = new WrapperPlayClientCreativeInventoryAction(event);
        int slot = wrapper.getSlot();
        com.github.retrooper.packetevents.protocol.item.ItemStack incoming = wrapper.getItemStack();
        boolean incomingEmpty = (incoming == null || incoming.isEmpty());
        boolean incomingIsProxy = !incomingEmpty && isVisualProxyPacketItem(incoming);

        EquipmentSlot eqSlot = CreativeArmorGuard.containerSlotToEquipmentSlot(slot);
        ArmorVisualManager.PlayerVisualSnapshot snap = visualManager.getSnapshotByUuid(uuid);
        boolean slotHasOverride = (eqSlot != null && snap != null && snap.hasOverrideFor(eqSlot));

        CreativeArmorGuard.Decision decision = CreativeArmorGuard.evaluatePacketAction(
                slot,
                incomingEmpty,
                incomingIsProxy,
                slotHasOverride
        );

        switch (decision) {
            case CANCEL_AND_RESYNC_ARMOR -> {
                event.setCancelled(true);
                scheduleCreativeResync(uuid, false);
            }
            case CANCEL_AND_RESYNC_INVENTORY -> {
                event.setCancelled(true);
                scheduleCreativeResync(uuid, true);
            }
            case ALLOW_REAL_EQUIP_PRESERVE_OLD, ALLOW -> {
                // Handled normally on the main thread (with InventoryCreativeEvent guard for real armor swap)
            }
        }
    }

    private void scheduleCreativeResync(UUID uuid, boolean fullInventory) {
        if (fullInventory) {
            pendingFullResync.add(uuid);
        }
        if (!pendingArmorResync.add(uuid)) {
            return; // Already scheduled for next tick
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            pendingArmorResync.remove(uuid);
            boolean doFull = pendingFullResync.remove(uuid);
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) return;

            clearClientCursor(player);
            if (doFull) {
                player.updateInventory();
            }
            syncSelfArmor(player);
        });
    }

    public void broadcastArmor(Player target) {
        WrapperPlayServerEntityEquipment packet = buildEquipmentPacket(target);
        for (Player viewer : target.getWorld().getPlayers()) {
            if (viewer.getUniqueId().equals(target.getUniqueId())) continue;
            if (!viewer.canSee(target)) continue;
            try {
                PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
            } catch (Throwable ignored) {
            }
        }
    }

    public void sendArmorToViewer(Player viewer, Player target) {
        try {
            PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, buildEquipmentPacket(target));
        } catch (Throwable ignored) {
        }
    }

    public void syncSelfArmor(Player player) {
        if (!config.isShowCosmeticToSelf()) return;
        ArmorVisualManager.PlayerVisualSnapshot snap = visualManager.getSnapshotByUuid(player.getUniqueId());
        if (snap != null && snap.wardrobeGuiOpen()) return;

        for (ArmorSection sec : ArmorSection.values()) {
            EquipmentSlot eqSlot = sec.getEquipmentSlot();
            ItemStack override = snap != null ? snap.overrides().get(eqSlot) : null;
            com.github.retrooper.packetevents.protocol.item.ItemStack packetItem;
            if (override != null) {
                packetItem = toMarkedSelfPacketItem(override);
            } else {
                ItemStack real = player.getInventory().getItem(eqSlot);
                packetItem = SpigotConversionUtil.fromBukkitItemStack(real == null ? ItemStack.empty() : real);
            }
            WrapperPlayServerSetSlot packet = new WrapperPlayServerSetSlot(
                    0, 0, sec.getPlayerContainerSlot(), packetItem
            );
            try {
                PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
            } catch (Throwable ignored) {
            }
        }
    }

    public void clearClientCursor(Player player) {
        if (player == null || !player.isOnline()) return;
        try {
            WrapperPlayServerSetCursorItem cursorPacket = new WrapperPlayServerSetCursorItem(
                    com.github.retrooper.packetevents.protocol.item.ItemStack.EMPTY
            );
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, cursorPacket);
        } catch (Throwable ignored) {
        }
    }

    private WrapperPlayServerEntityEquipment buildEquipmentPacket(Player target) {
        List<Equipment> list = new ArrayList<>(4);
        for (ArmorSection sec : ArmorSection.values()) {
            ItemStack visual = visualManager.getEffectiveVisualArmor(target, sec.getEquipmentSlot());
            list.add(new Equipment(toPacketSlot(sec.getEquipmentSlot()), SpigotConversionUtil.fromBukkitItemStack(visual)));
        }
        return new WrapperPlayServerEntityEquipment(target.getEntityId(), list);
    }

    /**
     * Converts a visual override ItemStack into a PacketEvents ItemStack stamped with an invisible
     * visual_proxy flag inside minecraft:custom_data (and PublicBukkitValues for Bukkit PDC).
     * Never mutates Bukkit ItemMeta, so all 1.21.8+ DataComponents (ITEM_MODEL, EQUIPPABLE,
     * CUSTOM_MODEL_DATA strings, DYED_COLOR) stay 100% intact!
     */
    static com.github.retrooper.packetevents.protocol.item.ItemStack toMarkedSelfPacketItem(ItemStack bukkitItem) {
        com.github.retrooper.packetevents.protocol.item.ItemStack peItem =
                SpigotConversionUtil.fromBukkitItemStack(bukkitItem);
        if (peItem == null || peItem.isEmpty()) return peItem;

        NBTCompound existing = peItem.getComponentOr(ComponentTypes.CUSTOM_DATA, null);
        NBTCompound customData = existing != null ? existing.copy() : new NBTCompound();
        customData.setTag(CreativeArmorGuard.VISUAL_PROXY_NBT_FLAG, new NBTByte((byte) 1));

        NBTCompound pbv = customData.getCompoundTagOrNull("PublicBukkitValues");
        NBTCompound pbvCopy = pbv != null ? pbv.copy() : new NBTCompound();
        pbvCopy.setTag(CreativeArmorGuard.VISUAL_PROXY_KEY_STRING, new NBTByte((byte) 1));
        customData.setTag("PublicBukkitValues", pbvCopy);

        peItem.setComponent(ComponentTypes.CUSTOM_DATA, customData);
        return peItem;
    }

    static boolean isVisualProxyPacketItem(com.github.retrooper.packetevents.protocol.item.ItemStack item) {
        if (item == null || item.isEmpty()) return false;
        NBTCompound customData = item.getComponentOr(ComponentTypes.CUSTOM_DATA, null);
        if (hasVisualProxyTag(customData)) return true;
        return hasVisualProxyTag(item.getNBT());
    }

    private static boolean hasVisualProxyTag(NBTCompound nbt) {
        if (nbt == null || nbt.isEmpty()) return false;
        if (nbt.getTagOrNull(CreativeArmorGuard.VISUAL_PROXY_NBT_FLAG) != null) {
            return true;
        }
        NBTCompound pbv = nbt.getCompoundTagOrNull("PublicBukkitValues");
        return pbv != null && pbv.getTagOrNull(CreativeArmorGuard.VISUAL_PROXY_KEY_STRING) != null;
    }

    private static EquipmentSlot toBukkitSlot(com.github.retrooper.packetevents.protocol.player.EquipmentSlot peSlot) {
        if (peSlot == null) return null;
        return switch (peSlot) {
            case HELMET -> EquipmentSlot.HEAD;
            case CHEST_PLATE -> EquipmentSlot.CHEST;
            case LEGGINGS -> EquipmentSlot.LEGS;
            case BOOTS -> EquipmentSlot.FEET;
            default -> null;
        };
    }

    private static com.github.retrooper.packetevents.protocol.player.EquipmentSlot toPacketSlot(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> com.github.retrooper.packetevents.protocol.player.EquipmentSlot.HELMET;
            case CHEST -> com.github.retrooper.packetevents.protocol.player.EquipmentSlot.CHEST_PLATE;
            case LEGS -> com.github.retrooper.packetevents.protocol.player.EquipmentSlot.LEGGINGS;
            case FEET -> com.github.retrooper.packetevents.protocol.player.EquipmentSlot.BOOTS;
            default -> com.github.retrooper.packetevents.protocol.player.EquipmentSlot.HELMET;
        };
    }
}
