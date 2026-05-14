package com.duntale.zsquad.camera;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.MathUtil;
import org.joml.Vector3d;
import org.joml.Vector3i;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.CameraNode;
import com.hypixel.hytale.protocol.MouseButtonState;
import com.hypixel.hytale.protocol.MouseButtonType;
import com.hypixel.hytale.protocol.Rangef;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.camera.CameraAxis;
import com.hypixel.hytale.server.core.asset.type.model.config.camera.CameraSettings;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.Intangible;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent;
import com.hypixel.hytale.server.core.event.events.player.PlayerMouseButtonEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerMouseMotionEvent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.CameraManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;

import com.duntale.zsquad.rpg.RpgService;
import com.duntale.zsquad.rpg.RpgStat;
import com.duntale.zsquad.rpg.RpgStatEffects;
import com.duntale.zsquad.merchant.CatalogEntry;
import com.duntale.zsquad.companion.CompanionComponent;
import com.duntale.zsquad.merchant.MerchantComponent;
import com.duntale.zsquad.merchant.MerchantService;
import com.duntale.zsquad.ZSquadPlugin;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.protocol.ClientCameraView;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.ApplyLookType;
import com.hypixel.hytale.protocol.ApplyMovementType;
import com.hypixel.hytale.protocol.AttachedToType;
import com.hypixel.hytale.protocol.CanMoveType;
import com.hypixel.hytale.protocol.MouseInputType;
import com.hypixel.hytale.protocol.MouseInputTargetType;
import com.hypixel.hytale.protocol.MovementForceRotationType;
import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.PositionDistanceOffsetType;
import com.hypixel.hytale.protocol.PositionType;
import com.hypixel.hytale.protocol.RotationType;
import com.hypixel.hytale.protocol.ServerCameraSettings;
import com.hypixel.hytale.protocol.packets.camera.SetServerCamera;
import com.hypixel.hytale.protocol.packets.entities.PlayAnimation;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.protocol.packets.interface_.SetPage;
import com.hypixel.hytale.protocol.packets.world.PlaySoundEvent2D;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.io.adapter.PacketFilter;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketFilter;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketWatcher;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.joml.Vector2f;
import org.joml.Vector3f;

