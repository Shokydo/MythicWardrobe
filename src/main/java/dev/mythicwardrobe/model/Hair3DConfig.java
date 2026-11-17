package dev.mythicwardrobe.model;

import org.bukkit.Color;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.*;

/**
 * Configuration model for MythicWardrobe v1.9.0 3D Hair Binding & Helmet-Aware Partial/Full Hiding.
 * Loaded from plugins/MythicWardrobe/hair-3d.yml.
 *
 * Supports:
 *  - Auto-equipping a 3D hairstyle whenever a matching 2D hair texture (`overlays/hairs/*.png`) is active
 *  - Helmet hiding modes:
 *      KEEP          — keep full 3D hair even under a helmet
 *      HIDE_ALL      — completely hide 3D hair when a matching helmet/hat is worn
 *      PARTIAL_MODEL — swap to a helmet-safe partial 3D model (e.g. hides high ponytail, keeps front bangs/strands!)
 *  - Helmet matching lists:
 *      ALL       — any helmet/hat hides/trims hair unless listed in `exceptions`
 *      WHITELIST — ONLY helmets/hats listed in `helmets` hide/trim hair
 *      BLACKLIST — all helmets/hats EXCEPT those listed in `helmets` / `exceptions`
 */
public final class Hair3DConfig {

    public enum HelmetBehavior {
        KEEP,
        HIDE_ALL,
        PARTIAL_MODEL;

        public static HelmetBehavior parse(String raw, HelmetBehavior fallback) {
            if (raw == null || raw.isBlank()) return fallback;
            String norm = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            return switch (norm) {
                case "KEEP", "NONE", "ALWAYS_SHOW" -> KEEP;
                case "HIDE", "HIDE_ALL", "DISABLE", "REMOVE" -> HIDE_ALL;
                case "PARTIAL", "PARTIAL_MODEL", "SWAP", "SWAP_MODEL", "REPLACE", "REPLACE_MODEL", "CHANGE_MODEL", "TRIM", "UNDER_HELMET" -> PARTIAL_MODEL;
                default -> fallback;
            };
        }
    }

    public enum HelmetListMode {
        ALL,
        WHITELIST,
        BLACKLIST;

        public static HelmetListMode parse(String raw, HelmetListMode fallback) {
            if (raw == null || raw.isBlank()) return fallback;
            String norm = raw.trim().toUpperCase(Locale.ROOT);
            return switch (norm) {
                case "ALL", "ANY" -> ALL;
                case "WHITELIST", "ONLY", "INCLUDE" -> WHITELIST;
                case "BLACKLIST", "EXCEPT", "EXCLUDE" -> BLACKLIST;
                default -> fallback;
            };
        }
    }

    /**
     * Lightweight descriptor of a currently equipped head covering (real helmet, cosmetic helmet, or 2D hat).
     */
    public record HelmetDescriptor(String mythicId, String materialName, String hatTexture) {
        public static HelmetDescriptor armor(String mythicId, String materialName) {
            return new HelmetDescriptor(mythicId, materialName, null);
        }

        public static HelmetDescriptor hat2d(String hatTexture) {
            return new HelmetDescriptor(null, null, hatTexture);
        }
    }

    public record Hair3DRule(
            String key,
            String mythicItem,
            String colorSpec,
            HelmetBehavior helmetMode,
            String helmetPartialItem,
            HelmetListMode helmetListMode,
            List<String> helmets,
            List<String> exceptions,
            boolean triggerOn2DHat
    ) {
        public boolean hasPartialModel() {
            return helmetMode == HelmetBehavior.PARTIAL_MODEL
                    && helmetPartialItem != null && !helmetPartialItem.isBlank();
        }
    }

    private final boolean enabled;
    private final boolean manualSlotOverridesBinding;
    private final boolean triggerOn2DHatDefault;
    private final HelmetListMode defaultHelmetListMode;
    private final List<String> defaultHelmets;
    private final List<String> defaultExceptions;
    private final Map<String, Hair3DRule> bindingsByNormalizedHair; // normalized 2D hair name -> rule
    private final Map<String, Hair3DRule> rulesByManualItemId;      // lowercase MythicItem ID -> rule

