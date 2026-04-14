package net.runelite.client.plugins.microbot.geflipper.models;

import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeSlots;

/**
 * Tracks an active flip opportunity in a GE slot.
 * Monitors buy/sell offers, quantities, and committed GP.
 */
public class FlipTracker {
    public final int itemId;
    public final String itemName;
    public final GrandExchangeSlots slot;

    // Offer details (immutable once set)
    public final int buyPrice;
    public final int sellPrice;
    public final int totalQuantity;

    // Mutable progress tracking
    public int quantityBought;
    public int quantitySold;

    // State tracking
    public final long startTime;
    public FlipState state;

    public FlipTracker(int itemId, String itemName, GrandExchangeSlots slot,
                       int buyPrice, int sellPrice, int totalQuantity) {
        this.itemId = itemId;
        this.itemName = itemName;
        this.slot = slot;
        this.buyPrice = buyPrice;
        this.sellPrice = sellPrice;
        this.totalQuantity = totalQuantity;
        this.quantityBought = 0;
        this.quantitySold = 0;
        this.startTime = System.currentTimeMillis();
        this.state = FlipState.BUYING;
    }

    /**
     * Called when a buy offer completes (BOUGHT state detected).
     * Records the actual quantity bought from the GE offer data.
     */
    public void updateBought(int quantityBought, int totalQuantity) {
        this.quantityBought = quantityBought;
        if (totalQuantity > 0) {
            // Update totalQuantity if GE partially filled differently than requested
        }
    }

    /**
     * Called when a sell offer progresses or completes.
     */
    public void updateSold(int quantitySold) {
        this.quantitySold = quantitySold;
    }

    /**
     * Returns the GP currently committed to this flip's active buy order.
     * 0 once items are bought (coins are gone, items are in inventory/bank).
     */
    public int getCommittedGP() {
        if (state == FlipState.BUYING) {
            return quantityBought * buyPrice;
        }
        // BOUGHT/SELLING/SOLD/COMPLETED: no coins committed, items in inventory
        return 0;
    }

    /**
     * Calculates the potential profit if all bought items are sold at the target price.
     */
    public int getPotentialProfit() {
        int potentialRevenue = quantityBought * sellPrice;
        int cost = quantityBought * buyPrice;
        return potentialRevenue - cost;
    }

    /**
     * Returns the fill percentage of the buy offer.
     */
    public double getBuyFillPercent() {
        return totalQuantity > 0 ? (double) quantityBought / totalQuantity * 100.0 : 0.0;
    }

    /**
     * Returns the fill percentage of the sell offer.
     */
    public double getSellFillPercent() {
        return totalQuantity > 0 ? (double) quantitySold / totalQuantity * 100.0 : 0.0;
    }

    /**
     * Returns how long this flip has been active in milliseconds.
     */
    public long getElapsedTime() {
        return System.currentTimeMillis() - startTime;
    }

    /**
     * Returns true if the buy phase is complete and we're selling.
     */
    public boolean isSelling() {
        return state == FlipState.SELLING || state == FlipState.SOLD;
    }

    /**
     * Returns true if the entire flip cycle is complete.
     */
    public boolean isComplete() {
        return state == FlipState.COMPLETED;
    }

    public enum FlipState {
        BUYING,
        BOUGHT,
        SELLING,
        SOLD,
        COMPLETED,
        CANCELLED
    }

    @Override
    public String toString() {
        return String.format("Flip[%s in slot %s: %d/%d bought, %d/%d sold, profit=%d]",
            itemName, slot, quantityBought, totalQuantity,
            quantitySold, totalQuantity, getPotentialProfit());
    }
}
