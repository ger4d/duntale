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

### Problem 11: Entity click detection — spatial queries approach (failed)

**Goal**: Detect when the player clicks on an entity (NPC/mob) during click-to-move, to support attack targeting.

**First attempt — server-side spatial queries**:
Used `EntityModule.get().getNetworkSendableSpatialResourceType()` → `SpatialStructure.collect()` to gather entities near the cursor position each tick, then checked bounding box intersection with a cursor ray. This detected entities but was unreliable/buggy in practice — inconsistent hit detection, false positives, and missed clicks.

**Solution — event-based `getTargetEntity()`**:
Both `PlayerMouseButtonEvent` and `PlayerMouseMotionEvent` provide `getTargetEntity()` which returns the `Entity` under the cursor (client-authoritative ray hit). This is accurate and consistent because the client already does precise entity picking.

- Added `resolveTargetEntity(Entity, Ref)` helper — extracts a valid `Ref<EntityStore>` from the event entity, excluding null, invalid refs, and self-targeting
- Added `@Nullable volatile Ref<EntityStore> targetEntity` field in `PlayerState`
- Both `onMouseButton` and `onMouseMotion` extract the entity ref and pass it to `updateTarget()`
- On click/motion: if entity exists and is within attack range → stop movement + log "Attacking"; if out of range → store entity ref, begin movement toward entity position

**Limitation**: `getTargetEntity()` only fires on click or mouse motion events. During held-click stationary movement (no mouse motion), no new entity detection occurs. Handled by storing `targetEntity` in `PlayerState` and checking range each tick in `tickMovement()`.

---

### Problem 12: Clicking on entity doesn't start movement

**Symptom**: Clicking on an entity that is out of attack range does not move the player toward it.

**Root cause**: `updateTarget()` had `if (targetBlock == null) return;` at the top of the method. When clicking on an entity floating above ground (or when the ray hits the entity but not a block), `targetBlock` is null and the method bails out before ever checking the entity ref.

**Solution**: Moved entity targeting logic BEFORE the null-block guard. The method now:
1. Checks entity targeting first (independent of targetBlock)
2. If entity found → handles it and returns
3. Only then checks `if (targetBlock == null) return;` for ground targeting

---

### Problem 13: DisablePrimary effect — preventing accidental attacks in click-to-move

**Goal**: When click-to-move is enabled, left-click should move the player, not trigger the primary attack ability. Need to suppress the "Primary" ability.

**Solution**: Used the `DisablePrimary.json` EntityEffect asset (already exists at `Server/Entity/Effects/Status/DisablePrimary.json`) which sets `AbilityEffects.Disabled: ["Primary"]` with `Infinite: true`.

- Added `applyDisablePrimary(store, ref)` in `CameraCommand` — loads the effect via `EntityEffect.getAssetMap().getAsset("DisablePrimary")`, then calls `EffectControllerComponent.addEffect(ref, effect, store)`
- Added `removeDisablePrimary(store, ref)` — gets the effect index via `EntityEffect.getAssetMap().getIndex("DisablePrimary")`, then calls `ecc.removeEffect(ref, effectIndex, store)`
- `enableOptionalFeatures()` applies the effect when clickMove is enabled
- `disableAllFeatures()` removes the effect on camera reset

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

### Problem 14: BlockOcclusionManager — iterative algorithm redesign

**Goal**: Remove structural wall/ceiling blocks between the camera and the player so the player is visible in overhead camera modes. Only replace "structural" blocks (walls, ceilings, pillars) — not decoratives (torches, furniture, plants, rubble).

**Phase 1 — Wall block whitelist**:
Created `scripts/extract_wall_blocks.py` to parse all 7 dungeon-gen theme JSONs (`Crypt`, `Hive`, `Mine`, `Arcane`, `Temple_Dark`, `Volcanic`, `Mushroom`). Extracts structural palette keys: `PrimaryWall`, `SecondaryWall`, `Ceiling`, `PillarBase`, `PillarMiddle`, `DecayVariants`, `Stairs`, `Slab`, `AccentBlock`. Excludes: `Floor` (below player), `OvergrowthBlocks` (thin/transparent), `RubbleBlocks` (floor-level), `Fluids`, `Lights`, `Props`. Produces 47 unique asset keys stored in `WALL_BLOCK_KEYS`. Lazy-resolved to integer block IDs via `BlockType.getAssetMap().getIndex(key)` on first use.

