package net.runelite.client.plugins.microbot.geflipper;

import net.runelite.client.plugins.microbot.geflipper.models.FlipAnalysis;
import net.runelite.client.plugins.microbot.geflipper.models.FlipTracker;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeSlots;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.PanelComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.*;
import java.util.List;
import java.util.Map;

public class GeFlipperOverlay extends Overlay {

    private final GeFlipperScript script;
    private final PanelComponent panelComponent = new PanelComponent();

    @Inject
    public GeFlipperOverlay(GeFlipperScript script) {
        this.script = script;
        setPosition(OverlayPosition.TOP_LEFT);
        setPreferredSize(new Dimension(360, 0));
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        panelComponent.getChildren().clear();

        Map<String, Object> stats = script.getSessionStats();

        // Null-safe stat extraction
        long profit          = toLong(stats.getOrDefault("sessionProfit", 0L));
        int  flipsCompleted  = toInt(stats.getOrDefault("flipsCompleted", 0));
        int  activeCount     = toInt(stats.getOrDefault("activeFlips", 0));
        long elapsedMinutes  = toLong(stats.getOrDefault("elapsedMinutes", 0L));
        int  inventoryGP     = toInt(stats.getOrDefault("inventoryGP", 0));
        int  opportunities   = toInt(stats.getOrDefault("analyzedOpportunities", 0));
        String mode          = String.valueOf(stats.getOrDefault("flipMode", "?"));

        // Title
        panelComponent.getChildren().add(
            TitleComponent.builder()
                .text("GE Flipper [" + mode + "]")
                .color(Color.YELLOW)
                .build()
        );

        // Session stats
        Color profitColor = profit >= 0 ? Color.GREEN : Color.RED;
        panelComponent.getChildren().add(
            LineComponent.builder()
                .left("GP Available:")
                .leftColor(Color.YELLOW)
                .right(String.format("%,d", inventoryGP))
                .rightColor(Color.YELLOW)
                .build()
        );
        panelComponent.getChildren().add(
            LineComponent.builder()
                .left("Session Profit:")
                .leftColor(Color.WHITE)
                .right(String.format("%s%,d gp", profit >= 0 ? "+" : "", profit))
                .rightColor(profitColor)
                .build()
        );
        panelComponent.getChildren().add(
            LineComponent.builder()
                .left("Flips Done:")
                .leftColor(Color.WHITE)
                .right(String.valueOf(flipsCompleted))
                .rightColor(Color.WHITE)
                .build()
        );
        panelComponent.getChildren().add(
            LineComponent.builder()
                .left("Active:")
                .leftColor(Color.WHITE)
                .right(activeCount + " | " + opportunities + " opps")
                .rightColor(Color.WHITE)
                .build()
        );
        panelComponent.getChildren().add(
            LineComponent.builder()
                .left("Elapsed:")
                .leftColor(Color.WHITE)
                .right(elapsedMinutes + " min")
                .rightColor(Color.GRAY)
                .build()
        );

        // Active flips
        Map<GrandExchangeSlots, FlipTracker> activeFlipMap = script.getActiveFlips();
        if (!activeFlipMap.isEmpty()) {
            panelComponent.getChildren().add(
                TitleComponent.builder().text("Active Flips").color(Color.CYAN).build()
            );
            for (Map.Entry<GrandExchangeSlots, FlipTracker> entry : activeFlipMap.entrySet()) {
                FlipTracker t = entry.getValue();
                String slotLabel = entry.getKey().name().replace("SLOT_", "");
                String progress = t.isSelling()
                    ? String.format("Sell %.0f%%", t.getSellFillPercent())
                    : String.format("Buy  %.0f%%", t.getBuyFillPercent());
                int pot = t.getPotentialProfit();
                String profitStr = pot > 0 ? String.format("+%,d", pot) : "-";
                panelComponent.getChildren().add(
                    LineComponent.builder()
                        .left(String.format("[%s] %s", slotLabel, t.itemName))
                        .leftColor(t.isSelling() ? Color.ORANGE : Color.WHITE)
                        .right(progress + " | " + profitStr)
                        .rightColor(Color.GREEN)
                        .build()
                );
            }
        }

        // Top opportunities
        List<FlipAnalysis> items = script.getAnalyzedItems();
        if (!items.isEmpty()) {
            panelComponent.getChildren().add(
                TitleComponent.builder().text("Top Opportunities").color(Color.GREEN).build()
            );
            int show = Math.min(5, items.size());
            for (int i = 0; i < show; i++) {
                FlipAnalysis fa = items.get(i);
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

    private static long toLong(Object v) {
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }

    private static int toInt(Object v) {
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }
}
