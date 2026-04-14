package net.runelite.client.plugins.microbot.allotmentrun;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.awt.*;

@PluginDescriptor(
        name = PluginDescriptor.Mocrosoft + "Allotment Runner",
        description = "Allotment Runner",
        tags = {"allotment", "farming", "vegetables", "skilling"},
        authors = {"Mocrosoft"},
        version = AllotmentrunPlugin.version,
        minClientVersion = "2.1.0",
        enabledByDefault = false,
        isExternal = true
)
@Slf4j
public class AllotmentrunPlugin extends Plugin {
    public static final String version = "1.0.0";

    @Inject
    private AllotmentrunConfig config;

    @Provides
    AllotmentrunConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(AllotmentrunConfig.class);
    }

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private AllotmentrunOverlay allotmentrunOverlay;

    @Inject
    AllotmentrunScript allotmentrunScript;

    static String status;

    @Override
    protected void startUp() throws AWTException {
        if (overlayManager != null) {
            overlayManager.add(allotmentrunOverlay);
        }
        allotmentrunScript.run();
    }

    protected void shutDown() {
        allotmentrunScript.shutdown();
        overlayManager.remove(allotmentrunOverlay);
        status = null;
    }
}
