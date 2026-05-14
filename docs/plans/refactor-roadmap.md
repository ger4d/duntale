# Refactor Roadmap

Status: Active Plan
Last verified: 2026-05-14
Source docs: REFACTOR_V3_PLAN.md
Verified against: build.gradle.kts, src/main/java/com/duntale/DuntalePlugin.java, src/main/java/com/duntale/**, src/test/java/com/duntale/**

## Purpose

This roadmap tracks behavior-preserving refactor work for the current structural hotspots in Duntale. It owns future decomposition priorities, not current subsystem behavior.

## Current Priority

- Establish test seams around bootstrap and player flow, dungeon transitions, merchant transactions, and click-to-move targeting before moving code.
- Thin `DuntalePlugin` into explicit registrars and coordinators, then remove `DuntalePlugin.get()` from leaf packages.
- After those seams exist, split the two largest runtime coordinators: `DungeonInstanceService` and `ClickToMoveManager`.

## Scope

- In scope: plugin and bootstrap decomposition, dependency wiring, dungeon orchestration boundaries, click-to-move and camera boundaries, config persistence cleanup, scoreboard coordination, and service-layer cache and persistence cleanup.
- Out of scope: net-new gameplay features, balance changes, archive or migration work, and subsystem behavior documentation.
- Already landed and not tracked here: production loot table externalization. Current loot tables are loaded from `LootTableConfig` assets through `LootTableRegistry`.

## Tasks

| ID | Priority | Status | Current evidence | Task |
|---|---|---|---|---|
| RR-001 | P0 | Ready | Service-level tests exist across dungeon, loot, merchant, companion, death, portal, and entry flows, but no `DuntalePlugin` or click-to-move tests were found in `src/test/java/com/duntale/`. | Add characterization tests around plugin player lifecycle, shared-world startup, dungeon floor transitions, merchant transactions, and click-to-move target resolution. |
| RR-002 | P0 | Ready | `DuntalePlugin` still owns `setup()`, `start()`, `shutdown()`, player connect/ready/disconnect handlers, portal routing, respawn handlers, and direct scoreboard updates. | Split `DuntalePlugin` into a thin plugin shell plus focused bootstrap and runtime coordinators for system registration, player lifecycle, shared-world startup, page routing, and scoreboard updates. |
| RR-003 | P0 | Blocked by RR-002 | Current source still contains 20 `DuntalePlugin.get()` call sites across pages, commands, components, merchant actions, and click-to-move code. | Remove `DuntalePlugin.get()` from leaf packages by passing explicit collaborators or narrow registries. |
| RR-004 | P0 | Blocked by RR-001 to RR-003 | `DungeonInstanceService` remains the central coordinator for instance creation, transitions, recovery, and end-state integration. | Decompose `DungeonInstanceService` into smaller collaborators for persistence and state access, roster validation, world transition orchestration, recovery and startup loading, and portal or end-of-run integration. |
| RR-005 | P0 | Blocked by RR-001 to RR-003 | `ClickToMoveManager` still opens merchants and mixes targeting, interaction, movement, and camera-mode behavior. | Split `ClickToMoveManager` into input routing, movement execution, interaction resolution, and camera-mode helpers. Keep merchant targeting and page-aware input rules out of the main movement loop. |
| RR-006 | P1 | Blocked by RR-005 | `BlockOcclusionManager` documents that it mutates real world blocks visible to all players and currently runs its own scheduler. | Replace the current shared-world occlusion approach with a per-player presentation boundary or a narrower runtime opt-in. |
| RR-007 | P1 | Ready | `DungeonGeneratePage` still reads and writes `generate-config.json`, and `FloorConfigService` still owns string-keyed JSON override maps. | Extract config file read and write logic plus generation orchestration out of `DungeonGeneratePage`, and move `FloorConfigService` field ownership toward typed config codecs or descriptors. |
| RR-008 | P1 | Ready | Level, XP, gold, and stat listeners all call `DuntalePlugin.updateScoreboard(...)` directly. | Introduce a scoreboard update coordinator so HUD refreshes are deduplicated and no longer fan out through the plugin entry class. |
| RR-009 | P1 | Ready | `RpgService`, `ProgressionService`, `CompanionService`, and `MerchantService` still hold per-player in-memory maps, and `GoldService` and `RpgService` still contain direct `SQLException` handling paths. | Tighten persistence and cache semantics across core player services, with explicit disconnect cleanup, predictable failure handling, and smaller responsibilities where state and persistence are currently mixed. |

## Dependencies

- RR-001 should land before RR-004 and RR-005 so behavior changes are measured against current flows.
- RR-002 should land before RR-003 because removing singleton access is easier after the plugin shell is thinned and dependencies have named homes.
- RR-003 should precede most leaf-package splits so extracted classes do not keep inheriting hidden plugin dependencies.
- RR-005 should precede RR-006 because the occlusion redesign depends on a cleaner click-to-move and camera boundary.
- RR-007 can proceed in parallel with RR-002 after ownership of config IO and generation orchestration is explicit.
- RR-008 and RR-009 can land incrementally, but both become easier once RR-002 stops routing every update through `DuntalePlugin`.

## Risks

- Bootstrap and player flow are highly centralized today, so partial extraction can leave duplicate lifecycle logic unless RR-002 owns the cutover boundary.
- Dungeon flow spans DB state, world loading, teleports, portal triggers, and death and respawn flows. Splits without RR-001 risk behavior drift.
- Click-to-move interacts with pages, merchants, combat, and camera presentation. Untangling it without keeping world-thread assumptions explicit can introduce input or world-state regressions.
- Floor and generation config currently span assets, SQLite, and per-plugin JSON files. Refactors here need a single source-of-truth decision before code movement.

## Related Docs

- [Architecture Overview](../architecture/overview.md)