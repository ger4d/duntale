package com.duntale.zsquad.camera;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.util.MathUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.CameraNode;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.protocol.MouseButtonState;
import com.hypixel.hytale.protocol.MouseButtonType;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.protocol.Rangef;
import com.hypixel.hytale.protocol.VelocityThresholdStyle;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.camera.CameraAxis;
import com.hypixel.hytale.server.core.asset.type.model.config.camera.CameraSettings;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.event.events.player.PlayerMouseButtonEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerMouseMotionEvent;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent;
import com.hypixel.hytale.protocol.VelocityConfig;
import com.hypixel.hytale.protocol.packets.entities.ChangeVelocity;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Click-to-move manager: detects left-click / left-drag and moves
 * the player toward the target block position.
 *
 * <p>With {@code planeNormal = (0,1,0)} the client automatically rotates the player
 * head toward the mouse position on the ground plane. The ±1 rad {@code Yaw.AngleRange}
 * makes the body follow the head tightly.</p>
 *
 * <h3>Movement approach</h3>
 * <ul>
 *   <li>Event handlers ({@code onMouseButton}, {@code onMouseMotion}) resolve the
 *       clicked block into a walkable {@link PlayerState#targetPosition} and send
 *       an initial {@code ChangeVelocity} packet + {@code PlayAnimation} on
 *       {@link AnimationSlot#Status}.</li>
 *   <li>While the left button is held, a cursor-to-player XZ offset is stored.
 *       {@link #tickMovement} recomputes the target each tick as
 *       {@code playerPos + offset}, so the player continuously walks toward the
 *       cursor even when the mouse is stationary and the camera follows.</li>
 *   <li>On mouse release, {@code leftButtonHeld} is cleared but movement is
 *       <b>not</b> stopped — the player continues to the last target until arrival.</li>
 *   <li>On arrival, a zero-velocity instruction is sent and the animation is stopped.</li>
 * </ul>
 *
 * <h3>Why ChangeVelocity instead of Velocity.set()</h3>
 * <p>Player movement is <b>client-authoritative</b>. Setting {@code Velocity.velocity}
 * server-side has no effect on position — the client's {@code ClientMovement} packet
 * overwrites it each tick. Instead, we send {@code ChangeVelocity} packets (163)
 * directly via {@code writeNoCache()}, which applies the velocity locally in the
 * client's physics simulation.</p>
 *
 * <h3>Why PlayAnimation(Status) instead of MovementStates</h3>
 * <p>The client computes local animation from WASD ({@code _wishDirection}). Without
 * keyboard input, it shows "Idle" regardless of server-set MovementStates. Only
 * {@code AnimationSlot.Status} suppresses the client's
 * {@code UpdateMovementAnimation()}, forcing our "Run" animation.</p>
 *
 * @since 1.0.0
 */
public class ClickToMoveManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    // ============================================
    // Constants
    // ============================================

    /** Movement speed in blocks/second. */
    private static final double MOVE_SPEED = 8.0;

    /** Stop moving when within this distance (XZ) of the target. */
    private static final double ARRIVAL_THRESHOLD = 1.0;

    /** Squared arrival threshold for cheaper distance checks. */
    private static final double ARRIVAL_THRESHOLD_SQ = ARRIVAL_THRESHOLD * ARRIVAL_THRESHOLD;

    /**
     * Minimum direction change (in radians) before re-sending a velocity instruction.
     * ~5.7° — avoids spamming packets when dragging with minor mouse movement.
     */
    private static final double DIRECTION_CHANGE_THRESHOLD = 0.1;

    /**
     * VelocityConfig with no decay. Velocity persists until explicitly stopped.
     * {@code groundResistance = 1.0} means no XZ velocity loss per tick.
     * Uses the protocol {@link VelocityConfig} directly (public fields, sent in
     * {@link ChangeVelocity} packets without going through the splitvelocity layer).
     */
    private static final VelocityConfig NO_DECAY_CONFIG = new VelocityConfig(
            1.0F,   // groundResistance  — no XZ decay on ground
            -1.0F,  // groundResistanceMax — disable threshold blending
            1.0F,   // airResistance     — no XZ decay in air
            -1.0F,  // airResistanceMax  — disable threshold blending
            0.0F,   // threshold         — immediate transition
            VelocityThresholdStyle.Linear
    );

    /** Run animation key. Must exist in the player model's AnimationSets. */
    private static final String RUN_ANIMATION = "Run";

    /** Backward run animation key — played when body faces away from the target. */
    private static final String RUN_BACKWARD_ANIMATION = "RunBackward";

    /**
     * ±1 radian (~57°) yaw range. Tight enough that the body follows the head
     * as the client rotates head toward the mouse cursor via {@code planeNormal = (0,1,0)}.
     */
    private static final CameraAxis FOLLOW_YAW_RANGE = new CameraAxis(
            new Rangef(-1.0F, 1.0F), new CameraNode[]{CameraNode.Head}
    );

    /**
     * Cardinal offsets to probe when the clicked block is a wall
     * (target Y &gt; player Y). Order: +X, -X, +Z, -Z.
     */
    private static final int[][] WALL_PROBE_OFFSETS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}
    };

    // ============================================
    // Entity Detection Constants
    // ============================================

    /**
     * Maximum distance (in blocks, XZ only) from the player to an entity for the
     * "attack range" check. If the entity is inside this range, movement stops
     * and an attack is triggered instead.
     */
    private static final double ATTACK_RANGE = 3.0;

    /** Squared attack range for cheaper distance checks. */
    private static final double ATTACK_RANGE_SQ = ATTACK_RANGE * ATTACK_RANGE;

    // ============================================
    // Per-player State
    // ============================================

    static final class PlayerState {
        /** Original player model saved before widening yaw range. */
        @Nullable Model savedOriginalModel;

        /**
         * Current movement target (center of block XZ, player Y level).
         * {@code null} when idle (no active movement).
         */
        @Nullable volatile Vector3d targetPosition;

        /**
         * Direction angle (radians) of the last velocity instruction sent to the client.
         * Used to detect significant direction changes and avoid redundant packets.
         */
        double lastSentAngle = Double.NaN;

        /**
         * Name of the animation currently playing on {@link AnimationSlot#Status},
         * or {@code null} if idle. Tracks "Run" vs "RunBackward" to allow switching
         * without redundant stop/play when the animation hasn't changed.
         */
        @Nullable String currentAnimation;

        /**
         * Whether the left mouse button is currently held. While held, each tick
         * recomputes the target as {@code playerPos + (cursorOffsetX, cursorOffsetZ)}
         * to account for camera following the player with a stationary mouse.
         */
        volatile boolean leftButtonHeld = false;

        /**
         * XZ offset from player position to the cursor's world target.
         * With a follow camera and stationary mouse, this offset is constant.
         * Updated on every mouse event (click or motion).
         */
        double cursorOffsetX;
        double cursorOffsetZ;

        /**
         * Entity reference reported by {@code getTargetEntity()} from the most recent
         * mouse event. When set, {@link ClickToMoveManager#tickMovement} walks toward
         * this entity and checks attack range each tick. Cleared when the entity becomes
         * invalid or the player clicks on empty ground.
         */
        @Nullable volatile Ref<EntityStore> targetEntity;
    }

    // ============================================
    // Instance Fields
    // ============================================

    private final Map<UUID, PlayerState> players = new ConcurrentHashMap<>();
    private final EventRegistry eventRegistry;

    // ============================================
    // Constructor
    // ============================================

    public ClickToMoveManager() {
        this.eventRegistry = new EventRegistry(
                new CopyOnWriteArrayList<>(), () -> true, "ClickToMoveManager",
                HytaleServer.get().getEventBus()
        );
        this.eventRegistry.enable();
        this.eventRegistry.register(PlayerMouseButtonEvent.class, this::onMouseButton);
        this.eventRegistry.register(PlayerMouseMotionEvent.class, this::onMouseMotion);
    }

    // ============================================
    // Public API
    // ============================================

    /**
     * Enables click-to-move for a player. Widens the player model's
     * {@code Yaw.AngleRange} to ±1 rad.
     */
    public void enable(@Nonnull UUID uuid,
                       @Nonnull Store<EntityStore> store,
                       @Nonnull Ref<EntityStore> ref) {
        PlayerState state = new PlayerState();
        players.put(uuid, state);
        widenModelAngleRange(state, uuid, store, ref);
    }

    /**
     * Disables click-to-move for a player. Stops active movement, restores the original model.
     */
    public void disable(@Nonnull UUID uuid,
                        @Nonnull Store<EntityStore> store,
                        @Nonnull Ref<EntityStore> ref) {
        PlayerState state = players.remove(uuid);
        if (state == null) return;

        // Stop movement if active (sends zero velocity + stops animation)
        if (state.targetPosition != null || state.currentAnimation != null) {
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef != null) {
                stopMovement(state, store, ref, playerRef);
            }
        }

        restoreModelAngleRange(state, uuid, store, ref);
    }

    /**
     * Disables click-to-move using only the UUID (schedules on world thread).
     */
    public void disable(@Nonnull UUID uuid) {
        PlayerState state = players.remove(uuid);
        if (state == null) return;

        PlayerRef playerRef = Universe.get().getPlayer(uuid);
        if (playerRef == null) return;

        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) return;

        Store<EntityStore> store = ref.getStore();
        World world = store.getExternalData().getWorld();
        world.execute(() -> {
            if (!ref.isValid()) return;
            // Stop movement if active
            if (state.targetPosition != null || state.currentAnimation != null) {
                stopMovement(state, store, ref, playerRef);
            }
            restoreModelAngleRange(state, uuid, store, ref);
        });
    }

    public boolean isEnabled(@Nonnull UUID uuid) {
        return players.containsKey(uuid);
    }

    /**
     * Returns {@code true} if the player needs tick processing — either has an
     * active movement target (walking to arrival) or is holding the button
     * (continuous movement with offset recomputation).
     */
    boolean needsProcessing(@Nonnull UUID uuid) {
        PlayerState state = players.get(uuid);
        return state != null && (state.leftButtonHeld || state.targetPosition != null);
    }

    /**
     * Called every tick by {@link ClickToMoveTickSystem}. Checks arrival and
     * updates velocity direction when the target changes (drag-to-move).
     *
     * <p>Velocity and animation are sent <b>once</b> (in {@link #updateTarget} or here
     * on direction change). The client maintains the velocity via the {@link #NO_DECAY_CONFIG}
     * until a stop instruction is sent.</p>
     */
    void tickMovement(@Nonnull Ref<EntityStore> ref,
                      @Nonnull Store<EntityStore> store,
                      @Nonnull PlayerRef playerRef,
                      @Nonnull UUID uuid) {
        PlayerState state = players.get(uuid);
        if (state == null) return;

        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) return;

        Vector3d pos = transform.getPosition();

        // While button is held, recompute target from stored cursor offset.
        // With a follow camera and stationary mouse, the offset is constant,
        // so the target slides forward as the player moves.
        if (state.leftButtonHeld) {
            double newTargetX = pos.x + state.cursorOffsetX;
            double newTargetZ = pos.z + state.cursorOffsetZ;

            // Validate the recomputed target is over solid ground, not void.
            // If invalid, skip updating — player continues toward last valid target.
            int blockX = MathUtil.floor(newTargetX);
            int blockZ = MathUtil.floor(newTargetZ);
            int footY = MathUtil.floor(pos.y) - 1;
            if (isWalkable(store, blockX, blockZ, footY)) {
                state.targetPosition = new Vector3d(newTargetX, pos.y, newTargetZ);
            }
        }

        Vector3d target = state.targetPosition;
        if (target == null) return;

        // ── Entity-in-range check ────────────────────────────────────
        // If we have a target entity (from mouse event), check each tick whether
        // the player has entered attack range. If so, stop and "attack".
        Ref<EntityStore> targetEntity = state.targetEntity;
        if (targetEntity != null && targetEntity.isValid()) {
            TransformComponent eTransform = store.getComponent(targetEntity, TransformComponent.getComponentType());
            if (eTransform != null) {
                Vector3d ePos = eTransform.getPosition();
                double edx = ePos.x - pos.x;
                double edz = ePos.z - pos.z;
                double entityDistSq = edx * edx + edz * edz;

                if (entityDistSq <= ATTACK_RANGE_SQ) {
                    LOGGER.atInfo().log("[CTM] Attacking (entity within range %.1f)", Math.sqrt(entityDistSq));
                    stopMovement(state, store, ref, playerRef);
                    return;
                }

                // Entity still out of range — walk toward it (update target to its current position)
                state.targetPosition = new Vector3d(ePos.x, pos.y, ePos.z);
                target = state.targetPosition;
            } else {
                // Entity no longer valid — clear entity target, keep walking to last position
                state.targetEntity = null;
            }
        }

        double dx = target.x - pos.x;
        double dz = target.z - pos.z;
        double distSq = dx * dx + dz * dz;

        if (distSq <= ARRIVAL_THRESHOLD_SQ) {
            // Only stop if button is released; while held the offset keeps us moving
            if (!state.leftButtonHeld) {
                stopMovement(state, store, ref, playerRef);
                return;
            }
            // Button held but too close — velocity will be near-zero, harmless
            return;
        }

        // Check if direction changed significantly → re-send velocity
        double angle = Math.atan2(dz, dx);
        if (Double.isNaN(state.lastSentAngle)) {
            // First tick after target set from event — velocity already sent
        } else {
            double angleDiff = Math.abs(angle - state.lastSentAngle);
            if (angleDiff > Math.PI) angleDiff = 2 * Math.PI - angleDiff;
            if (angleDiff > DIRECTION_CHANGE_THRESHOLD) {
                sendVelocity(state, playerRef, dx, dz, distSq);
            }
        }

        // Choose and ensure correct animation based on body facing vs movement direction
        String anim = chooseAnimation(transform, dx, dz);
        updateAnimation(state, store, ref, anim);

        // Keep MovementStates in sync for remote viewers
        setMovingStates(store, ref);
    }

    public void shutdown() {
        this.eventRegistry.shutdownAndCleanup(false);
        this.players.clear();
    }

    // ============================================
    // Event Handlers
    // ============================================

    private void onMouseMotion(@Nonnull PlayerMouseMotionEvent event) {
        Ref<EntityStore> ref = event.getPlayerRef();
        if (!ref.isValid()) return;

        Store<EntityStore> store = ref.getStore();
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null) return;

        UUID uuid = playerRef.getUuid();
        PlayerState state = players.get(uuid);
        if (state == null) return;

        // Only process motion when left button is held (drag-to-move)
        if (!isLeftButtonHeld(event)) return;

        Ref<EntityStore> targetEntityRef = resolveTargetEntity(event.getTargetEntity(), ref);
        Vector3i targetBlock = event.getTargetBlock();
        // Update target and offset — no particle on drag, only on click
        updateTarget(state, store, ref, playerRef, targetBlock, targetEntityRef, false);
    }

    private void onMouseButton(@Nonnull PlayerMouseButtonEvent event) {
        Ref<EntityStore> ref = event.getPlayerRef();
        if (!ref.isValid()) return;

        Store<EntityStore> store = ref.getStore();
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null) return;

        UUID uuid = playerRef.getUuid();
        PlayerState state = players.get(uuid);
        if (state == null) return;

        if (event.getMouseButton().mouseButtonType != MouseButtonType.Left) return;

        if (event.getMouseButton().state == MouseButtonState.Released) {
            // Button released — clear held flag but do NOT stop movement.
            // Player continues to last target until arrival.
            state.leftButtonHeld = false;
            return;
        }

        if (event.getMouseButton().state != MouseButtonState.Pressed) return;

        state.leftButtonHeld = true;
        Ref<EntityStore> targetEntityRef = resolveTargetEntity(event.getTargetEntity(), ref);
        Vector3i targetBlock = event.getTargetBlock();
        // Click → update target, store offset, and spawn particle
        updateTarget(state, store, ref, playerRef, targetBlock, targetEntityRef, true);
    }

    /**
     * Checks whether the left mouse button is held during a motion event.
     */
    private static boolean isLeftButtonHeld(@Nonnull PlayerMouseMotionEvent event) {
        if (event.getMouseMotion() == null) return false;
        MouseButtonType[] held = event.getMouseMotion().mouseButtonType;
        if (held == null) return false;
        for (MouseButtonType btn : held) {
            if (btn == MouseButtonType.Left) return true;
        }
        return false;
    }

    /**
     * Extracts and validates the entity reference from a mouse event's target entity.
     * Returns {@code null} if the target entity is null, invalid, or is the player themselves.
     *
     * @param targetEntity  the entity reported by the client (may be null)
     * @param playerRef     the player's own entity reference (to exclude self-targeting)
     * @return the valid entity reference, or {@code null}
     */
    @Nullable
    private static Ref<EntityStore> resolveTargetEntity(@Nullable Entity targetEntity,
                                                         @Nonnull Ref<EntityStore> playerRef) {
        if (targetEntity == null) return null;
        Ref<EntityStore> entityRef = targetEntity.getReference();
        if (entityRef == null || !entityRef.isValid()) return null;
        if (entityRef.equals(playerRef)) return null;
        return entityRef;
    }

    // ============================================
    // Target Resolution
    // ============================================

    /**
     * Resolves a clicked/dragged block into a walkable target position,
     * updates the player state and cursor offset, and optionally spawns a
     * particle visible only to the clicking player.
     *
     * <p><b>Entity targeting</b>: If the client reports a target entity (via
     * {@code getTargetEntity()}), and the entity is within attack range, movement
     * is stopped and "Attacking" is logged. If out of range, the entity ref is
     * stored in {@link PlayerState#targetEntity} so the tick system walks toward
     * it and checks range each tick.</p>
     *
     * <p><b>Wall detection</b>: If the target block's Y is higher than the player's
     * foot-level Y, the click hit a wall face. We probe the 4 cardinal neighbours
     * at the player's Y level to find a walkable (non-air) block as the actual target.</p>
     *
     * <p><b>Cursor offset</b>: Stores the XZ offset from the player position to the
     * resolved target. While the button is held and the mouse is stationary, the
     * tick system recomputes the target as {@code playerPos + offset} so the player
     * keeps walking in the same direction relative to the camera.</p>
     *
     * @param state           per-player state to update
     * @param store           entity store
     * @param ref             player entity reference
     * @param playerRef       player reference (for packet sending)
     * @param targetBlock     the block the cursor is over (may be null for void)
     * @param targetEntityRef the entity under the cursor (may be null)
     * @param spawnParticle   {@code true} to spawn feedback particle (click only, not drag)
     */
    private void updateTarget(@Nonnull PlayerState state,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull Ref<EntityStore> ref,
                              @Nonnull PlayerRef playerRef,
                              @Nullable Vector3i targetBlock,
                              @Nullable Ref<EntityStore> targetEntityRef,
                              boolean spawnParticle) {
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) return;

        Vector3d pos = transform.getPosition();
        int playerFootY = MathUtil.floor(pos.y) - 1; // block under player's feet

        // ── Entity targeting ─────────────────────────────────────────
        // If the client reports an entity under the cursor, check attack range.
        if (targetEntityRef != null) {
            TransformComponent eTransform = store.getComponent(targetEntityRef, TransformComponent.getComponentType());
            if (eTransform != null) {
                Vector3d ePos = eTransform.getPosition();
                double edx = ePos.x - pos.x;
                double edz = ePos.z - pos.z;
                double entityDistSq = edx * edx + edz * edz;

                if (entityDistSq <= ATTACK_RANGE_SQ) {
                    // In attack range → stop movement and attack
                    LOGGER.atInfo().log("[CTM] Attacking (entity within range %.1f)", Math.sqrt(entityDistSq));
                    state.targetEntity = null;
                    stopMovement(state, store, ref, playerRef);
                    return;
                }

                // Out of range → walk toward entity. Store ref so tickMovement
                // continuously updates the target to the entity's moving position.
                state.targetEntity = targetEntityRef;
                beginMovement(state, playerRef, transform, store, ref, pos,
                        new Vector3d(ePos.x, pos.y, ePos.z));
                return;
            }
        }

        // No entity target — clear any previous entity tracking
        state.targetEntity = null;

        // Ground targeting requires a valid target block
        if (targetBlock == null) return;

        int tx = targetBlock.getX();
        int tz = targetBlock.getZ();
        int ty = targetBlock.getY();
        
        // Wall check: target block higher than ground level → clicked a wall
        if (ty > playerFootY) {
            Vector3i resolved = resolveWallClick(store, ref, tx, tz, playerFootY);
            if (resolved == null) return; // no walkable neighbour found
            tx = resolved.getX();
            tz = resolved.getZ();
        }

        // Set target: center of block XZ, at player's current Y
        double targetX = tx + 0.5;
        double targetZ = tz + 0.5;

        beginMovement(state, playerRef, transform, store, ref, pos,
                new Vector3d(targetX, pos.y, targetZ));

        // Particle feedback — only on click, only visible to this player
        if (spawnParticle) {
            ParticleUtil.spawnParticleEffect("Block_Break_Ore",
                    new Vector3d(targetX, playerFootY + 1.0, targetZ),
                    List.of(ref),
                    store);
        }
    }

    /**
     * When a click hits a wall (target Y &gt; player Y), probes the 4 cardinal
     * neighbours at the player's foot level to find the nearest non-air block.
     *
     * @return the walkable block position, or {@code null} if none found
     */
    @Nullable
    private static Vector3i resolveWallClick(@Nonnull Store<EntityStore> store,
                                              @Nonnull Ref<EntityStore> ref,
                                              int wallX, int wallZ, int footY) {
        World world = store.getExternalData().getWorld();
        for (int[] offset : WALL_PROBE_OFFSETS) {
            int probeX = wallX + offset[0];
            int probeZ = wallZ + offset[1];

            long chunkIdx = ChunkUtil.indexChunkFromBlock(probeX, probeZ);
            WorldChunk chunk = world.getChunkIfInMemory(chunkIdx);
            if (chunk == null) continue;

            int blockId = chunk.getBlock(probeX, footY, probeZ);
            if (blockId != 0) {
                // Found a solid block at ground level — check above is air (walkable)
                int aboveId = chunk.getBlock(probeX, footY + 1, probeZ);
                if (aboveId == 0) {
                    return new Vector3i(probeX, footY, probeZ);
                }
            }
        }
        return null;
    }

    /**
     * Checks whether a block position is walkable: solid block at {@code footY}
     * and air above it. Returns {@code false} if the chunk is not loaded (treat as void).
     *
     * @param store  entity store to access the world
     * @param blockX block X coordinate
     * @param blockZ block Z coordinate
     * @param footY  Y level of the ground block (under the player's feet)
     * @return {@code true} if the position is walkable
     */
    private static boolean isWalkable(@Nonnull Store<EntityStore> store,
                                       int blockX, int blockZ, int footY) {
        World world = store.getExternalData().getWorld();
        long chunkIdx = ChunkUtil.indexChunkFromBlock(blockX, blockZ);
        WorldChunk chunk = world.getChunkIfInMemory(chunkIdx);
        if (chunk == null) return false;

        int blockId = chunk.getBlock(blockX, footY, blockZ);
        // TODO: Use a pre-defined list of walkable blocks
        int aboveId = chunk.getBlock(blockX, footY + 1, blockZ);
        return blockId != 0 && aboveId == 0;
    }

    // ============================================
    // Movement Helpers
    // ============================================

    /**
     * Sets the movement target and cursor offset, then sends the initial velocity
     * and animation if the target is beyond the arrival threshold.
     *
     * <p>Shared by both entity targeting and ground targeting in {@link #updateTarget}
     * to avoid duplicating the "start walking toward a position" logic.</p>
     *
     * @param state     per-player state to update
     * @param playerRef player reference (for packet sending)
     * @param transform player transform (for animation selection)
     * @param store     entity store
     * @param ref       player entity reference
     * @param playerPos player's current world position
     * @param target    the position to walk toward
     */
    private void beginMovement(@Nonnull PlayerState state,
                                @Nonnull PlayerRef playerRef,
                                @Nonnull TransformComponent transform,
                                @Nonnull Store<EntityStore> store,
                                @Nonnull Ref<EntityStore> ref,
                                @Nonnull Vector3d playerPos,
                                @Nonnull Vector3d target) {
        state.targetPosition = target;
        state.cursorOffsetX = target.x - playerPos.x;
        state.cursorOffsetZ = target.z - playerPos.z;

        double dx = target.x - playerPos.x;
        double dz = target.z - playerPos.z;
        double distSq = dx * dx + dz * dz;
        if (distSq > ARRIVAL_THRESHOLD_SQ) {
            sendVelocity(state, playerRef, dx, dz, distSq);
            updateAnimation(state, store, ref, chooseAnimation(transform, dx, dz));
            setMovingStates(store, ref);
        }
    }

    /**
     * Sends a {@code ChangeVelocity} packet directly to the client via the player's
     * packet handler. Bypasses the {@code Velocity.addInstruction → PlayerVelocityInstructionSystem}
     * pipeline so we can use the protocol {@link VelocityConfig} (public fields).
     * The {@link #NO_DECAY_CONFIG} ensures the velocity persists until explicitly stopped.
     */
    private static void sendVelocity(@Nonnull PlayerState state,
                                      @Nonnull PlayerRef playerRef,
                                      double dx, double dz, double distSq) {
        double dist = Math.sqrt(distSq);
        float vx = (float) ((dx / dist) * MOVE_SPEED);
        float vz = (float) ((dz / dist) * MOVE_SPEED);

        playerRef.getPacketHandler().writeNoCache(
                new ChangeVelocity(vx, 0.0F, vz, ChangeVelocityType.Set, NO_DECAY_CONFIG)
        );
        state.lastSentAngle = Math.atan2(dz, dx);
    }

    /**
     * Determines whether to play "Run" or "RunBackward" based on the angle
     * between the player's body facing direction and the movement direction.
     *
     * <p>Uses Hytale's yaw convention: forward = {@code (-sin(yaw), -cos(yaw))}.
     * If the dot product of the forward vector and the movement vector is
     * non-negative (within ±90°), the player is facing the target → "Run".
     * Otherwise → "RunBackward".</p>
     *
     * @param transform the player's transform (for body yaw)
     * @param dx        movement direction X (target.x - pos.x)
     * @param dz        movement direction Z (target.z - pos.z)
     * @return animation name to play
     */
    @Nonnull
    private static String chooseAnimation(@Nonnull TransformComponent transform,
                                           double dx, double dz) {
        float yaw = transform.getRotation().getYaw();
        double forwardX = -Math.sin(yaw);
        double forwardZ = -Math.cos(yaw);
        double dot = forwardX * dx + forwardZ * dz;
        return dot >= 0 ? RUN_ANIMATION : RUN_BACKWARD_ANIMATION;
    }

    /**
     * Ensures the correct animation is playing on {@link AnimationSlot#Status}.
     * If the desired animation is already playing, this is a no-op. If a different
     * animation is playing, it is stopped first. Sends to self so the client's
     * {@code UpdateMovementAnimation()} is suppressed.
     *
     * @param animationName the animation to play ("Run" or "RunBackward")
     */
    private static void updateAnimation(@Nonnull PlayerState state,
                                         @Nonnull Store<EntityStore> store,
                                         @Nonnull Ref<EntityStore> ref,
                                         @Nonnull String animationName) {
        if (animationName.equals(state.currentAnimation)) return; // already correct
        // Stop previous animation if switching
        if (state.currentAnimation != null) {
            AnimationUtils.stopAnimation(ref, AnimationSlot.Status, true, store);
        }
        AnimationUtils.playAnimation(ref, AnimationSlot.Status, animationName, true, store);
        state.currentAnimation = animationName;
    }

    /**
     * Stops the animation on {@link AnimationSlot#Status}, allowing the client's
     * {@code UpdateMovementAnimation()} to resume (shows "Idle").
     */
    private static void stopAnimation(@Nonnull PlayerState state,
                                       @Nonnull Store<EntityStore> store,
                                       @Nonnull Ref<EntityStore> ref) {
        if (state.currentAnimation == null) return;
        AnimationUtils.stopAnimation(ref, AnimationSlot.Status, true, store);
        state.currentAnimation = null;
    }

    /**
     * Sets movement states to "running on ground" for remote viewer animation sync.
     * Does NOT affect the local player (client overwrites from WASD each tick).
     */
    private static void setMovingStates(@Nonnull Store<EntityStore> store,
                                         @Nonnull Ref<EntityStore> ref) {
        MovementStatesComponent msc = store.getComponent(ref, MovementStatesComponent.getComponentType());
        if (msc == null) return;

        MovementStates states = msc.getMovementStates();
        states.idle = false;
        states.horizontalIdle = false;
        states.running = true;
        states.walking = false;
        states.sprinting = false;
        states.onGround = true;
    }

    /**
     * Clears the movement target, sends zero-velocity to the client,
     * stops the animation, and resets movement states to idle.
     */
    private static void stopMovement(@Nonnull PlayerState state,
                                      @Nonnull Store<EntityStore> store,
                                      @Nonnull Ref<EntityStore> ref,
                                      @Nonnull PlayerRef playerRef) {
        state.targetPosition = null;
        state.targetEntity = null;
        state.lastSentAngle = Double.NaN;
        state.cursorOffsetX = 0;
        state.cursorOffsetZ = 0;

        // Send zero velocity to client (clears _appliedVelocities)
        playerRef.getPacketHandler().writeNoCache(
                new ChangeVelocity(0.0F, 0.0F, 0.0F, ChangeVelocityType.Set, NO_DECAY_CONFIG)
        );

        // Stop Run animation → client resumes Idle
        stopAnimation(state, store, ref);

        // Reset states for remote viewers
        MovementStatesComponent msc = store.getComponent(ref, MovementStatesComponent.getComponentType());
        if (msc == null) return;

        MovementStates states = msc.getMovementStates();
        states.idle = true;
        states.horizontalIdle = true;
        states.running = false;
        states.walking = false;
        states.onGround = true;
    }

    // ============================================
    // Model AngleRange
    // ============================================

    private void widenModelAngleRange(@Nonnull PlayerState state,
                                       @Nonnull UUID uuid,
                                       @Nonnull Store<EntityStore> store,
                                       @Nonnull Ref<EntityStore> ref) {
        ModelComponent modelComponent = store.getComponent(ref, ModelComponent.getComponentType());
        if (modelComponent == null) return;

        Model original = modelComponent.getModel();
        state.savedOriginalModel = original;

        CameraSettings originalCamera = original.getCamera();
        CameraSettings newCamera = new CameraSettings(
                originalCamera != null ? originalCamera.getPositionOffset() : null,
                FOLLOW_YAW_RANGE,
                originalCamera != null ? originalCamera.getPitch() : null
        );

        Model newModel = new Model(
                original.getModelAssetId(), original.getScale(),
                original.getRandomAttachmentIds(), original.getAttachments(),
                original.getBoundingBox(), original.getModel(),
                original.getTexture(), original.getGradientSet(),
                original.getGradientId(), original.getEyeHeight(),
                original.getCrouchOffset(), original.getSittingOffset(),
                original.getSleepingOffset(), original.getAnimationSetMap(),
                newCamera, original.getLight(),
                original.getParticles(), original.getTrails(),
                original.getPhysicsValues(), original.getDetailBoxes(),
                original.getPhobia(), original.getPhobiaModelAssetId()
        );

        store.putComponent(ref, ModelComponent.getComponentType(), new ModelComponent(newModel));

        PlayerSkinComponent skinComponent = store.getComponent(ref, PlayerSkinComponent.getComponentType());
        if (skinComponent != null) {
            skinComponent.setNetworkOutdated();
        }

        LOGGER.atInfo().log("[CTM] Widened Yaw.AngleRange to ±180° for %s", uuid);
    }

    private void restoreModelAngleRange(@Nonnull PlayerState state,
                                         @Nonnull UUID uuid,
                                         @Nonnull Store<EntityStore> store,
                                         @Nonnull Ref<EntityStore> ref) {
        Model original = state.savedOriginalModel;
        state.savedOriginalModel = null;
        if (original == null || !ref.isValid()) return;

        store.putComponent(ref, ModelComponent.getComponentType(), new ModelComponent(original));

        PlayerSkinComponent skinComponent = store.getComponent(ref, PlayerSkinComponent.getComponentType());
        if (skinComponent != null) {
            skinComponent.setNetworkOutdated();
        }

        LOGGER.atInfo().log("[CTM] Restored original model for %s", uuid);
    }
}