    public Hair3DConfig(
            boolean enabled,
            boolean manualSlotOverridesBinding,
            boolean triggerOn2DHatDefault,
            HelmetListMode defaultHelmetListMode,
            List<String> defaultHelmets,
            List<String> defaultExceptions,
            Map<String, Hair3DRule> bindingsByNormalizedHair,
            Map<String, Hair3DRule> rulesByManualItemId
    ) {
        this.enabled = enabled;
        this.manualSlotOverridesBinding = manualSlotOverridesBinding;
        this.triggerOn2DHatDefault = triggerOn2DHatDefault;
        this.defaultHelmetListMode = defaultHelmetListMode;
        this.defaultHelmets = List.copyOf(defaultHelmets);
        this.defaultExceptions = List.copyOf(defaultExceptions);
        this.bindingsByNormalizedHair = Collections.unmodifiableMap(new LinkedHashMap<>(bindingsByNormalizedHair));
        this.rulesByManualItemId = Collections.unmodifiableMap(new LinkedHashMap<>(rulesByManualItemId));
    }

    public static Hair3DConfig empty() {
        return new Hair3DConfig(true, true, true, HelmetListMode.ALL, List.of(), List.of(), Map.of(), Map.of());
    }

    public static Hair3DConfig load(YamlConfiguration yaml) {
        if (yaml == null) return empty();
        boolean enabled = yaml.getBoolean("enabled", true);
        boolean manualOverrides = yaml.getBoolean("manual-slot-overrides-binding", true);
        boolean trigger2dHat = yaml.getBoolean("trigger-on-2d-hat", true);
        HelmetListMode defListMode = HelmetListMode.parse(yaml.getString("default-helmet-list-mode", "ALL"), HelmetListMode.ALL);
        List<String> defHelmets = cleanList(yaml.getStringList("default-helmets"));
        List<String> defExceptions = cleanList(yaml.getStringList("default-exceptions"));

        Map<String, Hair3DRule> bindings = new LinkedHashMap<>();
        ConfigurationSection bindRoot = yaml.getConfigurationSection("bindings");
        if (bindRoot != null) {
            collectRuleSections(bindRoot, "", bindings, defListMode, defHelmets, defExceptions, trigger2dHat, true);
        }

        Map<String, Hair3DRule> items = new LinkedHashMap<>();
        ConfigurationSection itemsRoot = yaml.getConfigurationSection("items");
        if (itemsRoot != null) {
            collectRuleSections(itemsRoot, "", items, defListMode, defHelmets, defExceptions, trigger2dHat, false);
        }

        return new Hair3DConfig(
                enabled, manualOverrides, trigger2dHat,
                defListMode, defHelmets, defExceptions,
                bindings, items
        );
    }

    /**
     * Handles both `hair_blonde:` AND `hair_blonde.png:` keys cleanly even though Bukkit's default
     * YamlConfiguration path separator is '.' (which nests `hair_blonde.png:` as `hair_blonde -> png`).
     */
    private static void collectRuleSections(
            ConfigurationSection parent,
            String prefix,
            Map<String, Hair3DRule> out,
            HelmetListMode defListMode,
            List<String> defHelmets,
            List<String> defExceptions,
            boolean defTrigger2dHat,
            boolean isBinding
    ) {
        for (String rawKey : parent.getKeys(false)) {
            ConfigurationSection sec = parent.getConfigurationSection(rawKey);
            if (sec == null) continue;
            String fullKey = prefix.isEmpty() ? rawKey : (prefix + "." + rawKey);
            if (looksLikeRuleSection(sec)) {
                String mapKey = isBinding ? normalizeHairKey(fullKey) : fullKey.trim().toLowerCase(Locale.ROOT);
                Hair3DRule rule = parseRule(isBinding ? mapKey : fullKey.trim(), sec, defListMode, defHelmets, defExceptions, defTrigger2dHat);
                if (!isBinding || (rule.mythicItem() != null && !rule.mythicItem().isBlank())) {
                    out.put(mapKey, rule);
                }
            } else {
                collectRuleSections(sec, fullKey, out, defListMode, defHelmets, defExceptions, defTrigger2dHat, isBinding);
            }
        }
    }

