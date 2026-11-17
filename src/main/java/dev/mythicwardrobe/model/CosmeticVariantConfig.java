package dev.mythicwardrobe.model;

import org.bukkit.Color;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.EquipmentSlot;

import java.util.*;

/**
 * Multimodal Cosmetic Skin System (MythicWardrobe v1.10.0).
 *
 * Allows a SINGLE cosmetic item (e.g. "WhiteVestPaint" — a white paint / camo item of ANY material
 * like WHITE_DYE, PAPER, or LEATHER_CHESTPLATE) to dynamically adapt its rendered 3D/armor model
 * depending on which real armor piece the player is currently wearing!
 *
 * Example:
 *  - Wearing LightMilitaryVest + WhiteVestPaint -> renders LightMilitaryVest_White
 *  - Wearing HeavyMilitaryVest + WhiteVestPaint -> renders HeavyMilitaryVest_White
 *  - Wearing no armor          + WhiteVestPaint -> KEEP_EMPTY (leaves slot empty; never puts dye on torso!)
 *
 * Loaded from plugins/MythicWardrobe/cosmetic-variants.yml and also supports inline
 * `Wardrobe.Variants:` directly on MythicMobs items (or `variants:` in items.yml).
 */
public final class CosmeticVariantConfig {

    public enum UnmatchedMode {
        /** Keep the real armor unchanged when no variant rule matches. */
        KEEP_REAL,
        /** Render fallbackMythicItem when real armor is worn but unmatched. */
        FALLBACK_MODEL,
        /** Render the cosmetic item itself (standard cosmetic armor behavior). */
        USE_SELF;

        public static UnmatchedMode parse(String raw, UnmatchedMode fallback) {
            if (raw == null || raw.isBlank()) return fallback;
            String norm = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            return switch (norm) {
                case "KEEP_REAL", "KEEP", "REAL", "IGNORE", "NONE", "PASS_THROUGH" -> KEEP_REAL;
                case "FALLBACK", "FALLBACK_MODEL", "DEFAULT", "DEFAULT_MODEL" -> FALLBACK_MODEL;
                case "USE_SELF", "SELF", "COSMETIC", "OVERRIDE" -> USE_SELF;
                default -> fallback;
            };
        }
    }

    public enum EmptyArmorMode {
        /** Do not render anything when the real armor slot is empty. */
        KEEP_EMPTY,
        /** Render emptyArmorMythicItem (or fallbackMythicItem) when the real armor slot is empty. */
        FALLBACK_MODEL,
        /** Render the cosmetic item itself when the real armor slot is empty. */
        USE_SELF;

        public static EmptyArmorMode parse(String raw, EmptyArmorMode fallback) {
            if (raw == null || raw.isBlank()) return fallback;
            String norm = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            return switch (norm) {
                case "KEEP_EMPTY", "KEEP_REAL", "EMPTY", "NONE", "HIDE", "IGNORE" -> KEEP_EMPTY;
                case "FALLBACK", "FALLBACK_MODEL", "DEFAULT", "DEFAULT_MODEL" -> FALLBACK_MODEL;
                case "USE_SELF", "SELF", "COSMETIC", "SHOW_SELF" -> USE_SELF;
                default -> fallback;
            };
        }
    }

    /**
     * Lightweight descriptor of the player's currently equipped real armor piece in the target slot.
     */
    public record ArmorDescriptor(
            String mythicId,
            String materialName,
            Integer customModelData,
            String itemModelKey,
            String equippableModelKey
    ) {
        public static final ArmorDescriptor EMPTY = new ArmorDescriptor(null, null, null, null, null);

        public static ArmorDescriptor of(String mythicId, String materialName) {
            return new ArmorDescriptor(mythicId, materialName, null, null, null);
        }

        public boolean isEmpty() {
            return (mythicId == null || mythicId.isBlank())
                    && (materialName == null || materialName.isBlank() || materialName.equalsIgnoreCase("AIR"));
        }
    }

