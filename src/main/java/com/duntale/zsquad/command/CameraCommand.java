package com.duntale.zsquad.command;

import com.duntale.zsquad.ZSquadPlugin;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.ApplyLookType;
import com.hypixel.hytale.protocol.ClientCameraView;
import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.MouseInputType;
import com.hypixel.hytale.protocol.MovementForceRotationType;
import com.hypixel.hytale.protocol.MovementSettings;
import com.hypixel.hytale.protocol.PositionDistanceOffsetType;
import com.hypixel.hytale.protocol.RotationType;
import com.hypixel.hytale.protocol.ServerCameraSettings;
import com.hypixel.hytale.protocol.Vector3f;
import com.hypixel.hytale.protocol.Vector2f;
import com.hypixel.hytale.protocol.AttachedToType;
import com.hypixel.hytale.protocol.packets.camera.SetServerCamera;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.FlagArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Map;

public class CameraCommand extends CommandBase {

    public CameraCommand() {
        super("camera", "Toggle camera view");
        this.addSubCommand(new TopDownSubCommand());
        this.addSubCommand(new IsoSubCommand());
        this.addSubCommand(new FirstPersonSubCommand());
    }

    @Override
    protected void executeSync(CommandContext context) {
         context.sendMessage(Message.raw("Usage: /camera <topdown|iso|fps> [--camrel] [--clickmove] [--xray]"));
    }

    // ── Shared camera configuration ──────────────────────────────────────

    /**
     * Creates the common overhead camera settings shared by top-down and isometric views.
     *
     * @param distance    camera distance from the player
     * @param camRelative if true, movement follows camera rotation (W = screen-up);
     *                    if false, movement follows player head rotation (W = where character faces)
     * @param clickMove   if true, disables WASD movement (click-to-move only)
     * @return configured settings (caller must set rotation)
     */
    @Nonnull
    private static ServerCameraSettings createBaseOverheadSettings(float distance, boolean camRelative, boolean clickMove) {
        ServerCameraSettings settings = new ServerCameraSettings();
        settings.positionLerpSpeed = 0.2F;
        settings.rotationLerpSpeed = clickMove ? 1.0F : 0.2F;
        settings.distance = distance;
        settings.displayCursor = true;
        settings.sendMouseMotion = true;
        settings.isFirstPerson = false;
        settings.eyeOffset = true;
        settings.positionDistanceOffsetType = PositionDistanceOffsetType.DistanceOffset;
        settings.rotationType = RotationType.Custom;
        settings.mouseInputType = MouseInputType.LookAtPlane;
        settings.planeNormal = new Vector3f(0.0F, 1.0F, 0.0F);
        // settings.applyLookType = ApplyLookType.Rotation;
        // settings.lookMultiplier = new Vector2f(0.0F, 0.0F);
        // settings.attachedToType = AttachedToType.None;
        // settings.position = new Position(150, 80, 20);
        // settings.rotation = new Direction()

        if (clickMove) {
            // Zero normal creates a degenerate plane: dot(rayDirection, (0,0,0)) = 0
            // for any ray → Ray.Intersects(Plane) returns null → LookAt() never fires.
            // This freezes LookOrientation on the client, preventing the body from
            // tracking the mouse cursor. Click events are unaffected because they use
            // MouseRaycast (world collision), not the plane intersection.
            settings.planeNormal = new Vector3f(0.0F, 0.0F, 0.0F);

            // Disable WASD movement entirely — only click-to-move controls the player
            // settings.movementMultiplier = new Vector3f(0.0F, 0.0F, 0.0F);
            settings.movementForceRotationType = MovementForceRotationType.Custom;
        } else if (camRelative) {
            settings.movementForceRotationType = MovementForceRotationType.CameraRotation;
        } else {
            settings.movementForceRotationType = MovementForceRotationType.AttachedToHead;
        }

        return settings;
    }

    /**
     * Sends a custom camera packet to the player.
     */
    private static void applyCamera(@Nonnull PlayerRef playerRef, @Nonnull ServerCameraSettings settings) {
        playerRef.getPacketHandler().writeNoCache(
                new SetServerCamera(ClientCameraView.Custom, true, settings)
        );
    }

