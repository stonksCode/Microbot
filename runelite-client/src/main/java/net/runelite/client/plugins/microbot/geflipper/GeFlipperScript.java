package net.runelite.client.plugins.microbot.geflipper;

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
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.geflipper.models.FlipAnalysis;
import net.runelite.client.plugins.microbot.geflipper.models.FlipTracker;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Grand Exchange Flipping Script.
 *
 * Automatically scans for profitable flip opportunities via the OSRS Wiki Prices API,
 * places buy/sell offers, monitors active offers, and cancels unprofitable ones.
 *
 * All API calls are delegated to {@link WikiPriceClient}, which handles rate-limiting,
 * caching, retries and exponential back-off so this script never hammers the API.
 */
@Slf4j
public class GeFlipperScript extends Script {

    private static final String PLUGIN_VERSION = "2.1";
    private static final int COINS_ITEM_ID = 995;

    private GeFlipperConfig config;
    private WikiPriceClient wikiClient;

    // Thread-safe: modified on script thread, read on client thread (overlay)
    private final Map<GrandExchangeSlots, FlipTracker> activeFlips = new ConcurrentHashMap<>();
    private final List<FlipAnalysis> analyzedItems = new CopyOnWriteArrayList<>();

    // Session stats
    private long sessionStartTime;
    private long sessionTotalProfit;
    private int  sessionFlipsCompleted;

    // GP budget (inventory coins only)
    private int  inventoryGP;
    private long lastBudgetCheckMs;

    // Loop timing
    private long lastFullScanMs;
    private long lastPriceRecheckMs;

    // ── Helpers ────────────────────────────────────────────────────────────
    private static String fmt(int n)  { return String.format("%,d", n); }
    private static String fmt(long n) { return String.format("%,d", n); }

    // ════════════════════════════════════════════════════════════════════════
    // STARTUP / SHUTDOWN
    // ════════════════════════════════════════════════════════════════════════

    public boolean run(GeFlipperConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.wikiClient = new WikiPriceClient(PLUGIN_VERSION);
        this.sessionStartTime = System.currentTimeMillis();
        this.sessionTotalProfit = 0;
        this.sessionFlipsCompleted = 0;
        this.inventoryGP = 0;
        this.lastBudgetCheckMs = 0;
        this.lastFullScanMs = 0;
        this.lastPriceRecheckMs = 0;

        refreshInventoryGP();
        reconcileExistingOffers();

        log.info("========================================");
        log.info("  GE FLIPPER STARTED (v{})", PLUGIN_VERSION);
        log.info("  Mode       : {}", config.flipMode());
        log.info("  Min Margin : {}%", config.minProfitMargin());
        log.info("  Min Volume : {} per 4h", config.minVolume());
        log.info("  Active Slots: {}", config.slotCount());
        log.info("  Scan Interval: {} min", config.refreshIntervalMinutes());
        log.info("  TS Candidates: {}", config.timeseriesCandidateLimit());
        log.info("  Available GP : {}", fmt(inventoryGP));
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

    @Override
    public void shutdown() {
        super.shutdown();
        if (wikiClient != null) wikiClient.shutdown();
        activeFlips.clear();
        analyzedItems.clear();
        log.info("[GEFlipper] Shutdown. Final profit: {} gp over {} flips",
                fmt(sessionTotalProfit), sessionFlipsCompleted);
    }

    // ════════════════════════════════════════════════════════════════════════
    // MAIN LOOP
    // ════════════════════════════════════════════════════════════════════════

    private void executeFlipLoop() {
        long now = System.currentTimeMillis();

        // 1. Collect any completed/cancelled offers
        checkAndCollectFinishedOffers();

        // 2. Refresh GP budget every 5 s
        if ((now - lastBudgetCheckMs) > 5_000) {
            refreshInventoryGP();
            lastBudgetCheckMs = now;
        }

        // 3. Monitor active offers for stale / undercut prices
        if ((now - lastPriceRecheckMs) > (config.priceRecheckSeconds() * 1_000L)) {
            if (config.cancelUndercutOffers()) monitorActiveOffers();
            checkStaleOffers(now);
            lastPriceRecheckMs = now;
        }

        // 4. Full market scan on interval
        long scanIntervalMs = config.refreshIntervalMinutes() * 60_000L;
        if ((now - lastFullScanMs) >= scanIntervalMs) {
            Microbot.status = "GE Flipper: Scanning market...";
            scanForFlipOpportunities();
            lastFullScanMs = now;
        }

        // 5. Fill any empty slots
        fillEmptySlots();

        // 6. Update HUD status
        updateStatus();
    }

    // ════════════════════════════════════════════════════════════════════════
    // BUDGET MANAGEMENT
    // ════════════════════════════════════════════════════════════════════════

    private void refreshInventoryGP() {
        int totalGP = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            int gp = 0;
            List<Rs2ItemModel> coins = Rs2Inventory.items(
                Rs2ItemModel.matches(true, "Coins")
            ).collect(Collectors.toList());
            for (Rs2ItemModel coin : coins) {
                if (coin != null && coin.getId() == COINS_ITEM_ID) {
                    gp += coin.getQuantity();
                }
            }
            return gp;
        }).orElse(0);

