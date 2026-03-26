package net.runelite.client.plugins.microbot.sailing;

public enum AlchOrder {
    /**
     * Alch items in the order they appear in the alch list config (original behaviour).
     * Processes all of one item name before moving to the next.
     */
    LIST_ORDER,

    /**
     * Left→right, top→bottom — inventory slot order (0, 1, 2, 3, 4, 5, ..., 27).
     * Sweeps each row from left to right before moving to the next row.
     */
    LEFT_TO_RIGHT,

    /**
     * Right→left, top→bottom — reverse of LEFT_TO_RIGHT (3, 2, 1, 0, 7, 6, 5, 4, ...).
     * Sweeps each row from right to left before moving to the next row.
     */
    RIGHT_TO_LEFT,

    /**
     * Top→bottom, left→right — sweeps each column top to bottom before moving to the next column.
     * Order: 0, 4, 8, 12, 16, 20, 24, 1, 5, 9, ...
     */
    TOP_TO_BOTTOM,

    /**
     * Bottom→top, left→right — sweeps each column bottom to top before moving to the next column.
     * Order: 24, 20, 16, 12, 8, 4, 0, 25, 21, 17, ...
     */
    BOTTOM_TO_TOP
}
