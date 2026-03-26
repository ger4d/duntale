package com.duntale.zsquad.camera;

import com.hypixel.hytale.component.Ref;
import org.joml.Vector3d;
import org.joml.Vector3i;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Mutable per-player state for the click-to-move system.
 *
 * <p><b>Threading model</b>: Fields marked {@code volatile} may be written on the
 * world thread (event handlers, tick system) and read from PacketAdapter callbacks
 * running on Netty I/O threads. All non-volatile fields are accessed exclusively
 * from the world thread — both mouse-event handlers and {@link ClickToMoveTickSystem}
 * execute on the world thread, so no synchronisation is needed for those.</p>
 */
final class PlayerState {

    /** Original player model saved before widening yaw range. */
    @Nullable Model savedOriginalModel;

    /**
     * Current movement target (center of block XZ, player Y level).
     * {@code null} when idle (no active movement).
     *
     * <p><b>Volatile</b>: read by PacketAdapter callbacks (Netty I/O thread)
     * to check whether the player is actively moving.</p>
     */
    @Nullable volatile Vector3d targetPosition;

    /**
     * Direction angle (radians) of the last velocity instruction sent to the client.
     * Used to detect significant direction changes and avoid redundant packets.
     *
     * <p>World-thread only.</p>
     */
    double lastSentAngle = Double.NaN;

    /**
     * Name of the animation currently playing on {@code AnimationSlot.Status},
     * or {@code null} if idle. Tracks "Run" vs "RunBackward" to allow switching
     * without redundant stop/play when the animation hasn't changed.
     *
     * <p>World-thread only.</p>
     */
    @Nullable String currentAnimation;

    /**
     * Whether the left mouse button is currently held. While held, each tick
     * recomputes the target as {@code playerPos + (cursorOffsetX, cursorOffsetZ)}
     * to account for camera following the player with a stationary mouse.
     *
     * <p><b>Volatile</b>: written by event handlers, read by tick system.</p>
     */
    volatile boolean leftButtonHeld = false;

    /**
     * XZ offset from player position to the cursor's world target.
     * With a follow camera and stationary mouse, this offset is constant.
     * Updated on every mouse event (click or motion).
     *
     * <p>World-thread only.</p>
     */
    double cursorOffsetX;
    double cursorOffsetZ;

    /**
     * Entity reference reported by {@code getTargetEntity()} from the most recent
     * mouse event. When set, {@link ClickToMoveManager#tickMovement} walks toward
     * this entity and checks attack range each tick. Cleared when the entity becomes
     * invalid or the player clicks on empty ground.
     *
     * <p><b>Volatile</b>: written by event handlers, read by tick system.</p>
     */
    @Nullable volatile Ref<EntityStore> targetEntity;

    /**
     * Block position of an interactable block (e.g. bench, chest) that the player
     * is walking toward. When the player arrives within interaction range, a
     * {@link com.hypixel.hytale.protocol.InteractionType#Use Use} interaction chain
     * is triggered. {@code null} when no block interaction is pending.
     *
     * <p><b>Volatile</b>: written by event handlers, read by tick system.</p>
     */
    @Nullable volatile Vector3i targetInteractBlock;

    /**
     * Entity reference of a merchant NPC that the player is walking toward.
     * When the player arrives within interaction range, the merchant UI is opened
     * directly (bypassing the NPC interaction system for click-move compatibility).
     * {@code null} when no merchant interaction is pending.
     *
     * <p><b>Volatile</b>: written by event handlers, read by tick system.</p>
     */
    @Nullable volatile Ref<EntityStore> targetMerchantEntity;

    /**
     * {@link System#nanoTime()} of the last successful attack chain execution.
     * Used together with the attack throttle constant to avoid wasteful
     * InteractionContext / InteractionChain allocation on every mouse event.
     *
     * <p>World-thread only.</p>
     */
    long lastAttackNanos;

    /**
     * The last server-sent {@link Page} for this player, tracked via an outbound
     * {@code SetPage} watcher. Used to suppress CTM input while a built-in page
     * (e.g. Bench) is open.
     *
     * <p><b>Volatile</b>: written by Netty I/O thread (PacketAdapter watcher),
     * read by world thread (event handlers).</p>
     *
     * <p><b>Limitation</b>: Client-toggled pages (Inventory, Map) are opened
     * entirely client-side — the server receives no notification, so this field
     * will NOT reflect those pages.</p>
     */
    @Nonnull volatile Page activePage = Page.None;
}