    /**
     * Equalizes movement speed multipliers so all directions move at the same speed.
     * Only needed in camera-relative mode.
     */
    private static void equalizeMovementSpeeds(@Nonnull Store<EntityStore> store,
                                                @Nonnull Ref<EntityStore> ref,
                                                @Nonnull PlayerRef playerRef) {
        MovementManager movementManager = store.getComponent(ref, MovementManager.getComponentType());
        if (movementManager == null) return;

        MovementSettings settings = movementManager.getSettings();
        if (settings == null) return;

        settings.backwardRunSpeedMultiplier = settings.forwardRunSpeedMultiplier;
        settings.strafeRunSpeedMultiplier = settings.forwardRunSpeedMultiplier;
        settings.backwardWalkSpeedMultiplier = settings.forwardWalkSpeedMultiplier;
        settings.strafeWalkSpeedMultiplier = settings.forwardWalkSpeedMultiplier;
        settings.backwardCrouchSpeedMultiplier = settings.forwardCrouchSpeedMultiplier;
        settings.strafeCrouchSpeedMultiplier = settings.forwardCrouchSpeedMultiplier;

        movementManager.update(playerRef.getPacketHandler());
    }

    /**
     * Restores the player's default movement settings.
     */
    private static void restoreMovementDefaults(@Nonnull Store<EntityStore> store,
                                                 @Nonnull Ref<EntityStore> ref) {
        MovementManager movementManager = store.getComponent(ref, MovementManager.getComponentType());
        if (movementManager == null) return;

        movementManager.resetDefaultsAndUpdate(ref, store);
    }

    /**
     * Adjusts movement for the selected mode.
     */
    private static void adjustMovementForMode(boolean camRelative,
                                               @Nonnull Store<EntityStore> store,
                                               @Nonnull Ref<EntityStore> ref,
                                               @Nonnull PlayerRef playerRef) {
        if (camRelative) {
            equalizeMovementSpeeds(store, ref, playerRef);
        } else {
            restoreMovementDefaults(store, ref);
        }
    }

    /**
     * Configures optional features (click-to-move, xray) for a player entering overhead mode.
     */
    private static void enableOptionalFeatures(@Nonnull PlayerRef playerRef,
                                                @Nonnull World world,
                                                @Nonnull Store<EntityStore> store,
                                                @Nonnull Ref<EntityStore> ref,
                                                boolean clickMove,
                                                boolean xray,
                                                float cameraYaw,
                                                float cameraPitch) {
        java.util.UUID uuid = playerRef.getUuid();
        ZSquadPlugin plugin = ZSquadPlugin.get();

        if (clickMove) {
            plugin.getClickToMoveManager().enable(uuid, cameraPitch, store, ref);
        } else {
            plugin.getClickToMoveManager().disable(uuid, store, ref);
        }

        if (xray) {
            plugin.getBlockOcclusionManager().enable(uuid, cameraYaw);
        } else {
            plugin.getBlockOcclusionManager().disable(uuid, world);
        }
    }

    /**
     * Disables all optional features for a player (used on FPS reset).
     */
    private static void disableAllFeatures(@Nonnull PlayerRef playerRef, @Nonnull World world,
                                            @Nonnull Store<EntityStore> store,
                                            @Nonnull Ref<EntityStore> ref) {
        java.util.UUID uuid = playerRef.getUuid();
        ZSquadPlugin plugin = ZSquadPlugin.get();
        plugin.getClickToMoveManager().disable(uuid, store, ref);
        plugin.getBlockOcclusionManager().disable(uuid, world);
    }

    // ── Top-Down ─────────────────────────────────────────────────────────

    private static class TopDownSubCommand extends AbstractPlayerCommand {

        private static final float DEFAULT_DISTANCE = 25.0F;

        private final OptionalArg<Float> distanceArg =
                this.withOptionalArg("distance", "Camera zoom distance", ArgTypes.FLOAT);
        private final FlagArg camRelFlag =
                this.withFlagArg("camrel", "Use camera-relative movement (W = screen-up)");
        private final FlagArg clickMoveFlag =
                this.withFlagArg("clickmove", "Enable click-to-move (left click to walk)");
        private final FlagArg xrayFlag =
                this.withFlagArg("xray", "Remove blocks occluding the player");

        public TopDownSubCommand() {
            super("topdown", "Switch to top-down view");
        }

        @Override
        protected void execute(@Nonnull CommandContext context,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref,
                               @Nonnull PlayerRef playerRef,
                               @Nonnull World world) {
            float distance = distanceArg.provided(context) ? distanceArg.get(context) : DEFAULT_DISTANCE;
            boolean camRelative = camRelFlag.get(context);
            boolean clickMove = clickMoveFlag.get(context);
            boolean xray = xrayFlag.get(context);

            ServerCameraSettings settings = createBaseOverheadSettings(distance, camRelative, clickMove);
            settings.rotation = new Direction(0.0F, (float) (-Math.PI / 2), 0.0F);

            applyCamera(playerRef, settings);
            adjustMovementForMode(camRelative, store, ref, playerRef);
            enableOptionalFeatures(playerRef, world, store, ref, clickMove, xray, 0.0F, (float) (-Math.PI / 2));

            StringBuilder info = new StringBuilder("Switched to Top-Down Camera (");
            info.append(camRelative ? "camera-rel" : "head-rel");
            if (clickMove) info.append(", click-move");
            if (xray) info.append(", xray");
            info.append(", distance: ").append(distance).append(").");
            context.sendMessage(Message.raw(info.toString()));
        }
    }

