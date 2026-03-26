package net.runelite.client.plugins.microbot.sailing.features.salvaging;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.api.boat.Rs2BoatCache;
import net.runelite.client.plugins.microbot.api.player.models.Rs2PlayerModel;
import net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectCache;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.sailing.AlchOrder;
import net.runelite.client.plugins.microbot.sailing.SailingConfig;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

import javax.inject.Inject;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

@Slf4j
public class SalvagingScript {

    private static final int SIZE_SALVAGEABLE_AREA = 15;
    private static final int MIN_INVENTORY_FULL = 24;
    private static final int MAX_INVENTORY_FULL = 28;
    private static final int SALVAGE_TIMEOUT = 20000;
    private static final int DEPLOY_TIMEOUT = 5000;
    private static final int WAIT_TIME = 5000;
    private static final int WAIT_TIME_MAX = 10000;

    private final Rs2TileObjectCache tileObjectCache;
    private final Rs2BoatCache boatCache;

    // Set to true after salvage is deposited at the station so the next tick
    // runs the alch/drop cleanup loop instead of jumping back to salvaging.
    private boolean needsCleanup = false;

    @Inject
    public SalvagingScript(Rs2TileObjectCache tileObjectCache, Rs2BoatCache boatCache) {
        this.tileObjectCache = tileObjectCache;
        this.boatCache = boatCache;
    }

    public List<Rs2TileObjectModel> getActiveWrecks() {
        return tileObjectCache.query()
                .where(wreck -> SalvageObjectIds.ACTIVE_SHIPWRECK_IDS.contains(wreck.getId()))
                .toListOnClientThread();
    }

    public List<Rs2TileObjectModel> getInactiveWrecks() {
        return tileObjectCache.query()
                .where(wreck -> SalvageObjectIds.INACTIVE_SHIPWRECK_IDS.contains(wreck.getId()))
                .toListOnClientThread();
    }

    public void run(SailingConfig config) {
        try {
            var player = new Rs2PlayerModel();

            if (isPlayerAnimating(player)) {
                log.info("Currently salvaging, waiting...");
                sleep(WAIT_TIME, WAIT_TIME_MAX);
                return;
            }

            // If we just finished a salvage deposit, run the cleanup loop
            // regardless of whether inventory is technically "full".
            if (needsCleanup) {
                log.info("Post-salvage cleanup: running alch/drop loop");
                needsCleanup = false;
                runCleanup(config);
                return;
            }

            // Check inventory FIRST before deciding whether to salvage
            if (isInventoryFull()) {
                log.info("Inventory full, handling before salvaging");
                handleFullInventory(config, player);
                return;
            }

            // Inventory has space — go find a wreck and salvage
            var nearbyWreck = findNearestWreck(player.getWorldLocation());
            if (nearbyWreck == null) {
                log.info("No shipwreck found nearby");
                sleep(WAIT_TIME);
                return;
            }

            deploySalvagingHook(player);

        } catch (Exception ex) {
            log.error("Error in salvaging script", ex);
        }
    }

    private boolean isPlayerAnimating(Rs2PlayerModel player) {
        return player.getAnimation() != -1;
    }

    private boolean isInventoryFull() {
        return Rs2Inventory.count() >= Rs2Random.between(MIN_INVENTORY_FULL, MAX_INVENTORY_FULL);
    }

    private boolean hasSalvageItems() {
        return Rs2Inventory.count("salvage") > 0;
    }

    private Rs2TileObjectModel findNearestWreck(WorldPoint playerLocation) {
        var activeWrecks = getActiveWrecks();

        if (activeWrecks.isEmpty()) {
            log.info("No active shipwrecks found");
            sleep(WAIT_TIME);
            return null;
        }

        return activeWrecks.stream()
                .filter(wreck -> isWithinSalvageArea(playerLocation, wreck))
                .min(Comparator.comparingInt(wreck -> playerLocation.distanceTo(wreck.getWorldLocation())))
                .orElse(null);
    }

    private boolean isWithinSalvageArea(WorldPoint playerLocation, Rs2TileObjectModel wreck) {
        return playerLocation.distanceTo(wreck.getWorldLocation()) <= SIZE_SALVAGEABLE_AREA;
    }

    private void handleFullInventory(SailingConfig config, Rs2PlayerModel player) {
        if (hasSalvageItems() && !isPlayerAnimating(player)) {
            // Deposit salvage, then flag that we need to run the cleanup loop next tick
            depositSalvageOrDrop(config);
            needsCleanup = true;
        } else {
            runCleanup(config);
        }
    }

    private void runCleanup(SailingConfig config) {
        // 1. Drop junk first to make room for casket loot
        dropJunk(config);
        // 2. Open caskets — now there's space for the loot to land
        if (config.openCaskets()) {
            openCaskets();
        }
        // 3. Alch anything on the alch list (including new loot from caskets)
        if (config.enableAlching()) {
            alchItems(config);
        }
        // 4. Drop anything leftover from casket loot that's also on the drop list
        dropJunk(config);
    }

    private void depositSalvageOrDrop(SailingConfig config) {
        var salvagingStation = findSalvagingStation();

        if (salvagingStation != null) {
            depositAtStation(salvagingStation);
        } else {
            log.info("No salvaging station found, dropping junk items");
            dropJunk(config);
        }
    }

    private Rs2TileObjectModel findSalvagingStation() {
        var playerWorldView = new Rs2PlayerModel().getWorldView().getId();

        return tileObjectCache.query()
                .fromWorldView()
                .where(obj -> obj.getName() != null && obj.getName().equalsIgnoreCase("salvaging station"))
                .where(obj -> obj.getWorldView().getId() == playerWorldView)
                .nearestOnClientThread();
    }

