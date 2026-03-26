package net.runelite.client.plugins.microbot.templetrekking;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;
import net.runelite.api.coords.WorldPoint;

import java.awt.Rectangle;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

@Slf4j
public class TempleTrekkingScript extends Script {

    // =========================================================================
    // NPC IDs — verified in-game
    // =========================================================================

    private static final int NPC_FYIONA_FRAY  = 1567;
    private static final int NPC_DALCIAN_FANG = 1566;
    private static final int NPC_ADVENTURER   = 1577;
    private static final int NPC_MAGE         = 1578;

    // =========================================================================
    // Item IDs — verified in-game
    // =========================================================================

    private static final int ITEM_SHORT_VINE = 7778;
    private static final int ITEM_LONG_VINE  = 7777;
    private static final int ITEM_LOG        = 1511;
    private static final int ITEM_PLANK      = 960;

    // =========================================================================
    // Object IDs — verified in-game
    // =========================================================================

    // Path stones
    // OBJ_CONTINUE_PATH (13832) is visible for the ENTIRE duration of a skill event
    // (both before and after solving it). It must NEVER be used as an event trigger.
    // It is only clicked explicitly at the end of each skill event handler, after
    // all prerequisites (bridge fixed + crossed, river swung) are confirmed done.
    private static final int OBJ_EVADE_PATH    = 13831; // Combat events only
    private static final int OBJ_CONTINUE_PATH = 13832; // Skill events — click only after completion

    // Bridge event — DEAD TREE variant (verified in-game)
    private static final int OBJ_DEAD_TREE               = 1365;
    private static final int OBJ_LOG_BRIDGE_BROKEN        = 13834; // stage 0: use 1st log
    private static final int OBJ_LOG_BRIDGE_PARTIAL       = 13835; // stage 1: use 2nd log
    private static final int OBJ_LOG_BRIDGE_ALMOST        = 13836; // stage 2: use 3rd log
    private static final int OBJ_LOG_BRIDGE_FIXED         = 13837; // stage 3: cross

    // Bridge event — ZOMBIE (undead lumberjack) variant (verified in-game)
    private static final int OBJ_PLANK_BRIDGE_BROKEN      = 13834; // stage 0: use 1st plank  (same as log broken — kept separate for clarity)
    private static final int OBJ_PLANK_BRIDGE_PARTIAL     = 22533; // stage 1: use 2nd plank
    private static final int OBJ_PLANK_BRIDGE_ALMOST      = 22534; // stage 2: use 3rd plank
    private static final int OBJ_PLANK_BRIDGE_FIXED       = 22535; // stage 3: cross

    // River crossing event
    // The swamp tree changes ID as vines are cut (tracks remaining vines):
    private static final int OBJ_SWAMP_TREE_3    = 13847; // 3 vines remaining (fresh)
    private static final int OBJ_SWAMP_TREE_2    = 13848; // 2 vines remaining
    private static final int OBJ_SWAMP_TREE_1    = 13849; // 1 vine remaining
    private static final int OBJ_SWAMP_TREE_BRANCH_BARE = 13845; // before vine attached
    private static final int OBJ_SWAMP_TREE_BRANCH_VINE = 13846; // after vine attached — Swing-from

    // Reward tokens — one ID per trek direction
    private static final int TOKEN_BURGH   = 10944; // Burgh de Rott Ramble token
    private static final int TOKEN_TEMPLE  = 10936; // Temple Trekking token
    private static final int[] ALL_TOKEN_IDS = { TOKEN_BURGH, TOKEN_TEMPLE };

    // Skill tomes — all variants; action is Read (left-click)
    private static final int[] ALL_TOME_IDS = { 7779, 7782, 7785, 7788, 7791, 7794, 7797 };

    // Burgh de Rott region ID — used to detect whether the player ended the trek there
    private static final WorldPoint BURGH_DE_ROTT_CENTER = new WorldPoint(3495, 3212, 0);
    private static final int BURGH_DE_ROTT_REGION = 13618;

    // All escort NPCs across both directions — any one of these being visible
    // means we are at a valid start point and can begin a trek.
    private static final int[] ALL_ESCORT_NPC_IDS = {
            NPC_FYIONA_FRAY, NPC_DALCIAN_FANG, // Temple Trekking (Paterdomus side)
            NPC_ADVENTURER, NPC_MAGE            // Burgh de Rott Ramble (Burgh side)
    };

    // =========================================================================
    // State
    // =========================================================================

    public static final String VERSION = "1.0.0";

    @Getter private TempleTrekkingState currentState = TempleTrekkingState.IDLE;
    @Getter private int trekCount = 0;
    @Getter private Instant startTime;

    /** For overlay display — total reward tokens currently in inventory. */
    public int getTokenCount() {
        if (!Microbot.isLoggedIn()) return 0;
        return countTokens();
    }

    /** For overlay display — current inventory management mode label. */
    public String getInventoryManagementMode() {
        if (config == null) return "NONE";
        return config.inventoryManagement().name();
    }

    private TempleTrekkingConfig config;
    private int bridgeMaterialsUsed = 0;
    private boolean trekInProgress  = false;
    private boolean routeClicked    = false;
    private boolean vineAttached    = false;
    // Set to true once 3 logs are in inventory during BRIDGE_CHOP_TREES.
    // Prevents re-entering the chop phase if dead trees regrow mid-event.
    private boolean logsCollected    = false;
    // Same latch for the zombie bridge — set true once 3 planks confirmed in inventory.
    // Prevents re-entering Phase A (and attacking zombies) once repair has started.
    private boolean planksCollected   = false;
    // Set true after the player swings across the river. Prevents re-entering
    // handleRiverCrossing and swinging back while the vine branch is still rendered.
    private boolean riverCrossed      = false;
    // Randomized free-slot threshold recalculated each CHECK_INVENTORY tick
    private int cleanupThreshold = 5;

    // =========================================================================
    // Entry point
    // =========================================================================

