package net.runelite.client.plugins.microbot.allotmentrun;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.api.npc.Rs2NpcQueryable;
import net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectQueryable;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.questhelper.helpers.mischelpers.farmruns.CropState;
import net.runelite.client.plugins.microbot.questhelper.helpers.mischelpers.farmruns.FarmingHandler;
import net.runelite.client.plugins.microbot.questhelper.helpers.mischelpers.farmruns.FarmingPatch;
import net.runelite.client.plugins.microbot.questhelper.helpers.mischelpers.farmruns.FarmingWorld;
import net.runelite.client.plugins.microbot.questhelper.helpers.mischelpers.farmruns.PatchImplementation;
import net.runelite.client.plugins.microbot.util.Rs2InventorySetup;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.timetracking.Tab;

import javax.inject.Inject;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static net.runelite.client.plugins.microbot.Microbot.log;

/**
 * AllotmentrunScript — state-machine allotment runner.
 *
 * Interaction API note:
 *   Rs2TileObjectModel and Rs2NpcModel both expose .click(String action),
 *   NOT .interact(String action). All calls use .click() accordingly.
 *
 * Flow per patch:
 *   WEEDS       → rakeLoop() until no Rake action visible → next tick reads Empty
 *   EMPTY       → compost → pay farmer → plant seeds
 *   HARVESTABLE → harvestLoop() using "Harvest" action (allotments use Harvest, not Pick)
 *                 notes produce with Leprechaun if inventory fills or after done
 *   DEAD        → clear → next tick reads Weeds/Empty
 *   GROWING     → skip
 */
@Slf4j
public class AllotmentrunScript extends Script {

    private static final boolean DEBUG = false;
    private static final int SEEDS_PER_PATCH = 3;
    private static final int PATCH_RADIUS = 6;

    // State detection using varbit values (what FarmingPatch is designed for)
    // These are the canonical varbit ranges from PatchImplementation
    
    private static final Set<Integer> WEEDY_OR_EMPTY_VARBITS = new HashSet<>();
    static {
        // All weed stages (inverse count: 0-3 weeds)
        for (int i = 0; i <= 5; i++) WEEDY_OR_EMPTY_VARBITS.add(i);
        // Weeds variant stages
        for (int i = 74; i <= 76; i++) WEEDY_OR_EMPTY_VARBITS.add(i);
        for (int i = 81; i <= 83; i++) WEEDY_OR_EMPTY_VARBITS.add(i);
        for (int i = 88; i <= 90; i++) WEEDY_OR_EMPTY_VARBITS.add(i);
        for (int i = 95; i <= 97; i++) WEEDY_OR_EMPTY_VARBITS.add(i);
        for (int i = 104; i <= 106; i++) WEEDY_OR_EMPTY_VARBITS.add(i);
        for (int i = 113; i <= 115; i++) WEEDY_OR_EMPTY_VARBITS.add(i);
        for (int i = 124; i <= 127; i++) WEEDY_OR_EMPTY_VARBITS.add(i);
        WEEDY_OR_EMPTY_VARBITS.add(141);
        for (int i = 145; i <= 148; i++) WEEDY_OR_EMPTY_VARBITS.add(i);
        for (int i = 152; i <= 155; i++) WEEDY_OR_EMPTY_VARBITS.add(i);
        for (int i = 159; i <= 162; i++) WEEDY_OR_EMPTY_VARBITS.add(i);
        for (int i = 168; i <= 171; i++) WEEDY_OR_EMPTY_VARBITS.add(i);
        for (int i = 177; i <= 180; i++) WEEDY_OR_EMPTY_VARBITS.add(i);
        for (int i = 188; i <= 192; i++) WEEDY_OR_EMPTY_VARBITS.add(i);
        WEEDY_OR_EMPTY_VARBITS.add(205);
    }
    
    private static final Set<Integer> HARVESTABLE_VARBITS = new HashSet<>();
    static {
        // Harvest ranges for all crops
        for (int i = 10; i <= 12; i++) HARVESTABLE_VARBITS.add(i);  // Potato
        for (int i = 17; i <= 19; i++) HARVESTABLE_VARBITS.add(i);  // Onion
        for (int i = 24; i <= 26; i++) HARVESTABLE_VARBITS.add(i);  // Cabbage
        for (int i = 31; i <= 33; i++) HARVESTABLE_VARBITS.add(i);  // Tomato
        for (int i = 40; i <= 42; i++) HARVESTABLE_VARBITS.add(i);  // Sweetcorn
        for (int i = 49; i <= 51; i++) HARVESTABLE_VARBITS.add(i);  // Strawberry
        for (int i = 60; i <= 62; i++) HARVESTABLE_VARBITS.add(i);  // Watermelon
        for (int i = 138; i <= 140; i++) HARVESTABLE_VARBITS.add(i); // Snape grass
    }
    
