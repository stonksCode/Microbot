package net.runelite.client.plugins.microbot.geflipper.models;

import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeSlots;

/**
 * Tracks an active flip in a single GE slot.
 */
public class FlipTracker {
    public final int itemId;
    public final String itemName;
    public final GrandExchangeSlots slot;

    // Offer prices / quantity (immutable once placed)
    public final int buyPrice;
    public final int sellPrice;
    public final int totalQuantity;

    // Progress counters (mutable)
    public int quantityBought;
    public int quantitySold;

    // State
    public final long startTime;
    public FlipState state;

    public FlipTracker(int itemId, String itemName, GrandExchangeSlots slot,
                       int buyPrice, int sellPrice, int totalQuantity) {
        this.itemId        = itemId;
        this.itemName      = itemName;
        this.slot          = slot;
        this.buyPrice      = buyPrice;
        this.sellPrice     = sellPrice;
        this.totalQuantity = totalQuantity;
        this.quantityBought = 0;
        this.quantitySold   = 0;
        this.startTime     = System.currentTimeMillis();
        this.state         = FlipState.BUYING;
    }

    public void updateBought(int quantityBought, int totalQuantity) {
        this.quantityBought = quantityBought;
    }

    public void updateSold(int quantitySold) {
        this.quantitySold = quantitySold;
    }

    /**
     * Returns the GP that is currently locked inside an active buy order.
     *
     * FIXED: while BUYING we reserve the FULL order cost (totalQuantity * buyPrice),
     * not just the partial quantity received so far.  This prevents the budget tracker
     * from double-counting "free" GP and placing buy orders we can't actually afford.
     *
     * Once the order transitions to BOUGHT/SELLING/COMPLETED the coins are spent;
     * GP returns when the sell completes and we collect.
     */
    public int getCommittedGP() {
        if (state == FlipState.BUYING) {
            // Reserve the full order value — coins are still committed in the GE offer
            return (long) totalQuantity * buyPrice > Integer.MAX_VALUE
                    ? Integer.MAX_VALUE
                    : totalQuantity * buyPrice;
        }
        // BOUGHT / SELLING / SOLD / COMPLETED: coins are gone, items are in inventory/bank
        return 0;
    }

    /** Potential profit assuming all items sell at the target sell price. */
    public int getPotentialProfit() {
        return (int) Math.min(Integer.MAX_VALUE,
                (long) quantityBought * sellPrice - (long) quantityBought * buyPrice);
    }

    public double getBuyFillPercent() {
        return totalQuantity > 0 ? (double) quantityBought / totalQuantity * 100.0 : 0.0;
    }

    public double getSellFillPercent() {
        return totalQuantity > 0 ? (double) quantitySold / totalQuantity * 100.0 : 0.0;
    }

    public long getElapsedTime() {
        return System.currentTimeMillis() - startTime;
    }

    public boolean isSelling() {
        return state == FlipState.SELLING || state == FlipState.SOLD;
    }

    public boolean isComplete() {
        return state == FlipState.COMPLETED;
    }

    public enum FlipState {
        BUYING, BOUGHT, SELLING, SOLD, COMPLETED, CANCELLED
    }

    @Override
    public String toString() {
        return String.format("Flip[%s | slot=%s | %d/%d bought | %d/%d sold | profit=%d]",
                itemName, slot, quantityBought, totalQuantity,
                quantitySold, totalQuantity, getPotentialProfit());
    }
}
