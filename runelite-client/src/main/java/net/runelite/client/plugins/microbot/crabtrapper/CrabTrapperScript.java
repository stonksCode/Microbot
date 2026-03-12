package net.runelite.client.plugins.microbot.crabtrapper;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.api.AnimationID;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * CrabTrapperScript
 *
 * Trap state detection is entirely name-based. Rs2TileObjectModel.getName()
 * calls getObjectDefinition().getImpostor() on the client thread, which
 * correctly resolves varbit-driven name changes (empty → baited → full).
 *
 * We scan all objects within SCAN_RADIUS, materialise the list, then call
 * getName() on each element individually (outside the stream) to avoid
 * client-thread interruption errors inside stream predicates.
 *
 * State names:
 *   "Crab trap (empty)"  → needs baiting
 *   "Crab trap (baited)" → waiting for a crab
 *   "Crab trap (full)"   → ready to collect
 */
public class CrabTrapperScript extends Script {

    private static final String TAG = "[CrabTrapper]";

    // -------------------------------------------------------------------------
    // Item IDs — verified from in-game inventory dump
    // -------------------------------------------------------------------------
    private static final int ITEM_FISH_OFFCUTS  = 11334;
    private static final int ITEM_RED_CRAB      = 31671;
    private static final int ITEM_CRAB_PASTE    = 31708;
    private static final int ITEM_RAW_CRAB_MEAT = 31686;
    private static final int ITEM_PESTLE        = 233;
    private static final int ITEM_KNIFE         = 946;

    private static final WorldPoint BANK_COORD      = new WorldPoint(3039, 3000, 0);
    private static final int        TICK_MS          = 600;
    private static final int        RELAXED_BASE_MS  = 3 * TICK_MS; // 1800ms
    private static final int        RED_CRAB_LVL_REQ = 21;
    private static final int        WALK_DISTANCE    = 3;  // tiles before walking

    // -------------------------------------------------------------------------
    // Enums
    // -------------------------------------------------------------------------
    public enum CrabType      { RED_CRAB }
    public enum InventoryMode { BANK, PASTE, CUT }
    public enum TimingMode    { RELAXED, IMMEDIATE, RANDOM }

    private enum State {
        INIT, BAIT_TRAPS, WAIT_FOR_FULL, COLLECT_TRAPS, PROCESS_INVENTORY, BANK_RUN, STOP
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------
    private CrabType      crabType      = CrabType.RED_CRAB;
    private InventoryMode inventoryMode = InventoryMode.PASTE;
    private TimingMode    timingMode    = TimingMode.RANDOM;

    private State        currentState = State.INIT;
    private String       stopReason   = "";
    private final Random random       = new Random();

    private boolean currentBlockRelaxed = true;
    private int     blockCyclesLeft     = 0;

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------
    public boolean run(CrabTrapperConfig config) {
        this.crabType      = config.crabType();
        this.inventoryMode = config.inventoryMode();
        this.timingMode    = config.timingMode();

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(
                this::tick, 0, TICK_MS, TimeUnit.MILLISECONDS);
        return true;
    }

    @Override
    public void shutdown() {
        super.shutdown();
    }

    // -------------------------------------------------------------------------
    // Tick dispatcher — runs every 600ms
    // -------------------------------------------------------------------------
    private void tick() {
        try {
            if (!Microbot.isLoggedIn()) return;
            switch (currentState) {
                case INIT:              handleInit();             break;
                case BAIT_TRAPS:        handleBaitTraps();        break;
                case WAIT_FOR_FULL:     handleWaitForFull();      break;
                case COLLECT_TRAPS:     handleCollectTraps();     break;
                case PROCESS_INVENTORY: handleProcessInventory(); break;
                case BANK_RUN:          handleBankRun();          break;
                case STOP:              handleStop();             break;
            }
        } catch (Exception e) {
            log(TAG + " Error in " + currentState + ": " + e.getMessage());
            stopReason = "Error: " + e.getMessage();
            currentState = State.STOP;
        }
    }

