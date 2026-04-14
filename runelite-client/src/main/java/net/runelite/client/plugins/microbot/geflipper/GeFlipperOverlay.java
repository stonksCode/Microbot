package net.runelite.client.plugins.microbot.geflipper;

import net.runelite.client.plugins.microbot.geflipper.models.FlipAnalysis;
import net.runelite.client.plugins.microbot.geflipper.models.FlipTracker;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.PanelComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;
import net.runelite.client.ui.overlay.components.LineComponent;

import javax.inject.Inject;
import java.awt.*;
import java.util.List;
import java.util.Map;

/**
 * Overlay for the GE Flipper plugin.
 * Displays active flips, profit stats, and top opportunities.
 */
public class GeFlipperOverlay extends Overlay {

    private final GeFlipperScript script;
    private final PanelComponent panelComponent = new PanelComponent();

    @Inject
    public GeFlipperOverlay(GeFlipperScript script) {
        this.script = script;
        setPosition(OverlayPosition.TOP_LEFT);
        setPreferredSize(new Dimension(350, 0));
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        panelComponent.getChildren().clear();

        // Title
        panelComponent.getChildren().add(
            TitleComponent.builder()
                .text("GE Flipper")
                .color(Color.YELLOW)
                .build()
        );

        // Session Stats
        Map<String, Object> stats = script.getSessionStats();
        int profit = (int) stats.getOrDefault("sessionProfit", 0);
        int flipsCompleted = (int) stats.getOrDefault("flipsCompleted", 0);
        int activeFlips = (int) stats.getOrDefault("activeFlips", 0);
        long elapsedMinutes = (long) stats.getOrDefault("elapsedMinutes", 0);
        int inventoryGP = (int) stats.getOrDefault("inventoryGP", 0);

        Color profitColor = profit >= 0 ? Color.GREEN : Color.RED;
        String profitStr = String.format("%s%,d gp", profit >= 0 ? "+" : "", profit);

        panelComponent.getChildren().add(
            LineComponent.builder()
                .left("Available GP:")
                .leftColor(Color.YELLOW)
                .right(String.format("%,d", inventoryGP))
                .rightColor(Color.YELLOW)
                .build()
        );

        panelComponent.getChildren().add(
            LineComponent.builder()
                .left("Session Profit:")
                .leftColor(Color.WHITE)
                .right(profitStr)
                .rightColor(profitColor)
                .build()
        );

        panelComponent.getChildren().add(
            LineComponent.builder()
                .left("Flips Completed:")
                .leftColor(Color.WHITE)
                .right(String.valueOf(flipsCompleted))
                .rightColor(Color.WHITE)
                .build()
        );

        panelComponent.getChildren().add(
            LineComponent.builder()
                .left("Active Flips:")
                .leftColor(Color.WHITE)
                .right(String.valueOf(activeFlips))
                .rightColor(Color.WHITE)
                .build()
        );

        panelComponent.getChildren().add(
            LineComponent.builder()
                .left("Elapsed Time:")
                .leftColor(Color.WHITE)
                .right(String.format("%d min", elapsedMinutes))
                .rightColor(Color.GRAY)
                .build()
        );

        // Active Flips Table
        Map<net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeSlots, FlipTracker> activeFlipMap = script.getActiveFlips();
        if (!activeFlipMap.isEmpty()) {
            panelComponent.getChildren().add(
                TitleComponent.builder()
                    .text("Active Flips")
                    .color(Color.CYAN)
                    .build()
            );

            for (Map.Entry<net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeSlots, FlipTracker> entry : activeFlipMap.entrySet()) {
                FlipTracker tracker = entry.getValue();
                String slotName = entry.getKey().name().replace("SLOT_", "");
                String progress;

                if (tracker.isSelling()) {
                    progress = String.format("Selling: %.0f%%", tracker.getSellFillPercent());
                } else {
                    progress = String.format("Buying: %.0f%%", tracker.getBuyFillPercent());
                }

                int potentialProfit = tracker.getPotentialProfit();
                String profitDisplay = potentialProfit > 0
                    ? String.format("+%,d", potentialProfit)
                    : "-";

                panelComponent.getChildren().add(
                    LineComponent.builder()
                        .left(String.format("[%s] %s", slotName, tracker.itemName))
                        .leftColor(Color.WHITE)
                        .right(String.format("%s | %s", progress, profitDisplay))
                        .rightColor(Color.GREEN)
                        .build()
                );
            }
        }

        // Top Opportunities
        List<FlipAnalysis> analyzedItems = script.getAnalyzedItems();
        if (!analyzedItems.isEmpty()) {
            panelComponent.getChildren().add(
                TitleComponent.builder()
                    .text("Top Opportunities")
                    .color(Color.GREEN)
                    .build()
            );

            int showCount = Math.min(5, analyzedItems.size());
            for (int i = 0; i < showCount; i++) {
                FlipAnalysis fa = analyzedItems.get(i);
                panelComponent.getChildren().add(
                    LineComponent.builder()
                        .left(String.format("%d. %s", i + 1, fa.itemName))
                        .leftColor(Color.WHITE)
                        .right(String.format("+%,d gp (%.1f%%)", fa.profitPerUnit, fa.profitMarginPercent))
                        .rightColor(Color.GREEN)
                        .build()
                );
            }
        }

        return panelComponent.render(graphics);
    }
}
