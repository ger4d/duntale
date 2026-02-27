package com.duntale.zsquad.camera;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.CameraNode;
import com.hypixel.hytale.protocol.MouseButtonState;
import com.hypixel.hytale.protocol.MouseButtonType;
import com.hypixel.hytale.protocol.Rangef;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.camera.CameraAxis;
import com.hypixel.hytale.server.core.asset.type.model.config.camera.CameraSettings;
import com.hypixel.hytale.server.core.event.events.player.PlayerMouseButtonEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerMouseMotionEvent;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Minimal click-to-move manager: detects left-click on the ground and
 * rotates the player's body toward the clicked location.
 *
 * <p>With {@code planeNormal = (0,1,0)} the client automatically rotates the player
 * head toward the mouse position on the ground plane. The ±180° {@code Yaw.AngleRange}
 * prevents {@code UpdateWithoutPosition} from clamping body yaw.</p>
 */
public class ClickToMoveManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /**
     * ±1 radian (~57°) yaw range. Tight enough that the body follows the head
     * as the client rotates head toward the mouse cursor via {@code planeNormal = (0,1,0)}.
     */
    private static final CameraAxis FOLLOW_YAW_RANGE = new CameraAxis(
            new Rangef(-1.0F, 1.0F), new CameraNode[]{CameraNode.Head}
    );

    // ── Per-player State ─────────────────────────────────────────────────

    static final class PlayerState {
        /** Original player model saved before widening yaw range. */
        @Nullable Model savedOriginalModel;
    }

    // ── Instance Fields ──────────────────────────────────────────────────

    private final Map<UUID, PlayerState> players = new ConcurrentHashMap<>();
    private final EventRegistry eventRegistry;

    // ── Constructor ──────────────────────────────────────────────────────

    public ClickToMoveManager() {
        this.eventRegistry = new EventRegistry(
                new CopyOnWriteArrayList<>(), () -> true, "ClickToMoveManager",
                HytaleServer.get().getEventBus()
        );
        this.eventRegistry.enable();
        this.eventRegistry.register(PlayerMouseButtonEvent.class, this::onMouseButton);
        this.eventRegistry.register(PlayerMouseMotionEvent.class, this::onMouseMotion);
    }

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Enables click-to-move for a player. Widens the player model's
     * {@code Yaw.AngleRange} to ±180°.
     */
    public void enable(@Nonnull UUID uuid,
                       @Nonnull Store<EntityStore> store,
                       @Nonnull Ref<EntityStore> ref) {
        PlayerState state = new PlayerState();
        players.put(uuid, state);
        widenModelAngleRange(state, uuid, store, ref);
    }

    /**
     * Disables click-to-move for a player. Restores the original model.
     */
    public void disable(@Nonnull UUID uuid,
                        @Nonnull Store<EntityStore> store,
                        @Nonnull Ref<EntityStore> ref) {
        PlayerState state = players.remove(uuid);
        if (state == null) return;
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
            restoreModelAngleRange(state, uuid, store, ref);
        });
    }

    public boolean isEnabled(@Nonnull UUID uuid) {
        return players.containsKey(uuid);
    }

    /** Stubs for {@link ClickToMoveTickSystem} — no tick logic in this minimal version. */
    boolean needsProcessing(@Nonnull UUID uuid) {
        return false;
    }

    void tickMovement(@Nonnull Ref<EntityStore> ref,
                      @Nonnull Store<EntityStore> store,
                      @Nonnull PlayerRef playerRef,
                      @Nonnull UUID uuid) {
        // No tick logic — rotation is applied immediately on click.
    }

    public void shutdown() {
        this.eventRegistry.shutdownAndCleanup(false);
        this.players.clear();
    }

    // ── Event Handlers ───────────────────────────────────────────────────

    private void onMouseMotion(@Nonnull PlayerMouseMotionEvent event) {
        Ref<EntityStore> ref = event.getPlayerRef();
        if (!ref.isValid()) return;

        Store<EntityStore> store = ref.getStore();
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null) return;

        UUID uuid = playerRef.getUuid();
        if (!players.containsKey(uuid)) return;

        LOGGER.at(Level.INFO).log("[CTM] MouseMotion received for %s screen=(%s) motion=%s",
                uuid,
                event.getScreenPoint() != null ? event.getScreenPoint().x + "," + event.getScreenPoint().y : "null",
                event.getMouseMotion() != null ? event.getMouseMotion().relativeMotion : "null");
    }

    private void onMouseButton(@Nonnull PlayerMouseButtonEvent event) {
        Ref<EntityStore> ref = event.getPlayerRef();
        if (!ref.isValid()) return;

        Store<EntityStore> store = ref.getStore();
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null) return;

        UUID uuid = playerRef.getUuid();
        if (!players.containsKey(uuid)) return;

        if (event.getMouseButton().mouseButtonType != MouseButtonType.Left) return;
        if (event.getMouseButton().state != MouseButtonState.Pressed) return;

        Vector3i targetBlock = event.getTargetBlock();
        if (targetBlock == null) return;

        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) return;

        Vector3d pos = transform.getPosition();
        double dx = targetBlock.getX() + 0.5 - pos.x;
        double dz = targetBlock.getZ() + 0.5 - pos.z;
        float clickYaw = (float) Math.atan2(-dx, -dz);

        // Particle feedback at the click location
        ParticleUtil.spawnParticleEffect("Block_Break_Ore",
                new Vector3d(targetBlock.getX() + 0.5, targetBlock.getY() + 1.0, targetBlock.getZ() + 0.5),
                store);

        LOGGER.at(Level.INFO).log("[CTM] Click rotate: yaw=%.1f° target=(%d,%d,%d) for %s",
                Math.toDegrees(clickYaw), targetBlock.getX(), targetBlock.getY(), targetBlock.getZ(), uuid);
    }

    // ── Model AngleRange ─────────────────────────────────────────────────

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

        LOGGER.at(Level.INFO).log("[CTM] Widened Yaw.AngleRange to ±180° for %s", uuid);
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

        LOGGER.at(Level.INFO).log("[CTM] Restored original model for %s", uuid);
    }
}
