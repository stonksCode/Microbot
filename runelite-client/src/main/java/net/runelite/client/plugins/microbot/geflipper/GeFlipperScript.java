package net.runelite.client.plugins.microbot.geflipper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.Global;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeAction;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeRequest;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeSlots;
import net.runelite.client.plugins.microbot.util.grandexchange.Rs2GrandExchange;
import net.runelite.client.plugins.microbot.util.grandexchange.models.ItemMappingData;
import net.runelite.client.plugins.microbot.util.grandexchange.models.TimeSeriesAnalysis;
import net.runelite.client.plugins.microbot.util.grandexchange.models.TimeSeriesDataPoint;
import net.runelite.client.plugins.microbot.util.grandexchange.models.TimeSeriesInterval;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.geflipper.models.FlipAnalysis;
import net.runelite.client.plugins.microbot.geflipper.models.FlipTracker;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Grand Exchange Flipping Script.
 * Automatically scans for profitable flip opportunities, places buy/sell offers,
 * monitors active offers for price changes, and cancels unprofitable offers.
 *
 * API Strategy:
 *   - /latest (bulk, all items) every 5 minutes — real-time buy/sell prices
 *   - /mapping (bulk, all items) every 30 minutes — item metadata, rarely changes
 *   - /timeseries (per-item) only for top 50 candidates after initial filter
 */
@Slf4j
public class GeFlipperScript extends Script {

    private GeFlipperConfig config;
    // Thread-safe collections: modified on script thread, read on client thread (overlay)
    private final Map<GrandExchangeSlots, FlipTracker> activeFlips = new ConcurrentHashMap<>();
    private final List<FlipAnalysis> analyzedItems = new CopyOnWriteArrayList<>();

    // Profit tracking — use long to prevent overflow
    private long sessionStartTime;
    private long sessionTotalProfit;
    private int sessionFlipsCompleted;

    // Dynamic budget tracking (inventory coins only)
    private int inventoryGP;
    private long lastBudgetCheck;
    private static final int COINS_ITEM_ID = 995;

    // API caching state
    private Map<Integer, ItemMappingData> cachedItemMapping = new HashMap<>();
    private Map<Integer, int[]> cachedLatestPrices = new HashMap<>(); // itemId -> [high, low]
    private long lastMappingFetch = 0;
    private long lastFullScan = 0;
    private long lastPriceRecheck = 0;
    private static final long MAPPING_CACHE_DURATION = 30 * 60 * 1000; // 30 min (rarely changes)
    private static final int TIMESERIES_CANDIDATE_LIMIT = 50; // Only fetch timeseries for top 50

    // API User-Agent (required by OSRS Wiki API)
    private static final String API_USER_AGENT = "Microbot-GE-Flipper/2.0";
    private static final String WIKI_LATEST_URL = "https://prices.runescape.wiki/api/v1/osrs/latest";
    private static final String WIKI_MAPPING_URL = "https://prices.runescape.wiki/api/v1/osrs/mapping";
    private static final String WIKI_TIMESERIES_URL = "https://prices.runescape.wiki/api/v1/osrs/timeseries";

    // Shared HTTP client with timeout
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build();

    // Helper for SLF4J-compatible number formatting
    private static String fmt(int n) { return String.format("%,d", n); }
    private static String fmt(long n) { return String.format("%,d", n); }

