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
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
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

    public static final NamespacedKey VISUAL_PROXY_KEY = new NamespacedKey("mythicwardrobe", "visual_proxy");

    public record PlayerVisualSnapshot(
            UUID playerId,
            int entityId,
            boolean wardrobeGuiOpen,
            Map<EquipmentSlot, ItemStack> overrides
    ) {
        public boolean hasOverrides() {
            return overrides != null && !overrides.isEmpty();
        }

        public boolean hasOverrideFor(EquipmentSlot slot) {
            return slot != null && overrides != null && overrides.containsKey(slot);
        }
    }

    /**
     * Checks whether a Bukkit ItemStack carries the visual_proxy PDC marker stamped onto
     * packet-level cosmetic/3D self-view items. Such items must never enter real inventories
     * or overwrite real armor in Creative mode.
     */
    public static boolean isVisualProxyItem(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return meta.getPersistentDataContainer().has(VISUAL_PROXY_KEY, PersistentDataType.BYTE);
    }

    private final JavaPlugin plugin;
    private final WardrobeConfig config;
    private final WardrobeItemResolver resolver;
    private final MythicMobsHook mythicHook;

    private final Map<UUID, PlayerWardrobeState> states;
    private final Map<UUID, Set<String>> comboHiddenSlots = new ConcurrentHashMap<>();
    private final Set<UUID> openWardrobeGuis = ConcurrentHashMap.newKeySet();
    private volatile Function<Player, Set<String>> blockedSlotsProvider = p -> Set.of();
    private volatile Function<Player, SkinModelVariant> bodyTypeProvider = p -> SkinModelVariant.CLASSIC;

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

    public void setBodyTypeProvider(Function<Player, SkinModelVariant> provider) {
        this.bodyTypeProvider = provider != null ? provider : (p -> SkinModelVariant.CLASSIC);
    }

    /**
     * Applies bodytype-overrides.yml (3px slim vs 4px wide) to an armor/cosmetic ItemStack.
     * Returns a modified clone if a rule matched and changed the visual model/item, or null if unchanged.
     */
    @SuppressWarnings("UnstableApiUsage")
    private ItemStack applyBodyTypeOverride(Player player, ItemStack item) {
        if (player == null || item == null || item.getType().isAir()) return null;
        List<BodyTypeOverrideRule> rules = config.getBodyTypeRules();
        if (rules.isEmpty()) return null;

        String mythicId = resolver.resolve(item).mythicId();
        Equippable eq = item.getData(DataComponentTypes.EQUIPPABLE);
        String eqModelKey = (eq != null && eq.assetId() != null) ? eq.assetId().asString() : null;

        SkinModelVariant variant = bodyTypeProvider.apply(player);
        boolean isSlim = variant == SkinModelVariant.SLIM;

        for (BodyTypeOverrideRule rule : rules) {
            if (!rule.matches(mythicId, eqModelKey)) continue;
            String target = rule.targetFor(isSlim);
            if (target == null) return null;

            if (BodyTypeOverrideRule.isModelKey(target)) {
                Key targetKey = Key.key(target);
                if (eqModelKey != null && eqModelKey.equalsIgnoreCase(targetKey.asString())) {
                    return null;
                }
                ItemStack clone = item.clone();
                Equippable baseEq = clone.getData(DataComponentTypes.EQUIPPABLE);
                EquipmentSlot slot = baseEq != null ? baseEq.slot() : resolver.detectEquipmentSlot(clone);
                if (slot == null) slot = EquipmentSlot.CHEST;
                Equippable.Builder builder = baseEq != null ? baseEq.toBuilder() : Equippable.equippable(slot);
                builder.assetId(targetKey);
                clone.setData(DataComponentTypes.EQUIPPABLE, builder.build());
                return clone;
            } else if (mythicHook != null) {
                ItemStack replacement = mythicHook.generateMythicItem(target);
                if (replacement != null && !replacement.getType().isAir()) {
                    return replacement;
                }
            }
            return null;
        }
        return null;
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

                // Resolve multimodal cosmetic skins (e.g. 1 paint/camo item adapting to Light vs Heavy armor!)
                ResolvedCosmetic resolvedCosmetic = resolveCosmeticVisual(cosmeticItem, realItem, eqSlot);
                ItemStack effectiveCosmetic = resolvedCosmetic.visualItem();

                ItemStack primaryVisual = (effectiveCosmetic != null && !effectiveCosmetic.getType().isAir())
                        ? effectiveCosmetic
                        : realItem;
                String primaryMythicId = null;
                if (effectiveCosmetic != null && !effectiveCosmetic.getType().isAir()) {
                    primaryMythicId = resolver.resolve(effectiveCosmetic).mythicId();
                    if ((primaryMythicId == null || primaryMythicId.isBlank()) && !resolvedCosmetic.resolvedMythicId().isBlank()) {
                        primaryMythicId = resolvedCosmetic.resolvedMythicId();
                    }
                } else if (realItem != null && !realItem.getType().isAir()) {
                    primaryMythicId = resolver.resolve(realItem).mythicId();
                }

                ItemStack resolvedOverride = null;

                // Identical native self-rendering in Survival AND Creative (Creative slots are protected
                // against deletion/overwrite by CreativeArmorGuard in PacketEventsArmorBridge + WardrobeListener).
                boolean canNativeSelfPromote = packetBridge != null
                        && config.isShowCosmeticToSelf();

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

                // 2. Fallback to resolved cosmetic armor item (null when multimodal cosmetic returned KEEP_REAL/KEEP_EMPTY)
                if (resolvedOverride == null && effectiveCosmetic != null && !effectiveCosmetic.getType().isAir()) {
                    resolvedOverride = effectiveCosmetic.clone();
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
                    ItemStack adjusted = applyBodyTypeOverride(player, resolvedOverride);
                    overrides.put(eqSlot, adjusted != null ? adjusted : resolvedOverride);
                } else if (primaryVisual != null && !primaryVisual.getType().isAir()) {
                    ItemStack adjustedReal = applyBodyTypeOverride(player, primaryVisual);
                    if (adjustedReal != null) {
                        overrides.put(eqSlot, adjustedReal);
                    }
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

        // 1. Cosmetic helmet (resolved through multimodal cosmetic variants so empty paint never hides 3D hair!)
        ItemStack real = player.getInventory().getHelmet();
        WardrobeSlotDef cosmeticHead = config.findSlotByKindAndEquipment(SlotKind.ARMOR_COSMETIC, EquipmentSlot.HEAD);
        if (cosmeticHead != null && (blockedSlots == null || !blockedSlots.contains(cosmeticHead.id()))) {
            ItemStack cos = state.peekSlotItem(cosmeticHead.id());
            if (cos != null && !cos.getType().isAir()) {
                ResolvedCosmetic res = resolveCosmeticVisual(cos, real, EquipmentSlot.HEAD);
                ItemStack eff = res.visualItem();
                if (eff != null && !eff.getType().isAir()) {
                    String effId = resolver.resolve(eff).mythicId();
                    if ((effId == null || effId.isBlank()) && !res.resolvedMythicId().isBlank()) {
                        effId = res.resolvedMythicId();
                    }
                    list.add(Hair3DConfig.HelmetDescriptor.armor(effId, eff.getType().name()));
                }
            }
        }

        // 2. Real helmet
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

    private record ResolvedCosmetic(ItemStack visualItem, String resolvedMythicId) {
        static final ResolvedCosmetic NONE = new ResolvedCosmetic(null, "");
    }

    /**
     * Resolves a cosmetic slot item against the player's currently worn real armor using
     * {@link CosmeticVariantConfig} (cosmetic-variants.yml + inline Wardrobe.Variants:).
     *
     * For regular cosmetic armor (no multimodal rule), returns the item itself unchanged.
     * For multimodal paint/camo items (e.g. WhiteVestPaint):
     *   - Adapts the rendered model to match the worn real armor (LightMilitaryVest -> LightMilitaryVest_White,
     *     HeavyMilitaryVest -> HeavyMilitaryVest_White, etc.).
     *   - Returns NONE when the real armor is missing/unmatched under KEEP_EMPTY / KEEP_REAL,
     *     so non-armor paint items (WHITE_DYE, PAPER) are never visually put on the player's body!
     */
    private ResolvedCosmetic resolveCosmeticVisual(ItemStack cosmeticItem, ItemStack realItem, EquipmentSlot eqSlot) {
        if (cosmeticItem == null || cosmeticItem.getType().isAir()) {
            return ResolvedCosmetic.NONE;
        }
        CosmeticVariantConfig variantCfg = config.getCosmeticVariantConfig();
        if (variantCfg == null || !variantCfg.isEnabled()) {
            return new ResolvedCosmetic(cosmeticItem, "");
        }

        ItemWardrobeMeta cosmeticMeta = resolver.resolve(cosmeticItem);
        CosmeticVariantConfig.ArmorDescriptor realDesc = describeRealArmor(realItem);
        CosmeticVariantConfig.ResolutionResult res = variantCfg.resolve(
                cosmeticMeta.mythicId(),
                cosmeticMeta.inlineCosmeticSkin(),
                eqSlot,
                realDesc
        );

        return switch (res.action()) {
            case USE_SELF -> new ResolvedCosmetic(cosmeticItem, cosmeticMeta.mythicId() == null ? "" : cosmeticMeta.mythicId());
            case KEEP_REAL -> ResolvedCosmetic.NONE;
            case APPLY_MYTHIC_ITEM -> {
                if (mythicHook == null || res.mythicItemId().isBlank()) yield ResolvedCosmetic.NONE;
                ItemStack generated = mythicHook.generateMythicItem(res.mythicItemId());
                if (generated == null || generated.getType().isAir()) yield ResolvedCosmetic.NONE;
                yield new ResolvedCosmetic(generated, res.mythicItemId());
            }
            case APPLY_VARIANT -> {
                CosmeticVariantConfig.VariantRule rule = res.matchedVariant();
                if (rule == null) yield ResolvedCosmetic.NONE;
                ItemStack visual = null;
                String resolvedId = "";
                if (rule.hasResultMythicItem() && mythicHook != null) {
                    visual = mythicHook.generateMythicItem(rule.resultMythicItem());
                    resolvedId = rule.resultMythicItem();
                } else if (realItem != null && !realItem.getType().isAir()) {
                    // Pure dye/model override directly on a copy of the worn real armor!
                    visual = realItem.clone();
                    resolvedId = realDesc.mythicId() != null ? realDesc.mythicId() : "";
                }
                if (visual == null || visual.getType().isAir()) {
                    yield ResolvedCosmetic.NONE;
                }
                applyVariantVisualMutations(visual, rule, eqSlot);
                yield new ResolvedCosmetic(visual, resolvedId);
            }
        };
    }

    private CosmeticVariantConfig.ArmorDescriptor describeRealArmor(ItemStack realItem) {
        if (realItem == null || realItem.getType().isAir()) {
            return CosmeticVariantConfig.ArmorDescriptor.EMPTY;
        }
        String mythicId = resolver.resolve(realItem).mythicId();
        String matName = realItem.getType().name();
        Integer cmd = null;
        try {
            ItemMeta meta = realItem.getItemMeta();
            if (meta != null && meta.hasCustomModelData()) {
                cmd = meta.getCustomModelData();
            }
        } catch (Throwable ignored) {
        }
        if (cmd == null) {
            try {
                CustomModelData data = realItem.getData(DataComponentTypes.CUSTOM_MODEL_DATA);
                if (data != null && !data.floats().isEmpty()) {
                    cmd = Math.round(data.floats().get(0));
                }
            } catch (Throwable ignored) {
            }
        }
        String itemModelKey = extractItemModelKey(realItem);
        String equippableModelKey = extractEquippableModelKey(realItem);
        return new CosmeticVariantConfig.ArmorDescriptor(mythicId, matName, cmd, itemModelKey, equippableModelKey);
    }

    private static String extractEquippableModelKey(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        try {
            Equippable eq = item.getData(DataComponentTypes.EQUIPPABLE);
            if (eq != null && eq.assetId() != null) {
                return eq.assetId().asString().toLowerCase(Locale.ROOT);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void applyVariantVisualMutations(ItemStack visual, CosmeticVariantConfig.VariantRule rule, EquipmentSlot eqSlot) {
        if (visual == null || rule == null) return;
        if (rule.hasDyeColor()) {
            Color c = CosmeticVariantConfig.parseColorSpec(rule.dyeColor());
            if (c != null) {
                applyOptionalHairColor(visual, c, null);
            }
        }
        if (rule.hasEquippableModel()) {
            try {
                NamespacedKey key = NamespacedKey.fromString(rule.equippableModel().toLowerCase(Locale.ROOT));
                if (key != null) {
                    EquipmentSlot targetSlot = eqSlot != null ? eqSlot : EquipmentSlot.CHEST;
                    Equippable.Builder b = Equippable.equippable(targetSlot).assetId(key);
                    visual.setData(DataComponentTypes.EQUIPPABLE, b.build());
                }
            } catch (Throwable ignored) {
            }
        }
        if (rule.hasItemModel()) {
            try {
                NamespacedKey key = NamespacedKey.fromString(rule.itemModel().toLowerCase(Locale.ROOT));
                if (key != null) {
                    visual.setData(DataComponentTypes.ITEM_MODEL, key);
                }
            } catch (Throwable ignored) {
            }
        }
        if (rule.hasCustomModelData()) {
            try {
                int val = rule.customModelData();
                visual.setData(DataComponentTypes.CUSTOM_MODEL_DATA,
                        CustomModelData.customModelData().addFloat((float) val).build());
            } catch (Throwable ignored) {
            }
        }
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

    public void syncSelfArmor(Player player) {
        if (player == null || !player.isOnline()) return;
        if (packetBridge != null) {
            packetBridge.syncSelfArmor(player);
        }
    }

    public void clearClientCursor(Player player) {
        if (player == null || !player.isOnline()) return;
        if (packetBridge != null) {
            packetBridge.clearClientCursor(player);
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
