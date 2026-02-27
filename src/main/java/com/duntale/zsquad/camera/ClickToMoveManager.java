package com.duntale.zsquad.camera;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.CameraNode;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.protocol.ComponentUpdate;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.EntityUpdate;
import com.hypixel.hytale.protocol.EquipmentUpdate;
import com.hypixel.hytale.protocol.ModelTransform;
import com.hypixel.hytale.protocol.MouseButtonState;
import com.hypixel.hytale.protocol.MouseButtonType;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.protocol.Rangef;
import com.hypixel.hytale.protocol.TransformUpdate;
import com.hypixel.hytale.protocol.VelocityThresholdStyle;
import com.hypixel.hytale.protocol.packets.entities.ChangeVelocity;
import com.hypixel.hytale.protocol.packets.entities.EntityUpdates;
import com.hypixel.hytale.protocol.packets.player.ClientMovement;
import com.hypixel.hytale.protocol.packets.player.ClientTeleport;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.camera.CameraAxis;
import com.hypixel.hytale.server.core.asset.type.model.config.camera.CameraSettings;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.LivingEntity;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.event.events.player.PlayerMouseButtonEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerMouseMotionEvent;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.io.adapter.PacketFilter;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages click-to-move behavior for players in overhead camera modes.
 *
 * <p>Click on the ground to set a destination — the player walks there automatically.
 * Drag to continuously update the destination. Right-click to cancel.</p>
 *
 * <h3>How it works</h3>
 *
 * <p>Hytale uses client-authoritative movement: animations are driven by {@code _wishDirection}
 * (WASD input), not by server-applied velocity. Without WASD the client always shows "Idle",
 * even when sliding via velocity. To fix this we use two complementary mechanisms:</p>
 *
 * <ol>
 *   <li><b>Velocity</b> — {@link ChangeVelocity} packets with a {@code VelocityConfig} that
 *       has {@code groundResistance = 1.0} (no per-tick decay). The velocity persists until
 *       explicitly cleared.</li>
 *   <li><b>Animation</b> — {@code PlayAnimation} on {@link AnimationSlot#Status} (slot 0).
 *       When {@code ServerAnimations[0]} is non-null on the client, the locally-computed
 *       {@code UpdateMovementAnimation()} is <b>completely skipped</b>.</li>
 * </ol>
 *
 * <h3>Rotation sync</h3>
 *
 * <p>Body rotation is synced via {@link ClientTeleport} with {@code lookOrientation = null}.
 * This avoids the camera snap caused by {@code PlayerEntity.SetTransform → SetRotation}.
 * The ±180° {@code Yaw.AngleRange} prevents {@code UpdateWithoutPosition} from overriding
 * body yaw in the stationary branch.</p>
 *
 * <h3>Packet filters</h3>
 * <ul>
 *   <li><b>Inbound</b>: Overrides {@code bodyOrientation} on {@link ClientMovement} packets
 *       with the desired movement or click yaw.</li>
 *   <li><b>Outbound</b>: Strips {@code lookOrientation} from self-entity
 *       {@link TransformUpdate} and removes {@link EquipmentUpdate} to prevent
 *       stale-value camera snaps and item-camera recalculations.</li>
 * </ul>
 *
 * <p>Movement is ticked via {@link ClickToMoveTickSystem}, a 30 TPS ECS system that runs
 * on the world thread after {@code ProcessPlayerInput}.</p>
 */
public class ClickToMoveManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    // ── Constants ────────────────────────────────────────────────────────

    /** How close (in blocks) the player must be to the target to stop moving. */
    private static final double ARRIVAL_THRESHOLD = 1.0;

    /** Movement speed in blocks/second. */
    private static final double MOVE_SPEED = 8.0;

    /** Movement animation ID for forward movement. */
    private static final String RUN_ANIMATION = "Run";

    /** Movement animation ID for backward movement (relative to look direction). */
    private static final String RUN_BACKWARD_ANIMATION = "RunBackward";

    /**
     * Angle threshold (radians, 120°) beyond which the movement animation
     * switches from forward to backward. Matches the client's {@code UpdateMovementAnimation}.
     */
    private static final double BACKWARD_ANGLE_THRESHOLD = Math.toRadians(120.0);

    /** Angle threshold (radians, 60°) below which movement counts as forward. */
    private static final double FORWARD_ANGLE_THRESHOLD = Math.toRadians(60.0);

    /** Forward run speed multiplier (relative to {@link #MOVE_SPEED}). */
    private static final double FORWARD_SPEED_MULTIPLIER = 1.0;

    /** Backward run speed multiplier (65% of forward). */
    private static final double BACKWARD_SPEED_MULTIPLIER = 0.65;

    /** Strafe run speed multiplier (80% of forward). */
    private static final double STRAFE_SPEED_MULTIPLIER = 0.8;

    /** Minimum direction change (radians) to re-send a velocity update. */
    private static final double DIRECTION_CHANGE_THRESHOLD = 0.05;

    /** Asset ID of the {@code DisablePrimary} entity effect. */
    private static final String DISABLE_PRIMARY_EFFECT_ID = "DisablePrimary";

    /** Maximum distance (blocks) for a left-click to count as a melee attack. */
    private static final double ATTACK_RANGE = 3.5;

    /**
     * VelocityConfig with zero decay — velocity persists until explicitly cleared.
     */
    private static final com.hypixel.hytale.protocol.VelocityConfig NO_DECAY_CONFIG =
            new com.hypixel.hytale.protocol.VelocityConfig(
                    1.0F, 0.0F, 1.0F, 0.0F, 0.0F,
                    VelocityThresholdStyle.Linear
            );

    /**
     * ±180° yaw range. Makes the client's idle body-clamping check
     * {@code AngleRange.Max != π && AngleRange.Min != -π} evaluate to {@code false},
     * so body orientation is never forced toward {@code LookOrientation}.
     */
    private static final CameraAxis FULL_YAW_RANGE = new CameraAxis(
            new Rangef(-180.0F, 180.0F), new CameraNode[]{CameraNode.Head}
    );

    // ── Per-player State ─────────────────────────────────────────────────

    /**
     * Consolidated per-player state for click-to-move.
     *
     * <p>Fields marked {@code volatile} are written by the world thread (events/ticks)
     * and read by the network thread (packet filters). All other fields are
     * accessed exclusively on the world thread.</p>
     */
    static final class PlayerState {

        // ── Set once on enable, read by outbound filter (network thread) ──

        /** Entity network ID for the outbound equipment filter. */
        volatile int networkId;

        // ── Written by world thread, read by network thread (inbound filter) ──

        /** Desired movement yaw (radians). Set every tick while walking. */
        @Nullable volatile Float desiredYaw;

        /** Look yaw (radians) set on click. Used as fallback body orientation. */
        @Nullable volatile Float lookYaw;

        // ── World thread only ──

        /** Current movement target (block coordinates), or null if idle. */
        @Nullable Vector3i target;

        /** Last known target block from any mouse event. Used for held-button continuation. */
        @Nullable Vector3i lastKnownTargetBlock;

        /** Last movement direction angle (radians). Used to detect direction changes. */
        @Nullable Double lastDirectionAngle;

        /** Current movement animation ID, or null when idle. */
        @Nullable String currentAnim;

        /** Whether the left mouse button is currently held. */
        boolean leftButtonHeld;

        /** Whether the cursor is over an attackable entity in range. */
        boolean attackableHover;

        /** Tick counter for periodic debug logging. */
        int debugTickCounter;

        /** Original player model saved before widening yaw range. */
        @Nullable Model savedOriginalModel;
    }

    // ── Instance Fields ──────────────────────────────────────────────────

    /** All active click-to-move players. Presence = enabled. */
    private final Map<UUID, PlayerState> players = new ConcurrentHashMap<>();

    /** Monotonically incrementing teleport ID for {@link ClientTeleport} packets. */
    private byte nextTeleportId = 0;

    private final EventRegistry eventRegistry;
    private final PacketFilter rotationFilter;
    private final PacketFilter equipmentFilter;

    // ── Constructor ──────────────────────────────────────────────────────

    /**
     * Creates a new ClickToMoveManager and registers event listeners and packet filters.
     */
    public ClickToMoveManager() {
        this.eventRegistry = new EventRegistry(
                new CopyOnWriteArrayList<>(), () -> true, "ClickToMoveManager",
                HytaleServer.get().getEventBus()
        );
        this.eventRegistry.enable();
        this.eventRegistry.register(PlayerMouseButtonEvent.class, this::onMouseButton);
        this.eventRegistry.register(PlayerMouseMotionEvent.class, this::onMouseMotion);

        // ── Inbound filter ──
        // Runs on the network thread BEFORE GamePacketHandler processes the packet.
        // Overrides bodyOrientation on ClientMovement so ProcessPlayerInput applies
        // our desired yaw instead of the stale client value.
        this.rotationFilter = PacketAdapters.registerInbound((PlayerRef playerRef, com.hypixel.hytale.protocol.Packet packet) -> {
            if (!(packet instanceof ClientMovement movement)) return false;

            // PlayerState state = players.get(playerRef.getUuid());
            // if (state == null) return false;

            // // Priority: movement direction (while walking) → click direction → pass through.
            // Float yaw = state.desiredYaw;
            // Float lookYaw = state.lookYaw;
            // if (yaw != null) {
            //     movement.bodyOrientation = new Direction(yaw, 0.0F, 0.0F);
            // } else if (lookYaw != null) {
            //     movement.bodyOrientation = new Direction(lookYaw, 0.0F, 0.0F);
            // }

            return false;
        });

        // ── Outbound filter ──
        // Strips lookOrientation from self-entity TransformUpdate (prevents
        // PlayerEntity.SetTransform → SetRotation camera snap) and removes
        // EquipmentUpdate (prevents redundant SetCharacterItem body recalc).
        this.equipmentFilter = PacketAdapters.registerOutbound((PlayerRef playerRef, com.hypixel.hytale.protocol.Packet packet) -> {
            if (!(packet instanceof EntityUpdates entityUpdates)) return false;
            if (entityUpdates.updates == null) return false;

            PlayerState state = players.get(playerRef.getUuid());
            if (state == null) return false;

            int selfNetworkId = state.networkId;
            if (selfNetworkId == 0) return false;

            // for (EntityUpdate entityUpdate : entityUpdates.updates) {
            //     if (entityUpdate.networkId != selfNetworkId) continue;
            //     if (entityUpdate.updates == null) break;

            //     boolean modified = false;
            //     for (ComponentUpdate cu : entityUpdate.updates) {
            //         if (cu instanceof TransformUpdate tu) {
            //             tu.transform.lookOrientation = null;
            //             modified = true;
            //         }
            //     }

            //     ComponentUpdate[] filtered = Arrays.stream(entityUpdate.updates)
            //             .filter(cu -> !(cu instanceof EquipmentUpdate))
            //             .toArray(ComponentUpdate[]::new);

            //     if (filtered.length != entityUpdate.updates.length) {
            //         entityUpdate.updates = filtered.length > 0 ? filtered : null;
            //         modified = true;
            //     }

            //     if (modified) {
            //         LOGGER.at(Level.FINE).log("[CTM] Filtered self-entity updates for %s",
            //                 playerRef.getUuid());
            //     }
            //     break;
            // }
            return false;
        });
    }

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Enables click-to-move for a player. Widens the player model's
     * {@code Yaw.AngleRange} to ±180° and applies the {@code DisablePrimary}
     * entity effect to suppress attack on left click.
     *
     * @param uuid  the player's UUID
     * @param store the entity store (must be on the world thread)
     * @param ref   the entity reference
     */
    public void enable(@Nonnull UUID uuid,
                       @Nonnull Store<EntityStore> store,
                       @Nonnull Ref<EntityStore> ref) {
        PlayerState state = new PlayerState();

        NetworkId networkIdComponent = store.getComponent(ref, NetworkId.getComponentType());
        if (networkIdComponent != null) {
            state.networkId = networkIdComponent.getId();
        }

        players.put(uuid, state);
        widenModelAngleRange(state, uuid, store, ref);
        applyDisablePrimaryEffect(ref, store);
    }

    /**
     * Disables click-to-move for a player and clears any active movement.
     * Restores the original player model's {@code Yaw.AngleRange}.
     *
     * @param uuid  the player's UUID
     * @param store the entity store (must be on the world thread)
     * @param ref   the entity reference
     */
    public void disable(@Nonnull UUID uuid,
                        @Nonnull Store<EntityStore> store,
                        @Nonnull Ref<EntityStore> ref) {
        PlayerState state = players.remove(uuid);
        if (state == null) return;

        restoreModelAngleRange(state, uuid, store, ref);
        removeDisablePrimaryEffect(ref, store);

        if (state.target != null || state.currentAnim != null) {
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef != null) {
                stopMovement(ref, store, playerRef, state);
            }
        }
    }

    /**
     * Disables click-to-move using only the UUID (no store/ref).
     * Resolves the player from the universe and schedules cleanup on the world thread.
     *
     * @param uuid the player's UUID
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
            restoreModelAngleRange(state, uuid, store, ref);
            removeDisablePrimaryEffect(ref, store);
            if (state.target != null || state.currentAnim != null) {
                stopMovement(ref, store, playerRef, state);
            }
        });
    }

    /**
     * Checks if click-to-move is enabled for a player.
     */
    public boolean isEnabled(@Nonnull UUID uuid) {
        return players.containsKey(uuid);
    }

    /**
     * Shuts down this manager and unregisters all event listeners and packet filters.
     */
    public void shutdown() {
        PacketAdapters.deregisterInbound(this.rotationFilter);
        PacketAdapters.deregisterOutbound(this.equipmentFilter);
        this.eventRegistry.shutdownAndCleanup(false);
        this.players.clear();
    }

    // ── Event Handlers ───────────────────────────────────────────────────

    private void onMouseButton(@Nonnull PlayerMouseButtonEvent event) {
        Ref<EntityStore> ref = event.getPlayerRef();
        if (!ref.isValid()) return;

        Store<EntityStore> store = ref.getStore();
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null) return;

        UUID uuid = playerRef.getUuid();
        PlayerState state = players.get(uuid);
        if (state == null) return;

        if (event.getMouseButton().mouseButtonType == MouseButtonType.Left) {
            if (event.getMouseButton().state == MouseButtonState.Pressed) {
                // ── Check for entity attack ─────────────────────────────
                Entity targetEntity = event.getTargetEntity();
                if (targetEntity instanceof LivingEntity && !(targetEntity instanceof Player)) {
                    if (isInAttackRange(ref, store, targetEntity)) {
                        state.leftButtonHeld = false;
                        if (state.target != null) {
                            state.lastDirectionAngle = null;
                            stopMovement(ref, store, playerRef, state);
                        }
                        LOGGER.at(Level.INFO).log("[CTM] Attack on %s allowed for %s",
                                targetEntity.getClass().getSimpleName(), uuid);
                        return; // Let the interaction chain handle the attack
                    }
                }

                // ── Ground click or out-of-range entity — click-to-move ──
                event.setCancelled(true);
                state.leftButtonHeld = true;

                Vector3i targetBlock = event.getTargetBlock();
                if (targetBlock != null) {
                    state.lastKnownTargetBlock = targetBlock;
                    state.target = targetBlock;

                    // Immediately rotate the body toward the clicked location
                    TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
                    if (transform != null) {
                        Vector3d pos = transform.getPosition();
                        double dx = targetBlock.getX() + 0.5 - pos.x;
                        double dz = targetBlock.getZ() + 0.5 - pos.z;
                        float clickYaw = (float) Math.atan2(-dx, -dz);
                        state.lookYaw = clickYaw;

                        // Update server-side rotation so the tracker broadcasts correct yaw
                        transform.getRotation().setYaw(clickYaw);
                        HeadRotation head = store.getComponent(ref, HeadRotation.getComponentType());
                        if (head != null) {
                            head.getRotation().setYaw(clickYaw);
                        }

                        // Body-only teleport — lookOrientation=null avoids camera snap
                        sendRotationSync(playerRef, clickYaw);
                    }

                    // Visual feedback
                    Vector3d particlePos = new Vector3d(
                            targetBlock.getX() + 0.5,
                            targetBlock.getY() + 1.0,
                            targetBlock.getZ() + 0.5
                    );
                    ParticleUtil.spawnParticleEffect("Block_Break_Ore", particlePos, store);
                }
            } else {
                // Released — movement continues to current target
                event.setCancelled(true);
                state.leftButtonHeld = false;
            }
        } else if (event.getMouseButton().mouseButtonType == MouseButtonType.Right
                && event.getMouseButton().state == MouseButtonState.Pressed) {
            // Right-click cancels movement
            event.setCancelled(true);
            state.leftButtonHeld = false;
            if (state.target != null) {
                state.lastDirectionAngle = null;
                stopMovement(ref, store, playerRef, state);
            }
        } else {
            event.setCancelled(true);
        }
    }

    private void onMouseMotion(@Nonnull PlayerMouseMotionEvent event) {
        Ref<EntityStore> ref = event.getPlayerRef();
        if (!ref.isValid()) return;

        Store<EntityStore> store = ref.getStore();
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null) return;

        UUID uuid = playerRef.getUuid();
        PlayerState state = players.get(uuid);
        if (state == null) return;

        // ── Hover detection — toggle DisablePrimary based on cursor target ──
        Entity targetEntity = event.getTargetEntity();
        boolean isOverAttackable = targetEntity instanceof LivingEntity
                && !(targetEntity instanceof Player)
                && isInAttackRange(ref, store, targetEntity);

        if (isOverAttackable && !state.attackableHover) {
            removeDisablePrimaryEffect(ref, store);
            state.attackableHover = true;
        } else if (!isOverAttackable && state.attackableHover) {
            applyDisablePrimaryEffect(ref, store);
            state.attackableHover = false;
        }

        // ── Drag-to-move: update destination while left button is held ──
        if (event.getMouseMotion() == null || event.getMouseMotion().mouseButtonType == null) return;

        boolean leftHeld = false;
        for (MouseButtonType button : event.getMouseMotion().mouseButtonType) {
            if (button == MouseButtonType.Left) {
                leftHeld = true;
                break;
            }
        }
        if (!leftHeld) return;

        Vector3i targetBlock = event.getTargetBlock();
        if (targetBlock != null) {
            state.lastKnownTargetBlock = targetBlock;
            state.target = targetBlock;
        }
    }

    // ── ECS System API ───────────────────────────────────────────────────

    /**
     * Checks if the given player needs click-to-move processing this tick.
     *
     * @param uuid the player's UUID
     * @return true if the player has an active target or is holding the left button
     */
    boolean needsProcessing(@Nonnull UUID uuid) {
        PlayerState state = players.get(uuid);
        return state != null && (state.target != null || state.leftButtonHeld);
    }

    /**
     * Processes one tick of click-to-move movement for a player.
     * Called by {@link ClickToMoveTickSystem} on the world thread at 30 TPS.
     *
     * @param ref       the entity reference
     * @param store     the entity store
     * @param playerRef the player reference
     * @param uuid      the player's UUID
     */
    void tickMovement(@Nonnull Ref<EntityStore> ref,
                      @Nonnull Store<EntityStore> store,
                      @Nonnull PlayerRef playerRef,
                      @Nonnull UUID uuid) {
        PlayerState state = players.get(uuid);
        if (state == null) return;

        Vector3i target = state.target;
        if (target == null) {
            // No active target, but button is held — re-set from last known position
            if (state.leftButtonHeld && state.lastKnownTargetBlock != null) {
                target = state.lastKnownTargetBlock;
                state.target = target;
            }
            if (target == null) return;
        }
        updateMovement(ref, store, playerRef, target, state);
    }

    // ── Movement Logic ───────────────────────────────────────────────────

    /**
     * Core movement tick: checks arrival, updates velocity/rotation, manages animation.
     * Must be called on the world thread.
     */
    private void updateMovement(@Nonnull Ref<EntityStore> ref,
                                 @Nonnull Store<EntityStore> store,
                                 @Nonnull PlayerRef playerRef,
                                 @Nonnull Vector3i target,
                                 @Nonnull PlayerState state) {
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) return;

        Vector3d playerPos = transform.getPosition();
        double dx = target.getX() + 0.5 - playerPos.x;
        double dz = target.getZ() + 0.5 - playerPos.z;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        if (horizontalDist < ARRIVAL_THRESHOLD) {
            // Arrived at target
            state.target = null;
            state.lastDirectionAngle = null;
            state.debugTickCounter = 0;

            // If left button is still held, project a new target further ahead
            if (state.leftButtonHeld && state.desiredYaw != null) {
                float yaw = state.desiredYaw;
                double projDx = -Math.sin(yaw) * 16.0;
                double projDz = -Math.cos(yaw) * 16.0;
                Vector3i nextTarget = new Vector3i(
                        (int) Math.floor(playerPos.x + projDx),
                        target.getY(),
                        (int) Math.floor(playerPos.z + projDz)
                );
                state.target = nextTarget;
                state.lastKnownTargetBlock = nextTarget;
                return;
            }

            stopMovement(ref, store, playerRef, state);
            return;
        }

        // Direction and yaw
        double currentAngle = Math.atan2(dz, dx);
        float desiredYaw = (float) Math.atan2(-dx, -dz);
        state.desiredYaw = desiredYaw;

        // Animation selection (before velocity, to detect zone changes)
        String targetAnim = selectMovementAnimation(desiredYaw, state.lookYaw);
        boolean animChanged = !targetAnim.equals(state.currentAnim);

        // Velocity with directional speed scaling
        double speedMultiplier = getDirectionalSpeedMultiplier(desiredYaw, state.lookYaw);
        double adjustedSpeed = MOVE_SPEED * speedMultiplier;
        double scale = adjustedSpeed / horizontalDist;
        double vx = dx * scale;
        double vz = dz * scale;

        // Re-send velocity when direction or speed zone changes
        boolean needsVelocityUpdate = (state.lastDirectionAngle == null)
                || Math.abs(angleDifference(currentAngle, state.lastDirectionAngle)) > DIRECTION_CHANGE_THRESHOLD
                || animChanged;

        if (needsVelocityUpdate) {
            sendVelocity(playerRef, (float) vx, (float) vz);
            state.lastDirectionAngle = currentAngle;

            // Sync body orientation — use lookYaw if available so body faces cursor
            Float lookYaw = state.lookYaw;
            float syncYaw = lookYaw != null ? lookYaw : desiredYaw;
            sendRotationSync(playerRef, syncYaw);

            LOGGER.at(Level.INFO).log("[CTM] velocity+yaw: yaw=%.1f° speed=%.1f dx=%.2f dz=%.2f",
                    Math.toDegrees(desiredYaw), adjustedSpeed, dx, dz);
        }

        // Periodic debug logging (~2 seconds)
        if (++state.debugTickCounter % 40 == 0) {
            com.hypixel.hytale.math.vector.Vector3f bodyRot = transform.getRotation();
            LOGGER.at(Level.INFO).log("[CTM] rotation: body=%.3f desired=%.3f pos=(%.1f, %.1f)",
                    bodyRot.getYaw(), desiredYaw, playerPos.x, playerPos.z);
        }

        // Animation transitions
        if (animChanged) {
            AnimationUtils.playAnimation(ref, AnimationSlot.Status, targetAnim, true, store);
            if (state.currentAnim == null) {
                setRunningState(store, ref);
            }
            state.currentAnim = targetAnim;
        }
    }

    // ── Rotation Sync ────────────────────────────────────────────────────

    /**
     * Sends a {@link ClientTeleport} to set the owning client's body rotation.
     *
     * <p>Body-only (lookOrientation = null) to avoid the camera snap caused by
     * {@code PlayerEntity.SetTransform → CameraModule.Controller.SetRotation}.
     * The ±180° AngleRange prevents {@code UpdateWithoutPosition} from overriding
     * the body yaw.</p>
     *
     * @param playerRef the player reference
     * @param yaw       the desired body yaw in radians
     */
    private void sendRotationSync(@Nonnull PlayerRef playerRef, float yaw) {
        Direction bodyDir = new Direction(yaw, 0.0F, 0.0F);
        ModelTransform modelTransform = new ModelTransform(null, bodyDir, null);
        ClientTeleport teleport = new ClientTeleport(nextTeleportId++, modelTransform, false);
        playerRef.getPacketHandler().writeNoCache(teleport);
    }

    /**
     * Sends a {@link ChangeVelocity} with no-decay config.
     * {@code ChangeVelocityType.Set} replaces any previous applied velocities on the client.
     */
    private static void sendVelocity(@Nonnull PlayerRef playerRef, float vx, float vz) {
        playerRef.getPacketHandler().writeNoCache(
                new ChangeVelocity(vx, 0.0F, vz, ChangeVelocityType.Set, NO_DECAY_CONFIG)
        );
    }

    // ── Movement State ───────────────────────────────────────────────────

    /**
     * Stops all click-to-move movement: zeroes velocity, clears animation,
     * and resets movement states. Must be called on the world thread.
     */
    private void stopMovement(@Nonnull Ref<EntityStore> ref,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull PlayerRef playerRef,
                               @Nonnull PlayerState state) {
        sendVelocity(playerRef, 0.0F, 0.0F);
        AnimationUtils.stopAnimation(ref, AnimationSlot.Status, true, store);
        state.target = null;
        state.currentAnim = null;
        state.desiredYaw = null;
        setIdleState(store, ref);
    }

    private static void setRunningState(@Nonnull Store<EntityStore> store,
                                         @Nonnull Ref<EntityStore> ref) {
        MovementStatesComponent movementStates = store.getComponent(ref, MovementStatesComponent.getComponentType());
        if (movementStates == null) return;

        MovementStates states = movementStates.getMovementStates();
        states.idle = false;
        states.horizontalIdle = false;
        states.walking = false;
        states.running = true;
        states.onGround = true;
    }

    private static void setIdleState(@Nonnull Store<EntityStore> store,
                                      @Nonnull Ref<EntityStore> ref) {
        MovementStatesComponent movementStates = store.getComponent(ref, MovementStatesComponent.getComponentType());
        if (movementStates == null) return;

        MovementStates states = movementStates.getMovementStates();
        states.idle = true;
        states.horizontalIdle = true;
        states.walking = false;
        states.running = false;
        states.onGround = true;
    }

    // ── Animation & Speed Helpers ────────────────────────────────────────

    /**
     * Returns the smallest signed angle difference between two angles in radians.
     */
    private static double angleDifference(double a, double b) {
        double diff = a - b;
        while (diff > Math.PI) diff -= 2 * Math.PI;
        while (diff < -Math.PI) diff += 2 * Math.PI;
        return diff;
    }

    /**
     * Checks whether the given entity is within {@link #ATTACK_RANGE} of the player.
     */
    private static boolean isInAttackRange(@Nonnull Ref<EntityStore> playerRef,
                                            @Nonnull Store<EntityStore> store,
                                            @Nonnull Entity target) {
        TransformComponent playerTransform = store.getComponent(playerRef, TransformComponent.getComponentType());
        if (playerTransform == null) return false;

        TransformComponent targetTransform = target.getTransformComponent();
        if (targetTransform == null) return false;

        Vector3d playerPos = playerTransform.getPosition();
        Vector3d targetPos = targetTransform.getPosition();
        double dx = targetPos.x - playerPos.x;
        double dz = targetPos.z - playerPos.z;
        return Math.sqrt(dx * dx + dz * dz) <= ATTACK_RANGE;
    }

    /**
     * Selects Run or RunBackward based on movement-vs-look angle.
     */
    @Nonnull
    private static String selectMovementAnimation(float movementYaw, @Nullable Float lookYaw) {
        if (lookYaw == null) return RUN_ANIMATION;
        double delta = angleDifference(movementYaw, lookYaw);
        return (Math.abs(delta) > BACKWARD_ANGLE_THRESHOLD) ? RUN_BACKWARD_ANIMATION : RUN_ANIMATION;
    }

    /**
     * Returns a directional speed multiplier matching the client's
     * {@code DefaultMovementController} base constants.
     */
    private static double getDirectionalSpeedMultiplier(float movementYaw, @Nullable Float lookYaw) {
        if (lookYaw == null) return FORWARD_SPEED_MULTIPLIER;
        double delta = Math.abs(angleDifference(movementYaw, lookYaw));
        if (delta < FORWARD_ANGLE_THRESHOLD) return FORWARD_SPEED_MULTIPLIER;
        if (delta >= BACKWARD_ANGLE_THRESHOLD) return BACKWARD_SPEED_MULTIPLIER;
        return STRAFE_SPEED_MULTIPLIER;
    }

    // ── Model AngleRange Manipulation ────────────────────────────────────

    /**
     * Replaces the player's {@link ModelComponent} with a copy whose
     * {@code Yaw.AngleRange} is ±180°, preventing the client's
     * {@code UpdateWithoutPosition} from forcing body orientation.
     * Also re-sends the skin to prevent skin loss on model update.
     */
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
                FULL_YAW_RANGE,
                originalCamera != null ? originalCamera.getPitch() : null
        );

        Model newModel = new Model(
                original.getModelAssetId(),
                original.getScale(),
                original.getRandomAttachmentIds(),
                original.getAttachments(),
                original.getBoundingBox(),
                original.getModel(),
                original.getTexture(),
                original.getGradientSet(),
                original.getGradientId(),
                original.getEyeHeight(),
                original.getCrouchOffset(),
                original.getSittingOffset(),
                original.getSleepingOffset(),
                original.getAnimationSetMap(),
                newCamera,
                original.getLight(),
                original.getParticles(),
                original.getTrails(),
                original.getPhysicsValues(),
                original.getDetailBoxes(),
                original.getPhobia(),
                original.getPhobiaModelAssetId()
        );

        store.putComponent(ref, ModelComponent.getComponentType(), new ModelComponent(newModel));

        PlayerSkinComponent skinComponent = store.getComponent(ref, PlayerSkinComponent.getComponentType());
        if (skinComponent != null) {
            skinComponent.setNetworkOutdated();
        }

        LOGGER.at(Level.INFO).log("[CTM] Widened Yaw.AngleRange to ±180° for %s", uuid);
    }

    /**
     * Restores the player's original {@link ModelComponent} saved during
     * {@link #widenModelAngleRange}.
     */
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

        LOGGER.at(Level.INFO).log("[CTM] Restored original model for %s", uuid);
    }

    // ── Entity Effect Helpers ────────────────────────────────────────────

    /**
     * Applies the {@code DisablePrimary} entity effect to suppress attack on left click.
     */
    private void applyDisablePrimaryEffect(@Nonnull Ref<EntityStore> ref,
                                            @Nonnull Store<EntityStore> store) {
        EntityEffect effect = EntityEffect.getAssetMap().getAsset(DISABLE_PRIMARY_EFFECT_ID);
        if (effect == null) {
            LOGGER.at(Level.WARNING).log("[CTM] EntityEffect '%s' not found", DISABLE_PRIMARY_EFFECT_ID);
            return;
        }
        EffectControllerComponent effectController =
                store.getComponent(ref, EffectControllerComponent.getComponentType());
        if (effectController == null) {
            LOGGER.at(Level.WARNING).log("[CTM] No EffectControllerComponent on entity");
            return;
        }
        effectController.addEffect(ref, effect, store);
        LOGGER.at(Level.INFO).log("[CTM] Applied %s effect", DISABLE_PRIMARY_EFFECT_ID);
    }

    /**
     * Removes the {@code DisablePrimary} entity effect, restoring attack ability.
     */
    private void removeDisablePrimaryEffect(@Nonnull Ref<EntityStore> ref,
                                             @Nonnull Store<EntityStore> store) {
        EffectControllerComponent effectController =
                store.getComponent(ref, EffectControllerComponent.getComponentType());
        if (effectController == null) return;
        int effectIndex = EntityEffect.getAssetMap().getIndex(DISABLE_PRIMARY_EFFECT_ID);
        if (effectIndex < 0) return;
        effectController.removeEffect(ref, effectIndex, store);
        LOGGER.at(Level.INFO).log("[CTM] Removed %s effect", DISABLE_PRIMARY_EFFECT_ID);
    }
}
