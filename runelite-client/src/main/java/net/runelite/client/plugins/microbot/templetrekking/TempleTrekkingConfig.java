package net.runelite.client.plugins.microbot.templetrekking;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

/**
 * The config interface defines the settings panel that appears in RuneLite
 * when the user right-clicks the plugin and selects "Settings".
 *
 * Each method here becomes a UI control in that panel.
 * The annotation @ConfigItem turns a method into a setting, and the
 * default return value is what the setting starts at.
 *
 * @ConfigGroup must match the string used in @PluginDescriptor later.
 */
@ConfigGroup("templetrekking")
public interface TempleTrekkingConfig extends Config {

    // -------------------------------------------------------------------------
    // Sections group related settings visually in the panel
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

    // -------------------------------------------------------------------------
    // Trek Settings
    // -------------------------------------------------------------------------

    /**
     * Which direction to run. This controls which NPC we look for at the start.
     *
     * Temple Trekking  = Burgh de Rott → Paterdomus (normal direction)
     * Burgh de Rott Ramble = Paterdomus → Burgh de Rott (requires Darkness of Hallowvale)
     *
     * For now the bot always uses easy followers — this setting controls start location.
     */
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

    /**
     * If true, the bot will complete the bridge event by chopping trees and
     * using logs to repair the bridge.
     * If false, it will try to evade (may not always be possible for puzzle events).
     */
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

    /**
     * If true, the bot will kill undead lumberjacks, loot planks, and repair the bridge.
     */
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

    /**
     * If true, the bot will cut vines and swing across the river.
     */
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
    // Inner enum for trek direction — kept here because it belongs to config
    // -------------------------------------------------------------------------

    /**
     * Enum representing which direction the bot travels.
     * This determines which starting NPC to look for.
     */
    enum TrekDirection {
        /** Start at Paterdomus, travel to Burgh de Rott. Requires Darkness of Hallowvale. */
        BURGH_DE_ROTT_RAMBLE,
        /** Start at Burgh de Rott, travel to Paterdomus. Requires In Aid of the Myreque. */
        TEMPLE_TREKKING
    }
}
