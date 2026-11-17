package dev.mythicwardrobe.hook;

import dev.mythicwardrobe.model.SignedSkinData;
import dev.mythicwardrobe.model.SkinModelVariant;
import dev.mythicwardrobe.skin.SkinBackend;
import dev.mythicwardrobe.skin.SkinCompositor;
import net.skinsrestorer.api.PropertyUtils;
import net.skinsrestorer.api.SkinsRestorer;
import net.skinsrestorer.api.SkinsRestorerProvider;
import net.skinsrestorer.api.VersionProvider;
import net.skinsrestorer.api.event.SkinApplyEvent;
import net.skinsrestorer.api.property.SkinProperty;
import net.skinsrestorer.api.property.SkinVariant;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.logging.Level;

/**
 * SkinsRestorer v15 API integration.
 * Uses temporary applySkin(player, SkinProperty) so player's permanent SkinsRestorer base skin selection is preserved.
 */
public final class SkinsRestorerBackend implements SkinBackend {

    private final JavaPlugin plugin;
    private final SkinModelVariant defaultVariant;
    private final SkinsRestorer skinsRestorer;
    private final Map<UUID, String> selfAppliedValues = new ConcurrentHashMap<>();
    private volatile BiConsumer<Player, SignedSkinData> baseSkinChangeListener;

    public SkinsRestorerBackend(JavaPlugin plugin, SkinModelVariant defaultVariant) {
        this.plugin = plugin;
        this.defaultVariant = defaultVariant;
        this.skinsRestorer = SkinsRestorerProvider.get();

        if (!VersionProvider.isCompatibleWith("15")) {
            plugin.getLogger().warning("SkinsRestorer API major version differs from 15; proceeding cautiously.");
        }

        skinsRestorer.getEventBus().subscribe(plugin, SkinApplyEvent.class, this::onSkinApplyEvent);
    }

    @Override
    public String getName() {
        return "SkinsRestorer v" + skinsRestorer.getVersion();
    }

    @Override
    public void setBaseSkinChangeListener(BiConsumer<Player, SignedSkinData> listener) {
        this.baseSkinChangeListener = listener;
    }

    @Override
    public Optional<SignedSkinData> fetchCurrentBaseSkin(Player player) {
        try {
            Optional<SkinProperty> stored = skinsRestorer.getPlayerStorage()
                    .getSkinForPlayer(player.getUniqueId(), player.getName());
            if (stored.isPresent()) {
                return Optional.of(fromSkinProperty(stored.get()));
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.FINE, "SkinsRestorer lookup failed for " + player.getName() + ", checking profile.", e);
        }
        return PaperNativeSkinBackend.extractFromProfile(player.getPlayerProfile(), defaultVariant);
    }

    @Override
    public void applySignedSkin(Player player, SignedSkinData skin) {
        if (skin == null || !skin.isValid() || !player.isOnline()) return;

        selfAppliedValues.put(player.getUniqueId(), skin.value());
        SkinProperty prop = SkinProperty.of(skin.value(), skin.signature());

        Runnable task = () -> {
            if (!player.isOnline()) return;
            try {
                skinsRestorer.getSkinApplier(Player.class).applySkin(player, prop);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "SkinsRestorer applySkin failed for " + player.getName(), e);
            }
        };
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    public void clearTracking(UUID playerId) {
        selfAppliedValues.remove(playerId);
    }

    private void onSkinApplyEvent(SkinApplyEvent event) {
        if (event.isCancelled()) return;
        SkinProperty prop = event.getProperty();
        if (prop == null) return;

        Player player;
        try {
            player = event.getPlayer(Player.class);
        } catch (Throwable ignored) {
            return;
        }
        if (player == null) return;

        String expectedSelf = selfAppliedValues.get(player.getUniqueId());
        if (expectedSelf != null && expectedSelf.equals(prop.getValue())) {
            // This event came from our own composed skin apply -> ignore to avoid recursion.
            return;
        }

        // Otherwise, SkinsRestorer applied an external / base skin (join or /skin <nick>).
        SignedSkinData newBase = fromSkinProperty(prop);
        BiConsumer<Player, SignedSkinData> listener = this.baseSkinChangeListener;
        if (listener != null) {
            listener.accept(player, newBase);
        }
    }

    private SignedSkinData fromSkinProperty(SkinProperty prop) {
        String url = null;
        SkinModelVariant variant = defaultVariant;
        try {
            url = PropertyUtils.getSkinTextureUrl(prop);
            SkinVariant srVar = PropertyUtils.getSkinVariant(prop);
            if (srVar == SkinVariant.SLIM) {
                variant = SkinModelVariant.SLIM;
            } else if (srVar == SkinVariant.CLASSIC) {
                variant = SkinModelVariant.CLASSIC;
            }
        } catch (Exception ignored) {
            var decoded = SkinCompositor.decodeTexturesProperty(prop.getValue(), defaultVariant);
            if (decoded.isPresent()) {
                url = decoded.get().textureUrl();
                variant = decoded.get().variant();
            }
        }
        if (url != null && url.startsWith("http://textures.minecraft.net")) {
            url = "https://" + url.substring("http://".length());
        }
        return new SignedSkinData(prop.getValue(), prop.getSignature(), url, variant);
    }
}