    private static boolean looksLikeRuleSection(ConfigurationSection sec) {
        return sec.contains("mythic-item")
                || sec.contains("helmet-mode")
                || sec.contains("helmet-partial-item")
                || sec.contains("helmet-model")
                || sec.contains("helmet-swap-item")
                || sec.contains("swap-model")
                || sec.contains("helmet-list-mode")
                || sec.contains("helmets")
                || sec.contains("hide-on-helmets")
                || sec.contains("exceptions")
                || sec.contains("keep-on-helmets")
                || sec.contains("color");
    }

    private static Hair3DRule parseRule(
            String key,
            ConfigurationSection sec,
            HelmetListMode defListMode,
            List<String> defHelmets,
            List<String> defExceptions,
            boolean defTrigger2dHat
    ) {
        String mythicItem = sec.getString("mythic-item", key);
        if (mythicItem != null) mythicItem = mythicItem.trim();
        String colorSpec = sec.getString("color", "");
        if (colorSpec != null) colorSpec = colorSpec.trim();
        String partial = sec.getString("helmet-partial-item", "");
        if (partial == null || partial.isBlank()) partial = sec.getString("helmet-model", "");
        if (partial == null || partial.isBlank()) partial = sec.getString("helmet-swap-item", "");
        if (partial == null || partial.isBlank()) partial = sec.getString("swap-model", "");
        if (partial != null) partial = partial.trim();

        HelmetBehavior defaultBehavior = (partial != null && !partial.isBlank())
                ? HelmetBehavior.PARTIAL_MODEL
                : HelmetBehavior.HIDE_ALL;
        HelmetBehavior behavior = HelmetBehavior.parse(sec.getString("helmet-mode"), defaultBehavior);

        HelmetListMode listMode = sec.contains("helmet-list-mode")
                ? HelmetListMode.parse(sec.getString("helmet-list-mode"), defListMode)
                : defListMode;

        List<String> helmets = sec.contains("helmets")
                ? cleanList(sec.getStringList("helmets"))
                : (sec.contains("hide-on-helmets") ? cleanList(sec.getStringList("hide-on-helmets")) : defHelmets);
        List<String> exceptions = sec.contains("exceptions")
                ? cleanList(sec.getStringList("exceptions"))
                : (sec.contains("keep-on-helmets") ? cleanList(sec.getStringList("keep-on-helmets")) : defExceptions);
        boolean trigger2dHat = sec.getBoolean("trigger-on-2d-hat", defTrigger2dHat);

        return new Hair3DRule(
                key, mythicItem, colorSpec, behavior, partial,
                listMode, Collections.unmodifiableList(helmets), Collections.unmodifiableList(exceptions),
                trigger2dHat
        );
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isManualSlotOverridesBinding() {
        return manualSlotOverridesBinding;
    }

    public boolean isTriggerOn2DHatDefault() {
        return triggerOn2DHatDefault;
    }

    public Map<String, Hair3DRule> getBindings() {
        return bindingsByNormalizedHair;
    }

    public Map<String, Hair3DRule> getManualItemRules() {
        return rulesByManualItemId;
    }

    /**
     * Resolves an auto-bound 3D hairstyle rule for an active 2D hair texture (e.g. "hair_brown.png" or "hairs/hair_brown.png").
     */
    public Optional<Hair3DRule> findBindingFor2DHair(String hairTexture) {
        if (!enabled || hairTexture == null || hairTexture.isBlank()) return Optional.empty();
        String norm = normalizeHairKey(hairTexture);
        Hair3DRule direct = bindingsByNormalizedHair.get(norm);
        if (direct != null) return Optional.of(direct);

        int slash = norm.lastIndexOf('/');
        if (slash >= 0) {
            Hair3DRule byBase = bindingsByNormalizedHair.get(norm.substring(slash + 1));
            if (byBase != null) return Optional.of(byBase);
        }
        Hair3DRule wildcard = bindingsByNormalizedHair.get("*");
        return Optional.ofNullable(wildcard);
    }

    /**
     * Resolves helmet-hiding rules for a manually equipped 3D hair MythicItem ID.
     * Falls back to any binding pointing to the same MythicItem ID.
     */
    public Optional<Hair3DRule> findRuleForManualItem(String mythicItemId) {
        if (!enabled || mythicItemId == null || mythicItemId.isBlank()) return Optional.empty();
        String key = mythicItemId.trim().toLowerCase(Locale.ROOT);
        Hair3DRule direct = rulesByManualItemId.get(key);
        if (direct != null) return Optional.of(direct);
        for (Hair3DRule b : bindingsByNormalizedHair.values()) {
            if (b.mythicItem() != null && b.mythicItem().equalsIgnoreCase(mythicItemId.trim())) {
                return Optional.of(b);
            }
        }
        return Optional.empty();
    }

    /**
     * Determines whether any of the currently active head coverings triggers this rule's helmet behavior.
     */
    public boolean isAffectedByHelmets(Hair3DRule rule, Collection<HelmetDescriptor> activeHelmets) {
        if (rule == null || rule.helmetMode() == HelmetBehavior.KEEP || activeHelmets == null || activeHelmets.isEmpty()) {
            return false;
        }
        for (HelmetDescriptor h : activeHelmets) {
            if (h == null) continue;
            if (h.hatTexture() != null && !rule.triggerOn2DHat()) continue;
            if (matchesRule(rule, h)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesRule(Hair3DRule rule, HelmetDescriptor h) {
        if (matchesAnyPattern(rule.exceptions(), h)) {
            return false;
        }
        return switch (rule.helmetListMode()) {
            case ALL -> true;
            case WHITELIST -> matchesAnyPattern(rule.helmets(), h);
            case BLACKLIST -> !matchesAnyPattern(rule.helmets(), h);
        };
    }

    public static boolean matchesAnyPattern(List<String> patterns, HelmetDescriptor h) {
        if (patterns == null || patterns.isEmpty() || h == null) return false;
        for (String pat : patterns) {
            if (matchesSinglePattern(pat, h)) return true;
        }
        return false;
    }

    private static boolean matchesSinglePattern(String rawPattern, HelmetDescriptor h) {
        if (rawPattern == null || rawPattern.isBlank()) return false;
        String pat = rawPattern.trim().toLowerCase(Locale.ROOT);
        if (pat.equals("*") || pat.equals("all")) return true;

        if (pat.startsWith("hat:")) {
            if (h.hatTexture() == null) return false;
            String want = normalizeHairKey(pat.substring(4));
            String actual = normalizeHairKey(h.hatTexture());
            return wildcardMatch(want, actual);
        }

        if (h.mythicId() != null && wildcardMatch(pat, h.mythicId().toLowerCase(Locale.ROOT))) {
            return true;
        }
        if (h.materialName() != null && wildcardMatch(pat, h.materialName().toLowerCase(Locale.ROOT))) {
            return true;
        }
        if (h.hatTexture() != null && wildcardMatch(normalizeHairKey(pat), normalizeHairKey(h.hatTexture()))) {
            return true;
        }
        return false;
    }

    private static boolean wildcardMatch(String pattern, String text) {
        if (pattern.equals("*")) return true;
        if (!pattern.contains("*")) return pattern.equals(text);
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*') regex.append(".*");
            else if ("\\[]{}()+?.^$|".indexOf(c) >= 0) regex.append('\\').append(c);
            else regex.append(c);
        }
        regex.append('$');
        return text.matches(regex.toString());
    }

    public static String normalizeHairKey(String raw) {
        if (raw == null) return "";
        String s = raw.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
        if (s.equals("*")) return "*";
        if (s.endsWith(".png")) s = s.substring(0, s.length() - 4);
        return s;
    }

    public static Color parseColorSpec(String colorSpec) {
        if (colorSpec == null || colorSpec.isBlank()) return null;
        String s = colorSpec.trim();
        try {
            if (s.startsWith("#")) {
                String hex = s.substring(1);
                if (hex.length() == 6) {
                    return Color.fromRGB(Integer.parseInt(hex, 16));
                }
            }
            String[] parts = s.split(",");
            if (parts.length == 3) {
                int r = Math.max(0, Math.min(255, Integer.parseInt(parts[0].trim())));
                int g = Math.max(0, Math.min(255, Integer.parseInt(parts[1].trim())));
                int b = Math.max(0, Math.min(255, Integer.parseInt(parts[2].trim())));
                return Color.fromRGB(r, g, b);
            }
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    private static List<String> cleanList(List<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String s : raw) {
            if (s != null && !s.trim().isEmpty()) out.add(s.trim());
        }
        return out;
    }
}
