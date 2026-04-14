package net.runelite.client.plugins.microbot.allotmentrun;

import net.runelite.client.config.*;
import net.runelite.client.plugins.microbot.inventorysetups.InventorySetup;

@ConfigInformation(
    "Automated Allotment Runs across all patches<br/><br/>" +
    "<b>Key differences from herb runner:</b><br/>" +
    "• Plants 3 seeds per patch (allotments require 3)<br/>" +
    "• Harvests vegetables, not herbs<br/>" +
    "• Supports farmer payment to prevent disease<br/><br/>" +
    "<b>Auto Banking withdraws:</b><br/>" +
    "• Farming tools (rake, spade, seed dibber)<br/>" +
    "• Your selected allotment seeds (×3 per patch)<br/>" +
    "• Your selected compost type<br/>" +
    "• Farmer payment items (if enabled)<br/><br/>" +
    "Patch locations: Falador, Catherby, Ardougne, Morytania, Hosidius")
@ConfigGroup("Allotmentrun")
public interface AllotmentrunConfig extends Config {

    // ── Inventory Setup ───────────────────────────────────────────────────────

    @ConfigSection(
            name = "Inventory Setup Method",
            description = "Choose between inventory setup or auto banking",
            position = 0
    )
    String inventorySection = "inventory";

    @ConfigItem(
            keyName = "useInventorySetup",
            name = "Use Inventory Setup",
            description = "Enable to use RuneLite inventory setups | Disable for automatic banking",
            section = inventorySection,
            position = 0
    )
    default boolean useInventorySetup() {
        return false;
    }

    @ConfigItem(
            keyName = "inventorySetup",
            name = "Inventory Setup Name",
            description = "Select your pre-configured inventory setup",
            section = inventorySection,
            position = 1
    )
    default InventorySetup inventorySetup() {
        return null;
    }

    @ConfigItem(
            keyName = "bypassInventoryRequirements",
            name = "Bypass Inventory Requirements",
            description = "Skip inventory checks and start with whatever is in your inventory",
            section = inventorySection,
            position = 2
    )
    default boolean bypassInventoryRequirements() {
        return false;
    }

    // ── Auto Banking ──────────────────────────────────────────────────────────

    @ConfigSection(
            name = "Auto Banking Settings",
            description = "Configure automatic banking options",
            position = 1
    )
    String autoSection = "autobanking";

    @ConfigItem(
            keyName = "allotmentSeedType",
            name = "Allotment Seed Type",
            description = "Choose which allotment seeds to plant (3 seeds used per patch)",
            section = autoSection,
            position = 0
    )
    default AllotmentSeedType allotmentSeedType() {
        return AllotmentSeedType.WATERMELON;
    }

    @ConfigItem(
            keyName = "compostType",
            name = "Compost Type",
            description = "Type of compost to apply (NONE to skip)",
            section = autoSection,
            position = 1
    )
    default CompostType compostType() {
        return CompostType.ULTRA;
    }

    @ConfigItem(
            keyName = "allowPartialRuns",
            name = "Allow Partial Runs",
            description = "Run even if there aren't enough seeds for all enabled patches",
            section = autoSection,
            position = 2
    )
    default boolean allowPartialRuns() {
        return false;
    }

    @ConfigItem(
            keyName = "dropEmptyBuckets",
            name = "Drop Empty Buckets",
            description = "Drop empty compost buckets after applying (not used with bottomless bucket)",
            section = autoSection,
            position = 3
    )
    default boolean dropEmptyBuckets() {
        return true;
    }

    // ── Produce Handling ──────────────────────────────────────────────────────

    @ConfigSection(
            name = "Produce Handling",
            description = "Settings for what to do with harvested produce",
            position = 2
    )
    String produceSection = "produce";

    @ConfigItem(
            keyName = "noteProduceAfterHarvest",
            name = "Note Produce After Harvest",
            description = "After harvesting each patch, use the nearby Tool Leprechaun to note all produce. " +
                    "Also triggered automatically if inventory fills during harvest.",
            section = produceSection,
            position = 0
    )
    default boolean noteProduceAfterHarvest() {
        return true;
    }

    // ── Disease Protection ────────────────────────────────────────────────────

    @ConfigSection(
            name = "Disease Protection",
            description = "Settings for protecting allotment patches from disease",
            position = 3
    )
    String diseaseSection = "disease";

    @ConfigItem(
            keyName = "payFarmer",
            name = "Pay Farmer to Protect",
            description = "Pay the nearby gardener with the appropriate item to prevent disease on each patch. " +
                    "Payment varies by seed type — the script will use whatever payment item is in your inventory.",
            section = diseaseSection,
            position = 0
    )
    default boolean payFarmer() {
        return false;
    }

    // ── General Settings ──────────────────────────────────────────────────────

    @ConfigSection(
            name = "General Settings",
            description = "General plugin settings",
            position = 4
    )
    String settingsSection = "settings";

    @ConfigItem(
            keyName = "goToBank",
            name = "Bank After Run",
            description = "Go to the closest bank after completing the allotment run",
            position = 0,
            section = settingsSection
    )
    default boolean goToBank() {
        return true;
    }

    // ── Location Toggles ──────────────────────────────────────────────────────

    @ConfigSection(
            name = "Location Toggles",
            description = "Enable or disable individual allotment patch locations",
            position = 5
    )
    String locationSection = "Location";

    @ConfigItem(
            keyName = "enableFalador",
            name = "Enable Falador Patches",
            description = "Include the two Falador allotment patches in the run",
            position = 0,
            section = locationSection
    )
    default boolean enableFalador() {
        return true;
    }

    @ConfigItem(
            keyName = "enableCatherby",
            name = "Enable Catherby Patches",
            description = "Include the two Catherby allotment patches in the run",
            position = 1,
            section = locationSection
    )
    default boolean enableCatherby() {
        return true;
    }

    @ConfigItem(
            keyName = "enableArdougne",
            name = "Enable Ardougne Patches",
            description = "Include the two Ardougne allotment patches in the run",
            position = 2,
            section = locationSection
    )
    default boolean enableArdougne() {
        return true;
    }

    @ConfigItem(
            keyName = "enableMorytania",
            name = "Enable Morytania Patches",
            description = "Include the two Morytania allotment patches in the run",
            position = 3,
            section = locationSection
    )
    default boolean enableMorytania() {
        return true;
    }

    @ConfigItem(
            keyName = "enableHosidius",
            name = "Enable Hosidius Patches",
            description = "Include the two Hosidius allotment patches in the run",
            position = 4,
            section = locationSection
    )
    default boolean enableHosidius() {
        return true;
    }
}
