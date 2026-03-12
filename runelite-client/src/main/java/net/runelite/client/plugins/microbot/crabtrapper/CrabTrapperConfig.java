package net.runelite.client.plugins.microbot.crabtrapper;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("CrabTrapper")
public interface CrabTrapperConfig extends Config {

    @ConfigItem(
            keyName = "crabType",
            name = "Crab Type",
            description = "Which crab to trap. More types will be added as Blue and Rainbow crabs are supported.",
            position = 0
    )
    default CrabTrapperScript.CrabType crabType() {
        return CrabTrapperScript.CrabType.RED_CRAB;
    }

    @ConfigItem(
            keyName = "inventoryMode",
            name = "Inventory Mode",
            description = "What to do when your inventory fills with crabs. PASTE: crush with pestle+mortar. CUT: cut with knife and bank meat. BANK: bank crabs directly.",
            position = 1
    )
    default CrabTrapperScript.InventoryMode inventoryMode() {
        return CrabTrapperScript.InventoryMode.PASTE;
    }

    @ConfigItem(
            keyName = "timingMode",
            name = "Timing Mode",
            description = "RELAXED: waits the 3-tick auto-rebait window before collecting. IMMEDIATE: collects as soon as a trap is full. RANDOM: alternates between the two to appear more human.",
            position = 2
    )
    default CrabTrapperScript.TimingMode timingMode() {
        return CrabTrapperScript.TimingMode.RANDOM;
    }
}