**Phase 2 — 3D ray + radius (failed)**:
Computed camera 3D position from `(yaw, pitch, distance)`, then traced a single ray from camera to player. At each Y level, computed the XZ intercept and cleared a radius around it. **Problem**: uniform `RAY_CLEAR_RADIUS=2` at all Y levels caused clearing behind the player at low Y — because near the player the ray intercept and player position nearly coincide, so the radius extends beyond the player in the wrong direction.

**Phase 3 — Parallel 9-ray cast (partially worked)**:
Cast 9 parallel rays (center + 8 adjacent open tiles). Each ray's camera origin was offset by the same XZ displacement as its target to simulate orthographic projection. Used entry/exit AABB at each Y slab to catch all blocks diagonal rays pass through. **Problem**: still missed some wall blocks visible through the debug lines. The 3D ray approach was fundamentally fragile for the isometric angle — small rounding at block boundaries caused misses.

**Phase 4 — 2D floor-plane DDA (current solution)**:
Key insight: dungeon walls are uniform vertical columns. Instead of tracing 3D rays, project the camera direction onto the XZ plane and perform a 2D DDA (Amanatides–Woo) voxel traversal. For each tile the ray visits, check the block at `playerY` (foot level). If it's a wall block, clear the entire column from `playerY` to `playerY + WALL_CLEAR_HEIGHT` (4 blocks).

9 rays: 1 center (player tile) + up to 8 adjacent tiles (skipped if they contain wall blocks at foot/head level). Direction: `(sin(yaw), cos(yaw))` normalized — the camera-facing direction projected on XZ. Max cast distance: 20 blocks.

**Anti-flicker fix**: tick 1 replaces walls with barrier blocks; tick 2's DDA reads barriers (not walls) → doesn't include them → restores → tick 3 sees walls again. Fixed by treating barriers (`replaceId`) as walls in the DDA check: `wallIds.contains(blockId) || blockId == replaceId`. Also increased tick interval from 250ms to 500ms (chunk needs time to commit block writes).

**Replacement block**: Uses `"Barrier"` (solid but invisible) instead of air — prevents lighting recalculation artifacts and entity fall-through.

---

### DisablePrimary — always active in overhead camera modes

**Change**: Previously `applyDisablePrimary()` was only called when `clickMove` was enabled. Changed to always apply in `enableOptionalFeatures()` for ANY overhead camera mode (topdown/iso), regardless of click-to-move state. Prevents accidental primary attacks (hit/swing/shot) in all overhead views. Removed on `/camera fps` reset via `disableAllFeatures()`.

---

### Weapon Family Research — Ranged vs Melee Classification

**Finding**: There is no direct `isRanged` flag on weapon items. The best signal is `Tags.Family` in the weapon JSON (`item.getData().getRawTags().get("Family")`).

**Classification by family**:
| Category | Families |
|----------|----------|
| **Ranged** | Bow, Crossbow, Gun, Bomb, Spellbook |
| **Hybrid** (melee + ranged) | Staff, Wand, Spear |
| **Melee** | Sword, Longsword, Axe, Club, Dagger, Mace, Stick |

**Other signals considered**: Damage type (`"Projectile"` vs `"Physical"` in `BaseDamage`) — deeply nested, some weapons have both. `PlayerAnimationsId` — indirect. `ItemWeapon.java` — only holds `StatModifiers`, `EntityStatsToClear`, `RenderDualWielded` — no range/type info.

**Recommended approach**: `Set<String> RANGED_FAMILIES = Set.of("Bow", "Crossbow", "Gun", "Bomb", "Spellbook")` + `HYBRID_FAMILIES = Set.of("Staff", "Wand", "Spear")`. Check held weapon family in `ClickToMoveManager` to decide melee range check vs ranged behavior.

---

### Problem 15: Server-forced attacks — DisablePrimary bypass

**Goal**: DisablePrimary (client-side only) blocks client-initiated attacks. Server needs to force attacks when click-to-move detects an entity in range.

**Key discovery**: DisablePrimary is CLIENT-SIDE ONLY enforcement. The server's `syncStart()` never checks `AbilityEffects.disabled`. Server can force interaction chains via `InteractionManager.startChain()` / `queueExecuteChain()` which bypass all client validation.

