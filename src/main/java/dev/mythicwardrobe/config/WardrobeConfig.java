package dev.mythicwardrobe.config;

import dev.mythicwardrobe.model.*;
import dev.mythicwardrobe.visual.HeadCompositePackBuilder;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.*;

public final class WardrobeConfig {

    public enum ClickMode {
        EMPTY_OR_RIGHT,
        ALWAYS,
        RIGHT_CLICK_ONLY
    }

    public enum SkinBackendMode {
        AUTO,
        SKINSRESTORER,
        PAPER
    }

    private final JavaPlugin plugin;

    // MineSkin
    private String mineSkinApiKey;
    private String mineSkinBaseUrl;
    private String mineSkinUserAgent;
    private String mineSkinVisibility;
    private int mineSkinMaxConcurrency;
    private int mineSkinTimeoutSeconds;
    private long mineSkinPollIntervalMs;
    private long rebuildDebounceTicks;

    // Skin
    private SkinBackendMode skinBackendMode;
    private boolean createExampleOverlays;
    private SkinModelVariant defaultVariant;
    private String fallbackClassicBaseSkin;
    private String fallbackSlimBaseSkin;
    private boolean clearAllOuterLayer;
    private boolean reapplyOnBaseSkinChange;
    private boolean deferSkinApplyWhileGuiOpen;
    private boolean autoReopenGuiAfterSkinApply;

    // Inventory Trigger
    private boolean inventoryTriggerEnabled;
    private ClickMode clickMode;
    private boolean smartCursorEquip;
    private boolean rightClickQuickEquip;

    // Cosmetic Armor
    private boolean cosmeticArmorEnabled;
    private boolean showCosmeticToSelf;

    // 3D Display
    private boolean display3DEnabled;
    private long displayUpdateIntervalTicks;
    private int displayTeleportDuration;
    private boolean hideArmorOverlayFromWearer;

    // Death
    private boolean keepWardrobeItemsOnDeath;

    // Layer / Slot Blocking (e.g. full Hazmat Suit in jacket blocking pants/undershirt/etc.)
    private BlockBehaviorMode defaultBlockMode = BlockBehaviorMode.UNEQUIP;
    private Material lockedSlotMaterial = Material.BARRIER;
    private boolean inheritClearOuterRegionsFromBlocked = true;

    // Temporary Web Editor (dev.mythicwardrobe.web)
    private boolean webEditorEnabled;
    private String webEditorHost;
    private int webEditorPort;
    private String webEditorPublicUrl;
    private int webEditorTokenExpireMinutes;

    // Debug
    private boolean debug;

    // Loaded configs
    private final Map<String, WardrobeSlotDef> slotsById = new LinkedHashMap<>();
    private final Map<ArmorSection, List<WardrobeSlotDef>> slotsBySection = new EnumMap<>(ArmorSection.class);
    private final Map<String, SkinLayerDef> skinLayersById = new LinkedHashMap<>();
    private final Map<String, String> skinLayerAliasToId = new HashMap<>();
    private List<SkinLayerDef> skinLayersOrdered = List.of();
    private final Map<String, ItemWardrobeMeta> configuredItems = new HashMap<>();
    private final List<ComboOverrideRule> comboRules = new ArrayList<>();
    private Hair3DConfig hair3DConfig = Hair3DConfig.empty();
    private MenuConfigLoader.ParsedMenusConfig menusConfig;
    private YamlConfiguration messagesConfig;
    private volatile HeadCompositePackBuilder.Manifest headCompositeManifest;

