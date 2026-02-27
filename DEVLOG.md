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

For our click-to-move implementation, we only need to manage `MovementStates` flags on the server (via `MovementStatesComponent`) — the client handles all animation selection automatically.

**Verified**: Remote entities receive `MovementStates` via `ServerMovementStates` (parsed by `ClientMovementStatesProtocolHelper.Parse` from network packets). `GetRelativeMovementStates()` is virtual — local player overrides with client-predicted states, remote entities use server-sent states directly. **Flags alone trigger animation even with zero position delta** (entity "runs in place"). Animation speed for remote entities uses asset-defined values only (no velocity-based scaling).

**Components needed**:
- `MovementStatesComponent` (component 10) — for animation flag sync
- `TransformComponent` (component 9) — for actual position movement

The key flags to set are:
- `running = true` (default movement), or `walking = true` for slow approach
- `idle = false`, `horizontalIdle = false` while moving
- `onGround = true`
- The client will pick forward/backward animation based on the angle between velocity and `LookOrientation` (which tracks the cursor).
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

---

## Current State (2026-02-27)

### Working
- Top-down and isometric camera views (`/camera topdown`, `/camera iso`)
- Head automatically tracks mouse cursor via `planeNormal = (0, 1, 0)`
- Body follows head with ±1 rad (~57°) AngleRange
- Item animations don't override AngleRange (all set to `{Min: -1, Max: 1}`)
- No Belly/multi-node TargetNodes issues (all normalized to `["Head"]`)
- Camera-relative and head-relative WASD movement modes
- Block occlusion (xray) support
- Left-click particle feedback
- No teleportId desync issues (teleport packets removed entirely)

### Known Issues
- **"Failed check getActiveSlot: X != Y"** — Only occurs when our custom camera (topdown/iso) is active; never with the default FPS camera. The check is a server-side validation in `InteractionModule.doMouseInteraction()` (InteractionModule.java L358-364): the client's `MouseInteraction` packet includes its `activeSlot`, and the server compares it against `playerComponent.getInventory().getActiveHotbarSlot()`. On mismatch, the interaction is rejected and the debug message appears in chat.

  **Root cause**: `sendMouseMotion = true` causes the client to send `MouseInteraction` packets on every mouse-move frame (not just on click). Every packet carries `activeSlot`, and the high packet volume increases the window for a race between hotbar slot-switch and the next mouse-motion packet arriving with stale slot data.

  **Resolution**: With `sendMouseMotion = false`, the error no longer triggers on mouse movement. It can still appear on rapid hotbar-switch + fast repeated clicks, but this is a base-game race condition (the client snapshots `activeSlot` at send time, and a scroll event may not yet be processed server-side). It self-corrects quickly and is cosmetic only.

  **Note**: `sendMouseMotion = true` is required for `PlayerMouseMotionEvent` to fire. The built-in `PlayerCameraTopdownCommand` does NOT set it. Currently left enabled because we need motion events for drag-to-move.

  **Why cancelling `PlayerMouseButtonEvent` does NOT help**: `InteractionModule.doMouseInteraction()` dispatch is fire-and-forget — `isCancelled()` is never checked. The `activeSlot` check runs **before** event dispatch.

### Not Yet Implemented / To Restore
- Click-to-move pathfinding / velocity (movement toward clicked position)
- Run/Walk animations triggered by movement (MovementStates flags — verified sufficient, see Animation section)
- Directional speed multipliers (forward/backward/strafe)
- DisablePrimary entity effect (prevent accidental ground attacks)
- Selective entity attack (attack on click when cursor is over enemy)
- Drag-to-move (hold mouse to continuously move)
- Packet filters (if needed)

### Known Constants (from prior implementation)
```
ARRIVAL_THRESHOLD = 1.0
DIRECTION_CHANGE_THRESHOLD = 0.05
BACKWARD_ANGLE_THRESHOLD = 120°
FORWARD_ANGLE_THRESHOLD = 60°
MOVE_SPEED = 8.0
FORWARD_MULTIPLIER = 1.0
SIDE_MULTIPLIER = 0.8
BACKWARD_MULTIPLIER = 0.65
```

### Asset: DisablePrimary.json
Already exists at `src/main/resources/Server/Entity/Effects/Status/DisablePrimary.json`:
```json
{"Infinite": true, "ApplicationEffects": {"AbilityEffects": {"Disabled": ["Primary"]}}}
```
