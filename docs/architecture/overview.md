# Architecture Overview

Status: Current
Last verified: 2026-05-14
Source docs: ARCH_OVERVIEW.md
Verified against: build.gradle.kts, src/main/java/com/duntale/DuntalePlugin.java, src/main/java/com/duntale/db/DatabaseProvider.java, src/main/java/com/duntale/**, src/test/java/com/duntale/**, src/main/resources/**

## Purpose

This doc captures the current deployable shape and ownership boundaries for Duntale. It covers bootstrap, package-level responsibilities, and where data, assets, and runtime systems live. Detailed subsystem behavior belongs in feature docs under `docs/systems/`.

## Current State

- Duntale ships as one Shadow JAR plugin targeting Java 25. The build bundles SQLite JDBC, depends on the Hytale Server API at compile time, and consumes sibling `dungeon-gen` and optional `DynamicTooltipsLib` jars.
- `src/main/resources/manifest.json` declares `com.duntale.DuntalePlugin` as the plugin entry point and packages the asset pack with the same artifact.
- `DuntalePlugin` is still both the composition root and an active runtime coordinator. In `setup()` it registers asset stores, constructs repositories and services, registers ECS components and systems, wires commands, and attaches player and world event handlers. In `start()` it initializes asset-backed runtime registries and the dungeon generation orchestrator.
- Player lifecycle flow remains centralized in `DuntalePlugin`: connect preloads RPG and progression state, ready attaches the HUD and routes the player into village, customization, or dungeon flows, and disconnect tears down click-to-move, merchants, scoreboards, and cached session state.
- Persistence is repository-driven. `DatabaseProvider` owns one SQLite connection behind callback-style `read`, `write`, and `transaction` methods guarded by a `ReentrantLock`. RPG, gold, progression, companion, dungeon instance, dungeon membership, and floor config repositories all build on that provider.
- Runtime gameplay uses a hybrid service plus ECS pattern. `DuntalePlugin` registers systems for click-to-move, combat scaling, loot, gold pickup, RPG damage scaling, death cleanup, spawners, and companion lifecycle, while larger domain services coordinate dungeon instances, merchants, companions, respawns, and player entry decisions.

## Implementation Map

| Area | Current ownership | Notes |
|---|---|---|
| Plugin bootstrap and player entry | `DuntalePlugin`, `PlayerEntryService`, `VillageWorldBootstrapService`, `DungeonEntryPage`, `DungeonInstancePortalPage`, `CustomizeCharacter*` | Startup, shared-world bootstrap, portal/page routing, and join/ready/disconnect orchestration live in the root package. |
| Dungeon instance flow | `dungeon/`, `death/`, `portal/`, `volume/` | `DungeonInstanceService` owns instance creation, transitions, recovery, and end-state integration; death and portal services sit alongside it. |
| Player interaction and world presentation | `camera/`, `companion/`, `spawner/` | Click-to-move, camera helpers, occlusion, companion lifecycle, and spawner ECS logic are separated by package but still wired centrally. |
| Economy, progression, and combat data | `economy/`, `rpg/`, `progression/`, `loot/`, `merchant/` | Gold, RPG stats, level progression, asset-backed loot tables, merchant pricing, and merchant sessions are separate service slices. |
| Commands and HUD | `command/`, `ui/` | Admin and debug commands, generation page UI, and the scoreboard HUD live here. |
| Asset-backed config types | `config/asset/`, `dungeon/config/asset/`, `loot/config/asset/` | Asset stores are registered during `setup()` and consumed after asset loading in `start()`. |

## Data, Assets, And Config

- The plugin creates or opens a runtime SQLite database at `duntale.db` under the plugin data directory. A checked-in `scaling.db` resource also ships in `src/main/resources`.
- The packaged asset tree is split between `Common/UI` and `Server/Configs`, `Server/Entity`, `Server/GameplayConfigs`, `Server/Instances`, `Server/Item`, `Server/Models`, and `Server/NPC`.
- `FloorConfigDefaultAsset`, `CustomizeCharacterConfigAsset`, and `LootTableConfig` asset stores are registered during `setup()`. `AssetCatalog.initialize()`, `MerchantPriceRegistry.initialize(assetCatalog)`, and `GenerationOrchestrator` creation are deferred to `start()` because they depend on loaded asset stores.
- Loot tables are currently asset-backed. `LootTableRegistry` resolves production tables from `LootTableConfig` assets, while still allowing direct programmatic registration in narrow tests.
- Floor-generation config remains mixed. `FloorConfigService` merges asset defaults with SQL-stored JSON override maps, and `DungeonGeneratePage` still reads and writes a `generate-config.json` file in the plugin data directory.

## Validation

- Build and deployment shape verified from `build.gradle.kts` and `src/main/resources/manifest.json`.
- Bootstrap, event wiring, and player flow verified from `src/main/java/com/duntale/DuntalePlugin.java`.
- Persistence model verified from `src/main/java/com/duntale/db/DatabaseProvider.java` plus repository packages under `dungeon/`, `economy/`, `progression/`, `rpg/`, and `companion/`.
- Package ownership verified from the current `src/main/java/com/duntale/` tree.
- Existing automated coverage verified from the current `src/test/java/com/duntale/` tree, including tests for dungeon services and repositories, loot, merchant pricing and service flow, companion services, death flow, portal and volume triggers, player entry, and village bootstrap.

## Known Gaps

- `DuntalePlugin` still combines composition-root duties with active gameplay coordination, so package boundaries are clearer than runtime ownership boundaries.
- Leaf classes still reach back through `DuntalePlugin.get()` in pages, commands, components, merchant actions, and click-to-move code. That keeps dependencies implicit.
- `BlockOcclusionManager` still runs its own scheduler and documents that it mutates real world blocks visible to all players. That is a current architectural limitation, not just historical commentary.
- Detailed canonical docs for click-to-move, dungeon instances, merchant flow, economy/RPG, and custom UI pages are still being migrated into `docs/systems/`.

## Related Docs

- [Refactor Roadmap](../plans/refactor-roadmap.md)