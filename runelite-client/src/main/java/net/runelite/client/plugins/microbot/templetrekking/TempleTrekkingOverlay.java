package net.runelite.client.plugins.microbot.templetrekking;

import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.*;
import java.time.Duration;
import java.time.Instant;

public class TempleTrekkingOverlay extends OverlayPanel {

    private final TempleTrekkingPlugin plugin;
    private final TempleTrekkingScript script;

    @Inject
    public TempleTrekkingOverlay(TempleTrekkingPlugin plugin, TempleTrekkingScript script) {
        this.plugin = plugin;
        this.script = script;
        setPosition(OverlayPosition.TOP_LEFT);
        setNaughty();
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        try {
            panelComponent.getChildren().clear();

            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("Temple Trekking")
                    .color(Color.CYAN)
                    .build());

            // Current state
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("State:")
                    .right(script.getCurrentState().toString())
                    .rightColor(getStateColor(script.getCurrentState()))
                    .build());

            // Trek counter
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Treks Done:")
                    .right(String.valueOf(script.getTrekCount()))
                    .build());

            // Runtime
            if (script.getStartTime() != null) {
                Duration runtime = Duration.between(script.getStartTime(), Instant.now());
                panelComponent.getChildren().add(LineComponent.builder()
                        .left("Runtime:")
                        .right(formatDuration(runtime))
                        .build());
            }

            // Free inventory slots
            int freeSlots = Rs2Inventory.emptySlotCount();
            Color slotColor = freeSlots <= 4 ? Color.RED : freeSlots <= 6 ? Color.ORANGE : Color.WHITE;
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Free Slots:")
                    .right(String.valueOf(freeSlots))
                    .rightColor(slotColor)
                    .build());

            // Reward token count
            int tokenCount = script.getTokenCount();
            if (tokenCount > 0) {
                panelComponent.getChildren().add(LineComponent.builder()
                        .left("Tokens:")
                        .right(String.valueOf(tokenCount))
                        .rightColor(Color.YELLOW)
                        .build());
            }

            // Inventory management mode
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Inv Mode:")
                    .right(script.getInventoryManagementMode())
                    .rightColor(Color.LIGHT_GRAY)
                    .build());

            // Microbot global status
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Status:")
                    .right(Microbot.status)
                    .build());

        } catch (Exception e) {
            // Swallow silently
        }

        return super.render(graphics);
    }

    private Color getStateColor(TempleTrekkingState state) {
        if (state == null) return Color.WHITE;
        switch (state) {
            case IDLE:
                return Color.GRAY;
            case TREKKING:
            case CONTINUE_TREK:
                return Color.GREEN;
            case DETECT_EVENT:
            case START_TREK:
            case SELECT_ROUTE:
            case CHECK_INVENTORY:
                return Color.YELLOW;
            case BRIDGE_CHOP_TREES:
            case BRIDGE_KILL_ZOMBIES:
            case RIVER_CROSSING:
                return Color.ORANGE;
            case EVADE_COMBAT:
                return Color.CYAN;
            case TREK_COMPLETE:
                return Color.MAGENTA;
            case BANKING:
                return new Color(100, 200, 255); // light blue
            case REDEEM_TOKENS:
            case USE_TOMES:
                return new Color(255, 180, 50);  // gold
            case UNKNOWN_EVENT:
                return Color.RED;
            default:
                return Color.WHITE;
        }
    }

    private String formatDuration(Duration duration) {
        long hours   = duration.toHours();
        long minutes = duration.toMinutes() % 60;
        long seconds = duration.getSeconds() % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }
}
