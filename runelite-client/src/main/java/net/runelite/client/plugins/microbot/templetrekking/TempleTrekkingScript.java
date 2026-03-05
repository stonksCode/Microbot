package net.runelite.client.plugins.microbot.templetrekking;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * TempleTrekkingScript — the full state machine for automating Temple Trekking.
 *
 * KEY CONCEPTS FOR LEARNING:
 *
 * 1. STATE MACHINE: The bot is always in exactly one state. Each tick, the switch
 *    statement calls the handler for that state. Handlers either do work or change
 *    the state. This makes complex multi-step logic easy to reason about.
 *
 * 2. NEW API vs OLD API:
 *    Old (deprecated): Rs2Npc.getNpc("Name")  /  Rs2Npc.interact(npc, "action")
 *    New (use this):   Microbot.getRs2NpcCache().query().withName("Name").nearest()
 *                      npc.click("action")
 *    Same pattern applies to objects: Microbot.getRs2TileObjectCache().query()...
 *
 * 3. NULL CHECKS: Game objects can disappear between ticks. Always null-check
 *    before using a result from a query.
 *
 * 4. sleepUntil(): Pauses until a condition is true OR a timeout expires.
 *    Returns true if condition was met, false if it timed out.
 *    Only safe to call from our background thread — never from the client thread.
 */
@Slf4j
public class TempleTrekkingScript extends Script {

    // =========================================================================
    // NPC IDs — verified in-game
    // Using IDs is more reliable than names because names can change with
    // game updates, but IDs are stable.
    // =========================================================================

    // Burgh de Rott side (Temple Trekking start) — easy followers
    private static final int NPC_FYIONA_FRAY   = 1567;
    private static final int NPC_DALCIAN_FANG  = 1566;

    // Paterdomus side (Burgh de Rott Ramble start) — easy followers
    private static final int NPC_ADVENTURER    = 1577;
    private static final int NPC_MAGE          = 1578;

    // =========================================================================
    // Item IDs — verified in-game
    // =========================================================================

    private static final int ITEM_SHORT_VINE   = 7778;  // Cut from swamp trees (need 3)
    private static final int ITEM_LONG_VINE    = 7777;  // Combined from short vines
    private static final int ITEM_LOG          = 1511;  // Chopped from dead trees
    private static final int ITEM_PLANK        = 960;   // Looted from Undead Lumberjacks

    // =========================================================================
    // Object names used in queries
    // We query by name because Temple Trekking uses instanced areas where
    // object IDs may vary. Names are consistent within the minigame.
    // =========================================================================

    // Path stones that advance the trek
    private static final String OBJ_CONTINUE_PATH  = "Path";       // Blue stones: "Continue-trek"
    private static final String OBJ_EVADE_PATH      = "Path";       // Same object type, different option

    // Bridge event objects
    private static final String OBJ_BROKEN_BRIDGE         = "Broken bridge";
    private static final String OBJ_PARTIALLY_BROKEN      = "Partially broken bridge";
    private static final String OBJ_SLIGHTLY_BROKEN       = "Slightly broken bridge";
    private static final String OBJ_FIXED_BRIDGE          = "Fixed bridge";
    private static final String OBJ_DEAD_TREE             = "Dead tree";

    // River event objects
    private static final String OBJ_SWAMP_TREE            = "Swamp tree";    // Has "Cut-vine" option
    private static final String OBJ_SWAMP_TREE_BRANCH     = "Swamp tree branch"; // Throw/swing

    // =========================================================================
    // Start locations — approximate WorldPoints for walking to start area.
    // NOTE: Verify these in-game with RuneLite's Tile Location plugin.
    // =========================================================================
    private static final WorldPoint RAMBLE_START_LOCATION = new WorldPoint(3408, 3479, 0);
    private static final WorldPoint TREK_START_LOCATION   = new WorldPoint(3494, 3215, 0);

    // =========================================================================
    // State tracking (exposed to overlay via @Getter)
    // =========================================================================