**Approach**: Keep DisablePrimary permanently active in overhead camera modes. When the server detects a click on an entity in range → force attack via `InteractionManager.queueExecuteChain()`. This is the same code path as `/interaction run Primary`.

**Implementation**: `triggerAttack()` in `ClickToMoveManager`:
1. Get `InteractionManager` component from entity
2. Create `InteractionContext.forInteraction(im, ref, InteractionType.Primary, store)`
3. Resolve `RootInteraction` asset from the context's root interaction ID
4. `im.initChain()` → `im.queueExecuteChain()` — queues for next `InteractionManager.tick()`
5. Client receives `SyncInteractionChains` packet (negative chainId = server-initiated) and plays weapon animation

**Ranged weapon detection**: `isRangedWeapon()` checks `RootInteraction.getData().getRawTags().get("Attack")` for `"Ranged"` tag. Ranged weapons fire immediately without walking to target.

**Limitation**: `queueExecuteChain()` fires instantly even for weapons with charge gates. Held-click during server-forced chain skips the charge phase.

---

### Problem 16: Attack fires on every mouse motion (no cooldown)

**Symptom**: With mouse continuously moving over an entity, `triggerAttack()` was called on every `PlayerMouseMotionEvent` frame — multiple times per tick.

**Analysis**: The engine's `executeChain0()` already checks `isOnCooldown()` and silently rejects chains on cooldown. So only the first call per weapon cooldown actually fires. But the overhead of creating `InteractionContext`, resolving `RootInteraction`, and allocating `InteractionChain` on every mouse frame was wasteful.

**Solution**: Added `lastAttackNanos` to `PlayerState` and a 400ms time-based throttle (`ATTACK_THROTTLE_NS`) in `triggerAttack()`. Returns early if less than 400ms since last call. This drastically reduces object allocation while the engine's built-in cooldown handles the actual weapon timing (data-driven via `RootInteraction.getCooldown()`, default 350ms for Primary/Secondary).

---

### Problem 17: Hurt animation overrides Run animation (stutter-walk)

**Symptom**: When the player takes damage while walking, the "Hurt" animation plays on `AnimationSlot.Status` — the same slot used for our "Run"/"RunBackward" click-to-move animation. This cancels the Run animation, causing the player to briefly appear idle before the next tick re-applies Run.

**Root cause**: The engine's damage feedback system sends `PlayAnimation` packets with `animationId` starting with "Hurt" on `AnimationSlot.Status`. In normal FPS mode this is fine (movement uses `AnimationSlot.Movement`), but click-to-move uses Status because Movement is client-controlled.

**Solution**: Registered `PacketAdapters.registerOutbound(PlayerPacketFilter)` in the `ClickToMoveManager` constructor. The filter drops outbound `PlayAnimation` packets where:
- `packet instanceof PlayAnimation pa`
- `pa.slot == AnimationSlot.Status`
- `pa.animationId != null && pa.animationId.startsWith("Hurt")`
- Player is in CTM mode (`players.containsKey(playerRef.getUuid())`)

This preserves the damage number/sound feedback while preventing the animation slot conflict.

---

### Problem 18: Excessive knockback in isometric mode

**Symptom**: When the player takes melee damage in overhead camera mode, knockback launches them across the arena — far more than in normal FPS mode.

**Root cause**: `HackKnockbackValues.PLAYER_KNOCKBACK_SCALE = 25.0f` (public static) applies a 25× multiplier to all player knockback. In FPS mode the player can counteract this with WASD, but click-to-move has no such input.

**Solution**: Created `ClickToMoveKnockbackSystem extends DamageEventSystem` registered in the `FilterDamage` group. For players in CTM mode, adds a `0.08×` modifier to the `KnockbackComponent` via `kb.addModifier(0.08)`. The engine's 25× multiplier × 0.08 = 2× effective knockback — a noticeable push without launching the player.

The system follows the same pattern as `CombatScalingSystem`: `AllLegacyLivingEntityTypesQuery`, `SystemGroupDependency<>(Order.BEFORE, DamageModule.get().getFilterDamageGroup())`.

---

### Problem 19: Click-to-move targeting through walls (wall occlusion)

**Symptom**: In isometric/top-down camera mode, clicking on a monster that is partially behind a wall does nothing — the player walks to the wall base instead of targeting the entity.

