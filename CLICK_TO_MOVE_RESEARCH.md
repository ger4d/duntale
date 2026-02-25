# Click-to-Move Animation Research Report

> **Date**: 2025-02-24  
> **Scope**: Deep analysis of why click-to-move animations fail, and the correct implementation strategy.  
> **Sources**: Hytale server source (`src-server/`), decompiled client source (`~/client-src/Hytale-C-/`).

---

## 1. Problem Statement

The current `ClickToMoveManager` successfully moves the player via server-applied velocity, but **fails to play the correct movement animations**. The player appears to slide (ice-skating) while in idle pose.

---

## 2. Architecture: Client-Authoritative Movement

Hytale uses **client-authoritative movement** for players:

| Layer | Authority | Mechanism |
|---|---|---|
| Position | **Client** | Client computes position from physics, sends `ClientMovement` (packet 108) each tick |
| Movement physics | **Server-configured, client-executed** | Server sends `MovementSettings` (60+ params); client simulates locally |
| Velocity impulses | **Server → Client** | `ChangeVelocity` (packet 163) / `ApplyKnockback` (164) sent to client |
| Teleport | **Server → Client** | `ClientTeleport` (packet 109), requires client ack |
| Animation | **Client-computed** | Driven by `MovementStates` derived from keyboard input (`_wishDirection`) |

The server **cannot** directly move players position-by-position. It can only:
1. Configure movement parameters (`MovementSettings`)
2. Apply velocity impulses (`ChangeVelocity`)
3. Teleport (`ClientTeleport`)
4. Override animations via `PlayAnimation` packets

---

## 3. Root Cause Analysis — Three Bugs

### Bug 1: Animations are tied to `_wishDirection`, not velocity

Client code in `DefaultMovementController.UpdateMovementStates()`:

```csharp
// _wishDirection is set ONLY from WASD keyboard input
IsHorizontalIdle = (_wishDirection == Vector2.Zero);
IsIdle = (|Yvel| ≈ 0) && (_wishDirection == Zero || |XZvel| ≈ 0);
```

The animation decision tree in `Entity.UpdateMovementAnimation()`:

```
if (!IsIdle) {
    ...
    if (IsHorizontalIdle) → play "Idle"     ← HIT: no WASD = horizontalIdle
    if (IsSprinting)      → play "Sprint"
    if (IsWalking)        → play "Walk"
    else                  → play "Run"       ← never reached
}
```

When server applies velocity via `ChangeVelocity`, `_wishDirection` stays `Vector2.Zero` (no WASD), so `IsHorizontalIdle = true`, and the animation tree plays **"Idle"** even though the player is physically moving.

### Bug 2: Wrong animation slot

Current code uses `AnimationSlot.Status` (server slot 1 → client internal slot 4):

```java
AnimationUtils.playAnimation(ref, AnimationSlot.Status, RUN_ANIMATION, true, store);
```

The client's animation priority check in `Entity.UpdateCharacter()`:

```csharp
bool flag = this.ServerAnimations[0] == null;  // slot 0 = Movement
if (flag) {
    this.UpdateMovementAnimation();  // locally-computed from _wishDirection
}
```

**Only `ServerAnimations[0]` (Movement slot) can suppress `UpdateMovementAnimation()`.** Playing on Status (slot 4) doesn't block the movement animation — the client computes "Idle" on slot 0 and that takes visual priority for the body.

### Bug 3: Velocity decays instantly (legacy path)

Current code uses `ChangeVelocityType.Set` without `VelocityConfig`:

```java
velocity.addInstruction(new Vector3d(vx, 0, vz), null, ChangeVelocityType.Set);
```

In the legacy path (no `VelocityConfig`):
1. `_requestedVelocity` is set to `(vx, 0, vz)`
2. Next client tick: `_velocity = _requestedVelocity` 
3. Same tick: `_requestedVelocity` is **zeroed**
4. Next tick: `ComputeMoveForce()` applies ground drag (`_velocity.X *= blockDrag`) → rapid decay

Result: Each server tick (50ms) produces a tiny velocity impulse that decays across client ticks (16ms). Movement is jerky and slow.

