package net.runelite.client.plugins.microbot.templetrekking;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

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

    // Bridge event objects — verified IDs
    private static final int OBJ_DEAD_TREE             = 1365;  // Chop for logs
    private static final int OBJ_BROKEN_BRIDGE         = 13834; // Stage 1 — use 1st log
    private static final int OBJ_PARTIALLY_BROKEN      = 13835; // Stage 2 — use 2nd log
    private static final int OBJ_SLIGHTLY_BROKEN       = 13836; // Stage 3 — use 3rd log
    private static final int OBJ_FIXED_BRIDGE          = 13837; // Cross this to finish

    // River event objects — IDs verified in-game
    private static final int OBJ_SWAMP_TREE               = 13847; // Cut-vine to get Short vines
    private static final int OBJ_SWAMP_TREE_BRANCH_BARE   = 13845; // Before long vine is attached
    private static final int OBJ_SWAMP_TREE_BRANCH_VINE   = 13846; // After long vine attached — Swing-from

    // =========================================================================
    // Start locations — approximate WorldPoints for walking to start area.
    // NOTE: Verify these in-game with RuneLite's Tile Location plugin.
    // =========================================================================
    private static final WorldPoint RAMBLE_START_LOCATION = new WorldPoint(3408, 3479, 0);
    private static final WorldPoint TREK_START_LOCATION   = new WorldPoint(3494, 3215, 0);

    /**
     * Paterdomus temple area — the region around the start NPCs.
     * We use this to detect whether we're at the start area or inside a trek instance.
     * A WorldArea is a rectangle defined by (x, y, width, height, plane).
     * If the player is OUTSIDE this area after selecting a route, they're in the trek.
     */
    private static final WorldArea PATERDOMUS_START_AREA = new WorldArea(3400, 3460, 30, 30, 0);

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

    /**
     * Set to true once we've successfully selected a route.
     * Prevents the bot from trying to start a new trek while one is already running.
     * Reset to false when TREK_COMPLETE is reached.
     */
    private boolean trekInProgress = false;

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
    // Find an easy escort NPC and right-click "Escort" to start.
    // Guards against trying to start while a trek is already in progress.
    // =========================================================================
    private void handleStartTrek() {
        // Safety guard: if we already started a trek this session, go straight
        // to TREKKING rather than trying to talk to the NPC again.
        // This handles the case where the bot restarts but the trek is still running.
        if (trekInProgress) {
            log.info("Trek already in progress, resuming TREKKING state...");
            setState(TempleTrekkingState.TREKKING);
            return;
        }

        // Pick which NPC IDs and start location to use based on direction config
        int[] npcIds = config.trekDirection() == TempleTrekkingConfig.TrekDirection.BURGH_DE_ROTT_RAMBLE
                ? new int[]{NPC_ADVENTURER, NPC_MAGE}
                : new int[]{NPC_FYIONA_FRAY, NPC_DALCIAN_FANG};

        WorldPoint startLocation = config.trekDirection() == TempleTrekkingConfig.TrekDirection.BURGH_DE_ROTT_RAMBLE
                ? RAMBLE_START_LOCATION
                : TREK_START_LOCATION;

        Rs2NpcModel escortNpc = Microbot.getRs2NpcCache().query()
                .withIds(npcIds)
                .nearest();

        if (escortNpc == null) {
            log.info("Escort NPC not found nearby, walking to start area...");
            Rs2Walker.walkTo(startLocation);
            return;
        }

        log.info("Found escort NPC: {} (id={}). Clicking Escort...", escortNpc.getName(), escortNpc.getId());
        boolean clicked = escortNpc.click("Escort");

        if (clicked) {
            // Human-like delay before the dialogue opens
            sleep(600, 1200);
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
    // After clicking Escort, the game shows:
    //   1. A standard dialogue: "Which route should we take?" — click Continue
    //   2. A custom MAP widget opens with route options (not a standard dialogue)
    //      The map has clickable regions with a "Select" action for each route.
    //      We click "Route One" (or whatever the widget text is) using Rs2Widget.
    //   3. After selecting, the map closes and two path stones appear:
    //      - "Continue-trek Path" (reopens map)
    //      - "Return-to-Paterdomus Path" (abandons trek)
    //      We ignore the map entirely and click "Continue-trek Path" directly.
    //   4. That transitions us into the trek instance proper.
    // =========================================================================
    private void handleSelectRoute() {

        // --- Step 1: Drain ALL standard dialogues before the map opens ---
        // Sequence: dialogue 1 → teleport to new location → dialogue 2 → dialogue 3 → map.
        // The teleport causes a brief moment where isInDialogue() returns false even
        // though more dialogues are coming. We handle this by:
        //   a) After each Continue click, waiting up to 3s for the next dialogue OR map
        //   b) If neither appears within 3s, THEN we check for map/path stones
        if (Rs2Dialogue.isInDialogue()) {
            if (Rs2Dialogue.hasContinue()) {
                sleep(400, 800);
                Rs2Dialogue.clickContinue();
                // Wait for: next dialogue box, OR map widget, OR teleport + new dialogue
                // 3s covers the teleport animation + new dialogue rendering time
                sleepUntil(() -> Rs2Dialogue.isInDialogue() || Rs2Widget.isWidgetVisible(329, 21), 3000);
            } else {
                // Dialogue open but no Continue yet — mid-render, wait a tick
                sleep(300, 500);
            }
            return;
        }

        // If we just came out of a dialogue and the map isn't visible yet,
        // give it a moment — we may be mid-teleport with more dialogues incoming.
        if (!Rs2Widget.isWidgetVisible(329, 21) && !trekInProgress) {
            // Wait briefly to see if another dialogue or the map appears
            boolean somethingAppeared = sleepUntil(
                () -> Rs2Dialogue.isInDialogue() || Rs2Widget.isWidgetVisible(329, 21),
                2500
            );
            if (!somethingAppeared) {
                // Nothing appeared — fall through to path stone check below
                log.debug("SELECT_ROUTE: No dialogue or map after 2.5s, checking for path stones...");
            } else {
                return; // Something appeared, handle it next tick
            }
        }

        // --- Step 2: Handle the route map widget ---
        // Click Route 1 exactly once, then block here until the map closes.
        // We do NOT return after clicking — we stay in this call until the map
        // is gone so the scheduler cannot re-enter and click a second time.
        if (Rs2Widget.isWidgetVisible(329, 21)) {
            log.info("Route map open, clicking Route 1 (329, 21)...");
            sleep(600, 1200);

            boolean clicked = Rs2Widget.clickWidget(329, 21);
            if (!clicked) {
                log.warn("Failed to click Route 1 widget, will retry next tick...");
                return;
            }

            // Block until the map widget is gone (up to 10s).
            // Keeping this in one call prevents the scheduler re-entering and clicking again.
            log.info("Route 1 clicked, waiting for map to close...");
            sleepUntil(() -> !Rs2Widget.isWidgetVisible(329, 21), 10000);
            sleep(500, 900);
            return;
        }

        // --- Step 3: Map is gone — look for the Continue-trek path stone ---
        // After the map closes (either by selecting a route OR by dismissing it),
        // two tile objects appear. We want "Continue-trek Path".
        // Clicking it either starts the trek leg directly, or reopens the map.
        // Either way, once we've clicked it we mark the trek as in progress.
        Rs2TileObjectModel continuePath = findObjectWithAction("Continue-trek");
        if (continuePath != null) {
            log.info("Found Continue-trek path stone, clicking...");
            sleep(500, 1000);
            continuePath.click("Continue-trek");

            // Mark trek in progress immediately
            trekInProgress = true;
            bridgeMaterialsUsed = 0;

            // Wait for the path stones to disappear (trek instance loading)
            sleepUntil(() -> findObjectWithAction("Continue-trek") == null, 10000);
            sleep(1000, 2000); // Let the instance finish loading

            log.info("Trek started. Transitioning to TREKKING.");
            setState(TempleTrekkingState.TREKKING);
            return;
        }

        // --- Fallback: nothing visible yet, wait ---
        // Could be mid-animation or mid-load
        if (trekInProgress) {
            log.info("Trek already in progress, transitioning to TREKKING.");
            setState(TempleTrekkingState.TREKKING);
        } else {
            log.debug("SELECT_ROUTE: waiting for map or path stones to appear...");
        }
    }

    // =========================================================================
    // STATE: TREKKING
    //
    // Walking between events. We trigger event detection only when we are
    // confident an event area has fully loaded — indicated by the presence of
    // a puzzle object (bridge, swamp tree) OR an Evade-event path option.
    // We deliberately do NOT trigger on the Continue-trek path alone, because
    // that stone exists in every event area including ones that aren't loaded yet.
    // =========================================================================
    private void handleTrekking() {
        if (hasRewardToken()) {
            log.info("Reward token detected — trek complete!");
            trekInProgress = false;
            setState(TempleTrekkingState.TREK_COMPLETE);
            return;
        }

        // Handle the pseudo-map / inter-event dialogue screen
        if (Rs2Dialogue.isInDialogue()) {
            if (Rs2Dialogue.hasSelectAnOption()) {
                boolean continued = Rs2Dialogue.clickOption("Continue on the trek");
                if (continued) {
                    log.info("Clicked 'Continue on the trek' from map screen");
                    sleep(1000, 2000);
                    return;
                }
                log.info("Unknown option dialogue: {}", Rs2Dialogue.getDialogueOptions());
            } else if (Rs2Dialogue.hasContinue()) {
                sleep(400, 800);
                Rs2Dialogue.clickContinue();
                sleep(500, 900);
            }
            return;
        }

        // Only enter DETECT_EVENT when a known puzzle object is visible.
        // Log exactly what triggered detection so we can verify in the log.
        boolean bridge      = findObjectById(OBJ_BROKEN_BRIDGE) != null
                           || findObjectById(OBJ_PARTIALLY_BROKEN) != null
                           || findObjectById(OBJ_SLIGHTLY_BROKEN) != null;
        boolean swampTree   = findObjectById(OBJ_SWAMP_TREE) != null;
        boolean evadePath   = findObjectWithAction("Evade-event") != null;

        if (bridge || swampTree || evadePath) {
            log.info("Event detected — bridge={} swampTree={} evade={}", bridge, swampTree, evadePath);
            setState(TempleTrekkingState.DETECT_EVENT);
        }
        // Otherwise idle — next tick will check again
    }

    // =========================================================================
    // STATE: DETECT_EVENT
    //
    // Identify which event has loaded and route to the correct handler.
    //
    // Detection order (per your design):
    //   1. Evade-event path visible → EVADE_COMBAT (combat events only have this)
    //   2. Broken bridge present → check for dead tree
    //      a. Dead tree present → BRIDGE_CHOP_TREES
    //      b. No dead tree → BRIDGE_KILL_ZOMBIES (lumberjacks)
    //   3. Swamp tree present → RIVER_CROSSING
    //   4. None of the above → wait and re-detect (never fall through to continue)
    // =========================================================================
    private void handleDetectEvent() {
        logNearbyObjects();

        // 1. Combat events: only they have the Evade-event path option
        if (findObjectWithAction("Evade-event") != null) {
            log.info("Detected: Combat event (Evade-event path visible)");
            setState(TempleTrekkingState.EVADE_COMBAT);
            return;
        }

        // 2. Bridge events: broken bridge is present
        boolean brokenBridgePresent =
                findObjectById(OBJ_BROKEN_BRIDGE) != null ||
                findObjectById(OBJ_PARTIALLY_BROKEN) != null ||
                findObjectById(OBJ_SLIGHTLY_BROKEN) != null;

        if (brokenBridgePresent) {
            boolean deadTreePresent = findObjectById(OBJ_DEAD_TREE) != null;
            if (deadTreePresent) {
                log.info("Detected: Bridge event — dead trees");
                bridgeMaterialsUsed = 0;
                setState(TempleTrekkingState.BRIDGE_CHOP_TREES);
            } else {
                log.info("Detected: Bridge event — undead lumberjacks");
                bridgeMaterialsUsed = 0;
                setState(TempleTrekkingState.BRIDGE_KILL_ZOMBIES);
            }
            return;
        }

        // 3. River crossing: swamp tree present
        if (findObjectById(OBJ_SWAMP_TREE) != null) {
            log.info("Detected: River crossing event");
            setState(TempleTrekkingState.RIVER_CROSSING);
            return;
        }

        // Nothing matched yet — the area may still be loading. Wait and retry.
        // NEVER fall through to CONTINUE_TREK from here.
        log.debug("DETECT_EVENT: no puzzle objects found yet, waiting for area to load...");
        sleep(500);
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
            return;
        }

        log.info("Clicking Evade-event path...");
        evadePath.click("Evade-event");
        sleepUntil(() -> findObjectWithAction("Evade-event") == null, 10000);
        setState(TempleTrekkingState.CONTINUE_TREK);
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
        // --- Step 1: Chop all 3 dead trees (id:1365) to get 3 logs ---
        if (Rs2Inventory.count(ITEM_LOG) < 3) {
            Rs2TileObjectModel deadTree = findObjectById(OBJ_DEAD_TREE); // id:1365

            if (deadTree != null) {
                log.info("Chopping dead tree (id:1365)... (have {} logs)", Rs2Inventory.count(ITEM_LOG));
                deadTree.click("Chop down");
                sleepUntil(Rs2Player::isAnimating, 3000);
                sleepUntil(() -> !Rs2Player.isAnimating(), 10000);
                sleep(300, 600); // Brief pause for log to land in inventory
            } else {
                log.warn("No dead tree found — all chopped? Have {} logs", Rs2Inventory.count(ITEM_LOG));
                sleep(1000);
            }
            return;
        }

        // --- Step 2: Use each log on the current bridge stage ---
        // Stages: 13834 (broken) → 13835 (partially) → 13836 (slightly) → 13837 (fixed)
        Rs2TileObjectModel bridge = getBrokenBridge();

        if (bridge == null) {
            // No broken bridge found — check if it's fixed and cross
            Rs2TileObjectModel fixedBridge = findObjectById(OBJ_FIXED_BRIDGE); // id:13837
            if (fixedBridge != null) {
                log.info("Bridge fixed (id:13837), crossing...");
                fixedBridge.click("Cross");
                sleepUntil(() -> findObjectById(OBJ_FIXED_BRIDGE) == null, 8000);
                setState(TempleTrekkingState.CONTINUE_TREK);
            } else {
                log.warn("No bridge found at all, waiting...");
                sleep(1000);
            }
            return;
        }

        log.info("Using log on bridge (id:{})... (repair {})", bridge.getId(), bridgeMaterialsUsed + 1);
        Rs2Inventory.use(ITEM_LOG);
        sleep(300, 500);
        bridge.click();

        // Confirm the repair prompt if it appears
        boolean dialogueAppeared = sleepUntil(Rs2Dialogue::hasSelectAnOption, 4000);
        if (dialogueAppeared) {
            Rs2Dialogue.clickOption("Yes");
            sleep(600, 900);
        }

        bridgeMaterialsUsed++;
        // Wait for the bridge object to update to the next stage (or become fixed)
        sleepUntil(() -> getBrokenBridge() != null || findObjectById(OBJ_FIXED_BRIDGE) != null, 3000);
        sleep(400, 700);
    }

    // =========================================================================
    // STATE: BRIDGE_KILL_ZOMBIES
    //
    // Steps:
    //   1. Kill Undead Lumberjacks (id:5655) until 3 planks (id:960) in inventory
    //   2. Use Plank → Broken bridge (13834) → Partially (13835) → Slightly (13836)
    //   3. Cross Fixed bridge (13837)
    // =========================================================================
    private void handleBridgeKillZombies() {
        // --- Step 1: Kill lumberjacks until we have 3 planks ---
        if (Rs2Inventory.count(ITEM_PLANK) < 3) {

            Rs2NpcModel lumberjack = Microbot.getRs2NpcCache().query()
                    .withId(5655) // Undead Lumberjack — verified ID
                    .nearest();

            if (lumberjack != null && !Rs2Player.isInCombat()) {
                int planksBeforeKill = Rs2Inventory.count(ITEM_PLANK);
                log.info("Attacking Undead Lumberjack (id:5655)... (have {} planks)", planksBeforeKill);
                lumberjack.click("Attack");
                sleepUntil(Rs2Player::isInCombat, 3000);
                sleepUntil(() -> !Rs2Player.isInCombat(), 30000);
                sleep(600, 1000); // Brief pause for loot to land on ground

                // Plank is on the ground — click it to pick up
                log.info("Lumberjack dead, picking up plank...");
                net.runelite.client.plugins.microbot.util.grounditem.Rs2GroundItem.loot(ITEM_PLANK);
                // Wait until the plank count increases (confirms pickup)
                sleepUntil(() -> Rs2Inventory.count(ITEM_PLANK) > planksBeforeKill, 4000);
                log.info("Planks now: {}/3", Rs2Inventory.count(ITEM_PLANK));
            } else if (lumberjack == null) {
                log.debug("Waiting for Undead Lumberjack (id:5655) to spawn... (have {} planks)",
                        Rs2Inventory.count(ITEM_PLANK));
                sleep(1000);
            }
            // Still in combat — wait it out
            return;
        }

        // --- Step 3: Use each plank on the current bridge stage ---
        Rs2TileObjectModel bridge = getBrokenBridge();

        if (bridge == null) {
            Rs2TileObjectModel fixedBridge = findObjectById(OBJ_FIXED_BRIDGE); // id:13837
            if (fixedBridge != null) {
                log.info("Bridge fixed (id:13837), crossing...");
                fixedBridge.click("Cross");
                sleepUntil(() -> findObjectById(OBJ_FIXED_BRIDGE) == null, 8000);
                setState(TempleTrekkingState.CONTINUE_TREK);
            } else {
                log.warn("No bridge found at all, waiting...");
                sleep(1000);
            }
            return;
        }

        log.info("Using plank on bridge (id:{})... (repair {})", bridge.getId(), bridgeMaterialsUsed + 1);
        Rs2Inventory.use(ITEM_PLANK);
        sleep(300, 500);
        bridge.click();

        boolean dialogueAppeared = sleepUntil(Rs2Dialogue::hasSelectAnOption, 4000);
        if (dialogueAppeared) {
            Rs2Dialogue.clickOption("Yes");
            sleep(600, 900);
        }

        bridgeMaterialsUsed++;
        sleepUntil(() -> getBrokenBridge() != null || findObjectById(OBJ_FIXED_BRIDGE) != null, 3000);
        sleep(400, 700);
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
            Rs2TileObjectModel swampTree = findObjectById(OBJ_SWAMP_TREE); // id:13847

            if (swampTree == null) {
                log.warn("No Swamp tree (id:13847) found for vine cutting");
                sleep(1000);
                return;
            }

            log.info("Cutting vine... (have {}/3)", vineCount);
            swampTree.click("Cut-vine");
            int currentCount = vineCount;
            sleepUntil(() -> Rs2Inventory.count(ITEM_SHORT_VINE) > currentCount, 5000);
            return;
        }

        // --- Step 2 & 3: Combine short vines into long vine ---
        if (!Rs2Inventory.contains(ITEM_LONG_VINE)) {
            log.info("Combining short vines into long vine...");
            Rs2Inventory.combine(ITEM_SHORT_VINE, ITEM_SHORT_VINE);
            sleepUntil(() -> Rs2Inventory.contains(ITEM_LONG_VINE), 5000);
            return;
        }

        // --- Step 4: Use long vine on bare branch (id:13845) ---
        // Once the vine is attached, the branch becomes id:13846 which has Swing-from.
        Rs2TileObjectModel bareBranch = findObjectById(OBJ_SWAMP_TREE_BRANCH_BARE); // id:13845
        if (bareBranch != null) {
            log.info("Using long vine on tree branch (id:13845)...");
            Rs2Inventory.use(ITEM_LONG_VINE);
            sleep(300, 500);
            bareBranch.click();
            // Wait for the bare branch to be replaced by the vine branch (id:13846)
            sleepUntil(() -> findObjectById(OBJ_SWAMP_TREE_BRANCH_VINE) != null, 5000);
            return;
        }

        // --- Step 5: Swing across from vine branch (id:13846) ---
        Rs2TileObjectModel vineBranch = findObjectById(OBJ_SWAMP_TREE_BRANCH_VINE); // id:13846
        if (vineBranch != null) {
            log.info("Swinging from vine branch (id:13846)...");
            vineBranch.click("Swing-from");
            // Wait until the branch disappears (we've crossed)
            sleepUntil(() -> findObjectById(OBJ_SWAMP_TREE_BRANCH_VINE) == null, 8000);
            setState(TempleTrekkingState.CONTINUE_TREK);
            return;
        }

        log.warn("River crossing: no branch found (bare or vine), waiting...");
        sleep(1000);
    }

    // =========================================================================
    // STATE: CONTINUE_TREK
    //
    // Called by event handlers after they finish (crossed bridge, swung on vine,
    // etc.). The Continue-trek path stone is now reachable on the far side.
    // Click it to load the next event segment, then return to TREKKING.
    // =========================================================================
    private void handleContinueTrek() {
        if (hasRewardToken()) {
            setState(TempleTrekkingState.TREK_COMPLETE);
            return;
        }

        Rs2TileObjectModel continuePath = findObjectWithAction("Continue-trek");
        if (continuePath == null) {
            // Stone not visible yet — may be mid-transition, return to TREKKING
            // to wait for the next event or for the stone to appear
            log.debug("CONTINUE_TREK: no path stone visible, returning to TREKKING");
            setState(TempleTrekkingState.TREKKING);
            return;
        }

        log.info("Clicking Continue-trek path stone...");
        continuePath.click("Continue-trek");
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
        trekInProgress = false; // Allow START_TREK to find the NPC again
        log.info("Trek {} complete! Token in inventory. Starting next trek...", trekCount);
        sleep(2000, 4000); // Human-like pause before starting another
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
        Rs2TileObjectModel bridge = findObjectById(OBJ_BROKEN_BRIDGE);    // id:13834
        if (bridge != null) return bridge;
        bridge = findObjectById(OBJ_PARTIALLY_BROKEN);                    // id:13835
        if (bridge != null) return bridge;
        return findObjectById(OBJ_SLIGHTLY_BROKEN);                       // id:13836
    }

    /**
     * Finds the nearest tile object matching a given ID.
     * More reliable than name-based lookup in instanced areas.
     *
     * @param id The object ID to search for
     * @return The nearest matching object, or null if not found
     */
    private Rs2TileObjectModel findObjectById(int id) {
        return Microbot.getRs2TileObjectCache().query()
                .withId(id)
                .nearest();
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
     * Dumps all visible tile object names and their actions to the log.
     * Used to verify our OBJ_* name constants match what the game actually uses.
     * Safe to leave in permanently — only runs on state entry, not every tick.
     */
    private void logNearbyObjects() {
        Microbot.getClientThread().invoke(() -> {
            var objects = Microbot.getRs2TileObjectCache().query().toList();
            log.info("=== Nearby objects ({} total) ===", objects.size());
            objects.stream()
                .filter(obj -> obj.getObjectComposition() != null)
                .forEach(obj -> {
                    var comp = obj.getObjectComposition();
                    String name = comp.getName();
                    String[] actions = comp.getActions();
                    String actionStr = actions != null
                        ? String.join(", ", java.util.Arrays.stream(actions)
                            .filter(a -> a != null && !a.isEmpty())
                            .toArray(String[]::new))
                        : "none";
                    log.info("  Object: '{}' | Actions: [{}]", name, actionStr);
                });
            log.info("=== End object list ===");
            return null;
        });
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
