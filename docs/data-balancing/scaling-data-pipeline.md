# Scaling Data Pipeline

Status: Current
Last verified: 2026-05-14
Source docs: DUNGEON_SCALING_SYSTEM.md, COMBAT_SCALE_REFACTOR_PLAN.md
Verified against: scripts/scaling/, scripts/scaling/scaling.db, src/main/java/com/duntale/progression/, src/main/resources/Server/Configs/FloorConfig/*.json, src/main/resources/Server/Configs/LootTables/*.json, src/test/java/com/duntale/loot/

## Purpose

Document the offline asset-parsing and scaling-dataset pipeline, the current `scaling.db` snapshot, and how that dataset relates to the live Java runtime.

## Current State

- `scripts/scaling/` is an offline tooling directory. It is useful for asset discovery, balance analysis, and database browsing, but it is not part of the live combat hot path.
- `parse_assets.py` creates or updates the base tables in `scaling.db` by scanning Hytale assets.
- `generate_scaling.py` drops and recreates the scaled tables for levels `1-60` and records the formula parameters it used.
- `simulate_combat.py` reads the scaled tables and prints a breakpoint-level TTK and EHP report.
- `browse_db.py` is a manual inspection tool for the base tables and requires `rich`.
- `pyproject.toml` targets Python `>=3.11` and declares `rich>=14.3.3` as a dependency.
- The repository currently contains a populated `scripts/scaling/scaling.db` snapshot.
- The inspected database contains these tables and row counts:

| Table | Purpose | Rows on 2026-05-14 |
|---|---|---:|
| `monsters_base` | Parsed NPC catalog | 417 |
| `weapons_base` | Parsed weapon catalog | 146 |
| `armor_base` | Parsed armor catalog | 108 |
| `monsters_scaled` | Precomputed NPC level rows | 25020 |
| `weapons_scaled` | Precomputed weapon level rows | 8760 |
| `armor_scaled` | Precomputed armor level rows | 6480 |
| `scaling_config` | Pipeline metadata | 3 |

- The current `scaling_config` keys in the checked-in database are `parse_assets_root`, `parse_assets_counts`, and `scaling_parameters`.
- `parse_assets_root` currently points at `/home/gpmod/lab/duntale/HytaleAssets`.
- `parse_assets_counts` matches the current base-table row counts.
- `scaling_parameters` currently records `monster_hp_k=8.0`, `monster_dmg_k=5.0`, `boss_hp_k=4.0`, `boss_dmg_k=3.0`, `weapon_dmg_k=6.0`, `armor_resist_k=4.0`, `armor_dr_cap=0.65`, `sigmoid_midpoint=30.0`, and `sigmoid_steepness=0.12`.
- The live Java runtime does not query `scaling.db` or any of the generated tables. Runtime combat behavior is owned by `CombatScaling`, `CombatScalingSystem`, `LeveledNpcSpawner`, and the item-asset reads in `CombatScalingSystem`.

## Implementation Map

- `scripts/scaling/parse_assets.py` parses NPC, weapon, and armor source assets into `monsters_base`, `weapons_base`, `armor_base`, and `scaling_config`.
- `scripts/scaling/generate_scaling.py` rebuilds `monsters_scaled`, `weapons_scaled`, `armor_scaled`, creates indexes, and stores `scaling_parameters`.
- `scripts/scaling/simulate_combat.py` reads the scaled tables and reports TTK and survival ratios at levels `1`, `15`, `30`, `45`, and `60`.
- `scripts/scaling/browse_db.py` provides a Rich-based table browser for the parsed catalogs.
- `scripts/scaling/pyproject.toml` and `scripts/scaling/uv.lock` define the Python environment for those tools.
- `scripts/scaling/scaling.db` is the checked-in offline dataset snapshot.
- `src/main/java/com/duntale/progression/CombatScaling.java` is the live gameplay formula owner and is separate from the offline generator.

## Data, Assets, And Config

- `parse_assets.py` defaults its asset root to `../../HytaleAssets` and resolves full NPC inheritance chains under `HytaleAssets/Server/NPC/Roles`.
- The base tables store asset facts:
  - `monsters_base` stores parsed NPC identity, category, tier, HP, damage, speed, attack distance, and extra JSON.
  - `weapons_base` stores weapon family, quality, item level, base damage, and attack-move JSON.
  - `armor_base` stores slot, quality, item level, physical and projectile resistance, health bonus, and special text.
- The scaled tables store per-level offline projections:
  - `monsters_scaled` stores `scaled_hp`, `scaled_damage`, `damage_mult`, `effective_dps`, plus precomputed elite columns.
  - `weapons_scaled` stores per-level weapon damage multipliers and DPS estimates.
  - `armor_scaled` stores per-level resistance multipliers, effective damage reduction, and effective EHP estimates.
- `generate_scaling.py` currently uses a different model for bosses than live runtime does. The script stores `boss_hp_k` and `boss_dmg_k` and precomputes elite columns, while the live Java runtime uses one shared base enemy curve plus runtime variant multipliers.
- `FloorConfig` assets are separate from this pipeline. `src/main/resources/Server/Configs/FloorConfig/*.json` controls dungeon layout, theme variants, and pacing breakpoints, not combat coefficients.
- `LootTables` assets are also separate from this pipeline. `src/main/resources/Server/Configs/LootTables/*.json` is the runtime source of truth for drop chance, entry weights, gear level ranges, and optional NPC-level or floor-level gates.
- The checked-in loot-table assets currently include three `_Elite` variants and no `_Boss` variants. Boss loot therefore falls back to base tables at runtime unless new assets are authored.

## Validation

- Verified the script inventory and Python environment files under `scripts/scaling/`.
- Verified database presence and contents in `scripts/scaling/scaling.db`.
- Because `sqlite3` was unavailable in the shell environment, the database was inspected through Python's built-in `sqlite3` module instead.
- Verified that `parse_assets.py` writes `parse_assets_root` and `parse_assets_counts` into `scaling_config`.
- Verified that `generate_scaling.py` writes `scaling_parameters` into `scaling_config` and recreates the scaled tables with indexes.
- Verified that `simulate_combat.py` consumes `monsters_scaled`, `weapons_scaled`, and `armor_scaled` and reports breakpoint simulations.
- Verified that live Java runtime code under `src/main/java/com/duntale/progression/` contains no `scaling.db` or `ScalingDataCache` reads.
- Verified loot-table runtime gates and behavior through the shipped JSON assets plus `LootTableTest`, `LootTableConfigTest`, `LootRollServiceTest`, `LootTableRegistryTest`, and `NpcLootSystemTest`.

## Known Gaps

- The offline generator and the live Java runtime are no longer formula-identical, so `scaling.db` should be treated as an analysis dataset, not a gameplay oracle.
- No automated consistency check compares `CombatScaling.java` to the stored `scaling_parameters` metadata or to the generated tables.
- The pipeline comments are not fully current. `browse_db.py` depends on `rich`, so the tooling is not purely stdlib-only in practice.

## Related Docs

- [Combat Scaling](../systems/combat-scaling.md)
- [Combat Scaling Refactor](../plans/combat-scaling-refactor.md)