**Root cause**: The client's camera-to-cursor raycast hits the wall block before reaching the entity behind it. The server receives `targetEntity = null` and `targetBlock = wall block`. The existing wall-click logic (`ty > playerFootY`) probes cardinal neighbours for walkable ground, unaware that an entity may be just behind the wall.

**Solution**: Added a server-side spatial query fallback in `onMouseButton`. When a click hits a wall block (target Y > player foot level) and no entity was reported by the client:

1. `findNearbyEntityFallback()` uses `TargetUtil.getAllEntitiesInSphere()` to search for entities within `WALL_ENTITY_SEARCH_RADIUS = 3.0` blocks of the wall block position.
2. Filters: excludes self, requires `BoundingBox` component (only targetable entities).
3. Returns the closest qualifying entity.
4. If found, the entity ref is passed to `updateTarget()` which handles it identically to a direct entity click (attack if in range, walk toward if not).

Only runs on click events (not drag/motion) to avoid per-frame spatial queries. The radius is kept small (3 blocks) to avoid false positives on distant entities.

---

### Problem 20: Missing hurt sound when dropping Hurt animation packets

**Symptom**: After adding the outbound Hurt animation filter (Problem 17), the player no longer hears the damage sound when hit. The hurt sound normally plays via a client-side keyframe SFX event embedded in the Hurt animation.

**Root cause**: The Hurt animation's `.json` keyframes trigger `SFX_Player_Hurt` on the client as an animation event. Dropping the `PlayAnimation` packet prevents the client from ever reaching that keyframe. The engine's `DamageSystems.ApplySoundEffects` sends impact sounds from `Damage.IMPACT_SOUND_EFFECT` / `PLAYER_IMPACT_SOUND_EFFECT` metadata, but weapon damage configs have **no sound events configured** — only `WorldParticles` and `CameraEffect`. So the only damage sound is embedded in the animation itself.

**Solution**: When the filter drops a Hurt animation packet, manually send `PlaySoundEvent2D(hurtSoundIndex, SoundCategory.SFX, 1.0, 1.0)` to the player. Sound index is lazily resolved from `SoundEvent.getAssetMap().getIndex("SFX_Player_Hurt")` on first use (asset map may not be populated during `setup()`). Returns `Integer.MIN_VALUE` on not-found (not `0` — important gotcha).

**Entity ID validation**: `PlayAnimation.entityId` is the network ID of the entity being animated, NOT the packet recipient. Without checking `entityId`, the filter would drop Hurt animations for nearby NPCs/players being displayed to the CTM player. Added `NetworkId` component lookup to verify `networkId.getId() == pa.entityId` — only drop the animation if it targets the player's own entity.

**Refactored** the inline lambda into private methods: `filterHurtAnimation()` (the packet filter) and `sendHurtSound()` (lazy resolution + packet send).

---

### Problem 21: CTM movement during open UI pages

**Goal**: Suppress click-to-move input when a UI page is open (e.g. RespawnPage, Bench).

**Research findings**:

| Page Type | Server visibility | Detection method |
|---|---|---|
| **Custom pages** (RespawnPage, shop UIs) | Full | `Player.getPageManager().getCustomPage() != null` |
| **Server-opened built-in** (Bench, etc.) | Full | Track outbound `SetPage` packets via `PacketAdapters.registerOutbound(PlayerPacketWatcher)` |
| **Map** | Partial | `WorldMapTracker.clientHasWorldMapVisible` (private field, no getter — setter only) |
| **Inventory** | **None** | Opened entirely client-side via Tab keybind. No server notification. `Page.Inventory` is never referenced in server code. |

**Key discoveries**:
- `SetPage` is `ToClientPacket` only — the client cannot send page state to the server.
- `CustomPageEvent.Dismiss` exists for custom pages only — not for built-in page closes.
- Built-in page closes (Escape/Tab) are handled entirely client-side with zero server notification.
- `PageManager` does NOT store the current built-in `Page` — only tracks `customPage`.
- `Page.Inventory` has zero references in the entire server codebase — it exists only in the `Page` enum.
- Keybinds are hardcoded in the client binary, not configurable server-side.

