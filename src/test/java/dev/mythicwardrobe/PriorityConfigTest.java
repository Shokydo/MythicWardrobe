package dev.mythicwardrobe;

import dev.mythicwardrobe.config.WardrobeConfig;
import dev.mythicwardrobe.model.SkinLayerDef;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PriorityConfigTest {

    @Test
    void topToBottomPriorityOrderMapsTopHighestAndBottomLowest() {
        Map<String, SkinLayerDef> layers = new LinkedHashMap<>();
        layers.put("body_skin", new SkinLayerDef("body_skin", 0, "body", true, Set.of(), List.of("body")));
        layers.put("eyes", new SkinLayerDef("eyes", 5, "eyes", false, Set.of(), List.of()));
        layers.put("eyebrows", new SkinLayerDef("eyebrows", 8, "eyebrows", false, Set.of(), List.of("brows")));
        layers.put("hair", new SkinLayerDef("hair", 45, "hair", false, Set.of(), List.of()));
        layers.put("mask_skin", new SkinLayerDef("mask_skin", 60, "masks", false, Set.of(), List.of("mask")));

        Map<String, String> aliases = Map.of(
                "body", "body_skin",
                "brows", "eyebrows",
                "mask", "mask_skin"
        );

        // User reverses hair above mask, and brows above hair!
        WardrobeConfig.applyPriorityOrder(layers, aliases, List.of("brows", "hair", "mask", "eyes", "body"));

        List<SkinLayerDef> drawOrder = new ArrayList<>(layers.values());
        drawOrder.sort(Comparator.comparingInt(SkinLayerDef::priority));

        // Draw order is bottom -> top (lowest priority first, highest priority last on top!)
        assertEquals(List.of("body_skin", "eyes", "mask_skin", "hair", "eyebrows"),
                drawOrder.stream().map(SkinLayerDef::id).toList());
        assertEquals(0, layers.get("body_skin").priority());
        assertTrue(layers.get("eyebrows").priority() > layers.get("hair").priority());
        assertTrue(layers.get("hair").priority() > layers.get("mask_skin").priority());
        assertTrue(layers.get("mask_skin").priority() > layers.get("eyes").priority());
    }

    @Test
    void partialPriorityListKeepsBodyAtBottomAndCustomUnlistedLayersBetween() {
        Map<String, SkinLayerDef> layers = new LinkedHashMap<>();
        layers.put("body_skin", new SkinLayerDef("body_skin", 0, "body", true, Set.of(), List.of("body")));
        layers.put("jacket", new SkinLayerDef("jacket", 30, "jackets", false, Set.of(), List.of()));
        layers.put("eyes", new SkinLayerDef("eyes", 5, "eyes", false, Set.of(), List.of()));
        layers.put("eyebrows", new SkinLayerDef("eyebrows", 8, "eyebrows", false, Set.of(), List.of("brows")));
        layers.put("hair", new SkinLayerDef("hair", 45, "hair", false, Set.of(), List.of()));
        layers.put("mask_skin", new SkinLayerDef("mask_skin", 60, "masks", false, Set.of(), List.of("mask")));

        Map<String, String> aliases = Map.of("body", "body_skin", "brows", "eyebrows", "mask", "mask_skin");

        WardrobeConfig.applyPriorityOrder(layers, aliases, List.of("mask", "hair", "brows", "eyes", "body"));

        assertEquals(0, layers.get("body_skin").priority());
        assertEquals(30, layers.get("jacket").priority()); // Unlisted layer keeps layers.yml priority above body_skin!
        assertTrue(layers.get("mask_skin").priority() > layers.get("hair").priority());
        assertTrue(layers.get("hair").priority() > layers.get("eyebrows").priority());
        assertTrue(layers.get("eyebrows").priority() > layers.get("eyes").priority());
        assertTrue(layers.get("eyes").priority() > layers.get("jacket").priority());
    }
}
