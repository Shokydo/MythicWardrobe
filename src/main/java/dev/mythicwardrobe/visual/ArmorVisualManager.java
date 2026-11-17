package dev.mythicwardrobe.visual;

import dev.mythicwardrobe.config.WardrobeConfig;
import dev.mythicwardrobe.hook.MythicMobsHook;
import dev.mythicwardrobe.manager.WardrobeItemResolver;
import dev.mythicwardrobe.model.*;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import io.papermc.paper.datacomponent.item.DyedItemColor;
import io.papermc.paper.datacomponent.item.Equippable;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Computes effective visual armor per slot (Combo MythicArmors override > Cosmetic Armor > Real Armor)
 * on the main thread, publishes a lock-free snapshot for Netty-thread-safe PacketEvents interception,
 * and syncs visuals to viewers & self (with Paper sendEquipmentChange fallback).
 */
public final class ArmorVisualManager {

    public record PlayerVisualSnapshot(
            UUID playerId,
            int entityId,
            boolean creativeMode,
            boolean wardrobeGuiOpen,
            Map<EquipmentSlot, ItemStack> overrides
    ) {
        public boolean hasOverrides() {
            return overrides != null && !overrides.isEmpty();
        }
    }

    private final JavaPlugin plugin;
    private final WardrobeConfig config;
    private final WardrobeItemResolver resolver;
    private final MythicMobsHook mythicHook;

    private final Map<UUID, PlayerWardrobeState> states;
    private final Map<UUID, Set<String>> comboHiddenSlots = new ConcurrentHashMap<>();
    private final Set<UUID> openWardrobeGuis = ConcurrentHashMap.newKeySet();
    private volatile Function<Player, Set<String>> blockedSlotsProvider = p -> Set.of();

    // Thread-safe snapshots read from Netty threads by PacketEventsArmorBridge
    private final Map<UUID, PlayerVisualSnapshot> snapshotByUuid = new ConcurrentHashMap<>();
    private final Map<Integer, PlayerVisualSnapshot> snapshotByEntityId = new ConcurrentHashMap<>();

    private PacketEventsArmorBridge packetBridge;

    public ArmorVisualManager(
            JavaPlugin plugin,
            WardrobeConfig config,
            WardrobeItemResolver resolver,
            MythicMobsHook mythicHook,
            Map<UUID, PlayerWardrobeState> states
    ) {
        this.plugin = plugin;
        this.config = config;
        this.resolver = resolver;
        this.mythicHook = mythicHook;
        this.states = states;
    }

    public void enablePacketEventsIfPresent() {
        if (Bukkit.getPluginManager().isPluginEnabled("packetevents")) {
            try {
                this.packetBridge = new PacketEventsArmorBridge(plugin, config, this);
                this.packetBridge.register();
                plugin.getLogger().info("PacketEvents detected — enabled full packet-level cosmetic & combo MythicArmors sync!");
            } catch (Throwable t) {
                plugin.getLogger().warning("PacketEvents detected but hook failed (" + t.getMessage() + "), using Paper sendEquipmentChange fallback.");
                this.packetBridge = null;
            }
        } else {
            plugin.getLogger().info("PacketEvents not found — using Paper sendEquipmentChange fallback for cosmetic armor (install PacketEvents for full packet interception & F5 self-preview).");
        }
    }

    public void disable() {
        if (packetBridge != null) {
            packetBridge.unregister();
            packetBridge = null;
        }
        comboHiddenSlots.clear();
        openWardrobeGuis.clear();
        snapshotByUuid.clear();
        snapshotByEntityId.clear();
    }

    public boolean isPacketEventsActive() {
        return packetBridge != null;
    }

    public void setWardrobeGuiOpen(Player player, boolean open) {
        if (player == null) return;
        if (open) {
            openWardrobeGuis.add(player.getUniqueId());
        } else {
            openWardrobeGuis.remove(player.getUniqueId());
        }
        rebuildSnapshot(player);
    }

    public void setBlockedSlotsProvider(Function<Player, Set<String>> provider) {
        this.blockedSlotsProvider = provider != null ? provider : (p -> Set.of());
    }

