package net.runelite.client.plugins.microbot.butterflycatcher;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("ButterflyCatcher")
public interface ButterflyCatcherConfig extends Config {

    @ConfigItem(
            keyName = "butterflyType",
            name = "Butterfly",
            description = "Which butterfly to hunt. Make sure your Hunter level meets the requirement.",
            position = 0
    )
    default ButterflyType butterflyType() {
        return ButterflyType.BLACK_WARLOCK;
    }
}