    // -------------------------------------------------------------------------
    // INIT
    // -------------------------------------------------------------------------
    private void handleInit() {
        log(TAG + " Initialising...");

        // Wait for inventory cache
        sleepUntil(() -> !Rs2Inventory.all().isEmpty(), 10000);

        int lvl = Microbot.getClient().getRealSkillLevel(net.runelite.api.Skill.HUNTER);
        if (lvl < RED_CRAB_LVL_REQ) {
            stop("Hunter level too low (" + lvl + "/" + RED_CRAB_LVL_REQ + ")"); return;
        }
        if (!Rs2Inventory.contains(ITEM_FISH_OFFCUTS)) {
            stop("No fish offcuts in inventory"); return;
        }
        if (inventoryMode == InventoryMode.PASTE && !Rs2Inventory.contains(ITEM_PESTLE)) {
            stop("Paste mode requires Pestle and Mortar"); return;
        }
        if (inventoryMode == InventoryMode.CUT && !Rs2Inventory.contains(ITEM_KNIFE)) {
            stop("Cut mode requires a Knife"); return;
        }

        // Confirm at least one of our trap coords has a visible crab trap
        boolean anyFound = false;
        for (WorldPoint coord : MY_TRAP_COORDS) {
            if (getTrapAt(coord) != null) { anyFound = true; break; }
        }
        if (!anyFound) {
            stop("No crab traps found — stand near the traps before starting"); return;
        }

        if (timingMode == TimingMode.RANDOM) rollTimingBlock();

        log(TAG + " Ready. Mode=" + inventoryMode + " Timing=" + timingMode
                + " MaxTraps=" + getMaxTraps());
        currentState = State.BAIT_TRAPS;
    }

    // -------------------------------------------------------------------------
    // BAIT_TRAPS
    // Iterates the 3 spots. Skips spots that are already baited/full.
    // Detects bait success by waiting for the player animation to start then finish,
    // since the empty trap object keeps the same ID after baiting.
    // -------------------------------------------------------------------------
    private void handleBaitTraps() {
        // If inventory is full we can't pick up crabs, so there's nothing useful
        // baiting can accomplish — skip ahead to process/bank.
        if (Rs2Inventory.isFull()) {
            log(TAG + " Inventory full at start of BAIT_TRAPS — processing.");
            currentState = State.PROCESS_INVENTORY;
            return;
        }

        int cap    = getMaxTraps();
        int active = countActive();

        log(TAG + " BAIT_TRAPS active=" + active + "/" + cap);

        while (active < cap) {
            Rs2TileObjectModel emptyTrap = findEmptyTrap();
            if (emptyTrap == null) break;

            WorldPoint coord = emptyTrap.getWorldLocation();
            final int trapId = emptyTrap.getId();
            log(TAG + " Baiting id=" + trapId + " at " + coord);
            walkTo(coord);
            emptyTrap.click("Bait");

            // Wait for animation to start (confirms server accepted the bait action)
            boolean animStarted = sleepUntil(
                    () -> Rs2Player.lastAnimationID != AnimationID.IDLE, 2500);
            if (!animStarted) {
                log(TAG + " No bait animation — skipping spot.");
                break;
            }
            log(TAG + " Baited (anim=" + Rs2Player.lastAnimationID + ") at " + coord);

            // Wait for the trap name to change away from empty — confirms the state
            // transition happened so findEmptyTrap() won't return the same trap again.
            // getName() is called outside sleepUntil to avoid client thread issues.
            boolean stateChanged = false;
            for (int i = 0; i < 10; i++) { // up to 3s (10 x 300ms)
                sleep(300);
                String name = getTrapName(coord);
                if (!"Crab trap (empty)".equalsIgnoreCase(name)) {
                    stateChanged = true;
                    log(TAG + " Trap " + trapId + " is now: " + name);
                    break;
                }
            }
            if (!stateChanged) {
                log(TAG + " Trap " + trapId + " name did not change — may still be empty.");
            }
            active++;
        }

        currentState = State.WAIT_FOR_FULL;
    }

    // -------------------------------------------------------------------------
    // WAIT_FOR_FULL
    // Polls tightly (every tick = 600ms) checking each spot for a full trap.
    // A spot is "full" when getNonEmptyObjectAt() returns an object named
    // "Crab trap (full)". No sleep — the scheduler drives the cadence.
    // -------------------------------------------------------------------------
    private int waitPollCount = 0;