    public boolean isDisplayHiddenByCombo(UUID playerId, String secondarySlotId) {
        Set<String> hidden = comboHiddenSlots.get(playerId);
        return hidden != null && hidden.contains(secondarySlotId.toLowerCase(Locale.ROOT));
    }

    public PlayerVisualSnapshot getSnapshotByUuid(UUID playerId) {
        return snapshotByUuid.get(playerId);
    }

    public PlayerVisualSnapshot getSnapshotByEntityId(int entityId) {
        return snapshotByEntityId.get(entityId);
    }

    /**
     * Recomputes visual overrides & combo suppression strictly on the main thread and atomically updates Netty snapshots.
     */
    public PlayerVisualSnapshot rebuildSnapshot(Player player) {
        if (player == null) return null;
        UUID uuid = player.getUniqueId();
        PlayerWardrobeState state = states.get(uuid);

        Map<EquipmentSlot, ItemStack> overrides = new EnumMap<>(EquipmentSlot.class);
        Set<String> hiddenSecondary = new HashSet<>();
        Set<String> blockedSlots = blockedSlotsProvider.apply(player);
        if (blockedSlots == null) blockedSlots = Set.of();
        // Suppress any 3D overlay/display slot currently blocked (e.g. in HIDE block mode)
        for (String b : blockedSlots) {
            hiddenSecondary.add(b.toLowerCase(Locale.ROOT));
        }

        if (config.isCosmeticArmorEnabled() && state != null) {
            for (ArmorSection sec : ArmorSection.values()) {
                EquipmentSlot eqSlot = sec.getEquipmentSlot();
                WardrobeSlotDef cosmeticDef = config.findSlotByKindAndEquipment(SlotKind.ARMOR_COSMETIC, eqSlot);
                boolean cosmeticBlocked = cosmeticDef != null && blockedSlots.contains(cosmeticDef.id());
                ItemStack cosmeticItem = (cosmeticDef != null && !cosmeticBlocked) ? state.peekSlotItem(cosmeticDef.id()) : null;
                ItemStack realItem = player.getInventory().getItem(eqSlot);

                ItemStack primaryVisual = (cosmeticItem != null && !cosmeticItem.getType().isAir()) ? cosmeticItem : realItem;
                String primaryMythicId = primaryVisual != null ? resolver.resolve(primaryVisual).mythicId() : null;

                ItemStack resolvedOverride = null;

                boolean canNativeSelfPromote = packetBridge != null
                        && config.isShowCosmeticToSelf()
                        && player.getGameMode() != GameMode.CREATIVE;

                // 0. Native Minecraft 1.21.4+ `minecraft:composite` unlimited HEAD 3D stacking!
                //    Combines 3D Hair (`hair_3d`) + 3D Mask (`mask_3d`) + 3D Helmet (`helmet_*`) + any extra
                //    HEAD 3D slots into ONE zero-lag vanilla HEAD equipment packet (0 ArmorStands!) when
                //    `/wardrobe buildheadpack` has generated `head_composite_manifest.json`!
                if (sec == ArmorSection.HEAD && canNativeSelfPromote) {
                    HeadCompositePackBuilder.Manifest manifest = config.getHeadCompositeManifest();
                    if (manifest != null) {
                        ItemStack composite = tryBuildHeadComposite(player, state, primaryVisual, manifest, hiddenSecondary, blockedSlots);
                        if (composite != null) {
                            resolvedOverride = composite;
                        }
                    }
                }

                // 1. Check MythicArmors combo-overrides.yml
                if (resolvedOverride == null && primaryMythicId != null && mythicHook != null) {
                    for (ComboOverrideRule rule : config.getComboRules()) {
                        if (rule.equipmentSlot() != eqSlot) continue;
                        if (blockedSlots.contains(rule.secondarySlotId().toLowerCase(Locale.ROOT))) continue;
                        WardrobeSlotDef secSlotDef = config.getSlot(rule.secondarySlotId());
                        ItemStack secItem = secSlotDef != null
                                ? resolveEffectiveSlot3DItem(player, state, secSlotDef, blockedSlots)
                                : state.peekSlotItem(rule.secondarySlotId());
                        if (secItem == null || secItem.getType().isAir()) continue;
                        String secId = resolver.resolve(secItem).mythicId();
                        if (rule.matches(primaryMythicId, secId)) {
                            ItemStack comboItem = mythicHook.generateMythicItem(rule.resultMythicItem());
                            if (comboItem != null && !comboItem.getType().isAir()) {
                                resolvedOverride = comboItem;
                                if (rule.hideSecondaryDisplay()) {
                                    hiddenSecondary.add(rule.secondarySlotId().toLowerCase(Locale.ROOT));
                                }
                                break;
                            }
                        }
                    }
                }

                // 2. Fallback to cosmetic armor item
                if (resolvedOverride == null && cosmeticItem != null && !cosmeticItem.getType().isAir()) {
                    resolvedOverride = cosmeticItem.clone();
                }

                // 3. Standalone DISPLAY_3D / ARMOR_OVERLAY promotion:
                boolean primaryEmpty = (primaryVisual == null || primaryVisual.getType().isAir());
                if (resolvedOverride == null && primaryEmpty && canNativeSelfPromote) {
                    for (WardrobeSlotDef overlayDef : config.getSlotsForSection(sec)) {
                        if (!overlayDef.isDisplay3D()) continue;
                        if (blockedSlots.contains(overlayDef.id())) continue;
                        ItemStack overlayItem = resolveEffectiveSlot3DItem(player, state, overlayDef, blockedSlots);
                        if (overlayItem == null || overlayItem.getType().isAir()) continue;
                        if (resolver.detectEquipmentSlot(overlayItem) == eqSlot) {
                            resolvedOverride = overlayItem.clone();
                            hiddenSecondary.add(overlayDef.id().toLowerCase(Locale.ROOT));
                            break;
                        }
                    }
                }

                if (resolvedOverride != null) {
                    overrides.put(eqSlot, resolvedOverride);
                }
            }
        }

        if (hiddenSecondary.isEmpty()) {
            comboHiddenSlots.remove(uuid);
        } else {
            comboHiddenSlots.put(uuid, hiddenSecondary);
        }

        PlayerVisualSnapshot prev = snapshotByUuid.get(uuid);
        if (prev != null && prev.entityId() != player.getEntityId()) {
            snapshotByEntityId.remove(prev.entityId());
        }

        PlayerVisualSnapshot snapshot = new PlayerVisualSnapshot(
                uuid,
                player.getEntityId(),
                player.getGameMode() == GameMode.CREATIVE,
                openWardrobeGuis.contains(uuid),
                overrides.isEmpty() ? Map.of() : Collections.unmodifiableMap(overrides)
        );
        snapshotByUuid.put(uuid, snapshot);
        snapshotByEntityId.put(player.getEntityId(), snapshot);
        return snapshot;
    }