    private static final Set<Integer> DEAD_VARBITS = new HashSet<>();
    static {
        for (int i = 199; i <= 201; i++) DEAD_VARBITS.add(i);
        for (int i = 206; i <= 208; i++) DEAD_VARBITS.add(i);
        for (int i = 209; i <= 211; i++) DEAD_VARBITS.add(i);
        for (int i = 213; i <= 215; i++) DEAD_VARBITS.add(i);
        for (int i = 220; i <= 222; i++) DEAD_VARBITS.add(i);
        for (int i = 227; i <= 231; i++) DEAD_VARBITS.add(i);
        for (int i = 236; i <= 240; i++) DEAD_VARBITS.add(i);
        for (int i = 245; i <= 251; i++) DEAD_VARBITS.add(i);
    }
    
    private static final Set<Integer> DISEASED_VARBITS = new HashSet<>();
    static {
        for (int i = 135; i <= 137; i++) DISEASED_VARBITS.add(i);
        for (int i = 142; i <= 144; i++) DISEASED_VARBITS.add(i);
        for (int i = 149; i <= 151; i++) DISEASED_VARBITS.add(i);
        for (int i = 156; i <= 158; i++) DISEASED_VARBITS.add(i);
        for (int i = 163; i <= 167; i++) DISEASED_VARBITS.add(i);
        for (int i = 172; i <= 176; i++) DISEASED_VARBITS.add(i);
        for (int i = 181; i <= 187; i++) DISEASED_VARBITS.add(i);
        for (int i = 193; i <= 198; i++) DISEASED_VARBITS.add(i);
    }
    
    // All other allotment IDs are growing crops (we just skip them)
    
    private static final Set<String> ALLOTMENT_PRODUCE = new HashSet<>(Arrays.asList(
            "potato", "onion", "cabbage", "tomato", "sweetcorn",
            "strawberry", "watermelon", "snape grass"
    ));

    // Known allotment object names to avoid interacting with herb/flower patches
    // Accepts: "allotment", "allotment (empty)", crop names when harvestable like "Watermelon", "Potato", etc.
    private static final Set<String> CROP_NAMES = new HashSet<>(Arrays.asList(
            "potato", "onion", "cabbage", "tomato", "sweetcorn", 
            "strawberry", "watermelon", "snape grass"
    ));
    
    private static boolean isAllotmentObject(String objName) {
        if (objName == null) return false;
        String lower = objName.toLowerCase();
        // Match generic allotment patches
        if (lower.equals("allotment") || lower.startsWith("allotment (")) return true;
        // Match harvestable crops (e.g., "Watermelon", "Potato")
        if (CROP_NAMES.contains(lower)) return true;
        return false;
    }

    @Inject private ConfigManager configManager;
    @Inject private FarmingWorld farmingWorld;
    private FarmingHandler farmingHandler;
    private final AllotmentrunPlugin plugin;
    private final AllotmentrunConfig config;

    @Inject ClientThread clientThread;
    private boolean initialized = false;

    private AllotmentPatch currentPatch;
    private List<FarmingPatch> currentClusterPatches = new ArrayList<>();
    private final List<AllotmentPatch> allotmentPatches = new ArrayList<>();
    private final List<FarmingPatch> allFarmingPatches = new ArrayList<>();

