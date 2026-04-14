package net.runelite.client.plugins.microbot.allotmentrun;

import lombok.Getter;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.questhelper.helpers.mischelpers.farmruns.CropState;
import net.runelite.client.plugins.microbot.questhelper.helpers.mischelpers.farmruns.FarmingHandler;
import net.runelite.client.plugins.microbot.questhelper.helpers.mischelpers.farmruns.FarmingPatch;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

import java.util.Objects;

/**
 * Represents a single allotment patch location.
 * Each farming area has TWO allotment patches — this model tracks one of them,
 * identified by the region name from FarmingWorld.
 */
@Getter
public class AllotmentPatch {

    // Approximate world-point for each allotment cluster used for walking.
    // Both allotments in a cluster share the same walk target; the script
    // then finds the actual game objects by ObjectID once in range.
    private static final WorldPoint FALADOR_WP     = new WorldPoint(3054, 3307, 0);
    private static final WorldPoint CATHERBY_WP    = new WorldPoint(2809, 3467, 0);
    private static final WorldPoint ARDOUGNE_WP    = new WorldPoint(2670, 3374, 0);
    private static final WorldPoint MORYTANIA_WP   = new WorldPoint(3602, 3523, 0);
    private static final WorldPoint HOSIDIUS_WP    = new WorldPoint(1738, 3554, 0);

    private final FarmingPatch patch;
    private final String regionName;
    private final CropState prediction;
    private final WorldPoint location;
    private boolean enabled;

    public AllotmentPatch(FarmingPatch patch, AllotmentrunConfig config, FarmingHandler farmingHandler) {
        this.patch = patch;
        this.regionName = patch.getRegion().getName();
        this.prediction = farmingHandler.predictPatch(patch);
        this.location = resolveLocation(regionName);

        switch (regionName) {
            case "Falador":
                this.enabled = config.enableFalador();
                break;
            case "Catherby":
                this.enabled = config.enableCatherby();
                break;
            case "Ardougne":
                this.enabled = config.enableArdougne();
                break;
            case "Morytania":
                this.enabled = config.enableMorytania();
                break;
            case "Kourend":   // fall-through — some API versions return "Kourend" for Hosidius
            case "Hosidius":
                this.enabled = config.enableHosidius();
                break;
            default:
                this.enabled = false;
                break;
        }
    }

    private static WorldPoint resolveLocation(String regionName) {
        switch (regionName) {
            case "Falador":   return FALADOR_WP;
            case "Catherby":  return CATHERBY_WP;
            case "Ardougne":  return ARDOUGNE_WP;
            case "Morytania": return MORYTANIA_WP;
            case "Kourend":   // fall-through
            case "Hosidius":  return HOSIDIUS_WP;
            default:          return new WorldPoint(0, 0, 0);
        }
    }

    public boolean isInRange(int distance) {
        return Rs2Player.getWorldLocation().distanceTo(location) < distance;
    }

    public boolean contains(WorldPoint worldPoint) {
        return Objects.equals(location, worldPoint);
    }
}
