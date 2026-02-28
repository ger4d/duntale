# ZSquad — Click-to-Move Camera System Dev Log

## Goal
Top-down / isometric camera with click-to-move body rotation, where the player's body and head track the mouse cursor and combat (damage) works correctly.

---

## Architecture Overview

### Key Files
- **CameraCommand.java** — `/camera topdown|iso|fps [--camrel] [--clickmove] [--xray]`
- **ClickToMoveManager.java** — Per-player state, model angle range override, click handler
- **ClickToMoveTickSystem.java** — ECS system stub (no tick logic currently)
- **38 item animation JSON overrides** — `src/main/resources/Server/Item/Animations/*.json`

### How It Works (Current State)
1. `/camera topdown --clickmove` sends `SetServerCamera` with:
   - `mouseInputType = LookAtPlane`
   - `planeNormal = (0, 1, 0)` — client auto-rotates head toward mouse on ground plane
   - `rotationType = Custom` (camera angle locked)
   - `movementForceRotationType = Custom` (WASD disabled for click-to-move)
2. `ClickToMoveManager.enable()` overrides the player model's `Yaw.AngleRange` to ±1 rad (~57°) so the body follows the head tightly.
3. Item animation assets override `Yaw.AngleRange` to `{Min: -1, Max: 1}` and normalize `Yaw.TargetNodes` to `["Head"]` to prevent items from clamping body rotation or causing wrong-axis rotation.
4. Left-click on ground spawns a particle at the target (interaction is cancelled to prevent accidental attacks on the ground).

---

## Problem History & Solutions

**Notes**: Client code is located at ~/client-src/Hytale-C-/

### Problem 1: Body snaps back to default orientation
**Root cause**: `UpdateWithoutPosition` (client `PlayerEntity.cs`) clamps body yaw by `AngleRange` every frame. Default `Entity.DefaultCameraAxis` is ±45° (±π/4).

**Solution**: Override the player model's `CameraSettings.Yaw.AngleRange` on the server via `ModelComponent`. Originally set to ±180° to fully disable clamping. Now set to ±1 rad for tighter body-follows-head behavior.

### Problem 2: Item CameraSettings override model AngleRange
**Root cause**: Client's `CameraSettings` priority chain: `ActionCameraSettings → _itemCameraSettings → _modelCameraSettings`. Items load `PlayerAnimations.Camera` from Embedded/Read-only JSON assets (server/Assets.zip), which hardcoded `Yaw.AngleRange: ±45°` (±0.7853982 rad). Holding any item overrode our model's wider range (current selected slot in the Hotbar is not empty).

**Solution**: Asset override via `IncludesAssetPack: true` in mod manifest. Copied all 38 item animation JSONs to `src/main/resources/Server/Item/Animations/`, removed the hardcoded `Yaw.AngleRange` (originally), then later set them to `{Min: -1, Max: 1}` to match the model.

### Problem 3: Battleaxe items cause wrong-axis model rotation
**Root cause**: Some items (Battleaxe, Axe, Bow, Crossbow, Daggers, Gloves, Handgun, Mace, Rifle, Shortbow, Staff, Sword) had `Yaw.TargetNodes: ["Belly"]` or multi-node arrays like `["Head", "LShoulder", "RShoulder"]`. With a wide yaw range, large body-look delta applied rotation to the Belly bone, causing visible torso twisting in X/Z axes.

**Solution**: Normalized all `Yaw.TargetNodes` to `["Head"]` across all 14 affected item animation JSONs.

### Problem 4: TeleportId desync on respawn (ClientTeleport approach)
**Root cause**: We initially sent body-rotation `ClientTeleport` packets with a standalone `nextTeleportId` counter. Game systems (respawn via `DeathComponent`, etc.) also create teleports through `PendingTeleport`, which maintains its own `nextTeleportId` / `lastTeleportId`. Our standalone counter desynchronized from the game's, causing `"Incorrect teleportId"` disconnects on GamePacketHandler.

**Solution (attempted and fixed the disconnection issue)**: Routed rotation teleports through `PendingTeleport.queueTeleport()` to share the same ID sequence. Included position in `ModelTransform` for ack validation.

**Final solution**: Abandoned the `ClientTeleport` approach entirely (see Problem 6).

