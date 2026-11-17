package dev.mythicwardrobe.visual;

import com.google.gson.*;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;

/**
 * Merges any two MythicArmors Blockbench (.bbmodel) armor models into a single native,
 * zero-lag combo .bbmodel (e.g. Chestplate + Backpack, or Helmet + Gas Mask):
 *   - Stitches main textures (and optional _e.png emissive textures) side-by-side
 *   - Rescales & offsets face UVs cleanly regardless of source resolutions
 *   - Merges canonical MythicArmors bones (head, body, left_arm, right_arm, waist, legs, feet)
 *   - Preserves 2D fallback layers & 16x16 inventory icons renamed to the output model ID
 */
public final class BBModelMerger {

    public record MergeResult(
            Path outputFile,
            int totalCubes,
            int totalBones,
            int atlasWidth,
            int atlasHeight
    ) {}

    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

    private BBModelMerger() {}

    public static MergeResult merge(Path fileA, Path fileB, Path outputFile, String outputName) throws IOException {
        if (!Files.isRegularFile(fileA)) {
            throw new IOException("Модель A не найдена: " + fileA);
        }
        if (!Files.isRegularFile(fileB)) {
            throw new IOException("Модель B не найдена: " + fileB);
        }

        JsonObject jsonA = JsonParser.parseString(Files.readString(fileA, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject jsonB = JsonParser.parseString(Files.readString(fileB, StandardCharsets.UTF_8)).getAsJsonObject();

        String cleanName = sanitizeName(outputName);

        // 1. Decode main textures (index 0) and optional emissives (*_e.png)
        BufferedImage mainA = decodeTextureAt(jsonA, 0);
        BufferedImage mainB = decodeTextureAt(jsonB, 0);
        if (mainA == null || mainB == null) {
            throw new IOException("Обе модели должны содержать встроенную основную текстуру (data:image/png;base64,...)");
        }

        int wA = mainA.getWidth();
        int hA = mainA.getHeight();
        int wB = mainB.getWidth();
        int hB = mainB.getHeight();

        int outW = wA + wB;
        int outH = Math.max(hA, hB);

        BufferedImage stitchedMain = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gm = stitchedMain.createGraphics();
        gm.drawImage(mainA, 0, 0, null);
        gm.drawImage(mainB, wA, 0, null);
        gm.dispose();

        BufferedImage emisA = findEmissiveTexture(jsonA);
        BufferedImage emisB = findEmissiveTexture(jsonB);
        BufferedImage stitchedEmis = null;
        if (emisA != null || emisB != null) {
            stitchedEmis = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D ge = stitchedEmis.createGraphics();
            if (emisA != null) ge.drawImage(emisA, 0, 0, wA, hA, null);
            if (emisB != null) ge.drawImage(emisB, wA, 0, wB, hB, null);
            ge.dispose();
        }

        // Source UV resolutions
        double resWA = getResolution(jsonA, "width", wA);
        double resHA = getResolution(jsonA, "height", hA);
        double resWB = getResolution(jsonB, "width", wB);
        double resHB = getResolution(jsonB, "height", hB);

        // 2. Collect elements with fresh UUIDs and remapped UVs + bone ownership map
        Map<String, String> uuidRemapA = new HashMap<>();
        Map<String, String> uuidRemapB = new HashMap<>();
        JsonArray mergedElements = new JsonArray();

        remapAndAppendElements(jsonA, mergedElements, uuidRemapA, wA / resWA, hA / resHA, 0.0, 0.0);
        remapAndAppendElements(jsonB, mergedElements, uuidRemapB, wB / resWB, hB / resHB, wA, 0.0);

        // 3. Merge bones (outliner groups) by canonical bone name
        Map<String, JsonObject> mergedGroups = new LinkedHashMap<>();
        mergeOutliner(jsonA, mergedGroups, uuidRemapA);
        mergeOutliner(jsonB, mergedGroups, uuidRemapB);

        JsonArray outliner = new JsonArray();
        for (JsonObject grp : mergedGroups.values()) {
            outliner.add(grp);
        }

        // 4. Build merged textures array (stitched main, optional stitched _e.png, plus A's fallback & icons)
        JsonArray textures = new JsonArray();
        textures.add(buildTextureEntry(cleanName + "_main.png", stitchedMain, 0, outW, outH));
        int nextId = 1;
        if (stitchedEmis != null) {
            textures.add(buildTextureEntry(cleanName + "_main_e.png", stitchedEmis, nextId++, outW, outH));
        }

        // Copy auxiliary textures (layer_1, layer_2, helmet/chestplate/leggings/boots icons) from A, falling back to B
        Set<String> copiedSuffixes = new HashSet<>();
        for (JsonObject src : new JsonObject[]{jsonA, jsonB}) {
            JsonArray srcTexs = src.getAsJsonArray("textures");
            if (srcTexs == null) continue;
            for (int i = 1; i < srcTexs.size(); i++) {
                JsonObject t = srcTexs.get(i).getAsJsonObject();
                String tName = t.has("name") ? t.get("name").getAsString().toLowerCase(Locale.ROOT) : "";
                if (tName.endsWith("_e.png") || tName.endsWith("_main.png")) continue;
                String suffix = extractAuxiliarySuffix(tName);
                if (suffix == null || !copiedSuffixes.add(suffix)) continue;
                JsonObject copy = t.deepCopy();
                String newFile = cleanName + "_" + suffix;
                copy.addProperty("name", newFile);
                copy.addProperty("path", newFile);
                copy.addProperty("id", String.valueOf(nextId++));
                copy.addProperty("uuid", UUID.randomUUID().toString());
                textures.add(copy);
            }
        }

        // 5. Assemble final MythicArmors-compatible .bbmodel JSON
        JsonObject out = new JsonObject();
        JsonObject meta = new JsonObject();
        meta.addProperty("format_version", "4.10");
        meta.addProperty("model_format", "free");
        meta.addProperty("box_uv", false);
        out.add("meta", meta);
        out.addProperty("name", cleanName);
        out.addProperty("model_identifier", cleanName);
        out.add("visible_box", jsonA.has("visible_box") ? jsonA.get("visible_box").deepCopy() : defaultVisibleBox());

        JsonObject res = new JsonObject();
        res.addProperty("width", outW);
        res.addProperty("height", outH);
        out.add("resolution", res);

        out.add("elements", mergedElements);
        out.add("outliner", outliner);
        out.add("textures", textures);

        if (outputFile.getParent() != null) {
            Files.createDirectories(outputFile.getParent());
        }
        Files.writeString(outputFile, PRETTY_GSON.toJson(out), StandardCharsets.UTF_8);

        return new MergeResult(outputFile, mergedElements.size(), mergedGroups.size(), outW, outH);
    }

    private static void remapAndAppendElements(
            JsonObject src,
            JsonArray outElements,
            Map<String, String> uuidRemap,
            double scaleU,
            double scaleV,
            double offsetU,
            double offsetV
    ) {
        JsonArray elements = src.getAsJsonArray("elements");
        if (elements == null) return;
        for (JsonElement el : elements) {
            if (!el.isJsonObject()) continue;
            JsonObject cube = el.getAsJsonObject().deepCopy();
            String oldUuid = cube.has("uuid") ? cube.get("uuid").getAsString() : UUID.randomUUID().toString();
            String newUuid = UUID.randomUUID().toString();
            uuidRemap.put(oldUuid, newUuid);
            cube.addProperty("uuid", newUuid);

            JsonObject faces = cube.getAsJsonObject("faces");
            if (faces != null) {
                for (Map.Entry<String, JsonElement> fe : faces.entrySet()) {
                    if (!fe.getValue().isJsonObject()) continue;
                    JsonObject face = fe.getValue().getAsJsonObject();
                    face.addProperty("texture", 0);
                    JsonArray uv = face.getAsJsonArray("uv");
                    if (uv != null && uv.size() == 4) {
                        double u0 = uv.get(0).getAsDouble() * scaleU + offsetU;
                        double v0 = uv.get(1).getAsDouble() * scaleV + offsetV;
                        double u1 = uv.get(2).getAsDouble() * scaleU + offsetU;
                        double v1 = uv.get(3).getAsDouble() * scaleV + offsetV;
                        JsonArray newUv = new JsonArray();
                        newUv.add(round2(u0));
                        newUv.add(round2(v0));
                        newUv.add(round2(u1));
                        newUv.add(round2(v1));
                        face.add("uv", newUv);
                    }
                }
            }
            outElements.add(cube);
        }
    }

    private static void mergeOutliner(
            JsonObject src,
            Map<String, JsonObject> mergedGroups,
            Map<String, String> uuidRemap
    ) {
        JsonArray outliner = src.getAsJsonArray("outliner");
        if (outliner == null) return;
        for (JsonElement item : outliner) {
            if (!item.isJsonObject()) continue;
            JsonObject grp = item.getAsJsonObject();
            String name = grp.has("name") ? grp.get("name").getAsString() : "body";
            String key = name.toLowerCase(Locale.ROOT);

            JsonObject target = mergedGroups.get(key);
            if (target == null) {
                target = grp.deepCopy();
                target.addProperty("uuid", UUID.randomUUID().toString());
                target.add("children", new JsonArray());
                mergedGroups.put(key, target);
            }

            JsonArray targetChildren = target.getAsJsonArray("children");
            JsonArray srcChildren = grp.getAsJsonArray("children");
            if (srcChildren != null) {
                for (JsonElement ch : srcChildren) {
                    if (ch.isJsonPrimitive()) {
                        String mapped = uuidRemap.get(ch.getAsString());
                        if (mapped != null) {
                            targetChildren.add(mapped);
                        }
                    } else if (ch.isJsonObject()) {
                        // Recursively remap any nested sub-group UUIDs
                        targetChildren.add(remapNestedGroup(ch.getAsJsonObject(), uuidRemap));
                    }
                }
            }
        }
    }

    private static JsonObject remapNestedGroup(JsonObject sub, Map<String, String> uuidRemap) {
        JsonObject copy = sub.deepCopy();
        copy.addProperty("uuid", UUID.randomUUID().toString());
        JsonArray newChildren = new JsonArray();
        JsonArray oldChildren = sub.getAsJsonArray("children");
        if (oldChildren != null) {
            for (JsonElement ch : oldChildren) {
                if (ch.isJsonPrimitive()) {
                    String mapped = uuidRemap.get(ch.getAsString());
                    if (mapped != null) newChildren.add(mapped);
                } else if (ch.isJsonObject()) {
                    newChildren.add(remapNestedGroup(ch.getAsJsonObject(), uuidRemap));
                }
            }
        }
        copy.add("children", newChildren);
        return copy;
    }

    private static BufferedImage decodeTextureAt(JsonObject model, int index) {
        JsonArray texs = model.getAsJsonArray("textures");
        if (texs == null || index < 0 || index >= texs.size()) return null;
        return decodeTextureObj(texs.get(index).getAsJsonObject());
    }

    private static BufferedImage findEmissiveTexture(JsonObject model) {
        JsonArray texs = model.getAsJsonArray("textures");
        if (texs == null) return null;
        for (JsonElement el : texs) {
            if (!el.isJsonObject()) continue;
            JsonObject t = el.getAsJsonObject();
            String name = t.has("name") ? t.get("name").getAsString().toLowerCase(Locale.ROOT) : "";
            if (name.endsWith("_e.png") || name.endsWith("_t_e.png")) {
                return decodeTextureObj(t);
            }
        }
        return null;
    }

    private static BufferedImage decodeTextureObj(JsonObject tex) {
        if (tex == null || !tex.has("source")) return null;
        String src = tex.get("source").getAsString();
        int comma = src.indexOf(',');
        String b64 = comma >= 0 ? src.substring(comma + 1) : src;
        try {
            byte[] bytes = Base64.getDecoder().decode(b64.trim());
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonObject buildTextureEntry(String filename, BufferedImage img, int id, int uvW, int uvH) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        String b64 = Base64.getEncoder().encodeToString(baos.toByteArray());

        JsonObject t = new JsonObject();
        t.addProperty("path", filename);
        t.addProperty("name", filename);
        t.addProperty("folder", "");
        t.addProperty("namespace", "");
        t.addProperty("id", String.valueOf(id));
        t.addProperty("width", img.getWidth());
        t.addProperty("height", img.getHeight());
        t.addProperty("uv_width", uvW);
        t.addProperty("uv_height", uvH);
        t.addProperty("particle", id == 0);
        t.addProperty("render_mode", "default");
        t.addProperty("render_sides", "auto");
        t.addProperty("frame_time", 1);
        t.addProperty("frame_order_type", "loop");
        t.addProperty("frame_order", "");
        t.addProperty("frame_interpolate", false);
        t.addProperty("visible", true);
        t.addProperty("mode", "bitmap");
        t.addProperty("saved", true);
        t.addProperty("uuid", UUID.randomUUID().toString());
        t.addProperty("source", "data:image/png;base64," + b64);
        return t;
    }

    private static String extractAuxiliarySuffix(String name) {
        List<String> known = List.of("layer_1.png", "layer_2.png", "helmet.png", "chestplate.png", "leggings.png", "boots.png");
        for (String suf : known) {
            if (name.endsWith("_" + suf) || name.equals(suf)) {
                return suf;
            }
        }
        return null;
    }

    private static double getResolution(JsonObject model, String field, int fallback) {
        if (model.has("resolution") && model.get("resolution").isJsonObject()) {
            JsonObject res = model.getAsJsonObject("resolution");
            if (res.has(field)) {
                double v = res.get(field).getAsDouble();
                if (v > 0) return v;
            }
        }
        return fallback;
    }

    private static JsonArray defaultVisibleBox() {
        JsonArray arr = new JsonArray();
        arr.add(1);
        arr.add(1);
        arr.add(0);
        return arr;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public static String sanitizeName(String raw) {
        String s = raw == null ? "merged_armor" : raw.trim().toLowerCase(Locale.ROOT);
        if (s.endsWith(".bbmodel")) s = s.substring(0, s.length() - 8);
        s = s.replaceAll("[^a-z0-9_\\-]", "_");
        return s.isEmpty() ? "merged_armor" : s;
    }
}