    /**
     * Resolves the effective 3D item to render for a DISPLAY_3D / ARMOR_OVERLAY slot.
     * For `hair_3d`, automatically supports:
     *   1) Auto-binding from the player's active 2D hair texture (`hair-3d.yml` -> `bindings:`),
     *   2) Helmet-aware full hiding (`HIDE_ALL`) or partial-model swapping (`PARTIAL_MODEL`, e.g. hide high ponytail, keep front strands!).
     */
    public ItemStack resolveEffectiveSlot3DItem(Player player, PlayerWardrobeState state, WardrobeSlotDef slotDef) {
        Set<String> blocked = blockedSlotsProvider.apply(player);
        return resolveEffectiveSlot3DItem(player, state, slotDef, blocked != null ? blocked : Set.of());
    }

    public ItemStack resolveEffectiveSlot3DItem(
            Player player,
            PlayerWardrobeState state,
            WardrobeSlotDef slotDef,
            Set<String> blockedSlots
    ) {
        if (player == null || state == null || slotDef == null) return null;
        if (blockedSlots != null && blockedSlots.contains(slotDef.id())) return null;

        if (!"hair_3d".equalsIgnoreCase(slotDef.id())) {
            return state.peekSlotItem(slotDef.id());
        }

        Hair3DConfig hairCfg = config.getHair3DConfig();
        ItemStack manualItem = state.peekSlotItem(slotDef.id());
        boolean hasManual = manualItem != null && !manualItem.getType().isAir();

        ItemStack baseHair = null;
        Hair3DConfig.Hair3DRule rule = null;

        if (hasManual && (hairCfg == null || !hairCfg.isEnabled() || hairCfg.isManualSlotOverridesBinding())) {
            baseHair = manualItem.clone();
            if (hairCfg != null && hairCfg.isEnabled()) {
                String manualMythicId = resolver.resolve(manualItem).mythicId();
                rule = hairCfg.findRuleForManualItem(manualMythicId).orElse(null);
            }
        } else if (hairCfg != null && hairCfg.isEnabled()) {
            String active2DHair = resolveActive2DLayerTexture(state, "hair", blockedSlots);
            Optional<Hair3DConfig.Hair3DRule> bound = hairCfg.findBindingFor2DHair(active2DHair);
            if (bound.isPresent() && mythicHook != null) {
                rule = bound.get();
                ItemStack generated = mythicHook.generateMythicItem(rule.mythicItem());
                if (generated != null && !generated.getType().isAir()) {
                    applyOptionalHairColor(generated, Hair3DConfig.parseColorSpec(rule.colorSpec()), null);
                    baseHair = generated;
                }
            }
            if (baseHair == null && hasManual) {
                baseHair = manualItem.clone();
                String manualMythicId = resolver.resolve(manualItem).mythicId();
                rule = hairCfg.findRuleForManualItem(manualMythicId).orElse(null);
            }
        }

        if (baseHair == null || baseHair.getType().isAir()) return null;
        if (hairCfg == null || !hairCfg.isEnabled() || rule == null) return baseHair;

        List<Hair3DConfig.HelmetDescriptor> activeHelmets = collectActiveHelmets(player, state, blockedSlots);
        if (!hairCfg.isAffectedByHelmets(rule, activeHelmets)) {
            return baseHair;
        }

        return switch (rule.helmetMode()) {
            case KEEP -> baseHair;
            case HIDE_ALL -> null;
            case PARTIAL_MODEL -> {
                if (!rule.hasPartialModel() || mythicHook == null) yield null;
                ItemStack partial = mythicHook.generateMythicItem(rule.helmetPartialItem());
                if (partial == null || partial.getType().isAir()) yield null;
                applyOptionalHairColor(partial, Hair3DConfig.parseColorSpec(rule.colorSpec()), extractDyedColor(baseHair));
                yield partial;
            }
        };
    }

