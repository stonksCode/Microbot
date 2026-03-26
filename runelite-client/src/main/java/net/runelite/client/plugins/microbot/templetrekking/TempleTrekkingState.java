package net.runelite.client.plugins.microbot.templetrekking;

/**
 * All possible states the Temple Trekking bot can be in at any given time.
 *
 *   IDLE
 *    └─► START_TREK
 *         └─► SELECT_ROUTE
 *              └─► TREKKING
 *                   └─► DETECT_EVENT
 *                        ├─► EVADE_COMBAT
 *                        ├─► BRIDGE_CHOP_TREES
 *                        ├─► BRIDGE_KILL_ZOMBIES
 *                        └─► RIVER_CROSSING
 *                             └─► back to TREKKING → TREK_COMPLETE
 *                                  └─► CHECK_INVENTORY
 *                                       ├─► START_TREK          (slots OK, keep going)
 *                                       ├─► BANKING             (slots low + in Burgh)
 *                                       │    └─► START_TREK
 *                                       └─► REDEEM_TOKENS       (slots low, redeem mode)
 *                                            └─► USE_TOMES
 *                                                 └─► START_TREK
 */
public enum TempleTrekkingState {

    IDLE,
    START_TREK,
    SELECT_ROUTE,
    TREKKING,
    DETECT_EVENT,
    EVADE_COMBAT,
    BRIDGE_CHOP_TREES,
    BRIDGE_KILL_ZOMBIES,
    RIVER_CROSSING,
    CONTINUE_TREK,

    /**
     * Trek finished. Check reward token count + free slots before
     * deciding whether to bank, redeem, or start the next trek immediately.
     */
    TREK_COMPLETE,

    /**
     * Evaluate free inventory slots after a trek completes.
     * Routes to BANKING, REDEEM_TOKENS, or START_TREK.
     */
    CHECK_INVENTORY,

    /**
     * Walk to Burgh de Rott bank, deposit all reward tokens, return to escort NPC.
     * Only entered if the player is already in Burgh de Rott at the time of the check.
     */
    BANKING,

    /**
     * Redeem each reward token for an XP tome via the reward selection interface.
     * After all tokens are converted, transitions to USE_TOMES.
     */
    REDEEM_TOKENS,

    /**
     * Read (left-click) each skill tome in the inventory to claim the XP.
     * After all tomes are consumed, transitions to START_TREK.
     */
    USE_TOMES,

    UNKNOWN_EVENT
}
