package net.runelite.client.plugins.microbot.butterflycatcher;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

import javax.inject.Inject;

@PluginDescriptor(
        name = "Butterfly Catcher",
        description = "Automates bare-handed butterfly catching for Hunter XP. "
                + "Select your butterfly in the config panel, stand near a spawn, and start.",
        tags = {"hunter", "butterfly", "microbot", "script"},
        enabledByDefault = false
)
@Slf4j
public class ButterflyCatcherPlugin extends Plugin {

    @Inject
    private ButterflyCatcherConfig config;

    private ButterflyCatcherScript script;

    @Provides
    ButterflyCatcherConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(ButterflyCatcherConfig.class);
    }

    @Override
    protected void startUp() {
        script = new ButterflyCatcherScript();
        script.run(config);
        log.info("Butterfly Catcher started — target: {}", config.butterflyType().getDisplayName());
    }

    @Override
    protected void shutDown() {
        if (script != null) {
            script.shutdown();
        }
        log.info("Butterfly Catcher stopped.");
    }
}