    private String resolveActive2DLayerTexture(PlayerWardrobeState state, String canonicalLayerId, Set<String> blockedSlots) {
        if (state == null || (blockedSlots != null && blockedSlots.contains(canonicalLayerId))) return null;
        WardrobeSlotDef slotDef = config.getSlot(canonicalLayerId);
        if (slotDef != null && slotDef.isSkinLayer()) {
            ItemStack item = state.peekSlotItem(slotDef.id());
            if (item != null && !item.getType().isAir()) {
                ItemWardrobeMeta meta = resolver.resolve(item);
                if (meta.hasTexture()) return meta.texturePath();
            }
        }
        return state.getSkillLayer(canonicalLayerId);
    }

    private List<Hair3DConfig.HelmetDescriptor> collectActiveHelmets(
            Player player,
            PlayerWardrobeState state,
            Set<String> blockedSlots
    ) {
        List<Hair3DConfig.HelmetDescriptor> list = new ArrayList<>(3);

        // 1. Cosmetic helmet
        WardrobeSlotDef cosmeticHead = config.findSlotByKindAndEquipment(SlotKind.ARMOR_COSMETIC, EquipmentSlot.HEAD);
        if (cosmeticHead != null && (blockedSlots == null || !blockedSlots.contains(cosmeticHead.id()))) {
            ItemStack cos = state.peekSlotItem(cosmeticHead.id());
            if (cos != null && !cos.getType().isAir()) {
                list.add(Hair3DConfig.HelmetDescriptor.armor(resolver.resolve(cos).mythicId(), cos.getType().name()));
            }
        }

        // 2. Real helmet
        ItemStack real = player.getInventory().getHelmet();
        if (real != null && !real.getType().isAir()) {
            list.add(Hair3DConfig.HelmetDescriptor.armor(resolver.resolve(real).mythicId(), real.getType().name()));
        }

        // 3. 2D hat layer (e.g. beanie_red.png)
        String hatTex = resolveActive2DLayerTexture(state, "hat", blockedSlots);
        if (hatTex != null && !hatTex.isBlank()) {
            list.add(Hair3DConfig.HelmetDescriptor.hat2d(hatTex));
        }

        return list;
    }