### Problem 5: Damage doesn't work — head not facing target
**Root cause**: ALL combat selectors (`RaycastSelector`, `HorizontalSelector`, `StabSelector`, `AOECircle/CylinderSelector`) use `HeadRotation` (server) / `LookOrientation` (client) to determine attack direction — NOT body rotation. The interaction chain is client-initiated: the client runs its own selectors using `LookOrientation` to find targets before sending anything to the server. With `lookOrientation = null` in our `ClientTeleport`, the client's `LookOrientation` stayed wherever the camera pointed (straight down in top-down), never at enemies.

**Attempted fix**: Set `HeadRotation` server-side to match body direction. Did NOT work because the interaction chain is client-initiated — if the client's `LookOrientation` doesn't aim at a target, no interaction chain is generated at all.

### Problem 6: Camera snap with ClientTeleport lookOrientation
**Root cause**: `PlayerEntity.SetTransform → CameraModule.Controller.SetRotation()` sets `this.Rotation` which is the camera lerp source. Even with fast `lerpSpeed`, a single frame of wrong rotation was visible.

**Why we abandoned ClientTeleport**: Sending `lookOrientation` would fix damage but snap the camera. Sending `lookOrientation = null` avoids snap but breaks damage. No way to have both.

### Final Solution: planeNormal = (0, 1, 0)
**Approach**: Let the CLIENT handle head rotation natively.
- `mouseInputType = LookAtPlane` with `planeNormal = (0, 1, 0)` makes the client calculate where the mouse cursor intersects the Y=0 ground plane and rotates `LookOrientation` toward that point every frame.
- Model `Yaw.AngleRange = ±1 rad` makes the body follow the head with ~57° max offset.
- Item animation `Yaw.AngleRange = {Min: -1, Max: 1}` ensures items don't override with tighter clamping.
- **No teleport packets needed** — the client sends `lookOrientation` and `bodyOrientation` naturally in `ClientMovement`, so both server `HeadRotation` and `TransformComponent.rotation` update correctly.
- Combat works because the client's `LookOrientation` genuinely faces the cursor.

**Limitations**: Because the head always faces the mouse cursor (not the movement target), implementing click-to-move velocity requires computing the angle between `HeadRotation` (cursor direction) and the movement direction (toward the clicked position) to select the correct locomotion animation dynamically every time the mouse moves.

### Problem 7: Movement doesn't work — player stays in place
**Root cause**: Three interacting bugs:

1. **`Velocity.set()` is server-only**: Setting the `Velocity` component's velocity field modifies a server-side variable that is never applied to player position. The client is authoritative — it sends position via `ClientMovement` packets that overwrite `TransformComponent`. `Velocity.set()` only works for NPC entities whose position is calculated server-side.

