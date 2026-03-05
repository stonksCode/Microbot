package net.runelite.client.plugins.microbot.templetrekking;

import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.*;
import java.time.Duration;
import java.time.Instant;

/**
 * The overlay draws information directly onto the game screen so you can
 * see what the bot is doing without opening any menus.
 *
 * OverlayPanel is a Microbot/RuneLite helper that gives us a pre-styled
 * dark panel to attach text to. We just add lines to it in render().
 *
 * render() is called every frame by RuneLite's overlay system.
 */
public class TempleTrekkingOverlay extends OverlayPanel {

    private final TempleTrekkingPlugin plugin;
    private final TempleTrekkingScript script;

    // Records the moment the script started so we can show a runtime timer
    private Instant startTime;

    /**
     * @Inject tells RuneLite's dependency injection system to provide
     * the plugin and script instances automatically — we don't create them.
     */
    @Inject
    public TempleTrekkingOverlay(TempleTrekkingPlugin plugin, TempleTrekkingScript script) {
        this.plugin = plugin;
        this.script = script;

        // TOP_LEFT places the overlay in the top-left corner of the screen.
        // The player can drag it anywhere they like at runtime.
        setPosition(OverlayPosition.TOP_LEFT);

        // setNaughty() allows the overlay to draw outside the game viewport
        // (needed for it to appear properly in the top-left corner)
        setNaughty();
    }

    /**
     * Called every frame. We clear the panel and rebuild the lines each time
     * so they always reflect the current state.
     *
     * @param graphics The Graphics2D context (we don't use it directly — we
     *                 add components to panelComponent instead)
     * @return The dimensions of the rendered panel (handled by super)
     */
    @Override
    public Dimension render(Graphics2D graphics) {
        try {
            // Clear previous frame's content
            panelComponent.getChildren().clear();

            // Title bar at the top of the overlay
            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("Temple Trekking")
                    .color(Color.CYAN)
                    .build());

            // Current state — shows exactly what the bot is doing right now
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("State:")
                    .right(script.getCurrentState().toString())
                    .rightColor(getStateColor(script.getCurrentState()))
                    .build());

            // Trek counter — how many full treks completed this session
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Treks Done:")
                    .right(String.valueOf(script.getTrekCount()))
                    .build());

            // Runtime — how long the script has been running this session
            if (script.getStartTime() != null) {
                Duration runtime = Duration.between(script.getStartTime(), Instant.now());
                panelComponent.getChildren().add(LineComponent.builder()
                        .left("Runtime:")
                        .right(formatDuration(runtime))
                        .build());
            }

            // Microbot.status is a global status string that Microbot updates
            // automatically for things like "walking to X" — we show it too
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Status:")
                    .right(Microbot.status)
                    .build());

        } catch (Exception e) {
            // If anything goes wrong in the overlay, we don't want it to crash
            // the whole client — just swallow the error silently
        }

        return super.render(graphics);
    }

    /**
     * Returns a color for each state so it's easy to see at a glance
     * whether the bot is doing something productive, waiting, or in trouble.
     */
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
                return Color.YELLOW;
            case BRIDGE_CHOP_TREES:
            case BRIDGE_KILL_ZOMBIES:
            case RIVER_CROSSING:
                return Color.ORANGE;
            case EVADE_COMBAT:
                return Color.CYAN;
            case TREK_COMPLETE:
                return Color.MAGENTA;
            case UNKNOWN_EVENT:
                return Color.RED;
            default:
                return Color.WHITE;
        }
    }

    /**
     * Converts a Duration into a human-readable HH:MM:SS string.
     * e.g. Duration.ofSeconds(3723) → "01:02:03"
     */
    private String formatDuration(Duration duration) {
        long hours   = duration.toHours();
        long minutes = duration.toMinutes() % 60;
        long seconds = duration.getSeconds() % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }
}
