package com.duntale.zsquad.camera;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.InteractionChain;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.InteractionManager;
import com.hypixel.hytale.server.core.modules.interaction.InteractionModule;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Map;

/**
 * Handles attack execution for the click-to-move system.
 *
 * <p>Creates a single {@link InteractionContext} per call to determine weapon
 * type (melee/ranged) and execute the attack chain, avoiding duplicate
 * allocations.</p>
 */
final class AttackHandler {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Result of an attack attempt. */
    enum AttackResult {
        /** Attack was executed (or silently throttled). Caller should stop movement. */
        ATTACKED,
        /** Weapon is melee and target is out of range. Caller should walk toward target. */
        OUT_OF_RANGE
    }

    private AttackHandler() {} // utility class

    /**
     * Attempts to attack using the player's Primary interaction chain.
     *
     * <p>Builds a single {@link InteractionContext} to resolve the held weapon's
     * {@link RootInteraction}, check ranged tags, and optionally queue the attack
     * chain — all in one pass.</p>
     *
     * <p>A lightweight time-based throttle prevents wasteful object allocation on
     * every mouse event. The engine's own {@code isOnCooldown()} handles real
     * weapon cooldowns; this avoids the object creation overhead.</p>
     *
     * @param state        per-player state (for throttle tracking)
     * @param store        entity store
     * @param ref          player entity reference
     * @param inMeleeRange {@code true} if the target entity is within melee attack range
     * @param throttleNs   minimum interval between attack executions (nanoseconds)
     * @return the result indicating whether an attack occurred or the target is out of range
     */
    @Nonnull
    static AttackResult tryAttack(@Nonnull PlayerState state,
                                  @Nonnull Store<EntityStore> store,
                                  @Nonnull Ref<EntityStore> ref,
                                  boolean inMeleeRange,
                                  long throttleNs) {
        InteractionManager im = store.getComponent(
                ref, InteractionModule.get().getInteractionManagerComponent());
        if (im == null) {
            // No interaction manager — treat as "handled" when in range, otherwise walk
            return inMeleeRange ? AttackResult.ATTACKED : AttackResult.OUT_OF_RANGE;
        }

        InteractionContext ctx = InteractionContext.forInteraction(
                im, ref, InteractionType.Primary, store);
        String rootId = ctx.getRootInteractionId(InteractionType.Primary);
        if (rootId == null) {
            if (inMeleeRange) {
                LOGGER.atWarning().log("[CTM] No Primary interaction for held item");
            }
            return inMeleeRange ? AttackResult.ATTACKED : AttackResult.OUT_OF_RANGE;
        }

        RootInteraction root = RootInteraction.getAssetMap().getAsset(rootId);
        if (root == null) {
            if (inMeleeRange) {
                LOGGER.atWarning().log("[CTM] RootInteraction asset not found: %s", rootId);
            }
            return inMeleeRange ? AttackResult.ATTACKED : AttackResult.OUT_OF_RANGE;
        }

        // If not in melee range, only ranged weapons fire immediately
        if (!inMeleeRange) {
            Map<String, String[]> rawTags = root.getData().getRawTags();
            String[] attackTags = rawTags.get("Attack");
            boolean isRanged = attackTags != null && Arrays.asList(attackTags).contains("Ranged");
            if (!isRanged) {
                return AttackResult.OUT_OF_RANGE;
            }
        }

        // Throttle — avoid wasteful chain allocation on rapid events
        long now = System.nanoTime();
        if (now - state.lastAttackNanos < throttleNs) {
            return AttackResult.ATTACKED;
        }
        state.lastAttackNanos = now;

        InteractionChain chain = im.initChain(InteractionType.Primary, ctx, root, false);
        im.queueExecuteChain(chain);
        return AttackResult.ATTACKED;
    }

    /**
     * Attempts to trigger a {@link InteractionType#Use Use} interaction on a block
     * (e.g. opening a bench or chest).
     *
     * <p>Manually populates {@link Interaction#TARGET_BLOCK} and
     * {@link Interaction#TARGET_BLOCK_RAW} on the interaction context's meta store,
     * since server-initiated chains (unlike client packets) do not auto-populate
     * these values. Uses {@link World#getBaseBlock(BlockPosition)} to resolve
     * multi-block structures (e.g. double chests) to their base position.</p>
     *
     * @param state      per-player state (for throttle tracking)
     * @param store      entity store
     * @param ref        player entity reference
     * @param blockPos   the block to interact with
     * @param throttleNs minimum interval between executions (nanoseconds)
     * @return {@code true} if the interaction was executed or throttled,
     *         {@code false} if no Use interaction exists for the current equipment
     */
    static boolean tryBlockInteraction(@Nonnull PlayerState state,
                                       @Nonnull Store<EntityStore> store,
                                       @Nonnull Ref<EntityStore> ref,
                                       @Nonnull Vector3i blockPos,
                                       long throttleNs) {
        InteractionManager im = store.getComponent(
                ref, InteractionModule.get().getInteractionManagerComponent());
        if (im == null) return false;

        InteractionContext ctx = InteractionContext.forInteraction(
                im, ref, InteractionType.Use, store);
        String rootId = ctx.getRootInteractionId(InteractionType.Use);
        if (rootId == null) {
            LOGGER.atWarning().log("[CTM] No Use interaction for current equipment");
            return false;
        }

        RootInteraction root = RootInteraction.getAssetMap().getAsset(rootId);
        if (root == null) {
            LOGGER.atWarning().log("[CTM] RootInteraction asset not found: %s", rootId);
            return false;
        }

        // Throttle — avoid duplicate chain allocation on rapid events
        long now = System.nanoTime();
        if (now - state.lastAttackNanos < throttleNs) return true;
        state.lastAttackNanos = now;

        // Populate TARGET_BLOCK and TARGET_BLOCK_RAW on the meta store.
        // The server-forced chain path (queueExecuteChain) does NOT populate these
        // automatically — only the client's syncStart packet handler does.
        BlockPosition rawPos = new BlockPosition(
                blockPos.getX(), blockPos.getY(), blockPos.getZ());
        World world = store.getExternalData().getWorld();
        BlockPosition basePos = world.getBaseBlock(rawPos);

        ctx.getMetaStore().putMetaObject(Interaction.TARGET_BLOCK, basePos);
        ctx.getMetaStore().putMetaObject(Interaction.TARGET_BLOCK_RAW, rawPos);

        InteractionChain chain = im.initChain(
                InteractionType.Use, ctx, root, -1, basePos, false);
        im.queueExecuteChain(chain);

        LOGGER.atFine().log("[CTM] Triggered Use interaction on block (%d, %d, %d)",
                blockPos.getX(), blockPos.getY(), blockPos.getZ());
        return true;
    }
}