---

## 4. Client Animation Priority System

The client has **9 internal animation slots** mapped from the server's 5:

| Server `AnimationSlot` | Value | Client Internal Slot |
|---|---|---|
| `Movement` | 0 | 0 |
| `Status` | 1 | 4 |
| `Action` | 2 | 6 |
| `Face` | 3 | 7 |
| `Emote` | 4 | 8 |

The critical gate in `Entity.UpdateCharacter()`:

```csharp
if (ServerAnimations[0] == null)        // Only Movement slot blocks this
    UpdateMovementAnimation(deltaTime); // Locally-computed from _wishDirection
```

When `ServerAnimations[0]` is set (via `PlayAnimation` on slot 0):
- `UpdateMovementAnimation()` is **completely skipped**
- The server-forced animation plays exclusively on the body

For **looping** animations (like "Run", which has `looping = true` by default):
- `IsSlotPlayingAnimation(0)` returns `true` indefinitely
- `ServerAnimations[0]` **never auto-clears**
- Must explicitly send `PlayAnimation(slot=0, animationId=null)` to clear

For **non-looping** animations:
- Auto-clears when animation completes
- `UpdateMovementAnimation()` resumes immediately

### PlayAnimation applies to the local player

There is **no special-case filtering** — the local player processes `PlayAnimation` packets for itself identically to remote entities. `AnimationUtils.playAnimation()` with `sendToSelf=true` sends to all visible players including the entity itself.

---

## 5. Velocity Systems Deep Dive

### Legacy Path (no VelocityConfig)

```
Server: velocity.addInstruction(vel, null, Set)
  → PlayerVelocityInstructionSystem sends ChangeVelocity packet
    → Client: _requestedVelocity = vel; _requestedVelocityChangeType = Set
      → Next tick: _velocity = _requestedVelocity; _requestedVelocity = 0
        → Next tick: _velocity.XZ *= blockDrag (rapid decay)
```

One-shot impulse. Decays naturally via ground drag.

### SplitVelocity Path (with VelocityConfig)

```
Server: velocity.addInstruction(vel, config, Set)
  → PlayerVelocityInstructionSystem sends ChangeVelocity packet with config
    → Client: _appliedVelocities.clear(); _appliedVelocities.add(vel, config)
      → Each tick: movementOffset += appliedVelocity; vel.XZ *= config.groundResistance
        → Removed when |vel|² < 0.001
```

Tracked **separately** from `_velocity`. Decay controlled by `VelocityConfig`:

| Field | Default | Effect |
|---|---|---|
| `groundResistance` | 0.82 | Multiply XZ by this per tick on ground |
| `groundResistanceMax` | 0.0 | Blend target as velocity drops below threshold |
| `airResistance` | 0.96 | Multiply XZ by this per tick in air |
| `airResistanceMax` | 0.0 | Blend target in air |
| `threshold` | 1.0 | Velocity magnitude at which blending starts |
| `style` | Linear | Blending curve (Linear or Squared) |

**With `groundResistance = 1.0` and `groundResistanceMax < 0` (disabled):**
- Velocity **never decays** → persists indefinitely
- To stop: send `ChangeVelocity(Set, 0, 0, 0)` with config → clears `_appliedVelocities`

---

## 6. MovementStates Broadcast Behavior

The server's `MovementStatesSystems.TickingSystem`:
- Compares `movementStates` vs `sentMovementStates` each tick
- If changed, broadcasts `MovementStatesUpdate` to all viewers **except the entity itself**
- The local player **never receives** its own `MovementStatesUpdate`

On the client:
- Remote entities: `GetRelativeMovementStates()` returns `ServerMovementStates` (from network)
- Local player: `GetRelativeMovementStates()` returns `MovementController.MovementStates` (from WASD)

**Setting `MovementStatesComponent` on the server only affects what OTHER players see.** It does not affect the local player's animation. This is why the current `setWalkState()` approach doesn't fix the local player's animation.

---

## 7. Body Rotation

Body orientation (`BodyOrientation.Yaw`) is set in `PlayerEntity.UpdateWithoutPosition()`:
- **When moving**: Body turns toward `_wishDirection` rotated by `CameraModule.MovementForceRotation`
- **When idle**: Body loosely follows look direction within angle constraints

