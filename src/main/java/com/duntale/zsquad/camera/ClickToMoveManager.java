package com.duntale.zsquad.camera;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.CameraNode;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChains;
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
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.camera.CameraAxis;
import com.hypixel.hytale.server.core.asset.type.model.config.camera.CameraSettings;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.event.events.player.PlayerMouseButtonEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerMouseMotionEvent;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.io.adapter.PacketFilter;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.World;
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
 * Click on the ground to set a destination — the player walks there automatically.
 * Drag to continuously update the destination. Right-click to cancel.
 *
 * <h3>How it works (see CLICK_TO_MOVE_RESEARCH.md for full analysis)</h3>
 *
 * <p>Hytale uses client-authoritative movement: animations are driven by {@code _wishDirection}
 * (WASD input), not by server-applied velocity. Without WASD the client always shows "Idle",
 * even when sliding via velocity. To fix this we use two complementary mechanisms:</p>
 *
 * <ol>
 *   <li><b>Velocity</b> — {@link ChangeVelocity} packets with a {@code VelocityConfig} that
 *       sets {@code groundResistance = 1.0} (no per-tick decay). The velocity persists until
 *       explicitly cleared. This replaces the old approach of re-sending velocity every tick.</li>
 *   <li><b>Animation</b> — {@code PlayAnimation} on {@link AnimationSlot#Movement} (slot 0).
 *       When {@code ServerAnimations[0]} is non-null on the client, the locally-computed
 *       {@code UpdateMovementAnimation()} is <b>completely skipped</b>. Because "Run" is a
 *       looping animation, it persists until we send {@code animationId = null} to clear it.</li>
 * </ol>
 *
 * <p>MovementStates are still set on the server for other players' benefit (the TickingSystem
 * broadcasts to all viewers <b>except</b> the entity itself).</p>
 *
 * <p>Movement is ticked via {@link ClickToMoveTickSystem}, a 30 TPS ECS system that runs
 * on the world thread after {@code ProcessPlayerInput}. This manager handles state and events;
 * the ECS system calls {@link #tickMovement} each server tick for each active player.</p>
 */
public class ClickToMoveManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** How close (in blocks) the player must be to the target to stop moving. */
    private static final double ARRIVAL_THRESHOLD = 1.0;

    /** Movement speed in blocks/second. */
    private static final double MOVE_SPEED = 8.0;

    /** Movement animation ID to play on the Movement slot. */
    private static final String RUN_ANIMATION = "Run";

    /**
     * Minimum direction change (in radians) to re-send a velocity update.
     * Avoids spamming packets when direction barely changes between ticks.
     */
    private static final double DIRECTION_CHANGE_THRESHOLD = 0.05;

    /**
     * VelocityConfig sent alongside ChangeVelocity packets.
     * {@code groundResistance = 1.0} means zero decay — velocity persists until explicitly cleared.
     * {@code groundResistanceMax = 0.0} with {@code threshold = 0.0} disables threshold-based blending.
     */
    private static final com.hypixel.hytale.protocol.VelocityConfig NO_DECAY_CONFIG =
            new com.hypixel.hytale.protocol.VelocityConfig(
                    1.0F,  // groundResistance — no decay on ground
                    0.0F,  // groundResistanceMax — irrelevant with threshold=0
                    1.0F,  // airResistance — no decay in air
                    0.0F,  // airResistanceMax
                    0.0F,  // threshold — disable blending (velocity never drops below this)
                    VelocityThresholdStyle.Linear
            );

    /** Per-player target position (block coordinates). Present = actively moving. */
    private final Map<UUID, Vector3i> activeTargets = new ConcurrentHashMap<>();

    /** Tracks which players have click-to-move enabled. */
    private final Map<UUID, Boolean> enabledPlayers = new ConcurrentHashMap<>();

    /** Per-player last movement direction angle (radians). Used to detect direction changes. */
    private final Map<UUID, Double> lastDirectionAngle = new ConcurrentHashMap<>();

    /** Tracks whether each player currently has an active run animation override. */
    private final Map<UUID, Boolean> animationActive = new ConcurrentHashMap<>();

    /** Per-player desired yaw (radians). Set every tick; used to override client rotation. */
    private final Map<UUID, Float> desiredYawMap = new ConcurrentHashMap<>();

    /** Per-player left mouse button held state. Updated from mouse button events. */
    private final Map<UUID, Boolean> leftButtonHeld = new ConcurrentHashMap<>();

    /** Per-player last known target block from any mouse event. Used for held-button continuation. */
    private final Map<UUID, Vector3i> lastKnownTargetBlock = new ConcurrentHashMap<>();

    /** Per-player tick counter for periodic debug logging. */
    private final Map<UUID, Integer> debugTickCounter = new ConcurrentHashMap<>();

    /**
     * Per-player camera pitch (radians). Stored on enable so that the inbound
     * rotation filter can keep server-side entity state consistent for remote viewers.
     */
    private final Map<UUID, Float> cameraPitchMap = new ConcurrentHashMap<>();

    /**
     * Per-player entity network ID. Cached on {@link #enable} so the outbound
     * equipment filter can identify the player's own entity in {@link EntityUpdates} packets.
     */
    private final Map<UUID, Integer> playerNetworkIds = new ConcurrentHashMap<>();

    /**
     * Per-player original {@link Model} saved before widening {@code Yaw.AngleRange}.
     * Restored on {@link #disable} to return the player model to normal.
     */
    private final Map<UUID, Model> savedOriginalModels = new ConcurrentHashMap<>();

    private final EventRegistry eventRegistry;

    /**
     * Inbound packet filter that intercepts {@link ClientMovement} packets
     * and replaces body/head orientation with our desired movement yaw.
     * Registered via {@link PacketAdapters#registerInbound(PacketFilter)}.
     */
    private final PacketFilter rotationFilter;

    /**
     * Outbound packet filter that strips {@link EquipmentUpdate} from {@link EntityUpdates}
     * packets being sent to the owning player. The Hytale client handles hotbar/equipment
     * changes locally; the server's redundant {@link EquipmentUpdate} triggers
     * {@code SetCharacterItem} a second time, which rebuilds item camera settings and can
     * cause the body orientation to be recalculated from the look direction.
     */
    private final PacketFilter equipmentFilter;

    /**
     * Creates a new ClickToMoveManager and registers mouse event listeners.
     */
    public ClickToMoveManager() {
        this.eventRegistry = new EventRegistry(
                new CopyOnWriteArrayList<>(), () -> true, "ClickToMoveManager",
                HytaleServer.get().getEventBus()
        );
        this.eventRegistry.enable();
        this.eventRegistry.register(PlayerMouseButtonEvent.class, this::onMouseButton);
        this.eventRegistry.register(PlayerMouseMotionEvent.class, this::onMouseMotion);

        // Register inbound packet filter to override client rotation on ClientMovement packets.
        // The filter runs on the network thread BEFORE GamePacketHandler processes the packet,
        // so we can mutate bodyOrientation/lookOrientation before they get queued as SetBody/SetHead.
        this.rotationFilter = PacketAdapters.registerInbound((PlayerRef playerRef, com.hypixel.hytale.protocol.Packet packet) -> {
            if (packet instanceof SyncInteractionChains chains) return true;
            if (!(packet instanceof ClientMovement movement)) return false;

            UUID uuid = playerRef.getUuid();
            Float yaw = desiredYawMap.get(uuid);
            if (yaw == null) return false; // No active movement — let packet through unmodified

            // Replace client's body/head orientation with our desired movement yaw.
            // lookOrientation uses the camera pitch so the server-side entity state
            // matches the TransformUpdate we send, preventing tracker contradictions.
            Direction bodyDir = new Direction(yaw, 0.0F, 0.0F);
            Float cameraPitch = cameraPitchMap.get(uuid);
            Direction lookDir = new Direction(yaw, cameraPitch != null ? cameraPitch : 0.0F, 0.0F);
            movement.bodyOrientation = bodyDir;
            movement.lookOrientation = lookDir;

            return false; // Don't drop the packet — let it through with modified orientation
        });

        // Register outbound packet filter to strip EquipmentUpdate for click-to-move players.
        // The LegacyEntityTrackerSystems sends EquipmentUpdate to ALL viewers (including self),
        // but the client already handles hotbar/equipment changes locally via
        // InventoryModule.SetActiveHotbarSlot → ChangeCharacterItem. The redundant server
        // EquipmentUpdate triggers SetCharacterItem a second time, which rebuilds item camera
        // settings and can recalculate body orientation from the look direction.
        this.equipmentFilter = PacketAdapters.registerOutbound((PlayerRef playerRef, com.hypixel.hytale.protocol.Packet packet) -> {
            if (!(packet instanceof EntityUpdates entityUpdates)) return false;
            if (entityUpdates.updates == null) return false;

            UUID uuid = playerRef.getUuid();
            if (!isEnabled(uuid)) return false;

            Integer selfNetworkId = playerNetworkIds.get(uuid);
            if (selfNetworkId == null) return false;

            for (EntityUpdate entityUpdate : entityUpdates.updates) {
                if (entityUpdate.networkId != selfNetworkId) continue;
                if (entityUpdate.updates == null) break;

                // Filter out EquipmentUpdate entries from the self-entity's component updates
                ComponentUpdate[] filtered = Arrays.stream(entityUpdate.updates)
                        .filter(cu -> !(cu instanceof EquipmentUpdate))
                        .toArray(ComponentUpdate[]::new);

                if (filtered.length != entityUpdate.updates.length) {
                    entityUpdate.updates = filtered.length > 0 ? filtered : null;
                    LOGGER.at(Level.FINE).log("[CTM] Stripped EquipmentUpdate from self-entity packet for %s", uuid);
                }
                break; // Only one entry per entity
            }
            return false; // Let the (potentially modified) packet through
        });
    }

    /**
     * Enables click-to-move for a player. Widens the player model's
     * {@code Yaw.AngleRange} to ±180° so the client's
     * {@code PlayerEntity.UpdateWithoutPosition()} idle branch does not
     * force-rotate the body toward the frozen {@code LookOrientation}.
     *
     * @param uuid        the player's UUID
     * @param cameraPitch the camera's pitch in radians (e.g., -π/2 for top-down, -π/4 for isometric).
     *                    Used by the inbound rotation filter for remote viewer consistency.
     * @param store       the entity store (must be on the world thread)
     * @param ref         the entity reference
     */
    public void enable(@Nonnull UUID uuid, float cameraPitch,
                       @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        enabledPlayers.put(uuid, true);
        cameraPitchMap.put(uuid, cameraPitch);

        // Cache the entity's network ID for the outbound equipment filter
        NetworkId networkIdComponent = store.getComponent(ref, NetworkId.getComponentType());
        if (networkIdComponent != null) {
            playerNetworkIds.put(uuid, networkIdComponent.getId());
        }

        widenModelAngleRange(uuid, store, ref);
    }

    /**
     * Disables click-to-move for a player and clears any active movement.
     * If the player is currently moving, stops velocity and animation.
     * Restores the original player model's {@code Yaw.AngleRange}.
     *
     * @param uuid  the player's UUID
     * @param store the entity store (must be on the world thread)
     * @param ref   the entity reference
     */
    public void disable(@Nonnull UUID uuid,
                        @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        enabledPlayers.remove(uuid);
        boolean wasMoving = activeTargets.remove(uuid) != null;
        lastDirectionAngle.remove(uuid);
        debugTickCounter.remove(uuid);
        desiredYawMap.remove(uuid);
        leftButtonHeld.remove(uuid);
        lastKnownTargetBlock.remove(uuid);
        boolean hadAnimation = animationActive.remove(uuid) != null;
        cameraPitchMap.remove(uuid);
        playerNetworkIds.remove(uuid);

        restoreModelAngleRange(uuid, store, ref);

        if (wasMoving || hadAnimation) {
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef != null) {
                stopMovement(ref, store, playerRef);
            }
        }
    }

    /**
     * Disables click-to-move for a player using only the UUID (no store/ref).
     * Resolves the player from the universe and schedules cleanup on the world thread.
     * Use the overload with {@code store}/{@code ref} when already on the world thread.
     *
     * @param uuid the player's UUID
     */
    public void disable(@Nonnull UUID uuid) {
        enabledPlayers.remove(uuid);
        boolean wasMoving = activeTargets.remove(uuid) != null;
        lastDirectionAngle.remove(uuid);
        debugTickCounter.remove(uuid);
        desiredYawMap.remove(uuid);
        leftButtonHeld.remove(uuid);
        lastKnownTargetBlock.remove(uuid);
        boolean hadAnimation = animationActive.remove(uuid) != null;
        cameraPitchMap.remove(uuid);
        playerNetworkIds.remove(uuid);

        PlayerRef playerRef = Universe.get().getPlayer(uuid);
        if (playerRef != null) {
            Ref<EntityStore> ref = playerRef.getReference();
            if (ref != null && ref.isValid()) {
                Store<EntityStore> store = ref.getStore();
                World world = store.getExternalData().getWorld();
                world.execute(() -> {
                    if (!ref.isValid()) return;
                    restoreModelAngleRange(uuid, store, ref);
                    if (wasMoving || hadAnimation) {
                        stopMovement(ref, store, playerRef);
                    }
                });
            }
        }
    }

    /**
     * Checks if click-to-move is enabled for a player.
     *
     * @param uuid the player's UUID
     * @return true if enabled
     */
    public boolean isEnabled(@Nonnull UUID uuid) {
        return enabledPlayers.containsKey(uuid);
    }

    /**
     * Shuts down this manager and unregisters all event listeners.
     */
    public void shutdown() {
        PacketAdapters.deregisterInbound(this.rotationFilter);
        PacketAdapters.deregisterOutbound(this.equipmentFilter);
        this.eventRegistry.shutdownAndCleanup(false);
        this.activeTargets.clear();
        this.enabledPlayers.clear();
        this.lastDirectionAngle.clear();
        this.animationActive.clear();
        this.debugTickCounter.clear();
        this.desiredYawMap.clear();
        this.leftButtonHeld.clear();
        this.lastKnownTargetBlock.clear();
        this.cameraPitchMap.clear();
        this.playerNetworkIds.clear();
        this.savedOriginalModels.clear();
    }

    // ── Event Handlers ───────────────────────────────────────────────────

    private void onMouseButton(@Nonnull PlayerMouseButtonEvent event) {
        Ref<EntityStore> ref = event.getPlayerRef();
        if (!ref.isValid()) return;

        Store<EntityStore> store = ref.getStore();
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null) return;

        UUID uuid = playerRef.getUuid();
        if (!isEnabled(uuid)) return;

        if (event.getMouseButton().mouseButtonType == MouseButtonType.Left) {
            if (event.getMouseButton().state == MouseButtonState.Pressed) {
                leftButtonHeld.put(uuid, true);
                // Set destination — player walks there automatically
                Vector3i targetBlock = event.getTargetBlock();
                if (targetBlock != null) {
                    lastKnownTargetBlock.put(uuid, targetBlock);
                    activeTargets.put(uuid, targetBlock);

                    // Visual feedback: particle effect at the clicked destination
                    Vector3d particlePos = new Vector3d(
                            targetBlock.getX() + 0.5,
                            targetBlock.getY() + 1.0,
                            targetBlock.getZ() + 0.5
                    );
                    ParticleUtil.spawnParticleEffect("Block_Break_Ore", particlePos, store);
                }
            } else {
                // Released — stop tracking held state; movement continues to current target
                leftButtonHeld.remove(uuid);
            }
        } else if (event.getMouseButton().mouseButtonType == MouseButtonType.Right
                && event.getMouseButton().state == MouseButtonState.Pressed) {
            // Right-click cancels movement
            leftButtonHeld.remove(uuid);
            if (activeTargets.remove(uuid) != null) {
                lastDirectionAngle.remove(uuid);
                stopMovement(ref, store, playerRef);
            }
        }
    }

    private void onMouseMotion(@Nonnull PlayerMouseMotionEvent event) {
        Ref<EntityStore> ref = event.getPlayerRef();
        if (!ref.isValid()) return;

        Store<EntityStore> store = ref.getStore();
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null) return;

        UUID uuid = playerRef.getUuid();
        if (!isEnabled(uuid)) return;

        // Check if left mouse button is held during drag — update destination
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
            lastKnownTargetBlock.put(uuid, targetBlock);
            activeTargets.put(uuid, targetBlock);
        }
    }

    // ── ECS System API ───────────────────────────────────────────────────

    /**
     * Checks if the given player needs click-to-move processing this tick.
     * Either they have an active target, or they're holding the left button
     * (in which case we may need to re-set a target from the last known position).
     *
     * @param uuid the player's UUID
     * @return true if processing is needed
     */
    boolean needsProcessing(@Nonnull UUID uuid) {
        return activeTargets.containsKey(uuid) || Boolean.TRUE.equals(leftButtonHeld.get(uuid));
    }

    /**
     * Processes one tick of click-to-move movement for a player.
     * Called by {@link ClickToMoveTickSystem} on the world thread at 30 TPS.
     *
     * <p>Checks arrival, updates velocity direction if it changed significantly,
     * sends rotation sync via {@link EntityUpdates}, and starts animation on first tick.
     * If the left mouse button is held but no active target exists, re-sets the target
     * from the last known mouse position.</p>
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
        Vector3i target = activeTargets.get(uuid);
        if (target == null) {
            // No active target, but button is held — re-set from last known position
            if (Boolean.TRUE.equals(leftButtonHeld.get(uuid))) {
                Vector3i lastTarget = lastKnownTargetBlock.get(uuid);
                if (lastTarget != null) {
                    activeTargets.put(uuid, lastTarget);
                    target = lastTarget;
                }
            }
            if (target == null) return;
        }
        updateMovement(ref, store, playerRef, target, uuid);
    }

    // ── Movement Logic ───────────────────────────────────────────────────

    /**
     * Checks arrival, updates velocity direction if it changed significantly,
     * and starts animation on first tick.
     * Must be called on the world thread.
     */
    private void updateMovement(@Nonnull Ref<EntityStore> ref,
                                 @Nonnull Store<EntityStore> store,
                                 @Nonnull PlayerRef playerRef,
                                 @Nonnull Vector3i target,
                                 @Nonnull UUID uuid) {
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) return;

        Vector3d playerPos = transform.getPosition();
        double dx = target.getX() + 0.5 - playerPos.x;
        double dz = target.getZ() + 0.5 - playerPos.z;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        if (horizontalDist < ARRIVAL_THRESHOLD) {
            // Arrived at target
            activeTargets.remove(uuid);
            lastDirectionAngle.remove(uuid);
            debugTickCounter.remove(uuid);

            // If left button is still held, keep walking in the same direction
            // by projecting a new target further along the current heading.
            if (Boolean.TRUE.equals(leftButtonHeld.get(uuid)) && desiredYawMap.containsKey(uuid)) {
                float yaw = desiredYawMap.get(uuid);
                // Project 16 blocks ahead in the current heading direction
                // Hytale yaw convention: atan2(-dx, -dz), so dx = -sin(yaw), dz = -cos(yaw)
                double projDx = -Math.sin(yaw) * 16.0;
                double projDz = -Math.cos(yaw) * 16.0;
                Vector3i nextTarget = new Vector3i(
                        (int) Math.floor(playerPos.x + projDx),
                        target.getY(),
                        (int) Math.floor(playerPos.z + projDz)
                );
                activeTargets.put(uuid, nextTarget);
                lastKnownTargetBlock.put(uuid, nextTarget);
                return; // Continue walking — don't stop
            }

            stopMovement(ref, store, playerRef);
            return;
        }

        // Compute current direction angle
        double currentAngle = Math.atan2(dz, dx);

        // Normalize direction and scale to desired speed
        double scale = MOVE_SPEED / horizontalDist;
        double vx = dx * scale;
        double vz = dz * scale;

        // Check if we need to send/update velocity
        Double prevAngle = lastDirectionAngle.get(uuid);
        boolean needsVelocityUpdate = (prevAngle == null)
                || Math.abs(angleDifference(currentAngle, prevAngle)) > DIRECTION_CHANGE_THRESHOLD;

        // Compute desired yaw using Hytale convention: atan2(-dx, -dz)
        float desiredYaw = (float) Math.atan2(-dx, -dz);
        desiredYawMap.put(uuid, desiredYaw);

        if (needsVelocityUpdate) {
            sendVelocity(playerRef, (float) vx, (float) vz);
            lastDirectionAngle.put(uuid, currentAngle);

            // Sync body + look orientation to the client on direction change only.
            // With the inverted plane normal (0,-1,0), LookOrientation is frozen on the client
            // (OnMouseInput → LookAtPlane intersection always null → LookAt never called).
            // By setting both body and look to the desired yaw, the idle body-tracking code
            // sees delta = 0 (within ±45°) and leaves the body alone until the next teleport.
            // We do NOT send this every tick because ClientTeleport triggers:
            //   - SetRotation on the camera controller (jitters the camera angle)
            //   - SkipTransformLerp + InvalidateState (disrupts physics)
            sendRotationSync(store, ref, playerRef, desiredYaw);

            LOGGER.at(Level.INFO).log("[CTM] SET velocity+yaw: desiredYaw=%.3f (deg=%.1f) dx=%.2f dz=%.2f",
                    desiredYaw, Math.toDegrees(desiredYaw), dx, dz);
        }

        // Periodic debug: log actual server-side rotation values every ~2 seconds
        int tickCount = debugTickCounter.merge(uuid, 1, Integer::sum);
        if (tickCount % 40 == 0) {
            com.hypixel.hytale.math.vector.Vector3f bodyRot = transform.getRotation();
            LOGGER.at(Level.INFO).log("[CTM] READ rotation: body(yaw=%.3f) desired=%.3f | pos=(%.1f, %.1f)",
                    bodyRot.getYaw(), desiredYaw, playerPos.x, playerPos.z);
        }

        // Start animation on first tick (persists via looping — no re-send needed)
        if (!Boolean.TRUE.equals(animationActive.get(uuid))) {
            AnimationUtils.playAnimation(ref, AnimationSlot.Status, RUN_ANIMATION, true, store);
            animationActive.put(uuid, true);

            // Set movement states for remote viewers
            setRunningState(store, ref);
        }
    }

    /**
     * Sends an {@link EntityUpdates} packet with a {@link TransformUpdate} to set the
     * owning client's body rotation. Uses {@code position = null} and
     * {@code lookOrientation = null} so the client keeps its current position and
     * look direction.
     *
     * <p><b>Camera snap prevention</b>: On the client, {@code PlayerEntity.SetTransform}
     * calls {@code CameraModule.Controller.SetRotation(lookOrientation)} whenever the
     * look orientation changes. By sending {@code lookOrientation = null}, the client's
     * {@code ModelTransformHelper.Decompose} preserves the entity's current
     * {@code LookOrientation}, the {@code !=} check evaluates to {@code false}, and
     * {@code SetRotation} is never called — eliminating the camera snap entirely.</p>
     *
     * <p>Body orientation is free to rotate because the model's {@code Yaw.AngleRange}
     * is widened to ±180° on enable (see {@link #widenModelAngleRange}), which disables
     * the idle body-clamping logic in {@code PlayerEntity.UpdateWithoutPosition()}.</p>
     */
    private void sendRotationSync(@Nonnull Store<EntityStore> store,
                                   @Nonnull Ref<EntityStore> ref,
                                   @Nonnull PlayerRef playerRef,
                                   float yaw) {
        NetworkId networkIdComponent = store.getComponent(ref, NetworkId.getComponentType());
        if (networkIdComponent == null) return;

        Direction bodyDir = new Direction(yaw, 0.0F, 0.0F);
        // lookOrientation is intentionally null to avoid triggering
        // PlayerEntity.SetTransform → Controller.SetRotation on the client.
        ModelTransform modelTransform = new ModelTransform(null, bodyDir, null);
        TransformUpdate transformUpdate = new TransformUpdate(modelTransform);
        EntityUpdate entityUpdate = new EntityUpdate(
                networkIdComponent.getId(),
                null,
                new ComponentUpdate[]{transformUpdate}
        );
        EntityUpdates packet = new EntityUpdates(null, new EntityUpdate[]{entityUpdate});
        playerRef.getPacketHandler().writeNoCache(packet);
    }

    /**
     * Sends a {@link ChangeVelocity} packet directly to the player with a no-decay config.
     * Uses {@code ChangeVelocityType.Set} which clears any previous applied velocities on the client
     * and replaces them with the new one.
     */
    private static void sendVelocity(@Nonnull PlayerRef playerRef, float vx, float vz) {
        playerRef.getPacketHandler().writeNoCache(
                new ChangeVelocity(vx, 0.0F, vz, ChangeVelocityType.Set, NO_DECAY_CONFIG)
        );
    }

    /**
     * Stops all click-to-move movement: zeroes velocity, clears animation override,
     * and resets movement states. Must be called on the world thread.
     */
    private void stopMovement(@Nonnull Ref<EntityStore> ref,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull PlayerRef playerRef) {
        // Zero velocity — this clears _appliedVelocities on the client
        sendVelocity(playerRef, 0.0F, 0.0F);

        // Clear the server animation override on slot 0 (animationId = null).
        // This sets ServerAnimations[0] = null on the client, allowing
        // UpdateMovementAnimation() to resume → shows "Idle" naturally.
        AnimationUtils.stopAnimation(ref, AnimationSlot.Status, true, store);
        animationActive.remove(playerRef.getUuid());

        // Clear desired yaw so the packet filter stops overriding client rotation
        desiredYawMap.remove(playerRef.getUuid());

        // Reset movement states for remote viewers
        setIdleState(store, ref);
    }

    /**
     * Sets MovementStates to running so other players see the correct animation.
     * The TickingSystem broadcasts this to all viewers except the entity itself.
     */
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

    /**
     * Resets MovementStates to idle so other players see the player standing still.
     */
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

    /**
     * Returns the smallest signed angle difference between two angles in radians.
     */
    private static double angleDifference(double a, double b) {
        double diff = a - b;
        while (diff > Math.PI) diff -= 2 * Math.PI;
        while (diff < -Math.PI) diff += 2 * Math.PI;
        return diff;
    }

    // ── Model AngleRange Manipulation ────────────────────────────────────

    /**
     * Yaw angle range used in click-to-move mode. ±180 degrees (sent in degrees;
     * client converts to radians). This makes the client's idle body-clamping check
     * {@code AngleRange.Max != π && AngleRange.Min != -π} evaluate to {@code false},
     * so the body orientation is never forcefully rotated toward {@code LookOrientation}.
     */
    private static final CameraAxis FULL_YAW_RANGE = new CameraAxis(
            new Rangef(-180.0F, 180.0F), new CameraNode[]{CameraNode.Head}
    );

    /**
     * Replaces the player's {@link ModelComponent} with a copy whose
     * {@code Yaw.AngleRange} is widened to ±180°. Saves the original model
     * for restoration in {@link #restoreModelAngleRange}.
     *
     * <p>This is necessary because the client's {@code PlayerEntity.UpdateWithoutPosition()}
     * forces body orientation toward {@code LookOrientation} when the head-body offset
     * exceeds {@code CameraSettings.Yaw.AngleRange} (default ±45°). With ±180° the
     * constraint is never exceeded, so body orientation stays at whatever the server set.</p>
     */
    private void widenModelAngleRange(@Nonnull UUID uuid,
                                       @Nonnull Store<EntityStore> store,
                                       @Nonnull Ref<EntityStore> ref) {
        ModelComponent modelComponent = store.getComponent(ref, ModelComponent.getComponentType());
        if (modelComponent == null) return;

        Model original = modelComponent.getModel();
        savedOriginalModels.put(uuid, original);

        // Build new CameraSettings with widened yaw range, preserving positionOffset and pitch
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

        // Force PlayerSkinUpdate to be sent alongside the ModelUpdate in the same tick.
        // Without this, the client receives a ModelUpdate without a PlayerSkinUpdate,
        // sets entity.PlayerSkin = null, and calls LoadCharacterModel() instead of
        // LoadPlayerModel() — resulting in the player's custom skin being lost.
        PlayerSkinComponent skinComponent = store.getComponent(ref, PlayerSkinComponent.getComponentType());
        if (skinComponent != null) {
            skinComponent.setNetworkOutdated();
        }

        LOGGER.at(Level.INFO).log("[CTM] Widened Yaw.AngleRange to ±180° for %s", uuid);
    }

    /**
     * Restores the player's original {@link ModelComponent} saved during
     * {@link #widenModelAngleRange}, returning {@code Yaw.AngleRange} to its
     * default value.
     */
    private void restoreModelAngleRange(@Nonnull UUID uuid,
                                         @Nonnull Store<EntityStore> store,
                                         @Nonnull Ref<EntityStore> ref) {
        Model original = savedOriginalModels.remove(uuid);
        if (original == null || !ref.isValid()) return;

        store.putComponent(ref, ModelComponent.getComponentType(), new ModelComponent(original));

        // Re-send skin alongside the model restoration (same reason as widenModelAngleRange)
        PlayerSkinComponent skinComponent = store.getComponent(ref, PlayerSkinComponent.getComponentType());
        if (skinComponent != null) {
            skinComponent.setNetworkOutdated();
        }

        LOGGER.at(Level.INFO).log("[CTM] Restored original model for %s", uuid);
    }
}
