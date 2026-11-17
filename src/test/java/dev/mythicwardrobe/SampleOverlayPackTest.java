package dev.mythicwardrobe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mythicwardrobe.model.SkinLayerDef;
import dev.mythicwardrobe.model.SkinModelVariant;
import dev.mythicwardrobe.skin.SampleOverlayPackGenerator;
import dev.mythicwardrobe.skin.SkinCompositor;
import dev.mythicwardrobe.skin.SkinLayerRegistry;
import dev.mythicwardrobe.web.WebEditorServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class SampleOverlayPackTest {

    @Test
    void generatesCompleteStarterPackAcrossAll14CategoriesAndComposes(@TempDir Path tempDir) throws Exception {
        Logger log = Logger.getLogger("test");
        SkinLayerRegistry registry = new SkinLayerRegistry(tempDir.toFile(), log);

        List<SkinLayerDef> layers = List.of(
                new SkinLayerDef("body_skin", 0, "body_skin", true, Set.of(), List.of("body")),
                new SkinLayerDef("eyes", 5, "eyes", false, Set.of(), List.of()),
                new SkinLayerDef("eyebrows", 8, "eyebrows", false, Set.of(), List.of()),
                new SkinLayerDef("hair", 10, "hairs", false, Set.of("HEAD_OUTER"), List.of()),
                new SkinLayerDef("beard", 12, "beards", false, Set.of(), List.of()),
                new SkinLayerDef("scars", 14, "scars", false, Set.of(), List.of()),
                new SkinLayerDef("pants", 15, "pants", false, Set.of("RIGHT_LEG_OUTER", "LEFT_LEG_OUTER"), List.of()),
                new SkinLayerDef("belt_skin", 18, "belts", false, Set.of(), List.of("belt")),
                new SkinLayerDef("undershirt", 20, "undershirts", false, Set.of("BODY_OUTER"), List.of()),
                new SkinLayerDef("socks", 25, "socks", false, Set.of(), List.of()),
                new SkinLayerDef("jacket", 30, "jackets", false, Set.of("BODY_OUTER", "RIGHT_ARM_OUTER", "LEFT_ARM_OUTER"), List.of()),
                new SkinLayerDef("boots_skin", 35, "boots", false, Set.of(), List.of()),
                new SkinLayerDef("gloves", 40, "gloves", false, Set.of(), List.of()),
                new SkinLayerDef("mask_skin", 60, "masks", false, Set.of(), List.of()),
                new SkinLayerDef("hat", 70, "hats", false, Set.of(), List.of())
        );

        registry.ensureDirectories(layers, List.of(), true);
        int count = registry.reload();
        assertTrue(count >= 68, "Expected at least 68 starter PNGs across all 15 categories (including hazmat_suit), got " + count);

        // Verify Hazmat Suit wide + slim variants exist in jackets/
        assertTrue(registry.resolve("jackets", "hazmat_suit.png", SkinModelVariant.CLASSIC).isPresent());
        assertTrue(registry.resolve("jackets", "hazmat_suit.png", SkinModelVariant.SLIM).isPresent());

        // Verify EVERY SINGLE ONE of the 15 categories has at least 2 distinct selectable textures!
        JsonArray json = WebEditorServer.buildLayersJson(layers, registry);
        assertEquals(15, json.size());
        for (int i = 0; i < json.size(); i++) {
            JsonObject obj = json.get(i).getAsJsonObject();
            String id = obj.get("id").getAsString();
            int texCount = obj.getAsJsonArray("textures").size();
            assertTrue(texCount >= 2, "Category '" + id + "' must have at least 2 variations, found " + texCount);
        }

        // Verify wide/slim resolution for body_skin, jackets, and gloves
        var wideBody = registry.resolve("body_skin", "light.png", SkinModelVariant.CLASSIC).orElseThrow();
        var slimBody = registry.resolve("body_skin", "light.png", SkinModelVariant.SLIM).orElseThrow();
        assertTrue(wideBody.relativeKey().contains("wide/"));
        assertTrue(slimBody.relativeKey().contains("slim/"));

        var wideGloves = registry.resolve("gloves", "tactical_black.png", SkinModelVariant.CLASSIC).orElseThrow();
        var slimGloves = registry.resolve("gloves", "tactical_black.png", SkinModelVariant.SLIM).orElseThrow();
        assertTrue(wideGloves.relativeKey().contains("wide/"));
        assertTrue(slimGloves.relativeKey().contains("slim/"));

        // Verify second call to generateMissingCategories(forceAll=false) writes 0 files when already populated
        assertEquals(0, SampleOverlayPackGenerator.generateMissingCategories(registry.getOverlaysDir(), log, false));

        // Full multi-layer compose test (Wide vs Slim produce distinct valid 64x64 PNGs)
        var eyes = registry.resolve("eyes", "blue.png", SkinModelVariant.CLASSIC).orElseThrow();
        var brows = registry.resolve("eyebrows", "dark_thick.png", SkinModelVariant.CLASSIC).orElseThrow();
        var beard = registry.resolve("beards", "full_beard.png", SkinModelVariant.CLASSIC).orElseThrow();
        var scar = registry.resolve("scars", "eye_scar.png", SkinModelVariant.CLASSIC).orElseThrow();
        var hat = registry.resolve("hats", "ushanka_brown.png", SkinModelVariant.CLASSIC).orElseThrow();

        var composedWide = SkinCompositor.compose(
                new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB),
                List.of(
                        new SkinCompositor.LayerInput(wideBody.image(), Set.of(), true),
                        new SkinCompositor.LayerInput(eyes.image(), Set.of(), false),
                        new SkinCompositor.LayerInput(brows.image(), Set.of(), false),
                        new SkinCompositor.LayerInput(beard.image(), Set.of(), false),
                        new SkinCompositor.LayerInput(scar.image(), Set.of(), false),
                        new SkinCompositor.LayerInput(wideGloves.image(), Set.of(), false),
                        new SkinCompositor.LayerInput(hat.image(), Set.of(), false)
                ),
                SkinModelVariant.CLASSIC,
                false
        );

        var composedSlim = SkinCompositor.compose(
                new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB),
                List.of(
                        new SkinCompositor.LayerInput(slimBody.image(), Set.of(), true),
                        new SkinCompositor.LayerInput(eyes.image(), Set.of(), false),
                        new SkinCompositor.LayerInput(brows.image(), Set.of(), false),
                        new SkinCompositor.LayerInput(beard.image(), Set.of(), false),
                        new SkinCompositor.LayerInput(scar.image(), Set.of(), false),
                        new SkinCompositor.LayerInput(slimGloves.image(), Set.of(), false),
                        new SkinCompositor.LayerInput(hat.image(), Set.of(), false)
                ),
                SkinModelVariant.SLIM,
                false
        );

        BufferedImage wideImg = ImageIO.read(new ByteArrayInputStream(composedWide.pngBytes()));
        BufferedImage slimImg = ImageIO.read(new ByteArrayInputStream(composedSlim.pngBytes()));
        assertNotNull(wideImg);
        assertNotNull(slimImg);
        assertEquals(64, wideImg.getWidth());
        assertEquals(64, wideImg.getHeight());
        assertNotEquals(composedWide.sha256Hash(), composedSlim.sha256Hash());
    }
}