Since click-to-move doesn't produce `_wishDirection`, the body won't rotate toward movement. However, the overhead camera's `MouseInputType.LookAtPlane` already rotates the **head** toward the cursor. In practice, this looks acceptable — the character faces the cursor while the Run animation plays.

For perfect body rotation, the server would need to send body orientation updates, but this is a visual polish concern, not a blocker.

---

## 8. Correct Implementation Strategy

### Movement: VelocityConfig with no-decay

```java
// Create a VelocityConfig that doesn't decay:
VelocityConfig config = new VelocityConfig();
config.groundResistance = 1.0F;      // no decay on ground
config.groundResistanceMax = -1.0F;   // disable threshold blending
config.airResistance = 1.0F;          // no decay in air  
config.airResistanceMax = -1.0F;      // disable threshold blending
config.threshold = 0.0F;

// On click (start movement):
velocity.addInstruction(new Vector3d(vx, 0, vz), config, ChangeVelocityType.Set);

// On direction change:
velocity.addInstruction(new Vector3d(newVx, 0, newVz), config, ChangeVelocityType.Set);

// On arrival (stop movement):
velocity.addInstruction(new Vector3d(0, 0, 0), config, ChangeVelocityType.Set);
```

### Animation: PlayAnimation on Movement slot (0)

```java
// On click (start movement):
AnimationUtils.playAnimation(ref, AnimationSlot.Movement, "Run", true, store);
// "Run" is looping by default → ServerAnimations[0] persists → blocks UpdateMovementAnimation()

// On arrival (stop movement):
AnimationUtils.stopAnimation(ref, AnimationSlot.Movement, true, store);
// Sends PlayAnimation(slot=0, animationId=null) → clears ServerAnimations[0]
// → UpdateMovementAnimation() resumes → shows "Idle"
```

### Tick loop simplification

No need to re-send velocity or animation every tick. The 20Hz loop only needs to:
1. Check arrival distance
2. Recalculate direction if target changed (drag)
3. Send velocity update only when direction changes significantly

```
On click/drag:
├─ ChangeVelocity(Set, vx, 0, vz) with VelocityConfig(resistance=1.0)  [once]
├─ PlayAnimation(slot=Movement, "Run", sendToSelf=true)                  [once]
└─ Set MovementStatesComponent(idle=false, running=true)                 [for others]

Each tick (~50ms):
├─ Check distance to target
├─ If arrived → stop velocity, stop animation, clear target
└─ If direction changed significantly → update velocity only

On arrival:
├─ ChangeVelocity(Set, 0, 0, 0) with VelocityConfig                   [once]
├─ StopAnimation(slot=Movement, sendToSelf=true)                        [once]
└─ Reset MovementStatesComponent(idle=true)                             [for others]
```

---

## 9. Summary of Changes

| Current (Broken) | Fixed |
|---|---|
| `AnimationSlot.Status` | `AnimationSlot.Movement` |
| `ChangeVelocity(Set)` without config (decays instantly) | `ChangeVelocity(Set)` with `VelocityConfig(groundResistance=1.0)` (persists) |
| Re-send velocity 20x/sec | Send once, update on direction change |
| Re-send PlayAnimation 5x/sec | Send once (looping persists), clear on arrival |
| Debug logging every tick | Remove debug logging |
| `setWalkState` + `setIdleState` on MovementStates | Keep for remote viewers, but not primary fix |

---

## 10. Risks & Edge Cases

- **Jumping/falling**: If player walks off an edge during click-to-move, the applied velocity continues horizontally. May want to detect falling and cancel, or let it ride.
- **Collision**: Applied velocity pushes into walls. The client's collision system handles this (slides along walls), but the server's arrival check won't trigger since the player can't reach the target. Add a "stuck" timeout.
- **Disconnect/teleport**: On teleport, `_appliedVelocities` persists. Need to send a stop-velocity on teleport or mode change.
- **Animation name**: "Run" must exist in the player model's AnimationSets. This is standard for the default player model.
