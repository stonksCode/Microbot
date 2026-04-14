package net.runelite.client.plugins.microbot.butterflycatcher;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

import java.util.concurrent.TimeUnit;

/**
 * ButterflyCatcherScript
 *
 * Bare-handed butterfly catching for Hunter XP.
 *
 * Logic per tick:
 *   1. Guard checks (logged in, not paused, not blocking event).
 *   2. Validate Hunter level against the selected butterfly's requirement.
 *   3. If the player is already animating (mid-catch) OR moving toward a
 *      butterfly they already clicked, skip — let the game resolve it.
 *   4. Find the nearest butterfly of the configured type by NPC ID.
 *   5. Click "Catch" on it and wait briefly for movement or animation to start.
 *
 * Human-like behaviour note:
 *   Real players click once and let the character chase the butterfly down.
 *   We replicate this by only re-clicking when the player is fully idle
 *   (neither animating nor moving). This prevents spam-clicking on a butterfly
 *   that is already being chased.
 *
 * Future expansion hooks are noted with TODO comments:
 *   - Jar mode: carry butterfly jars, click "Catch" with jar selected.
 *   - Bank mode: walk to bank, deposit jars, withdraw empties.
 *   - Net mode: equip butterfly net before catching.
 *   - Release mode: release caught butterflies from inventory to free space.
 */
@Slf4j
public class ButterflyCatcherScript extends Script {

    private static final String TAG  = "[ButterflyCatcher]";
    private static final int    TICK = 600; // ms per game tick

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------
    private ButterflyType targetButterfly;

    /**
     * Whether we have committed a "Catch" click this cycle.
     * Cleared once the player returns to a fully idle state (not moving,
     * not animating), meaning the previous attempt has fully resolved.
     */
    private boolean catchCommitted = false;

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------
    public boolean run(ButterflyCatcherConfig config) {
        this.targetButterfly = config.butterflyType();

        Microbot.log(TAG + " Starting — target: " + targetButterfly.getDisplayName()
                + " (NPC ID " + targetButterfly.getNpcId() + ")"
                + " | Required level: " + targetButterfly.getLevelRequired());

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(
                this::tick, 0, TICK, TimeUnit.MILLISECONDS);
        return true;
    }

    @Override
    public void shutdown() {
        super.shutdown();
    }

    // -------------------------------------------------------------------------
    // Main tick
    // -------------------------------------------------------------------------
    private void tick() {
        try {
            if (!Microbot.isLoggedIn()) return;
            if (!super.run()) return; // handles blocking events, pause state, run energy

            // Level check — warn and bail if under-levelled
            int hunterLevel = Microbot.getClient().getRealSkillLevel(Skill.HUNTER);
            if (hunterLevel < targetButterfly.getLevelRequired()) {
                Microbot.log(TAG + " Hunter level too low ("
                        + hunterLevel + " / " + targetButterfly.getLevelRequired()
                        + ") for " + targetButterfly.getDisplayName() + ". Stopping.");
                shutdown();
                return;
            }

            boolean animating = Rs2Player.isAnimating();
            boolean moving    = Rs2Player.isMoving();

            // If we already committed a click, check if the attempt has resolved.
            // The attempt is considered resolved when the player is fully idle
            // (not moving toward the butterfly AND not mid-catch animation).
            if (catchCommitted) {
                if (animating || moving) {
                    // Still chasing or catching — leave it alone, just like a human would.
                    log.debug(TAG + " Committed — animating={} moving={}, waiting.", animating, moving);
                    return;
                }
                // Fully idle: previous attempt has resolved (caught or lost the butterfly).
                catchCommitted = false;
                log.debug(TAG + " Attempt resolved, looking for next butterfly.");
            }

            // Extra safety: even without a committed click, don't interrupt an
            // animation already in progress (e.g. plugin started mid-catch).
            if (animating) return;

            // Find nearest butterfly of the selected type
            Rs2NpcModel butterfly = Microbot.getRs2NpcCache().query()
                    .withId(targetButterfly.getNpcId())
                    .nearest();

            if (butterfly == null) {
                // Nothing in render range — log occasionally so the console isn't
                // flooded when in a sparse area.
                log.debug(TAG + " No {} nearby — waiting.", targetButterfly.getDisplayName());
                return;
            }

            // Click "Catch" — Rs2NpcModel.click() handles camera rotation,
            // walk-into-reach, and menu entry dispatch.
            boolean clicked = butterfly.click("Catch");
            if (!clicked) {
                log.warn(TAG + " Click failed on {} at {}",
                        targetButterfly.getDisplayName(), butterfly.getWorldLocation());
                return;
            }

            // Mark that we have committed to this butterfly so we don't re-click
            // while the player is walking toward it.
            catchCommitted = true;

            log.debug(TAG + " Clicked Catch on {} at {}",
                    targetButterfly.getDisplayName(), butterfly.getWorldLocation());

            // Wait up to 2.5 ticks for movement or the catch animation to start.
            // This prevents re-clicking before the server even responds.
            sleepUntil(() -> Rs2Player.isAnimating() || Rs2Player.isMoving(), 1500);

        } catch (Exception e) {
            log.error(TAG + " Unexpected error in tick: {}", e.getMessage(), e);
        }
    }
}
