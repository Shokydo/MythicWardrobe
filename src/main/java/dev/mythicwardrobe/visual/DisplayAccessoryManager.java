package dev.mythicwardrobe.visual;

import dev.mythicwardrobe.config.WardrobeConfig;
import dev.mythicwardrobe.manager.WardrobeItemResolver;
import dev.mythicwardrobe.model.DisplayMountConfig;
import dev.mythicwardrobe.model.ItemWardrobeMeta;
import dev.mythicwardrobe.model.PlayerWardrobeState;
import dev.mythicwardrobe.model.WardrobeSlotDef;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dual-mode simultaneous 3D accessory & multi-model armor layering engine:
 *   1) Synchronized invisible marker ArmorStand mode — AUTOMATIC for any MythicArmors / Equippable
 *      3D armor model (backpack chestplate, 3D gas mask helmet, shoulder/belt/leg overlays).
 *      Renders the FULL 3D MythicArmors core shader simultaneously with the player's own armor!
 *   2) ItemDisplay mode — for non-equippable custom 3D items (ItemModel on PAPER / IRON_NUGGET etc.).
 */
public final class DisplayAccessoryManager {

    private static final class ActiveAccessory {
        boolean armorStandMode;
        Entity entity; // ArmorStand or ItemDisplay
        EquipmentSlot armorSlot;
        DisplayMountConfig mount;
        boolean hasItemDisplayOverride;
        ItemStack renderedItem;
    }

    private static final class MotionTracker {
        double lastX;
        double lastZ;
        double walkPhase;
        double currentAmp;
        boolean initialized;
    }

    private final JavaPlugin plugin;
    private final WardrobeConfig config;
    private final WardrobeItemResolver resolver;
    private final ArmorVisualManager armorVisualManager;
    private final Map<UUID, PlayerWardrobeState> states;
    private final NamespacedKey overlayMarkerKey;

    // playerId -> (slotId -> ActiveAccessory)
    private final Map<UUID, Map<String, ActiveAccessory>> activeAccessories = new ConcurrentHashMap<>();
    private final Map<UUID, MotionTracker> motionTrackers = new ConcurrentHashMap<>();
    private BukkitTask tickTask;

    public DisplayAccessoryManager(
            JavaPlugin plugin,
            WardrobeConfig config,
            WardrobeItemResolver resolver,
            ArmorVisualManager armorVisualManager,
            Map<UUID, PlayerWardrobeState> states
    ) {
        this.plugin = plugin;
        this.config = config;
        this.resolver = resolver;
        this.armorVisualManager = armorVisualManager;
        this.states = states;
        this.overlayMarkerKey = new NamespacedKey(plugin, "overlay_entity");
    }

