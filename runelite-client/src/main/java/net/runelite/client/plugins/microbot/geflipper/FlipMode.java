package net.runelite.client.plugins.microbot.geflipper;

public enum FlipMode {
    HIGH_VOLUME("High Volume", "Fast flips, lower margin per unit"),
    HIGH_MARGIN("High Margin", "Slower flips, higher profit per unit"),
    BALANCED("Balanced", "Mix of volume and margin"),
    CUSTOM_LIST("Custom List", "Use user-defined item names only");

    private final String displayName;
    private final String description;

    FlipMode(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    @Override
    public String toString() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }
}