        int prev = inventoryGP;
        inventoryGP = totalGP;

        if (inventoryGP != prev && prev > 0) {
            int diff = inventoryGP - prev;
            log.info("[GEFlipper] GP {} → {} ({}{} gp)",
                    fmt(prev), fmt(inventoryGP), diff > 0 ? "+" : "", fmt(diff));
        }
    }

    /**
     * Returns GP available for new buy orders.
     * FIXED: committed GP now accounts for the FULL order cost (totalQuantity * buyPrice),
     * not just the partial quantity received so far.
     */
    private int getFreeGP() {
        if (inventoryGP <= 0) return 0;
        int committed = 0;
        for (FlipTracker t : activeFlips.values()) {
            committed += t.getCommittedGP();
        }
        return Math.max(0, inventoryGP - committed);
    }

    // ════════════════════════════════════════════════════════════════════════
    // OFFER COLLECTION & LIFECYCLE
    // ════════════════════════════════════════════════════════════════════════

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

    private void collectFinishedOffer(GrandExchangeSlots slot, FlipTracker tracker, GrandExchangeOffer offer) {
        Microbot.status = "Collecting: " + tracker.itemName;

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
            log.warn("[GEFlipper] Failed to collect {} in slot {}", tracker.itemName, slot);
            return;
        }

        switch (offer.getState()) {
            case BOUGHT:
                tracker.updateBought(offer.getQuantitySold(), offer.getTotalQuantity());
                tracker.state = FlipTracker.FlipState.BOUGHT;
                if (config.collectToBank()) withdrawItemsForSell(tracker);
                placeSellOffer(slot, tracker);
                break;

            case SOLD:
                int soldQty   = offer.getQuantitySold();
                int sellPrice = offer.getPrice() > 0 ? offer.getPrice() : tracker.sellPrice;
                long profit   = (long) (sellPrice - tracker.buyPrice) * soldQty;
                sessionTotalProfit += profit;
                sessionFlipsCompleted++;

                log.info("[GEFlipper] ✓ Completed: {} x{} | Profit: {} gp | Session: {} gp",
                        tracker.itemName, soldQty, fmt(profit), fmt(sessionTotalProfit));

                tracker.state = FlipTracker.FlipState.COMPLETED;
                activeFlips.remove(slot);
                refreshInventoryGP();

                // FIXED: after a sell completes, find the BEST new opportunity rather than
                // re-buying the same item. Re-scan if data is stale first.
                if (analyzedItems.isEmpty() || isPriceDataStale()) {
                    log.info("[GEFlipper] Data stale — re-scanning before placing next buy...");
                    scanForFlipOpportunities();
                }
                // Slot is now empty; fillEmptySlots() in the next loop tick will pick the best item.
                break;

            case CANCELLED_BUY:
            case CANCELLED_SELL:
                activeFlips.remove(slot);
                refreshInventoryGP();
                log.info("[GEFlipper] Offer cancelled for {}", tracker.itemName);
                break;
        }
    }

    private void withdrawItemsForSell(FlipTracker tracker) {
        Microbot.status = "Withdrawing " + tracker.itemName;
        log.info("[GEFlipper] Withdrawing {} from bank for sell", tracker.itemName);

        // Check inventory first to avoid a redundant bank trip
        boolean alreadyHeld = Microbot.getClientThread().runOnClientThreadOptional(() ->
            Rs2Inventory.hasItem(tracker.itemId)
        ).orElse(false);

        if (alreadyHeld) {
            log.info("[GEFlipper] {} already in inventory, skipping withdraw", tracker.itemName);
            return;
        }

        boolean withdrawn = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            if (!net.runelite.client.plugins.microbot.util.bank.Rs2Bank.isOpen()) {
                net.runelite.client.plugins.microbot.util.bank.Rs2Bank.openBank();
                Global.sleepUntil(net.runelite.client.plugins.microbot.util.bank.Rs2Bank::isOpen, 5000);
            }
            // Use item ID instead of name to handle noted/variant items correctly
            return net.runelite.client.plugins.microbot.util.bank.Rs2Bank.withdrawX(tracker.itemId, tracker.totalQuantity);
        }).orElse(false);

        // Wait for items to appear in inventory (up to 5 s)
        if (withdrawn) {
            Global.sleepUntil(() -> Microbot.getClientThread()
                    .runOnClientThreadOptional(() -> Rs2Inventory.hasItem(tracker.itemId))
                    .orElse(false), 5000);
        }
    }

    private boolean isPriceDataStale() {
        long maxAgeMs = config.refreshIntervalMinutes() * 60_000L * 2;
        return (System.currentTimeMillis() - lastFullScanMs) > maxAgeMs;
    }

    // ════════════════════════════════════════════════════════════════════════
    // OFFER MONITORING
    // ════════════════════════════════════════════════════════════════════════

    private void monitorActiveOffers() {
        if (activeFlips.isEmpty()) return;

        Map<Integer, int[]> prices = wikiClient.getLatestPrices();
        if (prices.isEmpty()) return;

        List<GrandExchangeSlots> toCancel = new ArrayList<>();

        for (Map.Entry<GrandExchangeSlots, FlipTracker> entry : activeFlips.entrySet()) {
            GrandExchangeSlots slot = entry.getKey();
            FlipTracker tracker = entry.getValue();
            int[] p = prices.get(tracker.itemId);
            if (p == null) continue;

            int currentHigh = p[0];

            if (tracker.state == FlipTracker.FlipState.BUYING) {
                int spread = currentHigh - tracker.buyPrice;
                double margin = tracker.buyPrice > 0 ? (double) spread / tracker.buyPrice * 100.0 : 0;
                if (margin < config.minProfitMargin()) {
                    log.info("[GEFlipper] Spread closed on {} (margin {:.1f}%), cancelling buy",
                            tracker.itemName, margin);
                    toCancel.add(slot);
                }
            } else if (tracker.state == FlipTracker.FlipState.BOUGHT
                    || tracker.state == FlipTracker.FlipState.SELLING) {
                int currentProfit = currentHigh - tracker.buyPrice;
                double margin = tracker.buyPrice > 0 ? (double) currentProfit / tracker.buyPrice * 100.0 : 0;
                if (margin < config.minProfitMargin()) {
                    log.info("[GEFlipper] Sell no longer profitable on {} (margin {:.1f}%), cancelling",
                            tracker.itemName, margin);
                    toCancel.add(slot);
                }
            }
        }

        toCancel.forEach(this::cancelOffer);
    }

    private void checkStaleOffers(long now) {
        int timeoutMs = config.offerTimeoutMinutes() * 60_000;
        if (timeoutMs <= 0) return;

        activeFlips.entrySet().stream()
            .filter(e -> e.getValue().state == FlipTracker.FlipState.BUYING
                    && (now - e.getValue().startTime) > timeoutMs)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList())
            .forEach(slot -> {
                log.info("[GEFlipper] Offer timed out: {} ({}min), cancelling",
                        activeFlips.get(slot).itemName, config.offerTimeoutMinutes());
                cancelOffer(slot);
            });
    }

    private void cancelOffer(GrandExchangeSlots slot) {
        FlipTracker tracker = activeFlips.get(slot);
        if (tracker == null) return;

        Microbot.status = "Cancelling: " + tracker.itemName;
        log.info("[GEFlipper] Cancelling {} in slot {}", tracker.itemName, slot);

        boolean ok = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            if (!Rs2GrandExchange.isOpen()) {
                Rs2GrandExchange.openExchange();
                Global.sleep(1500);
            }
            Rs2GrandExchange.cancelSpecificOffers(Collections.singletonList(slot), config.collectToBank());
            return true;
        }).orElse(false);

        Global.sleep(2000);

        if (ok) {
            activeFlips.remove(slot);
            refreshInventoryGP();
        } else {
            log.warn("[GEFlipper] Failed to cancel {} in slot {}", tracker.itemName, slot);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // STARTUP RECONCILIATION
    // ════════════════════════════════════════════════════════════════════════

    private void reconcileExistingOffers() {
        GrandExchangeOffer[] offers = Microbot.getClientThread().runOnClientThreadOptional(() ->
            Microbot.getClient().getGrandExchangeOffers()
        ).orElse(new GrandExchangeOffer[0]);

        int slotsToUse = config.slotCount();
        int found = 0, collected = 0, tracking = 0;

        for (int i = 0; i < slotsToUse && i < offers.length; i++) {
            GrandExchangeOffer offer = offers[i];
            if (offer == null || offer.getState() == null
                    || offer.getState() == GrandExchangeOfferState.EMPTY) continue;

            GrandExchangeSlots slot = GrandExchangeSlots.values()[i];
            int itemId = offer.getItemId();
            String itemName = Microbot.getClientThread().runOnClientThreadOptional(() -> {
                var def = Microbot.getItemManager().getItemComposition(itemId);
                return def != null ? def.getName() : "Item " + itemId;
            }).orElse("Item " + itemId);

            switch (offer.getState()) {
                case BOUGHT:
                case SOLD: {
                    log.info("[GEFlipper] Reconcile: uncollected {} — {}", offer.getState(), itemName);
                    FlipTracker t = new FlipTracker(itemId, itemName, slot,
                            offer.getPrice(), offer.getPrice(), offer.getTotalQuantity());
                    if (offer.getState() == GrandExchangeOfferState.BOUGHT) {
                        t.state = FlipTracker.FlipState.BOUGHT;
                        t.updateBought(offer.getQuantitySold(), offer.getTotalQuantity());
                    } else {
                        t.state = FlipTracker.FlipState.SOLD;
                        t.updateSold(offer.getQuantitySold());
                    }
                    activeFlips.put(slot, t);
                    collectFinishedOffer(slot, t, offer);
                    collected++;
                    break;
                }
                case BUYING: {
                    log.info("[GEFlipper] Reconcile: active buy — {} @ {}", itemName, fmt(offer.getPrice()));
                    int[] p = wikiClient.getLatestPrices().getOrDefault(itemId, null);
                    int estSell = (p != null && p[0] > 0) ? p[0] : (int) (offer.getPrice() * 1.03);
                    FlipTracker t = new FlipTracker(itemId, itemName, slot,
                            offer.getPrice(), estSell, offer.getTotalQuantity());
                    t.updateBought(offer.getQuantitySold(), offer.getTotalQuantity());
                    activeFlips.put(slot, t);
                    tracking++;
                    break;
                }
                case SELLING: {
                    log.info("[GEFlipper] Reconcile: active sell — {} @ {}", itemName, fmt(offer.getPrice()));
                    int estBuy = (int) (offer.getPrice() * 0.97);
                    FlipTracker t = new FlipTracker(itemId, itemName, slot,
                            estBuy, offer.getPrice(), offer.getTotalQuantity());
                    t.state = FlipTracker.FlipState.SELLING;
                    t.updateSold(offer.getQuantitySold());
                    activeFlips.put(slot, t);
                    tracking++;
                    break;
                }
                case CANCELLED_BUY:
                case CANCELLED_SELL: {
                    log.info("[GEFlipper] Reconcile: cancelled offer — collecting {}", itemName);
                    FlipTracker t = new FlipTracker(itemId, itemName, slot,
                            offer.getPrice(), offer.getPrice(), offer.getTotalQuantity());
                    activeFlips.put(slot, t);
                    collectFinishedOffer(slot, t, offer);
                    collected++;
                    break;
                }
            }
            found++;
        }

        log.info("[GEFlipper] Reconcile: {} offers found, {} collected, {} tracked",
                found, collected, tracking);
    }

    // ════════════════════════════════════════════════════════════════════════
    // MARKET SCANNING — two-stage with async timeseries
    // ════════════════════════════════════════════════════════════════════════

    private void scanForFlipOpportunities() {
        analyzedItems.clear();
        try {
            // Stage 1: Bulk mapping + latest prices (both cached by WikiPriceClient)
            Map<Integer, ItemMappingData> mapping = wikiClient.getItemMapping();
            Map<Integer, int[]> latestPrices = wikiClient.getLatestPrices();

            if (mapping.isEmpty() || latestPrices.isEmpty()) {
                log.warn("[GEFlipper] Scan aborted: could not fetch market data");
                return;
            }

            // Stage 2: Spread filter — build candidate list
            Set<String> blacklist = parseBlacklist();
            Set<String> customList = parseCustomList();
            List<Candidate> candidates = new ArrayList<>();

            for (Map.Entry<Integer, ItemMappingData> entry : mapping.entrySet()) {
                int itemId = entry.getKey();
                ItemMappingData meta = entry.getValue();

                // FlipMode: CUSTOM_LIST restricts to user-specified items
                if (config.flipMode() == FlipMode.CUSTOM_LIST) {
                    if (!customList.contains(meta.name.toLowerCase())) continue;
                } else {
                    if (!shouldConsiderItem(meta, blacklist)) continue;
                }

                int[] prices = latestPrices.get(itemId);
                if (prices == null || prices[0] <= 0 || prices[1] <= 0) continue;

                int spread = prices[0] - prices[1];
                double margin = (double) spread / prices[1] * 100.0;
                if (margin < config.minProfitMargin()) continue;

                double score = calculateInitialScore(spread, margin, meta, config.flipMode());
                candidates.add(new Candidate(itemId, meta.name, meta, prices[0], prices[1], score));
            }

            log.info("[GEFlipper] {} candidates after spread filter", candidates.size());

            // Sort and cap for timeseries
            candidates.sort((a, b) -> Double.compare(b.initialScore, a.initialScore));
            int tsLimit = config.timeseriesCandidateLimit();

            List<Integer> tsIds = candidates.stream()
                    .limit(tsLimit)
                    .map(c -> c.itemId)
                    .collect(Collectors.toList());

            // Stage 3: Async batch timeseries (rate-limited inside WikiPriceClient)
            int timeoutSec = tsLimit * 4 + 30; // generous timeout: 3 s/item + buffer
            Map<Integer, TimeSeriesAnalysis> tsResults = wikiClient.getBatchTimeSeries(tsIds, tsLimit, timeoutSec);

            // Stage 4: Build FlipAnalysis for all candidates that passed timeseries
            for (Candidate c : candidates.subList(0, Math.min(tsLimit, candidates.size()))) {
                TimeSeriesAnalysis ts = tsResults.get(c.itemId);
                if (ts == null || ts.dataPoints.isEmpty()) continue;

                // Apply minVolume filter now that we have timeseries data
                int vol4h = ts.calculateVolumePer4Hours();
                if (vol4h < config.minVolume()) continue;

                FlipAnalysis fa = new FlipAnalysis(c.itemId, c.itemName, c.metadata, ts);
                if (fa.meetsMinProfit(config.minProfitMargin())) {
                    analyzedItems.add(fa);
                }
            }

            // Sort by flip score, with FlipMode weighting applied
            analyzedItems.sort((a, b) -> Double.compare(
                    getModeAdjustedScore(b), getModeAdjustedScore(a)));

            log.info("[GEFlipper] Scan complete: {} viable opportunities", analyzedItems.size());
            for (int i = 0; i < Math.min(5, analyzedItems.size()); i++) {
                log.info("  {}. {}", i + 1, analyzedItems.get(i));
            }

        } catch (Exception ex) {
            log.error("[GEFlipper] Scan failed: ", ex);
        }
    }

    /**
     * Initial scoring before timeseries — used to rank candidates for the TS fetch queue.
     * Weights vary by FlipMode so that the most relevant items are fetched first.
     */
    private double calculateInitialScore(int spread, double margin, ItemMappingData meta, FlipMode mode) {
        double tradeLimit = meta.getEffectiveTradeLimit();
        switch (mode) {
            case HIGH_VOLUME:
                // Prioritise high-volume, high-limit items (faster, smaller margin acceptable)
                return (tradeLimit / 50.0 * 50.0) + (margin * 2.0) + Math.min(30.0, spread / 200.0 * 30.0);

            case HIGH_MARGIN:
                // Prioritise raw margin and spread value
                return (margin * 5.0) + Math.min(50.0, spread / 50.0 * 50.0) + (tradeLimit / 200.0 * 10.0);

            case BALANCED:
            case CUSTOM_LIST:
            default:
                // Equal weighting
                return Math.min(40.0, spread / 100.0 * 40.0)
                        + Math.min(30.0, margin * 3.0)
                        + Math.min(20.0, tradeLimit / 50.0 * 20.0)
                        + 5.0;
        }
    }

    /**
     * Post-timeseries score adjustment for final sort ordering.
     * HIGH_VOLUME: boosts by estimated 4h volume.
     * HIGH_MARGIN: boosts by profit-per-unit.
     */
    private double getModeAdjustedScore(FlipAnalysis fa) {
        switch (config.flipMode()) {
            case HIGH_VOLUME:
                return fa.flipScore + (fa.estimatedVolumePer4Hours / 500.0 * 20.0);
            case HIGH_MARGIN:
                return fa.flipScore + (fa.profitPerUnit / 100.0 * 20.0);
            case CUSTOM_LIST:
            case BALANCED:
            default:
                return fa.flipScore;
        }
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

    // ════════════════════════════════════════════════════════════════════════
    // SLOT FILLING
    // ════════════════════════════════════════════════════════════════════════

    private void fillEmptySlots() {
        int slotsToUse = config.slotCount();

        GrandExchangeOffer[] offers = Microbot.getClientThread().runOnClientThreadOptional(() ->
            Microbot.getClient().getGrandExchangeOffers()
        ).orElse(new GrandExchangeOffer[0]);

        if (offers.length < 8) {
            log.warn("[GEFlipper] GE offers array incomplete ({}/8), skipping slot fill", offers.length);
            return;
        }

        // FIXED: iterate all slots per tick, not just the first empty one
        for (int i = 0; i < slotsToUse && i < offers.length; i++) {
            GrandExchangeOffer offer = offers[i];
            if (offer == null) continue;

            GrandExchangeSlots slot = GrandExchangeSlots.values()[i];
            if (activeFlips.containsKey(slot)) continue;
            if (offer.getState() != GrandExchangeOfferState.EMPTY) continue;

            FlipAnalysis best = findBestAffordableFlip();
            if (best == null) {
                log.info("[GEFlipper] No affordable flip opportunities found");
                break; // No point checking further slots if we're out of options/budget
            }

            log.info("[GEFlipper] Slot {} empty — placing buy for {}", i + 1, best.itemName);
            placeBuyOffer(slot, best.itemId, best.itemName);

            // Brief pause between placing offers on multiple slots
            Global.sleep(1000);
        }
    }

    private FlipAnalysis findBestAffordableFlip() {
        int availableGP = getFreeGP();
        if (availableGP <= 0) return null;

        // Collect item IDs already being actively flipped to avoid duplicates
        Set<Integer> activeItemIds = activeFlips.values().stream()
                .map(t -> t.itemId)
                .collect(Collectors.toSet());

        FlipAnalysis best = null;
        double bestScore = -1;

        for (FlipAnalysis fa : analyzedItems) {
            if (activeItemIds.contains(fa.itemId)) continue; // already flipping this item

            int buyPrice = (int) (fa.recommendedBuyPrice * (1.0 + config.buyPriceOffset() / 100.0));
            if (buyPrice <= 0) continue;

            int affordableQty = Math.min(fa.maxFlipQuantity, availableGP / buyPrice);
            if (affordableQty <= 0) continue;

            if (config.maxInvestmentPerSlot() > 0) {
                affordableQty = Math.min(affordableQty, config.maxInvestmentPerSlot() / buyPrice);
                if (affordableQty <= 0) continue;
            }

            double effectiveScore = getModeAdjustedScore(fa)
                    + ((long) fa.profitPerUnit * affordableQty / 1000.0 * 0.1);

            if (effectiveScore > bestScore) {
                bestScore = effectiveScore;
                best = fa;
            }
        }
        return best;
    }

    // ════════════════════════════════════════════════════════════════════════
    // OFFER PLACEMENT
    // ════════════════════════════════════════════════════════════════════════

    private void placeBuyOffer(GrandExchangeSlots slot, int itemId, String itemName) {
        Microbot.status = "Buying: " + itemName;

        FlipAnalysis analysis = analyzedItems.stream()
                .filter(a -> a.itemId == itemId)
                .findFirst().orElse(null);

        if (analysis == null) {
            log.warn("[GEFlipper] No analysis for {}, skipping buy", itemName);
            return;
        }

        int buyPrice = (int) (analysis.recommendedBuyPrice * (1.0 + config.buyPriceOffset() / 100.0));
        int quantity = analysis.maxFlipQuantity;
        int freeGP   = getFreeGP();

        quantity = Math.min(quantity, freeGP / Math.max(1, buyPrice));
        if (config.maxInvestmentPerSlot() > 0) {
            quantity = Math.min(quantity, config.maxInvestmentPerSlot() / Math.max(1, buyPrice));
        }

        if (quantity <= 0) {
            log.warn("[GEFlipper] Can't afford {} @ {} gp (free: {})", itemName, fmt(buyPrice), fmt(freeGP));
            return;
        }

        final int fBuyPrice = buyPrice;
        final int fQty      = quantity;

        boolean geOpen = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            if (!Rs2GrandExchange.isOpen()) {
                if (!Rs2GrandExchange.openExchange()) return false;
            }
            if (Rs2GrandExchange.isOfferScreenOpen()) {
                Rs2GrandExchange.backToOverview();
                Global.sleep(800);
            }
            return Rs2GrandExchange.isOpen();
        }).orElse(false);

        if (!geOpen) {
            log.error("[GEFlipper] Failed to open GE");
            return;
        }

        Global.sleep(2000);

        boolean slotsReady = Microbot.getClientThread().runOnClientThreadOptional(() ->
                Rs2GrandExchange.getAvailableSlot() != null
        ).orElse(false);

        if (!slotsReady) {
            log.error("[GEFlipper] GE slots not ready, retrying later");
            return;
        }

        log.info("[GEFlipper] Placing buy: {} x{} @ {} gp", itemName, fQty, fmt(fBuyPrice));

        boolean success = Microbot.getClientThread().runOnClientThreadOptional(() ->
            Rs2GrandExchange.processOffer(
                GrandExchangeRequest.builder()
                    .action(GrandExchangeAction.BUY)
                    .itemName(itemName)
                    .exact(true)
                    .quantity(fQty)
                    .price(fBuyPrice)
                    .slot(slot)
                    .build()
            )
        ).orElse(false);

        Global.sleep(1500);

        if (success) {
            FlipTracker tracker = new FlipTracker(itemId, itemName, slot,
                    fBuyPrice, analysis.recommendedSellPrice, fQty);
            activeFlips.put(slot, tracker);
            log.info("[GEFlipper] Buy placed: {} x{} @ {} gp (cost: {})",
                    itemName, fQty, fmt(fBuyPrice), fmt((long) fBuyPrice * fQty));
        } else {
            log.error("[GEFlipper] Failed to place buy for {}", itemName);
        }
    }

    private void placeSellOffer(GrandExchangeSlots slot, FlipTracker tracker) {
        Microbot.status = "Selling: " + tracker.itemName;

        // Use fresh analysis for sell price if available; fall back to tracker's price
        FlipAnalysis analysis = analyzedItems.stream()
                .filter(a -> a.itemId == tracker.itemId)
                .findFirst().orElse(null);

        int sellPrice = (analysis != null)
                ? (int) (analysis.recommendedSellPrice * (1.0 - config.sellPriceOffset() / 100.0))
                : tracker.sellPrice;

        // Guard: never sell below buy price
        if (sellPrice <= tracker.buyPrice) {
            sellPrice = (int) (tracker.buyPrice * 1.01);
            log.info("[GEFlipper] Sell price below buy price — adjusted to {}", fmt(sellPrice));
        }

        final int fSellPrice = sellPrice;
        final int fQty       = tracker.totalQuantity;

        boolean success = Microbot.getClientThread().runOnClientThreadOptional(() ->
            Rs2GrandExchange.processOffer(
                GrandExchangeRequest.builder()
                    .action(GrandExchangeAction.SELL)
                    .itemName(tracker.itemName)
                    .exact(true)
                    .quantity(fQty)
                    .price(fSellPrice)
                    .slot(slot)
                    .build()
            )
        ).orElse(false);

        if (success) {
            tracker.state = FlipTracker.FlipState.SELLING;
            log.info("[GEFlipper] Sell placed: {} x{} @ {} gp", tracker.itemName, fQty, fmt(fSellPrice));
        } else {
            log.error("[GEFlipper] Failed to place sell for {}", tracker.itemName);
        }
        Global.sleep(1500);
    }

    // ════════════════════════════════════════════════════════════════════════
    // FILTERS
    // ════════════════════════════════════════════════════════════════════════

    private boolean shouldConsiderItem(ItemMappingData meta, Set<String> blacklist) {
        if (blacklist.contains(meta.name.toLowerCase())) return false;
        if (meta.members && !config.includeMembersItems()) return false;
        if (config.maxTradeLimit() > 0 && meta.tradeLimitPer4Hours > config.maxTradeLimit()) return false;
        return true;
    }

    private Set<String> parseBlacklist() {
        return parseCommaSeparated(config.itemBlacklist());
    }

    private Set<String> parseCustomList() {
        return parseCommaSeparated(config.customItemList());
    }

    private static Set<String> parseCommaSeparated(String input) {
        if (input == null || input.trim().isEmpty()) return Collections.emptySet();
        return Arrays.stream(input.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    // ════════════════════════════════════════════════════════════════════════
    // STATUS & PUBLIC ACCESSORS (for overlay)
    // ════════════════════════════════════════════════════════════════════════

    private void updateStatus() {
        Microbot.status = String.format("GE Flipper [%s]: %d active | %d done | %s gp",
                config.flipMode(), activeFlips.size(), sessionFlipsCompleted, fmt(sessionTotalProfit));
    }

    public List<FlipAnalysis> getAnalyzedItems() {
        return Collections.unmodifiableList(new ArrayList<>(analyzedItems));
    }

    public Map<GrandExchangeSlots, FlipTracker> getActiveFlips() {
        return Collections.unmodifiableMap(new HashMap<>(activeFlips));
    }

    public Map<String, Object> getSessionStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("sessionProfit",        sessionTotalProfit);
        stats.put("flipsCompleted",       sessionFlipsCompleted);
        stats.put("activeFlips",          activeFlips.size());
        stats.put("elapsedMinutes",       (System.currentTimeMillis() - sessionStartTime) / 60_000L);
        stats.put("analyzedOpportunities", analyzedItems.size());
        stats.put("inventoryGP",          inventoryGP);
        stats.put("flipMode",             config.flipMode().toString());
        return stats;
    }
}
