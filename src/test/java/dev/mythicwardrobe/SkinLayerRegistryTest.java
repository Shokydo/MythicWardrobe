package dev.mythicwardrobe;

import dev.mythicwardrobe.model.SkinLayerDef;
import dev.mythicwardrobe.model.SkinModelVariant;
import dev.mythicwardrobe.skin.SkinLayerRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class SkinLayerRegistryTest {

    @Test
    void resolvesWideAndSlimVariantsAndSuggestions(@TempDir Path tempDir) throws Exception {
        SkinLayerRegistry registry = new SkinLayerRegistry(tempDir.toFile(), Logger.getLogger("test"));
        List<SkinLayerDef> layers = List.of(
                new SkinLayerDef("body_skin", 0, "body_skin", true, Set.of(), List.of("body")),
                new SkinLayerDef("jacket", 30, "jackets", false, Set.of(), List.of())
        );
        registry.ensureDirectories(layers, List.of(), false);

        File wideSkin = new File(registry.getOverlaysDir(), "body_skin/wide/pale.png");
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB), "png", wideSkin);

        File slimSkin = new File(registry.getOverlaysDir(), "body_skin/slim/pale.png");
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB), "png", slimSkin);

        File defaultCoat = new File(registry.getOverlaysDir(), "jackets/coat.png");
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB), "png", defaultCoat);

        File slimCoat = new File(registry.getOverlaysDir(), "jackets/slim/coat.png");
        slimCoat.getParentFile().mkdirs();
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB), "png", slimCoat);

        assertEquals(4, registry.reload());

        // body_skin resolves wide vs slim automatically from bare name "pale.png"
        var wideResolved = registry.resolve("body_skin", "pale.png", SkinModelVariant.CLASSIC).orElseThrow();
        assertEquals("body_skin/wide/pale.png", wideResolved.relativeKey());

        var slimResolved = registry.resolve("body_skin", "pale.png", SkinModelVariant.SLIM).orElseThrow();
        assertEquals("body_skin/slim/pale.png", slimResolved.relativeKey());

        // jacket resolves slim/coat.png for SLIM and coat.png for CLASSIC
        assertEquals("jackets/coat.png", registry.resolve("jackets", "coat.png", SkinModelVariant.CLASSIC).orElseThrow().relativeKey());
        assertEquals("jackets/slim/coat.png", registry.resolve("jackets", "coat.png", SkinModelVariant.SLIM).orElseThrow().relativeKey());

        // Suggestions collapse wide/slim into clean name "pale.png"
        assertEquals(List.of("pale.png"), registry.getSuggestionsForFolder("body_skin"));
    }
}
