package dev.mythicwardrobe.visual;

import com.google.gson.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Generates a native Minecraft 1.21.4+ `minecraft:composite` head item definition
 * (`assets/mythicwardrobe/items/head_composite.json`) directly from MythicArmors' generated `pack.zip`!
 *
 * This enables UNLIMITED simultaneous 3D HEAD models (e.g. 3D Hair + 3D Mask + 3D Helmet + 3D Glasses)
 * inside a SINGLE vanilla HEAD equipment packet — 0 ArmorStands, 0 entities, 0 movement lag,
 * and automatic dye-tint preservation for 3D hairstyles!
 */
public final class HeadCompositePackBuilder {

    public static final String COMPOSITE_ITEM_MODEL = "mythicwardrobe:head_composite";
    public static final String COMPOSITE_ZIP_ENTRY = "assets/mythicwardrobe/items/head_composite.json";

    public record BuildResult(
            Path mergedPackZip,
            Path manifestFile,
            int modelCount,
            int maxLayers,
            List<String> modelKeys
    ) {}

    public record Manifest(int maxLayers, Set<String> modelKeys) {
        public boolean supportsAll(Collection<String> keys) {
            if (keys == null || keys.isEmpty() || keys.size() > maxLayers) return false;
            return modelKeys.containsAll(keys);
        }
    }

    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

    private HeadCompositePackBuilder() {}

    /**
     * Reads all head item model definitions from MythicArmors' pack.zip, compiles `head_composite.json`,
     * writes `head_composite_manifest.json`, and builds a ready-to-use single-file `merged_pack.zip`.
     */
    public static BuildResult buildFromMythicArmorsPack(
            Path mythicArmorsPackZip,
            Path outputMergedZip,
            Path manifestPath,
            int maxLayers
    ) throws IOException {
        if (!Files.isRegularFile(mythicArmorsPackZip)) {
            throw new IOException("Ресурспак MythicArmors не найден: " + mythicArmorsPackZip
                    + " (сначала выполните /ma reload!)");
        }
        int layers = Math.max(2, Math.min(12, maxLayers));

        // 1. Scan assets/<ns>/items/*.json inside MythicArmors pack.zip
        Map<String, JsonElement> headModels = new TreeMap<>();
        try (ZipFile zf = new ZipFile(mythicArmorsPackZip.toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/');
                if (!name.startsWith("assets/") || !name.endsWith(".json")) continue;
                String[] parts = name.split("/");
                // Expect assets/<ns>/items/<path>.json
                if (parts.length < 4 || !parts[2].equals("items")) continue;

                String ns = parts[1];
                String relPath = String.join("/", Arrays.copyOfRange(parts, 3, parts.length));
                String itemId = relPath.substring(0, relPath.length() - 5); // strip .json
                if (ns.equals("mythicwardrobe") && itemId.equals("head_composite")) continue;
                // Exclude non-head armor icons (_chest_piece, _legs_piece, _feet_piece)
                if (itemId.endsWith("_chest_piece") || itemId.endsWith("_legs_piece") || itemId.endsWith("_feet_piece")) {
                    continue;
                }

                try (InputStream in = zf.getInputStream(entry)) {
                    String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    JsonObject obj = JsonParser.parseString(raw).getAsJsonObject();
                    if (obj.has("model") && obj.get("model").isJsonObject()) {
                        String fullKey = ns + ":" + itemId;
                        headModels.put(fullKey, obj.get("model").deepCopy());
                    }
                } catch (Exception ignored) {
                }
            }
        }

        if (headModels.isEmpty()) {
            throw new IOException("В " + mythicArmorsPackZip.getFileName() + " не найдено ни одной 3D-модели головы (assets/*/items/*_head_piece.json).");
        }

        // 2. Build Minecraft 1.21.4+ minecraft:composite JSON with `layers` indexed select slots
        JsonObject compositeJson = buildCompositeDefinition(headModels, layers);
        byte[] compositeBytes = PRETTY_GSON.toJson(compositeJson).getBytes(StandardCharsets.UTF_8);

        // 3. Write merged_pack.zip (everything from MythicArmors pack.zip + head_composite.json)
        if (outputMergedZip.getParent() != null) {
            Files.createDirectories(outputMergedZip.getParent());
        }
        try (ZipFile srcZip = new ZipFile(mythicArmorsPackZip.toFile(), StandardCharsets.UTF_8);
             ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(outputMergedZip))) {
            Enumeration<? extends ZipEntry> entries = srcZip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String ename = e.getName().replace('\\', '/');
                if (ename.equals(COMPOSITE_ZIP_ENTRY)) continue;
                ZipEntry copy = new ZipEntry(ename);
                zos.putNextEntry(copy);
                if (!e.isDirectory()) {
                    try (InputStream in = srcZip.getInputStream(e)) {
                        in.transferTo(zos);
                    }
                }
                zos.closeEntry();
            }

            zos.putNextEntry(new ZipEntry(COMPOSITE_ZIP_ENTRY));
            zos.write(compositeBytes);
            zos.closeEntry();
        }

        // 4. Write manifest JSON so ArmorVisualManager knows every compiled head model key
        JsonObject manifestObj = new JsonObject();
        manifestObj.addProperty("maxLayers", layers);
        JsonArray arr = new JsonArray();
        List<String> keys = new ArrayList<>(headModels.keySet());
        for (String k : keys) arr.add(k);
        manifestObj.add("models", arr);
        if (manifestPath.getParent() != null) {
            Files.createDirectories(manifestPath.getParent());
        }
        Files.writeString(manifestPath, PRETTY_GSON.toJson(manifestObj), StandardCharsets.UTF_8);

        return new BuildResult(outputMergedZip, manifestPath, keys.size(), layers, keys);
    }

    public static JsonObject buildCompositeDefinition(Map<String, JsonElement> headModels, int layers) {
        JsonArray modelsArray = new JsonArray();

        for (int idx = 0; idx < layers; idx++) {
            JsonObject select = new JsonObject();
            select.addProperty("type", "minecraft:select");
            select.addProperty("property", "minecraft:custom_model_data");
            select.addProperty("index", idx);

            JsonArray cases = new JsonArray();
            for (Map.Entry<String, JsonElement> entry : headModels.entrySet()) {
                JsonObject c = new JsonObject();
                c.addProperty("when", entry.getKey());
                c.add("model", entry.getValue().deepCopy());
                cases.add(c);
            }
            select.add("cases", cases);

            JsonObject fallback = new JsonObject();
            fallback.addProperty("type", "minecraft:empty");
            select.add("fallback", fallback);

            modelsArray.add(select);
        }

        JsonObject composite = new JsonObject();
        composite.addProperty("type", "minecraft:composite");
        composite.add("models", modelsArray);

        JsonObject root = new JsonObject();
        root.add("model", composite);
        return root;
    }

    public static Optional<Manifest> loadManifest(Path manifestPath) {
        if (manifestPath == null || !Files.isRegularFile(manifestPath)) {
            return Optional.empty();
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(manifestPath, StandardCharsets.UTF_8)).getAsJsonObject();
            int maxLayers = obj.has("maxLayers") ? obj.get("maxLayers").getAsInt() : 6;
            Set<String> keys = new HashSet<>();
            JsonArray arr = obj.getAsJsonArray("models");
            if (arr != null) {
                for (JsonElement el : arr) {
                    if (el.isJsonPrimitive()) {
                        keys.add(el.getAsString().toLowerCase(Locale.ROOT));
                    }
                }
            }
            if (keys.isEmpty()) return Optional.empty();
            return Optional.of(new Manifest(maxLayers, Collections.unmodifiableSet(keys)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
