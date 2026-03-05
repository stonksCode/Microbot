package net.runelite.client.plugins.microbot.templetrekking;

/**
 * All possible states the Temple Trekking bot can be in at any given time.
 *
 * Think of this as a map of every "mode" the bot can operate in.
 * The script holds exactly ONE of these at a time, and transitions
 * between them based on what happens in the game.
 *
 * Flow overview:
 *
 *   IDLE
 *    └─► START_TREK        (talk to an escort NPC)
 *         └─► SELECT_ROUTE  (choose Route 1 from dialogue)
 *              └─► TREKKING (walking between events, waiting)
 *                   └─► DETECT_EVENT (something appeared — what is it?)
 *                        ├─► EVADE_COMBAT       (click the evade path)
 *                        ├─► BRIDGE_CHOP_TREES  (chop trees, use logs on gaps)
 *                        ├─► BRIDGE_KILL_ZOMBIES (kill undead lumberjacks, loot planks)
 *                        ├─► RIVER_CROSSING     (cut 3 vines, combine, throw, swing)
 *                        └─► CONTINUE_TREK      (walk to the blue "Continue" stones)
 *                             └─► TREK_COMPLETE  (reward token received — restart loop)
 *                                  └─► back to START_TREK
 */
public enum TempleTrekkingState {

    /** Bot is doing nothing. Initial state before the script starts. */
    IDLE,

    /** Walk to and talk to an escort NPC at Paterdomus (Burgh de Rott Ramble start). */
    START_TREK,

    /** A dialogue is open — select "Route 1" (easy route). */
    SELECT_ROUTE,

    /**
     * No event is active. We're between events, just waiting for the next one
     * or walking along the path. The bot idles here until an event is detected.
     */
    TREKKING,

    /**
     * Something changed in the area — enemies, objects, or puzzles appeared.
     * We need to figure out WHICH event it is and transition to the right handler.
     */
    DETECT_EVENT,

    /**
     * A combat event appeared. On Route 1 we can use the evade path.
     * Find the "Evade-event" path object and click it.
     */
    EVADE_COMBAT,

    /**
     * Bridge event: trees are present.
     * Steps: chop trees → pick up logs → use logs on bridge gaps.
     */
    BRIDGE_CHOP_TREES,

    /**
     * Bridge event: no trees, undead lumberjacks spawn from the river.
     * Steps: wait for lumberjacks → kill them → loot planks → repair bridge.
     */
    BRIDGE_KILL_ZOMBIES,

    /**
     * River crossing event.
     * Steps: find knife (or use inventory knife) → cut vine 3 times →
     *        combine vines into long vine → throw at branch → swing across.
     */
    RIVER_CROSSING,

    /**
     * Event is done. Walk to the blue path stones and click "Continue-trek"
     * to move forward on the route.
     */
    CONTINUE_TREK,

    /**
     * The trek finished successfully! The follower gave us a reward token.
     * For now we leave the token in inventory and go back to start the next trek.
     */
    TREK_COMPLETE,

    /**
     * Something went wrong that we don't know how to handle — unknown event type,
     * unexpected game state, NPC not found, etc.
     * Log the problem, wait a moment, then try to recover by re-detecting the event.
     */
    UNKNOWN_EVENT
}
