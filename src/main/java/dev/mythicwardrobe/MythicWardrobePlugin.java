package dev.mythicwardrobe;

import dev.mythicwardrobe.command.WardrobeCommand;
import dev.mythicwardrobe.config.WardrobeConfig;
import dev.mythicwardrobe.gui.WardrobeGuiManager;
import dev.mythicwardrobe.hook.MythicMobsHook;
import dev.mythicwardrobe.hook.PaperNativeSkinBackend;
import dev.mythicwardrobe.hook.SkinsRestorerBackend;
import dev.mythicwardrobe.listener.WardrobeListener;
import dev.mythicwardrobe.manager.WardrobeItemResolver;
import dev.mythicwardrobe.manager.WardrobeManager;
import dev.mythicwardrobe.skin.MineSkinClient;
import dev.mythicwardrobe.skin.SkinBackend;
import dev.mythicwardrobe.skin.SkinLayerRegistry;
import dev.mythicwardrobe.storage.WardrobeDatabase;
import dev.mythicwardrobe.web.WebEditorServer;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.SQLException;
import java.util.logging.Level;

public final class MythicWardrobePlugin extends JavaPlugin {

    private WardrobeConfig wardrobeConfig;
    private WardrobeDatabase database;
    private SkinLayerRegistry layerRegistry;
    private MineSkinClient mineSkinClient;
    private SkinBackend skinBackend;
    private MythicMobsHook mythicHook;
    private WardrobeItemResolver itemResolver;
    private WardrobeManager wardrobeManager;
    private WardrobeGuiManager guiManager;
    private WebEditorServer webEditorServer;

