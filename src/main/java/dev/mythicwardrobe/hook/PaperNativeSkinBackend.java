package dev.mythicwardrobe.hook;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import dev.mythicwardrobe.model.SignedSkinData;
import dev.mythicwardrobe.model.SkinModelVariant;
import dev.mythicwardrobe.skin.SkinBackend;
import dev.mythicwardrobe.skin.SkinCompositor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;

/**
 * Native Paper 1.21+ PlayerProfile skin backend (used as fallback or when backend=PAPER).
 */
public final class PaperNativeSkinBackend implements SkinBackend {

    private final JavaPlugin plugin;
    private final SkinModelVariant defaultVariant;

    public PaperNativeSkinBackend(JavaPlugin plugin, SkinModelVariant defaultVariant) {
        this.plugin = plugin;
        this.defaultVariant = defaultVariant;
    }

    @Override
    public String getName() {
        return "PaperAPI";
    }

    @Override
    public Optional<SignedSkinData> fetchCurrentBaseSkin(Player player) {
        return extractFromProfile(player.getPlayerProfile(), defaultVariant);
    }

    @Override
    public void applySignedSkin(Player player, SignedSkinData skin) {
        if (skin == null || !skin.isValid() || !player.isOnline()) return;
        Runnable task = () -> {
            if (!player.isOnline()) return;
            PlayerProfile profile = player.getPlayerProfile();
            profile.setProperty(new ProfileProperty("textures", skin.value(), skin.signature()));
            player.setPlayerProfile(profile);
        };
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    public static Optional<SignedSkinData> extractFromProfile(PlayerProfile profile, SkinModelVariant fallbackVariant) {
        if (profile == null) return Optional.empty();
        for (ProfileProperty prop : profile.getProperties()) {
            if ("textures".equals(prop.getName()) && prop.getValue() != null && prop.getSignature() != null) {
                var decoded = SkinCompositor.decodeTexturesProperty(prop.getValue(), fallbackVariant);
                String url = decoded.map(SkinCompositor.DecodedTextureProperty::textureUrl).orElse(null);
                SkinModelVariant variant = decoded.map(SkinCompositor.DecodedTextureProperty::variant).orElse(fallbackVariant);
                return Optional.of(new SignedSkinData(prop.getValue(), prop.getSignature(), url, variant));
            }
        }
        return Optional.empty();
    }
}
