# Economy, RPG, And Progression

Status: Current
Last verified: 2026-05-14
Source docs: PLAN-economy-rpg.md, RPG_DUNGEON.md
Verified against: src/main/java/com/duntale/economy/, src/main/java/com/duntale/rpg/, src/main/java/com/duntale/progression/, src/main/java/com/duntale/loot/NpcLootSystem.java, src/main/java/com/duntale/loot/LootTable.java, src/main/java/com/duntale/camera/ClickToMoveManager.java, src/main/java/com/duntale/death/DungeonRespawnService.java, src/main/java/com/duntale/DuntalePlugin.java, src/main/resources/Server/Item/Items/Currency/Gold_Coin.json, src/main/resources/Common/UI/Custom/Pages/StatAssignment/StatAssignmentPage.ui, src/test/java/com/duntale/economy/InventoryGoldConversionSystemTest.java

## Purpose

Document the current gold wallet flow, RPG stat storage and formulas, stat-point assignment flow, XP and level progression, combat-scaling formulas, and the gameplay hooks that currently consume those systems.

## Current State

### Gold Economy

- Gold is persisted in `duntale.db` under the `player_gold` table and is capped at `999,999,999` by `GoldService`.
- Players currently gain wallet gold in two live ways:
  - `GoldPickupSystem` converts `Gold_Coin` item entities into balance when the nearest player in the same world is within `2.5` blocks on the XZ plane.
  - `InventoryGoldConversionSystem` converts `Gold_Coin` stacks when they move from an external container into the player's inventory. Moves within the player's own combined inventory are ignored.
- `NpcLootSystem` suppresses default NPC item drops for tracked dungeon NPCs, rolls custom loot, and tags spawned `Gold_Coin` entities with both `PreventPickup` and `CurrencyDrop` so the engine does not route them through normal item pickup.
- Gold is currently spent through merchant purchases and dungeon death options handled by `DungeonRespawnService`. Admins can also mutate balances through `/gold`.
- `GoldService.transfer(...)` is implemented with a database transaction, but no verified gameplay call sites use it today.

### RPG Stats And Stat Points

- Player stats are persisted in `player_stats`, cached per player by `RpgService`, and clamped to `0-100`.
- The seven shipped stats are `SPEED`, `STRENGTH`, `LUCK`, `STAMINA`, `AGILITY`, `RESISTANCE`, and `VITALITY`.
- Unassigned stat points are stored in the same table using the synthetic stat key `UNASSIGNED_POINTS`.
- `ProgressionService` level-up callbacks grant `3` unassigned points per gained level through `RpgService.POINTS_PER_LEVEL`.
- Players can spend points with `/assignstats`, which opens `StatAssignmentPage`. The page shows current stat values and remaining points, and each `+` button calls `RpgService.assignPoint(...)` then refreshes the UI.
- `/stat` remains the admin path for reading or setting stats directly.

### Progression And Combat Scaling

- Player progression is persisted in `player_progression` with `level`, `xp`, and `season` columns. `ProgressionService` uses a per-player lock map so concurrent XP grants for the same player are serialized.
- `NpcLootSystem` grants XP on tracked NPC kills using `10 * npcLevel`.
- `ProgressionRepository.calculateLevel(totalXP)` returns the highest defined level whose threshold is less than or equal to total XP.
- `ProgressionRepository.getXPForLevel(level)` returns the exact threshold when present and linearly interpolates between the nearest lower and higher thresholds when the exact level is missing.
- `LeveledNpcSpawner` applies dungeon-level progression to enemy NPCs by scaling HP and damage, then attaching a `CombatScalingComponent(level, damageMult, isCompanion=false, variant)`.
- `CombatScalingSystem` applies dungeon-level weapon and armor scaling from item metadata during damage resolution, before `RpgDamageScalingSystem` applies player Strength and Resistance.
- The scoreboard HUD is fed from the live services on player ready and on gold, XP, level-up, or stat changes. It shows gold, level, total XP, next-level XP target, and the full stat profile.