/**
 * Click-to-move manager: detects left-click / left-drag and moves
 * the player to the clicked block via server-side velocity instructions.
 *
 * <p>Delegates low-level concerns to helper classes:</p>
 * <ul>
 *   <li>{@link AttackHandler} — server-forced attack chains (single
 *       {@code InteractionContext} allocation)</li>
 *   <li>{@link MovementHelper} — velocity packets, animations, movement states</li>
 *   <li>{@link TargetResolver} — entity resolution, wall-click probing, walkability</li>
 * </ul>
 *
 * <h3>Velocity model (no-decay)</h3>
 * <p>A {@code ChangeVelocity} packet with {@code groundResistance = 1.0} (100 %
 * retained per tick) makes the XZ velocity persist until we send a stop instruction
 * (zero velocity). This avoids needing to re-send velocity every tick.</p>
 *
 * <h3>Animation model (Status slot override)</h3>
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

    /** Stop moving when within this distance (XZ) of the target. */
    private static final double ARRIVAL_THRESHOLD = 1.0;

    /** Squared arrival threshold for cheaper distance checks. */
    static final double ARRIVAL_THRESHOLD_SQ = ARRIVAL_THRESHOLD * ARRIVAL_THRESHOLD;

    /**
     * Minimum direction change (in radians) before re-sending a velocity instruction.
     * ~5.7° — avoids spamming packets when dragging with minor mouse movement.
     */
    private static final double DIRECTION_CHANGE_THRESHOLD = 0.1;

    /**
     * Maximum distance (in blocks, XZ only) from the player to an entity for the
     * "attack range" check.
     */
    private static final double ATTACK_RANGE = 3.0;

    /** Squared attack range for cheaper distance checks. */
    private static final double ATTACK_RANGE_SQ = ATTACK_RANGE * ATTACK_RANGE;

    /**
     * Minimum interval between server-forced attack chains (nanoseconds).
     * Prevents wasteful {@code InteractionContext} / {@code InteractionChain}
     * allocation on every mouse event.
     */
    private static final long ATTACK_THROTTLE_NS = 400_000_000L; // 400 ms

    /**
     * ±1 radian (~57°) yaw range. Tight enough that the body follows the head
     * as the client rotates head toward the mouse cursor via {@code planeNormal = (0,1,0)}.
     */
    private static final CameraAxis FOLLOW_YAW_RANGE = new CameraAxis(
            new Rangef(-1.0F, 1.0F), new CameraNode[]{CameraNode.Head}
    );

    // --- Default isometric camera preset (NW, distance 10, elevation 4) ---
    private static final float DEFAULT_ISO_DISTANCE = 10.0F;
    private static final float DEFAULT_ISO_ELEVATION = 4.0F;
    private static final float ISO_BASE_PITCH = (float) (-Math.PI / 4);
    private static final float DEFAULT_ISO_YAW = (float) (7 * Math.PI / 4); // NW
    private static final String DISABLE_PRIMARY_EFFECT_KEY = "DisablePrimary";

    // ============================================
    // Instance Fields
    // ============================================

    private final Map<UUID, PlayerState> players = new ConcurrentHashMap<>();
    private final EventRegistry eventRegistry;

    /** RPG service for per-player speed and attack throttle scaling. */
    private RpgService rpgService;

    /**
     * Lazily-resolved sound event index for {@code SFX_Player_Hurt}.
     * <p>0 = not yet resolved, -1 = resolution failed, &gt;0 = valid index.</p>
     */
    private int hurtSoundIndex;

    /** Stored reference for deregistration on shutdown. */
    private final PacketFilter hurtAnimationFilter;

    /** Stored reference for deregistration on shutdown. */
    private final PacketFilter pageWatcher;

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

        // Drop outbound Hurt animations for CTM players so they don't cancel
        // the Run animation on AnimationSlot.Status. The hurt sound (normally
        // carried by keyframe SFX in the animation) is sent manually instead.
        this.hurtAnimationFilter = PacketAdapters.registerOutbound(
                (PlayerPacketFilter) this::filterHurtAnimation);

        // Track server-sent pages (Bench, Custom, etc.) so we can suppress CTM
        // input while a page is open.  Note: client-toggled pages (Inventory, Map)
        // bypass the server entirely and are NOT visible here.
        this.pageWatcher = PacketAdapters.registerOutbound(
                (PlayerPacketWatcher) (playerRef, packet) -> {
                    if (packet instanceof SetPage setPage) {
                        PlayerState state = players.get(playerRef.getUuid());
                        if (state != null) {
                            state.activePage = setPage.page;
                        }
                    }
                });
    }

    // ============================================
    // Packet Filtering
    // ============================================

    /**
     * Outbound packet filter that drops Hurt animations targeting a CTM player's
     * own entity and replaces them with a manual {@link PlaySoundEvent2D} so the
     * player still gets audio feedback.
     *
     * @return {@code true} to drop the packet, {@code false} to let it through
     */
    private boolean filterHurtAnimation(@Nonnull PlayerRef playerRef, @Nonnull Object packet) {
        if (!(packet instanceof PlayAnimation pa)) return false;
        if (pa.slot != AnimationSlot.Status) return false;
        if (pa.animationId == null || !pa.animationId.startsWith("Hurt")) return false;
        if (!players.containsKey(playerRef.getUuid())) return false;

        // Only drop the animation if it targets the player's OWN entity.
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) return false;
        NetworkId networkId = ref.getStore().getComponent(ref, NetworkId.getComponentType());
        if (networkId == null || networkId.getId() != pa.entityId) return false;

        LOGGER.atFine().log("[CTM] Dropping Hurt animation '%s' for %s (entityId=%d)",
                pa.animationId, playerRef.getUsername(), pa.entityId);

        sendHurtSound(playerRef);
        return true;
    }

    /**
     * Sends the {@code SFX_Player_Hurt} sound to the given player. The sound
     * index is resolved lazily on first call because the asset map may not be
     * fully populated during plugin {@code setup()}.
     */
    private void sendHurtSound(@Nonnull PlayerRef playerRef) {
        if (hurtSoundIndex == 0) {
            int idx = SoundEvent.getAssetMap().getIndex("SFX_Player_Hurt");
            if (idx == Integer.MIN_VALUE) {
                LOGGER.atWarning().log("[CTM] SFX_Player_Hurt not found in SoundEvent asset map");
                hurtSoundIndex = -1;
                return;
            }
            LOGGER.atFine().log("[CTM] Resolved SFX_Player_Hurt → index %d", idx);
            hurtSoundIndex = idx;
        }
        if (hurtSoundIndex > 0) {
            playerRef.getPacketHandler().writeNoCache(
                    new PlaySoundEvent2D(hurtSoundIndex, SoundCategory.SFX, 1.0F, 1.0F)
            );
            LOGGER.atFine().log("[CTM] Sent hurt sound (index=%d) to %s",
                    hurtSoundIndex, playerRef.getUsername());
        }
    }

    // ============================================
    // Public API
    // ============================================

    /**
     * Sets the RPG service for per-player stat-based speed and attack throttle.
     *
     * @param rpgService the RPG service
     */
    public void setRpgService(@Nonnull RpgService rpgService) {
        this.rpgService = Objects.requireNonNull(rpgService, "rpgService");
    }

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
     * Enables click-to-move with the default isometric camera preset.
     *
     * <p>Equivalent to {@code /camera iso --angle NW --distance 10 --elevation 4 --clickmove}.
     * Sends the camera packet, applies the {@code DisablePrimary} entity effect, and
     * enables CTM with model angle range widening.
     *
     * @param uuid      the player UUID
     * @param store     the entity store
     * @param ref       the player entity reference
     * @param playerRef the player ref (for packet sending)
     */
    public void enableWithCamera(@Nonnull UUID uuid,
                                 @Nonnull Store<EntityStore> store,
                                 @Nonnull Ref<EntityStore> ref,
                                 @Nonnull PlayerRef playerRef) {
        enableWithCamera(uuid, store, ref, playerRef,
                DEFAULT_ISO_YAW, DEFAULT_ISO_DISTANCE, DEFAULT_ISO_ELEVATION);
    }

    /**
     * Enables click-to-move with a custom isometric camera configuration.
     *
     * <p>Sends the camera packet, applies the {@code DisablePrimary} entity effect, and
     * enables CTM with model angle range widening.
     *
     * @param uuid      the player UUID
     * @param store     the entity store
     * @param ref       the player entity reference
     * @param playerRef the player ref (for packet sending)
     * @param yaw       camera yaw in radians (compass direction)
     * @param distance  camera distance from the player
     * @param elevation camera Y elevation offset
     */
    public void enableWithCamera(@Nonnull UUID uuid,
                                 @Nonnull Store<EntityStore> store,
                                 @Nonnull Ref<EntityStore> ref,
                                 @Nonnull PlayerRef playerRef,
                                 float yaw,
                                 float distance,
                                 float elevation) {
        IsoCameraGeometry cameraGeometry = isoCameraGeometry(distance, elevation);

        // Build and send camera packet
        ServerCameraSettings settings = new ServerCameraSettings();
        settings.positionLerpSpeed = 0.2F;
        settings.rotationLerpSpeed = 0.2F;
        settings.distance = cameraGeometry.effectiveDistance();
        settings.displayCursor = true;
        settings.sendMouseMotion = true;
        settings.isFirstPerson = false;
        settings.eyeOffset = true;
        settings.positionDistanceOffsetType = PositionDistanceOffsetType.DistanceOffset;
        settings.rotationType = RotationType.Custom;
        settings.mouseInputType = MouseInputType.LookAtPlane;
        settings.planeNormal = new Vector3f(0.0F, 1.0F, 0.0F);
        settings.movementForceRotationType = MovementForceRotationType.Custom;
        settings.rotation = new Direction(yaw, cameraGeometry.pitch(), 0.0F);

        playerRef.getPacketHandler().writeNoCache(
                new SetServerCamera(ClientCameraView.Custom, true, settings)
        );

        // Apply DisablePrimary effect to prevent accidental hits in overhead view
        applyDisablePrimary(store, ref);

        // Enable CTM (widens model angle range for iso body follow)
        enable(uuid, store, ref);
    }

    /**
     * Disables click-to-move for a player. Stops active movement, restores the original model.
     */
    public void disable(@Nonnull UUID uuid,
                        @Nonnull Store<EntityStore> store,
                        @Nonnull Ref<EntityStore> ref) {
        PlayerState state = players.remove(uuid);
        if (state == null) return;

        if (state.targetPosition != null || state.currentAnimation != null) {
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef != null) {
                MovementHelper.stopMovement(state, store, ref, playerRef);
            }
        }

        restoreModelAngleRange(state, uuid, store, ref);
    }

    /**
     * Disables click-to-move and restores the built-in camera/input mode.
     *
     * @param uuid the player UUID
     * @param store the entity store
     * @param ref the player entity reference
     * @param playerRef the player ref used to send the camera reset packet
     */
    public void disableWithCameraReset(@Nonnull UUID uuid,
                                       @Nonnull Store<EntityStore> store,
                                       @Nonnull Ref<EntityStore> ref,
                                       @Nonnull PlayerRef playerRef) {
        disable(uuid, store, ref);
        removeDisablePrimary(store, ref);
        CameraManager cameraManager = store.getComponent(ref, CameraManager.getComponentType());
        if (cameraManager != null) {
            cameraManager.resetCamera(playerRef);
        } else {
            playerRef.getPacketHandler().writeNoCache(new SetServerCamera(ClientCameraView.Custom, false, null));
        }
    }

    /**
     * Stops CTM and switches to a transition camera that does not depend on the local player entity.
     *
     * @param uuid the player UUID
     * @param store the entity store
     * @param ref the player entity reference
     * @param playerRef the player ref used to send the camera packet
     */
    public void prepareForWorldTransition(@Nonnull UUID uuid,
                                          @Nonnull Store<EntityStore> store,
                                          @Nonnull Ref<EntityStore> ref,
                                          @Nonnull PlayerRef playerRef) {
        disable(uuid, store, ref);
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        Vector3d playerPosition = transform != null ? transform.getPosition() : new Vector3d();
        IsoCameraGeometry cameraGeometry = isoCameraGeometry(DEFAULT_ISO_DISTANCE, DEFAULT_ISO_ELEVATION);

        ServerCameraSettings settings = new ServerCameraSettings();
        settings.positionLerpSpeed = 0.0F;
        settings.rotationLerpSpeed = 0.0F;
        settings.distance = 0.0F;
        settings.displayCursor = false;
        settings.sendMouseMotion = false;
        settings.skipCharacterPhysics = true;
        settings.isFirstPerson = false;
        settings.eyeOffset = false;
        settings.mouseInputTargetType = MouseInputTargetType.None;
        settings.attachedToType = AttachedToType.None;
        settings.positionType = PositionType.Custom;
        settings.position = detachedIsoCameraPosition(playerPosition, DEFAULT_ISO_YAW, cameraGeometry);
        settings.positionDistanceOffsetType = PositionDistanceOffsetType.None;
        settings.rotationType = RotationType.Custom;
        settings.rotation = new Direction(DEFAULT_ISO_YAW, cameraGeometry.pitch(), 0.0F);
        settings.canMoveType = CanMoveType.Always;
        settings.applyMovementType = ApplyMovementType.Position;
        settings.movementMultiplier = new Vector3f(0.0F, 0.0F, 0.0F);
        settings.applyLookType = ApplyLookType.Rotation;
        settings.lookMultiplier = new Vector2f(0.0F, 0.0F);
        settings.mouseInputType = MouseInputType.LookAtPlane;
        settings.planeNormal = new Vector3f(0.0F, 1.0F, 0.0F);
        settings.movementForceRotationType = MovementForceRotationType.Custom;

        playerRef.getPacketHandler().writeNoCache(
                new SetServerCamera(ClientCameraView.Custom, true, settings)
        );
    }

    @Nonnull
    private static IsoCameraGeometry isoCameraGeometry(float distance, float elevation) {
        double horizontalDistance = distance * Math.cos(-ISO_BASE_PITCH);
        double verticalDistance = distance * Math.sin(-ISO_BASE_PITCH) + elevation;
        float pitch = (float) -Math.atan2(verticalDistance, horizontalDistance);
        float effectiveDistance = (float) Math.sqrt(
                horizontalDistance * horizontalDistance + verticalDistance * verticalDistance
        );
        return new IsoCameraGeometry(pitch, (float) horizontalDistance, (float) verticalDistance, effectiveDistance);
    }

    @Nonnull
    private static Position detachedIsoCameraPosition(@Nonnull Vector3d playerPosition,
                                                      float yaw,
                                                      @Nonnull IsoCameraGeometry cameraGeometry) {
        double offsetX = Math.sin(yaw) * cameraGeometry.horizontalDistance();
        double offsetZ = Math.cos(yaw) * cameraGeometry.horizontalDistance();
        return new Position(
                playerPosition.x + offsetX,
                playerPosition.y + cameraGeometry.verticalDistance(),
                playerPosition.z + offsetZ
        );
    }

    private record IsoCameraGeometry(float pitch,
                                     float horizontalDistance,
                                     float verticalDistance,
                                     float effectiveDistance) {
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
            if (state.targetPosition != null || state.currentAnimation != null) {
                MovementHelper.stopMovement(state, store, ref, playerRef);
            }
            restoreModelAngleRange(state, uuid, store, ref);
        });
    }

    /**
     * Stops active click-to-move movement without disabling the camera or CTM mode.
     *
     * @param uuid the player UUID
     * @param store the entity store
     * @param ref the player entity reference
     * @param playerRef the player ref used to send the zero-velocity packet
     */
    public void stopActiveMovement(@Nonnull UUID uuid,
                                   @Nonnull Store<EntityStore> store,
                                   @Nonnull Ref<EntityStore> ref,
                                   @Nonnull PlayerRef playerRef) {
        PlayerState state = players.get(uuid);
        if (state == null) {
            return;
        }

        MovementHelper.stopMovement(state, store, ref, playerRef);
        state.leftButtonHeld = false;
        state.targetMerchantEntity = null;
        state.targetInteractBlock = null;
    }

    /**
     * Clears transient click-to-move state for a dead player without disabling
     * the mode entirely, so it can resume on respawn.
     *
     * @param uuid the player UUID
     * @param store the entity store
     * @param ref the player entity reference
     */
    public void onPlayerDeath(@Nonnull UUID uuid,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull Ref<EntityStore> ref) {
        PlayerState state = players.get(uuid);
        if (state == null) {
            return;
        }

        state.dead = true;

        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef != null) {
            MovementHelper.stopMovement(state, store, ref, playerRef);
        } else {
            state.targetPosition = null;
            state.targetEntity = null;
            state.lastSentAngle = Double.NaN;
            state.cursorOffsetX = 0.0;
            state.cursorOffsetZ = 0.0;
            MovementHelper.stopAnimation(state, store, ref);
        }

        state.leftButtonHeld = false;
        state.targetMerchantEntity = null;
        state.targetInteractBlock = null;
        state.activePage = Page.None;

        LOGGER.atFine().log("[CTM] Cleared movement/input state for dead player %s", uuid);
    }

    /**
     * Re-applies respawn-sensitive click-to-move state after the death screen
     * completes.
     *
     * @param uuid the player UUID
     * @param store the entity store
     * @param ref the player entity reference
     */
    public void onPlayerRespawn(@Nonnull UUID uuid,
                                @Nonnull Store<EntityStore> store,
                                @Nonnull Ref<EntityStore> ref) {
        PlayerState state = players.get(uuid);
        if (state == null) {
            return;
        }

        state.dead = false;
        state.activePage = Page.None;

        removeDisablePrimary(store, ref);
        applyDisablePrimary(store, ref);

        ModelComponent modelComponent = store.getComponent(ref, ModelComponent.getComponentType());
        Model currentModel = modelComponent != null ? modelComponent.getModel() : null;
        if (!hasFollowYawRange(currentModel)) {
            widenModelAngleRange(state, uuid, store, ref);
        }

        LOGGER.atFine().log("[CTM] Reapplied respawn state for %s", uuid);
    }

    public boolean isEnabled(@Nonnull UUID uuid) {
        return players.containsKey(uuid);
    }

    /**
     * Applies the DisablePrimary entity effect to prevent primary interaction (hit/swing/shot).
     */
    private static void applyDisablePrimary(@Nonnull Store<EntityStore> store,
                                             @Nonnull Ref<EntityStore> ref) {
        EntityEffect effect = EntityEffect.getAssetMap().getAsset(DISABLE_PRIMARY_EFFECT_KEY);
        if (effect == null) {
            LOGGER.atWarning().log("DisablePrimary EntityEffect asset not found");
            return;
        }
        EffectControllerComponent ecc = store.getComponent(ref, EffectControllerComponent.getComponentType());
        if (ecc != null) {
            ecc.addEffect(ref, effect, store);
        }
    }

    /**
     * Removes the DisablePrimary entity effect, restoring primary interaction.
     */
    private static void removeDisablePrimary(@Nonnull Store<EntityStore> store,
                                             @Nonnull Ref<EntityStore> ref) {
        int effectIndex = EntityEffect.getAssetMap().getIndex(DISABLE_PRIMARY_EFFECT_KEY);
        if (effectIndex < 0) {
            return;
        }

        EffectControllerComponent ecc = store.getComponent(ref, EffectControllerComponent.getComponentType());
        if (ecc != null) {
            ecc.removeEffect(ref, effectIndex, store);
        }
    }

    /**
     * Returns {@code true} if the player needs tick processing — either has an
     * active movement target or is holding the button.
     */
    boolean needsProcessing(@Nonnull UUID uuid) {
        PlayerState state = players.get(uuid);
        return state != null && !state.dead && (state.leftButtonHeld || state.targetPosition != null);
    }

    /**
     * Called every tick by {@link ClickToMoveTickSystem}. Checks arrival and
     * updates velocity direction when the target changes (drag-to-move).
     */
    void tickMovement(@Nonnull Ref<EntityStore> ref,
                      @Nonnull Store<EntityStore> store,
                      @Nonnull PlayerRef playerRef,
                      @Nonnull UUID uuid) {
        PlayerState state = players.get(uuid);
        if (state == null || state.dead) return;

        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) return;

        Vector3d pos = transform.getPosition();

        // While button is held, recompute target from stored cursor offset.
        if (state.leftButtonHeld) {
            double newTargetX = pos.x + state.cursorOffsetX;
            double newTargetZ = pos.z + state.cursorOffsetZ;

            int blockX = MathUtil.floor(newTargetX);
            int blockZ = MathUtil.floor(newTargetZ);
            int footY = MathUtil.floor(pos.y) - 1;
            if (TargetResolver.isWalkable(store, blockX, blockZ, footY)) {
                state.targetPosition = new Vector3d(newTargetX, pos.y, newTargetZ);
            }
        }

        Vector3d target = state.targetPosition;
        if (target == null) return;

        // ── Entity-in-range check ────────────────────────────────────
        Ref<EntityStore> targetEntity = state.targetEntity;
        if (targetEntity != null && targetEntity.isValid()) {
            TransformComponent eTransform = store.getComponent(targetEntity, TransformComponent.getComponentType());
            if (eTransform != null) {
                Vector3d ePos = eTransform.getPosition();
                double edx = ePos.x - pos.x;
                double edz = ePos.z - pos.z;
                double entityDistSq = edx * edx + edz * edz;

                if (entityDistSq <= ATTACK_RANGE_SQ) {
                    AttackHandler.tryAttack(state, store, ref, true, getPlayerAttackThrottle(uuid));
                    state.targetEntity = null;
                    MovementHelper.stopMovement(state, store, ref, playerRef);
                    return;
                }

                // Entity still out of range — walk toward its current position
                state.targetPosition = new Vector3d(ePos.x, pos.y, ePos.z);
                target = state.targetPosition;
            } else {
                state.targetEntity = null;
            }
        }

        // ── Merchant-entity range check ──────────────────────────────
        Ref<EntityStore> merchantEntity = state.targetMerchantEntity;
        if (merchantEntity != null && merchantEntity.isValid()) {
            TransformComponent mTransform = store.getComponent(merchantEntity, TransformComponent.getComponentType());
            if (mTransform != null) {
                Vector3d mPos = mTransform.getPosition();
                double mdx = mPos.x - pos.x;
                double mdz = mPos.z - pos.z;
                double merchantDistSq = mdx * mdx + mdz * mdz;

                if (merchantDistSq <= ATTACK_RANGE_SQ) {
                    tryOpenMerchant(state, store, ref, merchantEntity);
                    state.targetMerchantEntity = null;
                    MovementHelper.stopMovement(state, store, ref, playerRef);
                    return;
                }

                // Still walking toward merchant
                state.targetPosition = new Vector3d(mPos.x, pos.y, mPos.z);
                target = state.targetPosition;
            } else {
                state.targetMerchantEntity = null;
            }
        }

        // ── Block-interaction range check ────────────────────────────
        Vector3i interactBlock = state.targetInteractBlock;
        if (interactBlock != null) {
            double bx = interactBlock.x + 0.5;
            double bz = interactBlock.z + 0.5;
            double bdx = bx - pos.x;
            double bdz = bz - pos.z;
            double blockDistSq = bdx * bdx + bdz * bdz;

            if (blockDistSq <= ATTACK_RANGE_SQ) {
                AttackHandler.tryBlockInteraction(
                        state, store, ref, interactBlock, getPlayerAttackThrottle(uuid));
                state.targetInteractBlock = null;
                MovementHelper.stopMovement(state, store, ref, playerRef);
                return;
            }

            // Still walking toward block — keep target on block center
            state.targetPosition = new Vector3d(bx, pos.y, bz);
            target = state.targetPosition;
        }

        double dx = target.x - pos.x;
        double dz = target.z - pos.z;
        double distSq = dx * dx + dz * dz;

        if (distSq <= ARRIVAL_THRESHOLD_SQ) {
            if (!state.leftButtonHeld) {
                MovementHelper.stopMovement(state, store, ref, playerRef);
                return;
            }
            return;
        }

        // Check if direction changed significantly → re-send velocity
        double angle = Math.atan2(dz, dx);
        if (!Double.isNaN(state.lastSentAngle)) {
            double angleDiff = Math.abs(angle - state.lastSentAngle);
            if (angleDiff > Math.PI) angleDiff = 2 * Math.PI - angleDiff;
            if (angleDiff > DIRECTION_CHANGE_THRESHOLD) {
                MovementHelper.sendVelocity(state, playerRef, dx, dz, distSq, getPlayerMoveSpeed(uuid));
            }
        }

        String anim = MovementHelper.chooseAnimation(transform, dx, dz);
        MovementHelper.updateAnimation(state, store, ref, anim);
        MovementHelper.setMovingStates(store, ref);
    }

    /**
     * Opens the merchant UI for the player if not throttled.
     */
    private void tryOpenMerchant(@Nonnull PlayerState state,
                                 @Nonnull Store<EntityStore> store,
                                 @Nonnull Ref<EntityStore> playerRef,
                                 @Nonnull Ref<EntityStore> merchantRef) {
        long now = System.nanoTime();
        if (now - state.lastAttackNanos < ATTACK_THROTTLE_NS) return;
        state.lastAttackNanos = now;

        MerchantComponent mc = store.getComponent(merchantRef, MerchantComponent.getComponentType());
        if (mc == null) return;

        PlayerRef pRef = store.getComponent(playerRef, PlayerRef.getComponentType());
        if (pRef == null) return;

        Player player = store.getComponent(playerRef, Player.getComponentType());
        if (player == null) return;

        MerchantService merchantService = ZSquadPlugin.get().getMerchantService();

        int floorLevel = mc.getFloorLevel();
        java.util.List<CatalogEntry> catalog;
        if (mc.hasCatalog()) {
            catalog = mc.getCatalog();
        } else {
            long seed = merchantRef.hashCode();
            catalog = ZSquadPlugin.get().getCatalogGenerator().generate(floorLevel, seed);
            mc.setCatalog(catalog);
        }

        merchantService.openMerchant(player, pRef, playerRef, store, catalog);
    }

    /**
     * Computes per-player move speed from the Speed RPG stat.
     */
    private double getPlayerMoveSpeed(@Nonnull UUID uuid) {
        int speedLevel = rpgService.getStat(uuid, RpgStat.SPEED);
        return RpgStatEffects.computeMoveSpeed(speedLevel);
    }

    /**
     * Computes per-player attack throttle from the Agility RPG stat.
     */
    private long getPlayerAttackThrottle(@Nonnull UUID uuid) {
        int agilityLevel = rpgService.getStat(uuid, RpgStat.AGILITY);
        return RpgStatEffects.computeAttackThrottleNs(agilityLevel);
    }

    /**
     * Shuts down event listeners, deregisters packet adapters, and clears state.
     */
    public void shutdown() {
        this.eventRegistry.shutdownAndCleanup(false);
        PacketAdapters.deregisterOutbound(this.hurtAnimationFilter);
        PacketAdapters.deregisterOutbound(this.pageWatcher);
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
        if (state == null || state.dead) return;

        if (isPageOpen(state, store, ref, false)) return;
        if (!isLeftButtonHeld(event)) return;

        Ref<EntityStore> targetEntityRef = TargetResolver.resolveTargetEntity(
            event.getTargetEntityRef(), ref);
        Vector3i targetBlock = event.getTargetBlock();
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
        if (state == null || state.dead) return;

        if (isPageOpen(state, store, ref, true)) return;
        if (event.getMouseButton().mouseButtonType != MouseButtonType.Left) return;

        if (event.getMouseButton().state == MouseButtonState.Released) {
            state.leftButtonHeld = false;
            return;
        }

        if (event.getMouseButton().state != MouseButtonState.Pressed) return;

        state.leftButtonHeld = true;
        Ref<EntityStore> targetEntityRef = TargetResolver.resolveTargetEntity(
            event.getTargetEntityRef(), ref);
        Vector3i targetBlock = event.getTargetBlock();

        // Wall occlusion fallback
        if (targetEntityRef == null && targetBlock != null) {
            TransformComponent tc = store.getComponent(ref, TransformComponent.getComponentType());
            if (tc != null && targetBlock.y > MathUtil.floor(tc.getPosition().y) - 1) {
                targetEntityRef = TargetResolver.findNearbyEntityFallback(store, ref, targetBlock);
            }
        }

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
     * Returns {@code true} when a UI page is open for the given player.
     *
     * <p>When {@code recoverStale} is {@code true} and a built-in page was
     * tracked via {@code SetPage} but no custom page is open, the tracker is
     * reset and the method returns {@code false}. This handles built-in page
     * closes that happen entirely client-side (no server notification).</p>
     */
    private boolean isPageOpen(@Nonnull PlayerState state,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref,
                               boolean recoverStale) {
        Player player = store.getComponent(ref, Player.getComponentType());
        boolean customPageOpen = player != null
                && player.getPageManager().getCustomPage() != null;
        if (customPageOpen) return true;

        // Check merchant session — the shop uses Page.Bench which can be
        // incorrectly cleared by the stale recovery logic below.
        PlayerRef pRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (pRef != null) {
            MerchantService ms = ZSquadPlugin.get().getMerchantService();
            if (ms != null && ms.hasOpenSession(pRef.getUuid())) return true;
        }

        if (state.activePage != Page.None) {
            if (recoverStale) {
                // Built-in page close is invisible to the server (client-side only).
                // A mouse-click arriving with no custom page suggests the player
                // closed the built-in page — clear the stale tracker.
                LOGGER.atInfo().log("[CTM] Clearing stale activePage=%s (assumed closed)",
                        state.activePage);
                state.activePage = Page.None;
                return false;
            }
            return true;
        }
        return false;
    }

    // ============================================
    // Target Resolution
    // ============================================

    /**
     * Resolves a clicked/dragged block into a walkable target position,
     * updates the player state, and optionally spawns a particle.
     *
     * <p>Uses {@link AttackHandler#tryAttack} with a single {@code InteractionContext}
     * to handle both melee-range and ranged-weapon cases.</p>
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
        int playerFootY = MathUtil.floor(pos.y) - 1;

        // Skip any companion - not a valid attack target (co-op friendly).
        // Redirect the click to the block below the companion so the player walks there.
        if (targetEntityRef != null) {
            if (store.getComponent(targetEntityRef, CompanionComponent.getComponentType()) != null) {
                TransformComponent cTransform = store.getComponent(
                        targetEntityRef, TransformComponent.getComponentType());
                if (cTransform != null) {
                    Vector3d cPos = cTransform.getPosition();
                    targetBlock = new Vector3i(
                            MathUtil.floor(cPos.x),
                            MathUtil.floor(cPos.y) - 1,
                            MathUtil.floor(cPos.z));
                }
                targetEntityRef = null;
            }
        }

        if (targetEntityRef != null) {
            if (store.getComponent(targetEntityRef, Intangible.getComponentType()) != null) {
                TransformComponent targetTransform = store.getComponent(
                        targetEntityRef, TransformComponent.getComponentType());
                if (targetTransform != null) {
                    Vector3d targetPosition = targetTransform.getPosition();
                    targetBlock = new Vector3i(
                            MathUtil.floor(targetPosition.x),
                            MathUtil.floor(targetPosition.y) - 1,
                            MathUtil.floor(targetPosition.z));
                }
                targetEntityRef = null;
            }
        }

        // ── Entity targeting ─────────────────────────────────────────
        if (targetEntityRef != null) {
            TransformComponent eTransform = store.getComponent(
                    targetEntityRef, TransformComponent.getComponentType());
            if (eTransform != null) {
                Vector3d ePos = eTransform.getPosition();
                double edx = ePos.x - pos.x;
                double edz = ePos.z - pos.z;
                double entityDistSq = edx * edx + edz * edz;

                // Check if entity is a merchant NPC → open merchant UI instead of attacking
                MerchantComponent mc = store.getComponent(targetEntityRef, MerchantComponent.getComponentType());
                if (mc != null) {
                    LOGGER.atInfo().log("[CTM] Target is a merchant NPC");

                    if (entityDistSq <= ATTACK_RANGE_SQ) {
                        tryOpenMerchant(state, store, ref, targetEntityRef);
                        MovementHelper.stopMovement(state, store, ref, playerRef);
                        return;
                    }
                    // Walk toward merchant
                    state.targetMerchantEntity = targetEntityRef;
                    state.targetEntity = null;
                    MovementHelper.beginMovement(state, playerRef, transform, store, ref, pos,
                            new Vector3d(ePos.x, pos.y, ePos.z), getPlayerMoveSpeed(playerRef.getUuid()));
                    return;
                }

                AttackHandler.AttackResult result = AttackHandler.tryAttack(
                        state, store, ref,
                        entityDistSq <= ATTACK_RANGE_SQ,
                        getPlayerAttackThrottle(playerRef.getUuid()));

                if (result == AttackHandler.AttackResult.ATTACKED) {
                    state.targetEntity = null;
                    MovementHelper.stopMovement(state, store, ref, playerRef);
                    return;
                }

                // OUT_OF_RANGE — melee weapon, walk toward entity
                state.targetEntity = targetEntityRef;
                MovementHelper.beginMovement(state, playerRef, transform, store, ref, pos,
                        new Vector3d(ePos.x, pos.y, ePos.z), getPlayerMoveSpeed(playerRef.getUuid()));
                return;
            }
        }

        // No entity target — clear any previous entity/interact tracking
        state.targetEntity = null;
        state.targetMerchantEntity = null;
        state.targetInteractBlock = null;

        if (targetBlock == null) return;

        int tx = targetBlock.x;
        int tz = targetBlock.z;
        int ty = targetBlock.y;

        // ── Interactable block check (bench, chest, etc.) ────────────
        if (TargetResolver.isInteractableBlock(store, tx, ty, tz)) {
            double bx = tx + 0.5;
            double bz = tz + 0.5;
            double dx = bx - pos.x;
            double dz = bz - pos.z;
            double distSq = dx * dx + dz * dz;

            if (distSq <= ATTACK_RANGE_SQ) {
                // In range → interact immediately
                AttackHandler.tryBlockInteraction(
                        state, store, ref, targetBlock, getPlayerAttackThrottle(playerRef.getUuid()));
                MovementHelper.stopMovement(state, store, ref, playerRef);
                return;
            }

            // Out of range → walk to block, interact on arrival
            state.targetInteractBlock = targetBlock;
            MovementHelper.beginMovement(state, playerRef, transform, store, ref, pos,
                    new Vector3d(bx, pos.y, bz), getPlayerMoveSpeed(playerRef.getUuid()));
            return;
        }

        // Wall check: target block higher than ground level → clicked a wall
        if (ty > playerFootY) {
            Vector3i resolved = TargetResolver.resolveWallClick(store, ref, tx, tz, playerFootY);
            if (resolved == null) return;
            tx = resolved.x;
            tz = resolved.z;
        }

        double targetX = tx + 0.5;
        double targetZ = tz + 0.5;

        MovementHelper.beginMovement(state, playerRef, transform, store, ref, pos,
                new Vector3d(targetX, pos.y, targetZ), getPlayerMoveSpeed(playerRef.getUuid()));

        if (spawnParticle) {
            ParticleUtil.spawnParticleEffect("Block_Break_Ore",
                    new Vector3d(targetX, playerFootY + 1.0, targetZ),
                    List.of(ref),
                    store);
        }
    }

    // ============================================
    // Model AngleRange
    // ============================================

    private static boolean hasFollowYawRange(@Nullable Model model) {
        if (model == null) {
            return false;
        }

        CameraSettings camera = model.getCamera();
        if (camera == null) {
            return false;
        }

        CameraAxis yaw = camera.getYaw();
        if (yaw == null) {
            return false;
        }

        return FOLLOW_YAW_RANGE.getAngleRange().equals(yaw.getAngleRange())
                && Arrays.equals(FOLLOW_YAW_RANGE.getTargetNodes(), yaw.getTargetNodes());
    }

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
