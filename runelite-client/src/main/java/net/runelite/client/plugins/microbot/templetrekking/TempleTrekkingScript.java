package net.runelite.client.plugins.microbot.templetrekking;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.npc.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.api.coords.WorldPoint;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * TempleTrekkingScript is the brain of the plugin.
 *
 * It extends Script, which gives us:
 *  - scheduledExecutorService   (a background thread pool)
 *  - mainScheduledFuture        (a handle to our running loop so we can cancel it)
 *  - super.run()                (checks global pause/stop flags before each tick)
 *  - shutdown()                 (cancels the loop cleanly)
 *  - sleep() / sleepUntil()     (safe to use here — we're on a background thread, not the client thread)
 *
 * The core pattern: scheduleWithFixedDelay runs our logic every 600ms (1 game tick).
 * Each tick, we look at currentState and call the matching handler method.
 * Each handler either does work or transitions to the next state.
 */
@Slf4j  // Lombok: gives us log.info(), log.warn(), log.error() for free
public class TempleTrekkingScript extends Script {

    // -------------------------------------------------------------------------
    // Constants — NPC names and WorldPoints we'll need
    // -------------------------------------------------------------------------

    /**
     * The easy escort NPCs available at the Burgh de Rott Ramble start (Paterdomus side).
     * We look for any one of these when starting a new trek.
     *
     * "Adventurer" and "Mage" are the easy-class followers on the Ramble side.
     * Exact names as they appear in-game (right-click the NPC to confirm).
     */
    private static final String[] EASY_RAMBLE_NPCS = {
        "Adventurer",
        "Mage"
    };

    /**
     * The easy escort NPCs available at the Temple Trekking start (Burgh de Rott side).
     */
    private static final String[] EASY_TREK_NPCS = {
        "Fyiona Fray",
        "Dalcian Fang"
    };

    /**
     * WorldPoint for the Burgh de Rott Ramble start area (east of Paterdomus, River Salve crossing).
     * We walk here before looking for an escort NPC.
     *
     * NOTE: You will need to verify these coordinates in-game using the
     * "Tile Location" plugin in RuneLite (turn it on in the plugin hub,
     * then hover your mouse over the NPC area to see the WorldPoint).
     * These are approximate — adjust once you confirm in-game.
     */
    private static final WorldPoint RAMBLE_START_LOCATION = new WorldPoint(3408, 3479, 0);

    /**
     * WorldPoint for the Temple Trekking start area (Burgh de Rott).
     * Same note — confirm in-game.
     */
    private static final WorldPoint TREK_START_LOCATION = new WorldPoint(3494, 3215, 0);

    // -------------------------------------------------------------------------
    // State tracking
    // -------------------------------------------------------------------------

    /**
     * @Getter (from Lombok) auto-generates a getCurrentState() method.
     * The overlay uses this to display the current state.
     */
    @Getter
    private TempleTrekkingState currentState = TempleTrekkingState.IDLE;

    /**
     * Counts how many full treks have completed this session.
     * Shown on the overlay.
     */
    @Getter
    private int trekCount = 0;

    /**
     * The moment run() was called. Used to calculate the runtime shown on the overlay.
     */
    @Getter
    private Instant startTime;

    /**
     * Holds a reference to the config so we know which direction to run,
     * and which events to complete vs skip.
     */
    private TempleTrekkingConfig config;

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    /**
     * Called by TempleTrekkingPlugin.startUp().
     * Saves the config, records start time, and kicks off the scheduled loop.
     *
     * @param config The user's settings from the RuneLite config panel.
     * @return true if the script started successfully.
     */
    public boolean run(TempleTrekkingConfig config) {
        this.config = config;
        this.startTime = Instant.now();
        this.currentState = TempleTrekkingState.START_TREK;

        log.info("Temple Trekking script starting. Direction: {}", config.trekDirection());

        /**
         * scheduleWithFixedDelay(task, initialDelay, period, unit)
         *
         * This runs our lambda every 600ms (one game tick).
         * "Fixed delay" means: wait 600ms AFTER the previous run finishes,
         * so if our logic takes 200ms, the next run starts 600ms later.
         * This prevents pile-up if a tick takes longer than expected.
         */
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                // super.run() returns false if the user has paused all scripts
                // or if the script has been shut down. Always check this first.
                if (!super.run()) return;

                // Don't do anything if we're not logged in
                if (!Microbot.isLoggedIn()) return;

                // Route execution to the correct handler based on current state
                switch (currentState) {
                    case IDLE:
                        // Waiting — do nothing until something sets a real state
                        break;

                    case START_TREK:
                        handleStartTrek();
                        break;

                    case SELECT_ROUTE:
                        handleSelectRoute();
                        break;

                    case TREKKING:
                        handleTrekking();
                        break;

                    case DETECT_EVENT:
                        handleDetectEvent();
                        break;

                    case EVADE_COMBAT:
                        handleEvadeCombat();
                        break;

                    case BRIDGE_CHOP_TREES:
                        handleBridgeChopTrees();
                        break;

                    case BRIDGE_KILL_ZOMBIES:
                        handleBridgeKillZombies();
                        break;

                    case RIVER_CROSSING:
                        handleRiverCrossing();
                        break;

                    case CONTINUE_TREK:
                        handleContinueTrek();
                        break;

                    case TREK_COMPLETE:
                        handleTrekComplete();
                        break;

                    case UNKNOWN_EVENT:
                        handleUnknownEvent();
                        break;

                    default:
                        log.warn("Unhandled state: {}", currentState);
                        break;
                }

            } catch (Exception e) {
                // Never let an exception crash the background thread.
                // Log it and keep running — the next tick will try again.
                log.error("Error in Temple Trekking script loop: ", e);
            }
        }, 0, 600, TimeUnit.MILLISECONDS);

        return true;
    }

    // -------------------------------------------------------------------------
    // State handlers — one method per state
    // Each method does one small piece of work, then either:
    //   a) Does nothing (waits for a condition to be true next tick)
    //   b) Transitions to the next state with setState()
    // -------------------------------------------------------------------------

    /**
     * STATE: START_TREK
     *
     * Goal: Find an easy escort NPC and start talking to them.
     *
     * Steps:
     * 1. Walk to the start location if we're not already there.
     * 2. Find any easy escort NPC nearby.
     * 3. Talk to them (this opens a dialogue).
     * 4. Transition to SELECT_ROUTE.
     */
    private void handleStartTrek() {
        log.info("Looking for escort NPC to start trek...");

        // Figure out which NPC names and location to use based on config
        String[] npcNames = config.trekDirection() == TempleTrekkingConfig.TrekDirection.BURGH_DE_ROTT_RAMBLE
                ? EASY_RAMBLE_NPCS
                : EASY_TREK_NPCS;

        WorldPoint startLocation = config.trekDirection() == TempleTrekkingConfig.TrekDirection.BURGH_DE_ROTT_RAMBLE
                ? RAMBLE_START_LOCATION
                : TREK_START_LOCATION;

        // Try to find any of the easy NPCs nearby
        Rs2NpcModel escortNpc = findEscortNpc(npcNames);

        if (escortNpc == null) {
            // NPC not visible — walk toward the start area so they come into render distance
            log.info("Escort NPC not found, walking to start location...");
            Rs2Walker.walkTo(startLocation);
            // Don't transition yet — next tick we'll check again after walking
            return;
        }

        // Found an NPC — talk to them
        log.info("Found escort NPC: {}. Talking...", escortNpc.getName());
        boolean talked = Rs2Npc.interact(escortNpc, "Escort");

        if (talked) {
            // Wait for the dialogue to open (up to 5 seconds)
            boolean dialogueOpened = sleepUntil(Rs2Dialogue::isInDialogue, 5000);
            if (dialogueOpened) {
                setState(TempleTrekkingState.SELECT_ROUTE);
            }
            // If dialogue didn't open, we'll try again next tick
        }
    }

    /**
     * STATE: SELECT_ROUTE
     *
     * Goal: Navigate through the NPC dialogue and select Route 1 (easy route).
     *
     * The dialogue tree for Temple Trekking escort NPCs typically goes:
     *   NPC: "Where would you like to go?"
     *   → Player chooses from Route 1 / Route 2 / Route 3
     *
     * We click through any "Continue" prompts first, then select Route 1.
     */
    private void handleSelectRoute() {
        if (!Rs2Dialogue.isInDialogue()) {
            // Dialogue closed unexpectedly — go back to start
            log.warn("Dialogue closed unexpectedly during route selection. Restarting...");
            setState(TempleTrekkingState.START_TREK);
            return;
        }

        // If there's a "Click here to continue" prompt, press space to advance
        if (Rs2Dialogue.hasContinue()) {
            Rs2Dialogue.clickContinue();
            sleep(400, 700); // Small delay so the next dialogue widget loads
            return;
        }

        // If we see route options, pick Route 1
        if (Rs2Dialogue.hasSelectAnOption()) {
            // Try clicking "Route 1" — the exact text may contain additional info like "(Easy)"
            // We use partial match (false = not exact) so "Route 1" matches "Route 1 (Easy Path)"
            boolean clicked = Rs2Dialogue.clickOption("Route 1");

            if (!clicked) {
                // Route 1 not found — log the available options for debugging
                log.warn("Could not find 'Route 1' dialogue option. Available options: {}",
                        Rs2Dialogue.getDialogueOptions());
                // Wait a tick and try again
                return;
            }

            log.info("Selected Route 1. Waiting for trek to begin...");

            // Wait until the dialogue closes (trek starts loading)
            sleepUntil(() -> !Rs2Dialogue.isInDialogue(), 8000);
            setState(TempleTrekkingState.TREKKING);
        }
    }

    /**
     * STATE: TREKKING
     *
     * Goal: Wait while the game is between events.
     * Transition to DETECT_EVENT the moment anything unusual appears.
     *
     * For now this is a placeholder — in Phase 2 we'll add real detection logic.
     * The key signals to watch for:
     *   - Enemies spawning in the area
     *   - Puzzle objects appearing (broken bridge, river without bridge, vine trees)
     *   - The trek completion screen
     *   - Reward token appearing in inventory
     */
    private void handleTrekking() {
        // Check if the trek has completed (reward token in inventory)
        if (Rs2Inventory.contains("Reward token")) {
            log.info("Reward token detected — trek complete!");
            setState(TempleTrekkingState.TREK_COMPLETE);
            return;
        }

        // TODO Phase 2: detect when an event area loads and transition to DETECT_EVENT
        // For now, log that we're trekking and wait
        // This prevents spam — only log occasionally
        if (Math.random() < 0.05) { // ~5% chance per tick = roughly every 12 seconds
            log.debug("Trekking — watching for events...");
        }
    }

    /**
     * STATE: DETECT_EVENT
     *
     * Goal: Figure out which type of event we've entered and route to the right handler.
     *
     * This is the event router. It checks what's present in the environment
     * and transitions to the appropriate handler state.
     *
     * Placeholder for Phase 2 — event detection logic will go here.
     */
    private void handleDetectEvent() {
        log.info("Detecting event type...");

        // TODO Phase 2: Implement event detection
        // For now, log and go back to TREKKING as a safe fallback
        // Once we implement detection, this will route to the right handler

        // Example of what this will look like:
        // if (isEnemiesPresent()) { setState(EVADE_COMBAT); return; }
        // if (isBridgeWithTrees()) { setState(BRIDGE_CHOP_TREES); return; }
        // if (isBridgeWithoutTrees()) { setState(BRIDGE_KILL_ZOMBIES); return; }
        // if (isRiverCrossing()) { setState(RIVER_CROSSING); return; }
        // setState(UNKNOWN_EVENT);

        setState(TempleTrekkingState.TREKKING); // Placeholder
    }

    /**
     * STATE: EVADE_COMBAT
     *
     * Goal: Find the "Evade-event" path object and click it.
     *
     * On Route 1, all combat events have an evade path — a set of tiles
     * that, when clicked, skip the combat and continue the trek.
     *
     * Placeholder for Phase 2.
     */
    private void handleEvadeCombat() {
        log.info("Evading combat event...");
        // TODO Phase 2: Find and click the evade path object
        setState(TempleTrekkingState.TREKKING);
    }

    /**
     * STATE: BRIDGE_CHOP_TREES
     *
     * Goal: Chop trees → pick up logs → use logs on bridge gaps.
     *
     * Placeholder for Phase 3.
     */
    private void handleBridgeChopTrees() {
        log.info("Handling bridge (chop trees) event...");
        // TODO Phase 3: Implement tree chopping and bridge repair
        setState(TempleTrekkingState.CONTINUE_TREK);
    }

    /**
     * STATE: BRIDGE_KILL_ZOMBIES
     *
     * Goal: Wait for Undead Lumberjacks → kill them → loot planks → repair bridge.
     *
     * Placeholder for Phase 3.
     */
    private void handleBridgeKillZombies() {
        log.info("Handling bridge (kill lumberjacks) event...");
        // TODO Phase 3: Implement zombie killing and bridge repair
        setState(TempleTrekkingState.CONTINUE_TREK);
    }

    /**
     * STATE: RIVER_CROSSING
     *
     * Goal: Cut 3 vines → combine into long vine → throw at branch → swing across.
     *
     * Placeholder for Phase 4.
     */
    private void handleRiverCrossing() {
        log.info("Handling river crossing event...");
        // TODO Phase 4: Implement vine cutting and river crossing
        setState(TempleTrekkingState.CONTINUE_TREK);
    }

    /**
     * STATE: CONTINUE_TREK
     *
     * Goal: Find the blue "Continue-trek" path stones and click them.
     *
     * After every event resolves, there are blue stone tiles at the end
     * of the event area that advance the trek. Clicking them returns us
     * to the main path and may trigger the next event.
     *
     * Placeholder for Phase 2.
     */
    private void handleContinueTrek() {
        log.info("Continuing trek...");
        // TODO Phase 2: Find and click the "Continue-trek" path object
        setState(TempleTrekkingState.TREKKING);
    }

    /**
     * STATE: TREK_COMPLETE
     *
     * Goal: The follower gave us a reward token. Increment the counter,
     * then loop back to start the next trek.
     *
     * For now we just leave the token in inventory as requested.
     */
    private void handleTrekComplete() {
        trekCount++;
        log.info("Trek {} complete! Reward token in inventory. Starting next trek...", trekCount);

        // Brief pause before starting next trek (feels more human-like)
        sleep(1500, 2500);

        setState(TempleTrekkingState.START_TREK);
    }

    /**
     * STATE: UNKNOWN_EVENT
     *
     * Goal: Something we don't recognize happened. Log it and try to recover.
     */
    private void handleUnknownEvent() {
        log.warn("Unknown event detected. Waiting 3 seconds before retrying detection...");
        sleep(3000);
        setState(TempleTrekkingState.DETECT_EVENT);
    }

    // -------------------------------------------------------------------------
    // Utility methods
    // -------------------------------------------------------------------------

    /**
     * Helper to find the first visible escort NPC from a list of possible names.
     *
     * @param names Array of NPC names to search for (any one of them will do)
     * @return The first matching NPC found, or null if none are visible
     */
    private Rs2NpcModel findEscortNpc(String[] names) {
        for (String name : names) {
            Rs2NpcModel npc = Rs2Npc.getNpc(name);
            if (npc != null) {
                return npc;
            }
        }
        return null;
    }

    /**
     * Central state transition method.
     *
     * Using a single method for all transitions means we can add logging,
     * debugging, or validation in one place later.
     *
     * @param newState The state to transition to
     */
    private void setState(TempleTrekkingState newState) {
        if (currentState != newState) {
            log.info("State: {} → {}", currentState, newState);
            currentState = newState;
        }
    }

    // -------------------------------------------------------------------------
    // Shutdown
    // -------------------------------------------------------------------------

    /**
     * Called by TempleTrekkingPlugin.shutDown().
     *
     * ALWAYS call super.shutdown() — it cancels the scheduled executor
     * and cleans up Microbot's internal state.
     */
    @Override
    public void shutdown() {
        super.shutdown();
        currentState = TempleTrekkingState.IDLE;
        log.info("Temple Trekking script shut down. Total treks completed: {}", trekCount);
    }
}
