package dev.mythicwardrobe.hook;

import dev.mythicwardrobe.manager.WardrobeManager;
import dev.mythicwardrobe.model.SkinModelVariant;
import io.lumine.mythic.api.adapters.AbstractEntity;
import io.lumine.mythic.api.config.MythicLineConfig;
import io.lumine.mythic.api.skills.conditions.IEntityCondition;
import io.lumine.mythic.bukkit.BukkitAdapter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * MythicMobs custom condition:
 *   - hasbodytype{type=slim} true
 *   - hasbodytype{type=wide} true
 */
public final class HasBodyTypeCondition implements IEntityCondition {

    private final WardrobeManager wardrobeManager;
    private final SkinModelVariant expectedVariant;

    public HasBodyTypeCondition(WardrobeManager wardrobeManager, MythicLineConfig config) {
        this.wardrobeManager = wardrobeManager;
        String val = config.getString(new String[]{"type", "bodytype", "model", "variant", "t"}, "").trim();
        this.expectedVariant = SkinModelVariant.parseOrNull(val);
    }

    @Override
    public boolean check(AbstractEntity target) {
        if (expectedVariant == null) return false;
        Entity bukkitEntity = BukkitAdapter.adapt(target);
        if (!(bukkitEntity instanceof Player player)) return false;
        return wardrobeManager.getEffectiveBodyType(player) == expectedVariant;
    }
}