### Verified Stat Formulas And Hooks

| Stat | Stored range | Formula | Current runtime hook |
| --- | --- | --- | --- |
| Speed | 0-100 | `8.0 + 4.0 * L / (L + 25)` | `ClickToMoveManager` movement velocity |
| Strength | 0-100 | `1.0 + 1.0 * L / (L + 25)` | `RpgDamageScalingSystem` on player -> NPC damage |
| Luck | 0-100 | Drop bonus: `0.30 * L / (L + 20)`; bonus rolls: `floor(L / 15)` | `NpcLootSystem -> LootRollService -> LootTable.roll(npcLevel, luckLevel)` |
| Stamina | 0-100 | `L * 5.0` | No verified gameplay consumer; displayed in HUD and stat UI |
| Agility | 0-100 | `max(140000000, 400000000 * (1 - 0.65 * L / (L + 25)))` ns | `ClickToMoveManager` attack throttle |
| Resistance | 0-100 | `0.40 * L / (L + 30)` | `RpgDamageScalingSystem` on NPC -> player damage |
| Vitality | 0-100 | `L * 10.0` | No verified gameplay consumer; displayed in HUD and stat UI |

### Verified Progression And Combat-Scaling Formulas

| System | Formula or rule | Current runtime hook |
| --- | --- | --- |
| XP on kill | `10 * npcLevel` | `NpcLootSystem` |
| Level lookup | Highest `levels.xp_required <= totalXP` | `ProgressionService.grantXP(...)` |
| NPC HP scaling | `baseHp + 8 * baseHp * sigmoid(level)`, then variant multiplier, then `+/-5%` variance | `LeveledNpcSpawner` |
| NPC damage scaling | `1 + 5 * sigmoid(level)`, then variant multiplier, then `+/-5%` variance | `LeveledNpcSpawner` and `CombatScalingSystem` |
| Weapon multiplier | `1 + 6 * sigmoid(level)`, plus optional item variance | `CombatScalingSystem` |
| Armor DR per piece | `baseResist * max(1 + 3 * sigmoid(level), 1)`, capped at `0.65` per piece | `CombatScalingSystem` |
| Combined armor DR | Sum of leveled piece DR values, capped at `0.65` total | `CombatScalingSystem` |

`sigmoid(level)` is the normalized dungeon-level curve in `CombatScaling`, clamped to levels `1-60` with midpoint `30` and steepness `0.12`.

Variant multipliers are applied after the base NPC scaling curve:

| Variant | HP multiplier by level band | Damage multiplier by level band |
| --- | --- | --- |
| Elite | `<10: 1.25`, `10-19: 1.5`, `20-29: 2.0`, `30-44: 2.5`, `45+: 3.0` | `<10: 1.1`, `10-19: 1.3`, `20-29: 1.5`, `30-44: 1.8`, `45+: 2.0` |
| Boss | `<10: 1.75`, `10-19: 2.5`, `20-29: 3.25`, `30-44: 4.0`, `45+: 4.75` | `<10: 1.2`, `10-19: 1.4`, `20-29: 1.7`, `30-44: 2.0`, `45+: 2.5` |

## Implementation Map