    /**
     * Main run method called by the plugin.
     */
    public boolean run(GeFlipperConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.sessionStartTime = System.currentTimeMillis();
        this.sessionTotalProfit = 0;
        this.sessionFlipsCompleted = 0;
        this.inventoryGP = 0;
        this.lastBudgetCheck = 0;
        this.lastFullScan = 0;
        this.lastMappingFetch = 0;
        this.lastPriceRecheck = 0;

        refreshInventoryGP();

        // Reconcile any existing GE offers before starting the loop
        reconcileExistingOffers();

        log.info("========================================");
        log.info("  GE FLIPPER STARTED (v2.0)");
        log.info("  Mode: {}", config.flipMode());
        log.info("  Min Profit Margin: {}%", config.minProfitMargin());
        log.info("  Active Slots: {}", config.slotCount());
        log.info("  Scan Interval: {} min", config.refreshIntervalMinutes());
        log.info("  Offer Timeout: {} min", config.offerTimeoutMinutes());
        log.info("  Cancel Unprofitable: {}", config.cancelUndercutOffers());
        log.info("  Available GP (inventory): {}", fmt(inventoryGP));
        log.info("========================================");

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn()) return;
                if (!super.run()) return;
                executeFlipLoop();
            } catch (Exception ex) {
                log.error("[GEFlipper] Unexpected error in flip loop: ", ex);
            }
        }, 0, 600, TimeUnit.MILLISECONDS);

        return true;
    }

    // ========================================
    // MAIN LOOP
    // ========================================

    private void executeFlipLoop() {
        long now = System.currentTimeMillis();

        // 1. Check and collect completed offers
        checkAndCollectFinishedOffers();

        // 2. Refresh inventory GP budget every 5 seconds
        if ((now - lastBudgetCheck) > 5000) {
            refreshInventoryGP();
            lastBudgetCheck = now;
        }

        // 3. Monitor active offers for stale/unprofitable prices
        if ((now - lastPriceRecheck) > (config.priceRecheckSeconds() * 1000L)) {
            if (config.cancelUndercutOffers()) {
                monitorActiveOffers();
            }
            checkStaleOffers(now);
            lastPriceRecheck = now;
        }

        // 4. Full market scan on interval
        long scanIntervalMs = config.refreshIntervalMinutes() * 60 * 1000L;
        if ((now - lastFullScan) >= scanIntervalMs) {
            Microbot.status = "GE Flipper: Scanning market...";
            scanForFlipOpportunities();
            lastFullScan = now;
        }

        // 5. Fill empty slots with best opportunities
        fillEmptySlots();

        // 6. Update status
        updateStatus();
    }

    // ========================================
    // BUDGET MANAGEMENT
    // ========================================

    private void refreshInventoryGP() {
        int totalGP = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            int gp = 0;
            List<Rs2ItemModel> coins = Rs2Inventory.items(
                Rs2ItemModel.matches(true, "Coins")
            ).collect(Collectors.toList());
            for (Rs2ItemModel coinStack : coins) {
                if (coinStack != null && coinStack.getId() == COINS_ITEM_ID) {
                    gp += coinStack.getQuantity();
                }
            }
            return gp;
        }).orElse(0);

        int previousGP = inventoryGP;
        inventoryGP = totalGP;

        if (inventoryGP != previousGP && previousGP > 0) {
            int diff = inventoryGP - previousGP;
            if (diff > 0) {
                log.info("[GEFlipper] Budget increased: {} -> {} (+{})",
                    fmt(previousGP), fmt(inventoryGP), fmt(diff));
            } else {
                log.info("[GEFlipper] Budget decreased: {} -> {} ({})",
                    fmt(previousGP), fmt(inventoryGP), fmt(diff));
            }
        }
    }

    private int getFreeGP() {
        if (inventoryGP <= 0) return 0;
        int committedBudget = 0;
        for (FlipTracker tracker : activeFlips.values()) {
            committedBudget += tracker.getCommittedGP();
        }
        return Math.max(0, inventoryGP - committedBudget);
    }

    // ========================================
    // OFFER COLLECTION & LIFECYCLE
    // ========================================

    private void checkAndCollectFinishedOffers() {
        GrandExchangeOffer[] offers = Microbot.getClientThread().runOnClientThreadOptional(() ->
            Microbot.getClient().getGrandExchangeOffers()
        ).orElse(new GrandExchangeOffer[0]);

        for (int i = 0; i < offers.length; i++) {
            GrandExchangeOffer offer = offers[i];
            if (offer == null || offer.getState() == null) continue;
            if (offer.getState() == GrandExchangeOfferState.EMPTY) continue;

            GrandExchangeSlots slot = GrandExchangeSlots.values()[i];
            FlipTracker tracker = activeFlips.get(slot);
            boolean isComplete = offer.getState() == GrandExchangeOfferState.BOUGHT
                || offer.getState() == GrandExchangeOfferState.SOLD
                || offer.getState() == GrandExchangeOfferState.CANCELLED_BUY
                || offer.getState() == GrandExchangeOfferState.CANCELLED_SELL;
            if (isComplete && tracker != null) {
                collectFinishedOffer(slot, tracker, offer);
            }
        }
    }

    /**
     * Collects a finished GE offer and transitions the flip state.
     * Handles collectToBank: withdraws items from bank if needed before selling.
     */
    private void collectFinishedOffer(GrandExchangeSlots slot, FlipTracker tracker, GrandExchangeOffer offer) {
        Microbot.status = "Collecting: " + tracker.itemName;

        // Collect the offer on client thread
        boolean collected = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            if (!Rs2GrandExchange.isOpen()) {
                Rs2GrandExchange.openExchange();
                Global.sleep(1500);
            }
            return Rs2GrandExchange.processOffer(
                GrandExchangeRequest.builder()
                    .action(GrandExchangeAction.COLLECT)
                    .slot(slot)
                    .toBank(config.collectToBank())
                    .build()
            );
        }).orElse(false);

        Global.sleep(2000);

        if (!collected) {
            log.warn("[GEFlipper] Failed to collect offer for {} in slot {}", tracker.itemName, slot);
            return;
        }

        if (offer.getState() == GrandExchangeOfferState.BOUGHT) {
            // Bought items — record actual quantity
            tracker.updateBought(offer.getQuantitySold(), offer.getTotalQuantity());
            tracker.state = FlipTracker.FlipState.BOUGHT;

            // If items were collected to bank, withdraw them for selling
            if (config.collectToBank()) {
                withdrawItemsForSell(tracker);
            }

            placeSellOffer(slot, tracker);

        } else if (offer.getState() == GrandExchangeOfferState.SOLD) {
            // Sold items — calculate real profit from GE data (use long to prevent overflow)
            int soldQty = offer.getQuantitySold();
            int buyPrice = tracker.buyPrice;
            int sellPrice = offer.getPrice() > 0 ? offer.getPrice() : tracker.sellPrice;
            long profit = (long) (sellPrice - buyPrice) * soldQty;

            sessionTotalProfit += profit;
            sessionFlipsCompleted++;
            log.info("[GEFlipper] Completed flip: {} — x{} sold, Profit: {} gp",
                tracker.itemName, soldQty, fmt(profit));

            tracker.state = FlipTracker.FlipState.COMPLETED;
            activeFlips.remove(slot);
            refreshInventoryGP();

            // Post-collection re-scan if data is stale
            if (analyzedItems.isEmpty() || isPriceDataStale()) {
                log.info("[GEFlipper] Price data stale, re-scanning before next buy...");
                scanForFlipOpportunities();
            }
            placeBuyOffer(slot, tracker.itemId, tracker.itemName);

        } else if (offer.getState() == GrandExchangeOfferState.CANCELLED_BUY
                || offer.getState() == GrandExchangeOfferState.CANCELLED_SELL) {
            activeFlips.remove(slot);
            refreshInventoryGP();
            log.info("[GEFlipper] Offer cancelled for {}", tracker.itemName);
        }
    }

    /**
     * Withdraws items from bank so they can be sold on the GE.
     * Called when collectToBank=true and a buy offer completed.
     */
    private void withdrawItemsForSell(FlipTracker tracker) {
        Microbot.status = "Withdrawing " + tracker.itemName + " from bank";
        log.info("[GEFlipper] Withdrawing {} from bank for selling", tracker.itemName);

        // Check if already in inventory (edge case)
        boolean alreadyInInventory = Microbot.getClientThread().runOnClientThreadOptional(() ->
            Rs2Inventory.hasItem(tracker.itemName, true)
        ).orElse(false);

        if (alreadyInInventory) {
            log.info("[GEFlipper] {} already in inventory, skipping withdraw", tracker.itemName);
            return;
        }

        Microbot.getClientThread().runOnClientThreadOptional(() -> {
            // Open bank if needed
            if (!net.runelite.client.plugins.microbot.util.bank.Rs2Bank.isOpen()) {
                net.runelite.client.plugins.microbot.util.bank.Rs2Bank.openBank();
                Global.sleep(1500);
            }
            // Withdraw item (withdraws all matching)
            return net.runelite.client.plugins.microbot.util.bank.Rs2Bank.withdrawItem(tracker.itemName);
        });

        // Wait for items to appear in inventory
        Global.sleepUntil(() -> Rs2Inventory.hasItem(tracker.itemName, true), 5000);
    }

    private boolean isPriceDataStale() {
        long maxAge = config.refreshIntervalMinutes() * 60 * 1000L * 2;
        return (System.currentTimeMillis() - lastFullScan) > maxAge;
    }

    // ========================================
    // OFFER MONITORING
    // ========================================

    private void monitorActiveOffers() {
        if (activeFlips.isEmpty() || cachedLatestPrices.isEmpty()) return;

        Map<Integer, int[]> freshPrices = fetchLatestPrices();
        if (!freshPrices.isEmpty()) {
            cachedLatestPrices = freshPrices;
        }

        List<GrandExchangeSlots> toCancel = new ArrayList<>();

        for (Map.Entry<GrandExchangeSlots, FlipTracker> entry : activeFlips.entrySet()) {
            GrandExchangeSlots slot = entry.getKey();
            FlipTracker tracker = entry.getValue();
            int[] prices = cachedLatestPrices.get(tracker.itemId);
            if (prices == null) continue;

            int currentHigh = prices[0]; // current sell price
            int currentLow = prices[1];  // current buy price

            if (tracker.state == FlipTracker.FlipState.BUYING) {
                int currentSpread = currentHigh - tracker.buyPrice;
                double currentMargin = tracker.buyPrice > 0
                    ? (double) currentSpread / tracker.buyPrice * 100.0 : 0;
                if (currentMargin < config.minProfitMargin()) {
                    log.info("[GEFlipper] Spread closed on {} (buying @ {}, sell now @ {}, margin {}%), cancelling",
                        tracker.itemName, fmt(tracker.buyPrice), fmt(currentHigh), String.format("%.1f", currentMargin));
                    toCancel.add(slot);
                }
            } else if (tracker.state == FlipTracker.FlipState.BOUGHT
                    || tracker.state == FlipTracker.FlipState.SELLING) {
                if (currentHigh < tracker.sellPrice) {
                    int currentProfit = currentHigh - tracker.buyPrice;
                    double currentMargin = tracker.buyPrice > 0
                        ? (double) currentProfit / tracker.buyPrice * 100.0 : 0;
                    if (currentMargin < config.minProfitMargin()) {
                        log.info("[GEFlipper] Sell no longer profitable on {} (margin {}%), cancelling",
                            tracker.itemName, String.format("%.1f", currentMargin));
                        toCancel.add(slot);
                    }
                }
            }
        }

        for (GrandExchangeSlots slot : toCancel) {
            cancelOffer(slot);
        }
    }

    private void checkStaleOffers(long now) {
        int timeoutMs = config.offerTimeoutMinutes() * 60 * 1000;
        if (timeoutMs <= 0) return;

        List<GrandExchangeSlots> toCancel = new ArrayList<>();
        for (Map.Entry<GrandExchangeSlots, FlipTracker> entry : activeFlips.entrySet()) {
            FlipTracker tracker = entry.getValue();
            if (tracker.state == FlipTracker.FlipState.BUYING) {
                long elapsed = now - tracker.startTime;
                if (elapsed > timeoutMs) {
                    log.info("[GEFlipper] Offer stale: {} ({} min elapsed, timeout {} min), cancelling",
                        tracker.itemName, String.format("%.0f", elapsed / 60000.0), config.offerTimeoutMinutes());
                    toCancel.add(entry.getKey());
                }
            }
        }
        for (GrandExchangeSlots slot : toCancel) {
            cancelOffer(slot);
        }
    }

    private void cancelOffer(GrandExchangeSlots slot) {
        FlipTracker tracker = activeFlips.get(slot);
        if (tracker == null) return;

        Microbot.status = "Cancelling: " + tracker.itemName;
        log.info("[GEFlipper] Cancelling offer for {} in slot {}", tracker.itemName, slot);

        // Cancel on client thread — only remove from tracking AFTER confirming success
        boolean cancelled = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            if (!Rs2GrandExchange.isOpen()) {
                Rs2GrandExchange.openExchange();
                Global.sleep(1500);
            }
            Rs2GrandExchange.cancelSpecificOffers(
                Collections.singletonList(slot), config.collectToBank());
            return true;
        }).orElse(false);

        Global.sleep(2000);

        if (cancelled) {
            activeFlips.remove(slot);
            refreshInventoryGP();
            log.info("[GEFlipper] Cancelled offer for {}, budget refreshed", tracker.itemName);
        } else {
            log.warn("[GEFlipper] Failed to cancel offer for {} in slot {}", tracker.itemName, slot);
        }
    }

    // ========================================
    // STARTUP RECONCILIATION
    // ========================================

    /**
     * Scans all GE slots at startup and handles any existing offers.
     * Handles: uncollected completed offers, active buys/sells, cancelled offers.
     */
    private void reconcileExistingOffers() {
        GrandExchangeOffer[] offers = Microbot.getClientThread().runOnClientThreadOptional(() ->
            Microbot.getClient().getGrandExchangeOffers()
        ).orElse(new GrandExchangeOffer[0]);

        int slotsToUse = config.slotCount();
        int found = 0;
        int collected = 0;
        int tracking = 0;

        for (int i = 0; i < slotsToUse && i < offers.length; i++) {
            GrandExchangeOffer offer = offers[i];
            if (offer == null || offer.getState() == null) continue;
            if (offer.getState() == GrandExchangeOfferState.EMPTY) continue;

            GrandExchangeSlots slot = GrandExchangeSlots.values()[i];
            int itemId = offer.getItemId();
            String itemName = Microbot.getClientThread().runOnClientThreadOptional(() -> {
                var def = Microbot.getItemManager().getItemComposition(itemId);
                return def != null ? def.getName() : "Item " + itemId;
            }).orElse("Item " + itemId);

            switch (offer.getState()) {
                case BOUGHT:
                case SOLD:
                    // Completed but uncollected — collect and transition
                    log.info("[GEFlipper] Reconciliation: uncollected {} offer in slot {} — {}",
                        offer.getState().name().toLowerCase(), slot, itemName);
                    FlipTracker tracker = new FlipTracker(
                        itemId, itemName, slot,
                        offer.getPrice(), offer.getPrice(), offer.getTotalQuantity());
                    if (offer.getState() == GrandExchangeOfferState.BOUGHT) {
                        tracker.state = FlipTracker.FlipState.BOUGHT;
                        tracker.updateBought(offer.getQuantitySold(), offer.getTotalQuantity());
                    } else {
                        tracker.state = FlipTracker.FlipState.SOLD;
                        tracker.updateSold(offer.getQuantitySold());
                    }
                    activeFlips.put(slot, tracker);
                    collectFinishedOffer(slot, tracker, offer);
                    collected++;
                    break;

                case BUYING:
                    // Active buy — track it, set sellPrice from current market if available
                    log.info("[GEFlipper] Reconciliation: tracking active buy in slot {} — {} x{} @ {}",
                        slot, itemName, offer.getQuantitySold() + "/" + offer.getTotalQuantity(),
                        fmt(offer.getPrice()));
                    int[] prices = cachedLatestPrices.get(itemId);
                    int estSellPrice = (prices != null && prices[0] > 0) ? prices[0] : (int) (offer.getPrice() * 1.03);
                    FlipTracker buyTracker = new FlipTracker(
                        itemId, itemName, slot,
                        offer.getPrice(), estSellPrice, offer.getTotalQuantity());
                    buyTracker.updateBought(offer.getQuantitySold(), offer.getTotalQuantity());
                    activeFlips.put(slot, buyTracker);
                    tracking++;
                    break;

                case SELLING:
                    // Active sell — track it
                    log.info("[GEFlipper] Reconciliation: tracking active sell in slot {} — {} x{} @ {}",
                        slot, itemName, offer.getQuantitySold() + "/" + offer.getTotalQuantity(),
                        fmt(offer.getPrice()));
                    int estBuyPrice = (int) (offer.getPrice() * 0.97);
                    FlipTracker sellTracker = new FlipTracker(
                        itemId, itemName, slot,
                        estBuyPrice, offer.getPrice(), offer.getTotalQuantity());
                    sellTracker.state = FlipTracker.FlipState.SELLING;
                    sellTracker.updateSold(offer.getQuantitySold());
                    activeFlips.put(slot, sellTracker);
                    tracking++;
                    break;

                case CANCELLED_BUY:
                case CANCELLED_SELL:
                    // Cancelled with items — collect to free slot
                    log.info("[GEFlipper] Reconciliation: cancelled offer in slot {} — collecting {}",
                        slot, itemName);
                    FlipTracker cancelTracker = new FlipTracker(
                        itemId, itemName, slot, offer.getPrice(), offer.getPrice(), offer.getTotalQuantity());
                    activeFlips.put(slot, cancelTracker);
                    collectFinishedOffer(slot, cancelTracker, offer);
                    collected++;
                    break;
            }
            found++;
        }

        if (found > 0) {
            log.info("[GEFlipper] Reconciliation complete: {} offers found, {} collected, {} now tracked",
                found, collected, tracking);
        } else {
            log.info("[GEFlipper] Reconciliation: no existing offers found, all slots available");
        }
    }

    // ========================================
    // MARKET SCANNING — Optimized two-stage
    // ========================================

    private void scanForFlipOpportunities() {
        analyzedItems.clear();
        try {
            // Stage 1: Cached mapping + bulk latest prices
            if (cachedItemMapping.isEmpty() ||
                (System.currentTimeMillis() - lastMappingFetch) > MAPPING_CACHE_DURATION) {
                cachedItemMapping = getItemMapping();
                lastMappingFetch = System.currentTimeMillis();
                log.info("[GEFlipper] Loaded {} items from mapping", cachedItemMapping.size());
            }

            Map<Integer, int[]> latestPrices = fetchLatestPrices();
            if (latestPrices.isEmpty()) {
                log.warn("[GEFlipper] Failed to fetch latest prices, using cached");
                latestPrices = cachedLatestPrices;
            } else {
                cachedLatestPrices = latestPrices;
            }
            log.info("[GEFlipper] Loaded prices for {} items", latestPrices.size());

            // Stage 2: Initial spread filter
            Set<String> blacklist = parseBlacklist();
            List<Candidate> candidates = new ArrayList<>();

            for (Map.Entry<Integer, ItemMappingData> entry : cachedItemMapping.entrySet()) {
                int itemId = entry.getKey();
                ItemMappingData metadata = entry.getValue();
                if (!shouldConsiderItem(metadata, blacklist)) continue;

                int[] prices = latestPrices.get(itemId);
                if (prices == null) continue;

                int highPrice = prices[0];
                int lowPrice = prices[1];
                if (highPrice <= 0 || lowPrice <= 0) continue;

                int spread = highPrice - lowPrice;
                double margin = lowPrice > 0 ? (double) spread / lowPrice * 100.0 : 0;
                if (margin < config.minProfitMargin()) continue;

                double initialScore = calculateInitialScore(spread, margin, metadata);
                candidates.add(new Candidate(itemId, metadata.name, metadata, highPrice, lowPrice, initialScore));
            }

            log.info("[GEFlipper] Found {} items with viable spreads", candidates.size());

            // Stage 3: Timeseries only for top N candidates
            candidates.sort((a, b) -> Double.compare(b.initialScore, a.initialScore));
            int timeseriesCount = Math.min(TIMESERIES_CANDIDATE_LIMIT, candidates.size());
            log.info("[GEFlipper] Running timeseries analysis on top {} candidates...", timeseriesCount);

            for (int i = 0; i < timeseriesCount; i++) {
                Candidate c = candidates.get(i);
                TimeSeriesAnalysis analysis = getTimeSeriesAnalysis(c.itemId);
                if (analysis == null || analysis.dataPoints.isEmpty()) continue;

                FlipAnalysis flipAnalysis = new FlipAnalysis(c.itemId, c.itemName, c.metadata, analysis);
                if (flipAnalysis.meetsMinProfit(config.minProfitMargin())) {
                    analyzedItems.add(flipAnalysis);
                }
            }

            analyzedItems.sort((a, b) -> Double.compare(b.flipScore, a.flipScore));

            log.info("[GEFlipper] Found {} viable flip opportunities", analyzedItems.size());
            if (!analyzedItems.isEmpty()) {
                log.info("[GEFlipper] Top 5 flips:");
                for (int i = 0; i < Math.min(5, analyzedItems.size()); i++) {
                    log.info("  {}. {}", i + 1, analyzedItems.get(i));
                }
            }
        } catch (Exception ex) {
            log.error("[GEFlipper] Failed to scan for flip opportunities: ", ex);
        }
    }

    private double calculateInitialScore(int spread, double margin, ItemMappingData metadata) {
        double profitScore = Math.min(40.0, spread / 100.0 * 40.0);
        double marginScore = Math.min(30.0, margin * 3.0);
        double volumeScore = Math.min(20.0, metadata.getEffectiveTradeLimit() / 50.0 * 20.0);
        return profitScore + marginScore + volumeScore + 5.0;
    }

    private static class Candidate {
        final int itemId;
        final String itemName;
        final ItemMappingData metadata;
        final double initialScore;
        Candidate(int itemId, String itemName, ItemMappingData metadata,
                  int highPrice, int lowPrice, double initialScore) {
            this.itemId = itemId;
            this.itemName = itemName;
            this.metadata = metadata;
            this.initialScore = initialScore;
        }
    }

    // ========================================
    // SLOT FILLING — Best affordable first
    // ========================================

    private void fillEmptySlots() {
        int slotsToUse = config.slotCount();
        GrandExchangeOffer[] offers = Microbot.getClientThread().runOnClientThreadOptional(() ->
            Microbot.getClient().getGrandExchangeOffers()
        ).orElse(new GrandExchangeOffer[0]);

        for (int i = 0; i < slotsToUse && i < offers.length; i++) {
            GrandExchangeOffer offer = offers[i];
            // Null-safe check
            if (offer == null) continue;
            GrandExchangeSlots slot = GrandExchangeSlots.values()[i];
            if (!activeFlips.containsKey(slot) && offer.getState() == GrandExchangeOfferState.EMPTY) {
                FlipAnalysis bestAffordable = findBestAffordableFlip();
                if (bestAffordable != null) {
                    placeBuyOffer(slot, bestAffordable.itemId, bestAffordable.itemName);
                }
                break;
            }
        }
    }

    private FlipAnalysis findBestAffordableFlip() {
        int availableGP = getFreeGP();
        if (availableGP <= 0) return null;

        FlipAnalysis best = null;
        double bestEffectiveScore = -1;

        for (FlipAnalysis fa : analyzedItems) {
            int buyPrice = (int) (fa.recommendedBuyPrice * (1.0 + config.buyPriceOffset() / 100.0));
            if (buyPrice <= 0) continue;

            int affordableQty = Math.min(fa.maxFlipQuantity, availableGP / buyPrice);
            if (affordableQty <= 0) continue;

            if (config.maxInvestmentPerSlot() > 0) {
                int maxByConfig = config.maxInvestmentPerSlot() / buyPrice;
                affordableQty = Math.min(affordableQty, maxByConfig);
                if (affordableQty <= 0) continue;
            }

            int totalProfit = fa.profitPerUnit * affordableQty;
            double effectiveScore = (fa.flipScore * 0.6) + ((totalProfit / 100.0) * 0.4);

            if (effectiveScore > bestEffectiveScore) {
                bestEffectiveScore = effectiveScore;
                best = fa;
            }
        }
        return best;
    }

    // ========================================
    // BUY / SELL OFFER PLACEMENT
    // ========================================

    private void placeBuyOffer(GrandExchangeSlots slot, int itemId, String itemName) {
        Microbot.status = "Buying: " + itemName;

        FlipAnalysis analysis = analyzedItems.stream()
            .filter(a -> a.itemId == itemId)
            .findFirst()
            .orElse(null);

        if (analysis == null) {
            log.warn("[GEFlipper] No analysis found for {}, skipping", itemName);
            return;
        }

        int buyPrice = (int) (analysis.recommendedBuyPrice * (1.0 + config.buyPriceOffset() / 100.0));
        int quantity = analysis.maxFlipQuantity;

        int availableGP = getFreeGP();
        if (availableGP > 0) {
            quantity = Math.min(quantity, availableGP / buyPrice);
        }
        if (config.maxInvestmentPerSlot() > 0) {
            quantity = Math.min(quantity, config.maxInvestmentPerSlot() / buyPrice);
        }

        if (quantity <= 0) {
            log.warn("[GEFlipper] Cannot afford {} (need {} gp each, have {} gp free)",
                itemName, fmt(buyPrice), fmt(availableGP));
            return;
        }

        final int fBuyPrice = buyPrice;
        final int fQuantity = quantity;
        final String fItemName = itemName;
        final GrandExchangeSlots fSlot = slot;
        final int fItemId = itemId;

        // Ensure GE is open before placing offer
        Microbot.getClientThread().runOnClientThreadOptional(() -> {
            if (!Rs2GrandExchange.isOpen()) {
                Rs2GrandExchange.openExchange();
                Global.sleep(3000); // Give client time to render GE UI
            }
            return true;
        });

        log.info("[GEFlipper] Placing buy offer: {} x{} @ {} gp (free GP: {})",
            fItemName, fQuantity, fmt(fBuyPrice), fmt(availableGP));

        boolean success = Microbot.getClientThread().runOnClientThreadOptional(() ->
            Rs2GrandExchange.processOffer(
                GrandExchangeRequest.builder()
                    .action(GrandExchangeAction.BUY)
                    .itemName(fItemName)
                    .exact(true)
                    .quantity(fQuantity)
                    .price(fBuyPrice)
                    .slot(fSlot)
                    .build()
            )
        ).orElse(false);

        // Extra delay after offer to let GE state settle
        Global.sleep(2000);

        if (success) {
            int cost = buyPrice * quantity;
            FlipTracker tracker = new FlipTracker(fItemId, fItemName, fSlot, buyPrice, analysis.recommendedSellPrice, quantity);
            activeFlips.put(fSlot, tracker);
            log.info("[GEFlipper] Buy offer placed: {} x{} @ {} gp (cost: {})",
                fItemName, fQuantity, fmt(fBuyPrice), fmt(cost));
        } else {
            log.error("[GEFlipper] Failed to place buy offer for {}", itemName);
        }
        Global.sleep(1500);
    }

    private void placeSellOffer(GrandExchangeSlots slot, FlipTracker tracker) {
        Microbot.status = "Selling: " + tracker.itemName;

        int sellPrice = tracker.sellPrice;
        FlipAnalysis analysis = analyzedItems.stream()
            .filter(a -> a.itemId == tracker.itemId)
            .findFirst()
            .orElse(null);
        if (analysis != null) {
            sellPrice = (int) (analysis.recommendedSellPrice * (1.0 - config.sellPriceOffset() / 100.0));
        }

        // Guard: don't sell below buy price
        if (sellPrice <= tracker.buyPrice) {
            sellPrice = (int) (tracker.buyPrice * 1.01);
            log.info("[GEFlipper] Adjusted sell price to {} (minimum above buy price {})",
                fmt(sellPrice), fmt(tracker.buyPrice));
        }

        final int fSellPrice = sellPrice;
        final int fQuantity = tracker.totalQuantity;
        final String fItemName = tracker.itemName;
        final GrandExchangeSlots fSlot = slot;

        boolean success = Microbot.getClientThread().runOnClientThreadOptional(() ->
            Rs2GrandExchange.processOffer(
                GrandExchangeRequest.builder()
                    .action(GrandExchangeAction.SELL)
                    .itemName(fItemName)
                    .exact(true)
                    .quantity(fQuantity)
                    .price(fSellPrice)
                    .slot(fSlot)
                    .build()
            )
        ).orElse(false);

        if (success) {
            tracker.state = FlipTracker.FlipState.SELLING;
            log.info("[GEFlipper] Placed sell offer: {} x{} @ {} gp", fItemName, fQuantity, fmt(fSellPrice));
        } else {
            log.error("[GEFlipper] Failed to place sell offer for {}", tracker.itemName);
        }
        Global.sleep(1500);
    }

    // ========================================
    // API FETCH METHODS
    // ========================================

    private Map<Integer, ItemMappingData> getItemMapping() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(java.net.URI.create(WIKI_MAPPING_URL))
                .header("User-Agent", API_USER_AGENT)
                .GET()
                .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return parseItemMapping(response.body());
            }
        } catch (Exception ex) {
            log.error("[GEFlipper] Failed to fetch item mapping: ", ex);
        }
        return Collections.emptyMap();
    }

    private Map<Integer, ItemMappingData> parseItemMapping(String json) {
        Map<Integer, ItemMappingData> result = new HashMap<>();
        try {
            JsonParser parser = new JsonParser();
            JsonArray array = parser.parse(json).getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                JsonObject obj = array.get(i).getAsJsonObject();
                int id = obj.get("id").getAsInt();
                String name = obj.has("name") ? obj.get("name").getAsString() : "Unknown";
                String examine = obj.has("examine") ? obj.get("examine").getAsString() : "";
                boolean members = obj.has("members") && obj.get("members").getAsBoolean();
                int limit = obj.has("limit") ? obj.get("limit").getAsInt() : 0;
                int value = obj.has("value") ? obj.get("value").getAsInt() : 0;
                int lowAlch = obj.has("lowalch") ? obj.get("lowalch").getAsInt() : 0;
                int highAlch = obj.has("highalch") ? obj.get("highalch").getAsInt() : 0;
                String icon = obj.has("icon") ? obj.get("icon").getAsString() : "";
                result.put(id, new ItemMappingData(id, name, examine, members, limit, value, lowAlch, highAlch, icon));
            }
        } catch (Exception ex) {
            log.error("[GEFlipper] Failed to parse item mapping: ", ex);
        }
        return result;
    }

    private Map<Integer, int[]> fetchLatestPrices() {
        Map<Integer, int[]> result = new HashMap<>();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(java.net.URI.create(WIKI_LATEST_URL))
                .header("User-Agent", API_USER_AGENT)
                .GET()
                .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonParser parser = new JsonParser();
                JsonObject root = parser.parse(response.body()).getAsJsonObject();
                JsonObject data = root.has("data") ? root.getAsJsonObject("data") : root;
                for (Map.Entry<String, JsonElement> entry : data.entrySet()) {
                    try {
                        int itemId = Integer.parseInt(entry.getKey());
                        JsonObject priceObj = entry.getValue().getAsJsonObject();
                        int high = priceObj.has("high") && !priceObj.get("high").isJsonNull()
                            ? priceObj.get("high").getAsInt() : 0;
                        int low = priceObj.has("low") && !priceObj.get("low").isJsonNull()
                            ? priceObj.get("low").getAsInt() : 0;
                        result.put(itemId, new int[]{high, low});
                    } catch (NumberFormatException e) {
                        // skip
                    }
                }
            }
        } catch (Exception ex) {
            log.error("[GEFlipper] Failed to fetch latest prices: ", ex);
        }
        return result;
    }

    private TimeSeriesAnalysis getTimeSeriesAnalysis(int itemId) {
        try {
            String url = String.format("%s?id=%d&timestep=5m", WIKI_TIMESERIES_URL, itemId);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .header("User-Agent", API_USER_AGENT)
                .GET()
                .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonParser parser = new JsonParser();
                JsonObject root = parser.parse(response.body()).getAsJsonObject();
                JsonArray data = root.has("data") ? root.getAsJsonArray("data") : null;
                if (data == null || data.size() == 0) return null;

                List<TimeSeriesDataPoint> points = new ArrayList<>();
                for (int i = 0; i < data.size(); i++) {
                    JsonObject dp = data.get(i).getAsJsonObject();
                    long timestamp = dp.has("timestamp") ? dp.get("timestamp").getAsLong() : 0;
                    int avgHigh = dp.has("avgHighPrice") && !dp.get("avgHighPrice").isJsonNull()
                        ? dp.get("avgHighPrice").getAsInt() : 0;
                    int avgLow = dp.has("avgLowPrice") && !dp.get("avgLowPrice").isJsonNull()
                        ? dp.get("avgLowPrice").getAsInt() : 0;
                    int highVol = dp.has("highPriceVolume") && !dp.get("highPriceVolume").isJsonNull()
                        ? dp.get("highPriceVolume").getAsInt() : 0;
                    int lowVol = dp.has("lowPriceVolume") && !dp.get("lowPriceVolume").isJsonNull()
                        ? dp.get("lowPriceVolume").getAsInt() : 0;
                    points.add(new TimeSeriesDataPoint(timestamp, avgHigh, avgLow, highVol, lowVol));
                }
                return new TimeSeriesAnalysis(points, TimeSeriesInterval.FIVE_MINUTES);
            }
        } catch (Exception ex) {
            log.error("[GEFlipper] Failed to fetch timeseries for item {}: ", itemId, ex);
        }
        return null;
    }

    // ========================================
    // FILTERS & HELPERS
    // ========================================

    private boolean shouldConsiderItem(ItemMappingData metadata, Set<String> blacklist) {
        if (blacklist.contains(metadata.name.toLowerCase())) return false;
        if (metadata.members && !config.includeMembersItems()) return false;
        if (config.maxTradeLimit() > 0 && metadata.tradeLimitPer4Hours > config.maxTradeLimit()) return false;
        return true;
    }

    private Set<String> parseBlacklist() {
        String blacklistStr = config.itemBlacklist();
        if (blacklistStr == null || blacklistStr.trim().isEmpty()) return Collections.emptySet();
        return Arrays.stream(blacklistStr.split(","))
            .map(String::trim)
            .map(String::toLowerCase)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toSet());
    }

    // ========================================
    // STATUS & STATS
    // ========================================

    private void updateStatus() {
        Microbot.status = String.format("GE Flipper: %d active, %d done, %s gp",
            activeFlips.size(), sessionFlipsCompleted, fmt(sessionTotalProfit));
    }

    public List<FlipAnalysis> getAnalyzedItems() {
        return Collections.unmodifiableList(new ArrayList<>(analyzedItems));
    }

    public Map<GrandExchangeSlots, FlipTracker> getActiveFlips() {
        return Collections.unmodifiableMap(new HashMap<>(activeFlips));
    }

    public Map<String, Object> getSessionStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("sessionProfit", sessionTotalProfit);
        stats.put("flipsCompleted", sessionFlipsCompleted);
        stats.put("activeFlips", activeFlips.size());
        stats.put("elapsedMinutes", (System.currentTimeMillis() - sessionStartTime) / 60000);
        stats.put("analyzedOpportunities", analyzedItems.size());
        stats.put("inventoryGP", inventoryGP);
        return stats;
    }

    @Override
    public void shutdown() {
        super.shutdown();
        activeFlips.clear();
        analyzedItems.clear();
        cachedItemMapping.clear();
        cachedLatestPrices.clear();
        log.info("[GEFlipper] Script shut down. Final profit: {} gp from {} flips",
            fmt(sessionTotalProfit), sessionFlipsCompleted);
    }
}
