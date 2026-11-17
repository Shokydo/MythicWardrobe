package dev.mythicwardrobe.skin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mythicwardrobe.model.SkinModelVariant;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.List;

/**
 * Pure Java2D 64x64 Minecraft skin compositor with:
 *  - Full base-skin replacement support (e.g. `body_skin` with replacesBaseSkin=true)
 *  - Selective outer-layer UV clearing
 *  - Strict Z-order alpha compositing
 *  - Deterministic SHA-256 caching keys incorporating classic/slim arm variant
 */
public final class SkinCompositor {

    public record LayerInput(BufferedImage image, Set<String> clearOuterRegions, boolean replacesBaseSkin) {
        public LayerInput(BufferedImage image, Set<String> clearOuterRegions) {
            this(image, clearOuterRegions, false);
        }
    }

    public record ComposedSkin(byte[] pngBytes, String sha256Hash, SkinModelVariant variant) {
    }

    public record DecodedTextureProperty(String textureUrl, SkinModelVariant variant) {
    }

    // Standard 64x64 Minecraft skin UV outer-layer rectangles [x, y, w, h]
    private static final Map<String, int[]> OUTER_UV_REGIONS = Map.of(
            "HEAD_OUTER", new int[]{32, 0, 32, 16},
            "BODY_OUTER", new int[]{16, 32, 24, 16},
            "RIGHT_ARM_OUTER", new int[]{40, 32, 16, 16},
            "LEFT_ARM_OUTER", new int[]{48, 48, 16, 16},
            "RIGHT_LEG_OUTER", new int[]{0, 32, 16, 16},
            "LEFT_LEG_OUTER", new int[]{0, 48, 16, 16}
    );

    private SkinCompositor() {
    }

    /**
     * Composes the base 64x64 skin with ordered layers.
     * If any layer has replacesBaseSkin=true (e.g. body_skin), the highest-priority such layer
     * replaces the canvas base completely, and subsequent layers draw on top of it.
     */
    public static ComposedSkin compose(
            BufferedImage baseSkin,
            List<LayerInput> orderedLayers,
            SkinModelVariant variant,
            boolean clearAllOuterLayer
    ) throws IOException {
        Objects.requireNonNull(baseSkin, "baseSkin");
        List<LayerInput> layers = orderedLayers == null ? List.of() : orderedLayers;

        // Check whether a layer (such as `body_skin`) completely replaces the player's base skin
        int replaceIndex = -1;
        for (int i = 0; i < layers.size(); i++) {
            LayerInput li = layers.get(i);
            if (li != null && li.replacesBaseSkin() && li.image() != null) {
                replaceIndex = i;
            }
        }

        BufferedImage effectiveBase = replaceIndex >= 0 ? layers.get(replaceIndex).image() : baseSkin;
        if (effectiveBase.getHeight() == 32) {
            effectiveBase = upgradeLegacy64x32(effectiveBase);
        }

        List<LayerInput> overlaysToDraw = replaceIndex >= 0
                ? layers.subList(replaceIndex + 1, layers.size())
                : layers;

        BufferedImage canvas = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        try {
            // 1. Paint effective base skin pixels onto canvas
            g.setComposite(AlphaComposite.Src);
            g.drawImage(effectiveBase, 0, 0, null);

            // 2. Collect outer-layer regions to clear before drawing overlays
            Set<String> regionsToClear = new LinkedHashSet<>();
            if (clearAllOuterLayer && !overlaysToDraw.isEmpty()) {
                regionsToClear.addAll(OUTER_UV_REGIONS.keySet());
            } else {
                for (LayerInput layer : overlaysToDraw) {
                    if (layer == null || layer.clearOuterRegions() == null) continue;
                    for (String r : layer.clearOuterRegions()) {
                        String upper = r.toUpperCase(Locale.ROOT);
                        if ("ALL_OUTER".equals(upper)) {
                            regionsToClear.addAll(OUTER_UV_REGIONS.keySet());
                        } else if (OUTER_UV_REGIONS.containsKey(upper)) {
                            regionsToClear.add(upper);
                        }
                    }
                }
            }

            if (!regionsToClear.isEmpty()) {
                g.setComposite(AlphaComposite.Clear);
                for (String region : regionsToClear) {
                    int[] rect = OUTER_UV_REGIONS.get(region);
                    if (rect != null) {
                        g.fillRect(rect[0], rect[1], rect[2], rect[3]);
                    }
                }
            }

            // 3. Alpha-composite each overlay layer in strict Z-order
            g.setComposite(AlphaComposite.SrcOver);
            for (LayerInput layer : overlaysToDraw) {
                if (layer != null && layer.image() != null) {
                    g.drawImage(layer.image(), 0, 0, null);
                }
            }
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
        ImageIO.write(canvas, "png", baos);
        byte[] pngBytes = baos.toByteArray();
        String hash = sha256Hex(variant.getApiName(), pngBytes);
        return new ComposedSkin(pngBytes, hash, variant);
    }

    /**
     * Upgrades a legacy 64x32 Minecraft skin to modern 64x64 format by duplicating right arm/leg to left arm/leg.
     */
    public static BufferedImage upgradeLegacy64x32(BufferedImage legacy) {
        BufferedImage out = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.drawImage(legacy, 0, 0, null);
            g.drawImage(legacy.getSubimage(0, 16, 16, 16), 16, 48, null);
            g.drawImage(legacy.getSubimage(40, 16, 16, 16), 32, 48, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /**
     * Decodes a Mojang base64 textures property string to extract skin URL and Classic/Slim model variant.
     */
    public static Optional<DecodedTextureProperty> decodeTexturesProperty(String base64Value, SkinModelVariant fallbackVariant) {
        if (base64Value == null || base64Value.isBlank()) return Optional.empty();
        try {
            String jsonStr = new String(Base64.getDecoder().decode(base64Value), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(jsonStr).getAsJsonObject();
            if (!root.has("textures")) return Optional.empty();
            JsonObject textures = root.getAsJsonObject("textures");
            if (!textures.has("SKIN")) return Optional.empty();
            JsonObject skinObj = textures.getAsJsonObject("SKIN");
            String url = skinObj.has("url") ? skinObj.get("url").getAsString() : null;
            if (url == null || url.isBlank()) return Optional.empty();
            if (url.startsWith("http://textures.minecraft.net")) {
                url = "https://" + url.substring("http://".length());
            }
            SkinModelVariant variant = SkinModelVariant.CLASSIC;
            if (skinObj.has("metadata")) {
                JsonObject meta = skinObj.getAsJsonObject("metadata");
                if (meta.has("model") && "slim".equalsIgnoreCase(meta.get("model").getAsString())) {
                    variant = SkinModelVariant.SLIM;
                }
            }
            return Optional.of(new DecodedTextureProperty(url, variant));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static String sha256Hex(String variantPrefix, byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(variantPrefix.getBytes(StandardCharsets.UTF_8));
            md.update((byte) ':');
            md.update(data);
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >>> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
