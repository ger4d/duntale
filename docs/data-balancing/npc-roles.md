# NPC Role Loot Balancing

Status: Current
Last verified: 2026-05-14
Source docs: NPC_REGULAR_ROLE_BASELINE.md
Verified against: src/main/java/com/duntale/loot/NpcLootSystem.java, src/main/java/com/duntale/loot/LootRollService.java, src/main/java/com/duntale/loot/LootTableRegistry.java, src/main/java/com/duntale/progression/LeveledNpcSpawner.java, src/main/resources/Server/Configs/LootTables/*.json, src/main/resources/Server/NPC/Roles/**/*.json, src/main/resources/Server/NPC/Groups/**/*.json, scripts/scaling/generate_scaling.py, scripts/scaling/parse_assets.py, scripts/scaling/browse_db.py, src/test/java/com/duntale/loot/LootRollServiceTest.java, src/test/java/com/duntale/loot/LootTableRegistryTest.java, src/test/java/com/duntale/loot/LootTableConfigTest.java, src/test/java/com/duntale/loot/LootTableTest.java

## Purpose

Document the current NPC loot-table surface for dungeon enemies: how role names resolve to loot tables, which regular or base tables are shipped, which variant overlays actually exist, and which legacy EV or proxy-bucket recommendations are historical analysis rather than current runtime behavior.

## Current State

- `NpcLootSystem` replaces engine drops only for NPCs that carry `CombatScalingComponent` and are not marked as companions. For those tracked NPCs, default drops are suppressed even when no custom loot table exists.
- `LeveledNpcSpawner` attaches `CombatScalingComponent` with the runtime variant (`NORMAL`, `ELITE`, or `BOSS`), and `NpcLootSystem` resolves the role name from `NPCPlugin` before rolling loot.
- `LootRollService` resolves variant-specific tables first using `<RoleName>_Elite` or `<RoleName>_Boss`, then falls back to the base role table. No `_Normal` tables are used.
- Gold scaling is a runtime behavior, not an asset field. After the table roll, `Gold_Coin` stack quantities are multiplied by NPC level; non-gold drops are not scaled.
- `LootTableRegistry` is asset-backed. If no programmatic registration exists, it resolves `LootTableConfig` assets from `Server/Configs/LootTables`.
- The shipped loot-table inventory is `41` NPC base tables, `3` `_Elite` overlays, and `4` chest tables. No `_Boss` overlays are checked in, so boss variants currently fall back to the base table.
- Every shipped NPC base table currently includes a `Gold_Coin` entry, uses `Rolls: 1`, and has a `DropChance` between `0.32` and `0.95`.
- Project-local NPC role overrides under `src/main/resources/Server/NPC/Roles` are limited to `Dungeon_Merchant` plus companion roles. The current repo does not ship local role JSON overrides for the enemy role names used by NPC loot tables.
- The `Aggressive` NPC group includes `Trork`, `Goblin`, `Skeleton`, `Void`, `Zombie`, `Vermin`, `Predators`, and `PredatorsBig`, and explicitly excludes the `Companion` group.

## Implementation Map

- `src/main/java/com/duntale/progression/LeveledNpcSpawner.java` spawns leveled NPCs, applies variant-aware combat scaling, and stores the variant on `CombatScalingComponent`.
- `src/main/java/com/duntale/loot/NpcLootSystem.java` intercepts NPC death before engine drop handling, suppresses default drops, resolves attacker Luck and XP grant, and delegates loot rolls to `LootRollService`.
- `src/main/java/com/duntale/loot/LootRollService.java` owns variant lookup, base fallback, and post-roll gold scaling by NPC level.
- `src/main/java/com/duntale/loot/LootTableRegistry.java` resolves `LootTableConfig` assets from `Server/Configs/LootTables` and lets programmatic registrations override asset-backed tables in tests.
- `src/test/java/com/duntale/loot/LootRollServiceTest.java` verifies boss-specific lookup, base fallback, and gold scaling.
- `src/main/resources/Server/NPC/Groups/Aggressive.json`, `Companion.json`, and `Merchant.json` are the checked-in NPC grouping overrides in this repo.

## Data, Assets, And Config

### Shipped Loot Table Inventory

| Category | Count | Current asset pattern | Notes |
|---|---:|---|---|
| NPC base tables | 41 | `<RoleName>.json` | Includes standard enemies plus named standalone roles such as `Scarak_Fighter_Royal_Guard`, `Ghoul`, `Goblin_Duke`, and `Trork_Chieftain`. |
| NPC elite overlays | 3 | `<RoleName>_Elite.json` | Present only for `Outlander_Marauder`, `Risen_Knight`, and `Skeleton_Knight`. |
| NPC boss overlays | 0 | `<RoleName>_Boss.json` | `BOSS` variant loot currently falls back to the base table. |
| Chest tables | 4 | `Chest_*.json` | Owned separately by [Chest Loot Balancing](./chests.md). |

