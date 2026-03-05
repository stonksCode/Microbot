package net.runelite.client.plugins.microbot.templetrekking;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.awt.*;

/**
 * TempleTrekkingPlugin is the entry point that RuneLite sees.
 *
 * RuneLite scans the classpath for classes annotated with @PluginDescriptor
 * and registers them as plugins. When the user enables this plugin in the
 * RuneLite panel, startUp() is called. When they disable it, shutDown() is called.
 *
 * This class is intentionally thin — it only handles:
 *   1. Plugin metadata (@PluginDescriptor)
 *   2. Dependency injection (@Inject)
 *   3. Lifecycle (startUp / shutDown)
 *
 * All actual logic lives in TempleTrekkingScript.
 * All user settings live in TempleTrekkingConfig.
 * All on-screen display lives in TempleTrekkingOverlay.
 */
@PluginDescriptor(
        // PluginDescriptor.Default adds the "[Microbot]" prefix automatically
        // so it appears as "[Microbot] Temple Trekking" in the plugin list
        name = PluginDescriptor.Default + "Temple Trekking",
        description = "Automates Temple Trekking / Burgh de Rott Ramble for reward tokens",
        tags = {"temple trekking", "burgh de rott", "morytania", "microbot", "minigame"},
        enabledByDefault = false  // Don't run unless the user explicitly turns it on
)
@Slf4j
public class TempleTrekkingPlugin extends Plugin {

    /**
     * @Inject tells RuneLite's Guice dependency injection system:
     * "Give me the singleton instance of this class."
     *
     * We never call `new TempleTrekkingScript()` ourselves — Guice creates
     * and manages these objects. This means:
     *   - They're automatically available everywhere they're @Inject-ed
     *   - RuneLite handles their lifecycle alongside the plugin
     */

    @Inject
    private TempleTrekkingConfig config;

    @Inject
    private TempleTrekkingScript script;

    @Inject
    private TempleTrekkingOverlay overlay;

    /**
     * OverlayManager is RuneLite's built-in system for managing overlays.
     * We inject it so we can register/unregister our overlay.
     */
    @Inject
    private OverlayManager overlayManager;

    /**
     * Called when the user enables the plugin.
     *
     * Order matters:
     * 1. Register the overlay FIRST so it appears on screen immediately
     * 2. Then start the script
     *
     * @throws AWTException Required by Plugin signature (from RuneLite)
     */
    @Override
    protected void startUp() throws AWTException {
        log.info("Temple Trekking plugin starting up...");

        // Register the overlay so RuneLite renders it each frame
        overlayManager.add(overlay);

        // Start the bot script, passing in the user's config settings
        script.run(config);

        log.info("Temple Trekking plugin started.");
    }

    /**
     * Called when the user disables the plugin.
     *
     * Order matters (reverse of startUp):
     * 1. Stop the script FIRST (stops the background thread)
     * 2. Then remove the overlay
     */
    @Override
    protected void shutDown() {
        log.info("Temple Trekking plugin shutting down...");

        // Stop the background thread and clean up script state
        script.shutdown();

        // Remove the overlay from the screen
        overlayManager.remove(overlay);

        log.info("Temple Trekking plugin shut down.");
    }

    // Note: RuneLite automatically wires up Config interfaces that extend Config
    // and are annotated with @ConfigGroup. No @Provides method needed here.
}
