package dev.mythicwardrobe.hook;

import dev.mythicwardrobe.manager.WardrobeManager;
import io.lumine.mythic.api.adapters.AbstractEntity;
import io.lumine.mythic.api.config.MythicLineConfig;
import io.lumine.mythic.api.skills.conditions.IEntityCondition;
import io.lumine.mythic.bukkit.BukkitAdapter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * MythicMobs custom condition:
 *   - haswardrobelayer{type=jacket;texture=demo_jacket.png} true
 *   - haswardrobelayer{type=mask_3d} true
 */
public final class HasWardrobeLayerCondition implements IEntityCondition {

    private final WardrobeManager wardrobeManager;
    private final String slotOrType;
    private final String expectedTexture;

    public HasWardrobeLayerCondition(WardrobeManager wardrobeManager, MythicLineConfig config) {
        this.wardrobeManager = wardrobeManager;
        this.slotOrType = config.getString(new String[]{"type", "slot", "layer", "s"}, "").trim();
        this.expectedTexture = config.getString(new String[]{"texture", "tex", "file", "item", "t"}, "").trim();
    }

    @Override
    public boolean check(AbstractEntity target) {
        Entity bukkitEntity = BukkitAdapter.adapt(target);
        if (!(bukkitEntity instanceof Player player)) {
            return false;
        }
        if (slotOrType.isEmpty()) return false;
        return wardrobeManager.hasLayerOrEquippedItem(player, slotOrType, expectedTexture);
    }
}
