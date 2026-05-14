# Click-To-Move

Status: Current
Last verified: 2026-05-14
Source docs: DEVLOG.md, CLICK_TO_MOVE_RESEARCH.md
Verified against: src/main/java/com/duntale/camera/, src/main/java/com/duntale/command/CameraCommand.java, src/main/java/com/duntale/DuntalePlugin.java, src/main/java/com/duntale/merchant/MerchantService.java, src/main/java/com/duntale/CustomizeCharacterService.java, src/main/resources/Server/Entity/Effects/Status/DisablePrimary.json, src/main/resources/Server/Item/Animations/*.json

## Purpose

Document the current click-to-move camera flow, its runtime entry points, and the limits that are still enforced by the live code and assets.

## Current State

- Manual overhead camera entry lives in `/camera topdown` and `/camera iso`. Click-to-move is only enabled when the command includes `--clickmove`, while `--xray` and `--camrel` remain optional overhead-camera features.
- Dungeon flows also enable click-to-move without a command. `DuntalePlugin` calls `ClickToMoveManager.enableWithCamera(...)` when a player enters a dungeon instance, and re-applies that preset if a dungeon end-portal transition fails and the player stays in the same dungeon world.
- Shared-world and first-person flows restore built-in controls through `disableWithCameraReset(...)`, which clears click-to-move state, removes the `DisablePrimary` effect, and resets the custom camera.
- Overhead camera packets use `MouseInputType.LookAtPlane`, `planeNormal = (0, 1, 0)`, `displayCursor = true`, and `sendMouseMotion = true`. When click-to-move is enabled, `movementForceRotationType` is set to `Custom` so left-click drives movement instead of normal WASD camera steering.
- All overhead camera modes apply the `DisablePrimary` entity effect. Click-to-move mode therefore depends on server-side attack and block-use execution rather than client-side primary input.
- The live yaw-follow range is `[-1, 1]` radians on the player model and on the item animation camera overrides. Held items therefore do not reintroduce the older tighter yaw clamp.
- Ground clicks walk to the center of the resolved block. Clicks on walls probe neighboring walkable tiles at the player's foot level, and clicks that hit a wall above the player can also fall back to a nearby entity search.
- Holding left-click keeps movement active. While the button stays held, the tick system re-applies the stored cursor offset against the moving player position so follow-camera movement continues even if the mouse itself is stationary.
- Releasing left-click stops drag updates but does not cancel the current walk. The player keeps moving until arrival, combat range, interaction range, death cleanup, or an explicit disable path stops the move.
- Entity clicks split into three cases. Companions and intangible entities are redirected to ground movement, merchants open a merchant UI when in range or trigger a walk-to-merchant flow when out of range, and hostile or attackable entities either fire immediately for ranged weapons or walk into melee range before the server queues the attack chain.
- Interactable blocks are detected from `InteractionType.Use` on the clicked `BlockType`. If already in range, the server triggers the use chain immediately. Otherwise the player walks to the block center and executes the use chain on arrival.
- Movement uses direct `ChangeVelocity` packets with a no-decay `VelocityConfig`, so the client keeps moving until the server sends an explicit zero-velocity stop. Local animation uses `AnimationSlot.Status` with `Run` or `RunBackward`, while `MovementStatesComponent` is still updated for remote viewers.
- A hurt-animation packet filter protects the status-slot locomotion animation. When a local player in click-to-move would receive a `Hurt*` status animation for their own entity, the packet is dropped and `SFX_Player_Hurt` is sent manually instead.
- `ClickToMoveKnockbackSystem` reduces knockback for players who are currently in click-to-move mode so overhead combat does not inherit the engine's larger player knockback scale unchanged.
- UI gating is partial by design. Click-to-move input is suppressed for custom pages, tracked server-opened built-in pages, and active merchant sessions. Client-only pages such as Inventory and Map are still not visible to the server.
- Death cleanup pauses movement without fully disabling the mode. `onPlayerDeath(...)` clears transient targets and page state, and `onPlayerRespawn(...)` re-applies `DisablePrimary` and the yaw-follow model override if click-to-move is still enabled.

## Implementation Map

- `src/main/java/com/duntale/command/CameraCommand.java` owns manual command entry, overhead camera defaults, `DisablePrimary` application for overhead modes, optional click-to-move enablement, and first-person reset behavior.
- `src/main/java/com/duntale/camera/ClickToMoveManager.java` owns per-player click-to-move state, mouse event handling, page tracking, auto-camera helpers, death/respawn cleanup hooks, world-transition camera setup, and the hurt-animation packet filter.
- `src/main/java/com/duntale/camera/MovementHelper.java` owns direct velocity packets, `AnimationSlot.Status` run animation control, and remote-viewer movement-state synchronization.
- `src/main/java/com/duntale/camera/TargetResolver.java` resolves walkable destinations, wall clicks, nearby-entity fallback behind walls, and interactable block detection.
- `src/main/java/com/duntale/camera/AttackHandler.java` inspects interaction roots, distinguishes ranged versus melee attack behavior from attack tags, throttles repeated chain allocation, and injects target-block metadata for server-forced `Use` chains.
- `src/main/java/com/duntale/camera/ClickToMoveTickSystem.java` runs after `PlayerSystems.ProcessPlayerInput` and performs per-tick arrival checks, target tracking, direction updates, and stop conditions.
- `src/main/java/com/duntale/camera/PlayerDeathCleanupSystem.java` and `src/main/java/com/duntale/camera/ClickToMoveKnockbackSystem.java` integrate click-to-move with death/respawn and damage handling.
- `src/main/java/com/duntale/DuntalePlugin.java`, `src/main/java/com/duntale/merchant/MerchantService.java`, and `src/main/java/com/duntale/CustomizeCharacterService.java` provide the surrounding state transitions: dungeon auto-enable, shared-world reset, merchant-session gating, and customization-mode disable paths.

## Data, Assets, And Config

- `src/main/resources/Server/Entity/Effects/Status/DisablePrimary.json` is an infinite status effect that disables only the `Primary` ability. That is the asset used by both manual camera commands and the manager's auto-camera helper.
- The item animation overrides under `src/main/resources/Server/Item/Animations/` keep `Camera.Yaw.TargetNodes` on `Head` and `Camera.Yaw.AngleRange` at `Min: -1, Max: 1`. Representative files such as `Default.json` and `Sword.json` match the same range used by the live model override.
- Manual command defaults differ from the auto-dungeon preset. `/camera topdown` defaults to distance `25`. `/camera iso` defaults to angle `se`, distance `20`, and elevation `0`. `ClickToMoveManager.enableWithCamera(...)` uses a fixed dungeon-oriented preset of NW yaw, distance `10`, and elevation `4`.
- Runtime thresholds are code-defined rather than externalized. Arrival uses `1.0` block in XZ, melee and use range use `3.0` blocks, and direction updates are only resent when the heading changes by more than `0.1` radians.
- Move speed and attack throttling are player-specific. `ClickToMoveManager` derives move speed from RPG `SPEED` and attack throttle from RPG `AGILITY` through `RpgStatEffects`, while merchant-window opening keeps a separate fixed `400 ms` anti-spam guard.

## Validation

- Verified manual camera flags, command defaults, and first-person reset behavior against `CameraCommand`.
- Verified click-to-move event handling, tick updates, page gating, death cleanup, hurt filtering, and world-transition behavior against the `camera/` package and `DuntalePlugin` integration points.
- Verified merchant-session gating against `MerchantService.hasOpenSession(...)` and the bench window open path.
- Verified customization-mode disable paths against `CustomizeCharacterService.start(...)` and `complete(...)`.
- Verified `DisablePrimary` asset contents and representative item animation camera overrides under `Server/Item/Animations`.
- No dedicated click-to-move automated tests were found under `src/test`, so the current document is backed by code and asset inspection rather than by a narrow test suite.

## Known Gaps

- The server still cannot observe client-only Inventory or Map toggles, so click-to-move suppression only covers server-visible page state and tracked merchant sessions.
- The click-to-move stack has no dedicated automated tests in this repository.

## Related Docs

- [Click-To-Move History](../research/click-to-move-history.md)