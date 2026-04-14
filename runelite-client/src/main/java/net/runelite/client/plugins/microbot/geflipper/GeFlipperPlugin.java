package net.runelite.client.plugins.microbot.geflipper;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.util.grandexchange.Rs2GrandExchange;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.awt.*;

/**
 * Grand Exchange Flipper Plugin.
 * Automates buying and selling on the GE to profit from price spreads.
 */
@PluginDescriptor(
    name = PluginDescriptor.Default + "GE Flipper",
    description = "Automated Grand Exchange flipping for profit",
    tags = {"ge", "grand", "exchange", "flip", "trading", "profit"},
    enabledByDefault = false
)
@Slf4j
public class GeFlipperPlugin extends Plugin {

    @Inject
    private GeFlipperScript script;

    @Inject
    private GeFlipperOverlay overlay;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private GeFlipperConfig config;

    @Override
    protected void startUp() throws AWTException {
        log.info("GE Flipper plugin starting");
        overlayManager.add(overlay);
        script.run(config);
    }

    @Override
    protected void shutDown() {
        log.info("GE Flipper plugin shutting down");
        overlayManager.remove(overlay);
        script.shutdown();

        // Close GE if open
        try {
            if (Rs2GrandExchange.isOpen()) {
                Rs2GrandExchange.closeExchange();
            }
        } catch (Exception ex) {
            log.warn("Error closing GE on shutdown: ", ex);
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (!event.getGroup().equals("geflipper")) {
            return;
        }
        log.info("GE Flipper config changed: {}", event.getKey());
    }

    @Provides
    GeFlipperConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(GeFlipperConfig.class);
    }
}
