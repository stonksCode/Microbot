package net.runelite.client.plugins.microbot.templetrekking;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup("templetrekking")
public interface TempleTrekkingConfig extends Config {

    // -------------------------------------------------------------------------
    // Sections
    // -------------------------------------------------------------------------

    @ConfigSection(
            name = "Trek Settings",
            description = "Settings that control how the trek runs",
            position = 0
    )
    String trekSection = "trek";

    @ConfigSection(
            name = "Event Handling",
            description = "Controls how specific events are handled",
            position = 1
    )
    String eventSection = "events";

    @ConfigSection(
            name = "Inventory Management",
            description = "What to do with reward tokens when inventory fills up",
            position = 2
    )
    String inventorySection = "inventory";

    // -------------------------------------------------------------------------
    // Trek Settings
    // -------------------------------------------------------------------------

    @ConfigItem(
            keyName = "trekDirection",
            name = "Trek Direction",
            description = "Which direction to run the minigame",
            position = 0,
            section = trekSection
    )
    default TrekDirection trekDirection() {
        return TrekDirection.BURGH_DE_ROTT_RAMBLE;
    }

    // -------------------------------------------------------------------------
    // Event Handling
    // -------------------------------------------------------------------------

    @ConfigItem(
            keyName = "doBridgeTrees",
            name = "Complete Bridge (Trees)",
            description = "Chop trees and repair the bridge when this event appears",
            position = 0,
            section = eventSection
    )
    default boolean doBridgeTrees() {
        return true;
    }

    @ConfigItem(
            keyName = "doBridgeLumberjacks",
            name = "Complete Bridge (Lumberjacks)",
            description = "Kill undead lumberjacks, loot planks, and repair the bridge",
            position = 1,
            section = eventSection
    )
    default boolean doBridgeLumberjacks() {
        return true;
    }

    @ConfigItem(
            keyName = "doRiverCrossing",
            name = "Complete River Crossing",
            description = "Cut vines and swing across the river when this event appears",
            position = 2,
            section = eventSection
    )
    default boolean doRiverCrossing() {
        return true;
    }

    // -------------------------------------------------------------------------
    // Inventory Management
    // -------------------------------------------------------------------------

    @ConfigItem(
            keyName = "inventoryManagement",
            name = "Reward Token Handling",
            description = "What to do when free inventory slots run low. "
                    + "NONE: ignore (manual cleanup). "
                    + "BANK: walk to Burgh de Rott bank and deposit all reward tokens (only if already in Burgh de Rott). "
                    + "REDEEM_TOMES: redeem tokens for XP tomes, then read all tomes.",
            position = 0,
            section = inventorySection
    )
    default InventoryManagement inventoryManagement() {
        return InventoryManagement.NONE;
    }

    @ConfigItem(
            keyName = "minFreeSlots",
            name = "Minimum Free Slots",
            description = "Trigger cleanup when free inventory slots drop to this value. "
                    + "Must be at least 4 (3 needed for event materials + 1 buffer). "
                    + "A random ±1 is applied each check for natural-looking behaviour.",
            position = 1,
            section = inventorySection
    )
    @Range(min = 4, max = 8)
    default int minFreeSlots() {
        return 5;
    }

    // -------------------------------------------------------------------------
    // Inner enums
    // -------------------------------------------------------------------------

    enum TrekDirection {
        /** Start at Paterdomus, travel to Burgh de Rott. */
        BURGH_DE_ROTT_RAMBLE,
        /** Start at Burgh de Rott, travel to Paterdomus. */
        TEMPLE_TREKKING
    }

    enum InventoryManagement {
        /** Do nothing — handle tokens manually. */
        NONE,
        /**
         * Walk to Burgh de Rott bank and deposit all reward tokens.
         * Only triggers if the player is already in Burgh de Rott at trek end.
         * If not, runs another trek first.
         */
        BANK,
        /**
         * Redeem each reward token for an XP tome (9th option on the reward menu),
         * then read all tomes to claim the XP.
         */
        REDEEM_TOMES
    }
}
