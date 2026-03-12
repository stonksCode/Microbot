package net.runelite.client.plugins.microbot.crabtrapper;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

import javax.inject.Inject;

@PluginDescriptor(
        name = "Crab Trapper",
        description = "Automates crab trapping for Hunter XP via the Sailing skill activity. Supports Red, Blue, and Rainbow crabs.",
        tags = {"hunter", "crab", "sailing", "microbot", "script"},
        enabledByDefault = false
)
@Slf4j
public class CrabTrapperPlugin extends Plugin {

    @Inject
    private CrabTrapperConfig config;

    private CrabTrapperScript script;

    @Provides
    CrabTrapperConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(CrabTrapperConfig.class);
    }

    @Override
    protected void startUp() {
        script = new CrabTrapperScript();
        script.run(config);
    }

    @Override
    protected void shutDown() {
        if (script != null) {
            script.shutdown();
        }
    }
}
