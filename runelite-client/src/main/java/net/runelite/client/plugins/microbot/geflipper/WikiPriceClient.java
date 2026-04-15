package net.runelite.client.plugins.microbot.geflipper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.util.grandexchange.models.ItemMappingData;
import net.runelite.client.plugins.microbot.util.grandexchange.models.TimeSeriesAnalysis;
import net.runelite.client.plugins.microbot.util.grandexchange.models.TimeSeriesDataPoint;
import net.runelite.client.plugins.microbot.util.grandexchange.models.TimeSeriesInterval;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dedicated HTTP client for the OSRS Wiki Prices API.
 *
 * Rate-limit strategy (conservative, as per Wiki API etiquette):
 *   - /mapping  : once per 30 min, bulk, no per-item calls
 *   - /latest   : once per 60 s minimum, bulk, single call
 *   - /timeseries: max 20 req/min (one per 3 s), queued async
 *
 * Implements:
 *   - Token-bucket throttle for timeseries calls (3 s gap minimum)
 *   - Exponential back-off on 429 / 5xx responses (up to 60 s)
 *   - Shared single HttpClient instance (HTTP/2 keep-alive)
 *   - Thread-safe result caching for timeseries
 */
@Slf4j
public class WikiPriceClient {

    // ── API endpoints ────────────────────────────────────────────────
    private static final String BASE_URL           = "https://prices.runescape.wiki/api/v1/osrs";
    private static final String MAPPING_URL        = BASE_URL + "/mapping";
    private static final String LATEST_URL         = BASE_URL + "/latest";
    private static final String TIMESERIES_URL     = BASE_URL + "/timeseries";

    // ── Rate-limit constants ─────────────────────────────────────────
    /** Minimum milliseconds between consecutive timeseries requests. */
    private static final long TS_MIN_GAP_MS        = 3_000;   // 20 req/min = 1 per 3 s
    /** Max back-off delay in ms (60 s). */
    private static final long MAX_BACKOFF_MS        = 60_000;
    /** Starting back-off delay (2 s). */
    private static final long BASE_BACKOFF_MS       = 2_000;
    /** How many consecutive failures before we give up on a single call. */
    private static final int  MAX_RETRIES           = 4;

    // ── Cache durations ──────────────────────────────────────────────
    public static final long MAPPING_TTL_MS         = 30 * 60 * 1_000L; // 30 min
    public static final long LATEST_TTL_MS          =      60 * 1_000L; //  1 min
    public static final long TIMESERIES_TTL_MS      =  5 * 60 * 1_000L; //  5 min

    // ── HTTP client (singleton, shared across all calls) ────────────
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** User-Agent as required by the OSRS Wiki API policy. */
    private final String userAgent;

    // ── Throttle state ───────────────────────────────────────────────
    /** Timestamp of the last timeseries HTTP request (epoch ms). */
    private final AtomicLong lastTsRequestTime = new AtomicLong(0);

    /** Consecutive 429/5xx failure counter, for back-off escalation. */
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    // ── Timeseries in-flight dedup: prevents duplicate concurrent requests ──
    private final ConcurrentHashMap<Integer, CompletableFuture<TimeSeriesAnalysis>> inFlight
            = new ConcurrentHashMap<>();

    // ── Timeseries result cache ──────────────────────────────────────
    private final ConcurrentHashMap<Integer, CachedResult<TimeSeriesAnalysis>> tsCache
            = new ConcurrentHashMap<>();

    // ── Bulk caches ──────────────────────────────────────────────────
    private volatile Map<Integer, ItemMappingData> mappingCache  = Collections.emptyMap();
    private volatile long mappingFetchedAt = 0;

    private volatile Map<Integer, int[]> latestCache = Collections.emptyMap();
    private volatile long latestFetchedAt = 0;