    private void handleWaitForFull() {
        Rs2TileObjectModel full = findFullTrap();
        if (full != null) {
            log(TAG + " Full trap found (id=" + full.getId() + ") after " + waitPollCount + " polls.");
            waitPollCount = 0;
            if (shouldWaitRelaxed()) {
                int waitMs = RELAXED_BASE_MS + (random.nextInt(3) * TICK_MS);
                log(TAG + " Relaxed wait " + waitMs + "ms.");
                sleep(waitMs);
            }
            currentState = State.COLLECT_TRAPS;
        } else {
            waitPollCount++;
            if (waitPollCount % 5 == 1) { // log every ~3s
                StringBuilder sb = new StringBuilder();
                sb.append(TAG).append(" poll#").append(waitPollCount).append(" [");
                for (int i = 0; i < MY_TRAP_COORDS.length; i++) {
                    String n = getNameAt(MY_TRAP_COORDS[i]);
                    sb.append(i > 0 ? ", " : "").append(n != null ? n : "null");
                }
                sb.append("]");
                log(sb.toString());
            }
        }
        // No sleep — 600ms scheduler drives next poll.
    }

    // -------------------------------------------------------------------------
    // COLLECT_TRAPS
    // Empties every full trap, then moves on.
    // -------------------------------------------------------------------------
    private void handleCollectTraps() {
        // Check inventory before collecting — if full, process immediately
        if (Rs2Inventory.isFull()) {
            log(TAG + " Inventory full — processing before collecting.");
            currentState = State.PROCESS_INVENTORY;
            return;
        }

        Rs2TileObjectModel full = findFullTrap();

        while (full != null) {
            // Re-check inventory before each trap
            if (Rs2Inventory.isFull()) {
                log(TAG + " Inventory full mid-collect — processing.");
                currentState = State.PROCESS_INVENTORY;
                return;
            }

            final int trapId = full.getId();
            WorldPoint coord = full.getWorldLocation();
            log(TAG + " Emptying trap id=" + trapId + " at " + coord);
            walkTo(coord);
            full.click("Empty");

            // Wait for the trap name to change away from full (simple sleep, no lambda getName)
            sleep(2400); // 4 ticks — enough for the server to process and update the name

            full = findFullTrap();
        }

        log(TAG + " Done collecting.");

        if (timingMode == TimingMode.RANDOM) {
            if (--blockCyclesLeft <= 0) rollTimingBlock();
        }

        currentState = State.PROCESS_INVENTORY;
    }

    // How many crabs should accumulate before we process/bank.
    // Inventory holds 28 slots; pestle + knife each take 1. We process when
    // crabs fill all remaining slots (i.e. inventory is full) OR the inventory
    // is full for BANK mode. For PASTE/CUT we also allow an early flush when
    // there are no offcuts left and we still have crabs.
    private boolean shouldProcessNow() {
        int crabCount = Rs2Inventory.count(ITEM_RED_CRAB);
        if (crabCount == 0) return false;
        // Always process if inventory is full (can't collect more)
        if (Rs2Inventory.isFull()) return true;
        // Process if we're out of offcuts — can't bait more traps anyway
        if (!Rs2Inventory.contains(ITEM_FISH_OFFCUTS)) return true;
        // For BANK mode: only bank when full (avoid excess trips)
        if (inventoryMode == InventoryMode.BANK) return Rs2Inventory.isFull();
        // For PASTE/CUT: process when full
        return Rs2Inventory.isFull();
    }

    // -------------------------------------------------------------------------
    // PROCESS_INVENTORY
    // -------------------------------------------------------------------------
    private void handleProcessInventory() {
        if (!Rs2Inventory.contains(ITEM_RED_CRAB)) {
            // No crabs to process. If inventory is still full there's nothing we
            // can do locally — we need a bank run to make space.
            if (Rs2Inventory.isFull()) {
                log(TAG + " No crabs but inventory full — bank run to make space.");
                currentState = State.BANK_RUN;
            } else {
                log(TAG + " No crabs — back to baiting.");
                currentState = State.BAIT_TRAPS;
            }
            return;
        }
        if (!shouldProcessNow()) {
            log(TAG + " Only " + Rs2Inventory.count(ITEM_RED_CRAB) + " crabs — back to baiting.");
            currentState = State.BAIT_TRAPS;
            return;
        }
        switch (inventoryMode) {
            case PASTE: handleCrushCrabs(); break;
            case CUT:   handleCutCrabs();   break;
            case BANK:  currentState = State.BANK_RUN; break;
        }
    }