### Current Base-Table Policy

| Property | Current shipped state |
|---|---|
| `Rolls` | `1` on all `41` NPC base tables |
| `DropChance` | Ranges from `0.32` to `0.95` across base tables |
| Gold presence | `41 / 41` base tables include `Gold_Coin` |
| Gold scaling | Runtime multiplies gold quantity by NPC level after the roll |
| Variant overlay coverage | `3` elite overlays, `0` boss overlays |

### Base Tables With NPC-Level Gating

Only four checked-in base tables currently use `MinNpcLevel` gating inside their entries.

| Table | Gated field(s) | Current value(s) |
|---|---|---|
| `Feran_Longtooth` | `MinNpcLevel` | `10` |
| `Goblin_Lobber` | `MinNpcLevel` | `18` |
| `Scarak_Seeker` | `MinNpcLevel` | `14` |
| `Trork_Guard` | `MinNpcLevel` | `10` |

No checked-in NPC base table currently uses `MaxNpcLevel`.

### Elite Overlay Deltas

The only shipped overlay tuning is a stronger `_Elite` asset for three roles. Each elite overlay increases both `DropChance` and the gold range relative to its base table.

| Role | Base `DropChance` | Elite `DropChance` | Base gold | Elite gold |
|---|---:|---:|---:|---:|
| `Outlander_Marauder` | `0.52` | `0.72` | `5-12` | `9-18` |
| `Risen_Knight` | `0.52` | `0.74` | `5-12` | `9-18` |
| `Skeleton_Knight` | `0.50` | `0.70` | `5-11` | `8-16` |

### Historical Regular-Role Baseline And Proxy Buckets

The legacy root document analyzed a live balance dataset and proposed proxy-level EV targets for a regular-role pass. Those numbers are not reproduced by the checked-in repo today, but this doc retains them as historical balancing guidance because no other canonical doc owns that analysis.

| Proxy bucket | Historical suggested EV band | Historical note |
|---|---:|---|
| `<=10` | `50-130` | Early regular enemies |
| `11-18` | `110-250` | Mid-early regular enemies |
| `19-24` | `300-650` | Mid-late regular enemies |
| `25+` | `450-1200` | Late regulars; richer tables were recommended for elite or boss routing |

Historical outlier calls from that legacy analysis were:

- Under-rewarded: `Scarak_Louse`, `Goblin_Miner`, `Scarak_Fighter`, `Feran_Sharptooth`.
- Over-rewarded: `Goblin_Scavenger`, `Goblin_Ogre`, `Skeleton_Knight`, `Outlander_Marauder`, `Risen_Knight`.

Those EV claims came from a live `balance-dataset` export and merchant sell-value pricing model that are not checked into `v3-zsquad`. Treat them as recommendations or historical notes, not current verified runtime budgets.

## Validation

- Verified `NpcLootSystem` suppresses default tracked-NPC drops, resolves the runtime role name from `NPCPlugin`, and delegates loot to `LootRollService`.
- Verified `LootRollService` prefers `_Elite` or `_Boss` tables when present, falls back to the base table when absent, and multiplies only `Gold_Coin` quantities by NPC level.
- Verified `LootRollServiceTest` covers variant lookup, base fallback, and gold scaling behavior.
- Verified `LootTableRegistry` resolves `LootTableConfig` assets from `Server/Configs/LootTables`, and `LootTableRegistryTest` covers asset-backed lookup plus programmatic override precedence.
- Verified every checked-in NPC base loot table includes `Gold_Coin`, all use `Rolls: 1`, and only `Feran_Longtooth`, `Goblin_Lobber`, `Scarak_Seeker`, and `Trork_Guard` currently use `MinNpcLevel` gating.
- Verified `scripts/scaling/generate_scaling.py`, `parse_assets.py`, and `browse_db.py` cover monster, weapon, and armor scaling data, but do not reproduce the legacy loot EV and proxy-bucket analysis from `NPC_REGULAR_ROLE_BASELINE.md`.
- Verified project-local NPC role JSON coverage is limited to `Dungeon_Merchant` and companion roles, while group overrides are `Aggressive`, `Companion`, and `Merchant`.

## Known Gaps

- No `_Boss` loot tables are checked in. `BOSS` variant spawns can exist at runtime, but their loot currently resolves to the base role table.
- The project does not check in enemy role JSON overrides for the base loot-table role names, so this doc can verify loot assets and group wiring but not a local role-by-role enemy definition surface.
- The legacy EV, proxy-level, and HP-tier baseline numbers depend on an external dataset export that is not stored in this repo. They cannot be re-derived from the checked-in scripts alone.
- No automated test snapshots the exact contents of all shipped NPC loot-table JSON assets, so table counts and values are still a manual verification step.

## Related Docs

- [Chest Loot Balancing](./chests.md)
- [Combat Scaling](../systems/combat-scaling.md)
- [Merchant](../systems/merchant.md)