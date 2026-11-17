package dev.mythicwardrobe.web;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.mythicwardrobe.config.WardrobeConfig;
import dev.mythicwardrobe.manager.WardrobeManager;
import dev.mythicwardrobe.model.SkinLayerDef;
import dev.mythicwardrobe.model.SkinModelVariant;
import dev.mythicwardrobe.skin.SkinLayerRegistry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/**
 * Temporary, cleanly isolated embedded HTTP server for the visual 3D skin & layer testing editor.
 * Can be disabled via `web-editor.enabled: false` in config.yml or deleted completely once
 * replaced by an in-game GUI menu.
 */
public final class WebEditorServer {

    private record EditorSession(UUID playerUuid, String playerName, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    private static final Gson GSON = new Gson();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JavaPlugin plugin;
    private final WardrobeConfig config;
    private final SkinLayerRegistry layerRegistry;
    private final WardrobeManager wardrobeManager;

    private final Map<String, EditorSession> sessions = new ConcurrentHashMap<>();
    private HttpServer server;
    private byte[] cachedHtmlBytes;

    public WebEditorServer(
            JavaPlugin plugin,
            WardrobeConfig config,
            SkinLayerRegistry layerRegistry,
            WardrobeManager wardrobeManager
    ) {
        this.plugin = plugin;
        this.config = config;
        this.layerRegistry = layerRegistry;
        this.wardrobeManager = wardrobeManager;
    }

    public boolean start() {
        stop();
        if (!config.isWebEditorEnabled()) {
            return false;
        }
        try {
            this.cachedHtmlBytes = loadEditorHtml();
            InetSocketAddress addr = new InetSocketAddress(config.getWebEditorHost(), config.getWebEditorPort());
            this.server = HttpServer.create(addr, 0);
            this.server.setExecutor(Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "MythicWardrobe-WebEditor");
                t.setDaemon(true);
                return t;
            }));

            server.createContext("/", this::handleIndex);
            server.createContext("/api/state", this::handleState);
            server.createContext("/api/overlay", this::handleOverlay);
            server.createContext("/api/base-skin", this::handleBaseSkin);
            server.createContext("/api/apply", this::handleApply);

