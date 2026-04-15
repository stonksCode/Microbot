package net.runelite.client.plugins.microbot.geflipper;

import net.runelite.client.config.*;

@ConfigGroup("geflipper")
public interface GeFlipperConfig extends Config {

    @ConfigSection(name = "Strategy", description = "Flipping strategy settings", position = 0)
    String strategySection = "strategySection";

    @ConfigSection(name = "Filters", description = "Item filtering settings", position = 1)
    String filterSection = "filterSection";

    @ConfigSection(name = "Advanced", description = "Advanced flipping settings", position = 2)
    String advancedSection = "advancedSection";

    // ── Strategy Section ──────────────────────────────────────────────────

    @ConfigItem(
        keyName = "flipMode",
        name = "Flip Mode",
        description = "How to select and score items to flip",
        position = 0,
        section = strategySection
    )
    default FlipMode flipMode() {
        return FlipMode.BALANCED;
    }

    @ConfigItem(
        keyName = "customItemList",
        name = "Custom Item List",
        description = "Comma-separated item names to flip (only used when Flip Mode = Custom List)",
        position = 1,
        section = strategySection
    )
    default String customItemList() {
        return "";
    }

    @ConfigItem(
        keyName = "minProfitMargin",
        name = "Min Profit Margin (%)",
        description = "Minimum profit margin percentage to consider an item worth flipping",
        position = 2,
        section = strategySection
    )
    @Range(min = 1, max = 50)
    default int minProfitMargin() {
        return 3;
    }

    @ConfigItem(
        keyName = "maxInvestmentPerSlot",
        name = "Max Investment Per Slot",
        description = "Maximum coins to invest in a single GE slot (0 = unlimited)",
        position = 3,
        section = strategySection
    )
    @Range(min = 0)
    default int maxInvestmentPerSlot() {
        return 0;
    }

    @ConfigItem(
        keyName = "slotCount",
        name = "Active Slots",
        description = "Number of GE slots to use for flipping (1-8)",
        position = 4,
        section = strategySection
    )
    @Range(min = 1, max = 8)
    default int slotCount() {
        return 8;
    }

    // ── Filter Section ────────────────────────────────────────────────────

    @ConfigItem(
        keyName = "minVolume",
        name = "Min Volume (4h)",
        description = "Minimum estimated trade volume per 4 hours — low-volume items are skipped",
        position = 10,
        section = filterSection
    )
    @Range(min = 0)
    default int minVolume() {
        return 100;
    }

    @ConfigItem(
        keyName = "maxTradeLimit",
        name = "Max Trade Limit",
        description = "Skip items whose GE buy limit exceeds this (0 = no cap — include all items)",
        position = 11,
        section = filterSection
    )
    @Range(min = 0)
    default int maxTradeLimit() {
        return 0;
    }

    @ConfigItem(
        keyName = "includeMembersItems",
        name = "Members Items",
        description = "Whether to include members-only items",
        position = 12,
        section = filterSection
    )
    default boolean includeMembersItems() {
        return true;
    }

    @ConfigItem(
        keyName = "itemBlacklist",
        name = "Item Blacklist",
        description = "Comma-separated item names to never flip (case-insensitive)",
        position = 13,
        section = filterSection
    )
    default String itemBlacklist() {
        return "";
    }

    // ── Advanced Section ──────────────────────────────────────────────────

    @ConfigItem(
        keyName = "buyPriceOffset",
        name = "Buy Price Offset (%)",
        description = "How much above the low price to place buy offers (higher = faster fills)",
        position = 20,
        section = advancedSection
    )
    @Range(min = 0, max = 10)
    default int buyPriceOffset() {
        return 1;
    }

    @ConfigItem(
        keyName = "sellPriceOffset",
        name = "Sell Price Offset (%)",
        description = "How much below the high price to place sell offers (higher = faster sells)",
        position = 21,
        section = advancedSection
    )
    @Range(min = 0, max = 10)
    default int sellPriceOffset() {
        return 1;
    }

    @ConfigItem(
        keyName = "refreshIntervalMinutes",
        name = "Scan Interval (min)",
        description = "How often to re-scan the market for flip opportunities",
        position = 22,
        section = advancedSection
    )
    @Range(min = 1, max = 60)
    default int refreshIntervalMinutes() {
        return 5;
    }

    @ConfigItem(
        keyName = "cancelUndercutOffers",
        name = "Cancel Unprofitable Offers",
        description = "Cancel buy/sell offers if the market moves and the flip is no longer profitable",
        position = 23,
        section = advancedSection
    )
    default boolean cancelUndercutOffers() {
        return true;
    }

    @ConfigItem(
        keyName = "offerTimeoutMinutes",
        name = "Offer Timeout (min)",
        description = "Cancel buy offers that haven't filled after this many minutes (0 = never)",
        position = 24,
        section = advancedSection
    )
    @Range(min = 0, max = 120)
    default int offerTimeoutMinutes() {
        return 10;
    }

    @ConfigItem(
        keyName = "priceRecheckSeconds",
        name = "Price Re-check (sec)",
        description = "How often to re-check market prices on active offers",
        position = 25,
        section = advancedSection
    )
    @Range(min = 15, max = 300)
    default int priceRecheckSeconds() {
        return 60;
    }

    @ConfigItem(
        keyName = "collectToBank",
        name = "Collect to Bank",
        description = "Collect finished offers directly to bank instead of inventory",
        position = 26,
        section = advancedSection
    )
    default boolean collectToBank() {
        return true;
    }

    @ConfigItem(
        keyName = "timeseriesCandidateLimit",
        name = "Timeseries Scan Limit",
        description = "Max number of candidate items to run full timeseries analysis on (higher = slower scan but more data)",
        position = 27,
        section = advancedSection
    )
    @Range(min = 5, max = 100)
    default int timeseriesCandidateLimit() {
        return 30;
    }
}
