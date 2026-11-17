package dev.mythicwardrobe;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mythicwardrobe.visual.BBModelMerger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BBModelMergerTest {

    @Test
    void mergesTwoMythicArmorsModelsIntoValidStitchedBBModel(@TempDir Path tempDir) throws Exception {
        Path modelsDir = Path.of("mythicarmors-test-pack/plugins/MythicArmors/models");
        Path vest = modelsDir.resolve("tactical_warden.bbmodel");
        Path backpack = modelsDir.resolve("tactical_backpack.bbmodel");
        assertTrue(Files.isRegularFile(vest), "Expected tactical_warden.bbmodel in workspace");
        assertTrue(Files.isRegularFile(backpack), "Expected tactical_backpack.bbmodel in workspace");

        Path out = tempDir.resolve("custom_vest_pack.bbmodel");
        BBModelMerger.MergeResult res = BBModelMerger.merge(vest, backpack, out, "custom_vest_pack");

        assertTrue(Files.isRegularFile(out));
        assertEquals(72, res.totalCubes()); // 46 + 26 cubes
        assertEquals(9, res.totalBones());  // 9 canonical bones merged cleanly
        assertEquals(128, res.atlasWidth()); // 64 + 64 stitched atlas
        assertEquals(64, res.atlasHeight());

        JsonObject parsed = JsonParser.parseString(Files.readString(out, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("custom_vest_pack", parsed.get("name").getAsString());
        assertEquals(128, parsed.getAsJsonObject("resolution").get("width").getAsInt());
        assertEquals(72, parsed.getAsJsonArray("elements").size());
        assertEquals(9, parsed.getAsJsonArray("outliner").size());
        assertTrue(parsed.getAsJsonArray("textures").size() >= 2);
    }
}
