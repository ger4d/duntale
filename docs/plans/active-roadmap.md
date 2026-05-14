# Active Roadmap

Status: Active Plan
Last verified: 2026-05-14
Source docs: tasks.md
Verified against: python3 tasks/cli.py list and list --state output; src/main/java/com/duntale/dungeon/DungeonInstanceService.java; src/main/java/com/duntale/dungeon/FloorConfigService.java; src/main/java/com/duntale/command/FloorConfigPage.java; src/main/java/com/duntale/PlayerEntryService.java; src/main/java/com/duntale/portal/DungeonEndPortalService.java; src/main/java/com/duntale/loot/AttackerResolver.java; src/main/java/com/duntale/loot/LootTableRegistry.java; src/main/resources/Server/Configs/FloorConfig/*.json; src/main/resources/Server/Configs/LootTables/*.json; dungeon, portal, death, player-entry, loot, and floor-config tests

## Purpose

This doc tracks the active non-refactor product backlog for Duntale.

It is grounded in the live task database first, then narrowed by current code, assets, configs, and tests so already-shipped dungeon and floor-config work does not stay on the roadmap as if it were still pending.

## Current Priority

The current queue has eight open tasks in the task CLI: two `in-progress`, two `testing`, and four `planned`.

The immediate priority is to close the four tasks already nearest to done:

- `dungeon-end-portals`
- `configurable-loot-tables`
- `contextual-entry-flow`
- `companion-kill-credit`

Planned follow-on work should build on the shipped dungeon-instance and floor-config baseline instead of reopening those systems from scratch.

## Scope

This roadmap owns active non-refactor work only. Refactor sequencing lives in `docs/plans/refactor-roadmap.md`.

Historical note: the legacy `tasks.md` dungeon batches are no longer active roadmap items. The generator contract, persisted instance model, party gate, single-floor creation, startup reload, entry routing, floor transition, instance end flow, player-facing commands, game-mode and companion-entry fixes, and floor configuration system all have current repository evidence in the dungeon services, the floor-config editor and assets, and the related service tests.

## Tasks

| Task | State | Current evidence | Active focus |
|---|---|---|---|
| `dungeon-end-portals` | `in-progress` | `DungeonEndPortalService` already builds deterministic dynamic end-portal volumes and visuals, and portal tests cover id parsing and volume construction. | Finish live dungeon-floor integration, transition cleanup, and runtime validation so spawned exit portals behave correctly in active runs. |
| `configurable-loot-tables` | `in-progress` | Asset-backed loot tables already exist under `src/main/resources/Server/Configs/LootTables/`, `LootTableRegistry` resolves `LootTableConfig` assets at runtime, and registry tests cover asset-backed resolution. | Finish any remaining migration gaps and validate that NPC and chest loot behavior is driven by config assets instead of ad hoc registrations. |
| `contextual-entry-flow` | `testing` | `PlayerEntryService` already routes players between customization, dungeon entry, and village based on companion preference and active instance state, with tests covering fail-closed behavior. | Finish live validation around login, re-entry, missing shared-world fallback, and stale-instance edge cases. |
| `companion-kill-credit` | `testing` | `AttackerResolver` already credits companion kills to the owner UUID, and `NpcLootSystem` uses that attribution for Luck and XP lookup. | Confirm end-to-end XP and loot attribution in runtime so companion kills award the same player-facing rewards as direct kills. |
| `floorconfig-asset-pack-overrides` | `planned` | `FloorConfigService` already layers shipped floor-config JSON assets with mutable SQL overrides, and `FloorConfigServiceTest` covers the segmented inheritance model. | Decide whether mutable overrides should move fully into asset-pack JSON, and preserve the current breakpoint reset behavior if that migration happens. |
| `floor-config-dungeon-preview` | `planned` | `FloorConfigPage` supports edit, save, reset, and effective-value display, while `DungeonGeneratePage` already has disposable preview-world creation and preview-world cleanup. | Reuse the existing preview-world pipeline from dungeon generation inside floor-config editing instead of building a second preview path. |
| `disable-companion-damage` | `planned` | Current companion systems cover death protection and trap immunity, but no dedicated player-or-companion friendly-fire block was verified in the companion runtime slice. | Add a narrow damage rule that prevents players and companions from damaging companion NPCs without affecting enemy damage or recovery behavior. |
| `portal-transition-model-shake` | `planned` | Dynamic portal infrastructure now exists, but no specific mitigation for portal-transition model shake was verified in the current portal and transition surfaces. | Reproduce and isolate the visual instability before changing portal transfer behavior or model setup. |

## Dependencies

- Task CLI state in `tasks/tasks.db` is the live queue for open work.
- Dungeon follow-on work depends on the shipped instance baseline in `DungeonInstanceService`, plus the existing portal, death, and player-entry services and tests.
- Floor-config follow-on work depends on the current layered override model in `FloorConfigService`, shipped floor JSON under `Server/Configs/FloorConfig/`, and the existing `DungeonGeneratePage` preview flow.
- Loot and companion work depends on asset-backed loot-table loading, companion kill attribution, and companion combat protections already present in the runtime.

## Risks

- Task state and implementation reality have drifted apart in the legacy notes. Several systems listed as batches in `tasks.md` are already shipped, so future work should be scoped as integration, migration, or validation only.
- Floor-config override migration is easy to get wrong because the shipped behavior is segmented: asset breakpoints reset the baseline, while SQL overrides only rebase inside the active asset segment.
- Portal, entry, death, and transition work all touch shared world-transfer paths. Regressions there can strand players, leave stale worlds behind, or break resume behavior.
- Companion combat changes can accidentally suppress valid enemy damage or break XP and loot attribution if damage filtering and attacker resolution drift apart.

## Related Docs

- `docs/systems/dungeon-instances.md`
- `docs/validation/dungeon-instances.md`
- `docs/systems/custom-ui-pages.md`
- `docs/plans/refactor-roadmap.md`