    public WardrobeConfig(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        ensureDefaultFile("config.yml");
        ensureDefaultFile("layers.yml");
        ensureDefaultFile("priority.yml");
        ensureDefaultFile("slots.yml");
        ensureDefaultFile("menus.yml");
        ensureDefaultFile("items.yml");
        ensureDefaultFile("combo-overrides.yml");
        ensureDefaultFile("hair-3d.yml");
        ensureDefaultFile("messages.yml");

        plugin.reloadConfig();
        var cfg = plugin.getConfig();

        this.mineSkinApiKey = cfg.getString("mineskin.api-key", "");
        String rawBaseUrl = cfg.getString("mineskin.base-url", "https://api.mineskin.org");
        this.mineSkinBaseUrl = rawBaseUrl.endsWith("/") ? rawBaseUrl.substring(0, rawBaseUrl.length() - 1) : rawBaseUrl;
        this.mineSkinUserAgent = cfg.getString("mineskin.user-agent", "MythicWardrobe/1.2");
        this.mineSkinVisibility = cfg.getString("mineskin.visibility", "unlisted");
        this.mineSkinMaxConcurrency = Math.max(1, Math.min(5, cfg.getInt("mineskin.max-concurrency", 4)));
        this.mineSkinTimeoutSeconds = Math.max(10, cfg.getInt("mineskin.timeout-seconds", 45));
        this.mineSkinPollIntervalMs = Math.max(300L, cfg.getLong("mineskin.poll-interval-ms", 700L));
        this.rebuildDebounceTicks = Math.max(1L, cfg.getLong("mineskin.rebuild-debounce-ticks", 8L));

        this.skinBackendMode = parseEnum(cfg.getString("skin.backend", "AUTO"), SkinBackendMode.AUTO);
        this.createExampleOverlays = cfg.getBoolean("skin.create-example-overlays", true);
        this.defaultVariant = SkinModelVariant.fromString(cfg.getString("skin.default-variant", "CLASSIC"), SkinModelVariant.CLASSIC);
        this.fallbackClassicBaseSkin = cfg.getString("skin.fallback-base-skin.classic", "default_classic.png");
        this.fallbackSlimBaseSkin = cfg.getString("skin.fallback-base-skin.slim", "default_slim.png");
        this.clearAllOuterLayer = cfg.getBoolean("skin.clear-all-outer-layer", false);
        this.reapplyOnBaseSkinChange = cfg.getBoolean("skin.reapply-on-base-skin-change", true);
        this.deferSkinApplyWhileGuiOpen = cfg.getBoolean("skin.defer-apply-while-gui-open", true);
        this.autoReopenGuiAfterSkinApply = cfg.getBoolean("skin.auto-reopen-gui-after-skin-apply", true);

        this.inventoryTriggerEnabled = cfg.getBoolean("inventory-trigger.enabled", true);
        this.clickMode = parseEnum(cfg.getString("inventory-trigger.click-mode", "EMPTY_OR_RIGHT"), ClickMode.EMPTY_OR_RIGHT);
        this.smartCursorEquip = cfg.getBoolean("inventory-trigger.smart-cursor-equip", true);
        this.rightClickQuickEquip = cfg.getBoolean("inventory-trigger.right-click-quick-equip", true);

        this.cosmeticArmorEnabled = cfg.getBoolean("cosmetic-armor.enabled", true);
        this.showCosmeticToSelf = cfg.getBoolean("cosmetic-armor.show-to-self", true);

        this.display3DEnabled = cfg.getBoolean("display-3d.enabled", true);
        this.displayUpdateIntervalTicks = Math.max(1L, cfg.getLong("display-3d.update-interval-ticks", 1L));
        this.displayTeleportDuration = Math.max(0, Math.min(10, cfg.getInt("display-3d.teleport-duration", 2)));
        this.hideArmorOverlayFromWearer = cfg.getBoolean("display-3d.hide-armor-overlay-from-wearer", false);

        this.keepWardrobeItemsOnDeath = cfg.getBoolean("death.keep-wardrobe-items-on-death", true);

        this.defaultBlockMode = BlockBehaviorMode.parseOrDefault(
                cfg.getString("blocking.default-mode", "UNEQUIP"), BlockBehaviorMode.UNEQUIP);
        Material lockMat = Material.matchMaterial(cfg.getString("blocking.locked-slot-material", "BARRIER"));
        this.lockedSlotMaterial = lockMat != null ? lockMat : Material.BARRIER;
        this.inheritClearOuterRegionsFromBlocked = cfg.getBoolean("blocking.inherit-clear-outer-regions", true);

        this.webEditorEnabled = cfg.getBoolean("web-editor.enabled", true);
        this.webEditorHost = cfg.getString("web-editor.host", "0.0.0.0");
        this.webEditorPort = Math.max(1024, Math.min(65535, cfg.getInt("web-editor.port", 8095)));
        String pub = cfg.getString("web-editor.public-url", "http://localhost:" + this.webEditorPort);
        this.webEditorPublicUrl = pub.endsWith("/") ? pub.substring(0, pub.length() - 1) : pub;
        this.webEditorTokenExpireMinutes = Math.max(1, cfg.getInt("web-editor.token-expire-minutes", 30));

        this.debug = cfg.getBoolean("debug", false);

        loadSlots();
        loadSkinLayers();
        loadItems();
        loadCombos();
        loadHair3D();
        loadMenus();
        reloadHeadCompositeManifest();
        this.messagesConfig = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "messages.yml"));
    }

    private void loadHair3D() {
        File f = new File(plugin.getDataFolder(), "hair-3d.yml");
        if (f.isFile()) {
            this.hair3DConfig = Hair3DConfig.load(YamlConfiguration.loadConfiguration(f));
        } else {
            this.hair3DConfig = Hair3DConfig.empty();
        }
    }

    public Hair3DConfig getHair3DConfig() {
        return hair3DConfig;
    }

    public void reloadHeadCompositeManifest() {
        File mf = new File(plugin.getDataFolder(), "head_composite_manifest.json");
        this.headCompositeManifest = HeadCompositePackBuilder.loadManifest(mf.toPath()).orElse(null);
    }

    public HeadCompositePackBuilder.Manifest getHeadCompositeManifest() {
        return headCompositeManifest;
    }

    private void loadMenus() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "menus.yml"));
        this.menusConfig = MenuConfigLoader.load(yaml);
    }

    private void loadSkinLayers() {
        skinLayersById.clear();
        skinLayerAliasToId.clear();

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "layers.yml"));
        ConfigurationSection root = yaml.getConfigurationSection("layers");
        if (root != null) {
            for (String rawId : root.getKeys(false)) {
                ConfigurationSection sec = root.getConfigurationSection(rawId);
                if (sec == null) continue;
                String id = rawId.trim().toLowerCase(Locale.ROOT);
                int priority = sec.getInt("priority", 50);
                String folder = sec.getString("folder", id);
                boolean replacesBase = sec.getBoolean("replaces-base-skin", false);
                Set<String> clearRegions = new LinkedHashSet<>();
                for (String r : sec.getStringList("clear-outer-regions")) {
                    clearRegions.add(r.trim().toUpperCase(Locale.ROOT));
                }
                List<String> aliases = new ArrayList<>();
                for (String al : sec.getStringList("aliases")) {
                    if (al != null && !al.isBlank()) {
                        aliases.add(al.trim().toLowerCase(Locale.ROOT));
                    }
                }
                SkinLayerDef def = new SkinLayerDef(
                        id, priority, folder, replacesBase,
                        Collections.unmodifiableSet(clearRegions),
                        Collections.unmodifiableList(aliases)
                );
                skinLayersById.put(id, def);
                skinLayerAliasToId.put(id, id);
                for (String al : aliases) {
                    skinLayerAliasToId.putIfAbsent(al, id);
                }
            }
        }

        // Also register any SKIN_LAYER slot from slots.yml not explicitly listed in layers.yml
        for (WardrobeSlotDef slot : slotsById.values()) {
            if (!slot.isSkinLayer()) continue;
            if (!skinLayersById.containsKey(slot.id())) {
                SkinLayerDef def = new SkinLayerDef(
                        slot.id(),
                        slot.priority(),
                        slot.folder(),
                        false,
                        slot.clearOuterRegions(),
                        List.of()
                );
                skinLayersById.put(def.id(), def);
                skinLayerAliasToId.putIfAbsent(def.id(), def.id());
            }
        }

        // Apply explicit top-to-bottom order from priority.yml (index 0 = top-most, last = bottom-most!)
        File priorityFile = new File(plugin.getDataFolder(), "priority.yml");
        if (priorityFile.isFile()) {
            YamlConfiguration prioYaml = YamlConfiguration.loadConfiguration(priorityFile);
            List<String> orderList = prioYaml.getStringList("order");
            if (orderList.isEmpty()) {
                orderList = prioYaml.getStringList("priority");
            }
            applyPriorityOrder(skinLayersById, skinLayerAliasToId, orderList);
        }

        List<SkinLayerDef> ordered = new ArrayList<>(skinLayersById.values());
        ordered.sort(Comparator.comparingInt(SkinLayerDef::priority).thenComparing(SkinLayerDef::id));
        this.skinLayersOrdered = Collections.unmodifiableList(ordered);
    }

    /**
     * Applies top-to-bottom priority ordering from priority.yml:
     *   order[0] = TOP-most layer (highest numeric Z-priority, drawn LAST on top of everything)
     *   order[last] = BOTTOM-most layer (lowest numeric Z-priority, drawn FIRST at the base)
     */
    public static void applyPriorityOrder(
            Map<String, SkinLayerDef> layersById,
            Map<String, String> aliasToId,
            List<String> topToBottomOrder
    ) {
        if (topToBottomOrder == null || topToBottomOrder.isEmpty() || layersById.isEmpty()) return;

        // Deduplicate & resolve aliases preserving top-to-bottom order
        List<String> resolved = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String raw : topToBottomOrder) {
            if (raw == null || raw.isBlank()) continue;
            String key = raw.trim().toLowerCase(Locale.ROOT);
            String canonical = aliasToId.getOrDefault(key, key);
            if (layersById.containsKey(canonical) && seen.add(canonical)) {
                resolved.add(canonical);
            }
        }
        if (resolved.isEmpty()) return;

        int n = resolved.size();
        // If user listed a partial subset (e.g. mask, hair, brows, eyes, body), give base-skin bottom=0
        // and non-base listed layers high steps above default priorities so their relative order is always respected!
        boolean partialList = n < layersById.size();
        for (int i = 0; i < n; i++) {
            String id = resolved.get(i);
            SkinLayerDef old = layersById.get(id);
            int rankFromBottom = (n - 1) - i; // 0 for bottom-most, n-1 for top-most
            int newPriority;
            if (old.replacesBaseSkin() && i == n - 1) {
                newPriority = 0;
            } else {
                newPriority = partialList ? (1000 + rankFromBottom * 10) : (rankFromBottom * 10);
            }
            layersById.put(id, new SkinLayerDef(
                    old.id(),
                    newPriority,
                    old.folder(),
                    old.replacesBaseSkin(),
                    old.clearOuterRegions(),
                    old.aliases()
            ));
        }
    }

    private void ensureDefaultFile(String name) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) {
            plugin.saveResource(name, false);
        }
    }

    private void loadSlots() {
        slotsById.clear();
        slotsBySection.clear();
        for (ArmorSection sec : ArmorSection.values()) {
            slotsBySection.put(sec, new ArrayList<>());
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "slots.yml"));
        ConfigurationSection root = yaml.getConfigurationSection("slots");
        if (root == null) return;

        for (String id : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(id);
            if (sec == null) continue;

            ArmorSection section = ArmorSection.fromName(sec.getString("section", "CHEST"));
            if (section == null) section = ArmorSection.CHEST;

            SlotKind kind = parseEnum(sec.getString("kind", "SKIN_LAYER"), SlotKind.SKIN_LAYER);
            EquipmentSlot eqSlot = null;
            String eqName = sec.getString("equipment-slot");
            if (eqName != null && !eqName.isBlank()) {
                eqSlot = parseEnum(eqName, section.getEquipmentSlot());
            } else if (kind == SlotKind.ARMOR_REAL || kind == SlotKind.ARMOR_COSMETIC
                    || kind == SlotKind.DISPLAY_3D || kind == SlotKind.ARMOR_OVERLAY) {
                eqSlot = section.getEquipmentSlot();
            }

            int guiSlot = sec.getInt("gui-slot", 13);
            int priority = sec.getInt("priority", 10);
            String folder = sec.getString("folder", id.toLowerCase());
            String displayName = sec.getString("display-name", id);
            Material mat = Material.matchMaterial(sec.getString("placeholder-material", "GRAY_STAINED_GLASS_PANE"));
            if (mat == null) mat = Material.GRAY_STAINED_GLASS_PANE;
            Integer placeholderCmd = sec.contains("placeholder-custom-model-data")
                    ? sec.getInt("placeholder-custom-model-data") : null;
            String placeholderItemModel = sec.getString("placeholder-item-model", "");
            if (placeholderItemModel != null && placeholderItemModel.isBlank()) placeholderItemModel = null;
            List<String> lore = sec.getStringList("lore");

            Set<String> clearRegions = new LinkedHashSet<>();
            for (String r : sec.getStringList("clear-outer-regions")) {
                clearRegions.add(r.trim().toUpperCase(Locale.ROOT));
            }

            DisplayMountConfig displayMount = parseDisplayConfig(sec.getConfigurationSection("display"),
                    section == ArmorSection.HEAD ? DisplayMountConfig.defaultHead() : DisplayMountConfig.defaultBack());

            WardrobeSlotDef def = new WardrobeSlotDef(
                    id.toLowerCase(Locale.ROOT),
                    section,
                    kind,
                    eqSlot,
                    guiSlot,
                    priority,
                    folder,
                    displayName,
                    mat,
                    lore,
                    Collections.unmodifiableSet(clearRegions),
                    displayMount,
                    placeholderCmd,
                    placeholderItemModel
            );
            slotsById.put(def.id(), def);
            slotsBySection.get(section).add(def);
        }
    }

    private void loadItems() {
        configuredItems.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "items.yml"));
        ConfigurationSection root = yaml.getConfigurationSection("items");
        if (root == null) return;

        for (String itemId : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(itemId);
            if (sec == null) continue;
            String slotId = sec.getString("slot", "");
            String texture = sec.getString("texture", "");
            String onEquip = sec.getString("on-equip-skill", "");
            String onUnequip = sec.getString("on-unequip-skill", "");
            DisplayMountConfig override = null;
            if (sec.isConfigurationSection("display-override")) {
                WardrobeSlotDef slotDef = slotsById.get(slotId.toLowerCase(Locale.ROOT));
                DisplayMountConfig base = slotDef != null && slotDef.displayMount() != null
                        ? slotDef.displayMount()
                        : DisplayMountConfig.defaultBack();
                override = parseDisplayConfig(sec.getConfigurationSection("display-override"), base);
            }
            Set<String> blockedTargets = readBlockedList(sec);
            BlockBehaviorMode blockMode = BlockBehaviorMode.parseOrNull(
                    sec.getString("block-mode", sec.getString("blocking-mode", sec.getString("lock-mode", ""))));
            configuredItems.put(itemId.toLowerCase(Locale.ROOT), new ItemWardrobeMeta(
                    slotId.toLowerCase(Locale.ROOT),
                    texture,
                    override,
                    onEquip,
                    onUnequip,
                    itemId,
                    blockedTargets,
                    blockMode
            ));
        }
    }

    private static Set<String> readBlockedList(ConfigurationSection sec) {
        String[] keys = {"blocks", "block-slots", "blocked-slots", "block-layers", "blocked-layers", "locked-slots", "lock-slots", "block"};
        Set<String> out = new LinkedHashSet<>();
        for (String key : keys) {
            if (!sec.contains(key)) continue;
            if (sec.isList(key)) {
                out.addAll(ItemWardrobeMeta.normalizeBlockedTargets(sec.getStringList(key)));
            } else {
                String raw = sec.getString(key, "");
                out.addAll(ItemWardrobeMeta.parseBlockedTargetsCsv(raw));
            }
        }
        return out.isEmpty() ? Set.of() : Collections.unmodifiableSet(out);
    }

    private void loadCombos() {
        comboRules.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "combo-overrides.yml"));
        ConfigurationSection root = yaml.getConfigurationSection("combos");
        if (root == null) return;

        for (String id : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(id);
            if (sec == null) continue;
            EquipmentSlot eqSlot = parseEnum(sec.getString("equipment-slot", "HEAD"), EquipmentSlot.HEAD);
            String primary = sec.getString("primary-mythic-item", "*");
            String secondarySlot = sec.getString("secondary-slot", "").toLowerCase(Locale.ROOT);
            String secondaryItem = sec.getString("secondary-mythic-item", "*");
            String resultItem = sec.getString("result-mythic-item", "");
            boolean hideDisplay = sec.getBoolean("hide-secondary-display", true);
            if (!secondarySlot.isBlank() && !resultItem.isBlank()) {
                comboRules.add(new ComboOverrideRule(id, eqSlot, primary, secondarySlot, secondaryItem, resultItem, hideDisplay));
            }
        }
    }

    public static DisplayMountConfig parseDisplayConfig(ConfigurationSection sec, DisplayMountConfig defaults) {
        if (sec == null) return defaults;
        DisplayMountConfig.MountPoint mount = parseEnum(sec.getString("mount", defaults.mountPoint().name()), defaults.mountPoint());
        float ox = (float) sec.getDouble("offset-x", defaults.offsetX());
        float oy = (float) sec.getDouble("offset-y", defaults.offsetY());
        float oz = (float) sec.getDouble("offset-z", defaults.offsetZ());
        float sx = (float) sec.getDouble("scale-x", defaults.scaleX());
        float sy = (float) sec.getDouble("scale-y", defaults.scaleY());
        float sz = (float) sec.getDouble("scale-z", defaults.scaleZ());
        float rx = (float) sec.getDouble("rot-x", defaults.rotX());
        float ry = (float) sec.getDouble("rot-y", defaults.rotY());
        float rz = (float) sec.getDouble("rot-z", defaults.rotZ());
        boolean pitchFollow = sec.getBoolean("pitch-follow", defaults.pitchFollow());
        boolean hideWearer = sec.getBoolean("hide-from-wearer", defaults.hideFromWearer());
        ItemDisplay.ItemDisplayTransform transform = parseEnum(sec.getString("transform", defaults.transform().name()), defaults.transform());
        return new DisplayMountConfig(mount, ox, oy, oz, sx, sy, sz, rx, ry, rz, pitchFollow, hideWearer, transform);
    }

    private static <E extends Enum<E>> E parseEnum(String val, E fallback) {
        if (val == null || val.isBlank()) return fallback;
        try {
            return Enum.valueOf(fallback.getDeclaringClass(), val.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    // Message helpers
    public String getPrefix() {
        return messagesConfig.getString("prefix", "<aqua>[Гардероб] <gray>");
    }

    public String getRawMessage(String path, String def) {
        return messagesConfig.getString(path, def);
    }

    public String getPrefixedMessage(String key, String def) {
        return getPrefix() + messagesConfig.getString("messages." + key, def);
    }

    // Getters
    public String getMineSkinApiKey() { return mineSkinApiKey; }
    public String getMineSkinBaseUrl() { return mineSkinBaseUrl; }
    public String getMineSkinUserAgent() { return mineSkinUserAgent; }
    public String getMineSkinVisibility() { return mineSkinVisibility; }
    public int getMineSkinMaxConcurrency() { return mineSkinMaxConcurrency; }
    public int getMineSkinTimeoutSeconds() { return mineSkinTimeoutSeconds; }
    public long getMineSkinPollIntervalMs() { return mineSkinPollIntervalMs; }
    public long getRebuildDebounceTicks() { return rebuildDebounceTicks; }

    public SkinBackendMode getSkinBackendMode() { return skinBackendMode; }
    public boolean isCreateExampleOverlays() { return createExampleOverlays; }
    public SkinModelVariant getDefaultVariant() { return defaultVariant; }
    public String getFallbackClassicBaseSkin() { return fallbackClassicBaseSkin; }
    public String getFallbackSlimBaseSkin() { return fallbackSlimBaseSkin; }
    public boolean isClearAllOuterLayer() { return clearAllOuterLayer; }
    public boolean isReapplyOnBaseSkinChange() { return reapplyOnBaseSkinChange; }
    public boolean isDeferSkinApplyWhileGuiOpen() { return deferSkinApplyWhileGuiOpen; }
    public boolean isAutoReopenGuiAfterSkinApply() { return autoReopenGuiAfterSkinApply; }

    public boolean isInventoryTriggerEnabled() { return inventoryTriggerEnabled; }
    public ClickMode getClickMode() { return clickMode; }
    public boolean isSmartCursorEquip() { return smartCursorEquip; }
    public boolean isRightClickQuickEquip() { return rightClickQuickEquip; }

    public boolean isCosmeticArmorEnabled() { return cosmeticArmorEnabled; }
    public boolean isShowCosmeticToSelf() { return showCosmeticToSelf; }

    public boolean isDisplay3DEnabled() { return display3DEnabled; }
    public long getDisplayUpdateIntervalTicks() { return displayUpdateIntervalTicks; }
    public int getDisplayTeleportDuration() { return displayTeleportDuration; }
    public boolean isHideArmorOverlayFromWearer() { return hideArmorOverlayFromWearer; }

    public boolean isKeepWardrobeItemsOnDeath() { return keepWardrobeItemsOnDeath; }
    public BlockBehaviorMode getDefaultBlockMode() { return defaultBlockMode; }
    public Material getLockedSlotMaterial() { return lockedSlotMaterial; }
    public boolean isInheritClearOuterRegionsFromBlocked() { return inheritClearOuterRegionsFromBlocked; }
    public boolean isWebEditorEnabled() { return webEditorEnabled; }
    public String getWebEditorHost() { return webEditorHost; }
    public int getWebEditorPort() { return webEditorPort; }
    public String getWebEditorPublicUrl() { return webEditorPublicUrl; }
    public int getWebEditorTokenExpireMinutes() { return webEditorTokenExpireMinutes; }
    public boolean isDebug() { return debug; }

    public Map<String, WardrobeSlotDef> getSlotsById() { return Collections.unmodifiableMap(slotsById); }
    public WardrobeSlotDef getSlot(String id) { return id == null ? null : slotsById.get(id.toLowerCase(Locale.ROOT)); }
    public List<WardrobeSlotDef> getSlotsForSection(ArmorSection section) {
        return Collections.unmodifiableList(slotsBySection.getOrDefault(section, List.of()));
    }

    public WardrobeSlotDef findSlotByKindAndEquipment(SlotKind kind, EquipmentSlot eqSlot) {
        for (WardrobeSlotDef def : slotsById.values()) {
            if (def.kind() == kind && def.equipmentSlot() == eqSlot) {
                return def;
            }
        }
        return null;
    }

    public List<WardrobeSlotDef> getSkinLayerSlotsOrdered() {
        return slotsById.values().stream()
                .filter(WardrobeSlotDef::isSkinLayer)
                .sorted(Comparator.comparingInt(WardrobeSlotDef::priority))
                .toList();
    }

    /**
     * Resolves any configured layer ID or alias from layers.yml / slots.yml into its canonical lowercase ID.
     * Falls back to normalized input if it's an ad-hoc type.
     */
    public String resolveLayerId(String typeOrAlias) {
        if (typeOrAlias == null || typeOrAlias.isBlank()) return "";
        String lower = typeOrAlias.trim().toLowerCase(Locale.ROOT);
        return skinLayerAliasToId.getOrDefault(lower, lower);
    }

    public SkinLayerDef getSkinLayerDef(String typeOrAlias) {
        String canonical = resolveLayerId(typeOrAlias);
        return canonical.isEmpty() ? null : skinLayersById.get(canonical);
    }

    public List<SkinLayerDef> getSkinLayersOrdered() {
        return skinLayersOrdered;
    }

    public Map<String, SkinLayerDef> getSkinLayersById() {
        return Collections.unmodifiableMap(skinLayersById);
    }

    public List<String> getAllValidLayerTypeIds() {
        return skinLayersOrdered.stream().map(SkinLayerDef::id).toList();
    }

    /**
     * Expands raw blocked target specifications (individual slot/layer IDs, aliases, and group shortcuts like
     * `all_clothing`, `all_body`/`suit`, `all_skin`, `all_3d`, `all_cosmetic`, `all`/`*`) into canonical IDs.
     * Never blocks `sourceSlotOrLayerId` itself or `body_skin` via wildcards.
     */
    public Set<String> expandBlockedTargets(String sourceSlotOrLayerId, Collection<String> rawTargets) {
        return expandBlockedTargets(sourceSlotOrLayerId, rawTargets, slotsById, skinLayersById, skinLayerAliasToId);
    }

    public static Set<String> expandBlockedTargets(
            String sourceSlotOrLayerId,
            Collection<String> rawTargets,
            Map<String, WardrobeSlotDef> slotsById,
            Map<String, SkinLayerDef> skinLayersById,
            Map<String, String> aliasToId
    ) {
        if (rawTargets == null || rawTargets.isEmpty()) return Set.of();

        String sourceCanonical = sourceSlotOrLayerId == null ? "" : sourceSlotOrLayerId.trim().toLowerCase(Locale.ROOT);
        if (!sourceCanonical.isEmpty() && aliasToId != null) {
            sourceCanonical = aliasToId.getOrDefault(sourceCanonical, sourceCanonical);
        }

        Set<String> expanded = new LinkedHashSet<>();
        for (String raw : rawTargets) {
            if (raw == null || raw.isBlank()) continue;
            for (String piece : raw.split("[,;\\s]+")) {
                String token = piece.trim().toLowerCase(Locale.ROOT);
                if (token.isEmpty()) continue;

                switch (token) {
                    case "all_clothing", "clothing" -> {
                        if (slotsById != null) {
                            for (WardrobeSlotDef def : slotsById.values()) {
                                if (def.isSkinLayer()) expanded.add(def.id());
                            }
                        }
                    }
                    case "all_body", "suit", "jumpsuit", "full_body" -> {
                        for (String id : List.of("undershirt", "pants", "belt_skin", "socks", "gloves", "boots_skin")) {
                            expanded.add(aliasToId != null ? aliasToId.getOrDefault(id, id) : id);
                        }
                    }
                    case "all_skin", "skin" -> {
                        if (skinLayersById != null) {
                            for (SkinLayerDef def : skinLayersById.values()) {
                                if (!def.replacesBaseSkin()) expanded.add(def.id());
                            }
                        }
                    }
                    case "all_3d", "accessories", "3d" -> {
                        if (slotsById != null) {
                            for (WardrobeSlotDef def : slotsById.values()) {
                                if (def.isDisplay3D()) expanded.add(def.id());
                            }
                        }
                    }
                    case "all_cosmetic", "cosmetic" -> {
                        if (slotsById != null) {
                            for (WardrobeSlotDef def : slotsById.values()) {
                                if (def.isCosmeticArmor()) expanded.add(def.id());
                            }
                        }
                    }
                    case "all", "*" -> {
                        if (slotsById != null) {
                            expanded.addAll(slotsById.keySet());
                        }
                        if (skinLayersById != null) {
                            for (SkinLayerDef def : skinLayersById.values()) {
                                if (!def.replacesBaseSkin()) expanded.add(def.id());
                            }
                        }
                    }
                    default -> {
                        String canonical = (aliasToId != null) ? aliasToId.getOrDefault(token, token) : token;
                        expanded.add(canonical);
                    }
                }
            }
        }

        if (!sourceCanonical.isEmpty()) {
            expanded.remove(sourceCanonical);
        }
        return expanded.isEmpty() ? Set.of() : Collections.unmodifiableSet(expanded);
    }

    public List<WardrobeSlotDef> getDisplay3DSlots() {
        return slotsById.values().stream()
                .filter(WardrobeSlotDef::isDisplay3D)
                .toList();
    }

    public ItemWardrobeMeta getConfiguredItem(String mythicId) {
        return mythicId == null ? null : configuredItems.get(mythicId.toLowerCase(Locale.ROOT));
    }

    public List<ComboOverrideRule> getComboRules() {
        return Collections.unmodifiableList(comboRules);
    }

    public MenuConfigLoader.ParsedMenusConfig getMenusConfig() {
        return menusConfig;
    }

    public MenuMode getDefaultMenuMode() {
        return menusConfig != null ? menusConfig.defaultMode() : MenuMode.COMPACT;
    }

    public boolean isAllowPlayerMenuModeSwitch() {
        return menusConfig == null || menusConfig.allowPlayerSwitch();
    }
}