**Implementation**: Hybrid approach:
1. Added `volatile Page activePage` to `PlayerState` (tracked via outbound `SetPage` watcher)
2. Registered `PacketAdapters.registerOutbound(PlayerPacketWatcher)` to intercept `SetPage` packets and update `state.activePage`
3. Added `isPageOpen(state, store, ref)` helper — checks `state.activePage != Page.None` (built-in pages) AND `player.getPageManager().getCustomPage() != null` (custom pages)
4. Both `onMouseButton()` and `onMouseMotion()` call `isPageOpen()` and return early if any page is active

**Limitation**: Client-toggled pages (Inventory, Map) cannot be detected server-side.

---

## Current State (2026-02-28)

### Working
- Top-down and isometric camera views (`/camera topdown`, `/camera iso`)
- Head automatically tracks mouse cursor via `planeNormal = (0, 1, 0)`
- Body follows head with ±1 rad (~57°) AngleRange
- Item animations don't override AngleRange (all set to `{Min: -1, Max: 1}`)
- No Belly/multi-node TargetNodes issues (all normalized to `["Head"]`)
- Camera-relative and head-relative WASD movement modes
- **Block occlusion (xray)**: 2D floor-plane DDA raycast with 9 parallel rays (center + 8 adjacent). Detects wall blocks at `playerY` via Amanatides–Woo traversal, clears entire column (4 blocks high). Only replaces structural blocks from the 47-key whitelist (extracted from dungeon-gen themes). Barrier blocks used as replacement. Anti-flicker via barrier-aware DDA + 500ms tick interval
- **Click-to-move**: left-click sets target, player moves toward it at 8 b/s via `ChangeVelocity` packets (persistent velocity with `VelocityConfig(groundResistance=1.0)`)
- **Drag-to-move**: holding left button while moving mouse continuously updates target (`PlayerMouseMotionEvent` + `sendMouseMotion = true`); velocity direction re-sent only when angle changes >0.1 rad
- **Hold-click continuous movement**: stores XZ cursor offset on click/drag; on each tick while button held, recomputes target as `playerPos + offset`. Validates target is over solid ground (skips void). Gives smooth continuous walking toward cursor with follow camera.
- **Mouse release does NOT stop movement**: player continues to last valid target until arrival
- **Wall detection**: clicks on blocks above player Y probe 4 cardinal neighbours at foot level for walkable ground; also searches nearby entities via spatial index to handle wall occlusion (entity behind wall)
- **Run / RunBackward animation**: dynamically selects `"Run"` or `"RunBackward"` based on dot product of body forward vector `(-sin(yaw), -cos(yaw))` and movement direction — switches within ±90° threshold. Uses `AnimationSlot.Status` with `sendToSelf=true`; `MovementStatesComponent` flags for remote viewers
- **Arrival stop**: zero-velocity `ChangeVelocity` + `stopAnimation(AnimationSlot.Status)` + states reset to idle when within 1 block of target
- **Clean disable**: `disable()` sends stop velocity + stops animation if player was moving
- **Player-only particles**: `ParticleUtil.spawnParticleEffect` with `Collections.singletonList(ref)` — only visible to the clicking player
- **Click-only particles**: particles spawn on left-click press only, not during mouse drag
- No teleportId desync issues (teleport packets removed entirely)
- **DisablePrimary effect**: `CameraCommand` applies the `DisablePrimary.json` EntityEffect in ALL overhead camera modes (topdown/iso), not just click-to-move. Prevents accidental primary ability triggers (hit/swing/shot). Removed on `/camera fps` reset.
- **Entity click targeting**: `getTargetEntity()` from `PlayerMouseButtonEvent`/`PlayerMouseMotionEvent` detects entities under the cursor. If within attack range (3.0 blocks) → stop movement + trigger attack. If out of range → walk toward entity.
- **Entity tracking during movement**: `targetEntity` ref stored in `PlayerState`. Each tick in `tickMovement()`, if an entity target exists, its position is re-queried and the target updated — allows tracking moving entities. If entity becomes invalid (despawned/unloaded), the ref is cleared and the player walks to the last known position.
- **Server-forced attacks**: `triggerAttack()` uses `InteractionManager.queueExecuteChain()` to initiate the player's Primary interaction chain server-side. The chain executes on the next `InteractionManager.tick()` and syncs to the client via `SyncInteractionChains` (negative chainId = server-initiated). Bypasses the client's `DisablePrimary` gate because the chain originates server-side.
- **Ranged weapon detection**: `isRangedWeapon()` checks `RootInteraction.getData().getRawTags().get("Attack")` for `"Ranged"`. Ranged weapons fire immediately from the player's current position (no walk-to-target).
- **Attack cooldown throttle**: 400 ms time-based throttle (`ATTACK_THROTTLE_NS`) prevents wasteful `InteractionContext`/`InteractionChain` allocation on every mouse event. The engine's own `isOnCooldown()` in `executeChain0()` handles real weapon cooldowns (data-driven via `RootInteraction.getCooldown()`, default 350ms for Primary/Secondary from `InteractionTypeUtils.DEFAULT_COOLDOWN`); the throttle just avoids object creation overhead.
- **Hurt animation filter**: `PacketAdapters.registerOutbound()` drops outbound `PlayAnimation` packets with `animationId` starting with "Hurt" on `AnimationSlot.Status` for players in CTM mode. Validates `PlayAnimation.entityId` matches the player's own `NetworkId` — only drops Hurt animations for the player's own entity, not for nearby NPCs/players. Prevents the engine's hurt feedback from cancelling the `Run`/`RunBackward` animation.
- **Hurt sound replacement**: When the Hurt animation is dropped, manually sends `PlaySoundEvent2D` with `SFX_Player_Hurt` to the player. Sound index lazily resolved from `SoundEvent.getAssetMap()` on first intercept (handles `Integer.MIN_VALUE` not-found sentinel). Extracted into private methods `filterHurtAnimation()` and `sendHurtSound()`.
- **Knockback clamping**: `ClickToMoveKnockbackSystem` (extends `DamageEventSystem`, FilterDamage group) adds a `0.08×` modifier to `KnockbackComponent` for CTM players. The engine's `HackKnockbackValues.PLAYER_KNOCKBACK_SCALE = 25×` makes knockback extreme in isometric mode; `25 × 0.08 = 2×` effective knockback gives a gentle push.
- **Page-aware input suppression**: CTM input (both click and drag) is suppressed while a UI page is open. Checks custom pages via `PageManager.getCustomPage()` and server-opened built-in pages via an outbound `SetPage` packet watcher. Limitation: client-toggled pages (Inventory, Map) are invisible to the server.

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
- `SpatialStructure.collect()` for cursor-entity detection — unreliable for precise cursor-aim hit detection; use `getTargetEntity()` from mouse events instead. However, **spatial queries work well as a proximity fallback** (e.g. `TargetUtil.getAllEntitiesInSphere()` for wall occlusion fallback)