- `src/main/java/com/duntale/economy/GoldRepository.java` owns `player_gold` persistence.
- `src/main/java/com/duntale/economy/GoldService.java` owns balance reads, add or remove operations, cap enforcement, change callbacks, and transactional transfers.
- `src/main/java/com/duntale/economy/GoldPickupSystem.java` owns world-drop to wallet conversion.
- `src/main/java/com/duntale/economy/InventoryGoldConversionSystem.java` owns external-container to wallet conversion.
- `src/main/java/com/duntale/economy/GoldCommand.java` exposes `/gold check|give|take|set` for online players.
- `src/main/java/com/duntale/rpg/RpgRepository.java` owns stat and unassigned-point persistence in `player_stats`.
- `src/main/java/com/duntale/rpg/RpgService.java` owns stat caching, stat mutation, stat point grant and spend flow, and join or leave cache management.
- `src/main/java/com/duntale/rpg/RpgStatEffects.java` owns all player stat formulas.
- `src/main/java/com/duntale/rpg/RpgDamageScalingSystem.java` applies Strength and Resistance after dungeon combat scaling.
- `src/main/java/com/duntale/rpg/StatAssignCommand.java` and `src/main/java/com/duntale/rpg/StatAssignmentPage.java` own player stat-point spending UI.
- `src/main/java/com/duntale/progression/ProgressionRepository.java` owns `levels` and `player_progression` persistence.
- `src/main/java/com/duntale/progression/ProgressionService.java` owns XP grant, level-up fanout, and progression queries.
- `src/main/java/com/duntale/progression/CombatScaling.java`, `src/main/java/com/duntale/progression/CombatScalingSystem.java`, `src/main/java/com/duntale/progression/GearLevelService.java`, and `src/main/java/com/duntale/progression/LeveledNpcSpawner.java` own dungeon enemy and gear progression.
- `src/main/java/com/duntale/loot/NpcLootSystem.java` is the current kill-time integration point for gold drops, Luck-aware loot, and XP grants.
- `src/main/java/com/duntale/camera/ClickToMoveManager.java` is the current movement and attack-speed integration point for Speed and Agility.
- `src/main/java/com/duntale/death/DungeonRespawnService.java` is the current gold-sink integration point for paid dungeon death choices.
- `src/main/java/com/duntale/DuntalePlugin.java` wires repository initialization, ECS systems, commands, HUD updates, stat-point rewards, and join or leave hooks.

## Data, Assets, And Config

- The plugin initializes `duntale.db` in the plugin data directory and creates these economy or progression tables during setup:
  - `player_gold(uuid, balance)`
  - `player_stats(uuid, stat, value)`
  - `levels(level, xp_required)`
  - `player_progression(player_uuid, level, xp, season, updated_at)`
- `src/main/resources/Server/Item/Items/Currency/Gold_Coin.json` defines the wallet-convertible gold item. Current shipped values are `MaxStack: 9999`, `Tags.Type: ["Currency"]`, and `DropOnDeath: false`.
- `src/main/resources/Common/UI/Custom/Pages/StatAssignment/StatAssignmentPage.ui` is the shipped custom UI asset used by `/assignstats`.
- `GearLevelService` uses these item metadata keys for progression-aware combat scaling:
  - `duntale_weapon_level`
  - `duntale_armor_level`
  - `duntale_weapon_variance`
  - `duntale_armor_variance`
- `DuntalePlugin.start()` initializes `AssetCatalog` after assets load, then initializes merchant pricing from that catalog. Economy, stat, and progression persistence are initialized earlier during plugin setup.

## Validation

- Automated coverage exists for the external-container gold conversion logic in `src/test/java/com/duntale/economy/InventoryGoldConversionSystemTest.java`.
- The verified runtime hooks for movement, loot luck, combat scaling, progression rewards, and HUD updates are in the live source paths listed in the metadata block above.
- No dedicated `src/test/java/com/duntale/rpg/` or `src/test/java/com/duntale/progression/` test suites are currently present.

## Known Gaps

- The repository does not ship seed data for the `levels` table. Without an existing runtime database or external seeding, XP will still accumulate but players will remain at level `1` because no higher thresholds are defined.
- `STAMINA` and `VITALITY` formulas exist, and both stats are persisted and displayed, but no verified gameplay consumer currently applies those bonuses to live entity stats.
- `GoldService.transfer(...)` is implemented and transactional, but no verified gameplay path currently calls it.
- There is no dedicated automated coverage for `GoldPickupSystem`, `RpgDamageScalingSystem`, `StatAssignmentPage`, or the live scoreboard update flow.

## Related Docs

- [click-to-move.md](../systems/click-to-move.md)
- [dungeon-instances.md](../systems/dungeon-instances.md)