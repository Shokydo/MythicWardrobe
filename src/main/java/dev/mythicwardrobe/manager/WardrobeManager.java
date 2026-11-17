package dev.mythicwardrobe.manager;

import dev.mythicwardrobe.config.TextUtil;
import dev.mythicwardrobe.config.WardrobeConfig;
import dev.mythicwardrobe.gui.WardrobeGuiHolder;
import dev.mythicwardrobe.hook.MythicMobsHook;
import dev.mythicwardrobe.hook.SkinsRestorerBackend;
import dev.mythicwardrobe.model.*;
import dev.mythicwardrobe.skin.MineSkinClient;
import dev.mythicwardrobe.skin.SkinBackend;
import dev.mythicwardrobe.skin.SkinCompositor;
import dev.mythicwardrobe.skin.SkinLayerRegistry;
import dev.mythicwardrobe.storage.WardrobeDatabase;
import dev.mythicwardrobe.visual.ArmorVisualManager;
import dev.mythicwardrobe.visual.DisplayAccessoryManager;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.logging.Level;

/**
 * Central coordinator managing player wardrobe state, debounced async skin composition,
 * MythicMobs skill layers/triggers, cosmetic/combo armor visuals, and 3D displays.
 */
public final class WardrobeManager {

    private final JavaPlugin plugin;
    private final WardrobeConfig config;
    private final WardrobeDatabase database;
    private final SkinLayerRegistry layerRegistry;
    private final MineSkinClient mineSkinClient;
    private final SkinBackend skinBackend;
    private final WardrobeItemResolver itemResolver;
    private final MythicMobsHook mythicHook;

    private final Map<UUID, PlayerWardrobeState> states = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> pendingSkinTasks = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicLong> skinGenerations = new ConcurrentHashMap<>();
    private final Map<String, BufferedImage> baseSkinImageCache = new ConcurrentHashMap<>();
    // L1 RAM cache in front of SQLite mineskin_cache (< 1ms hit on repeated outfits)
    private final Map<String, SignedSkinData> memorySkinCache = new ConcurrentHashMap<>();
    // Skins already signed & ready to apply as soon as the player closes their GUI (prevents Respawn packet closing GUI!)
    private record PendingSkinApply(String hash, SignedSkinData signed, boolean notifySuccess) {}
    private final Map<UUID, PendingSkinApply> deferredSkinApplies = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> deferredFlushTasks = new ConcurrentHashMap<>();
    private volatile BiConsumer<Player, WardrobeGuiHolder> guiReopener;

    private final ArmorVisualManager armorVisualManager;
    private final DisplayAccessoryManager displayAccessoryManager;

    public WardrobeManager(
            JavaPlugin plugin,
            WardrobeConfig config,
            WardrobeDatabase database,
            SkinLayerRegistry layerRegistry,
            MineSkinClient mineSkinClient,
            SkinBackend skinBackend,
            WardrobeItemResolver itemResolver,
            MythicMobsHook mythicHook
    ) {
        this.plugin = plugin;
        this.config = config;
        this.database = database;
        this.layerRegistry = layerRegistry;
        this.mineSkinClient = mineSkinClient;
        this.skinBackend = skinBackend;
        this.itemResolver = itemResolver;
        this.mythicHook = mythicHook;

        this.armorVisualManager = new ArmorVisualManager(plugin, config, itemResolver, mythicHook, states);
        this.armorVisualManager.setBlockedSlotsProvider(p -> getActiveBlockedMap(p).keySet());
        this.displayAccessoryManager = new DisplayAccessoryManager(plugin, config, itemResolver, armorVisualManager, states);

        skinBackend.setBaseSkinChangeListener(this::onExternalBaseSkinChanged);
    }

    /**
     * Describes why a wardrobe slot or 2D skin layer is currently blocked.
     */
    public record SlotBlockInfo(String sourceId, String blockerName, BlockBehaviorMode mode) {}

    /**
     * Result of trying to equip an item into a wardrobe slot (taking slot/layer blocking into account).
     */
    public record EquipAttemptResult(boolean allowed, String errorMessage, int unequippedConflicts) {
        public static EquipAttemptResult ok(int unequippedConflicts) {
            return new EquipAttemptResult(true, null, unequippedConflicts);
        }

        public static EquipAttemptResult denied(String errorMessage) {
            return new EquipAttemptResult(false, errorMessage, 0);
        }
    }

    public void start() {
        armorVisualManager.enablePacketEventsIfPresent();
        displayAccessoryManager.start();
        for (Player online : Bukkit.getOnlinePlayers()) {
            handleJoin(online);
        }
    }

    public void shutdown() {
        for (BukkitTask task : pendingSkinTasks.values()) {
            task.cancel();
        }
        pendingSkinTasks.clear();
        for (BukkitTask task : deferredFlushTasks.values()) {
            task.cancel();
        }
        deferredFlushTasks.clear();
        deferredSkinApplies.clear();

        // Synchronously persist all online player states before shutdown
        for (Map.Entry<UUID, PlayerWardrobeState> entry : states.entrySet()) {
            database.saveAllSlotItems(entry.getKey(), entry.getValue().getSlotItems());
            database.saveSkillLayers(entry.getKey(), entry.getValue().getSkillLayers());
        }
        displayAccessoryManager.stop();
        armorVisualManager.disable();
        states.clear();
        skinGenerations.clear();
        baseSkinImageCache.clear();
        memorySkinCache.clear();
    }