    /**
     * One mapping branch inside a multimodal cosmetic skin: matches a set of real armor pieces
     * and defines the resulting model/color transformation.
     */
    public record VariantRule(
            String id,
            EquipmentSlot equipmentSlot, // nullable: null means applies to any equipment slot
            List<String> matchArmor,
            List<String> matchMaterials,
            Set<Integer> matchCustomModelData,
            List<String> matchModels,
            String resultMythicItem,
            String dyeColor,
            String equippableModel,
            String itemModel,
            Integer customModelData
    ) {
        public VariantRule {
            matchArmor = normalizePatterns(matchArmor);
            matchMaterials = normalizePatterns(matchMaterials);
            matchCustomModelData = matchCustomModelData == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(matchCustomModelData));
            matchModels = normalizePatterns(matchModels);
            resultMythicItem = resultMythicItem == null ? "" : resultMythicItem.trim();
            dyeColor = dyeColor == null ? "" : dyeColor.trim();
            equippableModel = equippableModel == null ? "" : equippableModel.trim();
            itemModel = itemModel == null ? "" : itemModel.trim();
        }

        public boolean hasResultMythicItem() {
            return !resultMythicItem.isBlank();
        }

        public boolean hasDyeColor() {
            return !dyeColor.isBlank();
        }

        public boolean hasEquippableModel() {
            return !equippableModel.isBlank();
        }

        public boolean hasItemModel() {
            return !itemModel.isBlank();
        }

        public boolean hasCustomModelData() {
            return customModelData != null;
        }

        public boolean hasAnyVisualEffect() {
            return hasResultMythicItem() || hasDyeColor() || hasEquippableModel() || hasItemModel() || hasCustomModelData();
        }