    // ── Isometric ────────────────────────────────────────────────────────

    private static class IsoSubCommand extends AbstractPlayerCommand {

        private static final float DEFAULT_DISTANCE = 20.0F;
        private static final float ISO_PITCH = (float) (-Math.PI / 4);

        private static final Map<String, Float> ANGLE_MAP = Map.of(
                "n",  0.0F,
                "ne", (float) (Math.PI / 4),
                "e",  (float) (Math.PI / 2),
                "se", (float) (3 * Math.PI / 4),
                "s",  (float) Math.PI,
                "sw", (float) (5 * Math.PI / 4),
                "w",  (float) (3 * Math.PI / 2),
                "nw", (float) (7 * Math.PI / 4)
        );

        private final OptionalArg<String> angleArg =
                this.withOptionalArg("angle", "Compass direction (n/ne/e/se/s/sw/w/nw)", ArgTypes.STRING);
        private final OptionalArg<Float> distanceArg =
                this.withOptionalArg("distance", "Camera zoom distance", ArgTypes.FLOAT);
        private final FlagArg camRelFlag =
                this.withFlagArg("camrel", "Use camera-relative movement (W = screen-up)");
        private final FlagArg clickMoveFlag =
                this.withFlagArg("clickmove", "Enable click-to-move (left click to walk)");
        private final FlagArg xrayFlag =
                this.withFlagArg("xray", "Remove blocks occluding the player");
        private final OptionalArg<Float> elevationArg =
                this.withOptionalArg("elevation", "Camera Y offset (elevation)", ArgTypes.FLOAT);

        public IsoSubCommand() {
            super("iso", "Switch to isometric view");
            this.addAliases("isometric");
        }

        @Override
        protected void execute(@Nonnull CommandContext context,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref,
                               @Nonnull PlayerRef playerRef,
                               @Nonnull World world) {
            String angleKey = angleArg.provided(context) ? angleArg.get(context) : "se";
            float distance = distanceArg.provided(context) ? distanceArg.get(context) : DEFAULT_DISTANCE;
            boolean camRelative = camRelFlag.get(context);
            boolean clickMove = clickMoveFlag.get(context);
            boolean xray = xrayFlag.get(context);

            Float yaw = ANGLE_MAP.get(angleKey.toLowerCase());
            if (yaw == null) {
                context.sendMessage(Message.raw(
                        "Unknown angle '" + angleKey + "'. Use: n, ne, e, se, s, sw, w, nw"));
                return;
            }

            float elevation = elevationArg.provided(context) ? elevationArg.get(context) : 0.0F;

            ServerCameraSettings settings = createBaseOverheadSettings(distance, camRelative, clickMove);
            settings.rotation = new Direction(yaw, ISO_PITCH, 0.0F);
            if (elevation != 0.0F) {
                settings.positionOffset = new Position(0.0, elevation, 0.0);
            }

            applyCamera(playerRef, settings);
            adjustMovementForMode(camRelative, store, ref, playerRef);
            enableOptionalFeatures(playerRef, world, store, ref, clickMove, xray, yaw, ISO_PITCH);

            StringBuilder info = new StringBuilder("Switched to Isometric Camera (");
            info.append(angleKey.toUpperCase());
            info.append(", ").append(camRelative ? "camera-rel" : "head-rel");
            if (clickMove) info.append(", click-move");
            if (xray) info.append(", xray");
            if (elevation != 0.0F) info.append(", elevation: ").append(elevation);
            info.append(", distance: ").append(distance).append(").");
            context.sendMessage(Message.raw(info.toString()));
        }
    }

    // ── First Person Reset ───────────────────────────────────────────────

    private static class FirstPersonSubCommand extends AbstractPlayerCommand {
        public FirstPersonSubCommand() {
            super("fps", "Reset to first person view");
            this.addAliases("reset", "firstperson");
        }

        @Override
        protected void execute(@Nonnull CommandContext context,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref,
                               @Nonnull PlayerRef playerRef,
                               @Nonnull World world) {
            // Reset camera
            playerRef.getPacketHandler().writeNoCache(
                    new SetServerCamera(ClientCameraView.Custom, false, null));

            // Restore defaults and disable all features
            restoreMovementDefaults(store, ref);
            disableAllFeatures(playerRef, world, store, ref);

            context.sendMessage(Message.raw("Switched to First-Person Camera."));
        }
    }
}
