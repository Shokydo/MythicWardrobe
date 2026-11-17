package dev.mythicwardrobe.skin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mythicwardrobe.config.WardrobeConfig;
import dev.mythicwardrobe.model.SignedSkinData;
import dev.mythicwardrobe.model.SkinModelVariant;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * High-throughput HTTP/2 client for MineSkin API v2 (/v2/queue + adaptive fast polling /v2/queue/{id}).
 * Features:
 * - Concurrent worker pool (up to 5 parallel MineSkin v2 queue jobs)
 * - Submit-only rate-limit gate (polling never blocks other players' submissions)
 * - Adaptive fast polling (350ms -> 550ms -> 800ms -> configured poll-interval-ms)
 * - Automatic retries on transient MineSkin/Mojang server errors (e.g. invalid_skin_data, generator_timeout, HTTP 429/5xx)
 * - Fallback fetch to /v2/skins/{result} if a completed job omits embedded skin payload
 * - In-flight SHA-256 deduplication (identical concurrent requests share one MineSkin call)
 * - Configurable base-url (supports official api.mineskin.org or self-hosted MineSkin-compatible proxy)
 */
public final class MineSkinClient {

    private static final int MAX_ATTEMPTS = 3;
    private static final Set<String> RETRYABLE_ERROR_CODES = Set.of(
            "invalid_skin_data",
            "skin_change_failed",
            "generator_timeout",
            "no_account_available",
            "internal_error",
            "job_failed",
            "rate_limit"
    );

    private final WardrobeConfig config;
    private final Logger logger;
    private final HttpClient httpClient;
    private final ExecutorService workerPool;
    private final Object submitGate = new Object();
    private final Map<String, CompletableFuture<SignedSkinData>> inFlightByHash = new ConcurrentHashMap<>();
    private volatile long nextAllowedSubmitMs = 0L;

    public MineSkinClient(WardrobeConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(12))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        int threads = Math.max(1, Math.min(5, config.getMineSkinMaxConcurrency()));
        AtomicInteger counter = new AtomicInteger(1);
        this.workerPool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "MythicWardrobe-MineSkin-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
    }

    public CompletableFuture<SignedSkinData> uploadAndSign(byte[] pngBytes, SkinModelVariant variant) {
        return uploadAndSign(null, pngBytes, variant);
    }

    /**
     * Uploads and signs a 64x64 PNG via MineSkin v2 with optional SHA-256 in-flight deduplication.
     */
    public CompletableFuture<SignedSkinData> uploadAndSign(String dedupHash, byte[] pngBytes, SkinModelVariant variant) {
        if (dedupHash != null && !dedupHash.isBlank()) {
            return inFlightByHash.computeIfAbsent(dedupHash, hash -> {
                CompletableFuture<SignedSkinData> future = submitAsync(pngBytes, variant);
                future.whenComplete((res, err) -> inFlightByHash.remove(hash));
                return future;
            });
        }
        return submitAsync(pngBytes, variant);
    }

    private CompletableFuture<SignedSkinData> submitAsync(byte[] pngBytes, SkinModelVariant variant) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return uploadWithRetries(pngBytes, variant);
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), e);
            }
        }, workerPool);
    }

    public Optional<BufferedImage> downloadSkinImage(String textureUrl) {
        if (textureUrl == null || textureUrl.isBlank()) return Optional.empty();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(textureUrl))
                    .header("User-Agent", config.getMineSkinUserAgent())
                    .timeout(Duration.ofSeconds(12))
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200 || resp.body() == null || resp.body().length == 0) {
                return Optional.empty();
            }
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(resp.body()));
            return Optional.ofNullable(img);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to download base skin from " + textureUrl, e);
            return Optional.empty();
        }
    }

    private SignedSkinData uploadWithRetries(byte[] pngBytes, SkinModelVariant variant) throws IOException, InterruptedException {
        IOException lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return uploadSingleAttempt(pngBytes, variant);
            } catch (MineSkinRequestException e) {
                lastError = e;
                if (!e.retryable || attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                long backoffMs = e.retryDelayMs > 0 ? e.retryDelayMs : (attempt == 1 ? 1200L : 2500L);
                logger.warning("[MineSkin] Transient error (" + e.errorCode + ") on attempt " + attempt + "/" + MAX_ATTEMPTS
                        + " — retrying in " + backoffMs + "ms: " + e.getMessage());
                Thread.sleep(Math.min(backoffMs, 6000L));
            } catch (IOException e) {
                lastError = e;
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                long backoffMs = attempt == 1 ? 1200L : 2500L;
                logger.warning("[MineSkin] Network/IO error on attempt " + attempt + "/" + MAX_ATTEMPTS
                        + " — retrying in " + backoffMs + "ms: " + e.getMessage());
                Thread.sleep(backoffMs);
            }
        }
        throw lastError != null ? lastError : new IOException("MineSkin request failed after " + MAX_ATTEMPTS + " attempts");
    }

    private SignedSkinData uploadSingleAttempt(byte[] pngBytes, SkinModelVariant variant) throws IOException, InterruptedException {
        String baseUrl = config.getMineSkinBaseUrl();
        // Use single-RTT POST /v2/generate first (0.6s-2.5s, zero polling overhead!),
        // with full automatic fallback to /v2/queue/{jobId} polling if server queues the job!
        String generateEndpoint = baseUrl + "/v2/generate";
        String jobEndpointPrefix = baseUrl + "/v2/queue/";
        String skinEndpointPrefix = baseUrl + "/v2/skins/";

        String boundary = "----MythicWardrobe" + UUID.randomUUID().toString().replace("-", "");
        byte[] multipartBody = buildMultipart(boundary, pngBytes, variant, config.getMineSkinVisibility());

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(generateEndpoint))
                .timeout(Duration.ofSeconds(config.getMineSkinTimeoutSeconds()))
                .header("User-Agent", config.getMineSkinUserAgent())
                .header("Accept", "application/json")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody));

        String apiKey = config.getMineSkinApiKey();
        boolean hasKey = apiKey != null && !apiKey.isBlank() && !apiKey.equals("YOUR_MINESKIN_API_KEY");
        if (hasKey) {
            reqBuilder.header("Authorization", "Bearer " + apiKey.trim());
        }

        HttpResponse<String> resp;
        // Serialize ONLY the rate-limited POST submission; polling runs concurrently across workers!
        synchronized (submitGate) {
            awaitRateLimitWindow();
            resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = resp.statusCode();
            String body = resp.body() == null ? "" : resp.body();

            if (status == 429) {
                long retryMs = parseRetryDelayMs(resp, body);
                nextAllowedSubmitMs = Math.max(nextAllowedSubmitMs, System.currentTimeMillis() + retryMs);
                throw new MineSkinRequestException(
                        "MineSkin rate-limited (HTTP 429, retry in " + Math.max(1, retryMs / 1000) + "s)",
                        "rate_limit",
                        true,
                        retryMs
                );
            }
            if (status == 200 || status == 201 || status == 202) {
                try {
                    updateRateLimitFromJson(JsonParser.parseString(body).getAsJsonObject());
                } catch (Exception ignored) {
                }
            }
        }

        int status = resp.statusCode();
        String body = resp.body() == null ? "" : resp.body();

        if (config.isDebug()) {
            logger.info("[MineSkin] POST " + generateEndpoint + " -> HTTP " + status);
        }
        if (status != 200 && status != 201 && status != 202) {
            ParsedError parsed = parseErrorFromBody(body);
            boolean retryable = status >= 500 || isRetryableErrorCode(parsed.code());
            String msg = "MineSkin HTTP " + status + (parsed.summary().isEmpty() ? "" : ": " + parsed.summary());
            if (config.isDebug()) {
                logger.warning("[MineSkin] Full error body: " + body);
            }
            throw new MineSkinRequestException(msg, parsed.code(), retryable, 0L);
        }

        JsonObject json = JsonParser.parseString(body).getAsJsonObject();

        // Immediate completion (duplicate already signed by MineSkin — 0 polling!)
        SignedSkinData immediate = tryExtractSignedSkin(json, variant);
        if (immediate != null) {
            return immediate;
        }

        // Queued job -> adaptive fast polling /v2/queue/{jobId}
        String jobId = extractJobId(json);
        if (jobId == null || jobId.isBlank()) {
            throw new MineSkinRequestException(
                    "MineSkin response contained neither skin data nor job id: " + truncate(body, 220),
                    "unexpected_response",
                    true,
                    0L
            );
        }

        long deadline = System.currentTimeMillis() + (config.getMineSkinTimeoutSeconds() * 1000L);
        long maxPollMs = config.getMineSkinPollIntervalMs();
        long[] adaptiveSteps = new long[]{350L, 550L, 800L};
        int pollAttempt = 0;

        while (System.currentTimeMillis() < deadline) {
            long sleepMs = pollAttempt < adaptiveSteps.length
                    ? Math.min(adaptiveSteps[pollAttempt], maxPollMs)
                    : maxPollMs;
            pollAttempt++;
            Thread.sleep(sleepMs);

            HttpRequest.Builder pollReq = HttpRequest.newBuilder()
                    .uri(URI.create(jobEndpointPrefix + jobId))
                    .timeout(Duration.ofSeconds(12))
                    .header("User-Agent", config.getMineSkinUserAgent())
                    .header("Accept", "application/json")
                    .GET();
            if (hasKey) {
                pollReq.header("Authorization", "Bearer " + apiKey.trim());
            }

            HttpResponse<String> pollResp = httpClient.send(pollReq.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String pollBody = pollResp.body() == null ? "" : pollResp.body();
            if (pollResp.statusCode() == 429) {
                long delay = parseRetryDelayMs(pollResp, pollBody);
                Thread.sleep(Math.min(delay, 4000L));
                continue;
            }
            if (pollResp.statusCode() >= 500) {
                // Transient gateway/server error while polling — wait and poll again
                continue;
            }
            if (pollResp.statusCode() >= 400) {
                ParsedError parsed = parseErrorFromBody(pollBody);
                boolean retryable = isRetryableErrorCode(parsed.code());
                throw new MineSkinRequestException(
                        "MineSkin poll HTTP " + pollResp.statusCode() + (parsed.summary().isEmpty() ? "" : ": " + parsed.summary()),
                        parsed.code(),
                        retryable,
                        0L
                );
            }

            JsonObject pollJson = JsonParser.parseString(pollBody).getAsJsonObject();
            SignedSkinData ready = tryExtractSignedSkin(pollJson, variant);
            if (ready != null) {
                return ready;
            }

            String jobStatus = extractJobStatus(pollJson);
            if ("completed".equalsIgnoreCase(jobStatus)) {
                // Some v2 responses return job.result UUID without embedding the skin object — fetch /v2/skins/{result}
                String resultSkinId = extractJobResult(pollJson);
                if (resultSkinId != null && !resultSkinId.isBlank()) {
                    SignedSkinData fetched = fetchSkinById(skinEndpointPrefix + resultSkinId, hasKey ? apiKey.trim() : null, variant);
                    if (fetched != null) {
                        return fetched;
                    }
                }
            }

            if ("failed".equalsIgnoreCase(jobStatus) || "error".equalsIgnoreCase(jobStatus)) {
                ParsedError parsed = parseErrorFromJson(pollJson);
                logger.warning("[MineSkin] Job " + jobId + " failed (" + parsed.code() + "): " + pollBody);
                boolean retryable = isRetryableErrorCode(parsed.code());
                String friendly = parsed.summary().isEmpty() ? "Job failed" : parsed.summary();
                throw new MineSkinRequestException(
                        "MineSkin (" + friendly + ")",
                        parsed.code(),
                        retryable,
                        0L
                );
            }
        }

        throw new MineSkinRequestException(
                "Timed out waiting for MineSkin job " + jobId,
                "generator_timeout",
                true,
                0L
        );
    }

    private SignedSkinData fetchSkinById(String skinUrl, String apiKey, SkinModelVariant variant) {
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder()
                    .uri(URI.create(skinUrl))
                    .timeout(Duration.ofSeconds(12))
                    .header("User-Agent", config.getMineSkinUserAgent())
                    .header("Accept", "application/json")
                    .GET();
            if (apiKey != null && !apiKey.isBlank()) {
                req.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> resp = httpClient.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 200 && resp.body() != null) {
                JsonObject obj = JsonParser.parseString(resp.body()).getAsJsonObject();
                return tryExtractSignedSkin(obj, variant);
            }
        } catch (Exception e) {
            if (config.isDebug()) {
                logger.log(Level.WARNING, "[MineSkin] Fallback GET " + skinUrl + " failed", e);
            }
        }
        return null;
    }

    public record ParsedError(String code, String message, String summary) {
    }

    public static boolean isRetryableErrorCode(String code) {
        if (code == null || code.isBlank()) return false;
        return RETRYABLE_ERROR_CODES.contains(code.trim().toLowerCase(Locale.ROOT));
    }

    public static ParsedError parseErrorFromBody(String body) {
        if (body == null || body.isBlank()) {
            return new ParsedError("unknown", "", "");
        }
        try {
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
            return parseErrorFromJson(obj);
        } catch (Exception ignored) {
            String clean = truncate(body.trim(), 140);
            return new ParsedError("unknown", clean, clean);
        }
    }

    public static ParsedError parseErrorFromJson(JsonObject json) {
        if (json == null) return new ParsedError("unknown", "", "");
        if (json.has("errors") && json.get("errors").isJsonArray()) {
            JsonArray arr = json.getAsJsonArray("errors");
            if (!arr.isEmpty() && arr.get(0).isJsonObject()) {
                JsonObject first = arr.get(0).getAsJsonObject();
                String code = getStr(first, "code");
                String msg = getStr(first, "message");
                String safeCode = code != null ? code : "job_failed";
                String safeMsg = msg != null ? msg : "";
                String summary = safeMsg.isEmpty() ? safeCode : (safeCode + ": " + safeMsg);
                return new ParsedError(safeCode, safeMsg, summary);
            }
        }
        if (json.has("messages") && json.get("messages").isJsonArray()) {
            JsonArray arr = json.getAsJsonArray("messages");
            if (!arr.isEmpty() && arr.get(0).isJsonObject()) {
                JsonObject first = arr.get(0).getAsJsonObject();
                String code = getStr(first, "code");
                String msg = getStr(first, "message");
                String safeCode = code != null ? code : "job_failed";
                String safeMsg = msg != null ? msg : "";
                String summary = safeMsg.isEmpty() ? safeCode : (safeCode + ": " + safeMsg);
                return new ParsedError(safeCode, safeMsg, summary);
            }
        }
        String err = getStr(json, "error");
        if (err != null && !err.isBlank()) {
            return new ParsedError("error", err, err);
        }
        return new ParsedError("job_failed", "", "job_failed");
    }

    private void awaitRateLimitWindow() throws InterruptedException {
        long now = System.currentTimeMillis();
        long wait = nextAllowedSubmitMs - now;
        if (wait > 0) {
            Thread.sleep(Math.min(wait, 15_000L));
        }
    }

    private void updateRateLimitFromJson(JsonObject json) {
        try {
            if (!json.has("rateLimit")) return;
            JsonObject rl = json.getAsJsonObject("rateLimit");
            if (rl.has("next")) {
                JsonObject next = rl.getAsJsonObject("next");
                if (next.has("absolute")) {
                    nextAllowedSubmitMs = Math.max(nextAllowedSubmitMs, next.get("absolute").getAsLong());
                } else if (next.has("relative")) {
                    nextAllowedSubmitMs = Math.max(nextAllowedSubmitMs, System.currentTimeMillis() + next.get("relative").getAsLong());
                }
            }
        } catch (Exception ignored) {
        }
    }

    private long parseRetryDelayMs(HttpResponse<?> resp, String body) {
        Optional<String> retryAfter = resp.headers().firstValue("Retry-After");
        if (retryAfter.isPresent()) {
            try {
                return Math.max(1000L, Long.parseLong(retryAfter.get().trim()) * 1000L);
            } catch (NumberFormatException ignored) {
            }
        }
        try {
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
            if (obj.has("rateLimit")) {
                JsonObject rl = obj.getAsJsonObject("rateLimit");
                if (rl.has("next") && rl.getAsJsonObject("next").has("relative")) {
                    return Math.max(1000L, rl.getAsJsonObject("next").get("relative").getAsLong());
                }
            }
        } catch (Exception ignored) {
        }
        return 3000L;
    }

    private static String extractJobId(JsonObject json) {
        if (json.has("job") && json.get("job").isJsonObject()) {
            JsonObject job = json.getAsJsonObject("job");
            if (job.has("id") && !job.get("id").isJsonNull()) {
                return job.get("id").getAsString();
            }
        }
        if (json.has("id") && !json.get("id").isJsonNull()) {
            return json.get("id").getAsString();
        }
        return null;
    }

    private static String extractJobStatus(JsonObject json) {
        if (json.has("job") && json.get("job").isJsonObject()) {
            JsonObject job = json.getAsJsonObject("job");
            if (job.has("status") && !job.get("status").isJsonNull()) {
                return job.get("status").getAsString();
            }
        }
        return "";
    }

    private static String extractJobResult(JsonObject json) {
        if (json.has("job") && json.get("job").isJsonObject()) {
            JsonObject job = json.getAsJsonObject("job");
            if (job.has("result") && !job.get("result").isJsonNull()) {
                return job.get("result").getAsString();
            }
        }
        return null;
    }

    private static SignedSkinData tryExtractSignedSkin(JsonObject root, SkinModelVariant fallbackVariant) {
        JsonObject skinObj = null;
        if (root.has("skin") && root.get("skin").isJsonObject()) {
            skinObj = root.getAsJsonObject("skin");
        } else if (root.has("texture") && root.get("texture").isJsonObject()) {
            skinObj = root;
        }
        if (skinObj == null) return null;

        JsonObject textureObj = null;
        if (skinObj.has("texture") && skinObj.get("texture").isJsonObject()) {
            textureObj = skinObj.getAsJsonObject("texture");
        }
        if (textureObj == null) return null;

        JsonObject dataObj = textureObj.has("data") && textureObj.get("data").isJsonObject()
                ? textureObj.getAsJsonObject("data")
                : textureObj;

        String value = getStr(dataObj, "value");
        String signature = getStr(dataObj, "signature");
        if (value == null || signature == null) return null;

        String url = null;
        if (textureObj.has("url") && textureObj.get("url").isJsonObject()) {
            url = getStr(textureObj.getAsJsonObject("url"), "skin");
        } else {
            url = getStr(textureObj, "url");
        }
        if (url == null) {
            url = SkinCompositor.decodeTexturesProperty(value, fallbackVariant)
                    .map(SkinCompositor.DecodedTextureProperty::textureUrl)
                    .orElse(null);
        }

        SkinModelVariant variant = fallbackVariant;
        String varStr = getStr(skinObj, "variant");
        if (varStr != null) {
            variant = SkinModelVariant.fromString(varStr, fallbackVariant);
        }

        return new SignedSkinData(value, signature, url, variant);
    }

    private static String getStr(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) return null;
        JsonElement el = obj.get(key);
        return (el != null && el.isJsonPrimitive()) ? el.getAsString() : null;
    }

    private static byte[] buildMultipart(String boundary, byte[] pngBytes, SkinModelVariant variant, String visibility) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(pngBytes.length + 512);
        String crlf = "\r\n";

        out.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"variant\"" + crlf + crlf).getBytes(StandardCharsets.UTF_8));
        out.write((variant.getApiName() + crlf).getBytes(StandardCharsets.UTF_8));

        String vis = (visibility == null || visibility.isBlank()) ? "unlisted" : visibility.trim().toLowerCase();
        out.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"visibility\"" + crlf + crlf).getBytes(StandardCharsets.UTF_8));
        out.write((vis + crlf).getBytes(StandardCharsets.UTF_8));

        out.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"file\"; filename=\"skin.png\"" + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: image/png" + crlf + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(pngBytes);
        out.write(crlf.getBytes(StandardCharsets.UTF_8));

        out.write(("--" + boundary + "--" + crlf).getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    public void shutdown() {
        workerPool.shutdownNow();
        inFlightByHash.clear();
    }

    private static final class MineSkinRequestException extends IOException {
        final String errorCode;
        final boolean retryable;
        final long retryDelayMs;

        MineSkinRequestException(String message, String errorCode, boolean retryable, long retryDelayMs) {
            super(message);
            this.errorCode = errorCode == null ? "unknown" : errorCode;
            this.retryable = retryable;
            this.retryDelayMs = retryDelayMs;
        }
    }
}
