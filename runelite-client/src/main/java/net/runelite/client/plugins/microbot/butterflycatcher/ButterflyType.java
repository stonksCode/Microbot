package net.runelite.client.plugins.microbot.butterflycatcher;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ButterflyType
 *
 * Defines all catchable butterflies from the Hunter skill.
 * Data sourced from: https://oldschool.runescape.wiki/w/Butterfly_(Hunter)
 *
 * Fields:
 *   displayName   – Human-readable name shown in the config dropdown.
 *   npcId         – NPC ID of the butterfly in the game world.
 *   levelRequired – Hunter level required to catch bare-handed.
 *   requiresNet   – Whether a butterfly net is required (bare-handed = false).
 *   jarItemId     – Item ID of the butterfly jar used for jar-based catching (-1 = none).
 *   caughtItemId  – Item ID received on a successful catch (-1 = not yet confirmed).
 *
 * NOTE: Only bare-handed catching is implemented in the current version.
 * requiresNet and jarItemId are reserved for future jar/bank expansion.
 */
@Getter
@RequiredArgsConstructor
public enum ButterflyType {

    RUBY_HARVEST(
            "Ruby Harvest",
            5525,
            15,
            false,
            10012,
            10009
    ),
    SAPPHIRE_GLACIALIS(
            "Sapphire Glacialis",
            5526,
            25,
            false,
            10013,
            10011
    ),
    SNOWY_KNIGHT(
            "Snowy Knight",
            5527,
            35,
            false,
            10014,
            10013
    ),
    BLACK_WARLOCK(
            "Black Warlock",
            5553,
            45,
            false,
            10015,
            10010
    );

    private final String  displayName;
    private final int     npcId;
    private final int     levelRequired;
    private final boolean requiresNet;
    private final int     jarItemId;
    private final int     caughtItemId;

    @Override
    public String toString() {
        return displayName;
    }
}
