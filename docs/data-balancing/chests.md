# Chest Loot Balancing

Status: Current
Last verified: 2026-05-14
Source docs: CHEST_BALANCE_BASELINE.md, CHEST_TIER_RECOMMENDATION.md
Verified against: src/main/java/com/duntale/loot/ChestLootService.java, src/main/java/com/duntale/loot/LootTable.java, src/main/java/com/duntale/loot/LootTableRegistry.java, src/main/java/com/duntale/loot/config/asset/LootTableConfig.java, src/main/java/com/duntale/loot/config/asset/LootEntryConfig.java, src/main/java/com/duntale/dungeon/DungeonInstanceService.java, src/main/resources/Server/Configs/LootTables/Chest_*.json, src/main/resources/Server/Configs/FloorConfig/*.json, src/test/java/com/duntale/loot/, ../dungeon-gen/src/main/java/com/duntale/dungeongen/config/asset/DungeonThemeConfig.java, ../dungeon-gen/src/main/java/com/duntale/dungeongen/generator/props/PropPlacer.java, ../dungeon-gen/assets/Server/Configs/DungeonGen/Themes/*.json

## Purpose

Document the current chest loot balance surface for dungeon runs: which tier tables exist, how floor bands gate rewards, which item pools are currently shipped, how generation exposes each tier, and which older balance claims are not backed by checked-in assets.

## Current State

- Chest tier selection is upstream from the loot tables. Dungeon generation records a `ChestTier` on each placed loot container, and `ChestLootService` only maps that tier to one of four global tables: `Chest_Regular`, `Chest_Golden`, `Chest_Epic`, or `Chest_Legendary`.
- Chest contents are not theme-specific after placement. A `LEGENDARY` chest uses the same `Chest_Legendary.json` pool regardless of which theme created it.
- Current chest tables are split into four floor bands: `1-15`, `16-30`, `31-45`, and `46+`.
- Current numeric floor-band budgets are encoded as `Gold_Coin` stack ranges inside each table. There is no checked-in runtime total-reward budget based on merchant sell value or a similar derived price curve.
- Gold and gear share the same weighted pool. Chests roll without replacement, so the same entry cannot appear twice in a single chest.
- Runtime chest roll count is owned by `ChestLootService`, not by the table assets: `Regular` rolls `3-5`, `Golden` rolls `2-4`, `Epic` rolls `1-3`, and `Legendary` rolls exactly `1` item.
- The table asset fields `Rolls` and `DropChance` do not currently drive chest outcomes. `ChestLootService` overrides roll count, and the chest-specific `LootTable.roll(context, requestedRolls, false)` path hardcodes an effective drop chance of `1.0`.
- All checked-in floor presets share the same active theme list: `crypt`, `arcane`, `hive`, `temple_dark`, and `volcanic`. `Mine` and `Mushroom` chest rules still exist in `dungeon-gen`, but they are not part of the current `theme.variants` arrays in `v3-zsquad` floor configs.

## Implementation Map

- `../dungeon-gen/assets/Server/Configs/DungeonGen/Themes/*.json` assigns an optional `ChestTier` to prop rules.
- `../dungeon-gen/src/main/java/com/duntale/dungeongen/config/asset/DungeonThemeConfig.java` parses the string `ChestTier` field into the `ChestTier` enum.
- `../dungeon-gen/src/main/java/com/duntale/dungeongen/generator/props/PropPlacer.java` records a `ChestDefinition` only when a prop rule has a non-null chest tier.
- `src/main/java/com/duntale/dungeon/DungeonInstanceService.java` passes those generated chest definitions to `ChestLootService.fillChests(...)`.
- `src/main/java/com/duntale/loot/ChestLootService.java` resolves the tier-specific table ID, injects floor level into the loot context, and applies the tier-specific runtime roll count.
- `src/main/java/com/duntale/loot/LootTableRegistry.java` resolves `Chest_*` configs from `Server/Configs/LootTables` through `LootTableConfig` assets.
- `src/main/java/com/duntale/loot/config/asset/LootEntryConfig.java` turns `MinFloorLevel` and `MaxFloorLevel` JSON values into runtime floor-level conditions.

## Data, Assets, And Config

### Runtime Tier Policy

| Tier | Runtime table | Runtime rolls per chest | Selection mode | Current floor bands |
|---|---|---:|---|---|
| Regular | `Chest_Regular` | `3-5` | No replacement | `1-15`, `16-30`, `31-45`, `46+` |
| Golden | `Chest_Golden` | `2-4` | No replacement | `1-15`, `16-30`, `31-45`, `46+` |
| Epic | `Chest_Epic` | `1-3` | No replacement | `1-15`, `16-30`, `31-45`, `46+` |
| Legendary | `Chest_Legendary` | `1` | No replacement | `1-15`, `16-30`, `31-45`, `46+` |

### Asset Fields That Do And Do Not Matter At Runtime

| Asset field | Current shipped value | Chest runtime effect |
|---|---|---|
| `Rolls` | `1` in all four `Chest_*` files | Not used by chest rolls. `ChestLootService` supplies its own requested roll count. |
| `DropChance` | `1.0` in all four `Chest_*` files | Not used by chest rolls. The chest-specific roll overload passes an effective drop chance of `1.0`. |
| `MinFloorLevel` / `MaxFloorLevel` | Varies per entry | Used. These gates decide which entries are eligible for a given floor. |
| `Weight` | Varies per entry | Used. Eligible entries are selected by weighted random roll. |
| `GearLevelMin` / `GearLevelMax` | Varies per entry | Used for leveled gear entries. |

### Floor-Band Gold Budgets

These are the current gold stack ranges encoded in the shipped assets. They are the only verified numeric chest budgets in the live data.

| Floor band | Regular | Golden | Epic | Legendary |
|---|---:|---:|---:|---:|
| `1-15` | `110-170` | `180-260` | `220-320` | `350-500` |
| `16-30` | `240-360` | `340-500` | `400-600` | `700-1000` |
| `31-45` | `460-640` | `600-820` | `750-1100` | `1400-2000` |
| `46+` | `700-980` | `900-1250` | `1200-1800` | `2200-3200` |

### Candidate Pools By Floor Band

#### Floors 1-15

- Regular: Gold `110-170`; gear `Weapon_Axe_Crude`, `Weapon_Longsword_Crude`, `Weapon_Shield_Copper`, `Weapon_Shortbow_Copper`, `Weapon_Spear_Copper`, `Armor_Leather_Soft_Head`, `Armor_Cloth_Wool_Legs`, `Armor_Cloth_Wool_Chest`, `Armor_Leather_Soft_Chest`, `Armor_Leather_Soft_Legs`, `Armor_Copper_Chest`, `Armor_Copper_Head`.
- Golden: Gold `180-260`; gear `Weapon_Daggers_Copper`, `Weapon_Longsword_Copper`, `Weapon_Sword_Copper`, `Weapon_Battleaxe_Copper`, `Weapon_Mace_Copper`, `Weapon_Shortbow_Copper`, `Armor_Leather_Soft_Chest`, `Armor_Cloth_Wool_Chest`, `Armor_Leather_Soft_Legs`, `Armor_Cloth_Wool_Legs`, `Armor_Copper_Chest`.
- Epic: Gold `220-320`; gear `Weapon_Mace_Copper`, `Weapon_Battleaxe_Copper`, `Armor_Copper_Chest`, `Armor_Leather_Soft_Chest`, `Armor_Cloth_Wool_Chest`, `Weapon_Shortbow_Copper`, `Armor_Copper_Head`.
- Legendary: Gold `350-500`; gear `Weapon_Mace_Copper`, `Weapon_Battleaxe_Copper`, `Weapon_Longsword_Copper`, `Armor_Copper_Chest`, `Armor_Leather_Soft_Chest`, `Armor_Cloth_Wool_Chest`, `Weapon_Shortbow_Copper`, `Armor_Copper_Head`.

#### Floors 16-30

- Regular: Gold `240-360`; gear `Weapon_Spear_Bronze`, `Weapon_Shield_Rusty`, `Weapon_Shield_Scrap`, `Weapon_Spear_Iron`, `Weapon_Spear_Bone`, `Armor_Bronze_Head`, `Armor_Iron_Head`, `Armor_Steel_Head`, `Armor_Iron_Legs`, `Armor_Bronze_Legs`.
- Golden: Gold `340-500`; gear `Weapon_Longsword_Stone_Trork`, `Weapon_Daggers_Bronze`, `Weapon_Daggers_Iron`, `Weapon_Sword_Bronze`, `Weapon_Axe_Bone`, `Weapon_Shortbow_Bronze`, `Armor_Bronze_Chest`, `Armor_Iron_Chest`, `Armor_Bronze_Legs`, `Armor_Iron_Legs`, `Armor_Steel_Legs`.
- Epic: Gold `400-600`; gear `Weapon_Sword_Bone`, `Weapon_Crossbow_Iron`, `Weapon_Mace_Iron`, `Weapon_Mace_Stone_Trork`, `Armor_Bronze_Ornate_Chest`, `Armor_Iron_Chest`, `Armor_Steel_Chest`.
- Legendary: Gold `700-1000`; gear `Weapon_Crossbow_Iron`, `Weapon_Mace_Iron`, `Weapon_Mace_Stone_Trork`, `Weapon_Sword_Bone`, `Armor_Bronze_Ornate_Chest`, `Armor_Steel_Chest`.

#### Floors 31-45

- Regular: Gold `460-640`; gear `Weapon_Shield_Cobalt`, `Weapon_Staff_Cobalt`, `Weapon_Staff_Doomed`, `Weapon_Shield_Adamantite`, `Armor_Steel_Ancient_Head`, `Armor_Cobalt_Hands`, `Armor_Cobalt_Head`, `Armor_Thorium_Hands`, `Armor_Adamantite_Hands`, `Armor_Cloth_Silk_Legs`.
- Golden: Gold `600-820`; gear `Weapon_Shortbow_Cobalt`, `Weapon_Shortbow_Thorium`, `Weapon_Spear_Double_Incandescent`, `Weapon_Shield_Adamantite`, `Weapon_Shortbow_Adamantite`, `Armor_Leather_Heavy_Legs`, `Armor_Steel_Ancient_Legs`, `Armor_Adamantite_Head`, `Armor_Cobalt_Legs`, `Armor_Thorium_Legs`.
- Epic: Gold `750-1100`; gear `Weapon_Sword_Cobalt`, `Weapon_Sword_Doomed`, `Weapon_Axe_Thorium`, `Weapon_Battleaxe_Cobalt`, `Weapon_Longsword_Spectral`, `Armor_Cloth_Silk_Chest`, `Armor_Steel_Ancient_Chest`, `Armor_Adamantite_Legs`.
- Legendary: Gold `1400-2000`; gear `Weapon_Battleaxe_Scythe_Void`, `Weapon_Longsword_Flame`, `Weapon_Longsword_Spectral`, `Armor_Cobalt_Chest`, `Armor_Thorium_Chest`, `Armor_Adamantite_Chest`.

#### Floors 46+

- Regular: Gold `700-980`; gear `Weapon_Spear_Mithril`, `Weapon_Shield_Mithril`, `Weapon_Spear_Onyxium`, `Weapon_Shield_Onyxium`, `Weapon_Staff_Onyxium`, `Armor_Onyxium_Head`, `Armor_Mithril_Hands`, `Armor_Mithril_Head`, `Armor_Onyxium_Legs`, `Armor_Onyxium_Chest`.
- Golden: Gold `900-1250`; gear `Weapon_Spear_Mithril`, `Weapon_Shortbow_Mithril`, `Weapon_Shortbow_Onyxium`, `Weapon_Shield_Mithril`, `Armor_Mithril_Head`, `Armor_Onyxium_Legs`, `Armor_Onyxium_Chest`, `Armor_Mithril_Chest`.
- Epic: Gold `1200-1800`; gear `Weapon_Daggers_Mithril`, `Weapon_Sword_Mithril`, `Weapon_Battleaxe_Mithril`, `Weapon_Battleaxe_Onyxium`, `Armor_Onyxium_Chest`, `Armor_Mithril_Legs`.
- Legendary: Gold `2200-3200`; gear `Weapon_Mace_Mithril`, `Weapon_Axe_Mithril`, `Weapon_Longsword_Mithril`, `Weapon_Club_Onyxium`, `Weapon_Axe_Onyxium`, `Armor_Mithril_Chest`, `Armor_Mithril_Legs`.

### Exposure In Generation

Chest tier exposure is owned by `dungeon-gen` prop rules, not by the chest loot tables. Current `v3-zsquad` floor configs activate only the theme set `crypt`, `arcane`, `hive`, `temple_dark`, and `volcanic`.

| Theme | Active in current floor configs | Loot-room exposure | Boss-room exposure | Ambient or ungated exposure |
|---|---|---|---|---|
| `Crypt` | Yes | `Regular` chest at `0.8` in `LOOT` rooms | `Epic` chest at `0.9` in `BOSS` rooms | None verified |
| `Arcane` | Yes | `Golden` chest at `0.7` in `LOOT` rooms | `Epic` chest at `0.9` in `BOSS` rooms | None verified |
| `Hive` | Yes | `Regular` chest at `0.7` in `LOOT` rooms | `Epic` chest at `0.9` in `BOSS` rooms | None verified |
| `Temple_Dark` | Yes | `Golden` chest at `0.8` in `LOOT` rooms | `Legendary` chest at `0.9` in `BOSS` rooms | None verified |
| `Volcanic` | Yes | `Golden` chest at `0.7` in `LOOT` rooms | `Epic` chest at `0.9` in `BOSS` rooms | `Regular` crate at `0.15` with no room-type gate in the theme asset |
| `Mine` | No | `Regular` chest at `0.6` in `LOOT` rooms | `Epic` chest at `0.8` in `BOSS` rooms | `Regular` crate at `0.15` in the upstream theme asset |
| `Mushroom` | No | `Regular` chest at `0.7` in `LOOT` rooms | `Epic` chest at `0.9` in `BOSS` rooms | None verified |

### Scripts And External Data

- No checked-in file under `scripts/scaling/` currently generates `Chest_*` loot tables, validates chest gold budgets, or derives chest candidate pools from merchant sell value.
- The current chest pools are therefore hand-authored asset data, not generated output from the offline scaling pipeline.

## Validation

- Verified `ChestLootServiceTest` covers chest rolls using floor context, no-replacement selection, and empty results when a chest table is missing.
- Verified `LootTableTest` covers no-replacement roll clamping to the number of eligible entries.
- Verified `LootTableConfigTest` covers floor-level bound conversion, gear modifier conversion, and asset validation errors.
- Verified `LootTableRegistryTest` covers asset-backed lookup and confirms loot tables are resolved from `LootTableConfig` assets rather than hardcoded registrations.
- Verified the shipped `Chest_*.json` assets directly for floor bands, gold stack ranges, and candidate item IDs.
- Verified all checked-in `FloorConfig` files share the same `theme.variants` array and that only `001.json` is marked `layout.bossRoom = false` among the stored presets.
- Verified `scripts/scaling/*.py` contains no current chest-table generation or merchant-value budget logic.

## Known Gaps

- The legacy root docs described merchant-sell-value budgets, boss-only outlier whitelists, and API-driven candidate generation. None of those policies are currently backed by checked-in `v3-zsquad` runtime assets or scripts, so they should be treated as recommendations, not current fact.
- Chest balance is global by tier. There is no theme-specific chest loot table, no per-theme weight override, and no floor-config-controlled tier distribution inside `v3-zsquad`.
- Because the chest path ignores asset `Rolls` and `DropChance`, authors can change those fields in `Chest_*.json` without affecting runtime chest behavior.
- The `46+` band is open-ended. Floors above `45` all reuse the same late-game pool until new floor gates are authored.
- `Mine` and `Mushroom` still have upstream chest exposure rules, but they are inactive under the current `theme.variants` arrays shipped with `v3-zsquad`.
- No automated test snapshots the exact `Chest_*` JSON contents or cross-checks active theme exposure against the current tier pools.

## Related Docs

- [Dungeon Instances](../systems/dungeon-instances.md)
- [Merchant](../systems/merchant.md)
- [Scaling Data Pipeline](./scaling-data-pipeline.md)