        public boolean matches(EquipmentSlot slot, ArmorDescriptor realArmor) {
            if (realArmor == null || realArmor.isEmpty()) return false;
            if (equipmentSlot != null && slot != null && equipmentSlot != slot) return false;

            boolean hasCriteria = !matchArmor.isEmpty() || !matchMaterials.isEmpty()
                    || !matchCustomModelData.isEmpty() || !matchModels.isEmpty();
            if (!hasCriteria) return false;

            // Match if ANY configured criterion group matches (so a rule can list both Mythic IDs and vanilla materials)
            if (!matchArmor.isEmpty() && realArmor.mythicId() != null && !realArmor.mythicId().isBlank()) {
                for (String pattern : matchArmor) {
                    if (wildcardMatch(pattern, realArmor.mythicId())) return true;
                }
            }
            if (!matchMaterials.isEmpty() && realArmor.materialName() != null && !realArmor.materialName().isBlank()) {
                for (String pattern : matchMaterials) {
                    if (wildcardMatch(pattern, realArmor.materialName())) return true;
                }
            }
            if (!matchCustomModelData.isEmpty() && realArmor.customModelData() != null) {
                if (matchCustomModelData.contains(realArmor.customModelData())) return true;
            }
            if (!matchModels.isEmpty()) {
                for (String pattern : matchModels) {
                    if (realArmor.equippableModelKey() != null && wildcardMatch(pattern, realArmor.equippableModelKey())) {
                        return true;
                    }
                    if (realArmor.itemModelKey() != null && wildcardMatch(pattern, realArmor.itemModelKey())) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /**
     * Full definition of a multimodal cosmetic item (1 item -> N armor-specific variants).
     */
    public record CosmeticSkinDef(
            String id,
            List<String> cosmeticItems,
            Set<String> allowedSlots,
            UnmatchedMode unmatchedMode,
            EmptyArmorMode emptyArmorMode,
            String fallbackMythicItem,
            String emptyArmorMythicItem,
            List<VariantRule> variants
    ) {
        public CosmeticSkinDef {
            cosmeticItems = normalizePatterns(cosmeticItems);
            allowedSlots = normalizeSlotSet(allowedSlots);
            unmatchedMode = unmatchedMode != null ? unmatchedMode : UnmatchedMode.KEEP_REAL;
            emptyArmorMode = emptyArmorMode != null ? emptyArmorMode : EmptyArmorMode.KEEP_EMPTY;
            fallbackMythicItem = fallbackMythicItem == null ? "" : fallbackMythicItem.trim();
            emptyArmorMythicItem = emptyArmorMythicItem == null ? "" : emptyArmorMythicItem.trim();
            variants = variants == null ? List.of() : List.copyOf(variants);
        }

        public boolean matchesCosmeticItem(String cosmeticMythicId) {
            if (cosmeticMythicId == null || cosmeticMythicId.isBlank()) return false;
            for (String pattern : cosmeticItems) {
                if (wildcardMatch(pattern, cosmeticMythicId)) return true;
            }
            return false;
        }

        public boolean allowsSlot(WardrobeSlotDef slot) {
            if (slot == null) return false;
            if (!allowedSlots.isEmpty()) {
                return allowedSlots.contains(slot.id().toLowerCase(Locale.ROOT));
            }
            // If allowed-slots was omitted, allow any ARMOR_COSMETIC slot covered by variants (or any ARMOR_COSMETIC if variants have null slot)
            if (slot.kind() != SlotKind.ARMOR_COSMETIC) return false;
            EquipmentSlot eq = slot.equipmentSlot() != null
                    ? slot.equipmentSlot()
                    : (slot.section() != null ? slot.section().getEquipmentSlot() : null);
            if (eq == null) return false;
            for (VariantRule rule : variants) {
                if (rule.equipmentSlot() == null || rule.equipmentSlot() == eq) {
                    return true;
                }
            }
            return false;
        }
    }

    public enum ResolutionAction {
        /** Apply the matched VariantRule (resultMythicItem and/or dye/model mutation on real armor). */
        APPLY_VARIANT,
        /** Render a specific fallback MythicItem ID. */
        APPLY_MYTHIC_ITEM,
        /** Leave the player's real armor (or empty slot) untouched — never equip the paint item itself. */
        KEEP_REAL,
        /** Fall back to standard cosmetic behavior (display the cosmetic item itself). */
        USE_SELF
    }

    public record ResolutionResult(
            ResolutionAction action,
            VariantRule matchedVariant,
            String mythicItemId
    ) {
        public static ResolutionResult variant(VariantRule rule) {
            return new ResolutionResult(ResolutionAction.APPLY_VARIANT, rule, rule.resultMythicItem());
        }

        public static ResolutionResult mythicItem(String mythicId) {
            return new ResolutionResult(ResolutionAction.APPLY_MYTHIC_ITEM, null, mythicId);
        }

        public static final ResolutionResult KEEP_REAL = new ResolutionResult(ResolutionAction.KEEP_REAL, null, "");
        public static final ResolutionResult USE_SELF = new ResolutionResult(ResolutionAction.USE_SELF, null, "");
    }

    private final boolean enabled;
    private final List<CosmeticSkinDef> skins;

    public CosmeticVariantConfig(boolean enabled, List<CosmeticSkinDef> skins) {
        this.enabled = enabled;
        this.skins = skins == null ? List.of() : List.copyOf(skins);
    }

    public static CosmeticVariantConfig empty() {
        return new CosmeticVariantConfig(true, List.of());
    }

    public boolean isEnabled() {
        return enabled;
    }

    public List<CosmeticSkinDef> getSkins() {
        return skins;
    }

    /**
     * Finds the configured multimodal cosmetic definition for a given cosmetic MythicItem ID,
     * falling back to an inline `Wardrobe.Variants:` definition if present.
     */
    public Optional<CosmeticSkinDef> findSkinDef(String cosmeticMythicId, CosmeticSkinDef inlineDef) {
        if (!enabled) return Optional.empty();
        if (cosmeticMythicId != null && !cosmeticMythicId.isBlank()) {
            for (CosmeticSkinDef def : skins) {
                if (def.matchesCosmeticItem(cosmeticMythicId)) {
                    return Optional.of(def);
                }
            }
        }
        if (inlineDef != null && !inlineDef.variants().isEmpty()) {
            return Optional.of(inlineDef);
        }
        return Optional.empty();
    }

    public boolean isMultimodalCosmetic(String cosmeticMythicId, CosmeticSkinDef inlineDef) {
        return findSkinDef(cosmeticMythicId, inlineDef).isPresent();
    }

    /**
     * Returns true if this cosmetic item is allowed to be placed into {@code slot} via multimodal skin rules.
     */
    public boolean canPlaceInSlot(String cosmeticMythicId, CosmeticSkinDef inlineDef, WardrobeSlotDef slot) {
        Optional<CosmeticSkinDef> opt = findSkinDef(cosmeticMythicId, inlineDef);
        return opt.map(def -> def.allowsSlot(slot)).orElse(false);
    }

    /**
     * Resolves what should be visually rendered in {@code eqSlot} when {@code cosmeticMythicId} is placed
     * in the cosmetic slot and {@code realArmor} is currently worn in the player's real armor slot.
     */
    public ResolutionResult resolve(
            String cosmeticMythicId,
            CosmeticSkinDef inlineDef,
            EquipmentSlot eqSlot,
            ArmorDescriptor realArmor
    ) {
        Optional<CosmeticSkinDef> opt = findSkinDef(cosmeticMythicId, inlineDef);
        if (opt.isEmpty()) {
            return ResolutionResult.USE_SELF;
        }
        CosmeticSkinDef def = opt.get();

        // 1. Real armor slot is EMPTY
        if (realArmor == null || realArmor.isEmpty()) {
            return switch (def.emptyArmorMode()) {
                case KEEP_EMPTY -> ResolutionResult.KEEP_REAL;
                case USE_SELF -> ResolutionResult.USE_SELF;
                case FALLBACK_MODEL -> {
                    String fb = !def.emptyArmorMythicItem().isBlank()
                            ? def.emptyArmorMythicItem()
                            : def.fallbackMythicItem();
                    yield !fb.isBlank() ? ResolutionResult.mythicItem(fb) : ResolutionResult.KEEP_REAL;
                }
            };
        }

        // 2. Real armor is equipped -> try variant rules in declaration order
        for (VariantRule rule : def.variants()) {
            if (rule.matches(eqSlot, realArmor) && rule.hasAnyVisualEffect()) {
                return ResolutionResult.variant(rule);
            }
        }

        // 3. No variant matched the worn real armor
        return switch (def.unmatchedMode()) {
            case KEEP_REAL -> ResolutionResult.KEEP_REAL;
            case USE_SELF -> ResolutionResult.USE_SELF;
            case FALLBACK_MODEL -> !def.fallbackMythicItem().isBlank()
                    ? ResolutionResult.mythicItem(def.fallbackMythicItem())
                    : ResolutionResult.KEEP_REAL;
        };
    }

    // ==================== YAML LOADER ====================

    public static CosmeticVariantConfig load(YamlConfiguration yaml) {
        if (yaml == null) return empty();
        boolean enabled = yaml.getBoolean("enabled", true);
        List<CosmeticSkinDef> loaded = new ArrayList<>();

        ConfigurationSection root = yaml.getConfigurationSection("skins");
        if (root == null) root = yaml.getConfigurationSection("cosmetic-skins");
        if (root == null) root = yaml.getConfigurationSection("variants");
        if (root == null) {
            return new CosmeticVariantConfig(enabled, List.of());
        }

        for (String skinKey : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(skinKey);
            if (sec == null) continue;
            CosmeticSkinDef def = parseSkinSection(skinKey, sec, null);
            if (def != null && !def.cosmeticItems().isEmpty()) {
                loaded.add(def);
            }
        }

        return new CosmeticVariantConfig(enabled, loaded);
    }

    /**
     * Parses a skin section either from cosmetic-variants.yml or inline inside items.yml.
     */
    public static CosmeticSkinDef parseSkinSection(String skinKey, ConfigurationSection sec, String defaultCosmeticItemId) {
        if (sec == null) return null;

        List<String> cosmeticItems = readStringOrList(sec, "cosmetic-items", "cosmetic-item", "item", "items", "mythic-item");
        if (cosmeticItems.isEmpty() && defaultCosmeticItemId != null && !defaultCosmeticItemId.isBlank()) {
            cosmeticItems = List.of(defaultCosmeticItemId.trim());
        }

        Set<String> allowedSlots = new LinkedHashSet<>(readStringOrList(sec, "allowed-slots", "slots", "slot"));
        UnmatchedMode unmatchedMode = UnmatchedMode.parse(
                sec.getString("unmatched-mode", sec.getString("fallback-mode", "KEEP_REAL")),
                UnmatchedMode.KEEP_REAL
        );
        EmptyArmorMode emptyArmorMode = EmptyArmorMode.parse(
                sec.getString("empty-armor-mode", sec.getString("no-armor-mode", "KEEP_EMPTY")),
                EmptyArmorMode.KEEP_EMPTY
        );
        String fallbackMythic = sec.getString("fallback-mythic-item", sec.getString("fallback-item", ""));
        String emptyMythic = sec.getString("empty-armor-mythic-item", sec.getString("empty-item", ""));

        List<VariantRule> rules = parseVariantsSection(sec.getConfigurationSection("variants"));
        if (rules.isEmpty() && sec.isList("variants")) {
            rules = parseVariantsMapList(sec.getMapList("variants"));
        }

        return new CosmeticSkinDef(
                skinKey,
                cosmeticItems,
                allowedSlots,
                unmatchedMode,
                emptyArmorMode,
                fallbackMythic,
                emptyMythic,
                rules
        );
    }

    /**
     * Parses a `variants:` ConfigurationSection supporting BOTH:
     *  - Shorthand map:
     *      LightMilitaryVest: LightMilitaryVest_White
     *      HeavyMilitaryVest: HeavyMilitaryVest_White
     *  - Full structured sub-sections:
     *      light_vest:
     *        equipment-slot: CHEST
     *        match-armor: [LightMilitaryVest]
     *        result-mythic-item: LightMilitaryVest_White
     *        dye-color: "#FFFFFF"
     */
    public static List<VariantRule> parseVariantsSection(ConfigurationSection variantsSec) {
        if (variantsSec == null) return List.of();
        List<VariantRule> out = new ArrayList<>();

        for (String key : variantsSec.getKeys(false)) {
            if (variantsSec.isConfigurationSection(key)) {
                ConfigurationSection sub = variantsSec.getConfigurationSection(key);
                if (sub == null) continue;
                EquipmentSlot eq = parseEquipmentSlot(sub.getString("equipment-slot", sub.getString("slot", "")));
                List<String> matchArmor = readStringOrList(sub, "match-armor", "match-mythic-item", "match-mythic-items", "armor", "real-armor", "when-wearing");
                List<String> matchMaterials = readStringOrList(sub, "match-materials", "match-material", "materials", "material");
                Set<Integer> matchCmds = readIntSet(sub, "match-custom-model-data", "match-cmd");
                List<String> matchModels = readStringOrList(sub, "match-models", "match-model", "match-equippable-model", "match-item-model");

                // If user used the armor Mythic ID as the section key and didn't repeat match-armor, use key!
                if (matchArmor.isEmpty() && matchMaterials.isEmpty() && matchCmds.isEmpty() && matchModels.isEmpty()) {
                    matchArmor = List.of(key);
                }

                String resultMythic = sub.getString("result-mythic-item",
                        sub.getString("result-item", sub.getString("mythic-item", sub.getString("model-item", ""))));
                String dyeColor = sub.getString("dye-color", sub.getString("color", ""));
                String eqModel = sub.getString("equippable-model", "");
                String itemModel = sub.getString("item-model", "");
                Integer cmd = sub.contains("custom-model-data") ? sub.getInt("custom-model-data") : null;

                VariantRule rule = new VariantRule(
                        key, eq, matchArmor, matchMaterials, matchCmds, matchModels,
                        resultMythic, dyeColor, eqModel, itemModel, cmd
                );
                if (rule.hasAnyVisualEffect()) {
                    out.add(rule);
                }
            } else {
                // Shorthand: "LightMilitaryVest: LightMilitaryVest_White" (or "MATERIAL:IRON_CHESTPLATE: #FFFFFF")
                String val = variantsSec.getString(key, "");
                if (val == null || val.isBlank()) continue;
                VariantRule rule = parseShorthandVariant(key, val);
                if (rule != null && rule.hasAnyVisualEffect()) {
                    out.add(rule);
                }
            }
        }
        return out;
    }

    public static VariantRule parseShorthandVariant(String rawKey, String rawValue) {
        if (rawKey == null || rawKey.isBlank() || rawValue == null || rawValue.isBlank()) return null;
        String k = rawKey.trim();
        String v = rawValue.trim();

        List<String> matchArmor = List.of();
        List<String> matchMaterials = List.of();
        Set<Integer> matchCmds = Set.of();
        List<String> matchModels = List.of();

        String lower = k.toLowerCase(Locale.ROOT);
        if (lower.startsWith("material:") || lower.startsWith("mat:")) {
            matchMaterials = splitCsv(k.substring(k.indexOf(':') + 1));
        } else if (lower.startsWith("cmd:")) {
            Set<Integer> parsed = new LinkedHashSet<>();
            for (String tok : splitCsv(k.substring(4))) {
                try { parsed.add(Integer.parseInt(tok)); } catch (NumberFormatException ignored) {}
            }
            matchCmds = parsed;
        } else if (lower.startsWith("model:")) {
            matchModels = splitCsv(k.substring(6));
        } else {
            matchArmor = splitCsv(k);
        }

        String resultMythic = "";
        String dyeColor = "";
        String eqModel = "";
        String itemModel = "";

        String vLower = v.toLowerCase(Locale.ROOT);
        if (v.startsWith("#") || vLower.startsWith("color:") || vLower.startsWith("dye:")) {
            dyeColor = v.startsWith("#") ? v : v.substring(v.indexOf(':') + 1).trim();
        } else if (vLower.startsWith("equippable:")) {
            eqModel = v.substring("equippable:".length()).trim();
        } else if (vLower.startsWith("item-model:")) {
            itemModel = v.substring("item-model:".length()).trim();
        } else {
            resultMythic = v;
        }

        return new VariantRule(k, null, matchArmor, matchMaterials, matchCmds, matchModels,
                resultMythic, dyeColor, eqModel, itemModel, null);
    }

    private static List<VariantRule> parseVariantsMapList(List<Map<?, ?>> rawList) {
        List<VariantRule> out = new ArrayList<>();
        int idx = 0;
        for (Map<?, ?> map : rawList) {
            idx++;
            EquipmentSlot eq = parseEquipmentSlot(strVal(map, "equipment-slot", "slot"));
            List<String> matchArmor = listVal(map, "match-armor", "match-mythic-item", "armor", "real-armor");
            List<String> matchMaterials = listVal(map, "match-materials", "match-material", "material");
            List<String> matchModels = listVal(map, "match-models", "match-model");
            Set<Integer> matchCmds = new LinkedHashSet<>();
            for (String s : listVal(map, "match-custom-model-data", "match-cmd")) {
                try { matchCmds.add(Integer.parseInt(s)); } catch (NumberFormatException ignored) {}
            }
            String resultMythic = strVal(map, "result-mythic-item", "result-item", "mythic-item");
            String dyeColor = strVal(map, "dye-color", "color");
            String eqModel = strVal(map, "equippable-model");
            String itemModel = strVal(map, "item-model");
            Integer cmd = null;
            String rawCmd = strVal(map, "custom-model-data");
            if (!rawCmd.isBlank()) {
                try { cmd = Integer.parseInt(rawCmd); } catch (NumberFormatException ignored) {}
            }
            VariantRule rule = new VariantRule("variant_" + idx, eq, matchArmor, matchMaterials, matchCmds, matchModels,
                    resultMythic, dyeColor, eqModel, itemModel, cmd);
            if (rule.hasAnyVisualEffect()) out.add(rule);
        }
        return out;
    }

    // ==================== HELPERS ====================

    public static Color parseColorSpec(String raw) {
        return Hair3DConfig.parseColorSpec(raw);
    }

    public static boolean wildcardMatch(String pattern, String value) {
        if (pattern == null || value == null) return false;
        String p = pattern.trim().toLowerCase(Locale.ROOT);
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (p.isEmpty() || v.isEmpty()) return false;
        if (p.equals("*")) return true;
        if (!p.contains("*")) return p.equals(v);

        String[] parts = p.split("\\*", -1);
        int pos = 0;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) continue;
            int found = v.indexOf(part, pos);
            if (found < 0) return false;
            if (i == 0 && !p.startsWith("*") && found != 0) return false;
            pos = found + part.length();
        }
        if (!p.endsWith("*") && parts.length > 0) {
            String last = parts[parts.length - 1];
            if (!last.isEmpty() && !v.endsWith(last)) return false;
        }
        return true;
    }

    private static EquipmentSlot parseEquipmentSlot(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "HEAD", "HELMET" -> EquipmentSlot.HEAD;
            case "CHEST", "CHESTPLATE", "BODY" -> EquipmentSlot.CHEST;
            case "LEGS", "LEGGINGS" -> EquipmentSlot.LEGS;
            case "FEET", "BOOTS" -> EquipmentSlot.FEET;
            default -> null;
        };
    }