    public void start() {
        stop();
        if (!config.isDisplay3DEnabled()) return;
        long interval = config.getDisplayUpdateIntervalTicks();
        this.tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 1L, interval);
    }

    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        removeAllDisplays();
        motionTrackers.clear();
    }

    public void syncPlayerDisplays(Player player) {
        if (!config.isDisplay3DEnabled() || player == null || !player.isOnline()) {
            if (player != null) removePlayerDisplays(player.getUniqueId());
            return;
        }
        if (shouldHideAll(player)) {
            removePlayerDisplays(player.getUniqueId());
            return;
        }

        PlayerWardrobeState state = states.get(player.getUniqueId());
        if (state == null) {
            removePlayerDisplays(player.getUniqueId());
            return;
        }

        Map<String, ActiveAccessory> playerMap = activeAccessories.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>());
        Set<String> validSlotIds = new HashSet<>();

        for (WardrobeSlotDef slot : config.getDisplay3DSlots()) {
            ItemStack item = armorVisualManager.resolveEffectiveSlot3DItem(player, state, slot);
            boolean hiddenByCombo = armorVisualManager.isDisplayHiddenByCombo(player.getUniqueId(), slot.id());

            if (item == null || item.getType().isAir() || hiddenByCombo) {
                removeSlotDisplay(playerMap, slot.id());
                continue;
            }

            validSlotIds.add(slot.id());
            ItemWardrobeMeta meta = resolver.resolve(item);
            DisplayMountConfig mount = slot.displayMount() != null ? slot.displayMount() : DisplayMountConfig.defaultBack();
            boolean hasItemOverride = meta.displayOverride() != null;
            if (hasItemOverride) {
                mount = mount.mergeOverride(meta.displayOverride());
            }

            EquipmentSlot detectedEq = resolver.detectEquipmentSlot(item);
            boolean useArmorStand = (slot.isArmorOverlay() || detectedEq != null);
            EquipmentSlot targetEq = detectedEq != null
                    ? detectedEq
                    : (slot.equipmentSlot() != null ? slot.equipmentSlot() : slot.section().getEquipmentSlot());

            ActiveAccessory active = playerMap.get(slot.id());
            boolean modeChanged = active != null && (active.armorStandMode != useArmorStand
                    || (useArmorStand && active.armorSlot != targetEq));

            if (active == null || modeChanged || active.entity == null || !active.entity.isValid()
                    || !active.entity.getWorld().equals(player.getWorld())) {
                removeSlotDisplay(playerMap, slot.id());
                active = useArmorStand
                        ? spawnArmorStandOverlay(player, item, targetEq, mount)
                        : spawnItemDisplay(player, item, mount);
                if (active != null) {
                    playerMap.put(slot.id(), active);
                }
            } else {
                active.mount = mount;
                active.hasItemDisplayOverride = hasItemOverride;
                if (!item.equals(active.renderedItem)) {
                    active.renderedItem = item.clone();
                    if (active.armorStandMode && active.entity instanceof ArmorStand stand) {
                        equipStandSlot(stand, targetEq, active.renderedItem);
                    } else if (active.entity instanceof ItemDisplay disp) {
                        disp.setItemStack(active.renderedItem);
                        applyStaticTransform(disp, mount);
                    }
                }
                boolean hide = active.armorStandMode
                        ? config.isHideArmorOverlayFromWearer()
                        : mount.hideFromWearer();
                applyVisibility(player, active.entity, hide);
            }
        }

        // Remove stale slots if config reloaded
        for (String existing : new ArrayList<>(playerMap.keySet())) {
            if (!validSlotIds.contains(existing)) {
                removeSlotDisplay(playerMap, existing);
            }
        }
    }

    private void tickAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            Map<String, ActiveAccessory> map = activeAccessories.get(uuid);
            if (shouldHideAll(player)) {
                if (map != null && !map.isEmpty()) {
                    removePlayerDisplays(uuid);
                }
                continue;
            }
            if (map == null || map.isEmpty()) {
                PlayerWardrobeState state = states.get(uuid);
                if (state != null && hasAnyActive3DSlot(player, state)) {
                    syncPlayerDisplays(player);
                }
                continue;
            }

            // Update horizontal walk cycle once per player per tick
            MotionTracker motion = updateMotion(player);

            for (Map.Entry<String, ActiveAccessory> entry : map.entrySet()) {
                ActiveAccessory active = entry.getValue();
                if (active == null || active.entity == null || !active.entity.isValid()
                        || !active.entity.getWorld().equals(player.getWorld())) {
                    syncPlayerDisplays(player);
                    break;
                }
                if (active.armorStandMode && active.entity instanceof ArmorStand stand) {
                    updateArmorStand(player, stand, active, motion);
                } else if (active.entity instanceof ItemDisplay disp) {
                    updateItemDisplay(player, disp, active.mount);
                }
            }
        }
    }

    private MotionTracker updateMotion(Player player) {
        MotionTracker mt = motionTrackers.computeIfAbsent(player.getUniqueId(), k -> new MotionTracker());
        Location loc = player.getLocation();
        if (!mt.initialized) {
            mt.lastX = loc.getX();
            mt.lastZ = loc.getZ();
            mt.initialized = true;
            return mt;
        }
        double dx = loc.getX() - mt.lastX;
        double dz = loc.getZ() - mt.lastZ;
        mt.lastX = loc.getX();
        mt.lastZ = loc.getZ();

        double dist = Math.hypot(dx, dz);
        if (dist > 0.008 && dist < 2.5) {
            mt.walkPhase += dist * 2.25;
            double targetAmp = Math.min(0.72, dist * 2.5);
            mt.currentAmp = mt.currentAmp * 0.55 + targetAmp * 0.45;
        } else {
            mt.currentAmp *= 0.6;
            if (mt.currentAmp < 0.01) mt.currentAmp = 0.0;
        }
        return mt;
    }

    private boolean hasAnyActive3DSlot(Player player, PlayerWardrobeState state) {
        for (WardrobeSlotDef slot : config.getDisplay3DSlots()) {
            ItemStack item = armorVisualManager.resolveEffectiveSlot3DItem(player, state, slot);
            if (item != null && !item.getType().isAir()
                    && !armorVisualManager.isDisplayHiddenByCombo(player.getUniqueId(), slot.id())) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Mode 1: Synchronized Invisible Marker ArmorStand (Full MythicArmors 3D!)
    // -------------------------------------------------------------------------
    private ActiveAccessory spawnArmorStandOverlay(
            Player player,
            ItemStack item,
            EquipmentSlot armorSlot,
            DisplayMountConfig mount
    ) {
        Location loc = computeArmorStandLocation(player);
        if (loc.getWorld() == null) return null;

        ArmorStand stand = loc.getWorld().spawn(loc, ArmorStand.class, as -> {
            as.setPersistent(false);
            as.setInvisible(true);
            as.setMarker(true);
            as.setSmall(false);
            as.setBasePlate(false);
            as.setArms(true);
            as.setGravity(false);
            as.setSilent(true);
            as.setInvulnerable(true);
            as.setCollidable(false);
            as.setCanPickupItems(false);
            as.getPersistentDataContainer().set(overlayMarkerKey, PersistentDataType.BYTE, (byte) 1);

            for (EquipmentSlot eq : new EquipmentSlot[]{
                    EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                    EquipmentSlot.FEET, EquipmentSlot.HAND, EquipmentSlot.OFF_HAND
            }) {
                as.addEquipmentLock(eq, ArmorStand.LockType.ADDING_OR_CHANGING);
                as.addEquipmentLock(eq, ArmorStand.LockType.REMOVING_OR_CHANGING);
            }
            equipStandSlot(as, armorSlot, item.clone());
        });

        // ArmorStand 3D overlays ignore legacy ItemDisplay `hide-from-wearer: true` in old slots.yml
        // so standalone & stacked masks/backpacks are ALWAYS visible in F5 unless explicitly hidden in config.yml!
        applyVisibility(player, stand, config.isHideArmorOverlayFromWearer());

        ActiveAccessory active = new ActiveAccessory();
        active.armorStandMode = true;
        active.entity = stand;
        active.armorSlot = armorSlot;
        active.mount = mount;
        active.renderedItem = item.clone();

        updateArmorStand(player, stand, active, motionTrackers.computeIfAbsent(player.getUniqueId(), k -> new MotionTracker()));
        return active;
    }

    private static void equipStandSlot(ArmorStand stand, EquipmentSlot slot, ItemStack item) {
        EntityEquipment eq = stand.getEquipment();
        if (eq == null) return;
        eq.clear();
        if (slot != null && item != null && !item.getType().isAir()) {
            eq.setItem(slot, item.clone());
        }
    }

    private void updateArmorStand(Player player, ArmorStand stand, ActiveAccessory active, MotionTracker motion) {
        Location target = computeArmorStandLocation(player);
        stand.teleport(target);

        float bodyYaw = getPlayerBodyYaw(player);
        float headYaw = player.getLocation().getYaw();
        float headPitch = player.getLocation().getPitch();
        float yawDelta = wrapDegrees(headYaw - bodyYaw);

        stand.setHeadPose(new EulerAngle(Math.toRadians(headPitch), Math.toRadians(yawDelta), 0.0));

        boolean sneaking = player.isSneaking();
        stand.setBodyPose(sneaking ? new EulerAngle(0.50, 0.0, 0.0) : EulerAngle.ZERO);

        if (motion != null && motion.currentAmp > 0.01) {
            double swing = Math.sin(motion.walkPhase) * motion.currentAmp;
            double sneakArm = sneaking ? 0.35 : 0.0;
            stand.setRightArmPose(new EulerAngle(swing + sneakArm, 0.0, 0.0));
            stand.setLeftArmPose(new EulerAngle(-swing + sneakArm, 0.0, 0.0));
            stand.setRightLegPose(new EulerAngle(-swing * 0.85, 0.0, 0.0));
            stand.setLeftLegPose(new EulerAngle(swing * 0.85, 0.0, 0.0));
        } else {
            EulerAngle idleArm = sneaking ? new EulerAngle(0.35, 0.0, 0.0) : EulerAngle.ZERO;
            stand.setRightArmPose(idleArm);
            stand.setLeftArmPose(idleArm);
            stand.setRightLegPose(EulerAngle.ZERO);
            stand.setLeftLegPose(EulerAngle.ZERO);
        }
    }

    private Location computeArmorStandLocation(Player player) {
        Location loc = player.getLocation().clone();
        float bodyYaw = getPlayerBodyYaw(player);

        // Velocity lead compensation: offsets client entity teleport interpolation lag (~2 ticks)
        // when running/sprinting so un-merged 3D ArmorStand overlays stay glued to the player!
        org.bukkit.util.Vector vel = player.getVelocity();
        double leadX = clamp(vel.getX() * 1.55, -0.45, 0.45);
        double leadZ = clamp(vel.getZ() * 1.55, -0.45, 0.45);

        // Vanilla ArmorStand head pivot is 1.4375 blocks vs Player's 1.40625 (0.9375 scale):
        // -0.03 standing / -0.25 sneaking aligns head, chest, legs & feet to sub-pixel precision!
        double yOffset = player.isSneaking() ? -0.25 : -0.03;
        if (player.isSneaking()) {
            double yawRad = Math.toRadians(bodyYaw);
            double forwardX = -Math.sin(yawRad);
            double forwardZ = Math.cos(yawRad);
            loc.add(leadX + forwardX * -0.04, yOffset, leadZ + forwardZ * -0.04);
        } else {
            loc.add(leadX, yOffset, leadZ);
        }

        loc.setYaw(bodyYaw);
        loc.setPitch(0f);
        return loc;
    }

    private static double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }

    private static float getPlayerBodyYaw(Player player) {
        try {
            return player.getBodyYaw();
        } catch (Throwable ignored) {
            return player.getLocation().getYaw();
        }
    }

    private static float wrapDegrees(float deg) {
        float d = deg % 360.0f;
        if (d >= 180.0f) d -= 360.0f;
        if (d < -180.0f) d += 360.0f;
        return d;
    }

    // -------------------------------------------------------------------------
    // Mode 2: ItemDisplay (Custom non-armor 3D items with ItemModel)
    // -------------------------------------------------------------------------
    private ActiveAccessory spawnItemDisplay(Player player, ItemStack item, DisplayMountConfig mount) {
        Location loc = computeItemDisplayLocation(player, mount);
        if (loc.getWorld() == null) return null;

        ItemDisplay display = loc.getWorld().spawn(loc, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setGravity(false);
            d.setInvulnerable(true);
            d.setSilent(true);
            d.setBillboard(Display.Billboard.FIXED);
            d.setTeleportDuration(config.getDisplayTeleportDuration());
            d.setInterpolationDuration(1);
            d.setViewRange(1.0f);
            d.setItemStack(item.clone());
            d.setItemDisplayTransform(mount.transform());
            d.getPersistentDataContainer().set(overlayMarkerKey, PersistentDataType.BYTE, (byte) 1);
            applyStaticTransform(d, mount);
        });

        applyVisibility(player, display, mount.hideFromWearer());

        ActiveAccessory active = new ActiveAccessory();
        active.armorStandMode = false;
        active.entity = display;
        active.mount = mount;
        active.renderedItem = item.clone();
        return active;
    }

    private void applyStaticTransform(ItemDisplay display, DisplayMountConfig mount) {
        display.setItemDisplayTransform(mount.transform());
        Quaternionf rot = new Quaternionf().rotateYXZ(
                (float) Math.toRadians(mount.rotY()),
                (float) Math.toRadians(mount.rotX()),
                (float) Math.toRadians(mount.rotZ())
        );
        display.setTransformation(new Transformation(
                new Vector3f(0f, 0f, 0f),
                rot,
                new Vector3f(mount.scaleX(), mount.scaleY(), mount.scaleZ()),
                new Quaternionf()
        ));
    }

    private void updateItemDisplay(Player player, ItemDisplay disp, DisplayMountConfig mount) {
        Location target = computeItemDisplayLocation(player, mount);
        disp.teleport(target);
    }

    private Location computeItemDisplayLocation(Player player, DisplayMountConfig mount) {
        Location base;
        float yaw = mount.mountPoint() == DisplayMountConfig.MountPoint.HEAD
                ? player.getLocation().getYaw()
                : getPlayerBodyYaw(player);
        float pitch = mount.pitchFollow() ? player.getLocation().getPitch() : 0f;

        if (mount.mountPoint() == DisplayMountConfig.MountPoint.HEAD) {
            base = player.getEyeLocation().clone();
        } else {
            base = player.getLocation().clone();
            if (player.isSneaking()) {
                base.add(0, -0.22, 0);
            }
        }

        org.bukkit.util.Vector vel = player.getVelocity();
        double leadX = clamp(vel.getX() * 1.4, -0.45, 0.45);
        double leadZ = clamp(vel.getZ() * 1.4, -0.45, 0.45);

        double yawRad = Math.toRadians(yaw);
        double forwardX = -Math.sin(yawRad);
        double forwardZ = Math.cos(yawRad);
        double rightX = Math.cos(yawRad);
        double rightZ = Math.sin(yawRad);

        double worldOffsetX = rightX * mount.offsetX() + forwardX * mount.offsetZ();
        double worldOffsetZ = rightZ * mount.offsetX() + forwardZ * mount.offsetZ();

        base.add(leadX + worldOffsetX, mount.offsetY(), leadZ + worldOffsetZ);
        base.setYaw(yaw);
        base.setPitch(pitch);
        return base;
    }

    private void applyVisibility(Player player, Entity entity, boolean hideFromWearer) {
        if (hideFromWearer) {
            player.hideEntity(plugin, entity);
        } else {
            player.showEntity(plugin, entity);
        }
    }

    private static boolean shouldHideAll(Player player) {
        return player.isDead()
                || player.getGameMode() == GameMode.SPECTATOR
                || player.hasPotionEffect(PotionEffectType.INVISIBILITY)
                || player.isGliding();
    }

    private void removeSlotDisplay(Map<String, ActiveAccessory> map, String slotId) {
        ActiveAccessory removed = map.remove(slotId);
        if (removed != null && removed.entity != null && removed.entity.isValid()) {
            removed.entity.remove();
        }
    }

    public void removePlayerDisplays(UUID playerId) {
        motionTrackers.remove(playerId);
        Map<String, ActiveAccessory> map = activeAccessories.remove(playerId);
        if (map == null) return;
        for (ActiveAccessory active : map.values()) {
            if (active != null && active.entity != null && active.entity.isValid()) {
                active.entity.remove();
            }
        }
    }

    public void removeAllDisplays() {
        for (UUID id : new ArrayList<>(activeAccessories.keySet())) {
            removePlayerDisplays(id);
        }
    }

    public int countActiveDisplays(UUID playerId) {
        Map<String, ActiveAccessory> map = activeAccessories.get(playerId);
        return map == null ? 0 : map.size();
    }
}
