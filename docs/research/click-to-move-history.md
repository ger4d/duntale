# Click-To-Move History

Status: Historical
Last verified: 2026-05-14
Source docs: DEVLOG.md, CLICK_TO_MOVE_RESEARCH.md
Verified against: src/main/java/com/duntale/camera/, src/main/java/com/duntale/command/CameraCommand.java, src/main/java/com/duntale/DuntalePlugin.java, src/main/java/com/duntale/merchant/MerchantService.java, src/main/resources/Server/Entity/Effects/Status/DisablePrimary.json, src/main/resources/Server/Item/Animations/*.json

## Purpose

Preserve the chronology behind the click-to-move camera stack, including the experiments that were abandoned and the findings that still explain why the current implementation looks the way it does. Current behavior lives in [../systems/click-to-move.md](../systems/click-to-move.md).

## Historical Context

- The legacy research report from 2025-02-24 focused on why early click-to-move attempts slid the player without correct local animation. Its most durable conclusion was that player movement is client-authoritative, so server-side state writes alone do not drive local player locomotion.
- Early iterations tried to solve facing and combat alignment with `ClientTeleport` rotation updates. The devlog records repeated problems with teleport ID coordination, camera snap, and combat direction when the client look orientation did not actually face the target.
- Later iterations replaced teleport-driven facing with a custom overhead camera that uses `MouseInputType.LookAtPlane` and `planeNormal = (0, 1, 0)`, plus matching yaw limits on the player model and item animation assets.
- Once the camera/facing approach stabilized, the remaining work shifted to interaction routing: drag-to-move, ranged-versus-melee targeting, walk-to-interact for blocks, merchant sessions, page suppression, hurt feedback, knockback, and death cleanup.

## Findings

- Client look orientation matters more than server body rotation for this feature set. The current camera design survives because it lets the client generate cursor-driven look orientation natively instead of trying to backfill it with teleport packets.
- Yaw clamping had to be solved in two places. Updating only the player model was not enough, because held-item animation camera settings could still override the model camera settings. The durable fix was to align both the live model override and the item animation overrides to the same `[-1, 1]` yaw range on `Head`.
- Server-forced movement needs a persistent transport primitive. The legacy research correctly identified direct `ChangeVelocity` with a no-decay `VelocityConfig` as the viable path, while raw velocity-component writes and teleport-based locomotion were dead ends for player-controlled entities.
- UI gating is inherently partial. Custom pages are directly visible, built-in pages require outbound `SetPage` tracking, and client-only Inventory or Map toggles still provide no reliable server signal. Merchant handling also needed its own session check because the bench page can look stale once close events stay client-side.
- Server-forced block interactions require more context than a plain use-chain call. The current code still reflects the historical finding that `Interaction.TARGET_BLOCK` and `Interaction.TARGET_BLOCK_RAW` must be populated manually, and filler-backed structures must be resolved to a base block before queueing the `Use` chain.
- Wall clicks were not just a pathing problem. Historical debugging showed that a wall can hide an entity from the client raycast entirely, which is why the live code keeps a nearby-entity fallback search in addition to cardinal ground probing.

## Still Relevant

- The current implementation still depends on the historical shift away from teleport-based facing and toward `LookAtPlane` camera-driven look orientation.
- The no-decay velocity approach from the early research remains part of the live code, even though the animation-slot recommendation from that report did not survive unchanged.
- The partial UI-visibility findings remain current: the repo now handles more server-visible cases, but it still cannot detect every client-owned page state.
- The later devlog additions around hurt animation filtering, knockback reduction, and death/respawn cleanup remain active because click-to-move still owns `AnimationSlot.Status` and still changes player handling while overhead mode is enabled.

## Superseded Or Retired

- The 2025 recommendation to force local locomotion through `AnimationSlot.Movement` is not the live repo behavior. `MovementHelper` now uses `AnimationSlot.Status` for `Run` and `RunBackward`, and the hurt-animation packet filter exists specifically to keep that status-slot animation from being displaced.
- Legacy notes that described `ClickToMoveTickSystem` as a stub are obsolete. The system now runs after `PlayerSystems.ProcessPlayerInput` and delegates the active per-tick movement logic to `ClickToMoveManager.tickMovement(...)`.
- Older notes that described the yaw-follow range as fully widened or `+/-180 degrees` are obsolete as current behavior. The live code and the item animation assets both use `[-1, 1]` radians. A leftover log string in `ClickToMoveManager` still says `+/-180`, but the actual runtime data does not.
- The `ClientTeleport` body-rotation approach is retired. Current click-to-move movement and facing do not rely on queued teleports for normal locomotion.
- Treat any fixed `8 blocks/sec` statement from the legacy notes as historical shorthand, not canonical behavior. The live manager computes move speed from RPG `SPEED`, even though `MovementHelper` still keeps `8.0` as a helper baseline constant.
- Treat any claim that page tracking alone solves all UI suppression as retired. Current code needs a combination of custom-page inspection, outbound `SetPage` tracking, and merchant-session tracking, and even that still cannot see Inventory or Map.

## Related Docs

- [Click-To-Move](../systems/click-to-move.md)