    public boolean run(TempleTrekkingConfig config) {
        this.config    = config;
        this.startTime = Instant.now();
        this.currentState = TempleTrekkingState.START_TREK;

        log.info("Temple Trekking script v{} starting. Direction: {}", VERSION, config.trekDirection());

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!super.run()) return;
                if (!Microbot.isLoggedIn()) return;

                switch (currentState) {
                    case IDLE:                break;
                    case START_TREK:          handleStartTrek();         break;
                    case SELECT_ROUTE:        handleSelectRoute();       break;
                    case TREKKING:            handleTrekking();          break;
                    case DETECT_EVENT:        handleDetectEvent();       break;
                    case EVADE_COMBAT:        handleEvadeCombat();       break;
                    case BRIDGE_CHOP_TREES:   handleBridgeChopTrees();   break;
                    case BRIDGE_KILL_ZOMBIES: handleBridgeKillZombies(); break;
                    case RIVER_CROSSING:      handleRiverCrossing();     break;
                    case TREK_COMPLETE:       handleTrekComplete();      break;
                    case CHECK_INVENTORY:     handleCheckInventory();    break;
                    case BANKING:             handleBanking();           break;
                    case REDEEM_TOKENS:       handleRedeemTokens();      break;
                    case USE_TOMES:           handleUseTomes();          break;
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
    // Looks for any escort NPC from either trek direction — whichever end the
    // player is at after the previous trek completes. No walking, no hardcoded
    // locations. If no escort NPC is visible, log an error and shut down.
    // =========================================================================
    private void handleStartTrek() {
        if (trekInProgress) {
            log.info("Trek already in progress, resuming TREKKING...");
            setState(TempleTrekkingState.TREKKING);
            return;
        }

        Rs2NpcModel escortNpc = Microbot.getRs2NpcCache().query()
                .withIds(ALL_ESCORT_NPC_IDS)
                .nearest();

        if (escortNpc == null) {
            // NPC may not be rendered yet — the area could still be loading after
            // the previous trek ended. Wait up to 10s before giving up for this tick.
            log.debug("No escort NPC visible yet — waiting up to 10s for area to load...");
            boolean appeared = sleepUntil(() ->
                Microbot.getRs2NpcCache().query().withIds(ALL_ESCORT_NPC_IDS).nearest() != null,
                10000);
            if (!appeared) {
                log.warn("Escort NPC still not visible after 10s — will retry next tick.");
                return; // retry next scheduler tick; never shut down
            }
            escortNpc = Microbot.getRs2NpcCache().query().withIds(ALL_ESCORT_NPC_IDS).nearest();
        }

        log.info("Found escort NPC: {} (id={}). Clicking Escort...", escortNpc.getName(), escortNpc.getId());
        if (escortNpc.click("Escort")) {
            sleep(600, 1200);
            if (sleepUntil(Rs2Dialogue::isInDialogue, 5000)) {
                log.info("Dialogue opened, transitioning to SELECT_ROUTE");
                routeClicked = false;
                setState(TempleTrekkingState.SELECT_ROUTE);
            } else {
                log.warn("Clicked Escort but no dialogue opened within 5s, retrying...");
            }
        }
    }

    // =========================================================================
    // STATE: SELECT_ROUTE
    //
    // Sequence: dialogue(s) → route map widget → TREKKING.
    // We NEVER interact with the Continue-trek path stone here or anywhere else.
    // =========================================================================
    private void handleSelectRoute() {

        // Step 1: Click through any standard dialogues
        if (Rs2Dialogue.isInDialogue()) {
            if (Rs2Dialogue.hasContinue()) {
                sleep(400, 800);
                Rs2Dialogue.clickContinue();
                sleepUntil(() -> Rs2Dialogue.isInDialogue() || Rs2Widget.isWidgetVisible(329, 21), 3000);
            } else {
                sleep(300, 500);
            }
            return;
        }

        // Wait briefly for map or another dialogue after a teleport
        if (!Rs2Widget.isWidgetVisible(329, 21) && !trekInProgress) {
            boolean appeared = sleepUntil(
                () -> Rs2Dialogue.isInDialogue() || Rs2Widget.isWidgetVisible(329, 21),
                2500
            );
            if (appeared) return;
            log.debug("SELECT_ROUTE: nothing after 2.5s, continuing...");
        }

        // Step 2: Click Route 1 on the map widget exactly once
        if (Rs2Widget.isWidgetVisible(329, 21)) {
            if (!routeClicked) {
                log.info("Route map open, clicking Route 1 (329, 21)...");
                sleep(400, 800);
                if (!Rs2Widget.clickWidget(329, 21)) {
                    log.warn("Failed to click Route 1, retrying next tick...");
                    return;
                }
                routeClicked = true;
                log.info("Route 1 clicked, waiting for map to close...");
            }
            sleep(200);
            return;
        }

        // Step 3: Map is gone — trek instance is loading, move to TREKKING.
        // The Continue-trek path stone may appear here but we ignore it entirely.
        log.info("Route map closed. Transitioning to TREKKING.");
        trekInProgress    = true;
        bridgeMaterialsUsed = 0;
        setState(TempleTrekkingState.TREKKING);
    }

    // =========================================================================
    // STATE: TREKKING
    //
    // Idle between events. We actively poll for event objects within each tick
    // (up to 200ms) rather than waiting a full 600ms between scheduler fires.
    // Never look for or interact with the Continue-trek stone here.
    // =========================================================================
    private void handleTrekking() {
        // Trek is complete when we are back at the start area with the escort NPC visible.
        if (trekInProgress && isAtEndpoint()) {
            log.info("Endpoint NPC visible (Hiylik Myna/Florin) — trek complete!");
            trekInProgress = false;
            setState(TempleTrekkingState.TREK_COMPLETE);
            return;
        }

        // Handle pseudo-map dialogue between segments
        if (Rs2Dialogue.isInDialogue()) {
            if (Rs2Dialogue.hasSelectAnOption()) {
                if (Rs2Dialogue.clickOption("Continue on the trek")) {
                    log.info("Clicked 'Continue on the trek'");
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

        // Actively poll for event objects within this tick window — reduces detection
        // latency vs. waiting for the next 600ms scheduler fire.
        boolean detected = sleepUntil(this::isEventPresent, 200);
        if (detected) {
            log.info("Event area detected — transitioning to DETECT_EVENT");
            setState(TempleTrekkingState.DETECT_EVENT);
        }
        // Otherwise idle — still walking between events
    }

    /** Returns true if any known event trigger object is visible. */
    private boolean isEventPresent() {
        return findObjectById(OBJ_EVADE_PATH)          != null
            || findObjectById(OBJ_LOG_BRIDGE_BROKEN)   != null
            || findObjectById(OBJ_LOG_BRIDGE_PARTIAL)  != null
            || findObjectById(OBJ_PLANK_BRIDGE_PARTIAL) != null
            || findObjectById(OBJ_PLANK_BRIDGE_ALMOST) != null
            || findSwampTree()                         != null;
    }

    // =========================================================================
    // STATE: DETECT_EVENT
    //
    // Identify the event type and route to the correct handler.
    // If unrecognised, log all nearby objects and shut down.
    // =========================================================================
    private void handleDetectEvent() {
        logNearbyObjects();

        // Step 1: If dialogue is open, click through it first.
        // Both bridge variants (dead tree AND zombie lumberjack) open a dialogue
        // when the player arrives. Lumberjacks don't spawn until dialogue is closed,
        // so we can't distinguish the variants until after it's dismissed.
        if (Rs2Dialogue.isInDialogue()) {
            log.info("DETECT_EVENT: dialogue open — clicking through before identifying event...");
            while (Rs2Dialogue.isInDialogue()) {
                if (Rs2Dialogue.hasContinue()) {
                    sleep(400, 700);
                    Rs2Dialogue.clickContinue();
                    sleep(400, 600);
                } else if (Rs2Dialogue.hasSelectAnOption()) {
                    // Don't blindly click options here — fall back and let TREKKING handle it
                    break;
                } else {
                    sleep(300);
                }
            }
            // Wait a moment for the scene to update after dialogue closes
            sleep(600, 1000);
            return; // re-enter DETECT_EVENT next tick with fresh scene state
        }

        // Step 2: Bridge events — checked before evade path because id:13831 can
        // co-exist with the zombie bridge and must not be mistaken for a combat event.
        // Stage 0 bridge id (13834) is shared by both variants. Stages 1+ differ.
        boolean brokenBridge = findObjectById(OBJ_LOG_BRIDGE_BROKEN)    != null
                            || findObjectById(OBJ_LOG_BRIDGE_PARTIAL)   != null
                            || findObjectById(OBJ_PLANK_BRIDGE_PARTIAL) != null
                            || findObjectById(OBJ_PLANK_BRIDGE_ALMOST)  != null;
        if (brokenBridge) {
            // Now that dialogue is closed we can distinguish the variants:
            //   Dead tree event  — OBJ_DEAD_TREE (id:1365) is present
            //   Zombie event     — lumberjack NPCs (id:5655) have spawned
            // If neither is conclusive yet (both false), wait up to 5s for one to appear.
            boolean hasDeadTree   = findObjectById(OBJ_DEAD_TREE) != null;
            boolean hasLumberjack = Microbot.getRs2NpcCache().query().withId(5655).nearest() != null;

            // Dead tree present = log bridge. No dead tree = zombie bridge.
            // Lumberjacks may not have spawned yet even after dialogue closes,
            // so we use dead tree presence as the sole distinguishing signal.
            if (hasDeadTree) {
                log.info("Detected: Bridge event — dead trees");
                bridgeMaterialsUsed = 0;
                logsCollected = false;
                setState(TempleTrekkingState.BRIDGE_CHOP_TREES);
            } else {
                log.info("Detected: Bridge event — undead lumberjacks (no dead tree present)");
                bridgeMaterialsUsed = 0;
                planksCollected = false;
                setState(TempleTrekkingState.BRIDGE_KILL_ZOMBIES);
            }
            return;
        }

        // Combat: Evade-event path stone (id:13831) present — checked AFTER bridge
        // to avoid false-positive when id:13831 co-exists with the zombie bridge.
        if (findObjectById(OBJ_EVADE_PATH) != null) {
            log.info("Detected: Combat event (id:13831 visible)");
            setState(TempleTrekkingState.EVADE_COMBAT);
            return;
        }

        // River crossing: any swamp tree variant present
        if (findSwampTree() != null) {
            log.info("Detected: River crossing event");
            vineAttached = false;
            riverCrossed = false; // Reset for fresh crossing
            setState(TempleTrekkingState.RIVER_CROSSING);
            return;
        }

        // No puzzle objects found. Two valid reasons:
        //   1. The event area is still loading — puzzle objects haven't appeared yet.
        //   2. The event was just completed and the area is unloading — only the
        //      Continue-trek stone (13832) remains, puzzle objects already gone.
        // In both cases, wait and retry. If puzzle objects genuinely never appear
        // after 10s, that is unexpected — log an error and shut down.
        boolean continueTrekVisible = findObjectById(OBJ_CONTINUE_PATH) != null;
        log.warn("DETECT_EVENT: no puzzle objects found (continueTrek={}) — waiting up to 10s...",
                continueTrekVisible);
        boolean puzzleAppeared = sleepUntil(() ->
                findObjectById(OBJ_EVADE_PATH) != null ||
                findObjectById(OBJ_LOG_BRIDGE_BROKEN) != null ||
                findObjectById(OBJ_LOG_BRIDGE_PARTIAL) != null ||
                findObjectById(OBJ_PLANK_BRIDGE_PARTIAL) != null ||
                findObjectById(OBJ_PLANK_BRIDGE_ALMOST) != null ||
                findSwampTree() != null,
                10000);
        if (!puzzleAppeared) {
            // Puzzle objects never appeared — most likely a slow scene load or an
            // event type we don't recognise yet. Log what's around and fall back to
            // TREKKING so the loop continues rather than dying permanently.
            log.warn("DETECT_EVENT: puzzle objects never appeared after 10s — falling back to TREKKING.");
            logNearbyObjects();
            setState(TempleTrekkingState.TREKKING);
        }
        // If they appeared, next scheduler tick will re-enter DETECT_EVENT and route correctly.
    }

    // =========================================================================
    // STATE: EVADE_COMBAT
    //
    // Click the evade path as fast as possible to minimise damage taken.
    // Retry up to 5 times (3s total) in case the first click misses.
    // =========================================================================
    private void handleEvadeCombat() {
        Rs2TileObjectModel evadePath = findObjectById(OBJ_EVADE_PATH);
        if (evadePath == null) {
            log.warn("Evade path (id:13831) not found, waiting...");
            return;
        }

        log.info("Clicking Evade-event path (id:13831) — evading ASAP...");
        // Retry loop: click immediately, then re-check every 600ms for up to 5s.
        for (int attempt = 1; attempt <= 5; attempt++) {
            evadePath.click("Evade-event");
            boolean gone = sleepUntil(() -> findObjectById(OBJ_EVADE_PATH) == null, 1000);
            if (gone) break;
            log.debug("Evade path still present after attempt {} — retrying", attempt);
            evadePath = findObjectById(OBJ_EVADE_PATH);
            if (evadePath == null) break; // disappeared between retry checks
        }
        setState(TempleTrekkingState.TREKKING);
    }

    // =========================================================================
    // STATE: BRIDGE_CHOP_TREES
    //
    // Log bridge stages (verified in-game):
    //   Stage 0 — id:13834  Broken bridge       (use 1st log)
    //   Stage 1 — id:13835  Partially repaired  (use 2nd log)
    //   Stage 2 — id:13836  Almost repaired     (use 3rd log)
    //   Stage 3 — id:13837  Fixed bridge        (cross, then Continue-trek)
    // =========================================================================
    private void handleBridgeChopTrees() {
        // ---- Phase A: collect 3 logs — logsCollected latches once complete ----
        if (!logsCollected) {
            int logs = Rs2Inventory.count(ITEM_LOG);
            if (logs < 3) {
                Rs2TileObjectModel deadTree = findObjectById(OBJ_DEAD_TREE);
                if (deadTree == null) {
                    log.warn("[LogBridge] No dead tree found — have {}/3 logs, waiting...", logs);
                    sleep(1000);
                    return;
                }
                log.info("[LogBridge] Chopping tree — have {}/3 logs", logs);
                deadTree.click("Chop down");
                sleepUntil(Rs2Player::isAnimating, 3000);
                sleepUntil(() -> !Rs2Player.isAnimating(), 10000);
                sleep(400, 700);
                return;
            }
            logsCollected = true;
            log.info("[LogBridge] 3 logs collected. Switching to repair phase.");
        }

        // ---- Phase B: repair one stage at a time, then cross + Continue-trek ----
        int currentStage = getLogBridgeStage();
        log.debug("[LogBridge] Repair phase — stage: {}", logBridgeStageName(currentStage));

        if (currentStage == 3) {
            Rs2TileObjectModel fixedBridge = findObjectById(OBJ_LOG_BRIDGE_FIXED);
            if (fixedBridge != null) {
                log.info("[LogBridge] Crossing fixed bridge (id:13837)...");
                fixedBridge.click("Cross");
                sleepUntil(() -> findObjectById(OBJ_LOG_BRIDGE_FIXED) == null, 8000);
                clickContinueTrek();
            } else {
                sleep(500);
            }
            return;
        }

        if (currentStage == -1) {
            log.debug("[LogBridge] Bridge mid-transition, waiting...");
            sleepUntil(() -> getLogBridgeStage() != -1, 5000);
            return;
        }

        Rs2TileObjectModel bridge = findObjectById(logBridgeIdForStage(currentStage));
        if (bridge == null) { sleep(600); return; }

        log.info("[LogBridge] Using log on {} (id:{}) — stage {} of 3",
                logBridgeStageName(currentStage), bridge.getId(), currentStage + 1);
        Rs2Inventory.use(ITEM_LOG);
        sleep(400, 600);
        bridge.click();
        if (sleepUntil(Rs2Dialogue::hasSelectAnOption, 4000)) {
            Rs2Dialogue.clickOption("Yes");
        }
        int stageBeforeUse = currentStage;
        boolean advanced = sleepUntil(() -> getLogBridgeStage() != stageBeforeUse, 8000);
        if (!advanced) log.warn("[LogBridge] Still at stage {} after 8s — retrying", logBridgeStageName(stageBeforeUse));
        sleep(400, 600);
    }

    // =========================================================================
    // STATE: BRIDGE_KILL_ZOMBIES
    //
    // Same bridge stage IDs as BRIDGE_CHOP_TREES — see stage table above.
    // Phase A: Collect 3 planks. Priority order each tick:
    //   1. Pick up any plank already on the ground (highest priority)
    //   2. Wait if already in combat (plank will drop when kill lands)
    //   3. Attack a lumberjack only if no planks on ground and not in combat
    // planksCollected latches true at 3 planks — Phase A never re-enters after that.
    // Phase B: Repair bridge one plank at a time.
    // =========================================================================
    private void handleBridgeKillZombies() {

        // ---- Phase A: collect all 3 planks — latched out once complete ----
        if (!planksCollected) {
            int currentCount = Rs2Inventory.count(ITEM_PLANK);

            if (currentCount >= 3) {
                planksCollected = true;
                log.info("[Bridge] 3 planks collected. Switching to repair phase.");
                // fall through to Phase B immediately
            } else {
                // Priority 1: plank on the ground — pick it up immediately, even mid-combat.
                var plankOnGround = Microbot.getRs2TileItemCache().query()
                        .withId(ITEM_PLANK)
                        .nearest(15);
                if (plankOnGround != null) {
                    log.info("[PlankBridge] Plank on ground — picking up (have {}/3)", currentCount);
                    plankOnGround.click("Take");
                    sleepUntil(() -> Rs2Inventory.count(ITEM_PLANK) > currentCount, 3000);
                    return;
                }

                // No plank on ground — click attack if not already in combat.
                // Do NOT sleepUntil(isInCombat) — that blocks the ground-plank check
                // for up to 3s if the zombie is stuck pathing. Instead just fire the
                // click and immediately return; the next 600ms scheduler tick will
                // re-check for ground planks first before attacking again.
                if (!Rs2Player.isInCombat()) {
                    Rs2NpcModel lumberjack = Microbot.getRs2NpcCache().query().withId(5655).nearest();
                    if (lumberjack != null) {
                        log.info("[PlankBridge] Attacking lumberjack (id:5655) — have {}/3 planks", currentCount);
                        lumberjack.click("Attack");
                        // No sleepUntil here — return immediately so ground plank
                        // check fires on the very next tick (~600ms).
                    } else {
                        log.debug("[PlankBridge] No lumberjack in range yet — have {}/3 planks", currentCount);
                    }
                } else {
                    log.debug("[PlankBridge] In combat, waiting for plank drop — have {}/3 planks", currentCount);
                }
                return;
            }
        }

        // ---- Phase B: repair bridge with all 3 planks, then cross + Continue-trek ----
        // Plank bridge stages (verified in-game):
        //   Stage 0 — id:13834  Broken bridge       (use 1st plank)
        //   Stage 1 — id:22533  Partially broken    (use 2nd plank)
        //   Stage 2 — id:22534  Slightly broken     (use 3rd plank)
        //   Stage 3 — id:22535  Fixed bridge        (cross, then Continue-trek)
        int currentStage = getPlankBridgeStage();
        log.debug("[PlankBridge] Repair phase — stage: {}", plankBridgeStageName(currentStage));

        if (currentStage == 3) {
            Rs2TileObjectModel fixedBridge = findObjectById(OBJ_PLANK_BRIDGE_FIXED);
            if (fixedBridge != null) {
                log.info("[PlankBridge] Crossing fixed bridge (id:22535)...");
                fixedBridge.click("Cross");
                sleepUntil(() -> findObjectById(OBJ_PLANK_BRIDGE_FIXED) == null, 8000);
                clickContinueTrek();
            } else {
                sleep(500);
            }
            return;
        }

        if (currentStage == -1) {
            log.debug("[PlankBridge] Bridge mid-transition, waiting...");
            sleepUntil(() -> getPlankBridgeStage() != -1, 5000);
            return;
        }

        Rs2TileObjectModel bridge = findObjectById(plankBridgeIdForStage(currentStage));
        if (bridge == null) { sleep(600); return; }

        log.info("[PlankBridge] Using plank on {} (id:{}) — stage {} of 3",
                plankBridgeStageName(currentStage), bridge.getId(), currentStage + 1);
        Rs2Inventory.use(ITEM_PLANK);
        sleep(400, 600);
        bridge.click();
        if (sleepUntil(Rs2Dialogue::hasSelectAnOption, 4000)) {
            Rs2Dialogue.clickOption("Yes");
        }
        int stageBeforeUse = currentStage;
        boolean advanced = sleepUntil(() -> getPlankBridgeStage() != stageBeforeUse, 8000);
        if (!advanced) log.warn("[PlankBridge] Still at stage {} after 8s — retrying", plankBridgeStageName(stageBeforeUse));
        sleep(400, 600);
    }

    // =========================================================================
    // STATE: RIVER_CROSSING
    //
    // Full sequence:
    //   1. Cut 3 short vines from the swamp tree (id:13847/13848/13849)
    //   2. Combine: use one short vine on another — repeat until long vine acquired
    //   3. Use long vine on bare branch (id:13845) — transforms to vine branch (id:13846)
    //   4. Swing-from vine branch (id:13846) to cross — then click Continue-trek
    //
    // Priority: always check the furthest-complete step first so we never
    // fall backwards once a step is done.
    // =========================================================================
    private void handleRiverCrossing() {
        // Guard: once the player has swung across, do not re-enter this handler.
        // The vine branch (id:13846) can still be rendered in the scene after crossing,
        // which would otherwise trigger another swing back to the starting side.
        if (riverCrossed) {
            log.debug("[River] Already crossed — waiting for state transition...");
            return;
        }

        // The bare branch (id:13845) is present from the start of the event — it is NOT
        // a signal that vines are ready. Only act on it once we have a long vine.
        // Correct sequence:
        //   1. Cut 3 short vines from swamp tree (id:13847/13848/13849)
        //   2. Combine 3 short vines into 1 long vine
        //   3. Use long vine on bare branch (id:13845) → vine branch (id:13846)
        //   4. Swing-from vine branch → clickContinueTrek

        // Step 4: vine branch (id:13846) visible — swing across
        // After the swing lands there is an unskippable NPC cutscene dialogue
        // followed by a fade-to-black before control returns to the player.
        // We must NOT click Continue-trek during that window or the click is lost.
        // Strategy:
        //   1. Click Swing-from, wait for animation to start then finish (player landed).
        //   2. Latch riverCrossed immediately.
        //   3. Wait out the NPC dialogue (hasContinue = true → click through it).
        //   4. Wait for the fade-to-black to end (player stops being in a dialogue AND
        //      is no longer animating) before clicking the Continue-trek stone.
        Rs2TileObjectModel vineBranch = findObjectById(OBJ_SWAMP_TREE_BRANCH_VINE);
        if (vineBranch != null) {
            log.info("[River] Vine branch (id:13846) — swinging across...");
            vineBranch.click("Swing-from");
            sleepUntil(Rs2Player::isAnimating, 3000);         // swing animation starts
            sleepUntil(() -> !Rs2Player.isAnimating(), 8000); // swing animation finishes (landed)
            riverCrossed = true; // latch before anything else — branch may still be rendered
            log.info("[River] Landed. Waiting for post-swing NPC dialogue to clear...");

            // Click through any NPC dialogue that appears after the swing
            sleepUntil(Rs2Dialogue::isInDialogue, 4000);
            while (Rs2Dialogue.isInDialogue()) {
                if (Rs2Dialogue.hasContinue()) {
                    sleep(400, 700);
                    Rs2Dialogue.clickContinue();
                    sleep(500, 800);
                } else {
                    sleep(300);
                }
            }

            // Wait for fade-to-black to end: player not animating + not in dialogue
            log.info("[River] Dialogue done. Waiting for fade-to-black to clear...");
            sleepUntil(() -> !Rs2Player.isAnimating() && !Rs2Dialogue.isInDialogue(), 8000);
            sleep(600, 1000); // brief buffer after fade ends before clicking stone

            log.info("[River] Crossing complete — clicking Continue-trek...");
            clickContinueTrek();
            return;
        }

        // Step 3: have long vine and bare branch is present — attach it
        if (Rs2Inventory.contains(ITEM_LONG_VINE)) {
            Rs2TileObjectModel bareBranch = findObjectById(OBJ_SWAMP_TREE_BRANCH_BARE);
            if (bareBranch == null) {
                log.debug("[River] Long vine ready, waiting for bare branch (id:13845)...");
                sleepUntil(() -> findObjectById(OBJ_SWAMP_TREE_BRANCH_BARE) != null, 5000);
                return;
            }
            log.info("[River] Attaching long vine to bare branch (id:13845)...");
            Rs2Inventory.use(ITEM_LONG_VINE);
            sleep(300, 500);
            bareBranch.click();
            sleepUntil(() -> findObjectById(OBJ_SWAMP_TREE_BRANCH_VINE) != null, 8000);
            return;
        }

        // Step 2: have 3 short vines — combine into long vine
        // Rs2Inventory.combine() selects the first vine, then explicitly finds a DIFFERENT
        // slot for the second vine and clicks it as the target. This is the correct
        // "use item on item" pattern — using use() twice just re-selects the same item.
        int vineCount = Rs2Inventory.count(ITEM_SHORT_VINE);
        if (vineCount >= 3) {
            log.info("[River] Combining short vines into long vine (have {})...", vineCount);
            Rs2Inventory.combine(ITEM_SHORT_VINE, ITEM_SHORT_VINE);
            sleepUntil(() -> Rs2Inventory.contains(ITEM_LONG_VINE), 5000);
            return;
        }

        // Step 1: cut short vines from the swamp tree until we have 3
        Rs2TileObjectModel swampTree = findSwampTree();
        if (swampTree == null) {
            log.warn("[River] No swamp tree found (ids:13847/13848/13849) — have {}/3 vines, waiting...", vineCount);
            sleep(1000);
            return;
        }
        log.info("[River] Cutting vine from tree (id:{}) — have {}/3 short vines", swampTree.getId(), vineCount);
        swampTree.click("Cut-vine");
        int before = vineCount;
        sleepUntil(() -> Rs2Inventory.count(ITEM_SHORT_VINE) > before, 5000);
    }

    // =========================================================================
    // STATE: TREK_COMPLETE
    // =========================================================================
    private void handleTrekComplete() {
        trekCount++;
        trekInProgress = false;
        log.info("Trek {} complete!", trekCount);
        sleep(1500, 2500);
        setState(TempleTrekkingState.CHECK_INVENTORY);
    }

    // =========================================================================
    // STATE: CHECK_INVENTORY
    //
    // Runs after every trek. Counts free slots and decides whether to clean up.
    // Randomises the threshold by ±1 each check for human-like variation.
    // =========================================================================
    private void handleCheckInventory() {
        if (config.inventoryManagement() == TempleTrekkingConfig.InventoryManagement.NONE) {
            setState(TempleTrekkingState.START_TREK);
            return;
        }

        // Randomise threshold ±1 around the configured value
        int base = config.minFreeSlots();
        cleanupThreshold = base + (int)(Math.random() * 3) - 1; // base-1, base, or base+1
        cleanupThreshold = Math.max(4, cleanupThreshold); // never below 4

        int freeSlots = Rs2Inventory.emptySlotCount();
        int tokenCount = countTokens();
        log.info("[CheckInventory] Free slots: {} / Threshold: {} / Tokens: {}",
                freeSlots, cleanupThreshold, tokenCount);

        if (freeSlots > cleanupThreshold || tokenCount == 0) {
            // Plenty of space or nothing to clean up — keep trekking
            log.info("[CheckInventory] Slots OK or no tokens — starting next trek");
            setState(TempleTrekkingState.START_TREK);
            return;
        }

        // Slots are running low — decide based on mode
        switch (config.inventoryManagement()) {
            case BANK:
                // Only bank if we're already in Burgh de Rott — otherwise do one more trek
                if (isInBurghDeRott()) {
                    log.info("[CheckInventory] Low slots + in Burgh — banking tokens");
                    setState(TempleTrekkingState.BANKING);
                } else {
                    log.info("[CheckInventory] Low slots but not in Burgh — one more trek");
                    setState(TempleTrekkingState.START_TREK);
                }
                break;
            case REDEEM_TOMES:
                log.info("[CheckInventory] Low slots — redeeming tokens for tomes");
                setState(TempleTrekkingState.REDEEM_TOKENS);
                break;
            default:
                setState(TempleTrekkingState.START_TREK);
        }
    }

    // =========================================================================
    // STATE: BANKING
    //
    // Walk to Burgh de Rott bank, deposit all reward tokens, return to loop.
    // =========================================================================
    private void handleBanking() {
        log.info("[Banking] Walking to Burgh de Rott bank...");
        Rs2Walker.walkTo(BankLocation.BURGH_DE_ROTT.getWorldPoint(), 4);
        sleepUntil(Rs2Bank::isOpen, 15000);

        if (!Rs2Bank.isOpen()) {
            // Try opening the bank manually if walk didn't trigger it
            Rs2Bank.openBank();
            sleepUntil(Rs2Bank::isOpen, 5000);
        }

        if (!Rs2Bank.isOpen()) {
            log.warn("[Banking] Could not open bank — retrying next tick");
            return;
        }

        // Deposit all reward tokens
        for (int tokenId : ALL_TOKEN_IDS) {
            while (Rs2Inventory.contains(tokenId)) {
                log.info("[Banking] Depositing token id:{}", tokenId);
                Rs2Bank.depositAll(tokenId);
                sleep(400, 700);
            }
        }

        log.info("[Banking] All tokens deposited. Closing bank.");
        Rs2Bank.closeBank();
        sleepUntil(() -> !Rs2Bank.isOpen(), 3000);
        sleep(500, 800);
        setState(TempleTrekkingState.START_TREK);
    }

    // =========================================================================
    // STATE: REDEEM_TOKENS
    //
    // For each reward token in inventory:
    //   1. Left-click (Look-at) the token — reward selection interface opens
    //   2. Click the 9th option (XP tome) — side panel shows
    //   3. Click the "Claim" button
    //   4. A skill tome appears in inventory replacing the token
    // After all tokens are redeemed, transition to USE_TOMES.
    // =========================================================================
    private void handleRedeemTokens() {
        // Check if any tokens remain
        if (countTokens() == 0) {
            log.info("[RedeemTokens] No tokens remaining — moving to USE_TOMES");
            setState(TempleTrekkingState.USE_TOMES);
            return;
        }

        // Find and click the first token
        Rs2ItemModel token = getFirstToken();
        if (token == null) {
            log.warn("[RedeemTokens] No token found — retrying next tick");
            return;
        }

        log.info("[RedeemTokens] Clicking token (id:{}) to open reward interface...", token.getId());
        Rs2Inventory.interact(token, "Look-at");

        // Wait for the TrekRewards interface to appear (parent widget 274)
        boolean interfaceOpen = sleepUntil(
                () -> Rs2Widget.isWidgetVisible(274, 4),
                5000);

        if (!interfaceOpen) {
            log.warn("[RedeemTokens] Reward interface (274,4) did not open — retrying");
            return;
        }

        // Get XP Tome bounds (274.5 child[7]) and click its exact centre
        sleep(200, 350);
        Rectangle xpTomeBounds = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            net.runelite.api.widgets.Widget rewards = Microbot.getClient().getWidget(274, 5);
            if (rewards == null) return null;
            net.runelite.api.widgets.Widget[] children = rewards.getChildren();
            if (children == null || children.length <= 7) return null;
            return children[7].getBounds();
        }).orElse(null);

        if (xpTomeBounds == null) {
            log.warn("[RedeemTokens] XP Tome widget bounds not found — retrying");
            return;
        }

        int centreX = xpTomeBounds.x + xpTomeBounds.width  / 2;
        int centreY = xpTomeBounds.y + xpTomeBounds.height / 2;
        log.info("[RedeemTokens] Clicking XP Tome centre ({}, {})", centreX, centreY);
        Microbot.getMouse().click(centreX, centreY);

        // Wait for the SIDE_PANEL (274.7) to appear, then click Claim
        boolean sidePanelVisible = sleepUntil(
                () -> Rs2Widget.isWidgetVisible(274, 7),
                5000);

        if (!sidePanelVisible) {
            log.warn("[RedeemTokens] Side panel (274,7) did not appear — retrying");
            return;
        }

        sleep(150, 250);
        boolean claimed = Rs2Widget.clickWidget("Claim");
        if (!claimed) {
            log.warn("[RedeemTokens] Claim button not found by text — retrying");
            return;
        }

        // Wait for the token to be consumed, then minimal buffer before next token
        int tokensBefore = countTokens();
        sleepUntil(() -> countTokens() < tokensBefore, 5000);
        sleep(150, 250);

        // If more tokens remain, stay in this state and loop
        // If none remain, next tick will transition to USE_TOMES
    }

    // =========================================================================
    // STATE: USE_TOMES
    //
    // Left-click (Read) each skill tome in inventory to instantly claim the XP.
    // No confirmation required — the tome is consumed on click.
    // =========================================================================
    private void handleUseTomes() {
        if (!hasTomes()) {
            log.info("[UseTomes] No tomes remaining — returning to START_TREK");
            setState(TempleTrekkingState.START_TREK);
            return;
        }

        // Read all tomes in one pass — no confirmation required, each is consumed on click
        while (hasTomes()) {
            Rs2ItemModel tome = getFirstTome();
            if (tome == null) break;

            log.info("[UseTomes] Reading tome (id:{})...", tome.getId());
            int tomesBefore = countTomes();
            Rs2Inventory.interact(tome, "Read");
            sleepUntil(() -> countTomes() < tomesBefore, 3000);
            sleep(100, 200);
        }

        log.info("[UseTomes] All tomes read — returning to START_TREK");
        setState(TempleTrekkingState.START_TREK);
    }

    // =========================================================================
    // UTILITY METHODS
    // =========================================================================

    /**
     * Returns the current bridge repair stage as an integer:
     *   0 = Broken bridge       (id:13834)
     *   1 = Partially repaired  (id:13835)
     */
    // --- Log bridge helpers (dead tree event: ids 13834/13835/13836/13837) ---

    private int getLogBridgeStage() {
        if (findObjectById(OBJ_LOG_BRIDGE_BROKEN)  != null) return 0;
        if (findObjectById(OBJ_LOG_BRIDGE_PARTIAL) != null) return 1;
        if (findObjectById(OBJ_LOG_BRIDGE_ALMOST)  != null) return 2;
        if (findObjectById(OBJ_LOG_BRIDGE_FIXED)   != null) return 3;
        return -1;
    }

    private int logBridgeIdForStage(int stage) {
        switch (stage) {
            case 0:  return OBJ_LOG_BRIDGE_BROKEN;  // 13834
            case 1:  return OBJ_LOG_BRIDGE_PARTIAL; // 13835
            case 2:  return OBJ_LOG_BRIDGE_ALMOST;  // 13836
            default: return OBJ_LOG_BRIDGE_FIXED;   // 13837
        }
    }

    private String logBridgeStageName(int stage) {
        switch (stage) {
            case 0:  return "Broken (id:13834)";
            case 1:  return "Partially repaired (id:13835)";
            case 2:  return "Almost repaired (id:13836)";
            case 3:  return "Fixed (id:13837)";
            default: return "Mid-transition";
        }
    }

    // --- Plank bridge helpers (zombie event: ids 13834/22533/22534/22535) ---

    private int getPlankBridgeStage() {
        if (findObjectById(OBJ_PLANK_BRIDGE_BROKEN)  != null) return 0;
        if (findObjectById(OBJ_PLANK_BRIDGE_PARTIAL) != null) return 1;
        if (findObjectById(OBJ_PLANK_BRIDGE_ALMOST)  != null) return 2;
        if (findObjectById(OBJ_PLANK_BRIDGE_FIXED)   != null) return 3;
        return -1;
    }

    private int plankBridgeIdForStage(int stage) {
        switch (stage) {
            case 0:  return OBJ_PLANK_BRIDGE_BROKEN;  // 13834
            case 1:  return OBJ_PLANK_BRIDGE_PARTIAL; // 22533
            case 2:  return OBJ_PLANK_BRIDGE_ALMOST;  // 22534
            default: return OBJ_PLANK_BRIDGE_FIXED;   // 22535
        }
    }

    private String plankBridgeStageName(int stage) {
        switch (stage) {
            case 0:  return "Broken (id:13834)";
            case 1:  return "Partially broken (id:22533)";
            case 2:  return "Slightly broken (id:22534)";
            case 3:  return "Fixed (id:22535)";
            default: return "Mid-transition";
        }
    }

    /**
     * Finds whichever swamp tree variant is currently present.
     * The tree changes ID as vines are cut: 13847 (3 left) → 13848 (2 left) → 13849 (1 left).
     */
    private Rs2TileObjectModel findSwampTree() {
        Rs2TileObjectModel t = findObjectById(OBJ_SWAMP_TREE_3);
        if (t != null) return t;
        t = findObjectById(OBJ_SWAMP_TREE_2);
        if (t != null) return t;
        return findObjectById(OBJ_SWAMP_TREE_1);
    }

    /**
     * Clicks the Continue-trek path stone (id:13832) to advance to the next segment.
     * Only called after a skill event is fully resolved:
     *   BRIDGE_CHOP_TREES  — after fixedBridge.click("Cross") + sleepUntil(bridge gone)
     *   BRIDGE_KILL_ZOMBIES — after fixedBridge.click("Cross") + sleepUntil(bridge gone)
     *   RIVER_CROSSING      — after vineBranch.click("Swing-from") + sleepUntil(branch gone)
     *
     * The stone is present for the ENTIRE event so it will always be found immediately.
     * The sleepUntil after clicking re-queries each tick via the lambda — it does NOT
     * hold a stale reference to the object found before the click.
     */
    private void clickContinueTrek() {
        Rs2TileObjectModel continuePath = findObjectById(OBJ_CONTINUE_PATH);
        if (continuePath == null) {
            log.warn("Continue-trek path stone (id:13832) not found — prerequisites may not be complete. Returning to TREKKING.");
            setState(TempleTrekkingState.TREKKING);
            return;
        }
        log.info("Clicking Continue-trek path stone (id:13832)...");
        continuePath.click("Continue-trek");
        // Re-query each poll tick — do NOT reuse the pre-click reference.
        sleepUntil(() -> findObjectById(OBJ_CONTINUE_PATH) == null, 8000);
        setState(TempleTrekkingState.TREKKING);
    }

    private Rs2TileObjectModel findObjectById(int id) {
        return Microbot.getRs2TileObjectCache().query().withId(id).nearest();
    }

    private void logNearbyObjects() {
        Microbot.getClientThread().invoke(() -> {
            var objects = Microbot.getRs2TileObjectCache().query().toList();
            log.info("=== Nearby objects ({} total) ===", objects.size());
            objects.stream()
                .filter(obj -> obj.getObjectComposition() != null)
                .forEach(obj -> {
                    var comp = obj.getObjectComposition();
                    String[] actions = comp.getActions();
                    String actionStr = actions != null
                        ? String.join(", ", java.util.Arrays.stream(actions)
                            .filter(a -> a != null && !a.isEmpty())
                            .toArray(String[]::new))
                        : "none";
                    log.info("  id:{} name:'{}' actions:[{}]", obj.getId(), comp.getName(), actionStr);
                });
            log.info("=== End object list ===");
            return null;
        });
    }

    /**
     * Returns true when the player has arrived at a trek endpoint.
     * Hiylik Myna (id:1579) only appears at Paterdomus (Temple Trekking end).
     * Florin (id:4454) only appears at Burgh de Rott (Ramble end).
     * Neither NPC can appear during an active trek instance.
     */
    private boolean isAtEndpoint() {
        return Microbot.getRs2NpcCache().query().withIds(new int[]{1579, 4454}).nearest() != null;
    }

    // --- Inventory helpers ---

    /** Total reward tokens (both IDs) currently in inventory. */
    private int countTokens() {
        int count = 0;
        for (int id : ALL_TOKEN_IDS) count += Rs2Inventory.count(id);
        return count;
    }

    /** Returns the first reward token found in inventory, or null. */
    private Rs2ItemModel getFirstToken() {
        for (int id : ALL_TOKEN_IDS) {
            Rs2ItemModel item = Rs2Inventory.get(id);
            if (item != null) return item;
        }
        return null;
    }

    /** Total skill tomes currently in inventory. */
    private int countTomes() {
        int count = 0;
        for (int id : ALL_TOME_IDS) count += Rs2Inventory.count(id);
        return count;
    }

    /** Returns true if any skill tome is in inventory. */
    private boolean hasTomes() {
        for (int id : ALL_TOME_IDS) {
            if (Rs2Inventory.contains(id)) return true;
        }
        return false;
    }

    /** Returns the first skill tome found in inventory, or null. */
    private Rs2ItemModel getFirstTome() {
        for (int id : ALL_TOME_IDS) {
            Rs2ItemModel item = Rs2Inventory.get(id);
            if (item != null) return item;
        }
        return null;
    }

    /** Returns true if the player is currently in the Burgh de Rott region. */
    private boolean isInBurghDeRott() {
        var player = Microbot.getClient().getLocalPlayer();
        if (player == null) return false;
        return player.getWorldLocation().getRegionID() == BURGH_DE_ROTT_REGION;
    }

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
        super.shutdown();
        currentState = TempleTrekkingState.IDLE;
        log.info("Temple Trekking script shut down. Total treks: {}", trekCount);
    }
}