    private void handleCrushCrabs() {
        int total = Rs2Inventory.count(ITEM_RED_CRAB);
        log(TAG + " Crushing crabs (" + total + "x).");
        Rs2Inventory.combine(ITEM_PESTLE, ITEM_RED_CRAB);
        // Wait for the game to process all crabs — one click processes the whole stack
        sleepUntil(() -> !Rs2Inventory.contains(ITEM_RED_CRAB), total * 1800);
        log(TAG + " Crushing done.");
        currentState = State.BAIT_TRAPS;
    }

    private void handleCutCrabs() {
        int total = Rs2Inventory.count(ITEM_RED_CRAB);
        log(TAG + " Cutting crabs (" + total + "x).");
        Rs2Inventory.combine(ITEM_KNIFE, ITEM_RED_CRAB);
        // Wait for the game to process all crabs — one click processes the whole stack
        sleepUntil(() -> !Rs2Inventory.contains(ITEM_RED_CRAB), total * 1800);
        log(TAG + " Cutting done.");
        // Only bank if we actually have meat to deposit
        currentState = Rs2Inventory.contains(ITEM_RAW_CRAB_MEAT)
                ? State.BANK_RUN : State.BAIT_TRAPS;
    }

    // -------------------------------------------------------------------------
    // BANK_RUN — uses the Bank Crab game object (ID 58094) at (3039, 3000)
    // It is a GameObject, NOT an NPC — interact via Rs2TileObjectModel.click().
    // -------------------------------------------------------------------------
    private static final int OBJ_BANK_CRAB    = 58094;
    private static final int BANK_OPEN_RETRIES = 3;

    /** Finds the Bank Crab game object within 5 tiles of BANK_COORD. */
    private Rs2TileObjectModel findBankCrab() {
        java.util.List<Rs2TileObjectModel> nearby = Microbot.getRs2TileObjectCache().query()
                .within(BANK_COORD, 5)
                .toList();
        for (Rs2TileObjectModel obj : nearby) {
            if (obj.getId() == OBJ_BANK_CRAB) return obj;
        }
        return null;
    }

    private void handleBankRun() {
        log(TAG + " Walking to Bank Crab.");
        Rs2Walker.walkTo(BANK_COORD);
        sleepUntil(() -> Rs2Player.getWorldLocation().distanceTo(BANK_COORD) <= 5, 20000);

        boolean opened = false;
        for (int attempt = 1; attempt <= BANK_OPEN_RETRIES; attempt++) {
            Rs2TileObjectModel bankCrab = findBankCrab();
            if (bankCrab == null) {
                log(TAG + " Bank Crab object not found (attempt " + attempt + "), retrying...");
                sleep(800, 1200);
                continue;
            }
            log(TAG + " Clicking Bank Crab (attempt " + attempt + "/" + BANK_OPEN_RETRIES + ").");
            bankCrab.click("Bank");
            sleepUntil(Rs2Bank::isOpen, 4000);
            if (Rs2Bank.isOpen()) { opened = true; break; }
            log(TAG + " Bank open failed (attempt " + attempt + "/" + BANK_OPEN_RETRIES + "), retrying...");
            sleep(800, 1200);
        }
        if (!opened) {
            log(TAG + " Could not open bank after " + BANK_OPEN_RETRIES + " attempts — will retry next tick.");
            return;
        }

        Rs2Bank.depositAll(ITEM_RED_CRAB);
        Rs2Bank.depositAll(ITEM_RAW_CRAB_MEAT);
        sleep(300, 500);

        if (!Rs2Inventory.contains(ITEM_FISH_OFFCUTS)) {
            if (!Rs2Bank.hasItem(ITEM_FISH_OFFCUTS)) {
                Rs2Bank.closeBank();
                stop("Out of fish offcuts");
                return;
            }
            Rs2Bank.withdrawAll(ITEM_FISH_OFFCUTS);
            sleep(300, 500);
        }

        Rs2Bank.closeBank();
        sleepUntil(() -> !Rs2Bank.isOpen(), 3000);
        log(TAG + " Bank run complete.");
        currentState = State.BAIT_TRAPS;
    }

    // -------------------------------------------------------------------------
    // STOP
    // -------------------------------------------------------------------------
    private void handleStop() {
        log(TAG + " Stopped. Reason: " + stopReason);
        shutdown();
    }

