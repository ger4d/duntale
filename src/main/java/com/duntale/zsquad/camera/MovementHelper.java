package com.duntale.zsquad.camera;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import org.joml.Vector3d;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.protocol.VelocityConfig;
import com.hypixel.hytale.protocol.VelocityThresholdStyle;
import com.hypixel.hytale.protocol.packets.entities.ChangeVelocity;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * Static movement helpers for the click-to-move system.
 *
 * <p>Handles velocity packets, animation control, and movement-state
 * synchronisation. All methods are stateless — mutable state lives in
 * {@link PlayerState}.</p>
 */
final class MovementHelper {

    /** Movement speed in blocks/second. */
    static final double MOVE_SPEED = 8.0;

    /** Run animation key. Must exist in the player model's AnimationSets. */
    private static final String RUN_ANIMATION = "Run";

    /** Backward run animation key — played when body faces away from the target. */
    private static final String RUN_BACKWARD_ANIMATION = "RunBackward";

    /**
     * VelocityConfig with no decay. Velocity persists until explicitly stopped.
     * {@code groundResistance = 1.0} means no XZ velocity loss per tick.
     */
    private static final VelocityConfig NO_DECAY_CONFIG = new VelocityConfig(
            1.0F,   // groundResistance  — no XZ decay on ground
            -1.0F,  // groundResistanceMax — disable threshold blending
            1.0F,   // airResistance     — no XZ decay in air
            -1.0F,  // airResistanceMax  — disable threshold blending
            0.0F,   // threshold         — immediate transition
            VelocityThresholdStyle.Linear
    );

    private MovementHelper() {} // utility class

    /**
     * Sets the movement target and cursor offset, then sends the initial velocity
     * and animation if the target is beyond the arrival threshold.
     *
     * @param state     per-player state to update
     * @param playerRef player reference (for packet sending)
     * @param transform player transform (for animation selection)
     * @param store     entity store
     * @param ref       player entity reference
     * @param playerPos player's current world position
     * @param target    the position to walk toward
     * @param moveSpeed movement speed in blocks/second
     */
    static void beginMovement(@Nonnull PlayerState state,
                              @Nonnull PlayerRef playerRef,
                              @Nonnull TransformComponent transform,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull Ref<EntityStore> ref,
                              @Nonnull Vector3d playerPos,
                              @Nonnull Vector3d target,
                              double moveSpeed) {
        state.targetPosition = target;
        state.cursorOffsetX = target.x - playerPos.x;
        state.cursorOffsetZ = target.z - playerPos.z;

        double dx = target.x - playerPos.x;
        double dz = target.z - playerPos.z;
        double distSq = dx * dx + dz * dz;
        if (distSq > ClickToMoveManager.ARRIVAL_THRESHOLD_SQ) {
            sendVelocity(state, playerRef, dx, dz, distSq, moveSpeed);
            updateAnimation(state, store, ref, chooseAnimation(transform, dx, dz));
            setMovingStates(store, ref);
        }
    }

    /**
     * Sends a {@code ChangeVelocity} packet directly to the client. Bypasses the
     * velocity-instruction pipeline so we can use the protocol {@link VelocityConfig}.
     * The {@link #NO_DECAY_CONFIG} ensures velocity persists until explicitly stopped.
     *
     * @param state     per-player state to update
     * @param playerRef player reference (for packet sending)
     * @param dx        delta X to target
     * @param dz        delta Z to target
     * @param distSq    squared distance to target
     * @param moveSpeed movement speed in blocks/second
     */
    static void sendVelocity(@Nonnull PlayerState state,
                             @Nonnull PlayerRef playerRef,
                             double dx, double dz, double distSq,
                             double moveSpeed) {
        double dist = Math.sqrt(distSq);
        float vx = (float) ((dx / dist) * moveSpeed);
        float vz = (float) ((dz / dist) * moveSpeed);

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
     * Dot product &ge; 0 means the player faces the target → "Run";
     * otherwise → "RunBackward".</p>
     */
    @Nonnull
    static String chooseAnimation(@Nonnull TransformComponent transform,
                                  double dx, double dz) {
        float yaw = transform.getRotation().yaw();
        double forwardX = -Math.sin(yaw);
        double forwardZ = -Math.cos(yaw);
        double dot = forwardX * dx + forwardZ * dz;
        return dot >= 0 ? RUN_ANIMATION : RUN_BACKWARD_ANIMATION;
    }

    /**
     * Ensures the correct animation is playing on {@link AnimationSlot#Status}.
     * No-op if the desired animation is already playing. If switching, stops the
     * previous animation first.
     */
    static void updateAnimation(@Nonnull PlayerState state,
                                @Nonnull Store<EntityStore> store,
                                @Nonnull Ref<EntityStore> ref,
                                @Nonnull String animationName) {
        if (animationName.equals(state.currentAnimation)) return;
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
    static void stopAnimation(@Nonnull PlayerState state,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull Ref<EntityStore> ref) {
        if (state.currentAnimation == null) return;
        AnimationUtils.stopAnimation(ref, AnimationSlot.Status, true, store);
        state.currentAnimation = null;
    }

    /**
     * Sets movement states to "running on ground" for remote viewer animation sync.
     */
    static void setMovingStates(@Nonnull Store<EntityStore> store,
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
    static void stopMovement(@Nonnull PlayerState state,
                             @Nonnull Store<EntityStore> store,
                             @Nonnull Ref<EntityStore> ref,
                             @Nonnull PlayerRef playerRef) {
        state.targetPosition = null;
        state.targetEntity = null;
        state.lastSentAngle = Double.NaN;
        state.cursorOffsetX = 0;
        state.cursorOffsetZ = 0;

        playerRef.getPacketHandler().writeNoCache(
                new ChangeVelocity(0.0F, 0.0F, 0.0F, ChangeVelocityType.Set, NO_DECAY_CONFIG)
        );

        stopAnimation(state, store, ref);
        MovementStatesComponent msc = store.getComponent(ref, MovementStatesComponent.getComponentType());
        if (msc == null) return;

        MovementStates states = msc.getMovementStates();
        states.idle = true;
        states.horizontalIdle = true;
        states.running = false;
        states.walking = false;
        states.onGround = true;
    }
}