    private void depositAtStation(Rs2TileObjectModel station) {
        station.click();
        sleepUntil(() -> !hasSalvageItems(), SALVAGE_TIMEOUT);
    }

    private void deploySalvagingHook(Rs2PlayerModel player) {
        var hook = tileObjectCache.query()
                .fromWorldView()
                .where(obj -> obj.getName() != null && obj.getName().toLowerCase().contains("salvaging hook"))
                .nearestOnClientThread();

        if (hook != null) {
            hook.click("Deploy");
            sleepUntil(() -> isPlayerAnimating(player), DEPLOY_TIMEOUT);
        }
    }

    private void openCaskets() {
        // Keep opening caskets until none remain.
        // We track the casket count before each open attempt — if the count doesn't
        // decrease after a 5 second wait, the open had no effect and we bail out
        // to avoid an infinite loop (e.g. inventory completely full with no room for loot).
        while (Rs2Inventory.hasItem("casket")) {
            int casketsBefore = Rs2Inventory.count("casket");
            log.info("Opening casket ({} casket(s) remaining)", casketsBefore);
            Rs2Inventory.interact("casket", "Open");

            // Wait until the casket count drops — this is the unambiguous signal
            // that one was consumed, regardless of what loot landed.
            sleepUntil(() -> Rs2Inventory.count("casket") < casketsBefore, 5000);

            // Safety: if the count didn't drop, the open had no effect — bail out
            if (Rs2Inventory.count("casket") >= casketsBefore) {
                log.warn("Casket open had no effect (inventory may be full), stopping casket loop");
                break;
            }

            sleep(300, 600);
        }
        log.info("All caskets opened");
    }

    private void alchItems(SailingConfig config) {
        var alchItems = config.alchItems();
        if (alchItems == null || alchItems.isBlank()) {
            return;
        }

        var itemNamesToAlch = Arrays.stream(alchItems.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(item -> !item.isEmpty())
                .collect(Collectors.toList());

        AlchOrder order = config.alchOrder();

        if (order == AlchOrder.LIST_ORDER) {
            // Original behaviour: burn through all of one item name before moving to the next
            for (String itemName : itemNamesToAlch) {
                while (Rs2Inventory.hasItem(itemName)) {
                    log.info("Alching (list order): {}", itemName);
                    Rs2Magic.alch(itemName);
                    Rs2Player.waitForXpDrop(Skill.MAGIC, 10000, false);
                }
            }
        } else {
            // Slot-based ordering modes.
            // The inventory is a 4-column x 7-row grid:
            //   column = slot % 4
            //   row    = slot / 4
            //
            // LEFT_TO_RIGHT  — sort by slot ascending          (row-major, left→right)
            // RIGHT_TO_LEFT  — sort by column DESC, then row   (row-major, right→left)
            // TOP_TO_BOTTOM  — sort by column ASC, then row    (column-major, top→bottom)
            // BOTTOM_TO_TOP  — sort by column ASC, then row DESC (column-major, bottom→top)
            //
            // We re-collect on every pass so the list stays accurate after each alch.
            final int COLUMNS = 4;
            Comparator<Rs2ItemModel> slotOrder;
            switch (order) {
                case RIGHT_TO_LEFT:
                    // Same rows as LEFT_TO_RIGHT but sweep right→left within each row
                    slotOrder = Comparator
                            .comparingInt((Rs2ItemModel i) -> i.getSlot() / COLUMNS)  // row ASC
                            .thenComparingInt(i -> -(i.getSlot() % COLUMNS));          // column DESC
                    break;
                case TOP_TO_BOTTOM:
                    // Sweep each column top→bottom before moving to the next column
                    slotOrder = Comparator
                            .comparingInt((Rs2ItemModel i) -> i.getSlot() % COLUMNS)  // column ASC
                            .thenComparingInt(i -> i.getSlot() / COLUMNS);             // row ASC
                    break;
                case BOTTOM_TO_TOP:
                    // Sweep each column bottom→top before moving to the next column
                    slotOrder = Comparator
                            .comparingInt((Rs2ItemModel i) -> i.getSlot() % COLUMNS)  // column ASC
                            .thenComparingInt(i -> -(i.getSlot() / COLUMNS));          // row DESC
                    break;
                default: // LEFT_TO_RIGHT
                    slotOrder = Comparator.comparingInt(Rs2ItemModel::getSlot);
                    break;
            }

            boolean alched;
            do {
                alched = false;
                List<Rs2ItemModel> candidates = Rs2Inventory.all().stream()
                        .filter(item -> itemNamesToAlch.stream()
                                .anyMatch(name -> item.getName().toLowerCase().contains(name)))
                        .sorted(slotOrder)
                        .collect(Collectors.toList());

                if (!candidates.isEmpty()) {
                    Rs2ItemModel next = candidates.get(0);
                    log.info("Alching ({}) slot {}: {}", order, next.getSlot(), next.getName());
                    Rs2Magic.alch(next);
                    Rs2Player.waitForXpDrop(Skill.MAGIC, 10000, false);
                    alched = true;
                }
            } while (alched);
        }
    }

    private void dropJunk(SailingConfig config) {
        var dropItems = config.dropItems();
        if (dropItems == null || dropItems.isBlank()) {
            return;
        }

        var junkItems = Arrays.stream(dropItems.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toArray(String[]::new);

        if (junkItems.length > 0) {
            Rs2Inventory.dropAll(junkItems);
        }
    }
}
