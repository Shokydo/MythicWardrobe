package dev.mythicwardrobe.skin;

import dev.mythicwardrobe.model.SignedSkinData;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.function.BiConsumer;

public interface SkinBackend {

    String getName();

    /**
     * Fetches the player's current underlying/base skin property from the backend or live profile.
     * May perform network/storage lookups — safe to call asynchronously.
     */
    Optional<SignedSkinData> fetchCurrentBaseSkin(Player player);

    /**
     * Applies a signed skin property to an online player (must be called on the main thread or handles sync internally).
     */
    void applySignedSkin(Player player, SignedSkinData skin);

    /**
     * Registers a listener notified whenever a player's underlying base skin changes (e.g. via SkinsRestorer /skin).
     */
    default void setBaseSkinChangeListener(BiConsumer<Player, SignedSkinData> listener) {
    }
}