    private static List<String> readStringOrList(ConfigurationSection sec, String... keys) {
        List<String> out = new ArrayList<>();
        for (String key : keys) {
            if (!sec.contains(key)) continue;
            if (sec.isList(key)) {
                for (String s : sec.getStringList(key)) {
                    out.addAll(splitCsv(s));
                }
            } else {
                String raw = sec.getString(key, "");
                out.addAll(splitCsv(raw));
            }
            if (!out.isEmpty()) return out;
        }
        return out;
    }

    private static Set<Integer> readIntSet(ConfigurationSection sec, String... keys) {
        Set<Integer> out = new LinkedHashSet<>();
        for (String key : keys) {
            if (!sec.contains(key)) continue;
            if (sec.isList(key)) {
                for (String s : sec.getStringList(key)) {
                    try { out.add(Integer.parseInt(s.trim())); } catch (NumberFormatException ignored) {}
                }
            } else {
                String raw = sec.getString(key, "");
                for (String s : splitCsv(raw)) {
                    try { out.add(Integer.parseInt(s)); } catch (NumberFormatException ignored) {}
                }
            }
            if (!out.isEmpty()) return out;
        }
        return out;
    }

    private static List<String> splitCsv(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String tok : raw.split("[,;]+")) {
            String clean = tok.trim();
            if (!clean.isEmpty()) out.add(clean);
        }
        return out;
    }

    private static List<String> normalizePatterns(List<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(raw.size());
        for (String s : raw) {
            if (s != null && !s.isBlank()) out.add(s.trim());
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    private static Set<String> normalizeSlotSet(Set<String> raw) {
        if (raw == null || raw.isEmpty()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (String s : raw) {
            if (s != null && !s.isBlank()) out.add(s.trim().toLowerCase(Locale.ROOT));
        }
        return out.isEmpty() ? Set.of() : Collections.unmodifiableSet(out);
    }

    private static String strVal(Map<?, ?> map, String... keys) {
        for (String k : keys) {
            Object v = map.get(k);
            if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v).trim();
        }
        return "";
    }

    private static List<String> listVal(Map<?, ?> map, String... keys) {
        for (String k : keys) {
            Object v = map.get(k);
            if (v == null) continue;
            if (v instanceof Collection<?> col) {
                List<String> out = new ArrayList<>();
                for (Object item : col) {
                    if (item != null) out.addAll(splitCsv(String.valueOf(item)));
                }
                if (!out.isEmpty()) return out;
            } else {
                List<String> out = splitCsv(String.valueOf(v));
                if (!out.isEmpty()) return out;
            }
        }
        return List.of();
    }
}
