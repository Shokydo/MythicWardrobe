package dev.mythicwardrobe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mythicwardrobe.visual.HeadCompositePackBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class HeadCompositePackBuilderTest {

    @Test
    void compilesCompositeHeadItemFromMythicArmorsPackZip(@TempDir Path temp) throws Exception {
        Path srcPack = temp.resolve("pack.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(srcPack))) {
            writeEntry(zos, "pack.mcmeta", "{\"pack\":{\"pack_format\":46,\"description\":\"Test\"}}");
            writeEntry(zos, "assets/mythicarmor/items/hair_spiky_3d_head_piece.json",
                    "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"mythicarmor:item/hair_spiky_3d_head_piece\",\"tints\":[{\"type\":\"minecraft:dye\",\"default\":16777215}]}}");
            writeEntry(zos, "assets/mythicarmor/items/tactical_gasmask_head_piece.json",
                    "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"mythicarmor:item/tactical_gasmask_head_piece\"}}");
            writeEntry(zos, "assets/mythicarmor/items/tactical_warden_head_piece.json",
                    "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"mythicarmor:item/tactical_warden_head_piece\"}}");
            // Non-head chest piece MUST be ignored
            writeEntry(zos, "assets/mythicarmor/items/tactical_warden_chest_piece.json",
                    "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"mythicarmor:item/tactical_warden_chest_piece\"}}");
        }

        Path mergedZip = temp.resolve("merged_pack.zip");
        Path manifestPath = temp.resolve("head_composite_manifest.json");

        HeadCompositePackBuilder.BuildResult res = HeadCompositePackBuilder.buildFromMythicArmorsPack(
                srcPack, mergedZip, manifestPath, 6
        );

        assertEquals(3, res.modelCount());
        assertEquals(6, res.maxLayers());
        assertTrue(res.modelKeys().contains("mythicarmor:hair_spiky_3d_head_piece"));
        assertTrue(res.modelKeys().contains("mythicarmor:tactical_gasmask_head_piece"));
        assertTrue(res.modelKeys().contains("mythicarmor:tactical_warden_head_piece"));
        assertFalse(res.modelKeys().contains("mythicarmor:tactical_warden_chest_piece"));

        // Verify generated composite JSON inside merged_pack.zip
        try (ZipFile zf = new ZipFile(mergedZip.toFile())) {
            assertNotNull(zf.getEntry("pack.mcmeta"));
            ZipEntry compEntry = zf.getEntry(HeadCompositePackBuilder.COMPOSITE_ZIP_ENTRY);
            assertNotNull(compEntry);
            try (InputStream in = zf.getInputStream(compEntry)) {
                JsonObject root = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject model = root.getAsJsonObject("model");
                assertEquals("minecraft:composite", model.get("type").getAsString());
                JsonArray slots = model.getAsJsonArray("models");
                assertEquals(6, slots.size());
                JsonObject slot0 = slots.get(0).getAsJsonObject();
                assertEquals("minecraft:select", slot0.get("type").getAsString());
                assertEquals("minecraft:custom_model_data", slot0.get("property").getAsString());
                assertEquals(0, slot0.get("index").getAsInt());
                assertEquals(3, slot0.getAsJsonArray("cases").size());
                assertEquals("minecraft:empty", slot0.getAsJsonObject("fallback").get("type").getAsString());
            }
        }

        // Verify manifest reload
        HeadCompositePackBuilder.Manifest loaded = HeadCompositePackBuilder.loadManifest(manifestPath).orElseThrow();
        assertEquals(6, loaded.maxLayers());
        assertTrue(loaded.supportsAll(List.of(
                "mythicarmor:hair_spiky_3d_head_piece",
                "mythicarmor:tactical_gasmask_head_piece",
                "mythicarmor:tactical_warden_head_piece"
        )));
    }

    private static void writeEntry(ZipOutputStream zos, String name, String content) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }
}