    // ── Serial executor for timeseries requests (enforces ordering + gap) ──
    private final ExecutorService tsExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GEFlipper-WikiAPI");
        t.setDaemon(true);
        return t;
    });

    // ────────────────────────────────────────────────────────────────────────

    public WikiPriceClient(String pluginVersion) {
        this.userAgent = "Microbot-GE-Flipper/" + pluginVersion
                + " (github.com/stonksCode/Microbot; for OSRS automation research)";
    }

    // ════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Returns item mapping data (name, trade limit, alch values, etc.).
     * Uses a 30-minute cache; only fetches when stale.
     */
    public Map<Integer, ItemMappingData> getItemMapping() {
        if (!mappingCache.isEmpty() && !isStale(mappingFetchedAt, MAPPING_TTL_MS)) {
            return mappingCache;
        }
        Map<Integer, ItemMappingData> fresh = fetchMapping();
        if (!fresh.isEmpty()) {
            mappingCache = Collections.unmodifiableMap(fresh);
            mappingFetchedAt = System.currentTimeMillis();
            log.info("[WikiAPI] Mapping refreshed: {} items", fresh.size());
        } else {
            log.warn("[WikiAPI] Mapping fetch failed — using cached ({} items)", mappingCache.size());
        }
        return mappingCache;
    }

    /**
     * Returns latest buy/sell prices for all items.
     * Uses a 60-second cache; only fetches when stale.
     *
     * @return map of itemId → int[]{highPrice, lowPrice}
     */
    public Map<Integer, int[]> getLatestPrices() {
        if (!latestCache.isEmpty() && !isStale(latestFetchedAt, LATEST_TTL_MS)) {
            return latestCache;
        }
        Map<Integer, int[]> fresh = fetchLatest();
        if (!fresh.isEmpty()) {
            latestCache = Collections.unmodifiableMap(fresh);
            latestFetchedAt = System.currentTimeMillis();
            log.info("[WikiAPI] Latest prices refreshed: {} items", fresh.size());
        } else {
            log.warn("[WikiAPI] Latest fetch failed — using cached ({} items)", latestCache.size());
        }
        return latestCache;
    }

    /**
     * Fetches fresh latest prices regardless of cache age.
     * Use sparingly — call {@link #getLatestPrices()} for normal usage.
     */
    public Map<Integer, int[]> forceRefreshLatestPrices() {
        latestFetchedAt = 0; // invalidate cache
        return getLatestPrices();
    }

    /**
     * Fetches timeseries analysis for a single item, with rate-limiting and caching.
     * Submits to a serial queue — returns a CompletableFuture so callers can batch
     * without blocking the bot loop thread.
     *
     * @param itemId the OSRS item ID
     * @return CompletableFuture resolving to TimeSeriesAnalysis, or null on failure
     */
    public CompletableFuture<TimeSeriesAnalysis> getTimeSeriesAsync(int itemId) {
        // Serve from cache if fresh
        CachedResult<TimeSeriesAnalysis> cached = tsCache.get(itemId);
        if (cached != null && !isStale(cached.fetchedAt, TIMESERIES_TTL_MS)) {
            return CompletableFuture.completedFuture(cached.value);
        }

        // Dedup: if a request is already in-flight for this item, reuse it
        return inFlight.computeIfAbsent(itemId, id -> {
            CompletableFuture<TimeSeriesAnalysis> future = CompletableFuture.supplyAsync(
                    () -> fetchTimeSeriesThrottled(id), tsExecutor
            );
            // Remove from in-flight map when done (success or failure)
            future.whenComplete((result, ex) -> {
                inFlight.remove(id);
                if (result != null) {
                    tsCache.put(id, new CachedResult<>(result));
                }
            });
            return future;
        });
    }

    /**
     * Convenience blocking wrapper — only use when you don't need concurrency.
     * Prefer {@link #getTimeSeriesAsync} for batch operations.
     */
    public TimeSeriesAnalysis getTimeSeries(int itemId) {
        try {
            return getTimeSeriesAsync(itemId).get(30, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("[WikiAPI] Timeseries timeout for item {}", itemId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            log.error("[WikiAPI] Timeseries error for item {}: {}", itemId, e.getCause().getMessage());
        }
        return null;
    }

    /**
     * Fetches timeseries for a batch of items, honouring the rate limit between each call.
     * Returns a map of itemId → analysis (missing entries = failed fetches).
     *
     * This is the preferred method for scanning — it queues all work on the serial
     * executor and collects results once all futures are done.
     *
     * @param itemIds ordered list of item IDs to fetch
     * @param maxItems cap on how many to actually fetch (top-N by position)
     * @param timeoutSeconds total wall-clock timeout for the entire batch
     */
    public Map<Integer, TimeSeriesAnalysis> getBatchTimeSeries(
            List<Integer> itemIds, int maxItems, int timeoutSeconds) {

        int count = Math.min(maxItems, itemIds.size());
        log.info("[WikiAPI] Queuing timeseries for {} items (est. ~{}s with rate limiting)",
                count, count * TS_MIN_GAP_MS / 1000);

        // Submit all futures first (serial executor queues them automatically)
        Map<Integer, CompletableFuture<TimeSeriesAnalysis>> futures = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            int id = itemIds.get(i);
            futures.put(id, getTimeSeriesAsync(id));
        }

        // Collect results
        Map<Integer, TimeSeriesAnalysis> results = new LinkedHashMap<>();
        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);

        for (Map.Entry<Integer, CompletableFuture<TimeSeriesAnalysis>> entry : futures.entrySet()) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                log.warn("[WikiAPI] Batch timeout reached after {}/{} items", results.size(), count);
                break;
            }
            try {
                TimeSeriesAnalysis ts = entry.getValue().get(remaining, TimeUnit.MILLISECONDS);
                if (ts != null) {
                    results.put(entry.getKey(), ts);
                }
            } catch (TimeoutException e) {
                log.warn("[WikiAPI] Timeout waiting for item {} in batch", entry.getKey());
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException e) {
                log.warn("[WikiAPI] Fetch failed for item {}: {}", entry.getKey(), e.getCause().getMessage());
            }
        }

        log.info("[WikiAPI] Batch complete: {}/{} timeseries fetched", results.size(), count);
        return results;
    }

    /** Returns ms since last timeseries request (for status display). */
    public long getMsSinceLastTsRequest() {
        return System.currentTimeMillis() - lastTsRequestTime.get();
    }

    /** Clears all caches (e.g. on plugin shutdown or manual refresh). */
    public void clearCaches() {
        mappingCache = Collections.emptyMap();
        latestCache = Collections.emptyMap();
        tsCache.clear();
        inFlight.clear();
        mappingFetchedAt = 0;
        latestFetchedAt = 0;
    }

    /** Shuts down the timeseries executor (call on plugin shutdown). */
    public void shutdown() {
        clearCaches();
        tsExecutor.shutdownNow();
    }

    // ════════════════════════════════════════════════════════════════════════
    // PRIVATE — THROTTLED FETCH
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Fetches a single timeseries item with throttling and exponential back-off.
     * Must only be called from the serial tsExecutor.
     */
    private TimeSeriesAnalysis fetchTimeSeriesThrottled(int itemId) {
        // Enforce minimum gap between requests
        enforceRateLimit();

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                lastTsRequestTime.set(System.currentTimeMillis());
                String url = TIMESERIES_URL + "?id=" + itemId + "&timestep=5m";
                HttpResponse<String> resp = sendGet(url);

                if (resp == null) {
                    backOff(attempt);
                    continue;
                }

                int status = resp.statusCode();

                if (status == 200) {
                    consecutiveFailures.set(0); // reset on success
                    return parseTimeSeries(resp.body());
                }

                if (status == 429) {
                    // Respect Retry-After header if present
                    long retryAfter = parseRetryAfter(resp);
                    log.warn("[WikiAPI] 429 rate-limited on item {} — waiting {}ms", itemId, retryAfter);
                    Thread.sleep(retryAfter);
                    consecutiveFailures.incrementAndGet();
                    continue;
                }

                if (status >= 500) {
                    log.warn("[WikiAPI] {}  on timeseries item {} (attempt {}/{})",
                            status, itemId, attempt + 1, MAX_RETRIES);
                    consecutiveFailures.incrementAndGet();
                    backOff(attempt);
                    continue;
                }

                // 4xx other than 429 — not retryable
                log.warn("[WikiAPI] {} fetching timeseries for item {}, skipping", status, itemId);
                return null;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                log.warn("[WikiAPI] Exception on timeseries item {} attempt {}: {}", itemId, attempt + 1, e.getMessage());
                try { backOff(attempt); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }

        log.error("[WikiAPI] Gave up on timeseries for item {} after {} retries", itemId, MAX_RETRIES);
        return null;
    }

    /** Blocks until the minimum gap since the last timeseries request has elapsed. */
    private void enforceRateLimit() {
        long last = lastTsRequestTime.get();
        long elapsed = System.currentTimeMillis() - last;
        long wait = TS_MIN_GAP_MS - elapsed;
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Exponential back-off: 2s, 4s, 8s, 16s … capped at 60s. */
    private void backOff(int attempt) throws InterruptedException {
        long delay = Math.min(BASE_BACKOFF_MS * (1L << attempt), MAX_BACKOFF_MS);
        log.info("[WikiAPI] Back-off: waiting {}ms (attempt {})", delay, attempt + 1);
        Thread.sleep(delay);
    }

    private long parseRetryAfter(HttpResponse<String> resp) {
        return resp.headers().firstValue("Retry-After")
                .map(v -> {
                    try { return Long.parseLong(v.trim()) * 1000L; }
                    catch (NumberFormatException e) { return BASE_BACKOFF_MS * 4; }
                })
                .orElse(BASE_BACKOFF_MS * 4);
    }

    // ════════════════════════════════════════════════════════════════════════
    // PRIVATE — BULK FETCHES (no per-item rate-limiting needed)
    // ════════════════════════════════════════════════════════════════════════

    private Map<Integer, ItemMappingData> fetchMapping() {
        try {
            HttpResponse<String> resp = sendGet(MAPPING_URL);
            if (resp != null && resp.statusCode() == 200) {
                return parseMapping(resp.body());
            }
            if (resp != null) log.warn("[WikiAPI] Mapping HTTP {}", resp.statusCode());
        } catch (Exception e) {
            log.error("[WikiAPI] Mapping fetch exception: {}", e.getMessage());
        }
        return Collections.emptyMap();
    }

    private Map<Integer, int[]> fetchLatest() {
        try {
            HttpResponse<String> resp = sendGet(LATEST_URL);
            if (resp != null && resp.statusCode() == 200) {
                return parseLatest(resp.body());
            }
            if (resp != null) log.warn("[WikiAPI] Latest HTTP {}", resp.statusCode());
        } catch (Exception e) {
            log.error("[WikiAPI] Latest fetch exception: {}", e.getMessage());
        }
        return Collections.emptyMap();
    }

    // ════════════════════════════════════════════════════════════════════════
    // PRIVATE — HTTP HELPER
    // ════════════════════════════════════════════════════════════════════════

    private HttpResponse<String> sendGet(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .GET()
                .timeout(Duration.ofSeconds(20))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    // ════════════════════════════════════════════════════════════════════════
    // PRIVATE — JSON PARSERS
    // ════════════════════════════════════════════════════════════════════════

    private Map<Integer, ItemMappingData> parseMapping(String json) {
        Map<Integer, ItemMappingData> result = new HashMap<>();
        try {
            JsonArray array = new JsonParser().parse(json).getAsJsonArray();
            for (JsonElement el : array) {
                JsonObject obj = el.getAsJsonObject();
                int id       = obj.get("id").getAsInt();
                String name  = getString(obj, "name", "Unknown");
                String exam  = getString(obj, "examine", "");
                boolean mem  = obj.has("members") && obj.get("members").getAsBoolean();
                int limit    = getInt(obj, "limit", 0);
                int value    = getInt(obj, "value", 0);
                int loAlch   = getInt(obj, "lowalch", 0);
                int hiAlch   = getInt(obj, "highalch", 0);
                String icon  = getString(obj, "icon", "");
                result.put(id, new ItemMappingData(id, name, exam, mem, limit, value, loAlch, hiAlch, icon));
            }
        } catch (Exception e) {
            log.error("[WikiAPI] Mapping parse error: {}", e.getMessage());
        }
        return result;
    }

    private Map<Integer, int[]> parseLatest(String json) {
        Map<Integer, int[]> result = new HashMap<>();
        try {
            JsonObject root = new JsonParser().parse(json).getAsJsonObject();
            JsonObject data = root.has("data") ? root.getAsJsonObject("data") : root;
            for (Map.Entry<String, JsonElement> entry : data.entrySet()) {
                try {
                    int id = Integer.parseInt(entry.getKey());
                    JsonObject p = entry.getValue().getAsJsonObject();
                    int high = getInt(p, "high", 0);
                    int low  = getInt(p, "low", 0);
                    result.put(id, new int[]{high, low});
                } catch (NumberFormatException ignored) {}
            }
        } catch (Exception e) {
            log.error("[WikiAPI] Latest parse error: {}", e.getMessage());
        }
        return result;
    }

    private TimeSeriesAnalysis parseTimeSeries(String json) {
        try {
            JsonObject root = new JsonParser().parse(json).getAsJsonObject();
            JsonArray data  = root.has("data") ? root.getAsJsonArray("data") : null;
            if (data == null || data.size() == 0) return null;

            List<TimeSeriesDataPoint> points = new ArrayList<>(data.size());
            for (JsonElement el : data) {
                JsonObject dp  = el.getAsJsonObject();
                long ts        = dp.has("timestamp")        ? dp.get("timestamp").getAsLong()        : 0;
                int avgHigh    = getInt(dp, "avgHighPrice",  0);
                int avgLow     = getInt(dp, "avgLowPrice",   0);
                int highVol    = getInt(dp, "highPriceVolume", 0);
                int lowVol     = getInt(dp, "lowPriceVolume",  0);
                points.add(new TimeSeriesDataPoint(ts, avgHigh, avgLow, highVol, lowVol));
            }
            return new TimeSeriesAnalysis(points, TimeSeriesInterval.FIVE_MINUTES);
        } catch (Exception e) {
            log.error("[WikiAPI] Timeseries parse error: {}", e.getMessage());
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // UTILITIES
    // ════════════════════════════════════════════════════════════════════════

    private static boolean isStale(long fetchedAt, long ttlMs) {
        return (System.currentTimeMillis() - fetchedAt) > ttlMs;
    }

    private static String getString(JsonObject obj, String key, String def) {
        return (obj.has(key) && !obj.get(key).isJsonNull()) ? obj.get(key).getAsString() : def;
    }

    private static int getInt(JsonObject obj, String key, int def) {
        try {
            return (obj.has(key) && !obj.get(key).isJsonNull()) ? obj.get(key).getAsInt() : def;
        } catch (Exception e) { return def; }
    }

    // ════════════════════════════════════════════════════════════════════════
    // INNER — CACHE ENTRY
    // ════════════════════════════════════════════════════════════════════════

    private static class CachedResult<T> {
        final T value;
        final long fetchedAt;
        CachedResult(T value) {
            this.value = value;
            this.fetchedAt = System.currentTimeMillis();
        }
    }
}