2. **`MovementStatesComponent` overwritten by client each tick**: The client sends its own `MovementStates` based on WASD input (`_wishDirection`). Without keyboard input, `_wishDirection` is zero, so the client sends `idle=true`, `running=false` — overwriting our server-side flags. Remote viewers briefly see running (from our flags) then immediately see idle (from client's next tick).

3. **Local player animation driven by WASD, not server states**: `UpdateMovementAnimation()` (Entity.cs L3709) checks `_wishDirection` to decide animations. Without WASD input, it always picks "Idle" regardless of `MovementStates`. Only `AnimationSlot.Movement` (slot 0) can suppress this — other animation slots do NOT block `UpdateMovementAnimation()`.

**Solution**: Three-part fix:
- **Movement**: Send `ChangeVelocity` packets directly to the client via `PlayerRef.getPacketHandler().writeNoCache()` with `VelocityConfig(groundResistance=1.0)` for no-decay persistent velocity. Uses protocol `VelocityConfig` (public fields) instead of going through `Velocity.addInstruction` → `PlayerVelocityInstructionSystem` (which requires splitvelocity `VelocityConfig` with private fields).
- **Local animation**: `AnimationUtils.playAnimation(ref, AnimationSlot.Movement, "Run", sendToSelf=true, store)` — slot 0 suppresses client's `UpdateMovementAnimation()`, and `sendToSelf=true` ensures the local player sees the Run animation.
- **Remote animation**: `MovementStatesComponent` flags still set for remote viewers (they receive `ServerMovementStates` and use them directly since `GetRelativeMovementStates()` returns server-sent states for remote entities).
- **On arrival**: Zero-velocity `ChangeVelocity` packet + `AnimationUtils.stopAnimation(AnimationSlot.Movement)` + reset `MovementStatesComponent` to idle.

### Problem 7a: VelocityConfig private fields (splitvelocity vs protocol)
**Root cause**: `Velocity.addInstruction()` takes `com.hypixel.hytale.server.core.modules.splitvelocity.VelocityConfig` which has **private fields with no setters** — only a no-arg constructor with defaults (groundResistance=0.82). The protocol version (`com.hypixel.hytale.protocol.VelocityConfig`) has **public fields** and a full constructor.

**Solution**: Bypass `Velocity.addInstruction()` entirely. Send `ChangeVelocity` packets directly to the player's packet handler using the protocol `VelocityConfig`:
```java
playerRef.getPacketHandler().writeNoCache(
    new ChangeVelocity(vx, 0.0F, vz, ChangeVelocityType.Set, NO_DECAY_CONFIG)
);
```

### Problem 7b: AnimationSlot.Movement doesn't work — use AnimationSlot.Status
**Root cause**: Initial implementation used `AnimationSlot.Movement` (slot 0) based on the theory that it would suppress the client's `UpdateMovementAnimation()`. In practice, `AnimationSlot.Status` works for overriding the local player's animation, while `.Movement` does not.

**Solution**: Use `AnimationSlot.Status` for both `playAnimation` and `stopAnimation`:
```java
AnimationUtils.playAnimation(ref, AnimationSlot.Status, "Run", true, store);
AnimationUtils.stopAnimation(ref, AnimationSlot.Status, true, store);
```

### Problem 8: HeadRotation is horizontal with planeNormal=(0,1,0) — ground-plane intersection fails
**Root cause**: With `mouseInputType=LookAtPlane` and `planeNormal=(0,1,0)`, HeadRotation does **not** point from the eye toward the cursor's ground position. Instead, pitch is always ~0 (horizontal), and yaw encodes only the XZ direction toward the cursor. The direction vector has `dir.y ≈ 0`, making ground-plane intersection impossible (`t = (groundY - eye.y) / dir.y` → infinity).

**Debug evidence** (from `TargetUtil.getLook()` + `DebugUtils` visualization):
```
pitch=-0.000 yaw=-2.452 roll=0.000 | dir=(0.637, 0.000, 0.771) | eye=(167.0, 62.6, 23.9)
```

The direction is purely horizontal — HeadRotation gives direction but NOT distance to the cursor.

**Implications**:
- `TargetUtil.getTargetBlock(ref, ...)` shoots horizontally and hits walls — wrong for top-down.
- `TargetUtil.getLook()` cannot reconstruct cursor world position — only the direction.
- Ground-plane intersection approach is not viable.

**Solution**: Use the cursor offset approach (see Problem 9).

### Problem 9: Hold-click continuous movement with stationary mouse
**Root cause**: Mouse motion events only fire when the mouse actually moves. While holding click with a stationary mouse, the player moves, the camera follows, but the cursor's **world position** shifts — and no events fire to update the target. The player walks to the original target and stops, instead of continuing toward where the cursor now points.

**Key insight**: With a follow camera and stationary mouse, the **offset from player to cursor in world space is constant** — the camera moves with the player, and the cursor stays at the same screen position:
```
cursor_world = player_pos + constant_offset
```

**Solution**: Cursor offset approach:
1. On mouse event (click or drag), compute and store `offsetXZ = targetBlockCenter - playerPos`
2. On tick while button is held, recompute target as `currentPlayerPos + storedOffset`
3. If recomputed target is over void (no solid block at foot level), skip the update — player continues to the last valid target
4. On mouse release, clear `leftButtonHeld` but do NOT stop movement — player walks to last target until arrival

This gives the correct behavior: hold-click makes the player walk continuously toward where the cursor points, even as the camera follows.

### Problem 10: Only "Run" animation plays — no backward animation
**Root cause**: `startAnimation()` always played `"Run"` regardless of whether the player's body was facing the movement direction or away from it. The boolean `animationPlaying` flag didn't track *which* animation was active.

**Key insight**: The player's body yaw comes from `TransformComponent.getRotation().getYaw()`. Using Hytale's convention (from `Transform.getDirection()`), the body forward vector is:
```
forwardX = -sin(yaw)
forwardZ = -cos(yaw)
```
Compute the dot product of the forward vector with the movement direction `(dx, dz)`. If `dot >= 0`, the player is facing within ±90° of the movement direction → play `"Run"`. If `dot < 0`, the player is facing away → play `"RunBackward"`.

**Solution**:
- Added `chooseAnimation(transform, dx, dz)` — computes body-forward dot movement-direction, returns `"Run"` or `"RunBackward"`
- Replaced `boolean animationPlaying` with `@Nullable String currentAnimation` in `PlayerState` to track which animation is active
- Added `updateAnimation(state, store, ref, animName)` — if desired animation differs from current, stops the old and starts the new; same animation is a no-op
- Both `tickMovement()` (per-tick) and `updateTarget()` (on click/drag) call `chooseAnimation → updateAnimation`, so the animation updates dynamically as the player turns

---

## Animation Selection Reference

Animation selection is **entirely client-side** (`Entity.UpdateMovementAnimation()` in Entity.cs L3709-L4090). The server sends `MovementStates` boolean flags; the client maps those flags + velocity direction to animation keys.

### Forward vs Backward Determination

Determined client-side via the angle between position delta and `LookOrientation.Yaw` (Entity.cs L3715-3718):

```csharp
float num2 = MathHelper.WrapAngle(movementYaw - this.LookOrientation.Yaw);
bool isMovingBackward = num2 > 2.0943952f || num2 < -2.0943952f;  // ±120° (2π/3)
```

**Backward threshold: ±120°.** If the angle between movement direction and look direction exceeds 120°, the backward animation variant plays.

### Walk vs Run vs Sprint

Determined by **client input bindings**, NOT velocity thresholds (for players):

| State | Condition | Default speed multiplier |
|---|---|---|
| **Walk** | Player holds Walk key | 0.3× |
| **Run** | Default (no Walk, no Sprint) | 1.0× |
| **Sprint** | Sprint key + MoveForwards + on ground | 1.65× |

Note: There is no `IsRunning` flag on the client — `Running` is the implicit default when neither Walking nor Sprinting. The server protocol has a `running` flag that's derived: `running = !idle && !horizontalIdle && !walking && !sprinting`.

For **NPCs**, the server uses velocity-based thresholds with hysteresis (`fastMotionThreshold` per NPC role) to set `walking` vs `running`.

### Grounded Locomotion Animation Decision Tree

```
if IsHorizontalIdle           → "Idle"
if IsRolling                  → "SafetyRoll"
if IsSliding                  → "CrouchSlide"

if IsCrouching:
  ├─ backward?                → "CrouchWalkBackward"
  └─ forward?                 → "CrouchWalk"

if IsMantling                 → "MantleUp"

Ground locomotion:
  ├─ IsSprinting              → "Sprint"
  ├─ IsWalking:
  │   ├─ backward?            → "WalkBackward"
  │   └─ forward?             → "Walk"
  └─ else (Running = default):
      ├─ backward?            → "RunBackward"
      └─ forward?             → "Run"
```

There are **no dedicated strafe animations** — lateral movement reuses forward/backward animations based on the ±120° threshold above.

### All Grounded Animation Keys

**Core locomotion**: `Idle`, `Walk`, `WalkBackward`, `Run`, `RunBackward`, `Sprint`, `Crouch`, `CrouchWalk`, `CrouchWalkBackward`, `CrouchSlide`
**Steps**: `StepWalk`, `StepRun`, `StepSprint`, `StepCrouchWalk`
**Jumps**: `Jump`, `JumpWalk`, `JumpRun`, `JumpCrouch`
**Fall**: `Fall`, `FallFar`
**Combat**: `Interact`, `SwingLeft`, `SwingRight`, `SwingUpLeft`, `Guard`

### Click-to-Move Implications

For our click-to-move implementation, we use:
1. **`ChangeVelocity` packet** (id 163) sent directly to the client — controls actual movement
2. **`AnimationSlot.Status`** with `playAnimation("Run", sendToSelf=true)` — overrides local player animation
3. **`MovementStatesComponent`** — syncs animation flags to remote viewers only
4. **Cursor offset** — stored on click/drag, reapplied each tick as `playerPos + offset` for hold-click movement

The client's local animation is driven by `_wishDirection` (WASD input). Without keyboard input, it always shows "Idle" regardless of server-set `MovementStates`. `AnimationSlot.Status` overrides this.

**Key insight**: `Velocity.set()` modifies the server-side velocity field which is NEVER applied to player position (only used for NPCs). The client is authoritative for player position — it sends position in `ClientMovement` packets that overwrite the server's `TransformComponent`. To move a player, we must send a `ChangeVelocity` packet which the client applies in its local physics simulation.

**VelocityConfig**: Without a config (null), velocity is a one-shot impulse that decays with default resistance (0.82/tick). With `VelocityConfig(groundResistance=1.0)`, velocity persists until explicitly stopped with a zero-velocity instruction.
---

## Key Client/Server Code References

### Client (C#, read-only)
| File | Key Details |
|---|---|
| `PlayerEntity.cs L252-327` | `UpdateWithoutPosition`: Moving = always clamp by AngleRange; Idle = skip when range is ±π |
| `PlayerEntity.cs L512-535` | `UpdateModelLookOrientation`: applies body-look delta to TargetNodes |
| `Entity.cs L5984` | `DefaultCameraAxis`: `Rangef(-0.7853982, 0.7853982)` = ±45° |
| `Entity.cs L259-268` | CameraSettings priority: `ActionCameraSettings → _itemCameraSettings → _modelCameraSettings` |
| `Entity.cs L2218` | `SetupItemCamera`: reads item's `PlayerAnimations.Camera`, falls back to model for null fields |
| `Entity.cs L1177` | `SetCharacterModel`: clears `_itemCameraSettings = null` |
| `PacketHandler.cs L2338-2357` | `ProcessClientTeleportPacket`: applies transform, sends immediate ack `ClientMovement` with `TeleportAck(teleportId)` |
| `Entity.cs L3709-4090` | `UpdateMovementAnimation()`: maps MovementStates + velocity angle to animation keys |
| `Entity.cs L3715-3718` | Backward threshold: `±120°` angle between movement direction and `LookOrientation.Yaw` |
| `DefaultMovementController.cs L603` | Walk/Run/Sprint determined by input bindings, not velocity |

### Server (Java, read-only)
| File | Key Details |
|---|---|
| `PendingTeleport.java` | `validate(teleportId, position)`: checks `teleportId == lastTeleportId`, distance ≤ 0.001 |
| `TeleportSystems.java L277-312` | `teleportToPosition`: creates `PendingTeleport`, assigns sequential IDs |
| `GamePacketHandler.java L281-301` | Ack validation: `INVALID_ID → disconnect("Incorrect teleportId")` |
| `RaycastSelector.java` | Uses `HeadRotation.getRotation()` for ray direction |
| `HorizontalSelector.java` | Uses `HeadRotation` for frustum swing arc |
| `StabSelector.java` | Uses `HeadRotation` for thrust projection |
| `AOECircleSelector.java` | Rotates offset by `HeadRotation.getRotation().getYaw()` |
| `DamageEntityInteraction.java` | Uses `HeadRotation` for knockback direction |
| `PlayerVelocityInstructionSystem.java` | Drains `Velocity.getInstructions()`, sends `ChangeVelocity` packets to client, clears list |
| `Velocity.java` | `addInstruction(vec, config, type)` uses splitvelocity `VelocityConfig` (private fields) |
| `ChangeVelocity.java` (protocol) | Packet 163, uses protocol `VelocityConfig` (public fields), sent via `writeNoCache()` |
| `AnimationUtils.java` | `playAnimation(ref, slot, animId, sendToSelf, store)` / `stopAnimation(ref, slot, sendToSelf, store)` — use `AnimationSlot.Status` |
| `TargetUtil.java` | `getLook(ref, store)` → `Transform` with eye position + HeadRotation direction; `getTargetBlock(ref, dist, store)` → 3D block raycast (NOT suitable for top-down) |
| `DebugUtils.java` | `addSphere()`, `addLine()`, `addArrow()` — server-to-all-clients debug shapes |

---

## Current State (2026-02-28)

### Working
- Top-down and isometric camera views (`/camera topdown`, `/camera iso`)
- Head automatically tracks mouse cursor via `planeNormal = (0, 1, 0)`
- Body follows head with ±1 rad (~57°) AngleRange
- Item animations don't override AngleRange (all set to `{Min: -1, Max: 1}`)
- No Belly/multi-node TargetNodes issues (all normalized to `["Head"]`)
- Camera-relative and head-relative WASD movement modes
- Block occlusion (xray) support
- **Click-to-move**: left-click sets target, player moves toward it at 8 b/s via `ChangeVelocity` packets (persistent velocity with `VelocityConfig(groundResistance=1.0)`)
- **Drag-to-move**: holding left button while moving mouse continuously updates target (`PlayerMouseMotionEvent` + `sendMouseMotion = true`); velocity direction re-sent only when angle changes >0.1 rad
- **Hold-click continuous movement**: stores XZ cursor offset on click/drag; on each tick while button held, recomputes target as `playerPos + offset`. Validates target is over solid ground (skips void). Gives smooth continuous walking toward cursor with follow camera.
- **Mouse release does NOT stop movement**: player continues to last valid target until arrival
- **Wall detection**: clicks on blocks above player Y probe 4 cardinal neighbours at foot level for walkable ground
- **Run / RunBackward animation**: dynamically selects `"Run"` or `"RunBackward"` based on dot product of body forward vector `(-sin(yaw), -cos(yaw))` and movement direction — switches within ±90° threshold. Uses `AnimationSlot.Status` with `sendToSelf=true`; `MovementStatesComponent` flags for remote viewers
- **Arrival stop**: zero-velocity `ChangeVelocity` + `stopAnimation(AnimationSlot.Status)` + states reset to idle when within 1 block of target
- **Clean disable**: `disable()` sends stop velocity + stops animation if player was moving
- **Player-only particles**: `ParticleUtil.spawnParticleEffect` with `Collections.singletonList(ref)` — only visible to the clicking player
- **Click-only particles**: particles spawn on left-click press only, not during mouse drag
- No teleportId desync issues (teleport packets removed entirely)

### Known Issues
- **"Failed check getActiveSlot: X != Y"** — Only occurs when our custom camera (topdown/iso) is active; never with the default FPS camera. The check is a server-side validation in `InteractionModule.doMouseInteraction()` (InteractionModule.java L358-364): the client's `MouseInteraction` packet includes its `activeSlot`, and the server compares it against `playerComponent.getInventory().getActiveHotbarSlot()`. On mismatch, the interaction is rejected and the debug message appears in chat.

  **Root cause**: `sendMouseMotion = true` causes the client to send `MouseInteraction` packets on every mouse-move frame (not just on click). Every packet carries `activeSlot`, and the high packet volume increases the window for a race between hotbar slot-switch and the next mouse-motion packet arriving with stale slot data.

  **Resolution**: With `sendMouseMotion = false`, the error no longer triggers on mouse movement. It can still appear on rapid hotbar-switch + fast repeated clicks, but this is a base-game race condition (the client snapshots `activeSlot` at send time, and a scroll event may not yet be processed server-side). It self-corrects quickly and is cosmetic only.

  **Note**: `sendMouseMotion = true` is required for `PlayerMouseMotionEvent` to fire. The built-in `PlayerCameraTopdownCommand` does NOT set it. Currently left enabled because we need motion events for drag-to-move.

  **Why cancelling `PlayerMouseButtonEvent` does NOT help**: `InteractionModule.doMouseInteraction()` dispatch is fire-and-forget — `isCancelled()` is never checked. The `activeSlot` check runs **before** event dispatch.

### Important Anti-Patterns (DON'T USE)
- `Velocity.set()` — server-only, never applied to player position
- `Velocity.addInstruction()` — requires splitvelocity `VelocityConfig` with private fields
- `AnimationSlot.Movement` — doesn't work for click-to-move, use `.Status`
- `MovementStatesComponent` alone — client overwrites from WASD each tick
- `TargetUtil.getTargetBlock(ref, ...)` — 3D block raycast from HeadRotation, shoots horizontally with `planeNormal=(0,1,0)`, hits walls instead of ground
- `TargetUtil.getLook()` for cursor position — HeadRotation gives XZ direction only (pitch≈0), cannot compute distance to cursor
- Ground-plane intersection via `getLook()` — `dir.y ≈ 0` makes `t` infinite
- Stopping movement on mouse release — walk-to-arrival is the intended behavior

### Not Yet Implemented / To Restore
- **Wall face-aware resolution**: Currently probes 4 cardinal neighbours blindly when click hits a wall. Corner walls may resolve to perpendicular ground instead of the face-aligned ground the player intended. Enhancement: use the clicked face normal to prioritize the correct neighbour.
- Directional speed multipliers (forward/backward)
- DisablePrimary entity effect (prevent accidental ground attacks)
- Selective entity attack (attack on click when cursor is over enemy)
- Pathfinding / obstacle avoidance
- Packet filters (if needed)

### Active Constants
```
ARRIVAL_THRESHOLD = 1.0  (stop distance)
MOVE_SPEED = 8.0         (blocks/second)
FOLLOW_YAW_RANGE = ±1 rad (~57°)
```

### Asset: DisablePrimary.json
Already exists at `src/main/resources/Server/Entity/Effects/Status/DisablePrimary.json`:
```json
{"Infinite": true, "ApplicationEffects": {"AbilityEffects": {"Disabled": ["Primary"]}}}
```


### Future Notes:
- If side strafing like is desired, we can dynamically