### Not Yet Implemented / To Restore
- **Wall face-aware resolution**: Currently probes 4 cardinal neighbours blindly when click hits a wall. Corner walls may resolve to perpendicular ground instead of the face-aligned ground the player intended. Enhancement: use the clicked face normal to prioritize the correct neighbour.
- Directional speed multipliers (forward/backward)
- **Charged attack support**: `queueExecuteChain()` fires instantly even for weapons with charge gates (e.g. sword charged thrust). Held-click during a server-forced chain skips the charge phase. Acceptable for auto-attack but may need special handling for deliberate charged abilities.
- Pathfinding / obstacle avoidance

### Active Constants
```
ARRIVAL_THRESHOLD = 1.0       (stop distance)
MOVE_SPEED = 8.0              (blocks/second)
FOLLOW_YAW_RANGE = ±1 rad    (~57°)
ATTACK_RANGE = 3.0            (entity attack range, blocks — melee only)
ATTACK_THROTTLE_NS = 400ms    (minimum interval between server-forced attacks)
KNOCKBACK_MODIFIER = 0.08     (CTM knockback reduction: 25× engine × 0.08 = 2× effective)
WALL_ENTITY_SEARCH_RADIUS=3.0 (spatial fallback radius for wall-occluded entities)
WALL_CLEAR_HEIGHT = 4         (blocks above playerY to clear per wall column)
MAX_CAST_DISTANCE = 20        (2D DDA max ray length, blocks)
TICK_INTERVAL_MS = 500        (BOM tick rate)
```

### Asset: DisablePrimary.json
Already exists at `src/main/resources/Server/Entity/Effects/Status/DisablePrimary.json`:
```json
{"Infinite": true, "ApplicationEffects": {"AbilityEffects": {"Disabled": ["Primary"]}}}
```


### Future Notes:
- If side strafing like is desired, we can dynamically