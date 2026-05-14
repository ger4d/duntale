# Combat Scaling

Status: Current
Last verified: 2026-05-14
Source docs: DUNGEON_SCALING_SYSTEM.md, COMBAT_SCALE_REFACTOR_PLAN.md
Verified against: src/main/java/com/duntale/progression/, src/main/java/com/duntale/companion/, src/main/java/com/duntale/spawner/, src/main/java/com/duntale/loot/, src/main/java/com/duntale/rpg/, src/main/java/com/duntale/DuntalePlugin.java, src/main/resources/Server/Configs/FloorConfig/*.json, src/main/resources/Server/Configs/LootTables/*.json, src/test/java/com/duntale/loot/

## Purpose

Document the current runtime combat-scaling behavior for dungeon enemies, companions, leveled gear, and the loot and RPG systems that consume scaled levels during live gameplay.

## Current State

- Runtime combat scaling is computed in Java by `CombatScaling`. The live server does not read `scripts/scaling/scaling.db` on the combat path.
- The core level curve is a normalized sigmoid over levels `1-60` with midpoint `30.0` and steepness `0.12`.
- Normal enemy NPC HP uses `baseHp + 8.0 * baseHp * sigmoid(level)`. Normal enemy outgoing damage uses `1.0 + 5.0 * sigmoid(level)`.
- Companion HP and companion outgoing damage use the same runtime constants as normal enemies, but companion scaling is handled on its own spawn and level-up path instead of the enemy spawner path.
- Elite and boss variants are runtime multipliers layered on top of the normal enemy curve, not separate database rows.

| Variant | Level bands | HP multiplier | Damage multiplier |
|---|---|---:|---:|
| Elite | 1-9 / 10-19 / 20-29 / 30-44 / 45-60 | 1.25 / 1.5 / 2.0 / 2.5 / 3.0 | 1.1 / 1.3 / 1.5 / 1.8 / 2.0 |
| Boss | 1-9 / 10-19 / 20-29 / 30-44 / 45-60 | 1.75 / 2.5 / 3.25 / 4.0 / 4.75 | 1.2 / 1.4 / 1.7 / 2.0 / 2.5 |

- `SpawnerTickSystem` is the main dungeon entry point for scaled enemy levels. For each spawn, it reads `floorLevel` and `levelVariance` from the spawner definition, rolls a random level in `floorLevel +/- levelVariance`, and clamps that result to `1-60`.
- `SpawnerTickSystem` maps dungeon-gen `SpawnerVariant.NORMAL`, `ELITE`, and `BOSS` directly to `CombatScaling.NpcVariant` before calling `LeveledNpcSpawner`.
- `LeveledNpcSpawner` reads base HP from the NPC role's `initialMaxHealth`, applies the runtime formula, adds plus or minus 5 percent variance to both HP and damage multiplier, clamps HP to `1-10000`, clamps damage multiplier to at least `1.0`, and writes the HP increase as an `EntityStatMap` max-health modifier.
- Elite NPCs get a visual scale multiplier of `1.2` and a `"[Lv.X *]"` nameplate prefix. Bosses get a `"[Lv.X BOSS]"` prefix. Normal enemies get `"[Lv.X]"`.
- `CombatScalingComponent` is attached to every leveled enemy and companion. It stores the resolved level, damage multiplier, companion flag, and variant for later damage and loot lookups.
- `CompanionSpawner` uses the companion role's own `initialMaxHealth`, not an enemy role lookup, then applies the companion formula plus variance and attaches `CombatScalingComponent(companion=true)`.
- `CompanionService.onPlayerLevelUp(...)` recalculates the companion damage multiplier and reapplies the HP modifier when the owner levels up.
- `CombatScalingSystem` handles base combat scaling before the engine filter-damage group, and `RpgDamageScalingSystem` runs after it.
- Current damage rules are:
  - Enemy NPC to player: enemy damage multiplier, then player armor damage reduction.
  - Enemy NPC to companion: enemy damage multiplier only.
  - Companion to enemy NPC: companion damage multiplier only.
  - Player to enemy NPC: held-weapon multiplier if the held item has weapon-level metadata.
  - Player to companion: no combat-scaling multiplier is applied, but the hit is not canceled.
- Player weapon scaling is metadata-driven. `GearLevelService` stores weapon levels in `duntale_weapon_level` and optional per-item variance in `duntale_weapon_variance`. Unleveled weapons use base damage.
- Player armor scaling is also metadata-driven. `CombatScalingSystem` reads physical resistance directly from the Hytale armor asset's `DamageResistance.Physical[].Amount`, applies `CombatScaling.armorDR(baseResist, armorLevel)` per worn piece, applies optional armor variance metadata, and caps combined armor damage reduction at `0.65`.
- `CombatScaling.weaponMult(level)` uses `1.0 + 6.0 * sigmoid(level)`. `CombatScaling.armorDR(...)` multiplies base physical resistance by `1.0 + 3.0 * sigmoid(level)` before the `0.65` cap.
- RPG combat stats layer on top of base combat scaling. `RpgDamageScalingSystem` multiplies player-to-NPC damage by the Strength multiplier and reduces NPC-to-player damage by the Resistance damage-reduction fraction.
- Strength uses a hyperbolic curve with a max bonus of `1.0` and half-point `25.0`, so it can at most double outgoing player damage. Resistance uses a hyperbolic curve with max damage reduction `0.40` and half-point `30.0`.
- Loot uses scaled NPC level and variant, but companions are explicitly excluded from NPC loot processing.
- `NpcLootSystem` grants `10 * npcLevel` XP to the credited attacker, reads Luck from the attacker's RPG profile, resolves variant-specific loot tables first, and suppresses default engine drops for tracked leveled NPCs.
- Luck affects loot in two ways through `LootTable.roll(npcLevel, luckLevel)`: up to `0.30` bonus drop chance from the hyperbolic Luck curve, and one bonus table roll per `15` Luck.
- `LootRollService` multiplies `Gold_Coin` stack quantity by `npcLevel` after the table roll. Non-gold drops are not quantity-scaled by NPC level.
- Variant loot resolution tries `<RoleName>_Elite` or `<RoleName>_Boss` first, then falls back to the base role table. The current asset set contains three `_Elite` tables and no `_Boss` tables, so boss kills currently fall back to the base table unless a future boss-specific asset is added.

## Implementation Map

- `src/main/java/com/duntale/progression/CombatScaling.java` owns the live combat formulas and variant multipliers.
- `src/main/java/com/duntale/progression/CombatScalingComponent.java` stores resolved scaling data on leveled entities.
- `src/main/java/com/duntale/progression/LeveledNpcSpawner.java` applies enemy HP scaling, enemy damage multipliers, nameplates, and elite visuals at spawn time.
- `src/main/java/com/duntale/companion/CompanionSpawner.java` and `src/main/java/com/duntale/companion/CompanionService.java` own companion spawn-time and level-up scaling.
- `src/main/java/com/duntale/spawner/SpawnerTickSystem.java` turns dungeon spawner definitions into live enemy level and variant choices.
- `src/main/java/com/duntale/progression/CombatScalingSystem.java` applies NPC, companion, weapon, and armor scaling during damage handling.
- `src/main/java/com/duntale/progression/GearLevelService.java` owns weapon and armor level metadata plus variance metadata.
- `src/main/java/com/duntale/rpg/RpgDamageScalingSystem.java` and `src/main/java/com/duntale/rpg/RpgStatEffects.java` layer Strength, Resistance, and Luck curves on top of base scaling.
- `src/main/java/com/duntale/loot/NpcLootSystem.java`, `src/main/java/com/duntale/loot/LootRollService.java`, and `src/main/java/com/duntale/loot/LootTable.java` consume NPC level, variant, and Luck for drop resolution.
- `src/main/java/com/duntale/DuntalePlugin.java` registers the combat-scaling component, the enemy and companion spawners, `CombatScalingSystem`, `NpcLootSystem`, and `RpgDamageScalingSystem`.

## Data, Assets, And Config

- Combat coefficients are defined in Java, not in `FloorConfig` assets or `scaling.db`.
- `src/main/resources/Server/Configs/FloorConfig/*.json` ships dungeon-generation breakpoints for layout, theme, and pacing. `FloorConfigService` uses them for sparse floor defaults, but those assets do not contain combat-scaling coefficients.
- The currently shipped `FloorConfig` breakpoints are `001`, `005`, `010`, `020`, `025`, `030`, `040`, `045`, `050`, `055`, and `060`.
- `src/main/resources/Server/Configs/LootTables/*.json` is the runtime source of truth for drop chance, roll count, leveled gear ranges, and optional NPC-level or floor-level gating.
- Example runtime loot behavior in the shipped assets:
  - NPC tables such as `Zombie.json` define `Rolls`, `DropChance`, and leveled weapon or armor entries with `GearLevelMin` and `GearLevelMax`.
  - Some tables use `MinNpcLevel` or `MaxNpcLevel` to gate individual entries.
  - Chest tables use `MinFloorLevel` and `MaxFloorLevel` instead of NPC level.
- `scripts/scaling/scaling.db` remains in the repository as an offline analysis and catalog artifact. See [Scaling Data Pipeline](../data-balancing/scaling-data-pipeline.md) for its current role.

## Validation

- Verified the live formulas and component flow in `CombatScaling`, `CombatScalingSystem`, `LeveledNpcSpawner`, `CompanionSpawner`, `CompanionService`, `GearLevelService`, and `RpgDamageScalingSystem`.
- Verified spawner level and variant handoff in `SpawnerTickSystem`.
- Verified loot, Luck, XP, and gold-scaling behavior in `NpcLootSystem`, `LootRollService`, `LootTable`, and the shipped loot-table JSON assets.
- Verified plugin registration order in `DuntalePlugin`.
- Automated coverage exists for `LootTable`, `LootTableConfig`, `LootRollService`, and attacker resolution in `NpcLootSystemTest`.
- No dedicated automated tests were found for `CombatScaling`, `CombatScalingSystem`, `LeveledNpcSpawner`, `CompanionSpawner`, `CompanionService` scaling updates, `SpawnerTickSystem`, or `RpgDamageScalingSystem`.

## Known Gaps

- Player attacks against companions are still allowed at base damage because `CombatScalingSystem` skips scaling for companion targets but does not cancel the hit.
- The offline scaling database is no longer authoritative for live gameplay behavior, so balance conclusions drawn from `scaling.db` can drift from what the Java runtime actually does.
- Dedicated automated tests for combat-formula outputs, damage ordering, and dungeon enemy spawn scaling are still missing.

## Related Docs

- [Scaling Data Pipeline](../data-balancing/scaling-data-pipeline.md)
- [Combat Scaling Refactor](../plans/combat-scaling-refactor.md)
- [Economy RPG](./economy-rpg.md)