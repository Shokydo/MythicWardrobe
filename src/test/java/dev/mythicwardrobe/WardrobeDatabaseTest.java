package dev.mythicwardrobe;

import dev.mythicwardrobe.model.SignedSkinData;
import dev.mythicwardrobe.model.SkinModelVariant;
import dev.mythicwardrobe.storage.WardrobeDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class WardrobeDatabaseTest {

    @Test
    void persistsRawSlotsSkillLayersBaseSkinAndMineSkinCache(@TempDir Path tempDir) throws Exception {
        try (WardrobeDatabase db = new WardrobeDatabase(tempDir.resolve("wardrobe.db").toFile(), Logger.getLogger("test"))) {
            db.init();

            UUID uuid = UUID.randomUUID();
            byte[] payload = new byte[]{1, 2, 3, 4, 5};
            db.saveRawSlotItem(uuid, "jacket", payload);
            assertArrayEquals(payload, db.loadRawSlotItems(uuid).get("jacket"));

            db.saveRawSlotItem(uuid, "jacket", null);
            assertFalse(db.loadRawSlotItems(uuid).containsKey("jacket"));

            db.saveSkillLayers(uuid, Map.of("jacket", "jackets/demo_jacket.png", "mask_skin", "masks/demo_mask.png"));
            Map<String, String> loadedLayers = db.loadSkillLayers(uuid);
            assertEquals("jackets/demo_jacket.png", loadedLayers.get("jacket"));
            assertEquals("masks/demo_mask.png", loadedLayers.get("mask_skin"));

            SignedSkinData base = new SignedSkinData("valBase", "sigBase", "https://textures.minecraft.net/texture/1", SkinModelVariant.SLIM);
            db.saveBaseSkin(uuid, base);
            var loadedBase = db.loadBaseSkin(uuid).orElseThrow();
            assertEquals(base, loadedBase);

            SignedSkinData signed = new SignedSkinData("valComposed", "sigComposed", "https://textures.minecraft.net/texture/2", SkinModelVariant.CLASSIC);
            db.putCachedSkin("deadbeef", signed);
            var cached = db.getCachedSkin("deadbeef").orElseThrow();
            assertEquals(signed, cached);

            // BodyType persistence (slim / wide / reset to auto)
            assertTrue(db.loadBodyType(uuid).isEmpty());
            db.saveBodyType(uuid, SkinModelVariant.SLIM);
            assertEquals(SkinModelVariant.SLIM, db.loadBodyType(uuid).orElseThrow());
            db.saveBodyType(uuid, SkinModelVariant.CLASSIC);
            assertEquals(SkinModelVariant.CLASSIC, db.loadBodyType(uuid).orElseThrow());
            db.saveBodyType(uuid, null);
            assertTrue(db.loadBodyType(uuid).isEmpty());
        }
    }
}