    private static void applyOptionalHairColor(ItemStack item, Color configuredColor, DyedItemColor inheritedDye) {
        if (item == null || item.getType().isAir()) return;
        try {
            if (inheritedDye != null) {
                item.setData(DataComponentTypes.DYED_COLOR, inheritedDye);
                return;
            }
            if (configuredColor != null) {
                ItemMeta meta = item.getItemMeta();
                if (meta instanceof LeatherArmorMeta leather) {
                    leather.setColor(configuredColor);
                    item.setItemMeta(leather);
                } else {
                    item.setData(DataComponentTypes.DYED_COLOR, DyedItemColor.dyedItemColor(configuredColor));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Combines 2+ equipped 3D HEAD models (e.g. 3D Hair + 3D Mask + 3D Helmet) into ONE native
     * Minecraft 1.21.4+ `minecraft:composite` HEAD ItemStack using CustomModelData strings.
     * Preserves DyedItemColor from tinted models (such as colorable 3D hairstyles).
     */
    private ItemStack tryBuildHeadComposite(
            Player player,
            PlayerWardrobeState state,
            ItemStack primaryHead,
            HeadCompositePackBuilder.Manifest manifest,
            Set<String> hiddenSecondary,
            Set<String> blockedSlots
    ) {
        List<String> modelKeys = new ArrayList<>();
        List<String> consumedOverlaySlots = new ArrayList<>();
        DyedItemColor preservedDye = null;

        // 1. Primary helmet (cosmetic or real)
        if (primaryHead != null && !primaryHead.getType().isAir()) {
            String key = extractItemModelKey(primaryHead);
            if (key == null) return null; // Non-ItemModel helmet (e.g. vanilla diamond helmet) -> keep normal fallback
            modelKeys.add(key);
            DyedItemColor d = extractDyedColor(primaryHead);
            if (d != null) preservedDye = d;
        }

        // 2. All occupied HEAD 3D overlay slots (hair_3d, mask_3d, glasses_3d, etc.)
        for (WardrobeSlotDef slot : config.getSlotsForSection(ArmorSection.HEAD)) {
            if (!slot.isDisplay3D()) continue;
            if (blockedSlots != null && blockedSlots.contains(slot.id())) continue;
            ItemStack item = resolveEffectiveSlot3DItem(player, state, slot, blockedSlots);
            if (item == null || item.getType().isAir()) continue;
            String key = extractItemModelKey(item);
            if (key == null) return null;
            modelKeys.add(key);
            consumedOverlaySlots.add(slot.id().toLowerCase(Locale.ROOT));
            DyedItemColor d = extractDyedColor(item);
            if (d != null) preservedDye = d;
        }

        // Only use composite when 2+ models are stacked simultaneously and all exist in the built pack!
        if (modelKeys.size() < 2 || !manifest.supportsAll(modelKeys)) {
            return null;
        }

        try {
            ItemStack stack = new ItemStack(Material.LEATHER_HELMET);
            stack.setData(DataComponentTypes.EQUIPPABLE, Equippable.equippable(EquipmentSlot.HEAD).build());
            stack.setData(DataComponentTypes.ITEM_MODEL, NamespacedKey.fromString(HeadCompositePackBuilder.COMPOSITE_ITEM_MODEL));
            stack.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addStrings(modelKeys).build());
            if (preservedDye != null) {
                stack.setData(DataComponentTypes.DYED_COLOR, preservedDye);
            }
            hiddenSecondary.addAll(consumedOverlaySlots);
            return stack;
        } catch (Throwable t) {
            if (config.isDebug()) {
                plugin.getLogger().warning("[HeadComposite] Failed to build composite item: " + t.getMessage());
            }
            return null;
        }
    }

    private static String extractItemModelKey(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        try {
            Key key = item.getData(DataComponentTypes.ITEM_MODEL);
            if (key != null) {
                return key.asString().toLowerCase(Locale.ROOT);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static DyedItemColor extractDyedColor(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        try {
            return item.getData(DataComponentTypes.DYED_COLOR);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public ItemStack getEffectiveVisualArmor(Player player, EquipmentSlot eqSlot) {
        PlayerVisualSnapshot snap = snapshotByUuid.get(player.getUniqueId());
        if (snap != null) {
            ItemStack ov = snap.overrides().get(eqSlot);
            if (ov != null) return ov.clone();
        }
        ItemStack real = player.getInventory().getItem(eqSlot);
        return real == null ? ItemStack.empty() : real.clone();
    }

    /**
     * Rebuilds the player's visual snapshot and pushes updates to viewers & self.
     */
    public void refreshPlayerArmorVisuals(Player player) {
        if (player == null || !player.isOnline()) return;

        rebuildSnapshot(player);

        if (packetBridge != null) {
            packetBridge.broadcastArmor(player);
            packetBridge.syncSelfArmor(player);
        } else {
            Map<EquipmentSlot, ItemStack> visuals = new EnumMap<>(EquipmentSlot.class);
            for (ArmorSection sec : ArmorSection.values()) {
                visuals.put(sec.getEquipmentSlot(), getEffectiveVisualArmor(player, sec.getEquipmentSlot()));
            }
            for (Player viewer : player.getWorld().getPlayers()) {
                if (viewer.getUniqueId().equals(player.getUniqueId())) continue;
                if (viewer.canSee(player)) {
                    viewer.sendEquipmentChange(player, visuals);
                }
            }
        }
    }

    /**
     * Sends all currently online players' cosmetic armor overrides to a newly joined/tracking viewer.
     */
    public void sendAllOverridesToViewer(Player viewer) {
        if (viewer == null || !viewer.isOnline() || !config.isCosmeticArmorEnabled()) return;
        for (Player target : viewer.getWorld().getPlayers()) {
            if (target.getUniqueId().equals(viewer.getUniqueId())) continue;
            if (!viewer.canSee(target)) continue;
            sendTargetArmorToViewer(viewer, target);
        }
    }

    public void sendTargetArmorToViewer(Player viewer, Player target) {
        if (viewer == null || target == null || !viewer.isOnline() || !target.isOnline()) return;
        PlayerVisualSnapshot snap = snapshotByUuid.get(target.getUniqueId());
        if (snap == null || !snap.hasOverrides()) return;

        if (packetBridge != null) {
            packetBridge.sendArmorToViewer(viewer, target);
        } else {
            Map<EquipmentSlot, ItemStack> visuals = new EnumMap<>(EquipmentSlot.class);
            for (ArmorSection sec : ArmorSection.values()) {
                visuals.put(sec.getEquipmentSlot(), getEffectiveVisualArmor(target, sec.getEquipmentSlot()));
            }
            viewer.sendEquipmentChange(target, visuals);
        }
    }

    public void clearPlayer(UUID playerId) {
        comboHiddenSlots.remove(playerId);
        openWardrobeGuis.remove(playerId);
        PlayerVisualSnapshot removed = snapshotByUuid.remove(playerId);
        if (removed != null) {
            snapshotByEntityId.remove(removed.entityId());
        }
    }
}
