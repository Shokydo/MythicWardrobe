package dev.mythicwardrobe;

import dev.mythicwardrobe.model.SkinModelVariant;
import dev.mythicwardrobe.skin.SkinCompositor;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SkinCompositorTest {

    @Test
    void layersCompositeInPriorityOrderAndClearOuterRegion() throws Exception {
        // Base skin with bright red pixel on BODY_OUTER (20, 36) and green on HEAD_OUTER (40, 8)
        BufferedImage base = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        base.setRGB(20, 36, new Color(255, 0, 0, 255).getRGB());
        base.setRGB(22, 36, new Color(255, 0, 0, 255).getRGB());
        base.setRGB(40, 8, new Color(0, 255, 0, 255).getRGB());

        // Undershirt draws blue at (20, 36) and clears BODY_OUTER
        BufferedImage undershirt = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        undershirt.setRGB(20, 36, new Color(0, 0, 255, 255).getRGB());

        // Jacket draws yellow on top of (20, 36)
        BufferedImage jacket = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        jacket.setRGB(20, 36, new Color(255, 255, 0, 255).getRGB());

        SkinCompositor.ComposedSkin composed = SkinCompositor.compose(
                base,
                List.of(
                        new SkinCompositor.LayerInput(undershirt, Set.of("BODY_OUTER")),
                        new SkinCompositor.LayerInput(jacket, Set.of("BODY_OUTER"))
                ),
                SkinModelVariant.CLASSIC,
                false
        );

        BufferedImage out = ImageIO.read(new ByteArrayInputStream(composed.pngBytes()));
        assertEquals(64, out.getWidth());
        assertEquals(64, out.getHeight());

        // (20, 36) should be jacket's yellow (on top of undershirt)
        assertEquals(new Color(255, 255, 0, 255).getRGB(), out.getRGB(20, 36));
        // (22, 36) was in BODY_OUTER and not drawn by clothing -> cleared to transparent!
        assertEquals(0, (out.getRGB(22, 36) >>> 24));
        // (40, 8) is in HEAD_OUTER -> untouched green!
        assertEquals(new Color(0, 255, 0, 255).getRGB(), out.getRGB(40, 8));

        // Deterministic hash check
        SkinCompositor.ComposedSkin composedAgain = SkinCompositor.compose(
                base,
                List.of(
                        new SkinCompositor.LayerInput(undershirt, Set.of("BODY_OUTER")),
                        new SkinCompositor.LayerInput(jacket, Set.of("BODY_OUTER"))
                ),
                SkinModelVariant.CLASSIC,
                false
        );
        assertEquals(composed.sha256Hash(), composedAgain.sha256Hash());

        // Slim variant must produce a different cache hash!
        SkinCompositor.ComposedSkin slim = SkinCompositor.compose(
                base,
                List.of(
                        new SkinCompositor.LayerInput(undershirt, Set.of("BODY_OUTER")),
                        new SkinCompositor.LayerInput(jacket, Set.of("BODY_OUTER"))
                ),
                SkinModelVariant.SLIM,
                false
        );
        assertNotEquals(composed.sha256Hash(), slim.sha256Hash());
    }

    @Test
    void upgradesLegacy64x32Skin() {
        BufferedImage legacy = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        int armColor = new Color(120, 80, 40, 255).getRGB();
        legacy.setRGB(44, 20, armColor); // Right arm

        BufferedImage upgraded = SkinCompositor.upgradeLegacy64x32(legacy);
        assertEquals(64, upgraded.getWidth());
        assertEquals(64, upgraded.getHeight());
        // Right arm (40+4, 16+4) copied to Left arm (32+4, 48+4) = (36, 52)
        assertEquals(armColor, upgraded.getRGB(36, 52));
    }

    @Test
    void bodySkinLayerReplacesOriginalBaseSkinCompletely() throws Exception {
        // Original Mojang skin has bright magenta everywhere
        BufferedImage mojangBase = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        mojangBase.setRGB(10, 10, new Color(255, 0, 255, 255).getRGB());
        mojangBase.setRGB(20, 22, new Color(255, 0, 255, 255).getRGB());

        // body_skin has peach skin tone at (10,10) and transparent at (20,22)
        BufferedImage bodySkin = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        int peach = new Color(235, 198, 168, 255).getRGB();
        bodySkin.setRGB(10, 10, peach);

        // eyes layer draws blue at (11, 11)
        BufferedImage eyes = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        int blue = new Color(40, 120, 220, 255).getRGB();
        eyes.setRGB(11, 11, blue);

        SkinCompositor.ComposedSkin composed = SkinCompositor.compose(
                mojangBase,
                List.of(
                        new SkinCompositor.LayerInput(bodySkin, Set.of(), true),
                        new SkinCompositor.LayerInput(eyes, Set.of(), false)
                ),
                SkinModelVariant.SLIM,
                false
        );

        BufferedImage out = ImageIO.read(new ByteArrayInputStream(composed.pngBytes()));
        // (10,10) must be peach from body_skin
        assertEquals(peach, out.getRGB(10, 10));
        // (20,22) must NOT leak magenta from old mojangBase (alpha = 0)
        assertEquals(0, (out.getRGB(20, 22) >>> 24));
        // (11,11) must be blue eyes drawn on top of body_skin
        assertEquals(blue, out.getRGB(11, 11));
    }

    @Test
    void decodesMojangBase64TexturesProperty() {
        String slimJson = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/abc123\",\"metadata\":{\"model\":\"slim\"}}}}";
        String b64 = Base64.getEncoder().encodeToString(slimJson.getBytes(StandardCharsets.UTF_8));

        var decoded = SkinCompositor.decodeTexturesProperty(b64, SkinModelVariant.CLASSIC).orElseThrow();
        assertEquals("https://textures.minecraft.net/texture/abc123", decoded.textureUrl());
        assertEquals(SkinModelVariant.SLIM, decoded.variant());
    }
}