    @Getter private TempleTrekkingState currentState = TempleTrekkingState.IDLE;
    @Getter private int trekCount = 0;
    @Getter private Instant startTime;

    private TempleTrekkingConfig config;

    // Tracks how many logs/planks we've used to repair the current bridge
    // so we know when the bridge is fully fixed.
    private int bridgeMaterialsUsed = 0;

    // =========================================================================
    // Entry point
    // =========================================================================

    public boolean run(TempleTrekkingConfig config) {
        this.config = config;
        this.startTime = Instant.now();
        this.currentState = TempleTrekkingState.START_TREK;

        log.info("Temple Trekking script starting. Direction: {}", config.trekDirection());

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!super.run()) return;
                if (!Microbot.isLoggedIn()) return;

                switch (currentState) {
                    case IDLE:               break;
                    case START_TREK:         handleStartTrek();        break;
                    case SELECT_ROUTE:       handleSelectRoute();      break;
                    case TREKKING:           handleTrekking();         break;
                    case DETECT_EVENT:       handleDetectEvent();      break;
                    case EVADE_COMBAT:       handleEvadeCombat();      break;
                    case BRIDGE_CHOP_TREES:  handleBridgeChopTrees();  break;
                    case BRIDGE_KILL_ZOMBIES:handleBridgeKillZombies();break;
                    case RIVER_CROSSING:     handleRiverCrossing();    break;
                    case CONTINUE_TREK:      handleContinueTrek();     break;
                    case TREK_COMPLETE:      handleTrekComplete();     break;
                    case UNKNOWN_EVENT:      handleUnknownEvent();     break;
                    default: log.warn("Unhandled state: {}", currentState); break;
                }
            } catch (Exception e) {
                log.error("Error in Temple Trekking script loop: ", e);
            }
        }, 0, 600, TimeUnit.MILLISECONDS);

        return true;
    }

    // =========================================================================
    // STATE: START_TREK
    //
    // Find an easy escort NPC and right-click "Escort" to start immediately
    // without going through dialogue, then wait for SELECT_ROUTE dialogue.
    // =========================================================================
    private void handleStartTrek() {
        // Pick which NPC IDs and start location to use based on direction config
        int[] npcIds = config.trekDirection() == TempleTrekkingConfig.TrekDirection.BURGH_DE_ROTT_RAMBLE
                ? new int[]{NPC_ADVENTURER, NPC_MAGE}
                : new int[]{NPC_FYIONA_FRAY, NPC_DALCIAN_FANG};

        WorldPoint startLocation = config.trekDirection() == TempleTrekkingConfig.TrekDirection.BURGH_DE_ROTT_RAMBLE
                ? RAMBLE_START_LOCATION
                : TREK_START_LOCATION;

        // Query the NPC cache for the nearest escort NPC matching any of our IDs.
        // withIds() accepts varargs — matches any NPC whose ID is in the array.
        Rs2NpcModel escortNpc = Microbot.getRs2NpcCache().query()
                .withIds(npcIds)
                .nearest();

        if (escortNpc == null) {
            // Not visible yet — walk toward the start area to load them in
            log.info("Escort NPC not found nearby, walking to start area...");
            Rs2Walker.walkTo(startLocation);
            return; // Check again next tick after walking
        }

        log.info("Found escort NPC: {} (id={}). Clicking Escort...", escortNpc.getName(), escortNpc.getId());

        // click("Escort") uses the right-click "Escort" option directly,
        // which skips most of the dialogue and goes straight to route selection.
        boolean clicked = escortNpc.click("Escort");

        if (clicked) {
            // Wait up to 5 seconds for a dialogue to open
            boolean dialogueOpened = sleepUntil(Rs2Dialogue::isInDialogue, 5000);
            if (dialogueOpened) {
                log.info("Dialogue opened, transitioning to SELECT_ROUTE");
                setState(TempleTrekkingState.SELECT_ROUTE);
            } else {
                log.warn("Clicked Escort but no dialogue opened within 5s, retrying...");
            }
        }
    }

    // =========================================================================
    // STATE: SELECT_ROUTE
    //
    // Navigate the route selection dialogue and pick Route 1.
    // The dialogue may have one or more "Continue" prompts before the
    // route options appear — we handle both.
    // =========================================================================
    private void handleSelectRoute() {
        if (!Rs2Dialogue.isInDialogue()) {
            log.warn("Dialogue closed unexpectedly, returning to START_TREK");
            setState(TempleTrekkingState.START_TREK);
            return;
        }

        // Advance any "click to continue" prompts first
        if (Rs2Dialogue.hasContinue()) {
            Rs2Dialogue.clickContinue();
            sleep(400, 700); // Wait for the next dialogue widget to render
            return;
        }

        // Now look for the route selection options
        if (Rs2Dialogue.hasSelectAnOption()) {
            // Try clicking "Route 1" — partial match handles "Route 1 (Easy path)" etc.
            boolean clicked = Rs2Dialogue.clickOption("Route 1");

            if (!clicked) {
                log.warn("Could not find 'Route 1' option. Visible options: {}",
                        Rs2Dialogue.getDialogueOptions());
                return; // Retry next tick
            }

            log.info("Selected Route 1, waiting for trek to begin...");
            // Wait until dialogue closes (trek instance starts loading)
            sleepUntil(() -> !Rs2Dialogue.isInDialogue(), 8000);
            bridgeMaterialsUsed = 0; // Reset bridge counter for new trek
            setState(TempleTrekkingState.TREKKING);
        }
    }

    // =========================================================================
    // STATE: TREKKING
    //
    // We're between events — just watching for something to happen.
    // Checks every tick for:
    //   1. Trek completion (reward token in inventory)
    //   2. An event loading (enemies, puzzle objects appearing)
    // =========================================================================
    private void handleTrekking() {
        // Check if trek completed — reward token appears in inventory
        if (hasRewardToken()) {
            log.info("Reward token detected — trek complete!");
            setState(TempleTrekkingState.TREK_COMPLETE);
            return;
        }

        // Check if we've entered an event area by looking for event-specific objects or enemies.
        // The "Continue-trek" path object only appears inside event areas.
        // So if we can see it, an event has loaded and we need to determine which type.
        Rs2TileObjectModel continuePath = findObjectByName(OBJ_CONTINUE_PATH);
        if (continuePath != null) {
            log.info("Event area detected — running event detection");
            setState(TempleTrekkingState.DETECT_EVENT);
        }
        // Otherwise just wait — next tick will check again
    }

    // =========================================================================
    // STATE: DETECT_EVENT
    //
    // We're inside an event area. Figure out which type and route accordingly.
    //
    // Detection priority order:
    //   1. Broken bridge + dead trees → BRIDGE_CHOP_TREES
    //   2. Broken bridge + no dead trees → BRIDGE_KILL_ZOMBIES
    //   3. Swamp tree with Cut-vine option → RIVER_CROSSING
    //   4. Enemies present + evade path visible → EVADE_COMBAT
    //   5. Nothing recognisable → UNKNOWN_EVENT
    // =========================================================================
    private void handleDetectEvent() {
        // Check for broken bridge first — most reliable signal
        boolean brokenBridgePresent = findObjectByName(OBJ_BROKEN_BRIDGE) != null
                || findObjectByName(OBJ_PARTIALLY_BROKEN) != null
                || findObjectByName(OBJ_SLIGHTLY_BROKEN) != null;

        if (brokenBridgePresent) {
            // Distinguish tree variant from lumberjack variant
            boolean deadTreesPresent = findObjectByName(OBJ_DEAD_TREE) != null;

            if (deadTreesPresent && config.doBridgeTrees()) {
                log.info("Detected: Bridge event (dead trees)");
                bridgeMaterialsUsed = 0;
                setState(TempleTrekkingState.BRIDGE_CHOP_TREES);
            } else if (!deadTreesPresent && config.doBridgeLumberjacks()) {
                log.info("Detected: Bridge event (undead lumberjacks)");
                bridgeMaterialsUsed = 0;
                setState(TempleTrekkingState.BRIDGE_KILL_ZOMBIES);
            } else {
                // Bridge event but config says skip — treat as unknown for now
                // Future: add "evade puzzle" logic here
                log.info("Bridge event detected but config set to skip, treating as unknown");
                setState(TempleTrekkingState.UNKNOWN_EVENT);
            }
            return;
        }

        // Check for river crossing — swamp tree with Cut-vine option
        if (findObjectByName(OBJ_SWAMP_TREE) != null && config.doRiverCrossing()) {
            log.info("Detected: River crossing event");
            setState(TempleTrekkingState.RIVER_CROSSING);
            return;
        }

        // Check for combat event — look for the evade path
        // On Route 1, all combat events have an "Evade-event" path option
        Rs2TileObjectModel evadePath = findObjectWithAction("Evade-event");
        if (evadePath != null) {
            log.info("Detected: Combat event (evade path visible)");
            setState(TempleTrekkingState.EVADE_COMBAT);
            return;
        }

        // Could be the Abidor Crank friendly event (free heal) — just continue
        // Abidor Crank has no puzzle or combat, just walk to the continue path
        log.info("No specific event detected — treating as friendly/unknown, continuing trek");
        setState(TempleTrekkingState.CONTINUE_TREK);
    }

    // =========================================================================
    // STATE: EVADE_COMBAT
    //
    // Find and click the "Evade-event" path option to skip the combat.
    // On Route 1 this is always available for all combat events.
    // =========================================================================
    private void handleEvadeCombat() {
        Rs2TileObjectModel evadePath = findObjectWithAction("Evade-event");

        if (evadePath == null) {
            log.warn("Evade path not found, waiting...");
            return; // Retry next tick
        }

        log.info("Clicking Evade-event path...");
        evadePath.click("Evade-event");

        // Wait until the event area unloads (evade path disappears)
        sleepUntil(() -> findObjectWithAction("Evade-event") == null, 10000);
        setState(TempleTrekkingState.TREKKING);
    }

    // =========================================================================
    // STATE: BRIDGE_CHOP_TREES
    //
    // Steps:
    //   1. Chop all 3 dead trees (get 3 logs)
    //   2. Use logs on bridge to repair it (3 logs needed)
    //      - Each log changes: Broken → Partially broken → Slightly broken → Fixed
    //   3. Cross the fixed bridge
    //   4. Transition to CONTINUE_TREK
    // =========================================================================
    private void handleBridgeChopTrees() {
        // --- Step 1: Chop trees until we have 3 logs ---
        if (!Rs2Inventory.contains(ITEM_LOG) || Rs2Inventory.count(ITEM_LOG) < 3) {
            Rs2TileObjectModel deadTree = findObjectByName(OBJ_DEAD_TREE);

            if (deadTree != null) {
                log.info("Chopping dead tree... (have {} logs)", Rs2Inventory.count(ITEM_LOG));
                deadTree.click("Chop down");
                // Wait until our animation starts (chopping) or stops (tree gone)
                sleepUntil(() -> Rs2Player.isAnimating(), 3000);
                // Then wait until we stop animating (tree chopped)
                sleepUntil(() -> !Rs2Player.isAnimating(), 10000);
            } else if (Rs2Inventory.count(ITEM_LOG) == 0) {
                // No tree found and no logs — shouldn't happen, but guard against it
                log.warn("No dead tree found and no logs in inventory during bridge event");
                sleep(1000);
            }
            // If we have some logs but still need more, loop back next tick to find next tree
            return;
        }

        // --- Step 2: Use logs to repair the bridge ---
        // Find whichever form of broken bridge is currently present
        Rs2TileObjectModel bridge = getBrokenBridge();

        if (bridge == null) {
            // Bridge is now fixed — move to crossing
            Rs2TileObjectModel fixedBridge = findObjectByName(OBJ_FIXED_BRIDGE);
            if (fixedBridge != null) {
                log.info("Bridge fixed! Crossing...");
                fixedBridge.click("Cross");
                sleepUntil(() -> findObjectByName(OBJ_FIXED_BRIDGE) == null, 8000);
                setState(TempleTrekkingState.CONTINUE_TREK);
            }
            return;
        }

        // Use a log on the broken bridge
        // Rs2Inventory.use() selects the item (highlights it), then we click the bridge
        log.info("Using log on bridge... (attempt {})", bridgeMaterialsUsed + 1);
        Rs2Inventory.use(ITEM_LOG);   // Selects the log in inventory
        sleep(300, 500);              // Short delay for selection to register
        bridge.click();               // Click the bridge (triggers "use log on bridge")

        // Wait for the dialogue that asks "Attempt to fix the bridge?"
        boolean dialogueAppeared = sleepUntil(Rs2Dialogue::hasSelectAnOption, 4000);
        if (dialogueAppeared) {
            Rs2Dialogue.clickOption("Yes");
            sleep(600, 900);
        }

        bridgeMaterialsUsed++;

        // After 3 logs the bridge is fully fixed
        // Wait a moment for the bridge object to update
        sleep(800, 1200);
    }

    // =========================================================================
    // STATE: BRIDGE_KILL_ZOMBIES
    //
    // Steps:
    //   1. Kill Undead Lumberjacks as they spawn from the river
    //   2. Loot their planks (need 3)
    //   3. Use planks on bridge to repair (same 3-step process as logs)
    //   4. Cross the fixed bridge
    // =========================================================================
    private void handleBridgeKillZombies() {
        // --- Step 1 & 2: Kill lumberjacks and loot planks ---
        if (Rs2Inventory.count(ITEM_PLANK) < 3) {

            // Look for an Undead Lumberjack to attack
            Rs2NpcModel lumberjack = Microbot.getRs2NpcCache().query()
                    .withName("Undead Lumberjack")
                    .nearest();

            if (lumberjack != null && !Rs2Player.isInCombat()) {
                log.info("Attacking Undead Lumberjack... (have {} planks)", Rs2Inventory.count(ITEM_PLANK));
                lumberjack.click("Attack");
                // Wait to enter combat
                sleepUntil(() -> Rs2Player.isInCombat(), 3000);
                // Wait until combat ends (lumberjack dies)
                sleepUntil(() -> !Rs2Player.isInCombat(), 30000);
                sleep(600); // Brief pause for loot to appear on ground
            } else if (lumberjack == null) {
                // No lumberjack spawned yet — wait for the next wave
                log.debug("Waiting for Undead Lumberjack to spawn...");
                sleep(1000);
            }
            // Don't return here — fall through to check if planks appeared on ground
            // (loot is picked up automatically via auto-loot or we need to click it)
            return;
        }

        // --- Step 3: Repair the bridge with planks ---
        Rs2TileObjectModel bridge = getBrokenBridge();

        if (bridge == null) {
            // Bridge is fixed
            Rs2TileObjectModel fixedBridge = findObjectByName(OBJ_FIXED_BRIDGE);
            if (fixedBridge != null) {
                log.info("Bridge fixed! Crossing...");
                fixedBridge.click("Cross");
                sleepUntil(() -> findObjectByName(OBJ_FIXED_BRIDGE) == null, 8000);
                setState(TempleTrekkingState.CONTINUE_TREK);
            }
            return;
        }

        log.info("Using plank on bridge... (attempt {})", bridgeMaterialsUsed + 1);
        Rs2Inventory.use(ITEM_PLANK);
        sleep(300, 500);
        bridge.click();

        boolean dialogueAppeared = sleepUntil(Rs2Dialogue::hasSelectAnOption, 4000);
        if (dialogueAppeared) {
            Rs2Dialogue.clickOption("Yes");
            sleep(600, 900);
        }

        bridgeMaterialsUsed++;
        sleep(800, 1200);
    }

    // =========================================================================
    // STATE: RIVER_CROSSING
    //
    // Steps:
    //   1. Cut vine from Swamp tree 3 times (get 3 Short vines id:7778)
    //   2. Combine 2 Short vines → use one on another (opens combine dialogue)
    //   3. Combine result with 3rd short vine → Long vine (id:7777)
    //   4. Use Long vine on Swamp tree branch
    //   5. Click "Swing-from" Swamp tree branch
    // =========================================================================
    private void handleRiverCrossing() {
        // --- Step 1: Get 3 short vines ---
        int vineCount = Rs2Inventory.count(ITEM_SHORT_VINE);
        if (vineCount < 3) {
            Rs2TileObjectModel swampTree = findObjectByName(OBJ_SWAMP_TREE);

            if (swampTree == null) {
                log.warn("No Swamp tree found for vine cutting");
                sleep(1000);
                return;
            }

            log.info("Cutting vine... (have {}/3)", vineCount);
            swampTree.click("Cut-vine");
            // Wait for the vine to appear in inventory
            int currentCount = vineCount;
            sleepUntil(() -> Rs2Inventory.count(ITEM_SHORT_VINE) > currentCount, 5000);
            return; // Come back next tick — may need to cut again
        }

        // --- Step 2 & 3: Combine short vines into long vine ---
        if (!Rs2Inventory.contains(ITEM_LONG_VINE)) {
            log.info("Combining short vines into long vine...");

            // combine(primaryId, secondaryId) selects the first item and clicks the second —
            // the correct way to combine two items in inventory by their IDs.
            Rs2Inventory.combine(ITEM_SHORT_VINE, ITEM_SHORT_VINE);

            // Wait for long vine to appear (two uses of combine produce long vine from 3 short vines)
            sleepUntil(() -> Rs2Inventory.contains(ITEM_LONG_VINE), 5000);
            return;
        }

        // --- Step 4: Use long vine on tree branch ---
        Rs2TileObjectModel treeBranch = findObjectByName(OBJ_SWAMP_TREE_BRANCH);

        if (treeBranch == null) {
            log.warn("No Swamp tree branch found");
            sleep(1000);
            return;
        }

        // Check if we need to throw the vine (before it's attached)
        // or swing from it (after it's attached)
        String[] actions = treeBranch.getObjectComposition() != null
                ? treeBranch.getObjectComposition().getActions()
                : new String[]{};

        boolean hasSwing = false;
        boolean hasThrow = false;
        for (String action : actions) {
            if (action == null) continue;
            if (action.equalsIgnoreCase("Swing-from")) hasSwing = true;
            if (action.equalsIgnoreCase("Throw-to") || action.equalsIgnoreCase("Use")) hasThrow = true;
        }

        if (!hasSwing) {
            // Need to throw/attach the vine first
            log.info("Using long vine on tree branch...");
            Rs2Inventory.use(ITEM_LONG_VINE); // Select long vine
            sleep(300, 500);
            treeBranch.click();               // Use on branch
            sleepUntil(() -> {
                // Wait until the branch gains a "Swing-from" option
                Rs2TileObjectModel branch = findObjectByName(OBJ_SWAMP_TREE_BRANCH);
                if (branch == null) return false;
                String[] acts = branch.getObjectComposition() != null
                        ? branch.getObjectComposition().getActions() : new String[]{};
                for (String a : acts) {
                    if ("Swing-from".equalsIgnoreCase(a)) return true;
                }
                return false;
            }, 5000);
        } else {
            // --- Step 5: Swing across ---
            log.info("Swinging from tree branch across river...");
            treeBranch.click("Swing-from");
            // Wait until we've crossed (branch disappears from scene)
            sleepUntil(() -> findObjectByName(OBJ_SWAMP_TREE_BRANCH) == null, 8000);
            setState(TempleTrekkingState.CONTINUE_TREK);
        }
    }

    // =========================================================================
    // STATE: CONTINUE_TREK
    //
    // Click the blue "Continue-trek" path stones to advance.
    // =========================================================================
    private void handleContinueTrek() {
        Rs2TileObjectModel continuePath = findObjectWithAction("Continue-trek");

        if (continuePath == null) {
            // Path stones not visible yet, or already clicked — check if trek is done
            if (hasRewardToken()) {
                setState(TempleTrekkingState.TREK_COMPLETE);
            } else {
                // Just went through an event, back to trekking
                setState(TempleTrekkingState.TREKKING);
            }
            return;
        }

        log.info("Clicking Continue-trek path...");
        continuePath.click("Continue-trek");

        // Wait for the event area to unload (path stones disappear)
        sleepUntil(() -> findObjectWithAction("Continue-trek") == null, 10000);
        setState(TempleTrekkingState.TREKKING);
    }

    // =========================================================================
    // STATE: TREK_COMPLETE
    //
    // Trek finished — increment counter, leave token in inventory, restart.
    // =========================================================================
    private void handleTrekComplete() {
        trekCount++;
        log.info("Trek {} complete! Token in inventory. Starting next trek...", trekCount);
        sleep(1500, 2500); // Brief pause before restarting
        setState(TempleTrekkingState.START_TREK);
    }

    // =========================================================================
    // STATE: UNKNOWN_EVENT
    //
    // Unrecognised situation — log and try to recover by re-detecting.
    // =========================================================================
    private void handleUnknownEvent() {
        log.warn("Unknown event state. Waiting 3 seconds before retrying detection...");
        sleep(3000);
        setState(TempleTrekkingState.DETECT_EVENT);
    }

    // =========================================================================
    // UTILITY METHODS
    // =========================================================================

    /**
     * Returns the current form of broken bridge present in the scene,
     * checking all three progressive states.
     * Returns null if no broken bridge exists (i.e. it's fixed or not a bridge event).
     */
    private Rs2TileObjectModel getBrokenBridge() {
        Rs2TileObjectModel bridge = findObjectByName(OBJ_BROKEN_BRIDGE);
        if (bridge != null) return bridge;
        bridge = findObjectByName(OBJ_PARTIALLY_BROKEN);
        if (bridge != null) return bridge;
        return findObjectByName(OBJ_SLIGHTLY_BROKEN);
    }

    /**
     * Finds the nearest tile object matching a given name (case-insensitive).
     * Uses the new Rs2TileObjectCache — the non-deprecated way to find objects.
     *
     * @param name The exact name of the object to find
     * @return The nearest matching object, or null if not found
     */
    private Rs2TileObjectModel findObjectByName(String name) {
        return Microbot.getRs2TileObjectCache().query()
                .withName(name)
                .nearest();
    }

    /**
     * Finds the nearest tile object that has a specific right-click action available.
     * Useful for finding path stones by their "Continue-trek" or "Evade-event" action
     * since both path types share the same object name "Path".
     *
     * @param action The action string to search for (e.g., "Continue-trek")
     * @return The nearest matching object, or null if not found
     */
    private Rs2TileObjectModel findObjectWithAction(String action) {
        return Microbot.getRs2TileObjectCache().query()
                .where(obj -> {
                    // Get the object's composition to check its actions array
                    var comp = obj.getObjectComposition();
                    if (comp == null) return false;
                    String[] actions = comp.getActions();
                    if (actions == null) return false;
                    for (String a : actions) {
                        if (action.equalsIgnoreCase(a)) return true;
                    }
                    return false;
                })
                .nearest();
    }

    /**
     * Checks whether a reward token is in the player's inventory.
     * Checks by name since there are blue/yellow/red variants.
     */
    private boolean hasRewardToken() {
        return Rs2Inventory.contains("Reward token");
    }

    /**
     * Central state transition — all state changes go through here.
     * Having one place for transitions makes debugging much easier.
     */
    private void setState(TempleTrekkingState newState) {
        if (currentState != newState) {
            log.info("State: {} → {}", currentState, newState);
            currentState = newState;
        }
    }

    // =========================================================================
    // SHUTDOWN
    // =========================================================================

    @Override
    public void shutdown() {
        super.shutdown(); // ALWAYS call this first — cancels the executor
        currentState = TempleTrekkingState.IDLE;
        log.info("Temple Trekking script shut down. Total treks this session: {}", trekCount);
    }
}