    @Override
    public void onEnable() {
        this.wardrobeConfig = new WardrobeConfig(this);
        this.wardrobeConfig.reload();

        // SQLite Database
        this.database = new WardrobeDatabase(new File(getDataFolder(), "wardrobe.db"), getLogger());
        try {
            database.init();
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Failed to initialize SQLite database! Disabling MythicWardrobe.", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // Skin Overlays Registry
        this.layerRegistry = new SkinLayerRegistry(getDataFolder(), getLogger());
        this.layerRegistry.ensureDirectories(
                wardrobeConfig.getSkinLayersOrdered(),
                wardrobeConfig.getSlotsById().values(),
                wardrobeConfig.isCreateExampleOverlays()
        );
        int overlayCount = this.layerRegistry.reload();
        getLogger().info("Loaded " + overlayCount + " PNG skin overlays, "
                + wardrobeConfig.getSkinLayersOrdered().size() + " skin layer types (layers.yml), and "
                + wardrobeConfig.getSlotsById().size() + " GUI slots.");

        // MineSkin API v2 Client
        this.mineSkinClient = new MineSkinClient(wardrobeConfig, getLogger());
        if (wardrobeConfig.getMineSkinApiKey() == null
                || wardrobeConfig.getMineSkinApiKey().isBlank()
                || wardrobeConfig.getMineSkinApiKey().equals("YOUR_MINESKIN_API_KEY")) {
            getLogger().warning("MineSkin API key is not configured in config.yml! Get a free key at https://account.mineskin.org/keys for fast skin signing.");
        }

        // Skin Backend (SkinsRestorer v15 or Paper API)
        this.skinBackend = createSkinBackend();
        getLogger().info("Active skin backend: " + skinBackend.getName());

        // MythicMobs 5.13+ Hook
        if (Bukkit.getPluginManager().isPluginEnabled("MythicMobs")) {
            this.mythicHook = new MythicMobsHook(this, wardrobeConfig);
            Bukkit.getPluginManager().registerEvents(mythicHook, this);
            getLogger().info("MythicMobs detected — registered custom mechanic `wardrobe` and condition `haswardrobelayer`!");
        } else {
            getLogger().info("MythicMobs not found — running in standalone mode.");
        }

        // Resolver, Manager, GUI
        this.itemResolver = new WardrobeItemResolver(this, wardrobeConfig, mythicHook);
        this.wardrobeManager = new WardrobeManager(
                this,
                wardrobeConfig,
                database,
                layerRegistry,
                mineSkinClient,
                skinBackend,
                itemResolver,
                mythicHook
        );
        if (mythicHook != null) {
            mythicHook.attachManager(wardrobeManager);
        }

        this.guiManager = new WardrobeGuiManager(this, wardrobeConfig, wardrobeManager, itemResolver);
        this.wardrobeManager.setGuiReopener(guiManager::reopenSnapshot);

        // Events & Commands
        Bukkit.getPluginManager().registerEvents(
                new WardrobeListener(this, wardrobeConfig, wardrobeManager, guiManager, itemResolver),
                this
        );

        WardrobeCommand cmdExecutor = new WardrobeCommand(this, wardrobeConfig, wardrobeManager, guiManager, layerRegistry, itemResolver);
        PluginCommand cmd = getCommand("wardrobe");
        if (cmd != null) {
            cmd.setExecutor(cmdExecutor);
            cmd.setTabCompleter(cmdExecutor);
        }

        // Start PacketEvents bridge, 3D display ticker, and hydrate online players
        wardrobeManager.start();

        // Start temporary Web Skin Editor (disabled cleanly when web-editor.enabled: false)
        this.webEditorServer = new WebEditorServer(this, wardrobeConfig, layerRegistry, wardrobeManager);
        if (wardrobeConfig.isWebEditorEnabled()) {
            this.webEditorServer.start();
        }

        getLogger().info("MythicWardrobe v" + getPluginMeta().getVersion() + " enabled successfully!");
    }

    @Override
    public void onDisable() {
        if (webEditorServer != null) {
            webEditorServer.stop();
        }
        if (wardrobeManager != null) {
            wardrobeManager.shutdown();
        }
        if (mineSkinClient != null) {
            mineSkinClient.shutdown();
        }
        if (database != null) {
            database.close();
        }
    }

    public int reloadAll() {
        wardrobeConfig.reload();
        layerRegistry.ensureDirectories(
                wardrobeConfig.getSkinLayersOrdered(),
                wardrobeConfig.getSlotsById().values(),
                wardrobeConfig.isCreateExampleOverlays()
        );
        int count = layerRegistry.reload();
        if (wardrobeManager != null) {
            wardrobeManager.reloadVisualsForAll();
        }
        if (webEditorServer != null) {
            if (wardrobeConfig.isWebEditorEnabled()) {
                webEditorServer.start();
            } else {
                webEditorServer.stop();
            }
        }
        return count;
    }

    private SkinBackend createSkinBackend() {
        WardrobeConfig.SkinBackendMode mode = wardrobeConfig.getSkinBackendMode();
        boolean srEnabled = Bukkit.getPluginManager().isPluginEnabled("SkinsRestorer");

        if (mode == WardrobeConfig.SkinBackendMode.SKINSRESTORER || (mode == WardrobeConfig.SkinBackendMode.AUTO && srEnabled)) {
            if (srEnabled) {
                try {
                    return new SkinsRestorerBackend(this, wardrobeConfig.getDefaultVariant());
                } catch (Throwable t) {
                    getLogger().log(Level.WARNING, "Failed to hook SkinsRestorer v15 API, falling back to Paper API.", t);
                }
            } else if (mode == WardrobeConfig.SkinBackendMode.SKINSRESTORER) {
                getLogger().warning("config.yml requested SKINSRESTORER backend, but SkinsRestorer is not installed! Falling back to Paper API.");
            }
        }
        return new PaperNativeSkinBackend(this, wardrobeConfig.getDefaultVariant());
    }

    public WardrobeManager getWardrobeManager() {
        return wardrobeManager;
    }

    public WardrobeConfig getWardrobeConfig() {
        return wardrobeConfig;
    }

    public WebEditorServer getWebEditorServer() {
        return webEditorServer;
    }
}