    @Inject
    public AllotmentrunScript(AllotmentrunPlugin plugin, AllotmentrunConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    // ── Patch locations ───────────────────────────────────────────────────────

    private static List<WorldPoint> getPatchLocations(String regionName) {
        switch (regionName) {
            case "Ardougne":  return Arrays.asList(new WorldPoint(2672, 3379, 0), new WorldPoint(2672, 3371, 0));
            case "Catherby":  return Arrays.asList(new WorldPoint(2814, 3466, 0), new WorldPoint(2815, 3460, 0));
            case "Falador":   return Arrays.asList(new WorldPoint(3052, 3307, 0), new WorldPoint(3055, 3305, 0));
            case "Kourend":   // fall-through — some API versions return "Kourend" for Hosidius
            case "Hosidius":  return Arrays.asList(new WorldPoint(1739, 3553, 0), new WorldPoint(1735, 3552, 0));
            case "Morytania": return Arrays.asList(new WorldPoint(3597, 3524, 0), new WorldPoint(3601, 3522, 0));
            default:          return Collections.emptyList();
        }
    }

    // ── Main loop ─────────────────────────────────────────────────────────────

    public boolean run() {
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn()) return;
                if (!super.run()) return;
                tick();
            } catch (Exception e) {
                log("[ERROR] " + e.getMessage());
            }
        }, 0, 800, TimeUnit.MILLISECONDS); // Faster tick rate for snappier behavior
        return true;
    }

    private void tick() {
        if (!initialized) {
            initialized = true;
            AllotmentrunPlugin.status = "Initializing";
            populateAllotmentPatches();
            sleepUntil(() -> !allotmentPatches.isEmpty(), 2000);

            if (allotmentPatches.isEmpty()) {
                AllotmentrunPlugin.status = "Nothing to do";
                return;
            }
            if (!setupInventory()) return;
            log("Ready — " + allotmentPatches.size() + " cluster(s)");
        }

        if (Rs2Inventory.hasItem("Weeds")) Rs2Inventory.drop("Weeds");

        if (currentPatch == null) advanceToNextPatch();
        if (currentPatch == null) { finishRun(); return; }
        if (!currentPatch.isEnabled()) {
            currentPatch = null;
            currentClusterPatches.clear();
            return;
        }

        if (!currentPatch.isInRange(8)) {
            AllotmentrunPlugin.status = "Walking to " + currentPatch.getRegionName();
            Rs2Walker.walkTo(currentPatch.getLocation(), 4);
            return;
        }

        if (Rs2Player.isMoving()) {
            AllotmentrunPlugin.status = "Arriving at " + currentPatch.getRegionName();
            return;
        }

        if (currentClusterPatches.isEmpty()) {
            populateClusterPatches();
            if (currentClusterPatches.isEmpty()) {
                log("No cluster patches for " + currentPatch.getRegionName());
                currentPatch = null;
                return;
            }
        }

        AllotmentrunPlugin.status = "Farming " + currentPatch.getRegionName();
        if (workCluster()) {
            log("Cluster done: " + currentPatch.getRegionName());
            currentPatch = null;
            currentClusterPatches.clear();
        }
    }

    // ── Cluster ───────────────────────────────────────────────────────────────
    // 
    // Architecture: Process each patch once per tick. Track if we're waiting
    // for a multi-tick operation (raking/planting). Don't let unprocessable
    // patches (already harvested, growing) block progression.

    private int currentPatchIndex = 0;
    private boolean waitingForMultiTick = false;

    private boolean workCluster() {
        List<WorldPoint> locations = getPatchLocations(currentPatch.getRegionName());
        if (locations.isEmpty()) return true;

        // If we were waiting for a multi-tick operation, resume from that patch
        int startIndex = waitingForMultiTick ? currentPatchIndex : 0;
        waitingForMultiTick = false;

        for (int i = startIndex; i < locations.size(); i++) {
            WorldPoint loc = locations.get(i);
            FarmingPatch fp = (i < currentClusterPatches.size()) ? currentClusterPatches.get(i) : null;
            String label = currentPatch.getRegionName() + "/" + (fp != null ? fp.getName() : "#" + i);

            if (fp == null) { 
                log("[" + label + "] No FarmingPatch"); 
                continue; 
            }

            String state = readState(loc, fp, label);
            if (DEBUG) log("[" + label + "] state=" + state);

            boolean needsRetry = processPatchState(loc, fp, label, state);
            
            if (needsRetry) {
                // Multi-tick operation in progress, resume from this index next tick
                currentPatchIndex = i;
                waitingForMultiTick = true;
                return false;
            }
            
            // Patch processed successfully, continue to next
        }
        
        // All patches processed
        currentPatchIndex = 0;
        return true;
    }

    /**
     * Process a single patch based on its state.
     * @return true if this patch needs to be retried next tick (multi-tick operation)
     */
    private boolean processPatchState(WorldPoint loc, FarmingPatch fp, String label, String state) {
        switch (state) {
            case "Growing":
            case "Diseased":
                // Nothing to do, just skip
                if (state.equals("Diseased")) {
                    log("[" + label + "] Diseased — skipping");
                }
                return false; // No retry needed
                
            case "Dead":
                clearDeadPatch(loc, fp, label);
                // After clearing, re-read state and continue in same tick
                state = readState(loc, fp, label);
                if (DEBUG) log("[" + label + "] Post-clear state=" + state);
                
                if (state.equals("Weeds")) {
                    rakeLoop(loc, fp, label);
                    return true; // Raking takes multiple ticks
                } else if (state.equals("Empty")) {
                    return plantPatch(loc, fp, label); // Returns true if needs retry
                }
                return false; // Cleared but not yet plantable, continue
                
            case "Weeds":
                rakeLoop(loc, fp, label);
                return true; // Raking takes multiple ticks
                
            case "Empty":
                return plantPatch(loc, fp, label); // Returns true if needs retry
                
            case "Harvestable":
                return harvestPatch(loc, fp, label);
                
            default:
                log("[" + label + "] Unknown state: " + state);
                return false;
        }
    }

    /**
     * Harvest a patch, then plant if it becomes empty.
     * @return true if needs retry next tick
     */
    private boolean harvestPatch(WorldPoint loc, FarmingPatch fp, String label) {
        AllotmentrunPlugin.status = "Harvesting " + currentPatch.getRegionName();
        log("[" + label + "] Harvest loop start");

        int consecutiveNoProgress = 0;
        int maxNoProgress = 3;

        for (int i = 0; i < 40; i++) {
            // Check varbit - stop when no longer harvestable
            int varbitValue = Microbot.getVarbitValue(fp.getVarbit());
            if (!HARVESTABLE_VARBITS.contains(varbitValue)) {
                log("[" + label + "] Varbit=" + varbitValue + " — no longer harvestable, done");
                break;
            }

            Rs2TileObjectModel target = findPatchAction(loc, "Harvest");
            if (target == null) {
                log("[" + label + "] No Harvest action — done harvesting");
                break;
            }

            int produceCountBefore = (int) Rs2Inventory.items()
                    .filter(item -> !item.isNoted()
                            && ALLOTMENT_PRODUCE.contains(item.getName().toLowerCase().trim()))
                    .count();
            log("[" + label + "] Harvest " + (i + 1) + " (inv produce=" + produceCountBefore + ")");
            
            clickWithRandomOffset(target, "Harvest");

            boolean activity = sleepUntil(() -> {
                        int after = (int) Rs2Inventory.items()
                                .filter(item -> !item.isNoted()
                                        && ALLOTMENT_PRODUCE.contains(item.getName().toLowerCase().trim()))
                                .count();
                        return Rs2Player.isAnimating() || after > produceCountBefore || Rs2Inventory.isFull();
                    },
                    2000);

            if (!activity) {
                consecutiveNoProgress++;
                if (consecutiveNoProgress >= maxNoProgress) {
                    log("[" + label + "] No harvest activity after " + consecutiveNoProgress + " tries — done");
                    break;
                }
                log("[" + label + "] No harvest activity detected, retry " + consecutiveNoProgress + "/" + maxNoProgress);
                sleep(400);
                continue;
            }

            consecutiveNoProgress = 0;
            Rs2Player.waitForAnimation(3000);
            sleep((int) (Math.random() * 400 + 200));

            if (Rs2Inventory.hasItem("Weeds")) Rs2Inventory.drop("Weeds");
            if (Rs2Inventory.isFull()) noteProduceWithLeprechaun(label);
        }

        if (config.noteProduceAfterHarvest()) noteProduceWithLeprechaun(label);
        log("[" + label + "] Harvest loop done");

        // After harvesting, re-read state and attempt to plant if empty/weedy
        String postHarvestState = readState(loc, fp, label);
        if (DEBUG) log("[" + label + "] Post-harvest state=" + postHarvestState);

        if (postHarvestState.equals("Weeds")) {
            rakeLoop(loc, fp, label);
            // After raking, check state again and plant if empty
            postHarvestState = readState(loc, fp, label);
            if (postHarvestState.equals("Empty")) {
                return plantPatch(loc, fp, label);
            }
            return true; // Raking done but may need more ticks
        } else if (postHarvestState.equals("Empty")) {
            return plantPatch(loc, fp, label);
        }

        return false; // Done with this patch
    }

    // ── Rake ──────────────────────────────────────────────────────────────────

    private void rakeLoop(WorldPoint loc, FarmingPatch fp, String label) {
        AllotmentrunPlugin.status = "Raking " + currentPatch.getRegionName();
        log("[" + label + "] Rake loop start");

        if (!Rs2Inventory.hasItem("Rake")) {
            log("[" + label + "] ERROR: No rake in inventory — cannot rake");
            return;
        }

        // Click "Rake" once — OSRS keeps raking until the patch is empty
        Rs2TileObjectModel target = findPatchAction(loc, "Rake");
        if (target == null) {
            log("[" + label + "] No Rake action — patch already clean");
            return;
        }

        log("[" + label + "] Clicking Rake (auto-continues until patch is clean)");
        clickWithRandomOffset(target, "Rake");

        // Wait for the patch to no longer be weedy (raking is done)
        boolean done = sleepUntil(() -> {
            int v = Microbot.getVarbitValue(fp.getVarbit());
            return !WEEDY_OR_EMPTY_VARBITS.contains(v);
        }, 30000);

        if (done) {
            log("[" + label + "] Raking complete — varbit changed");
        } else {
            log("[" + label + "] Raking timed out, but continuing");
        }

        if (Rs2Inventory.hasItem("Weeds")) Rs2Inventory.drop("Weeds");
    }

    /**
     * Clicks on a tile object with a random offset to avoid perfect clicking patterns.
     */
    private void clickWithRandomOffset(Rs2TileObjectModel obj, String action) {
        // OSRS already has some variance in click targeting, but we add a small
        // random delay before clicking to vary the timing
        sleep((int) (Math.random() * 300 + 100));
        obj.click(action);
    }

    // ── Plant ─────────────────────────────────────────────────────────────────

    private boolean plantPatch(WorldPoint loc, FarmingPatch fp, String label) {
        // Compost — try action-based first, fall back to use-item-on-object
        if (config.compostType() != CompostType.NONE) {
            CompostType compost = config.compostType();
            if (Rs2Inventory.hasItem(compost.getItemId())) {
                Rs2TileObjectModel target = findPatchAction(loc, "Compost");
                if (target != null) {
                    AllotmentrunPlugin.status = "Composting " + currentPatch.getRegionName();
                    log("[" + label + "] Composting (via Compost action)");
                    Rs2Inventory.use(compost.getItemId());
                    target.click("Compost");
                    Rs2Player.waitForXpDrop(Skill.FARMING, 4000, false);
                    sleep(400);
                    if (config.dropEmptyBuckets() && !compost.isBottomless())
                        Rs2Inventory.drop(ItemID.BUCKET_EMPTY);
                } else {
                    // Fallback: use compost item directly on the patch object.
                    // Some patch states don't expose a "Compost" action but still accept
                    // the item being used on them. Find any allotment object at the location.
                    Rs2TileObjectModel patchObj = findAnyAllotmentAt(loc);
                    if (patchObj != null) {
                        AllotmentrunPlugin.status = "Composting " + currentPatch.getRegionName();
                        log("[" + label + "] Composting (use item on patch)");
                        Rs2Inventory.use(compost.getItemId());
                        clickWithRandomOffset(patchObj, "Use");
                        Rs2Player.waitForXpDrop(Skill.FARMING, 4000, false);
                        sleep((int) (Math.random() * 400 + 200));
                        if (config.dropEmptyBuckets() && !compost.isBottomless())
                            Rs2Inventory.drop(ItemID.BUCKET_EMPTY);
                    } else {
                        log("[" + label + "] WARNING: No patch object found to compost — skipping compost");
                    }
                }
            } else {
                log("[" + label + "] WARNING: No " + compost.name() + " in inventory — skipping compost");
            }
        }

        // Pay farmer
        if (config.payFarmer()) tryPayFarmer(label);

        // Plant
        AllotmentSeedType seedType = getFirstAllotmentSeedInInventory();
        if (seedType == null) { log("[" + label + "] No seeds"); return false; }

        AllotmentrunPlugin.status = "Planting " + currentPatch.getRegionName();
        log("[" + label + "] Planting " + seedType.getSeedName());

        // Empty patches don't have a "Plant" action — use seeds on the patch object directly.
        // We need to find the actual allotment patch object, not any nearby object
        Rs2TileObjectModel patchObj = findAnyAllotmentAt(loc);
        if (patchObj == null) { 
            log("[" + label + "] No allotment patch object found near " + loc); 
            return false; 
        }
        
        String objName = patchObj.getName() != null ? patchObj.getName() : "(null)";
        log("[" + label + "] Using seeds on object: id=" + patchObj.getId() + ", name='" + objName + "'");

        int varbitBefore = Microbot.getVarbitValue(fp.getVarbit());
        log("[" + label + "] Varbit before planting: " + varbitBefore);

        Rs2Inventory.use(seedType.getItemId());
        clickWithRandomOffset(patchObj, "Use");

        // Wait for varbit to change to a growing state (not in weedy/empty range)
        // After planting seeds, varbit should jump to the crop's growing range
        boolean planted = sleepUntil(() -> {
            int afterVarbit = Microbot.getVarbitValue(fp.getVarbit());
            return !WEEDY_OR_EMPTY_VARBITS.contains(afterVarbit);
        }, 8000);

        int varbitAfter = Microbot.getVarbitValue(fp.getVarbit());
        log("[" + label + "] Plant " + (planted ? "OK" : "timed out")
                + " — varbit before=" + varbitBefore + ", after=" + varbitAfter);
        return planted;
    }

    // ── Clear ─────────────────────────────────────────────────────────────────

    private void clearDeadPatch(WorldPoint loc, FarmingPatch fp, String label) {
        Rs2TileObjectModel target = findPatchAction(loc, "Clear");
        if (target == null) { log("[" + label + "] No Clear action"); return; }
        AllotmentrunPlugin.status = "Clearing " + currentPatch.getRegionName();
        log("[" + label + "] Clearing dead crop");
        target.click("Clear");
        sleepUntil(() -> !varbitToAllotmentState(
                Microbot.getVarbitValue(fp.getVarbit())).equals("Dead"), 8000);
    }

    // ── Leprechaun noting ─────────────────────────────────────────────────────

    private void noteProduceWithLeprechaun(String label) {
        log("[" + label + "] Attempting to note produce with Tool Leprechaun");
        
        // Search for NPCs with "leprechaun" in the name (case-insensitive)
        var leprechaun = new Rs2NpcQueryable()
                .where(npc -> {
                    String name = npc.getName();
                    if (name == null) return false;
                    return name.toLowerCase().contains("leprechaun");
                })
                .nearest(30);

        if (leprechaun == null) {
            log("[" + label + "] No Tool Leprechaun found within 30 tiles");
            return;
        }

        AllotmentrunPlugin.status = "Noting produce";
        for (int i = 0; i < 15; i++) {
            var produce = Rs2Inventory.items()
                    .filter(item -> !item.isNoted()
                            && ALLOTMENT_PRODUCE.contains(item.getName().toLowerCase().trim()))
                    .findFirst().orElse(null);
            if (produce == null) break;

            log("[" + label + "] Noting: " + produce.getName());
            Rs2Inventory.use(produce);
            leprechaun.click("Use");
            Rs2Inventory.waitForInventoryChanges(3000);
            sleep(300);
        }
    }

    // ── Object interaction helper ─────────────────────────────────────────────

    private Rs2TileObjectModel findPatchAction(WorldPoint patchLoc, String action) {
        return new Rs2TileObjectQueryable()
                .within(patchLoc, PATCH_RADIUS)
                .where(obj -> {
                    var comp = obj.getObjectComposition();
                    if (comp == null) return false;
                    // Only match allotment object names to avoid interacting with herb/flower patches
                    String objName = comp.getName();
                    if (objName == null) return false;
                    
                    // Debug: log all object names found at patch location
                    if (DEBUG) {
                        boolean matches = isAllotmentObject(objName);
                        log("[DEBUG findPatchAction] Found object: '" + objName + "' (matches=" + matches + ")");
                    }
                    
                    if (!isAllotmentObject(objName)) return false;
                    String[] actions = (comp.getImpostorIds() != null && comp.getImpostor() != null)
                            ? comp.getImpostor().getActions()
                            : comp.getActions();
                    if (actions == null) return false;
                    for (String a : actions) {
                        if (a != null && a.toLowerCase().startsWith(action.toLowerCase())) return true;
                    }
                    return false;
                })
                .nearest(patchLoc, PATCH_RADIUS);
    }

    /**
     * Find any allotment patch object at the given location, regardless of available actions.
     * Used for fallback interactions (e.g., composting via use-item-on-object).
     */
    /**
     * Find the actual allotment patch object at the given location.
     * Filters by known allotment object names to avoid clicking random scenery.
     */
    private Rs2TileObjectModel findAnyAllotmentAt(WorldPoint patchLoc) {
        return new Rs2TileObjectQueryable()
                .within(patchLoc, PATCH_RADIUS)
                .where(obj -> {
                    var comp = obj.getObjectComposition();
                    if (comp == null) return false;
                    String objName = comp.getName();
                    return isAllotmentObject(objName);
                })
                .nearest(patchLoc, PATCH_RADIUS);
    }

    // ── State reading (using varbits, which FarmingPatch is designed for) ─────

    private String readState(WorldPoint loc, FarmingPatch fp, String label) {
        // First, log all objects in the area for debugging
        // debug removed
        
        int varbitValue = Microbot.getVarbitValue(fp.getVarbit());
        if (DEBUG) log("[DEBUG " + label + "] Varbit=" + fp.getVarbit() + ", value=" + varbitValue);
        
        if (WEEDY_OR_EMPTY_VARBITS.contains(varbitValue)) {
            boolean hasRake = findPatchAction(loc, "Rake") != null;
            return hasRake ? "Weeds" : "Empty";
        }
        
        if (HARVESTABLE_VARBITS.contains(varbitValue)) {
            boolean hasHarvest = findPatchAction(loc, "Harvest") != null;
            // If varbit says harvestable but no Harvest action, already harvested → Empty
            return hasHarvest ? "Harvestable" : "Empty";
        }
        
        if (DEAD_VARBITS.contains(varbitValue)) {
            return "Dead";
        }
        
        if (DISEASED_VARBITS.contains(varbitValue)) {
            return "Diseased";
        }
        
        // Any other varbit value = growing crop
        return "Growing";
    }
    
    /**
     * Debug: Log objects found via findPatchAction for different actions.
     * This helps identify what actions are available on patches.
     */
    private void debugLogAllObjectsAtLocation(WorldPoint loc, String label) {
        if (!DEBUG) return;
        
        try {
            String[] actionsToCheck = {"Rake", "Harvest", "Plant", "Compost", "Clear", "Inspect"};
            for (String action : actionsToCheck) {
                Rs2TileObjectModel obj = findPatchAction(loc, action);
                if (obj != null) {
                    log("[DEBUG-ACTIONS " + label + "] '" + action + "' found: ID=" + obj.getId() + 
                        ", name='" + (obj.getName() != null ? obj.getName() : "(null)") + "'");
                }
            }
        } catch (Exception e) {
            log("[DEBUG-ACTIONS " + label + "] Error: " + e.getMessage());
        }
    }

    static String varbitToAllotmentState(int v) {
        if (v >= 0   && v <= 5)   return "Weeds";

        if (v >= 10  && v <= 12)  return "Harvestable"; // Potato
        if (v >= 17  && v <= 19)  return "Harvestable"; // Onion
        if (v >= 24  && v <= 26)  return "Harvestable"; // Cabbage
        if (v >= 31  && v <= 33)  return "Harvestable"; // Tomato
        if (v >= 40  && v <= 42)  return "Harvestable"; // Sweetcorn
        if (v >= 49  && v <= 51)  return "Harvestable"; // Strawberry
        if (v >= 60  && v <= 62)  return "Harvestable"; // Watermelon
        if (v >= 138 && v <= 140) return "Harvestable"; // Snape grass

        if (v >= 135 && v <= 137) return "Diseased";
        if (v >= 142 && v <= 144) return "Diseased";
        if (v >= 149 && v <= 151) return "Diseased";
        if (v >= 156 && v <= 158) return "Diseased";
        if (v >= 163 && v <= 167) return "Diseased";
        if (v >= 172 && v <= 176) return "Diseased";
        if (v >= 181 && v <= 187) return "Diseased";
        if (v >= 193 && v <= 198) return "Diseased";

        if (v >= 199 && v <= 201) return "Dead";
        if (v >= 206 && v <= 208) return "Dead";
        if (v >= 209 && v <= 211) return "Dead";
        if (v >= 213 && v <= 215) return "Dead";
        if (v >= 220 && v <= 222) return "Dead";
        if (v >= 227 && v <= 231) return "Dead";
        if (v >= 236 && v <= 240) return "Dead";
        if (v >= 245 && v <= 251) return "Dead";

        if (v >= 74  && v <= 76)  return "Weeds";
        if (v >= 81  && v <= 83)  return "Weeds";
        if (v >= 88  && v <= 90)  return "Weeds";
        if (v >= 95  && v <= 97)  return "Weeds";
        if (v >= 104 && v <= 106) return "Weeds";
        if (v >= 113 && v <= 115) return "Weeds";
        if (v >= 124 && v <= 127) return "Weeds";
        if (v == 141)             return "Weeds";
        if (v >= 145 && v <= 148) return "Weeds";
        if (v >= 152 && v <= 155) return "Weeds";
        if (v >= 159 && v <= 162) return "Weeds";
        if (v >= 168 && v <= 171) return "Weeds";
        if (v >= 177 && v <= 180) return "Weeds";
        if (v >= 188 && v <= 192) return "Weeds";
        if (v == 205)             return "Weeds";
        if (v == 212)             return "Weeds";
        if (v >= 216 && v <= 219) return "Weeds";

        if (v >= 6 && v <= 134)  return "Growing";

        log("[WARN] varbitToAllotmentState: unexpected v=" + v);
        return "Weeds";
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void tryPayFarmer(String label) {
        var gardener = new Rs2NpcQueryable().withName("Gardener").nearest(10);
        if (gardener == null) { log("[" + label + "] No Gardener"); return; }
        AllotmentrunPlugin.status = "Paying farmer";
        gardener.click("Pay");
        sleepUntil(() -> !Rs2Player.isAnimating(), 5000);
    }

    private AllotmentSeedType getFirstAllotmentSeedInInventory() {
        for (AllotmentSeedType seed : AllotmentSeedType.values()) {
            if (seed != AllotmentSeedType.BEST && Rs2Inventory.hasItem(seed.getItemId()))
                return seed;
        }
        return null;
    }

    // ── Population ────────────────────────────────────────────────────────────

    private void populateAllotmentPatches() {
        this.farmingHandler = new FarmingHandler(Microbot.getClient(), configManager);
        allotmentPatches.clear();
        allFarmingPatches.clear();
        clientThread.runOnClientThreadOptional(() -> {
            Set<FarmingPatch> allotmentTab = farmingWorld.getTabs().get(Tab.ALLOTMENT);
            if (allotmentTab == null) return true;
            allFarmingPatches.addAll(allotmentTab);
            Set<String> seenRegions = new LinkedHashSet<>();
            for (FarmingPatch patch : allotmentTab) {
                String region = patch.getRegion().getName();
                if (seenRegions.contains(region)) continue;
                AllotmentPatch ap = new AllotmentPatch(patch, config, farmingHandler);
                if (ap.isEnabled() && ap.getPrediction() != CropState.GROWING) {
                    seenRegions.add(region);
                    allotmentPatches.add(ap);
                }
            }
            return true;
        });
    }

    private void populateClusterPatches() {
        currentClusterPatches.clear();
        String regionName = currentPatch.getRegionName();
        for (FarmingPatch fp : allFarmingPatches) {
            if (fp.getRegion().getName().equals(regionName)
                    && fp.getImplementation() == PatchImplementation.ALLOTMENT) {
                currentClusterPatches.add(fp);
            }
        }
        currentClusterPatches.sort(Comparator.comparingInt(FarmingPatch::getPatchNumber));
        log("Loaded " + currentClusterPatches.size() + " patches for " + regionName);
    }

    private void advanceToNextPatch() {
        currentPatch = allotmentPatches.stream().filter(AllotmentPatch::isEnabled).findFirst().orElse(null);
        if (currentPatch != null) allotmentPatches.remove(currentPatch);
    }

    // ── Finish run ────────────────────────────────────────────────────────────

    private void finishRun() {
        AllotmentrunPlugin.status = "Finishing";
        if (config.goToBank()) {
            Rs2Walker.walkTo(Rs2Bank.getNearestBank().getWorldPoint());
            sleepUntil(() -> !Rs2Player.isMoving(), 30000);
            if (!Rs2Bank.isOpen()) Rs2Bank.openBank();
            sleepUntil(Rs2Bank::isOpen, 5000);
            Rs2Bank.depositAll();
            Rs2Bank.closeBank();
        }
        AllotmentrunPlugin.status = "Finished";
        shutdown();
    }

    // ── Inventory setup ───────────────────────────────────────────────────────

    private boolean setupInventory() {
        if (config.bypassInventoryRequirements()) return true;
        if (config.useInventorySetup()) {
            var inv = new Rs2InventorySetup(config.inventorySetup(), mainScheduledFuture);
            if (!inv.doesInventoryMatch() || !inv.doesEquipmentMatch()) {
                Rs2Walker.walkTo(Rs2Bank.getNearestBank().getWorldPoint(), 20);
                return inv.loadEquipment() && inv.loadInventory();
            }
            return true;
        }
        return setupAutoInventory();
    }

    private boolean setupAutoInventory() {
        Rs2Walker.walkTo(Rs2Bank.getNearestBank().getWorldPoint(), 20);
        if (!Rs2Bank.openBank()) return false;
        if (!sleepUntil(Rs2Bank::isOpen, 10000)) return false;

        Rs2Bank.depositAll();
        Rs2Inventory.waitForInventoryChanges(4000);

        int patchCount = (int) allotmentPatches.stream().filter(AllotmentPatch::isEnabled).count() * 2;

        boolean toolsOk = Rs2Bank.withdrawX(ItemID.RAKE, 1)
                & Rs2Bank.withdrawX(ItemID.SPADE, 1)
                & Rs2Bank.withdrawX(ItemID.DIBBER, 1);
        if (!toolsOk) return false;

        AllotmentSeedType seedType = config.allotmentSeedType();
        int seedsNeeded = patchCount * SEEDS_PER_PATCH;

        if (seedType == AllotmentSeedType.BEST) {
            if (!withdrawBestSeeds(patchCount)) return false;
        } else {
            int level = Microbot.getClient().getRealSkillLevel(net.runelite.api.Skill.FARMING);
            if (!seedType.canPlant(level)) return false;
            if (!Rs2Bank.withdrawX(seedType.getItemId(), seedsNeeded)) {
                if (!config.allowPartialRuns()) return false;
                int avail = Rs2Bank.count(seedType.getItemId());
                if (avail >= SEEDS_PER_PATCH) Rs2Bank.withdrawX(seedType.getItemId(), avail);
                else return false;
            }
        }

        CompostType compost = config.compostType();
        if (compost != CompostType.NONE) {
            int needed = compost.isBottomless() ? 1 : patchCount;
            if (!Rs2Bank.withdrawX(compost.getItemId(), needed)) return false;
        }

        Rs2Bank.closeBank();
        sleepUntil(() -> !Rs2Bank.isOpen(), 5000);
        return true;
    }

    private boolean withdrawBestSeeds(int patchCount) {
        int level = Microbot.getClient().getRealSkillLevel(net.runelite.api.Skill.FARMING);
        List<AllotmentSeedType> plantable = AllotmentSeedType.getPlantableSeeds(level);
        if (plantable.isEmpty()) return false;
        int needed = patchCount * 2 * SEEDS_PER_PATCH;
        int withdrawn = 0;
        for (AllotmentSeedType s : plantable) {
            if (withdrawn >= needed) break;
            int avail = Rs2Bank.count(s.getItemId());
            if (avail > 0) {
                int take = Math.min(avail, needed - withdrawn);
                if (Rs2Bank.withdrawX(s.getItemId(), take)) withdrawn += take;
            }
        }
        return withdrawn >= SEEDS_PER_PATCH;
    }

    @Override
    public void shutdown() {
        super.shutdown();
        initialized = false;
        currentPatch = null;
        currentClusterPatches.clear();
    }
}