    // -------------------------------------------------------------------------
    // Trap detection
    //
    // getName() on Rs2TileObjectModel correctly resolves varbit impostor names
    // (empty/baited/full) via getImpostor() on the client thread.
    //
    // We fetch all objects in the area as a List first, then call getName()
    // on each element individually (outside the stream) to avoid the
    // client-thread-in-predicate problem.
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Our 3 trap spots — hardcoded WorldPoints so we never interact with
    // other players' traps (e.g. 58888 @ 3037,2970 which is nearby but excluded).
    // -------------------------------------------------------------------------
    private static final WorldPoint[] MY_TRAP_COORDS = {
        new WorldPoint(3035, 2975, 0),
        new WorldPoint(3037, 2974, 0),
        new WorldPoint(3036, 2972, 0),
    };
    private static final int SPOT_RADIUS = 1; // within 1 tile of each coord

    /**
     * Returns the trap object at the given WorldPoint, or null if none.
     * Fetches all objects within SPOT_RADIUS, then calls getName() on each
     * individually outside the stream — safe from client thread issues.
     */
    private Rs2TileObjectModel getTrapAt(WorldPoint coord) {
        java.util.List<Rs2TileObjectModel> nearby = Microbot.getRs2TileObjectCache().query()
                .within(coord, SPOT_RADIUS)
                .toList();
        for (Rs2TileObjectModel obj : nearby) {
            String name = obj.getName();
            if (name != null && name.toLowerCase().startsWith("crab trap")) return obj;
        }
        return null;
    }

    /**
     * Returns the name of the trap at the given coord, or null.
     */
    private String getNameAt(WorldPoint coord) {
        Rs2TileObjectModel obj = getTrapAt(coord);
        return obj != null ? obj.getName() : null;
    }

    /**
     * Finds the first of MY traps that has the given name.
     */
    private Rs2TileObjectModel findMyTrap(String name) {
        for (WorldPoint coord : MY_TRAP_COORDS) {
            Rs2TileObjectModel obj = getTrapAt(coord);
            if (obj == null) continue;
            String n = obj.getName();
            if (name.equalsIgnoreCase(n)) return obj;
        }
        return null;
    }

    /** Finds the first of my traps that is empty (needs baiting). */
    private Rs2TileObjectModel findEmptyTrap() {
        return findMyTrap("Crab trap (empty)");
    }

    /** Finds the first of my traps that is full (ready to collect). */
    private Rs2TileObjectModel findFullTrap() {
        return findMyTrap("Crab trap (full)");
    }

    /** Counts my traps that are baited or full (active). */
    private int countActive() {
        int count = 0;
        for (WorldPoint coord : MY_TRAP_COORDS) {
            String name = getNameAt(coord);
            if ("Crab trap (baited)".equalsIgnoreCase(name)
                    || "Crab trap (full)".equalsIgnoreCase(name)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns the current name of the trap at the given coord.
     * Used to poll for state changes after baiting.
     */
    private String getTrapName(WorldPoint coord) {
        return getNameAt(coord);
    }



    // -------------------------------------------------------------------------
    // Movement
    // -------------------------------------------------------------------------
    private void walkTo(WorldPoint coord) {
        if (Rs2Player.getWorldLocation().distanceTo(coord) > WALK_DISTANCE) {
            Rs2Walker.walkTo(coord);
            sleepUntil(() -> Rs2Player.getWorldLocation().distanceTo(coord) <= WALK_DISTANCE, 8000);
        }
    }

    // -------------------------------------------------------------------------
    // Timing
    // -------------------------------------------------------------------------
    private void rollTimingBlock() {
        currentBlockRelaxed = random.nextInt(100) < 65;
        int cycles = currentBlockRelaxed ? (8 + random.nextInt(8)) : (3 + random.nextInt(4));
        blockCyclesLeft = cycles;
        log(TAG + " Timing: " + (currentBlockRelaxed ? "Relaxed" : "Immediate")
                + " for " + cycles + " cycles.");
    }

    private boolean shouldWaitRelaxed() {
        switch (timingMode) {
            case RELAXED:   return true;
            case IMMEDIATE: return false;
            case RANDOM:    return currentBlockRelaxed;
            default:        return true;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------
    private int getMaxTraps() {
        int lvl = Microbot.getClient().getRealSkillLevel(net.runelite.api.Skill.HUNTER);
        if (lvl >= 80) return 5;
        if (lvl >= 60) return 4;
        if (lvl >= 40) return 3;
        if (lvl >= 20) return 2;
        return 1;
    }

    private void stop(String reason) {
        log(TAG + " Stopping: " + reason);
        stopReason = reason;
        currentState = State.STOP;
    }

    private void log(String msg) {
        Microbot.log(msg);
    }
}
