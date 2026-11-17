package dev.mythicwardrobe.command;

import dev.mythicwardrobe.MythicWardrobePlugin;
import dev.mythicwardrobe.config.TextUtil;
import dev.mythicwardrobe.config.WardrobeConfig;
import dev.mythicwardrobe.gui.WardrobeGuiManager;
import dev.mythicwardrobe.manager.WardrobeItemResolver;
import dev.mythicwardrobe.manager.WardrobeManager;
import dev.mythicwardrobe.model.*;
import dev.mythicwardrobe.skin.SkinLayerRegistry;
import dev.mythicwardrobe.visual.BBModelMerger;
import dev.mythicwardrobe.visual.HeadCompositePackBuilder;
import dev.mythicwardrobe.web.WebEditorServer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class WardrobeCommand implements CommandExecutor, TabCompleter {

    private final MythicWardrobePlugin plugin;
    private final WardrobeConfig config;
    private final WardrobeManager manager;
    private final WardrobeGuiManager guiManager;
    private final SkinLayerRegistry layerRegistry;
    private final WardrobeItemResolver resolver;

    public WardrobeCommand(
            MythicWardrobePlugin plugin,
            WardrobeConfig config,
            WardrobeManager manager,
            WardrobeGuiManager guiManager,
            SkinLayerRegistry layerRegistry,
            WardrobeItemResolver resolver
    ) {
        this.plugin = plugin;
        this.config = config;
        this.manager = manager;
        this.guiManager = guiManager;
        this.layerRegistry = layerRegistry;
        this.resolver = resolver;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(TextUtil.parse(config.getPrefixedMessage("player-only", "Только для игроков.")));
                return true;
            }
            if (!player.hasPermission("mythicwardrobe.use")) {
                player.sendMessage(TextUtil.parse(config.getPrefixedMessage("no-permission", "Нет прав.")));
                return true;
            }
            guiManager.openDefault(player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "open" -> handleOpen(sender, args);
            case "mode", "menu", "style", "gui" -> handleMenuMode(sender, args);
            case "set", "wear" -> handleSet(sender, args);
            case "clear", "unwear", "remove" -> handleClear(sender, args);
            case "reset" -> handleReset(sender, args);
            case "bodytype", "body_type", "arms" -> handleBodyType(sender, args);
            case "web", "editor" -> handleWebEditor(sender, args);
            case "prewarm" -> handlePrewarm(sender);
            case "layers" -> handleLayers(sender);
            case "tag" -> handleTag(sender, args);
            case "block", "blocks", "lock" -> handleBlock(sender, args);
            case "merge", "combine" -> handleMerge(sender, args);
            case "buildheadpack", "headpack" -> handleBuildHeadPack(sender, args);
            case "list" -> handleList(sender);
            case "reload" -> handleReload(sender);
            case "debug" -> handleDebug(sender, args);
            default -> sendUsage(sender);
        }
        return true;
    }

    private void handleOpen(CommandSender sender, String[] args) {
        String targetView = args.length >= 2 ? args[1].trim().toLowerCase(Locale.ROOT) : "";
        ArmorSection section = args.length >= 2 ? ArmorSection.fromName(args[1]) : null;
        Player target;
        if (args.length >= 3) {
            if (!sender.hasPermission("mythicwardrobe.admin")) {
                sender.sendMessage(TextUtil.parse(config.getPrefixedMessage("no-permission", "Нет прав.")));
                return;
            }
            target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                sender.sendMessage(TextUtil.parse(config.getPrefixedMessage("player-not-found", "Игрок <player> не найден."), Map.of("player", args[2])));
                return;
            }
        } else {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(TextUtil.parse(config.getPrefixedMessage("player-only", "Только для игроков.")));
                return;
            }
            if (!p.hasPermission("mythicwardrobe.use")) {
                p.sendMessage(TextUtil.parse(config.getPrefixedMessage("no-permission", "Нет прав.")));
                return;
            }
            target = p;
        }

        if (targetView.equals("unified") || targetView.equals("all")) {
            guiManager.openUnified(target);
        } else if (targetView.equals("hub")) {
            guiManager.openCompactHub(target);
        } else if (section == null) {
            guiManager.openDefault(target);
        } else {
            guiManager.openSection(target, section);
        }
    }

    /**
     * Supports:
     *   /wardrobe mode <compact|tabbed|unified|default>            (for self, if allow-player-switch=true)
     *   /wardrobe mode <player> <compact|tabbed|unified|default>   (admin)
     */
    private void handleMenuMode(CommandSender sender, String[] args) {
        if (args.length < 2) {
            if (sender instanceof Player p) {
                MenuMode cur = manager.getEffectiveMenuMode(p);
                sender.sendMessage(TextUtil.parse(config.getPrefix() + "<gray>Текущий режим меню: <white>" + cur.getRussianLabel()
                        + " <dark_gray>(" + cur.name().toLowerCase(Locale.ROOT) + ")"));
            }
            sender.sendMessage(TextUtil.parse(config.getPrefix() + "<yellow>/wardrobe mode [игрок] <compact|tabbed|unified|default>"));
            return;
        }

        Player target;
        String modeArg;
        if (args.length == 2) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(TextUtil.parse(config.getPrefix() + "<yellow>/wardrobe mode <игрок> <compact|tabbed|unified|default>"));
                return;
            }
            if (!p.hasPermission("mythicwardrobe.use")) {
                p.sendMessage(TextUtil.parse(config.getPrefixedMessage("no-permission", "Нет прав.")));
                return;
            }
            if (!config.isAllowPlayerMenuModeSwitch() && !p.hasPermission("mythicwardrobe.admin")) {
                p.sendMessage(TextUtil.parse(config.getPrefixedMessage("mode-disabled",
                        "<red>Переключение режима меню игроками отключено в menus.yml.")));
                return;
            }
            target = p;
            modeArg = args[1];
        } else {
            if (!sender.hasPermission("mythicwardrobe.admin")) {
                sender.sendMessage(TextUtil.parse(config.getPrefixedMessage("no-permission", "Нет прав.")));
                return;
            }
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(TextUtil.parse(config.getPrefixedMessage("player-not-found", "Игрок <player> не найден."), Map.of("player", args[1])));
                return;
            }
            modeArg = args[2];
        }

        MenuMode newMode;
        String displayLabel;
        if (modeArg.equalsIgnoreCase("default") || modeArg.equalsIgnoreCase("reset")) {
            newMode = null;
            displayLabel = "по умолчанию (" + config.getDefaultMenuMode().getRussianLabel() + ")";
        } else {
            newMode = MenuMode.parseOrNull(modeArg);
            if (newMode == null) {
                sender.sendMessage(TextUtil.parse(config.getPrefixedMessage("mode-invalid",
                        "<red>Неверный режим! Используйте: <white>compact</white>, <white>tabbed</white>, <white>unified</white> или <white>default</white>.")));
                return;
            }
            displayLabel = newMode.getRussianLabel();
        }

        manager.setPlayerMenuMode(target, newMode);
        sender.sendMessage(TextUtil.parse(
                config.getPrefixedMessage("mode-switched", "<green>Режим меню переключён на: <white><mode>"),
                Map.of("mode", displayLabel, "player", target.getName())
        ));
    }

    private void handleSet(CommandSender sender, String[] args) {
        if (!sender.hasPermission("mythicwardrobe.admin")) {
            sender.sendMessage(TextUtil.parse(config.getPrefixedMessage("no-permission", "Нет прав.")));
            return;
        }
        if (args.length < 4) {
            sender.sendMessage(TextUtil.parse(config.getPrefix() + "<yellow>/wardrobe set <игрок> <тип_слоя> <файл.png> [blocks_csv]"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(TextUtil.parse(config.getPrefixedMessage("player-not-found", "Игрок <player> не найден."), Map.of("player", args[1])));
            return;
        }
        String rawType = args[2];
        String canonicalType = config.resolveLayerId(rawType);
        String texture = args[3];
        Set<String> blockedTargets = args.length >= 5 ? ItemWardrobeMeta.parseBlockedTargetsCsv(args[4]) : Set.of();

        SkinLayerDef layerDef = config.getSkinLayerDef(canonicalType);
        String folderHint = layerDef != null ? layerDef.folder() : canonicalType;
        SkinModelVariant targetVariant = manager.getEffectiveBodyType(target);
        if (layerRegistry.resolve(folderHint, texture, targetVariant).isEmpty()) {
            sender.sendMessage(TextUtil.parse(
                    config.getPrefixedMessage("texture-not-found", "Текстура <texture> не найдена."),
                    Map.of("texture", texture)
            ));
            return;
        }

        boolean applied = manager.setSkillLayer(target, canonicalType, texture, blockedTargets, false, true);
        if (!applied) {
            var blocker = manager.getBlockerFor(target, canonicalType);
            String blockerName = blocker.map(WardrobeManager.SlotBlockInfo::blockerName).orElse("костюмом");
            sender.sendMessage(TextUtil.parse(
                    config.getPrefixedMessage("layer-blocked-by", "<red>Слой <white><slot></white> <red>сейчас заблокирован предметом <yellow><blocker></yellow><red>!"),
                    Map.of("slot", canonicalType, "blocker", blockerName)
            ));
            return;
        }
        sender.sendMessage(TextUtil.parse(
                config.getPrefixedMessage("layer-set", "Слой <slot> установлен: <texture>"),
                Map.of("slot", canonicalType, "texture", texture)
        ));
    }

    private void handleClear(CommandSender sender, String[] args) {
        if (!sender.hasPermission("mythicwardrobe.admin")) {
            sender.sendMessage(TextUtil.parse(config.getPrefixedMessage("no-permission", "Нет прав.")));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(TextUtil.parse(config.getPrefix() + "<yellow>/wardrobe clear <игрок> <тип_слоя|all>"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(TextUtil.parse(config.getPrefixedMessage("player-not-found", "Игрок <player> не найден."), Map.of("player", args[1])));
            return;
        }
        String slotType = args[2].toLowerCase(Locale.ROOT);
        if (slotType.equals("all")) {
            manager.resetSkillLayersAndRefresh(target, true);
            sender.sendMessage(TextUtil.parse(config.getPrefixedMessage("skin-reset", "Слои сброшены.")));
        } else {
            String canonical = config.resolveLayerId(slotType);
            manager.clearSkillLayer(target, canonical, true);
            sender.sendMessage(TextUtil.parse(config.getPrefixedMessage("layer-cleared", "Слой <slot> очищен."), Map.of("slot", canonical)));
        }
    }

    private void handleReset(CommandSender sender, String[] args) {
        if (!sender.hasPermission("mythicwardrobe.admin")) {
            sender.sendMessage(TextUtil.parse(config.getPrefixedMessage("no-permission", "Нет прав.")));
            return;
        }
        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(TextUtil.parse(config.getPrefixedMessage("player-not-found", "Игрок <player> не найден."), Map.of("player", args[1])));
                return;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage(TextUtil.parse(config.getPrefix() + "<yellow>/wardrobe reset <игрок>"));
            return;
        }
        manager.resetSkillLayersAndRefresh(target, true);
        sender.sendMessage(TextUtil.parse(config.getPrefixedMessage("skin-reset", "Слои скина сброшены.")));
    }

    /**
     * Supports:
     *   /wardrobe bodytype <slim|wide|auto>            (for self, needs mythicwardrobe.use)
     *   /wardrobe bodytype <player> <slim|wide|auto>   (for another player, needs mythicwardrobe.admin)
     */
    private void handleBodyType(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(TextUtil.parse(config.getPrefix() + "<yellow>/wardrobe bodytype [игрок] <slim|wide|auto>"));
            return;
        }

        Player target;
        String modeArg;
        if (args.length == 2) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(TextUtil.parse(config.getPrefix() + "<yellow>/wardrobe bodytype <игрок> <slim|wide|auto>"));
                return;
            }
            if (!p.hasPermission("mythicwardrobe.use")) {
                p.sendMessage(TextUtil.parse(config.getPrefixedMessage("no-permission", "Нет прав.")));
                return;
            }
            target = p;
            modeArg = args[1];
        } else {
            if (!sender.hasPermission("mythicwardrobe.admin")) {
                sender.sendMessage(TextUtil.parse(config.getPrefixedMessage("no-permission", "Нет прав.")));
                return;
            }
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(TextUtil.parse(config.getPrefixedMessage("player-not-found", "Игрок <player> не найден."), Map.of("player", args[1])));
                return;
            }
            modeArg = args[2];
        }

        SkinModelVariant variant;
        String displayLabel;
        if (modeArg.equalsIgnoreCase("auto") || modeArg.equalsIgnoreCase("reset")) {
            variant = null;
            displayLabel = "auto";
        } else {
            variant = SkinModelVariant.parseOrNull(modeArg);
            if (variant == null) {
                sender.sendMessage(TextUtil.parse(config.getPrefixedMessage("bodytype-invalid", "Используйте slim, wide или auto.")));
                return;
            }
            displayLabel = variant == SkinModelVariant.SLIM ? "slim (узкие руки 3px)" : "wide (широкие руки 4px)";
        }

        manager.setBodyType(target, variant, true);
        sender.sendMessage(TextUtil.parse(
                config.getPrefixedMessage("bodytype-set", "Тип тела для <player>: <bodytype>"),
                Map.of("player", target.getName(), "bodytype", displayLabel)
        ));
    }

    private void handleWebEditor(CommandSender sender, String[] args) {
        WebEditorServer web = plugin.getWebEditorServer();
        if (web == null || !web.isRunning()) {
            sender.sendMessage(TextUtil.parse(config.getPrefixedMessage("web-editor-disabled",
                    "Веб-редактор отключён в config.yml.")));
            return;
        }
        Player target;
        if (args.length >= 2) {
            if (!sender.hasPermission("mythicwardrobe.admin")) {
                sender.sendMessage(TextUtil.parse(config.getPrefixedMessage("no-permission", "Нет прав.")));
                return;
            }
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(TextUtil.parse(config.getPrefixedMessage("player-not-found", "Игрок <player> не найден."), Map.of("player", args[1])));
                return;
            }
        } else {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(TextUtil.parse(config.getPrefix() + "<yellow>/wardrobe web <игрок>"));
                return;
            }
            if (!p.hasPermission("mythicwardrobe.web")) {
                p.sendMessage(TextUtil.parse(config.getPrefixedMessage("no-permission", "Нет прав.")));
                return;
            }
            target = p;
        }

        String url = web.createSessionUrl(target);
        String msg = config.getPrefixedMessage("web-editor-link",
                "Откройте тестовый редактор скина: <click:open_url:'<url>'><underlined><aqua><url></aqua></underlined></click>");
        target.sendMessage(TextUtil.parse(msg, Map.of(
                "url", url,
                "minutes", String.valueOf(config.getWebEditorTokenExpireMinutes())
        )));
        if (sender != target) {
            sender.sendMessage(TextUtil.parse(config.getPrefix() + "<green>Ссылка веб-редактора отправлена игроку <white>" + target.getName() + "</white>: <aqua>" + url));
        }
    }

    private void handlePrewarm(CommandSender sender) {
        if (!sender.hasPermission("mythicwardrobe.admin")) {
            sender.sendMessage(TextUtil.parse(config.getPrefixedMessage("no-permission", "Нет прав.")));
            return;
        }
        manager.prewarmBaseSkinsAsync(sender);
    }

    private void handleLayers(CommandSender sender) {
        if (!sender.hasPermission("mythicwardrobe.admin")) {
            sender.sendMessage(TextUtil.parse(config.getPrefixedMessage("no-permission", "Нет прав.")));
            return;
        }
        List<SkinLayerDef> ordered = new ArrayList<>(config.getSkinLayersOrdered());
        Collections.reverse(ordered); // Show TOP-TO-BOTTOM matching priority.yml!
        sender.sendMessage(TextUtil.parse(config.getPrefix() + "<white>Приоритет 2D-слоёв (<aqua>priority.yml</aqua> + <aqua>layers.yml</aqua>, <yellow>сверху вниз</yellow>):"));
        for (int i = 0; i < ordered.size(); i++) {
            SkinLayerDef def = ordered.get(i);
            String posTag = i == 0 ? " <green>[ВЕРХНИЙ]" : (i == ordered.size() - 1 ? " <gold>[НИЖНИЙ]" : "");
            String baseTag = def.replacesBaseSkin() ? " <gold>[BASE]" : "";
            String aliasTag = def.aliases().isEmpty() ? "" : " <dark_gray>(" + String.join(", ", def.aliases()) + ")";
            sender.sendMessage(TextUtil.parse("<dark_gray>  " + (i + 1) + ". <aqua>" + def.id()
                    + " <gray>→ overlays/" + def.folder() + "/" + posTag + baseTag + aliasTag));
        }
    }

    private void handleTag(CommandSender sender, String[] args) {
        if (!sender.hasPermission("mythicwardrobe.admin")) {
            sender.sendMessage(TextUtil.parse(config.getPrefixedMessage("no-permission", "Нет прав.")));
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(TextUtil.parse(config.getPrefixedMessage("player-only", "Только для игроков.")));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(TextUtil.parse(config.getPrefix() + "<yellow>/wardrobe tag <slotId> [texture.png] [blocks_csv] [UNEQUIP|PREVENT|HIDE]"));
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            player.sendMessage(TextUtil.parse(config.getPrefixedMessage("hold-item", "Возьмите предмет в руку!")));
            return;
        }
        String slotId = config.resolveLayerId(args[1]);
        WardrobeSlotDef def = config.getSlot(slotId);
        if (def == null) {
            def = config.getSlot(args[1].toLowerCase(Locale.ROOT));
        }
        if (def == null) {
            player.sendMessage(TextUtil.parse(config.getPrefixedMessage("unknown-slot", "Неизвестный слот: <slot>"), Map.of("slot", args[1])));
            return;
        }
        String texture = args.length >= 3 ? args[2] : "";
        if (def.isSkinLayer() && !texture.isBlank()
                && layerRegistry.resolve(def.folder(), texture, config.getDefaultVariant()).isEmpty()) {
            player.sendMessage(TextUtil.parse(config.getPrefixedMessage("texture-not-found", "Текстура <texture> не найдена."), Map.of("texture", texture)));
            return;
        }
        Set<String> blocks = args.length >= 4 ? ItemWardrobeMeta.parseBlockedTargetsCsv(args[3]) : null;
        BlockBehaviorMode mode = args.length >= 5 ? BlockBehaviorMode.parseOrNull(args[4]) : null;
        resolver.tagItem(hand, def.id(), texture, blocks, mode);
        player.sendMessage(TextUtil.parse(
                config.getPrefixedMessage("tagged-item", "Предмет привязан к слоту <slot> (текстура: <texture>)"),
                Map.of("slot", def.id(), "texture", texture.isBlank() ? "нет" : texture)
        ));
        if (blocks != null && !blocks.isEmpty()) {
            player.sendMessage(TextUtil.parse(
                    config.getPrefixedMessage("item-blocks-set", "Задана блокировка слоёв/слотов: <blocks> (режим: <mode>)"),
                    Map.of("blocks", String.join(", ", blocks), "mode", mode != null ? mode.name() : config.getDefaultBlockMode().name())
            ));
        }
    }

    private void handleBlock(CommandSender sender, String[] args) {
        if (!sender.hasPermission("mythicwardrobe.admin")) {
            sender.sendMessage(TextUtil.parse(config.getPrefixedMessage("no-permission", "Нет прав.")));
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(TextUtil.parse(config.getPrefixedMessage("player-only", "Только для игроков.")));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(TextUtil.parse(config.getPrefix() + "<yellow>/wardrobe block <слоты_через_запятую | all_clothing | suit | clear> [UNEQUIP|PREVENT|HIDE]"));
            sender.sendMessage(TextUtil.parse("<gray>Пример: <white>/wardrobe block pants,undershirt,belt_skin,gloves,socks,boots_skin,mask_skin,hat UNEQUIP"));
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            player.sendMessage(TextUtil.parse(config.getPrefixedMessage("hold-item", "Возьмите предмет в руку!")));
            return;
        }
        String raw = args[1].trim();
        if (raw.equalsIgnoreCase("clear") || raw.equalsIgnoreCase("none") || raw.equalsIgnoreCase("reset")) {
            resolver.setItemBlocks(hand, Set.of(), null);
            player.sendMessage(TextUtil.parse(config.getPrefixedMessage("item-blocks-cleared", "Блокировки слоёв сняты с предмета в руке.")));
            return;
        }
        Set<String> blocks = ItemWardrobeMeta.parseBlockedTargetsCsv(raw);
        BlockBehaviorMode mode = args.length >= 3 ? BlockBehaviorMode.parseOrNull(args[2]) : null;
        resolver.setItemBlocks(hand, blocks, mode);
        player.sendMessage(TextUtil.parse(
                config.getPrefixedMessage("item-blocks-set", "Для предмета в руке задана блокировка слоёв/слотов: <blocks> (режим: <mode>)"),
                Map.of("blocks", String.join(", ", blocks), "mode", mode != null ? mode.name() : config.getDefaultBlockMode().name())
        ));
    }

    private void handleList(CommandSender sender) {
        if (!sender.hasPermission("mythicwardrobe.admin")) {
            sender.sendMessage(TextUtil.parse(config.getPrefixedMessage("no-permission", "Нет прав.")));
            return;
        }
        Set<String> keys = layerRegistry.getAllOverlayKeys();
        sender.sendMessage(TextUtil.parse(config.getPrefix() + "<white>Загруженные PNG-слои (<aqua>" + keys.size() + "</aqua>):"));
        if (keys.isEmpty()) {
            sender.sendMessage(TextUtil.parse("<gray>  (пусто — положите 64x64 PNG в plugins/MythicWardrobe/overlays/)"));
            return;
        }
        int shown = 0;
        for (String key : keys) {
            sender.sendMessage(TextUtil.parse("<dark_gray>  • <aqua>" + key));
            if (++shown >= 40) {
                sender.sendMessage(TextUtil.parse("<gray>  ... и ещё " + (keys.size() - shown)));
                break;
            }
        }
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("mythicwardrobe.admin")) {
            sender.sendMessage(TextUtil.parse(config.getPrefixedMessage("no-permission", "Нет прав.")));
            return;
        }
        int count = plugin.reloadAll();
        sender.sendMessage(TextUtil.parse(
                config.getPrefixedMessage("reloaded", "Перезагружено! Текстур: <count>, слоёв скина: <layers>, GUI-слотов: <slots>"),
                Map.of(
                        "count", String.valueOf(count),
                        "layers", String.valueOf(config.getSkinLayersOrdered().size()),
                        "slots", String.valueOf(config.getSlotsById().size())
                )
        ));
    }

    private void handleDebug(CommandSender sender, String[] args) {
        if (!sender.hasPermission("mythicwardrobe.admin")) {
            sender.sendMessage(TextUtil.parse(config.getPrefixedMessage("no-permission", "Нет прав.")));
            return;
        }
        Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : (sender instanceof Player p ? p : null);
        if (target == null) {
            sender.sendMessage(TextUtil.parse(config.getPrefix() + "<yellow>/wardrobe debug <игрок>"));
            return;
        }
        PlayerWardrobeState state = manager.getState(target);
        Map<String, WardrobeManager.SlotBlockInfo> activeBlocked = manager.getActiveBlockedMap(target);
        Map<String, String> activeLayers = manager.collectActiveSkinLayersOrdered(state, activeBlocked.keySet());
        SkinModelVariant effectiveBody = manager.getEffectiveBodyType(target);
        String bodyDesc = effectiveBody.getBodyTypeName()
                + (state.getBodyTypeOverride() != null ? " (override)" : " (auto)");

        sender.sendMessage(TextUtil.parse(config.getPrefix() + "<white>Отладка для <aqua>" + target.getName() + "</aqua>:"));
        sender.sendMessage(TextUtil.parse("<gray>  Skin Backend: <white>" + manager.getSkinBackend().getName()));
        sender.sendMessage(TextUtil.parse("<gray>  PacketEvents: <white>" + (manager.getArmorVisualManager().isPacketEventsActive() ? "ON" : "OFF (Paper fallback)")));
        sender.sendMessage(TextUtil.parse("<gray>  BodyType (Arms): <aqua>" + bodyDesc));
        sender.sendMessage(TextUtil.parse("<gray>  Menu Mode: <aqua>" + manager.getEffectiveMenuMode(target).name()
                + " <dark_gray>(default: " + config.getDefaultMenuMode().name() + ")"));
        sender.sendMessage(TextUtil.parse("<gray>  Base Skin: <white>" + (state.getBaseSkin() != null ? state.getBaseSkin().variant() + " (" + state.getBaseSkin().textureUrl() + ")" : "none/fallback")));
        sender.sendMessage(TextUtil.parse("<gray>  Active 2D Layers (Z-order): <aqua>" + (activeLayers.isEmpty() ? "нет" : activeLayers.toString())));
        sender.sendMessage(TextUtil.parse("<gray>  Blocked Slots/Layers: <red>" + (activeBlocked.isEmpty() ? "<green>нет" : activeBlocked.keySet().toString())));
        sender.sendMessage(TextUtil.parse("<gray>  Equipped Custom Slots: <white>" + state.getSlotItems().keySet()));
        sender.sendMessage(TextUtil.parse("<gray>  Active 3D Displays: <white>" + manager.getDisplayAccessoryManager().countActiveDisplays(target.getUniqueId())));
        sender.sendMessage(TextUtil.parse("<gray>  Last Skin Hash: <white>" + state.getLastAppliedSkinHash()));
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(TextUtil.parse(config.getPrefix() + "<white>Команды MythicWardrobe:"));
        sender.sendMessage(TextUtil.parse("<gray>  /wardrobe <dark_gray>— открыть гардероб"));
        sender.sendMessage(TextUtil.parse("<gray>  /wardrobe open <head|chest|legs|feet|hub|unified> [игрок]"));
        sender.sendMessage(TextUtil.parse("<gray>  /wardrobe mode [игрок] <compact|tabbed|unified|default> <dark_gray>— вид меню"));
        sender.sendMessage(TextUtil.parse("<gray>  /wardrobe bodytype [игрок] <slim|wide|auto> <dark_gray>— узкие/широкие руки"));
        if (plugin.getWebEditorServer() != null && plugin.getWebEditorServer().isRunning()) {
            sender.sendMessage(TextUtil.parse("<gray>  /wardrobe web [игрок] <dark_gray>— открыть тестовый 3D веб-редактор"));
        }
        if (sender.hasPermission("mythicwardrobe.admin")) {
            sender.sendMessage(TextUtil.parse("<gray>  /wardrobe set <игрок> <тип> <файл.png>"));
            sender.sendMessage(TextUtil.parse("<gray>  /wardrobe clear <игрок> <тип|all>"));
            sender.sendMessage(TextUtil.parse("<gray>  /wardrobe reset [игрок]"));
            sender.sendMessage(TextUtil.parse("<gray>  /wardrobe layers <dark_gray>— показать все слои из layers.yml"));
            sender.sendMessage(TextUtil.parse("<gray>  /wardrobe tag <slotId> [файл.png] [blocks_csv] [UNEQUIP|PREVENT|HIDE]"));
            sender.sendMessage(TextUtil.parse("<gray>  /wardrobe block <слоты|all_clothing|suit|clear> [UNEQUIP|PREVENT|HIDE] <dark_gray>— блокировка слоёв на предмете в руке"));
            sender.sendMessage(TextUtil.parse("<gray>  /wardrobe merge <modelA> <modelB> <имя> <dark_gray>— склеить 2 .bbmodel (напр. жилет + рюкзак)"));
            sender.sendMessage(TextUtil.parse("<gray>  /wardrobe buildheadpack [слоёв] <dark_gray>— собрать composite-пак для безлимитных 3D-слоёв головы (волосы+маска+шлем)"));
            sender.sendMessage(TextUtil.parse("<gray>  /wardrobe list | prewarm | reload | debug [игрок]"));
        }
    }

    private void handleBuildHeadPack(CommandSender sender, String[] args) {
        if (!sender.hasPermission("mythicwardrobe.admin")) {
            sender.sendMessage(TextUtil.parse(config.getPrefixedMessage("no-permission", "Нет прав.")));
            return;
        }
        int maxLayers = 6;
        if (args.length >= 2) {
            try {
                maxLayers = Math.max(2, Math.min(12, Integer.parseInt(args[1])));
            } catch (NumberFormatException ignored) {
            }
        }

        File pluginsDir = plugin.getDataFolder().getParentFile();
        File packZip = new File(pluginsDir, "MythicArmors/pack.zip");
        if (!packZip.isFile()) {
            File alt = new File(pluginsDir, "MythicArmor/pack.zip");
            if (alt.isFile()) packZip = alt;
        }
        Path srcPack = packZip.toPath();
        Path mergedZip = new File(plugin.getDataFolder(), "merged_pack.zip").toPath();
        Path manifest = new File(plugin.getDataFolder(), "head_composite_manifest.json").toPath();
        int finalMaxLayers = maxLayers;

        sender.sendMessage(TextUtil.parse(config.getPrefix() + "<gray>Собираем <aqua>minecraft:composite</aqua> ресурспак головы (до <white>" + finalMaxLayers + "</white> 3D-слоёв одновременно)..."));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                HeadCompositePackBuilder.BuildResult res = HeadCompositePackBuilder.buildFromMythicArmorsPack(
                        srcPack, mergedZip, manifest, finalMaxLayers
                );
                Bukkit.getScheduler().runTask(plugin, () -> {
                    config.reloadHeadCompositeManifest();
                    manager.reloadVisualsForAll();
                    sender.sendMessage(TextUtil.parse(config.getPrefix() + "<green>Готово! Собрано <white>" + res.modelCount()
                            + "</white> 3D-моделей головы (до <white>" + res.maxLayers() + "</white> одновременно без лага!)."));
                    sender.sendMessage(TextUtil.parse("<gray>  Ресурспак: <aqua>plugins/MythicWardrobe/merged_pack.zip"));
                    sender.sendMessage(TextUtil.parse("<gray>  Подключите <white>merged_pack.zip <gray>в клиент/сервер — 3D-причёска + 3D-маска + 3D-шлем будут сидеть на кости head с 0 лага!"));
                });
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(TextUtil.parse(
                        config.getPrefix() + "<red>Ошибка сборки head_composite: <white>" + e.getMessage()
                )));
            }
        });
    }

    private void handleMerge(CommandSender sender, String[] args) {
        if (!sender.hasPermission("mythicwardrobe.admin")) {
            sender.sendMessage(TextUtil.parse(config.getPrefixedMessage("no-permission", "Нет прав.")));
            return;
        }
        if (args.length < 4) {
            sender.sendMessage(TextUtil.parse(config.getPrefix() + "<yellow>Использование: <white>/wardrobe merge <modelA> <modelB> <outputName>"));
            sender.sendMessage(TextUtil.parse("<gray>Пример: <white>/wardrobe merge tactical_warden tactical_backpack tactical_warden_backpack"));
            return;
        }

        File modelsDir = resolveMythicArmorsModelsDir();
        String nameA = BBModelMerger.sanitizeName(args[1]);
        String nameB = BBModelMerger.sanitizeName(args[2]);
        String outName = BBModelMerger.sanitizeName(args[3]);

        Path fileA = modelsDir.toPath().resolve(nameA + ".bbmodel");
        Path fileB = modelsDir.toPath().resolve(nameB + ".bbmodel");
        Path outFile = modelsDir.toPath().resolve(outName + ".bbmodel");

        sender.sendMessage(TextUtil.parse(config.getPrefix() + "<gray>Склеиваем <white>" + nameA + " + " + nameB + " <gray>-> <aqua>" + outName + ".bbmodel<gray>..."));

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                BBModelMerger.MergeResult res = BBModelMerger.merge(fileA, fileB, outFile, outName);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    sender.sendMessage(TextUtil.parse(config.getPrefix() + "<green>Готово! Создана комбо-модель <white>" + outName + ".bbmodel"
                            + " <gray>(" + res.totalCubes() + " кубов, " + res.totalBones() + " костей, атлас " + res.atlasWidth() + "x" + res.atlasHeight() + ")."));
                    sender.sendMessage(TextUtil.parse("<gray>1) Выполните <white>/ma reload <gray>и обновите ресурспак MythicArmors."));
                    sender.sendMessage(TextUtil.parse("<gray>2) В MythicMobs укажите <aqua>mythicarmor:" + outName + "_chest_piece <gray>(или <aqua>_head_piece<gray>) и пропишите правило в <white>combo-overrides.yml<gray>!"));
                });
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(TextUtil.parse(
                        config.getPrefix() + "<red>Ошибка склейки .bbmodel: <white>" + e.getMessage()
                )));
            }
        });
    }

    private File resolveMythicArmorsModelsDir() {
        File pluginsDir = plugin.getDataFolder().getParentFile();
        File primary = new File(pluginsDir, "MythicArmors/models");
        if (primary.isDirectory()) return primary;
        File alt = new File(pluginsDir, "MythicArmor/models");
        if (alt.isDirectory()) return alt;
        return primary;
    }

    private List<String> listAvailableBBModels() {
        File dir = resolveMythicArmorsModelsDir();
        if (!dir.isDirectory()) return List.of();
        File[] files = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".bbmodel"));
        if (files == null) return List.of();
        List<String> names = new ArrayList<>();
        for (File f : files) {
            String n = f.getName();
            names.add(n.substring(0, n.length() - 8));
        }
        Collections.sort(names);
        return names;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("open", "mode", "bodytype", "web"));
            if (sender.hasPermission("mythicwardrobe.admin")) {
                subs.addAll(List.of("set", "clear", "reset", "layers", "tag", "block", "merge", "buildheadpack", "list", "prewarm", "reload", "debug"));
            }
            return filterPrefix(subs, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if ((sub.equals("merge") || sub.equals("combine")) && sender.hasPermission("mythicwardrobe.admin")) {
            if (args.length == 2 || args.length == 3) {
                return filterPrefix(listAvailableBBModels(), args[args.length - 1]);
            }
            if (args.length == 4) {
                return List.of(BBModelMerger.sanitizeName(args[1]) + "_" + BBModelMerger.sanitizeName(args[2]));
            }
            return List.of();
        }
        if (sub.equals("open") && args.length == 2) {
            return filterPrefix(List.of("head", "chest", "legs", "feet", "hub", "unified"), args[1]);
        }
        if ((sub.equals("mode") || sub.equals("menu") || sub.equals("style") || sub.equals("gui")) && args.length == 2) {
            List<String> opts = new ArrayList<>(List.of("compact", "tabbed", "unified", "default"));
            if (sender.hasPermission("mythicwardrobe.admin")) {
                Bukkit.getOnlinePlayers().forEach(p -> opts.add(p.getName()));
            }
            return filterPrefix(opts, args[1]);
        }
        if ((sub.equals("mode") || sub.equals("menu") || sub.equals("style") || sub.equals("gui")) && args.length == 3) {
            return filterPrefix(List.of("compact", "tabbed", "unified", "default"), args[2]);
        }
        if ((sub.equals("bodytype") || sub.equals("body_type") || sub.equals("arms")) && args.length == 2) {
            List<String> opts = new ArrayList<>(List.of("slim", "wide", "auto"));
            if (sender.hasPermission("mythicwardrobe.admin")) {
                Bukkit.getOnlinePlayers().forEach(p -> opts.add(p.getName()));
            }
            return filterPrefix(opts, args[1]);
        }
        if ((sub.equals("bodytype") || sub.equals("body_type") || sub.equals("arms")) && args.length == 3) {
            return filterPrefix(List.of("slim", "wide", "auto"), args[2]);
        }
        if ((sub.equals("set") || sub.equals("clear") || sub.equals("reset") || sub.equals("debug") || sub.equals("web") || sub.equals("editor")) && args.length == 2) {
            return filterPrefix(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        if ((sub.equals("set") || sub.equals("clear")) && args.length == 3) {
            // Dynamic layer IDs straight from layers.yml + slots.yml!
            List<String> types = new ArrayList<>(config.getAllValidLayerTypeIds());
            if (sub.equals("clear")) types.add("all");
            return filterPrefix(types, args[2]);
        }
        if (sub.equals("set") && args.length == 4) {
            SkinLayerDef def = config.getSkinLayerDef(args[2]);
            String folder = def != null ? def.folder() : args[2];
            return filterPrefix(layerRegistry.getSuggestionsForFolder(folder), args[3]);
        }
        if (sub.equals("tag") && args.length == 2) {
            return filterPrefix(new ArrayList<>(config.getSlotsById().keySet()), args[1]);
        }
        if (sub.equals("tag") && args.length == 3) {
            WardrobeSlotDef slotDef = config.getSlot(args[1]);
            if (slotDef != null && slotDef.isSkinLayer()) {
                return filterPrefix(layerRegistry.getSuggestionsForFolder(slotDef.folder()), args[2]);
            }
            return filterPrefix(new ArrayList<>(layerRegistry.getAllOverlayKeys()), args[2]);
        }
        if ((sub.equals("block") || sub.equals("blocks") || sub.equals("lock")) && args.length == 2) {
            List<String> opts = new ArrayList<>(List.of("all_clothing", "all_body", "suit", "all_skin", "all_3d", "all", "clear",
                    "pants,undershirt,belt_skin,gloves,socks,boots_skin,mask_skin,hat"));
            opts.addAll(config.getSlotsById().keySet());
            return filterPrefix(opts, args[1]);
        }
        if ((sub.equals("block") || sub.equals("blocks") || sub.equals("lock")) && args.length == 3) {
            return filterPrefix(List.of("UNEQUIP", "PREVENT", "HIDE"), args[2]);
        }
        if (sub.equals("tag") && args.length == 4) {
            return filterPrefix(List.of("all_clothing", "all_body", "suit", "pants,undershirt,belt_skin,gloves,socks,boots_skin,mask_skin,hat"), args[3]);
        }
        if (sub.equals("tag") && args.length == 5) {
            return filterPrefix(List.of("UNEQUIP", "PREVENT", "HIDE"), args[4]);
        }
        return List.of();
    }

    private static List<String> filterPrefix(List<String> candidates, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String c : candidates) {
            if (c.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(c);
            }
        }
        return out;
    }
}
