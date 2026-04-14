package net.runelite.client.plugins.microbot.geflipper.models;

import net.runelite.client.plugins.microbot.util.grandexchange.models.ItemMappingData;
import net.runelite.client.plugins.microbot.util.grandexchange.models.TimeSeriesAnalysis;

/**
 * Holds analysis data for a potential flip opportunity.
 * Combines item metadata, price analysis, and calculated flip metrics.
 */
public class FlipAnalysis {
    public final int itemId;
    public final String itemName;
    public final ItemMappingData itemMetadata;
    public final TimeSeriesAnalysis priceAnalysis;

    // Calculated flip metrics
    public final int recommendedBuyPrice;
    public final int recommendedSellPrice;
    public final int profitPerUnit;
    public final double profitMarginPercent;
    public final int estimatedVolumePer4Hours;
    public final int maxFlipQuantity; // Based on trade limit and volume
    public final long estimatedTotalProfit; // long to prevent overflow
    public final double flipScore; // Overall attractiveness score

    public FlipAnalysis(
        int itemId,
        String itemName,
        ItemMappingData itemMetadata,
        TimeSeriesAnalysis priceAnalysis
    ) {
        this.itemId = itemId;
        this.itemName = itemName;
        this.itemMetadata = itemMetadata;
        this.priceAnalysis = priceAnalysis;

        // Calculate recommended prices
        this.recommendedBuyPrice = priceAnalysis.getRecommendedBuyPrice();
        this.recommendedSellPrice = priceAnalysis.getRecommendedSellPrice();

        // Calculate profit metrics
        this.profitPerUnit = Math.max(0, this.recommendedSellPrice - this.recommendedBuyPrice);
        this.profitMarginPercent = this.recommendedBuyPrice > 0
            ? ((double) this.profitPerUnit / this.recommendedBuyPrice) * 100.0
            : 0.0;

        // Volume and quantity estimates
        this.estimatedVolumePer4Hours = priceAnalysis.calculateVolumePer4Hours();
        this.maxFlipQuantity = calculateMaxFlipQuantity();

        // Total profit estimate (use long to prevent overflow)
        this.estimatedTotalProfit = (long) this.profitPerUnit * this.maxFlipQuantity;

        // Calculate overall flip score
        this.flipScore = calculateFlipScore();
    }

    /**
     * Calculates the maximum quantity we can flip per 4-hour cycle.
     * Limited by GE trade limit and available market volume.
     */
    private int calculateMaxFlipQuantity() {
        if (itemMetadata == null) {
            return Math.min(estimatedVolumePer4Hours / 2, 100); // Conservative estimate
        }

        int tradeLimit = itemMetadata.getEffectiveTradeLimit();
        // We can buy up to the trade limit, but limited by available volume
        // Use 25% of total volume as a conservative flip capacity
        int volumeCapacity = Math.max(10, estimatedVolumePer4Hours / 4);

        return Math.min(tradeLimit, volumeCapacity);
    }

    /**
     * Calculates an overall attractiveness score for this flip opportunity.
     * Higher score = better flip opportunity.
     *
     * Considers: profit margin, volume, stability, trade limit flexibility
     */
    private double calculateFlipScore() {
        if (profitPerUnit <= 0 || estimatedVolumePer4Hours <= 0) {
            return 0.0;
        }

        // Profit component (0-40 points): scaled by profit per unit
        double profitScore = Math.min(40.0, (profitPerUnit / 100.0) * 40.0);

        // Volume component (0-30 points): scaled by volume per 4h
        double volumeScore = Math.min(30.0, (estimatedVolumePer4Hours / 1000.0) * 30.0);

        // Margin component (0-20 points): scaled by margin percentage
        double marginScore = Math.min(20.0, profitMarginPercent * 4.0);

        // Stability component (0-10 points): based on volume stability
        double stabilityScore = priceAnalysis.volumeStability * 10.0;

        return profitScore + volumeScore + marginScore + stabilityScore;
    }

    /**
     * Returns true if this flip meets minimum profitability requirements.
     */
    public boolean meetsMinProfit(int minMarginPercent) {
        return profitMarginPercent >= minMarginPercent && profitPerUnit > 0;
    }

    @Override
    public String toString() {
        return String.format("%s: buy=%d, sell=%d, profit/unit=%d, margin=%.1f%%, score=%.1f",
            itemName, recommendedBuyPrice, recommendedSellPrice,
            profitPerUnit, profitMarginPercent, flipScore);
    }
}