    public void handleJoin(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerWardrobeState state = states.computeIfAbsent(uuid, PlayerWardrobeState::new);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<String, ItemStack> loadedItems = database.loadSlotItems(uuid);
            Map<String, String> loadedSkillLayers = database.loadSkillLayers(uuid);
            Optional<SignedSkinData> savedBase = database.loadBaseSkin(uuid);
            Optional<SkinModelVariant> savedBodyType = database.loadBodyType(uuid);
            Optional<MenuMode> savedMenuMode = database.loadMenuMode(uuid);

            if (savedBase.isEmpty()) {
                savedBase = skinBackend.fetchCurrentBaseSkin(player);
                savedBase.ifPresent(s -> database.saveBaseSkin(uuid, s));
            }

            SignedSkinData resolvedBase = savedBase.orElse(null);
            SkinModelVariant resolvedBodyType = savedBodyType.orElse(null);
            MenuMode resolvedMenuMode = savedMenuMode.orElse(null);

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                loadedItems.forEach(state::setSlotItem);
                loadedSkillLayers.forEach((k, v) -> state.setSkillLayer(config.resolveLayerId(k), v));
                if (state.getBaseSkin() == null && resolvedBase != null) {
                    state.setBaseSkin(resolvedBase);
                }
                if (resolvedBodyType != null) {
                    state.setBodyTypeOverride(resolvedBodyType);
                }
                if (resolvedMenuMode != null) {
                    state.setMenuModeOverride(resolvedMenuMode);
                }

                // Refresh armor visuals & 3D accessories, and sync existing players' cosmetic armor to this viewer
                armorVisualManager.refreshPlayerArmorVisuals(player);
                armorVisualManager.sendAllOverridesToViewer(player);
                displayAccessoryManager.syncPlayerDisplays(player);

                // Re-apply layered clothing skin if any 2D layers or custom bodytype are active
                if (needsCustomSkinComposition(state)) {
                    scheduleSkinRebuild(player, false);
                }
            });
        });
    }

    public void handleQuit(Player player) {
        UUID uuid = player.getUniqueId();
        BukkitTask pending = pendingSkinTasks.remove(uuid);
        if (pending != null) pending.cancel();
        BukkitTask flushTask = deferredFlushTasks.remove(uuid);
        if (flushTask != null) flushTask.cancel();
        deferredSkinApplies.remove(uuid);
        skinGenerations.remove(uuid);

        displayAccessoryManager.removePlayerDisplays(uuid);
        armorVisualManager.clearPlayer(uuid);
        if (skinBackend instanceof SkinsRestorerBackend sr) {
            sr.clearTracking(uuid);
        }

        PlayerWardrobeState state = states.remove(uuid);
        if (state != null) {
            Map<String, ItemStack> itemsSnapshot = new HashMap<>(state.getSlotItems());
            Map<String, String> skillsSnapshot = new HashMap<>(state.getSkillLayers());
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                database.saveAllSlotItems(uuid, itemsSnapshot);
                database.saveSkillLayers(uuid, skillsSnapshot);
            });
        }
    }

    public void handleDeath(Player player, List<ItemStack> drops) {
        UUID uuid = player.getUniqueId();
        displayAccessoryManager.removePlayerDisplays(uuid);

        if (config.isKeepWardrobeItemsOnDeath()) {
            return;
        }
        PlayerWardrobeState state = states.get(uuid);
        if (state == null) return;

        boolean skinAffected = false;
        for (Map.Entry<String, ItemStack> entry : new HashMap<>(state.getSlotItems()).entrySet()) {
            ItemStack item = entry.getValue();
            if (item == null || item.getType().isAir()) continue;
            drops.add(item.clone());
            WardrobeSlotDef def = config.getSlot(entry.getKey());
            if (def != null && def.isSkinLayer()) {
                skinAffected = true;
            }
            triggerUnequipSkill(player, item);
        }
        state.clearAllSlotItems();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> database.saveAllSlotItems(uuid, Map.of()));

        if (skinAffected) {
            scheduleSkinRebuild(player, false);
        }
    }

    public void handleRespawnOrWorldChange(Player player) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            armorVisualManager.refreshPlayerArmorVisuals(player);
            displayAccessoryManager.syncPlayerDisplays(player);
        }, 3L);
    }

    private void onExternalBaseSkinChanged(Player player, SignedSkinData newBase) {
        if (player == null || newBase == null || !newBase.isValid()) return;
        UUID uuid = player.getUniqueId();
        PlayerWardrobeState state = states.get(uuid);
        if (state == null) return;

        state.setBaseSkin(newBase);
        state.setLastAppliedSkinHash(null);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> database.saveBaseSkin(uuid, newBase));

        if (config.isReapplyOnBaseSkinChange() && needsCustomSkinComposition(state)) {
            if (config.isDebug()) {
                plugin.getLogger().info("[Debug] Base skin changed for " + player.getName() + ", re-layering clothes...");
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    scheduleSkinRebuild(player, false);
                }
            }, 5L);
        }
    }

    public PlayerWardrobeState getState(Player player) {
        return states.computeIfAbsent(player.getUniqueId(), PlayerWardrobeState::new);
    }

    public ItemStack getSlotItem(Player player, WardrobeSlotDef slot) {
        if (slot.isRealArmor()) {
            ItemStack real = player.getInventory().getItem(slot.equipmentSlot());
            return (real == null || real.getType().isAir()) ? null : real.clone();
        }
        return getState(player).getSlotItem(slot.id());
    }

    /**
     * Updates a wardrobe slot (real armor, cosmetic armor, 2D skin layer, or 3D display) cleanly.
     */
    public void setSlotItem(Player player, WardrobeSlotDef slot, ItemStack newItem, boolean deferSkinRebuild) {
        boolean isEmpty = (newItem == null || newItem.getType().isAir() || newItem.getAmount() <= 0);
        ItemStack cleanNew = isEmpty ? null : newItem.clone();

        if (slot.isRealArmor()) {
            player.getInventory().setItem(slot.equipmentSlot(), cleanNew);
            armorVisualManager.refreshPlayerArmorVisuals(player);
            displayAccessoryManager.syncPlayerDisplays(player);
            return;
        }

        PlayerWardrobeState state = getState(player);
        ItemStack oldItem = state.getSlotItem(slot.id());

        // Trigger MythicMobs equip/unequip skills for custom GUI slots
        if (oldItem != null && !oldItem.equals(cleanNew)) {
            triggerUnequipSkill(player, oldItem);
        }
        state.setSlotItem(slot.id(), cleanNew);
        if (cleanNew != null && !cleanNew.equals(oldItem)) {
            triggerEquipSkill(player, cleanNew);
        }

        // Persist asynchronously
        UUID uuid = player.getUniqueId();
        ItemStack persistCopy = cleanNew == null ? null : cleanNew.clone();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> database.saveSlotItem(uuid, slot.id(), persistCopy));

        if (slot.isSkinLayer()) {
            state.setDirtySkin(true);
            if (!deferSkinRebuild) {
                scheduleSkinRebuild(player, false);
            }
        } else if (slot.isCosmeticArmor() || slot.isDisplay3D()) {
            armorVisualManager.refreshPlayerArmorVisuals(player);
            displayAccessoryManager.syncPlayerDisplays(player);
        }

        // If either the removed or newly equipped item declares slot/layer blocks, refresh 3D visuals & skin state too!
        boolean oldBlocked = oldItem != null && itemResolver.resolve(oldItem).hasBlocks();
        boolean newBlocked = cleanNew != null && itemResolver.resolve(cleanNew).hasBlocks();
        if (oldBlocked || newBlocked) {
            armorVisualManager.refreshPlayerArmorVisuals(player);
            displayAccessoryManager.syncPlayerDisplays(player);
            if (!slot.isSkinLayer()) {
                state.setDirtySkin(true);
                if (!deferSkinRebuild) {
                    scheduleSkinRebuild(player, false);
                }
            }
        }
    }

    /**
     * Computes the complete map of currently blocked slot/layer IDs for this player
     * (from all equipped custom GUI items, real armor pieces, and active skill layers with `blocks`).
     */
    public Map<String, SlotBlockInfo> getActiveBlockedMap(Player player) {
        if (player == null) return Map.of();
        PlayerWardrobeState state = states.get(player.getUniqueId());
        Map<String, SlotBlockInfo> result = new LinkedHashMap<>();

        if (state != null) {
            // 1. Equipped custom GUI wardrobe slots (2D skin layers, cosmetic armor, 3D accessories)
            for (Map.Entry<String, ItemStack> entry : state.getSlotItems().entrySet()) {
                ItemStack item = entry.getValue();
                if (item == null || item.getType().isAir()) continue;
                ItemWardrobeMeta meta = itemResolver.resolve(item);
                if (!meta.hasBlocks()) continue;

                String srcId = entry.getKey().toLowerCase(Locale.ROOT);
                WardrobeSlotDef srcSlot = config.getSlot(srcId);
                String blockerName = formatItemDisplayName(item, srcSlot, meta.mythicId());
                BlockBehaviorMode mode = meta.blockMode() != null ? meta.blockMode() : config.getDefaultBlockMode();
                for (String target : config.expandBlockedTargets(srcId, meta.blockedTargets())) {
                    result.putIfAbsent(target, new SlotBlockInfo(srcId, blockerName, mode));
                }
            }

            // 2. Active MythicMobs skill layers that declared `blocks=...`
            for (Map.Entry<String, Set<String>> entry : state.getSkillLayerBlocks().entrySet()) {
                String srcLayer = entry.getKey().toLowerCase(Locale.ROOT);
                String activeTex = state.getSkillLayer(srcLayer);
                if (activeTex == null || activeTex.isBlank()) continue;
                Set<String> rawBlocks = entry.getValue();
                if (rawBlocks == null || rawBlocks.isEmpty()) continue;

                WardrobeSlotDef srcSlot = config.getSlot(srcLayer);
                String blockerName = srcSlot != null ? stripTags(srcSlot.displayName()) : (srcLayer + " (" + activeTex + ")");
                for (String target : config.expandBlockedTargets(srcLayer, rawBlocks)) {
                    result.putIfAbsent(target, new SlotBlockInfo(srcLayer, blockerName, config.getDefaultBlockMode()));
                }
            }
        }

        // 3. Real vanilla armor slots (if an admin adds `Blocks:` to a helmet/chestplate/leggings/boots)
        for (ArmorSection sec : ArmorSection.values()) {
            EquipmentSlot eq = sec.getEquipmentSlot();
            ItemStack real = player.getInventory().getItem(eq);
            if (real == null || real.getType().isAir()) continue;
            ItemWardrobeMeta meta = itemResolver.resolve(real);
            if (!meta.hasBlocks()) continue;

            WardrobeSlotDef realSlot = config.findSlotByKindAndEquipment(SlotKind.ARMOR_REAL, eq);
            String srcId = realSlot != null ? realSlot.id() : eq.name().toLowerCase(Locale.ROOT);
            String blockerName = formatItemDisplayName(real, realSlot, meta.mythicId());
            BlockBehaviorMode mode = meta.blockMode() != null ? meta.blockMode() : config.getDefaultBlockMode();
            for (String target : config.expandBlockedTargets(srcId, meta.blockedTargets())) {
                result.putIfAbsent(target, new SlotBlockInfo(srcId, blockerName, mode));
            }
        }

        return result.isEmpty() ? Map.of() : Collections.unmodifiableMap(result);
    }

    public Optional<SlotBlockInfo> getBlockerFor(Player player, String slotOrLayerId) {
        if (player == null || slotOrLayerId == null || slotOrLayerId.isBlank()) return Optional.empty();
        String canonical = config.resolveLayerId(slotOrLayerId);
        Map<String, SlotBlockInfo> blocked = getActiveBlockedMap(player);
        SlotBlockInfo info = blocked.get(slotOrLayerId.trim().toLowerCase(Locale.ROOT));
        if (info == null) {
            info = blocked.get(canonical);
        }
        return Optional.ofNullable(info);
    }

    /**
     * Validates slot/layer blocking rules, resolves conflicts according to BlockBehaviorMode (UNEQUIP / PREVENT / HIDE),
     * and equips (or unequips) the item into targetSlot.
     */
    public EquipAttemptResult prepareAndEquipSlotItem(Player player, WardrobeSlotDef targetSlot, ItemStack newItem, boolean deferSkinRebuild) {
        if (player == null || targetSlot == null) {
            return EquipAttemptResult.denied("Invalid slot");
        }
        boolean isEmpty = (newItem == null || newItem.getType().isAir() || newItem.getAmount() <= 0);
        if (isEmpty) {
            setSlotItem(player, targetSlot, null, deferSkinRebuild);
            return EquipAttemptResult.ok(0);
        }

        // 1. Refuse placing into a slot currently locked by another equipped item/suit
        Optional<SlotBlockInfo> existingBlocker = getBlockerFor(player, targetSlot.id());
        if (existingBlocker.isPresent() && !existingBlocker.get().sourceId().equalsIgnoreCase(targetSlot.id())) {
            String msg = config.getPrefixedMessage("slot-blocked-by",
                            "<red>Слот <white><slot></white> <red>заблокирован предметом <yellow><blocker></yellow><red>!")
                    .replace("<slot>", stripTags(targetSlot.displayName()))
                    .replace("<blocker>", existingBlocker.get().blockerName());
            return EquipAttemptResult.denied(msg);
        }

        // 2. Check whether the NEW item itself blocks other occupied slots/layers
        ItemWardrobeMeta newMeta = itemResolver.resolve(newItem);
        int unequippedCount = 0;
        if (newMeta.hasBlocks()) {
            Set<String> targets = config.expandBlockedTargets(targetSlot.id(), newMeta.blockedTargets());
            BlockBehaviorMode mode = newMeta.blockMode() != null ? newMeta.blockMode() : config.getDefaultBlockMode();
            PlayerWardrobeState state = getState(player);

            if (mode == BlockBehaviorMode.PREVENT) {
                List<String> conflictNames = new ArrayList<>();
                for (String t : targets) {
                    WardrobeSlotDef conflictSlot = config.getSlot(t);
                    if (conflictSlot != null) {
                        ItemStack worn = getSlotItem(player, conflictSlot);
                        if (worn != null && !worn.getType().isAir()) {
                            conflictNames.add(stripTags(conflictSlot.displayName()));
                            continue;
                        }
                    }
                    String skillTex = state.getSkillLayer(t);
                    if (skillTex != null && !skillTex.isBlank()) {
                        conflictNames.add(conflictSlot != null ? stripTags(conflictSlot.displayName()) : t);
                    }
                }
                if (!conflictNames.isEmpty()) {
                    String msg = config.getPrefixedMessage("equip-blocked-conflicts",
                                    "<red>Нельзя надеть: сначала снимите вещи из слотов <white><conflicts></white><red>!")
                            .replace("<conflicts>", String.join(", ", conflictNames));
                    return EquipAttemptResult.denied(msg);
                }
            } else if (mode == BlockBehaviorMode.UNEQUIP) {
                for (String t : targets) {
                    WardrobeSlotDef conflictSlot = config.getSlot(t);
                    if (conflictSlot == null) continue;
                    ItemStack worn = getSlotItem(player, conflictSlot);
                    if (worn == null || worn.getType().isAir()) continue;

                    setSlotItem(player, conflictSlot, null, true);
                    Map<Integer, ItemStack> overflow = player.getInventory().addItem(worn.clone());
                    for (ItemStack drop : overflow.values()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), drop);
                    }
                    unequippedCount++;
                }
            }
            // HIDE mode leaves conflicting items untouched in their slots while visually suppressing & locking them!
        }

        setSlotItem(player, targetSlot, newItem, deferSkinRebuild);
        return EquipAttemptResult.ok(unequippedCount);
    }

    public static String formatItemDisplayName(ItemStack item, WardrobeSlotDef fallbackSlot, String mythicId) {
        if (item != null && !item.getType().isAir()) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
                String plain = PlainTextComponentSerializer.plainText().serialize(meta.displayName()).trim();
                if (!plain.isEmpty()) return plain;
            }
        }
        if (mythicId != null && !mythicId.isBlank()) return mythicId;
        if (fallbackSlot != null) return stripTags(fallbackSlot.displayName());
        return item != null ? item.getType().name() : "Костюм";
    }

    private static String stripTags(String text) {
        return text == null ? "" : text.replaceAll("<[^>]+>", "");
    }

    /**
     * Sets a 2D skin overlay directly via MythicMobs mechanic or /wardrobe set command.
     * Supports any layer ID or alias defined in layers.yml / slots.yml!
     */
    public boolean setSkillLayer(Player player, String layerType, String texturePath, boolean immediate) {
        return setSkillLayer(player, layerType, texturePath, Set.of(), false, immediate);
    }

    public boolean setSkillLayer(
            Player player,
            String layerType,
            String texturePath,
            Set<String> blockedTargets,
            boolean force,
            boolean immediate
    ) {
        if (layerType == null || layerType.isBlank()) return false;
        String canonical = config.resolveLayerId(layerType);

        if (!force) {
            Optional<SlotBlockInfo> blocker = getBlockerFor(player, canonical);
            if (blocker.isPresent() && !blocker.get().sourceId().equalsIgnoreCase(canonical)) {
                return false;
            }
        }

        PlayerWardrobeState state = getState(player);
        state.setSkillLayer(canonical, texturePath);
        if (blockedTargets != null && !blockedTargets.isEmpty()) {
            state.setSkillLayerBlocks(canonical, config.expandBlockedTargets(canonical, blockedTargets));
        } else {
            state.setSkillLayerBlocks(canonical, Set.of());
        }
        persistSkillLayersAsync(player.getUniqueId(), state);
        boolean affects3DHair = "hair".equalsIgnoreCase(canonical) || "hat".equalsIgnoreCase(canonical);
        if ((blockedTargets != null && !blockedTargets.isEmpty()) || affects3DHair) {
            armorVisualManager.refreshPlayerArmorVisuals(player);
            displayAccessoryManager.syncPlayerDisplays(player);
        }
        scheduleSkinRebuild(player, immediate);
        return true;
    }

    public boolean clearSkillLayer(Player player, String layerType, boolean immediate) {
        if (layerType == null || layerType.isBlank()) return false;
        String canonical = config.resolveLayerId(layerType);
        PlayerWardrobeState state = getState(player);
        boolean hadBlocks = !state.getSkillLayerBlocks(canonical).isEmpty();
        boolean affects3DHair = "hair".equalsIgnoreCase(canonical) || "hat".equalsIgnoreCase(canonical);
        state.removeSkillLayer(canonical);
        persistSkillLayersAsync(player.getUniqueId(), state);
        if (hadBlocks || affects3DHair) {
            armorVisualManager.refreshPlayerArmorVisuals(player);
            displayAccessoryManager.syncPlayerDisplays(player);
        }
        scheduleSkinRebuild(player, immediate);
        return true;
    }

    public void resetSkillLayersAndRefresh(Player player, boolean immediate) {
        PlayerWardrobeState state = getState(player);
        boolean hadBlocks = !state.getSkillLayerBlocks().isEmpty();
        boolean hadHairOrHat = state.getSkillLayer("hair") != null || state.getSkillLayer("hat") != null;
        state.clearSkillLayers();
        persistSkillLayersAsync(player.getUniqueId(), state);
        if (hadBlocks || hadHairOrHat) {
            armorVisualManager.refreshPlayerArmorVisuals(player);
            displayAccessoryManager.syncPlayerDisplays(player);
        }
        scheduleSkinRebuild(player, immediate);
    }

    /**
     * Atomically clears one, several, or all skill layers (used by the `unwardrobe` MythicMobs mechanic,
     * typically bound to `~onUnequip`).
     *
     * If {@code onlyTexture} is non-blank, a layer is removed ONLY if its currently worn texture matches
     * (case-insensitive, with or without `.png` extension / folder prefix) — this prevents race conditions
     * when swapping item A directly with item B!
     *
     * @return number of layers removed (plus 1 if bodytype override was reset)
     */
    public int clearSkillLayers(Player player, List<String> layerTypes, String onlyTexture, boolean resetBodyType, boolean immediate) {
        if (player == null) return 0;
        PlayerWardrobeState state = getState(player);
        boolean clearAll = false;
        List<String> canonicalTargets = new ArrayList<>();
        if (layerTypes == null || layerTypes.isEmpty()) {
            clearAll = !resetBodyType;
        } else {
            for (String raw : layerTypes) {
                if (raw == null || raw.isBlank()) continue;
                String trimmed = raw.trim();
                if (trimmed.equalsIgnoreCase("all") || trimmed.equals("*")) {
                    clearAll = true;
                    break;
                }
                canonicalTargets.add(config.resolveLayerId(trimmed));
            }
        }

        boolean hadBlocksBefore = !state.getSkillLayerBlocks().isEmpty();
        boolean hadHairOrHatBefore = state.getSkillLayer("hair") != null || state.getSkillLayer("hat") != null;
        int changed = applySkillLayerRemoval(state, clearAll, canonicalTargets, onlyTexture);

        if (resetBodyType && state.getBodyTypeOverride() != null) {
            state.setBodyTypeOverride(null);
            state.setLastAppliedSkinHash(null);
            UUID uuid = player.getUniqueId();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> database.saveBodyType(uuid, null));
            changed++;
        }

        if (changed > 0) {
            persistSkillLayersAsync(player.getUniqueId(), state);
            if (hadBlocksBefore || hadHairOrHatBefore) {
                armorVisualManager.refreshPlayerArmorVisuals(player);
                displayAccessoryManager.syncPlayerDisplays(player);
            }
            scheduleSkinRebuild(player, immediate);
        }
        return changed;
    }

    /**
     * Pure state mutation helper for unit-testable `unwardrobe` clearing semantics.
     */
    public static int applySkillLayerRemoval(PlayerWardrobeState state, boolean clearAll, Collection<String> canonicalLayerIds, String onlyTexture) {
        int removed = 0;
        boolean hasFilter = onlyTexture != null && !onlyTexture.isBlank();
        if (clearAll) {
            if (!hasFilter) {
                removed = state.getSkillLayers().size();
                state.clearSkillLayers();
            } else {
                for (Map.Entry<String, String> entry : new HashMap<>(state.getSkillLayers()).entrySet()) {
                    if (matchesTextureFilter(entry.getValue(), onlyTexture)) {
                        state.removeSkillLayer(entry.getKey());
                        removed++;
                    }
                }
            }
        } else if (canonicalLayerIds != null) {
            for (String id : canonicalLayerIds) {
                if (id == null || id.isBlank()) continue;
                String current = state.getSkillLayer(id);
                if (current == null) continue;
                if (!hasFilter || matchesTextureFilter(current, onlyTexture)) {
                    state.removeSkillLayer(id);
                    removed++;
                }
            }
        }
        return removed;
    }

    public static boolean matchesTextureFilter(String current, String filter) {
        if (filter == null || filter.isBlank()) return true;
        if (current == null || current.isBlank()) return false;
        String normCur = normalizeTextureName(current);
        String normFil = normalizeTextureName(filter);
        if (normCur.equals(normFil)) return true;
        // Also match basename when one includes a subfolder (e.g. "jacket/leather.png" vs "leather.png")
        int curSlash = normCur.lastIndexOf('/');
        int filSlash = normFil.lastIndexOf('/');
        String curBase = curSlash >= 0 ? normCur.substring(curSlash + 1) : normCur;
        String filBase = filSlash >= 0 ? normFil.substring(filSlash + 1) : normFil;
        return curBase.equals(filBase);
    }

    private static String normalizeTextureName(String raw) {
        String s = raw.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
        if (s.endsWith(".png")) {
            s = s.substring(0, s.length() - 4);
        }
        return s;
    }

    public MenuMode getEffectiveMenuMode(Player player) {
        if (!config.isAllowPlayerMenuModeSwitch()) {
            return config.getDefaultMenuMode();
        }
        PlayerWardrobeState state = player != null ? states.get(player.getUniqueId()) : null;
        if (state != null && state.getMenuModeOverride() != null) {
            return state.getMenuModeOverride();
        }
        return config.getDefaultMenuMode();
    }

    public void setPlayerMenuMode(Player player, MenuMode mode) {
        if (player == null) return;
        PlayerWardrobeState state = getState(player);
        state.setMenuModeOverride(mode);
        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> database.saveMenuMode(uuid, mode));
    }

    /**
     * Changes the player's arm width / body type (SLIM = 3px narrow arms, CLASSIC = 4px wide arms, null = auto from base skin).
     */
    public void setBodyType(Player player, SkinModelVariant variant, boolean immediate) {
        if (player == null) return;
        PlayerWardrobeState state = getState(player);
        state.setBodyTypeOverride(variant);
        state.setLastAppliedSkinHash(null);
        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> database.saveBodyType(uuid, variant));
        scheduleSkinRebuild(player, immediate);
    }

    public SkinModelVariant getEffectiveBodyType(Player player) {
        PlayerWardrobeState state = states.get(player.getUniqueId());
        if (state != null) {
            if (state.getBodyTypeOverride() != null) return state.getBodyTypeOverride();
            if (state.getBaseSkin() != null && state.getBaseSkin().variant() != null) {
                return state.getBaseSkin().variant();
            }
        }
        return config.getDefaultVariant();
    }

    /**
     * Snapshot of currently active 2D skin layers (canonical layerId -> texture filename/ref),
     * excluding any layers currently suppressed/blocked by full-suit items.
     */
    public Map<String, String> getActiveSkinLayers(Player player) {
        return collectActiveSkinLayersOrdered(getState(player), getActiveBlockedMap(player).keySet());
    }

    /**
     * Loads and encodes the player's current base skin (or neutral mannequin fallback) as 64x64 PNG bytes.
     * Safe to invoke off the main thread (used by the temporary web editor).
     */
    public byte[] getPlayerBaseSkinPngBytes(Player player) {
        PlayerWardrobeState state = states.get(player.getUniqueId());
        SignedSkinData base = state != null ? state.getBaseSkin() : null;
        SkinModelVariant variant = getEffectiveBodyType(player);
        BufferedImage img = loadBaseSkinImage(base, variant);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to encode base skin PNG for " + player.getName(), e);
            return new byte[0];
        }
    }

    /**
     * Atomically applies a full skin configuration (bodytype + selected layers) from the temporary web editor,
     * triggering only ONE MineSkin compose + SkinsRestorer apply cycle. Must be called on the main server thread.
     */
    public void applyWebEditorBatch(Player player, SkinModelVariant bodyType, Map<String, String> selectedLayers) {
        if (player == null || !player.isOnline()) return;
        PlayerWardrobeState state = getState(player);
        state.setBodyTypeOverride(bodyType);
        state.clearSkillLayers();
        if (selectedLayers != null) {
            for (Map.Entry<String, String> entry : selectedLayers.entrySet()) {
                String canonical = config.resolveLayerId(entry.getKey());
                String tex = entry.getValue();
                if (!canonical.isEmpty() && tex != null && !tex.isBlank()) {
                    state.setSkillLayer(canonical, tex.trim());
                }
            }
        }
        state.setLastAppliedSkinHash(null);
        UUID uuid = player.getUniqueId();
        Map<String, String> snapshot = new HashMap<>(state.getSkillLayers());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            database.saveBodyType(uuid, bodyType);
            database.saveSkillLayers(uuid, snapshot);
        });
        player.sendMessage(TextUtil.parse(config.getPrefixedMessage("web-editor-applied",
                "Изменения из веб-редактора применены! Обновляем скин...")));
        armorVisualManager.refreshPlayerArmorVisuals(player);
        displayAccessoryManager.syncPlayerDisplays(player);
        scheduleSkinRebuild(player, true);
    }

    public boolean hasLayerOrEquippedItem(Player player, String slotOrType, String expectedTextureOrItem) {
        PlayerWardrobeState state = states.get(player.getUniqueId());
        if (state == null) return false;
        String key = config.resolveLayerId(slotOrType);

        // 1. Check skill layers
        String skillTex = state.getSkillLayer(key);
        if (skillTex != null && !skillTex.isBlank()) {
            if (expectedTextureOrItem == null || expectedTextureOrItem.isBlank()) return true;
            return matchesTextureRef(skillTex, expectedTextureOrItem);
        }

        // 2. Check configured wardrobe slot
        WardrobeSlotDef def = config.getSlot(key);
        if (def != null) {
            ItemStack equipped = getSlotItem(player, def);
            if (equipped == null || equipped.getType().isAir()) return false;
            if (expectedTextureOrItem == null || expectedTextureOrItem.isBlank()) return true;
            ItemWardrobeMeta meta = itemResolver.resolve(equipped);
            if (meta.hasTexture() && matchesTextureRef(meta.texturePath(), expectedTextureOrItem)) return true;
            if (meta.mythicId() != null && meta.mythicId().equalsIgnoreCase(expectedTextureOrItem)) return true;
            return false;
        }
        return false;
    }

    private static boolean matchesTextureRef(String actual, String expected) {
        String a = actual.replace('\\', '/').toLowerCase(Locale.ROOT);
        String e = expected.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (a.equals(e)) return true;
        if (!e.endsWith(".png") && a.equals(e + ".png")) return true;
        return a.endsWith("/" + e) || (!e.endsWith(".png") && a.endsWith("/" + e + ".png"));
    }

    public void scheduleSkinRebuild(Player player, boolean immediate) {
        if (player == null || !player.isOnline()) return;
        UUID uuid = player.getUniqueId();
        BukkitTask existing = pendingSkinTasks.remove(uuid);
        if (existing != null) existing.cancel();

        long delay = immediate ? 1L : config.getRebuildDebounceTicks();
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pendingSkinTasks.remove(uuid);
            Player online = Bukkit.getPlayer(uuid);
            if (online != null && online.isOnline()) {
                rebuildSkinAsync(online);
            }
        }, delay);
        pendingSkinTasks.put(uuid, task);
    }

    public void flushDirtySkin(Player player) {
        PlayerWardrobeState state = states.get(player.getUniqueId());
        if (state != null && state.isDirtySkin()) {
            state.setDirtySkin(false);
            scheduleSkinRebuild(player, false);
        }
    }

    public Map<String, String> collectActiveSkinLayersOrdered(PlayerWardrobeState state) {
        return collectActiveSkinLayersOrdered(state, Set.of());
    }

    public Map<String, String> collectActiveSkinLayersOrdered(PlayerWardrobeState state, Set<String> blockedLayerIds) {
        Map<String, String> ordered = new LinkedHashMap<>();
        Set<String> blocked = blockedLayerIds == null ? Set.of() : blockedLayerIds;

        // 1. All layers defined in layers.yml + SKIN_LAYER slots from slots.yml, sorted strictly by priority
        for (SkinLayerDef layerDef : config.getSkinLayersOrdered()) {
            if (blocked.contains(layerDef.id())) {
                continue;
            }
            String overrideTex = state.getSkillLayer(layerDef.id());
            if (overrideTex != null && !overrideTex.isBlank()) {
                ordered.put(layerDef.id(), overrideTex);
                continue;
            }
            WardrobeSlotDef guiSlot = config.getSlot(layerDef.id());
            if (guiSlot != null && guiSlot.isSkinLayer()) {
                ItemStack item = state.peekSlotItem(guiSlot.id());
                if (item != null && !item.getType().isAir()) {
                    ItemWardrobeMeta meta = itemResolver.resolve(item);
                    if (meta.hasTexture()) {
                        ordered.put(layerDef.id(), meta.texturePath());
                    }
                }
            }
        }

        // 2. Any unlisted ad-hoc MythicMobs skill layers not in layers.yml (drawn on top)
        List<String> extraKeys = new ArrayList<>();
        for (String key : state.getSkillLayers().keySet()) {
            if (!ordered.containsKey(key) && !blocked.contains(key)) {
                extraKeys.add(key);
            }
        }
        Collections.sort(extraKeys);
        for (String k : extraKeys) {
            String tex = state.getSkillLayer(k);
            if (tex != null && !tex.isBlank()) {
                ordered.put(k, tex);
            }
        }
        return ordered;
    }

    /**
     * Pure helper for unit testing: filters ordered layer map by blocked targets and computes
     * inherited clear-outer-regions from blocked layers for each active blocker.
     */
    public static Set<String> computeInheritedClearOuterRegions(
            String activeLayerId,
            Map<String, SlotBlockInfo> activeBlockedMap,
            Map<String, SkinLayerDef> skinLayersById
    ) {
        Set<String> extra = new LinkedHashSet<>();
        if (activeLayerId == null || activeBlockedMap == null || skinLayersById == null) return extra;
        for (Map.Entry<String, SlotBlockInfo> e : activeBlockedMap.entrySet()) {
            if (activeLayerId.equalsIgnoreCase(e.getValue().sourceId())) {
                SkinLayerDef blockedDef = skinLayersById.get(e.getKey());
                if (blockedDef != null && blockedDef.clearOuterRegions() != null) {
                    extra.addAll(blockedDef.clearOuterRegions());
                }
            }
        }
        return extra;
    }

    private boolean needsCustomSkinComposition(PlayerWardrobeState state) {
        if (!collectActiveSkinLayersOrdered(state).isEmpty()) return true;
        SkinModelVariant override = state.getBodyTypeOverride();
        if (override == null) return false;
        SignedSkinData base = state.getBaseSkin();
        return base == null || base.variant() != override;
    }

    private void rebuildSkinAsync(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerWardrobeState state = states.get(uuid);
        if (state == null) return;
        state.setDirtySkin(false);

        Map<String, SlotBlockInfo> activeBlockedMap = getActiveBlockedMap(player);
        Map<String, String> activeLayers = collectActiveSkinLayersOrdered(state, activeBlockedMap.keySet());
        SkinModelVariant bodyTypeOverride = state.getBodyTypeOverride();
        long generation = skinGenerations.computeIfAbsent(uuid, k -> new AtomicLong()).incrementAndGet();

        // Case 1: No clothing/body layers active AND body type matches original base skin -> restore original base skin directly (0 MineSkin calls!)
        if (activeLayers.isEmpty()) {
            SignedSkinData base = state.getBaseSkin();
            boolean variantNeedsChange = bodyTypeOverride != null && (base == null || base.variant() != bodyTypeOverride);
            if (!variantNeedsChange) {
                if (base != null && base.isValid()) {
                    if (!"BASE".equals(state.getLastAppliedSkinHash())) {
                        applyComposedOnMainThread(player, state, "BASE", base, generation, false);
                    }
                }
                return;
            }
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // Ensure we have base skin captured
                SignedSkinData base = state.getBaseSkin();
                if (base == null || !base.isValid()) {
                    Optional<SignedSkinData> fetched = skinBackend.fetchCurrentBaseSkin(player);
                    if (fetched.isPresent()) {
                        base = fetched.get();
                        state.setBaseSkin(base);
                        database.saveBaseSkin(uuid, base);
                    }
                }

                SkinModelVariant variant;
                if (bodyTypeOverride != null) {
                    variant = bodyTypeOverride;
                } else if (base != null && base.variant() != null) {
                    variant = base.variant();
                } else {
                    variant = config.getDefaultVariant();
                }

                List<SkinCompositor.LayerInput> layerInputs = new ArrayList<>();
                boolean hasBaseReplacement = false;

                for (Map.Entry<String, String> entry : activeLayers.entrySet()) {
                    String layerId = entry.getKey();
                    String texRef = entry.getValue();
                    SkinLayerDef layerDef = config.getSkinLayerDef(layerId);
                    String folderHint = layerDef != null ? layerDef.folder() : layerId;
                    Set<String> clearRegions = new LinkedHashSet<>(layerDef != null ? layerDef.clearOuterRegions() : Set.of());
                    if (config.isInheritClearOuterRegionsFromBlocked() && !activeBlockedMap.isEmpty()) {
                        clearRegions.addAll(computeInheritedClearOuterRegions(layerId, activeBlockedMap, config.getSkinLayersById()));
                    }
                    boolean replacesBase = layerDef != null && layerDef.replacesBaseSkin();

                    Optional<SkinLayerRegistry.LoadedOverlay> overlay = layerRegistry.resolve(folderHint, texRef, variant);
                    if (overlay.isPresent()) {
                        layerInputs.add(new SkinCompositor.LayerInput(overlay.get().image(), clearRegions, replacesBase));
                        if (replacesBase) {
                            hasBaseReplacement = true;
                        }
                    } else if (config.isDebug()) {
                        plugin.getLogger().warning("[Debug] Missing overlay texture '" + texRef + "' for layer '" + layerId + "' (variant=" + variant.getBodyTypeName() + ")");
                    }
                }

                boolean variantDiffersFromBase = bodyTypeOverride != null && (base == null || base.variant() != variant);
                if (layerInputs.isEmpty() && !variantDiffersFromBase) {
                    if (base != null && base.isValid() && !"BASE".equals(state.getLastAppliedSkinHash())) {
                        applyComposedOnMainThread(player, state, "BASE", base, generation, false);
                    }
                    return;
                }

                // Skip downloading Mojang base skin if a `body_skin` layer completely replaces the base canvas!
                BufferedImage baseImage = hasBaseReplacement
                        ? new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
                        : loadBaseSkinImage(base, variant);

                SkinCompositor.ComposedSkin composed = SkinCompositor.compose(
                        baseImage, layerInputs, variant, config.isClearAllOuterLayer()
                );

                if (composed.sha256Hash().equals(state.getLastAppliedSkinHash())) {
                    return; // Exact same skin already applied!
                }

                // Check L1 RAM cache first (< 1ms!), then L2 persistent SQLite cache
                SignedSkinData ramCached = memorySkinCache.get(composed.sha256Hash());
                if (ramCached != null && ramCached.isValid()) {
                    if (config.isDebug()) {
                        plugin.getLogger().info("[Debug] MineSkin L1 RAM HIT for " + player.getName() + " (" + composed.sha256Hash().substring(0, 10) + ")");
                    }
                    applyComposedOnMainThread(player, state, composed.sha256Hash(), ramCached, generation, false);
                    return;
                }

                Optional<SignedSkinData> dbCached = database.getCachedSkin(composed.sha256Hash());
                if (dbCached.isPresent()) {
                    putMemorySkinCache(composed.sha256Hash(), dbCached.get());
                    if (config.isDebug()) {
                        plugin.getLogger().info("[Debug] MineSkin SQLite HIT for " + player.getName() + " (" + composed.sha256Hash().substring(0, 10) + ")");
                    }
                    applyComposedOnMainThread(player, state, composed.sha256Hash(), dbCached.get(), generation, false);
                    return;
                }

                // Cache MISS -> upload to MineSkin API v2 (deduplicated across concurrent requests by SHA-256!)
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline() && isCurrentGeneration(uuid, generation)) {
                        player.sendMessage(TextUtil.parse(config.getPrefixedMessage("skin-updating", "Применяем одежду на скин...")));
                    }
                });

                mineSkinClient.uploadAndSign(composed.sha256Hash(), composed.pngBytes(), variant).whenComplete((signed, err) -> {
                    if (err != null || signed == null || !signed.isValid()) {
                        String reason = err != null && err.getCause() != null
                                ? err.getCause().getMessage()
                                : (err != null ? err.getMessage() : "Empty MineSkin response");
                        plugin.getLogger().warning("MineSkin sign failed for " + player.getName() + ": " + reason);
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (player.isOnline() && isCurrentGeneration(uuid, generation)) {
                                player.sendMessage(TextUtil.parse(
                                        config.getPrefixedMessage("skin-update-failed", "Не удалось обновить скин: <reason>"),
                                        Map.of("reason", reason == null ? "error" : reason)
                                ));
                            }
                        });
                        return;
                    }

                    // Always persist signed texture in both L1 RAM & L2 SQLite even if superseded by a newer outfit
                    putMemorySkinCache(composed.sha256Hash(), signed);
                    database.putCachedSkin(composed.sha256Hash(), signed);
                    applyComposedOnMainThread(player, state, composed.sha256Hash(), signed, generation, true);
                });

            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to compose wardrobe skin for " + player.getName(), e);
            }
        });
    }

    private boolean isCurrentGeneration(UUID uuid, long gen) {
        AtomicLong current = skinGenerations.get(uuid);
        return current == null || current.get() == gen;
    }

    private void putMemorySkinCache(String hash, SignedSkinData data) {
        if (hash == null || data == null || !data.isValid()) return;
        if (memorySkinCache.size() > 2048) {
            memorySkinCache.clear();
        }
        memorySkinCache.put(hash, data);
    }

    public void setGuiReopener(BiConsumer<Player, WardrobeGuiHolder> reopener) {
        this.guiReopener = reopener;
    }

    /**
     * Returns true when the player currently has ANY menu/chest/wardrobe GUI open
     * (i.e. anything other than their own default survival/creative crafting view).
     */
    public boolean isAnyMenuGuiOpen(Player player) {
        if (player == null || !player.isOnline()) return false;
        try {
            Inventory top = player.getOpenInventory().getTopInventory();
            if (top == null) return false;
            if (top.getHolder() instanceof WardrobeGuiHolder) return true;
            InventoryType type = top.getType();
            return type != InventoryType.CRAFTING && type != InventoryType.CREATIVE && type != InventoryType.PLAYER;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Called from InventoryCloseEvent for ANY inventory. Waits 2 ticks so tab switches / Back button
     * navigation keep the menu uninterrupted; once the player actually exits menus, applies any
     * deferred ready-signed skin in 0 ms!
     */
    public void onAnyInventoryClosed(Player player) {
        if (player == null || !player.isOnline()) return;
        UUID uuid = player.getUniqueId();
        if (!deferredSkinApplies.containsKey(uuid)) return;

        BukkitTask prev = deferredFlushTasks.remove(uuid);
        if (prev != null) prev.cancel();

        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            deferredFlushTasks.remove(uuid);
            Player online = Bukkit.getPlayer(uuid);
            if (online == null || !online.isOnline()) return;
            if (isAnyMenuGuiOpen(online)) {
                return; // Player navigated to another menu/tab (e.g. Back button) -> keep deferring!
            }
            PendingSkinApply pending = deferredSkinApplies.remove(uuid);
            PlayerWardrobeState st = states.get(uuid);
            if (pending != null && st != null) {
                executeSignedSkinApplyNow(online, st, pending.hash(), pending.signed(), pending.notifySuccess(), false);
            }
        }, 2L);
        deferredFlushTasks.put(uuid, task);
    }

    private void applyComposedOnMainThread(Player player, PlayerWardrobeState state, String hash, SignedSkinData signed, long generation, boolean notifySuccess) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            if (!isCurrentGeneration(player.getUniqueId(), generation)) {
                return; // A newer outfit request superseded this one; cached safely, skip stale apply
            }
            // Prevent Minecraft client's Respawn packet from force-closing the player's open GUI/menu!
            if (config.isDeferSkinApplyWhileGuiOpen() && isAnyMenuGuiOpen(player)) {
                deferredSkinApplies.put(player.getUniqueId(), new PendingSkinApply(hash, signed, notifySuccess));
                if (config.isDebug()) {
                    plugin.getLogger().info("[Debug] Deferred skin apply for " + player.getName() + " while GUI is open.");
                }
                return;
            }
            deferredSkinApplies.remove(player.getUniqueId());
            executeSignedSkinApplyNow(player, state, hash, signed, notifySuccess, true);
        });
    }

    private void executeSignedSkinApplyNow(
            Player player,
            PlayerWardrobeState state,
            String hash,
            SignedSkinData signed,
            boolean notifySuccess,
            boolean allowAutoReopen
    ) {
        WardrobeGuiHolder openWardrobe = null;
        if (allowAutoReopen && config.isAutoReopenGuiAfterSkinApply()) {
            try {
                Inventory top = player.getOpenInventory().getTopInventory();
                if (top != null && top.getHolder() instanceof WardrobeGuiHolder holder) {
                    openWardrobe = holder;
                }
            } catch (Throwable ignored) {
            }
        }

        state.setLastAppliedSkinHash(hash);
        skinBackend.applySignedSkin(player, signed);
        if (notifySuccess) {
            player.sendMessage(TextUtil.parse(config.getPrefixedMessage("skin-updated", "Скин обновлён!")));
        }
        schedulePostSkinVisualResync(player);

        if (openWardrobe != null && guiReopener != null) {
            WardrobeGuiHolder snapshot = openWardrobe;
            UUID uuid = player.getUniqueId();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Player online = Bukkit.getPlayer(uuid);
                if (online != null && online.isOnline() && !isAnyMenuGuiOpen(online)) {
                    guiReopener.accept(online, snapshot);
                }
            }, 2L);
        }
    }

    /**
     * Background pre-warmer: signs all configured `body_skin` textures (both wide & slim) into
     * L1 RAM + SQLite cache so switching base skin tones becomes instant (0 MineSkin wait).
     */
    public void prewarmBaseSkinsAsync(CommandSender sender) {
        List<SkinLayerDef> baseLayers = config.getSkinLayersOrdered().stream()
                .filter(SkinLayerDef::replacesBaseSkin)
                .toList();

        List<String> jobs = new ArrayList<>();
        for (SkinLayerDef def : baseLayers) {
            for (String tex : layerRegistry.getTexturesInFolder(def.folder())) {
                jobs.add(def.folder() + "|" + tex);
            }
        }
        int totalVariants = jobs.size() * 2;
        if (totalVariants == 0) {
            sender.sendMessage(TextUtil.parse(config.getPrefix() + "<yellow>Нет базовых слоёв (replaces-base-skin: true) для прогрева."));
            return;
        }

        sender.sendMessage(TextUtil.parse(
                config.getPrefixedMessage("prewarm-started", "Запущен прогрев <total> базовых вариантов..."),
                Map.of("total", String.valueOf(totalVariants))
        ));

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            AtomicInteger cachedCount = new AtomicInteger();
            AtomicInteger signedCount = new AtomicInteger();
            AtomicInteger failedCount = new AtomicInteger();

            for (String item : jobs) {
                String[] parts = item.split("\\|", 2);
                String folder = parts[0];
                String tex = parts[1];
                for (SkinModelVariant variant : new SkinModelVariant[]{SkinModelVariant.CLASSIC, SkinModelVariant.SLIM}) {
                    try {
                        Optional<SkinLayerRegistry.LoadedOverlay> overlay = layerRegistry.resolve(folder, tex, variant);
                        if (overlay.isEmpty()) continue;

                        SkinCompositor.ComposedSkin composed = SkinCompositor.compose(
                                new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB),
                                List.of(new SkinCompositor.LayerInput(overlay.get().image(), Set.of(), true)),
                                variant,
                                config.isClearAllOuterLayer()
                        );

                        if (memorySkinCache.containsKey(composed.sha256Hash())) {
                            cachedCount.incrementAndGet();
                            continue;
                        }
                        Optional<SignedSkinData> existing = database.getCachedSkin(composed.sha256Hash());
                        if (existing.isPresent()) {
                            putMemorySkinCache(composed.sha256Hash(), existing.get());
                            cachedCount.incrementAndGet();
                            continue;
                        }

                        SignedSkinData signed = mineSkinClient.uploadAndSign(composed.sha256Hash(), composed.pngBytes(), variant).join();
                        if (signed != null && signed.isValid()) {
                            putMemorySkinCache(composed.sha256Hash(), signed);
                            database.putCachedSkin(composed.sha256Hash(), signed);
                            signedCount.incrementAndGet();
                        } else {
                            failedCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failedCount.incrementAndGet();
                        plugin.getLogger().warning("Prewarm failed for " + tex + " (" + variant + "): " + e.getMessage());
                    }
                }
            }

            Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(TextUtil.parse(
                    config.getPrefixedMessage("prewarm-done", "Прогрев завершён: новых <signed>, из кэша <cached>, ошибок <failed>."),
                    Map.of(
                            "signed", String.valueOf(signedCount.get()),
                            "cached", String.valueOf(cachedCount.get()),
                            "failed", String.valueOf(failedCount.get())
                    )
            )));
        });
    }

    private void schedulePostSkinVisualResync(Player player) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            armorVisualManager.refreshPlayerArmorVisuals(player);
            displayAccessoryManager.syncPlayerDisplays(player);
        }, 4L);
    }

    private BufferedImage loadBaseSkinImage(SignedSkinData base, SkinModelVariant variant) {
        if (base != null && base.textureUrl() != null && !base.textureUrl().isBlank()) {
            BufferedImage cached = baseSkinImageCache.get(base.textureUrl());
            if (cached != null) return cached;

            Optional<BufferedImage> downloaded = mineSkinClient.downloadSkinImage(base.textureUrl());
            if (downloaded.isPresent()) {
                BufferedImage img = downloaded.get();
                if (baseSkinImageCache.size() > 256) {
                    baseSkinImageCache.clear();
                }
                baseSkinImageCache.put(base.textureUrl(), img);
                return img;
            }
        }
        String fallbackName = variant == SkinModelVariant.SLIM
                ? config.getFallbackSlimBaseSkin()
                : config.getFallbackClassicBaseSkin();
        return layerRegistry.loadFallbackBaseSkin(fallbackName).orElseGet(SkinLayerRegistry::generateNeutralMannequin);
    }

    private void persistSkillLayersAsync(UUID uuid, PlayerWardrobeState state) {
        Map<String, String> snapshot = new HashMap<>(state.getSkillLayers());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> database.saveSkillLayers(uuid, snapshot));
    }

    private void triggerEquipSkill(Player player, ItemStack item) {
        if (mythicHook == null || item == null) return;
        ItemWardrobeMeta meta = itemResolver.resolve(item);
        if (meta.onEquipSkill() != null && !meta.onEquipSkill().isBlank()) {
            mythicHook.castSkill(player, meta.onEquipSkill());
        }
    }

    private void triggerUnequipSkill(Player player, ItemStack item) {
        if (mythicHook == null || item == null) return;
        ItemWardrobeMeta meta = itemResolver.resolve(item);
        if (meta.onUnequipSkill() != null && !meta.onUnequipSkill().isBlank()) {
            mythicHook.castSkill(player, meta.onUnequipSkill());
        }
    }

    public void reloadVisualsForAll() {
        displayAccessoryManager.start();
        for (Player p : Bukkit.getOnlinePlayers()) {
            armorVisualManager.refreshPlayerArmorVisuals(p);
            displayAccessoryManager.syncPlayerDisplays(p);
        }
    }

    public ArmorVisualManager getArmorVisualManager() {
        return armorVisualManager;
    }

    public DisplayAccessoryManager getDisplayAccessoryManager() {
        return displayAccessoryManager;
    }

    public SkinBackend getSkinBackend() {
        return skinBackend;
    }

    public WardrobeItemResolver getItemResolver() {
        return itemResolver;
    }
}
