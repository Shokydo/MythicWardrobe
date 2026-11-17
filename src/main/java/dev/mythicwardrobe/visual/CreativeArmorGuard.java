package dev.mythicwardrobe.visual;

import org.bukkit.inventory.EquipmentSlot;

/**
 * Pure, Netty-thread-safe decision engine that lets cosmetic & 3D armor render identically
 * in Creative mode as in Survival mode while preventing real armor from ever being deleted,
 * overwritten by a visual proxy packet, or duplicated into the player's inventory.
 */
public final class CreativeArmorGuard {

    /** Key mirrored in Bukkit PersistentDataContainer (PublicBukkitValues). */
    public static final String VISUAL_PROXY_KEY_STRING = "mythicwardrobe:visual_proxy";

    /** Top-level flag inside minecraft:custom_data for zero-overhead Netty packet checks. */
    public static final String VISUAL_PROXY_NBT_FLAG = "mythicwardrobe_visual_proxy";

    public enum Decision {
        /** Normal Creative action — let the server process it as usual. */
        ALLOW,
        /** Cancel packet, clear client cursor, and re-sync visual armor slots (5..8). */
        CANCEL_AND_RESYNC_ARMOR,
        /** Cancel packet, clear client cursor, and re-sync full player inventory + visual armor. */
        CANCEL_AND_RESYNC_INVENTORY,
        /**
         * Incoming real (non-proxy, non-empty) item placed onto an armor slot (5..8) that currently
         * displays a visual override: allow server-side handling via InventoryCreativeEvent where the
         * previous real armor is safely preserved (never deleted) and visuals are refreshed.
         */
        ALLOW_REAL_EQUIP_PRESERVE_OLD
    }

    private CreativeArmorGuard() {
    }

    /**
     * Evaluates an incoming Client -> Server CREATIVE_INVENTORY_ACTION packet.
     *
     * @param containerSlot         protocol slot (5=HEAD, 6=CHEST, 7=LEGS, 8=FEET, 9..45=inv, -1=drop)
     * @param incomingEmpty         true if incoming item is null or AIR
     * @param incomingIsVisualProxy true if incoming item carries the mythicwardrobe visual_proxy marker
     * @param slotHasVisualOverride true if containerSlot is 5..8 and currently has an active visual override
     */
    public static Decision evaluatePacketAction(
            int containerSlot,
            boolean incomingEmpty,
            boolean incomingIsVisualProxy,
            boolean slotHasVisualOverride
    ) {
        boolean isArmorSlot = containerSlotToEquipmentSlot(containerSlot) != null;

        // 1. NEVER allow a visual_proxy packet item to overwrite ANY slot or be dropped/duplicated!
        if (incomingIsVisualProxy) {
            return isArmorSlot ? Decision.CANCEL_AND_RESYNC_ARMOR : Decision.CANCEL_AND_RESYNC_INVENTORY;
        }

        // 2. For armor slots 5..8 that currently show a visual override (cosmetic / combo / 3D overlay):
        if (isArmorSlot && slotHasVisualOverride) {
            if (incomingEmpty) {
                // Prevent deleting real armor or clearing the visual slot when clicking slot 5..8
                // or pressing Shift+Click on the Creative Destroy Item button!
                return Decision.CANCEL_AND_RESYNC_ARMOR;
            }
            // Player placed a real (non-proxy) item into this armor slot in Creative:
            // let InventoryCreativeEvent safely preserve any existing real armor and refresh visuals.
            return Decision.ALLOW_REAL_EQUIP_PRESERVE_OLD;
        }

        return Decision.ALLOW;
    }

    public static EquipmentSlot containerSlotToEquipmentSlot(int containerSlot) {
        return switch (containerSlot) {
            case 5 -> EquipmentSlot.HEAD;
            case 6 -> EquipmentSlot.CHEST;
            case 7 -> EquipmentSlot.LEGS;
            case 8 -> EquipmentSlot.FEET;
            default -> null;
        };
    }
}
