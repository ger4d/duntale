# Dungeon Instances

Status: Current
Last verified: 2026-05-14
Source docs: DUNGEON_INSTANCE_PLAN.md, Validation.md
Verified against: src/main/java/com/duntale/dungeon/, src/main/java/com/duntale/command/DungeonCommand.java, src/main/java/com/duntale/command/PartyCommand.java, src/main/java/com/duntale/portal/DungeonEndPortalService.java, src/main/java/com/duntale/volume/DungeonInstancePortalTriggerService.java, src/main/java/com/duntale/death/, src/main/java/com/duntale/PlayerEntryService.java, src/main/resources/Server/GameplayConfigs/Dungeon.json, src/main/resources/Server/Configs/FloorConfig/*.json, src/test/java/com/duntale/dungeon/, src/test/java/com/duntale/portal/, src/test/java/com/duntale/volume/, src/test/java/com/duntale/death/, src/test/java/com/duntale/PlayerEntryServiceTest.java

## Purpose

Document the current dungeon-instance lifecycle, player entry and re-entry rules, floor transition behavior, cleanup rules, and the runtime integrations that depend on persisted instance state.

## Current State

- `DungeonInstanceService` creates one persistent world per run. The first floor uses `dungeon-{instanceId}` and later floors use `dungeon-{instanceId}-f{floor}`.
- A player may belong to only one non-`ENDED` dungeon instance at a time. The service enforces that rule transactionally before it persists a new instance row and new membership rows.
- Instance metadata is persisted as a `DungeonInstance` record with `instanceId`, `worldName`, `floorLevel`, `floorY`, `entrancePosition`, `exitPosition`, `state`, `theme`, `seed`, and `createdAt`. Membership is stored separately and is the source of truth for player-to-instance lookups.
- Party state is only a pre-run roster builder. `PartyService` is in-memory, capped at 6 players including the owner, and is wired into disconnect cleanup through `DuntalePlugin.onPlayerDisconnect(...)`. Once a run starts, the persisted instance roster is authoritative.
- Player-facing party commands exist now. `/party create`, `/party invite`, `/party kick`, `/party leave`, `/party disband`, and `/party list` are registered in `DuntalePlugin` and backed by `PartyCommand` plus `PartyService`.
- Player-facing dungeon start also exists now. `/dungeon start` calls `DungeonInstanceService.createInstanceForPlayer(playerId, 1)` and uses the initiating player's current party roster when present.
- Starting a run reserves the roster in `CREATING`, creates a new persistent void world, runs dungeon generation, finalizes that world with dungeon content, teleports the roster, and only then marks the instance `ACTIVE`.
- New instance worlds use `VoidWorldGenProvider`, `GameplayConfig` `Dungeon`, `GameMode.Adventure`, `savingPlayers=true`, `deleteOnUniverseStart=false`, and `deleteOnRemove=false` until cleanup is armed.
- The runtime floor origin is fixed at `(0, 20, 0)`. Generated entrance and exit positions are translated from generator-relative coordinates and then persisted as the authoritative join and transition positions.
- World finalization adds dungeon-owned content directly into the live instance world. `DungeonInstanceService.LiveRuntimeAdapter.finalizeWorld(...)` creates spawners, merchants, chest loot, and a dynamic floor-exit portal after generation succeeds.
- The shared-world authored dungeon portal is still separate from per-instance exit portals. `DungeonInstancePortalTriggerService` matches the authored `dungeon_instance_portal` TriggerVolume in the shared world and opens `DungeonInstancePortalPage`.
- The shared-world portal page has two modes. Players without an active run see an `Enter` action that starts floor 1. Players with an active run see `Continue` and `New Dungeon`; `New Dungeon` force-ends the current run before it starts a fresh floor-1 run.
- Join and re-entry flow is split between `PlayerEntryService`, `DungeonEntryPage`, and `DuntalePlugin`. Players without a stored companion preference route to customization first. Players with a stored preference and an active instance route to the dungeon-entry page. Players with a stored preference and no active instance route to the village.
- `Continue` only resumes `ACTIVE` instances. If the player has a `CREATING` or `TRANSITIONING` instance, the entry page keeps them in the shared-world flow and shows a retry-later message. If the instance row exists but its world is unavailable, the player is routed to the village instead of being left stuck.
- Entering a dungeon instance enables click-to-move with the dungeon camera preset. Routing to the village resets click-to-move and restores built-in controls before teleporting the player.
- Floor transitions are player-facing through the dynamic exit portal and operator-facing through `/dungeon transition <instanceId>`. The plugin also exposes `/dungeon tpout` so a player can teleport near the active floor exit for manual verification.
- `DungeonEndPortalService` creates deterministic dynamic exit portals with IDs in the form `dungeon_end_portal_{instanceId}_f{floor}`. They are centered on the persisted exit block, target players only, and use a 2-second cooldown.
- A floor transition only starts when the triggering player still belongs to the matching `ACTIVE` instance, the floor level encoded in the volume ID matches the current floor, and the trigger event comes from the instance's current world. The plugin also blocks duplicate in-flight transitions per instance.
- Transitioning a floor claims `TRANSITIONING`, chooses the next floor's theme from the resolved `theme.variants` list, creates the next world, generates the next floor, teleports the full roster to the new entrance, arms the old world for removal when empty, and then re-persists the instance as `ACTIVE` on the new floor.
- If transition setup fails before roster transfer, the old floor stays authoritative: the service cleans up the failed new world and reverts the instance to `ACTIVE` on the old world. If failure happens after transfer but before final `ACTIVE` persistence, the service keeps a runtime override so `Continue` still routes to the new floor until persistence recovers or the server restarts.
- Ending a run is stateful. `endInstance(...)` only ends `ACTIVE` instances or retries cleanup for already ended ones. `/dungeon end` uses `forceEndInstance(...)`, which can also end `CREATING` instances and live `TRANSITIONING` instances.
- Cleanup is world-based, not entity-sweep-based. Ending an instance first marks it `ENDED`, then evacuates online roster members who are still in the tracked source world, then arms each cleanup world with `deleteOnRemove=true` and `REMOVE_WHEN_EMPTY` so the engine removes it after the last player leaves.
- Force-ending a live transition tracks both the old and new worlds. Cleanup and removal arming run against both worlds so stuck transitions do not strand an in-between floor.
- Startup recovery is implemented. `DungeonInstanceService.loadOnStartup()` restores `ACTIVE` instances, ends interrupted `CREATING` rows after best-effort world cleanup, reverts interrupted `TRANSITIONING` rows back to `ACTIVE`, and does not regenerate already persisted dungeon blocks or entities.
- After worlds load, `DuntalePlugin` backfills dynamic end portals for loaded `ACTIVE` instances so restart-resumed floors regain their exit portal volumes and visuals.
- Dungeon death handling is live. `DungeonDeathScreenSystem` runs before the built-in death screen and only replaces it when the player dies inside the matching world for an `ACTIVE` dungeon instance.
- `DungeonDeathPage` offers three actions: paid respawn on the current floor at `floorLevel * 500` gold, paid restart on the previous floor at `floorLevel * 300` gold when `floorLevel > 1`, and free village retreat.
- Current-floor respawn keeps the same instance. Lower-floor restart force-ends the current instance and creates a new active instance for the captured roster at `floorLevel - 1`. Village retreat force-ends the active run after respawn settlement and routes the player to the shared world.
- `Dungeon.json` is the gameplay profile for dungeon worlds. It disables block breaking, gathering, and placement, keeps combat enabled, sets item-loss percentages to `0.0`, and uses world-spawn-point respawn when the custom dungeon death flow does not intercept.

## Implementation Map

- `src/main/java/com/duntale/dungeon/DungeonInstanceService.java` owns instance creation, roster validation, transitions, end and force-end flows, startup recovery, and continue-route lookups.
- `src/main/java/com/duntale/dungeon/DungeonInstanceRepository.java` and `src/main/java/com/duntale/dungeon/DungeonMembershipRepository.java` own persisted instance and roster storage plus state-claim helpers.
- `src/main/java/com/duntale/dungeon/PartyService.java` owns transient party state that seeds initial dungeon rosters.
- `src/main/java/com/duntale/command/DungeonCommand.java` exposes instance inspection and operator controls, plus player-usable `/dungeon start` and `/dungeon tpout` helpers.
- `src/main/java/com/duntale/command/PartyCommand.java` exposes the player-facing party lifecycle.
- `src/main/java/com/duntale/PlayerEntryService.java`, `src/main/java/com/duntale/DungeonEntryPage.java`, and `src/main/java/com/duntale/DungeonInstancePortalPage.java` own join-time routing and shared-world portal UI.
- `src/main/java/com/duntale/portal/DungeonEndPortalService.java` owns dynamic end-portal volume IDs, parsing, visuals, and registration helpers.
- `src/main/java/com/duntale/volume/DungeonInstancePortalTriggerService.java` owns authored village portal matching.
- `src/main/java/com/duntale/death/DungeonRespawnService.java`, `src/main/java/com/duntale/death/DungeonDeathScreenSystem.java`, and `src/main/java/com/duntale/death/DungeonDeathPage.java` own dungeon death interception, pricing, and restart or retreat actions.
- `src/main/java/com/duntale/DuntalePlugin.java` wires command registration, disconnect cleanup, entry routing, TriggerVolume handlers, startup recovery, and post-restart end-portal backfill.

## Data, Assets, And Config

- `src/main/resources/Server/GameplayConfigs/Dungeon.json` is applied to instance worlds through `WorldConfig.setGameplayConfig("Dungeon")`.
- `src/main/resources/Server/Configs/FloorConfig/*.json` provides shipped floor-config breakpoints. The current asset set includes `001.json`, `005.json`, `010.json`, `020.json`, `025.json`, `030.json`, `040.json`, `045.json`, `050.json`, `055.json`, and `060.json`.
- `FloorConfigDefaultAsset` reads those numeric filenames as floor breakpoints under `Configs/FloorConfig`. `FloorConfigService` layers code defaults, the highest shipped asset breakpoint at or below the requested floor, and then local SQL overrides inside that asset segment.
- `theme.variants` is resolved per floor through `FloorConfigService.getThemeVariantsForFloor(...)`. Instance creation and floor transition pick one available runtime theme from that list and fall back to `crypt` when none of the resolved variants are currently loadable.
- `DungeonCommand` exposes `/dungeon floorconfig <floor>` and `/dungeon floorconfig list` for inspecting and editing the SQL override layer without changing the shipped asset files.

## Validation

- Automated coverage exists for persistence, lifecycle transitions, continue-route behavior, party behavior, trigger parsing, floor-config layering, and dungeon death services.
- Manual checks are still needed for full in-engine page flow, TriggerVolume wiring, teleport timing, and camera handoff.
- Use [Dungeon Instance Validation](../validation/dungeon-instances.md) for the operator checklist and exact test entry points.

## Known Gaps

- Party state is intentionally transient. Pre-run parties do not survive restart even though active dungeon instances and their worlds do.
- If a persisted active instance points at a world that is no longer loaded, `Continue` routes the player to the village rather than attempting automatic world repair or regeneration.
- There are no dedicated automated tests for the exact custom-page text and button wiring in `DungeonEntryPage`, `DungeonInstancePortalPage`, `DungeonDeathPage`, or for the chat output formatting in `DungeonCommand` and `PartyCommand`.

## Related Docs

- [Dungeon Instance Validation](../validation/dungeon-instances.md)