            server.start();
            plugin.getLogger().info("Temporary Web Skin Editor started on http://"
                    + config.getWebEditorHost() + ":" + config.getWebEditorPort()
                    + " (public URL: " + config.getWebEditorPublicUrl() + ")");
            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to start Web Skin Editor on port " + config.getWebEditorPort()
                            + " (check if port is free or set web-editor.enabled: false)", e);
            this.server = null;
            return false;
        }
    }

    public void stop() {
        if (server != null) {
            try {
                server.stop(0);
            } catch (Exception ignored) {
            }
            server = null;
        }
        sessions.clear();
    }

    public boolean isRunning() {
        return server != null;
    }

    public String createSessionUrl(Player player) {
        cleanupExpiredSessions();
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expires = Instant.now().plusSeconds(config.getWebEditorTokenExpireMinutes() * 60L);
        sessions.put(token, new EditorSession(player.getUniqueId(), player.getName(), expires));
        return config.getWebEditorPublicUrl() + "/?token=" + token;
    }

    private void cleanupExpiredSessions() {
        sessions.entrySet().removeIf(e -> e.getValue().isExpired());
    }

    private EditorSession resolveSession(Map<String, String> query) {
        String token = query.get("token");
        if (token == null || token.isBlank()) return null;
        EditorSession session = sessions.get(token);
        if (session == null || session.isExpired()) {
            sessions.remove(token);
            return null;
        }
        return session;
    }

    private void handleIndex(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, errorJson("Method not allowed"));
            return;
        }
        String path = ex.getRequestURI().getPath();
        if (!"/".equals(path) && !"/index.html".equals(path)) {
            sendBytes(ex, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        sendBytes(ex, 200, "text/html; charset=utf-8", cachedHtmlBytes);
    }

    private void handleState(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, errorJson("Method not allowed"));
            return;
        }
        Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());
        EditorSession session = resolveSession(query);
        if (session == null) {
            sendJson(ex, 401, errorJson("Ссылка истекла или неверна. Выполните /wardrobe web в игре."));
            return;
        }

        Player player = Bukkit.getPlayer(session.playerUuid());
        if (player == null || !player.isOnline()) {
            sendJson(ex, 404, errorJson("Игрок " + session.playerName() + " должен быть онлайн на сервере."));
            return;
        }

        SkinModelVariant effectiveBodyType = wardrobeManager.getEffectiveBodyType(player);
        Map<String, String> activeLayers = wardrobeManager.getActiveSkinLayers(player);

        JsonObject root = new JsonObject();
        root.addProperty("playerName", player.getName());
        root.addProperty("connectedToServer", true);
        root.addProperty("bodyType", effectiveBodyType.getBodyTypeName());
        root.add("layers", buildLayersJson(config.getSkinLayersOrdered(), layerRegistry));

        JsonObject activeObj = new JsonObject();
        for (Map.Entry<String, String> e : activeLayers.entrySet()) {
            // Strip leading folder/variant prefixes so the UI matches tile names cleanly
            String val = e.getValue().replace('\\', '/');
            int slash = val.lastIndexOf('/');
            String bare = slash >= 0 ? val.substring(slash + 1) : val;
            if (!bare.toLowerCase(Locale.ROOT).endsWith(".png")) {
                bare = bare + ".png";
            }
            activeObj.addProperty(e.getKey(), bare);
        }
        root.add("activeLayers", activeObj);

        sendJson(ex, 200, root);
    }

    private void handleOverlay(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, errorJson("Method not allowed"));
            return;
        }
        Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());
        String layerId = query.getOrDefault("layer", "");
        String fileName = query.getOrDefault("file", "");
        String btRaw = query.getOrDefault("bodytype", "wide");

        if (fileName.isBlank() || fileName.contains("..")) {
            sendJson(ex, 400, errorJson("Invalid overlay request"));
            return;
        }

        SkinLayerDef def = config.getSkinLayerDef(layerId);
        String folderHint = def != null ? def.folder() : layerId;
        SkinModelVariant variant = SkinModelVariant.fromString(btRaw, SkinModelVariant.CLASSIC);

        Optional<SkinLayerRegistry.LoadedOverlay> resolved = layerRegistry.resolve(folderHint, fileName, variant);
        if (resolved.isEmpty()) {
            sendBytes(ex, 404, "text/plain; charset=utf-8", "Overlay not found".getBytes(StandardCharsets.UTF_8));
            return;
        }

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(resolved.get().image(), "png", out);
            ex.getResponseHeaders().set("Cache-Control", "public, max-age=60");
            sendBytes(ex, 200, "image/png", out.toByteArray());
        }
    }

    private void handleBaseSkin(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, errorJson("Method not allowed"));
            return;
        }
        Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());
        EditorSession session = resolveSession(query);
        if (session == null) {
            sendJson(ex, 401, errorJson("Invalid session token"));
            return;
        }
        Player player = Bukkit.getPlayer(session.playerUuid());
        if (player == null || !player.isOnline()) {
            sendJson(ex, 404, errorJson("Player offline"));
            return;
        }
        byte[] png = wardrobeManager.getPlayerBaseSkinPngBytes(player);
        sendBytes(ex, 200, "image/png", png);
    }

    private void handleApply(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, errorJson("Method not allowed"));
            return;
        }
        Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());
        EditorSession session = resolveSession(query);
        if (session == null) {
            sendJson(ex, 401, errorJson("Ссылка истекла или неверна. Выполните /wardrobe web снова."));
            return;
        }
        Player player = Bukkit.getPlayer(session.playerUuid());
        if (player == null || !player.isOnline()) {
            sendJson(ex, 404, errorJson("Игрок " + session.playerName() + " оффлайн."));
            return;
        }

        String body;
        try (InputStream in = ex.getRequestBody()) {
            byte[] raw = in.readNBytes(65536);
            body = new String(raw, StandardCharsets.UTF_8);
        }

        JsonObject req;
        try {
            req = JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            sendJson(ex, 400, errorJson("Некорректный JSON"));
            return;
        }

        String btStr = req.has("bodyType") ? req.get("bodyType").getAsString() : "wide";
        SkinModelVariant variant = SkinModelVariant.fromString(btStr, SkinModelVariant.CLASSIC);

        Map<String, String> selected = new LinkedHashMap<>();
        if (req.has("layers") && req.get("layers").isJsonObject()) {
            JsonObject layersObj = req.getAsJsonObject("layers");
            for (Map.Entry<String, JsonElement> entry : layersObj.entrySet()) {
                if (entry.getValue().isJsonNull()) continue;
                String layerKey = config.resolveLayerId(entry.getKey());
                String tex = entry.getValue().getAsString().trim();
                if (layerKey.isEmpty() || tex.isEmpty() || tex.contains("..")) continue;

                SkinLayerDef def = config.getSkinLayerDef(layerKey);
                String folderHint = def != null ? def.folder() : layerKey;
                if (layerRegistry.resolve(folderHint, tex, variant).isPresent()) {
                    selected.put(layerKey, tex);
                }
            }
        }

        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player online = Bukkit.getPlayer(uuid);
            if (online != null && online.isOnline()) {
                wardrobeManager.applyWebEditorBatch(online, variant, selected);
            }
        });

        JsonObject ok = new JsonObject();
        ok.addProperty("ok", true);
        ok.addProperty("appliedLayers", selected.size());
        ok.addProperty("bodyType", variant.getBodyTypeName());
        ok.addProperty("message", "Отправлено на сервер (" + selected.size() + " сл., "
                + variant.getBodyTypeName() + ")! Скин обновится через пару секунд.");
        sendJson(ex, 200, ok);
    }

    public static JsonArray buildLayersJson(List<SkinLayerDef> orderedLayers, SkinLayerRegistry registry) {
        JsonArray arr = new JsonArray();
        for (SkinLayerDef def : orderedLayers) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", def.id());
            obj.addProperty("priority", def.priority());
            obj.addProperty("folder", def.folder());
            obj.addProperty("replacesBaseSkin", def.replacesBaseSkin());

            JsonArray clearArr = new JsonArray();
            for (String r : def.clearOuterRegions()) {
                clearArr.add(r);
            }
            obj.add("clearOuterRegions", clearArr);

            JsonArray texArr = new JsonArray();
            for (String tex : registry.getTexturesInFolder(def.folder())) {
                texArr.add(tex);
            }
            obj.add("textures", texArr);
            arr.add(obj);
        }
        return arr;
    }

    private byte[] loadEditorHtml() throws IOException {
        try (InputStream in = plugin.getResource("web/editor.html")) {
            if (in == null) {
                throw new IOException("Missing bundled resource web/editor.html");
            }
            return in.readAllBytes();
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> map = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) return map;
        for (String pair : rawQuery.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                String k = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                String v = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                map.put(k, v);
            }
        }
        return map;
    }

    private static JsonObject errorJson(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", message);
        return o;
    }

    private static void sendJson(HttpExchange ex, int status, JsonObject body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        sendBytes(ex, status, "application/json; charset=utf-8", bytes);
    }

    private static void sendBytes(HttpExchange ex, int status, String contentType, byte[] bytes) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
