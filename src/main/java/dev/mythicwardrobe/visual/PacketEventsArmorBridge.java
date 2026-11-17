package dev.mythicwardrobe.visual;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import dev.mythicwardrobe.config.WardrobeConfig;
import dev.mythicwardrobe.model.ArmorSection;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

/**
 * Netty-thread-safe PacketEvents 2.x interceptor for cosmetic & combo MythicArmors.
 * Reads ONLY immutable precomputed PlayerVisualSnapshot objects on Netty threads.
 */
public final class PacketEventsArmorBridge {

    private final JavaPlugin plugin;
    private final WardrobeConfig config;
    private final ArmorVisualManager visualManager;
    private PacketListenerCommon registeredListener;

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
        if (snap == null || !snap.hasOverrides() || snap.creativeMode() || snap.wardrobeGuiOpen()) return;

        WrapperPlayServerSetSlot wrapper = new WrapperPlayServerSetSlot(event);
        if (wrapper.getWindowId() != 0) return;

        EquipmentSlot eqSlot = containerSlotToEquipmentSlot(wrapper.getSlot());
        if (eqSlot == null) return;

        ItemStack override = snap.overrides().get(eqSlot);
        if (override != null) {
            wrapper.setItem(SpigotConversionUtil.fromBukkitItemStack(override));
            event.markForReEncode(true);
        }
    }

    private void handleWindowItems(PacketSendEvent event) {
        User user = event.getUser();
        if (user == null || user.getUUID() == null) return;
        ArmorVisualManager.PlayerVisualSnapshot snap = visualManager.getSnapshotByUuid(user.getUUID());
        if (snap == null || !snap.hasOverrides() || snap.creativeMode() || snap.wardrobeGuiOpen()) return;

        WrapperPlayServerWindowItems wrapper = new WrapperPlayServerWindowItems(event);
        if (wrapper.getWindowId() != 0) return;

        List<com.github.retrooper.packetevents.protocol.item.ItemStack> items = wrapper.getItems();
        if (items == null || items.size() <= 8) return;

        boolean modified = false;
        for (ArmorSection sec : ArmorSection.values()) {
            ItemStack override = snap.overrides().get(sec.getEquipmentSlot());
            if (override != null) {
                items.set(sec.getPlayerContainerSlot(), SpigotConversionUtil.fromBukkitItemStack(override));
                modified = true;
            }
        }
        if (modified) {
            wrapper.setItems(items);
            event.markForReEncode(true);
        }
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
        if (!config.isShowCosmeticToSelf() || player.getGameMode() == GameMode.CREATIVE) return;
        ArmorVisualManager.PlayerVisualSnapshot snap = visualManager.getSnapshotByUuid(player.getUniqueId());
        if (snap != null && snap.wardrobeGuiOpen()) return;

        for (ArmorSection sec : ArmorSection.values()) {
            ItemStack visual = visualManager.getEffectiveVisualArmor(player, sec.getEquipmentSlot());
            WrapperPlayServerSetSlot packet = new WrapperPlayServerSetSlot(
                    0, 0, sec.getPlayerContainerSlot(), SpigotConversionUtil.fromBukkitItemStack(visual)
            );
            try {
                PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
            } catch (Throwable ignored) {
            }
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

    private static EquipmentSlot containerSlotToEquipmentSlot(int containerSlot) {
        return switch (containerSlot) {
            case 5 -> EquipmentSlot.HEAD;
            case 6 -> EquipmentSlot.CHEST;
            case 7 -> EquipmentSlot.LEGS;
            case 8 -> EquipmentSlot.FEET;
            default -> null;
        };
    }
}
