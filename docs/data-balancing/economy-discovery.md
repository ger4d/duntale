# Economy Discovery Report

Status: Discovery snapshot
Generated: 2026-06-12 by `scripts/scaling/discover_economy.py`
Inputs: refreshed `scaling.db` (multi-source: builtin, duntale, wans, zets), dungeon-gen theme SpawnPools, LootTables assets, RpgConfig.json, FloorConfig assets.
Formula replication sources: `CombatScaling.java`, `MerchantPriceRegistry.java`, `LootTable.java`, `LootRollService.java`, `RpgStatEffects.java`, `CatalogGenerator.java`.

## 1. Item Catalog Inventory

| Category | Source | Items |
|---|---|---|
| armor | builtin | 105 |
| weapon | builtin | 203 |
| weapon | duntale | 20 |
| weapon | wans | 16 |
| weapon | zets | 8 |

Weapon damage resolution (offline parser): chain=49, inline=175, none=23. `inline` is runtime-visible; `chain`/`none` likely fall back to tier/quality pricing at runtime (needs a one-off runtime spot check).

### Items with unknown pricing quality (priced as Common, coeff 1.0)

| Item | Category | Source | Quality | ItemLevel |
|---|---|---|---|---|
| WanMine_Ashthorn_Dagger | weapon | wans | Relic | 55 |
| WanMine_Chromatic_Cleaver_Dagger | weapon | wans | Relic | 55 |
| WanMine_Frostburn_Dagger | weapon | wans | Relic | 45 |
| WanMine_Gaias_Wrath_Sword | weapon | wans | Relic | 60 |
| WanMine_God_Slayer_Battleaxe | weapon | wans | Relic | 60 |
| WanMine_Heartroot_Dagger | weapon | wans | Relic | 45 |
| WanMine_Helioram_Mace | weapon | wans | Relic | 65 |
| WanMine_Lethal_Leftovers_Sword | weapon | wans | Relic | 35 |
| WanMine_Maelstrom_Mace | weapon | wans | Relic | 65 |
| WanMine_Mjollnir_Mace | weapon | wans | Relic | 55 |
| WanMine_Nightshade_Dagger | weapon | wans | Relic | 55 |
| WanMine_Quasar_Cosmic_Sword | weapon | wans | Relic | 75 |
| WanMine_Soulblight_Longsword | weapon | wans | Relic | 60 |
| WanMine_Soulblight_Longsword_V1 | weapon | wans | Relic | 60 |
| WanMine_Void_Requiem_Scythe | weapon | wans | Relic | 65 |
| Wanmine_Weapon_Eclipsebound_Dagger | weapon | wans | Relic | 45 |
| Mystic_Aetherflux_Blade | weapon | zets | Celestial | 45 |
| Mystic_Dominus_Maul | weapon | zets | Abyssal | 48 |
| Mystic_Dreadreap | weapon | zets | Abyssal | 50 |
| Mystic_Impressio | weapon | zets | Mythical | 42 |
| Mystic_Noctivagus | weapon | zets | Mythical | 40 |
| Mystic_Vampiric_Daggers | weapon | zets | Mythical | 35 |
| Mystic_Whirlwinds | weapon | zets | Mythical | 40 |

### Items likely priced via fallback at runtime (no runtime-visible stats)

| Item | Category | Source | Quality | ItemLevel | Fallback buy@50 |
|---|---|---|---|---|---|
| Halloween_Broomstick | weapon | builtin | Common | 40 | 1749 |
| Tool_Sickle_Copper | weapon | builtin | Common | 10 | 251 |
| Tool_Sickle_Crude | weapon | builtin | Common | 5 | 95 |
| Tool_Sickle_Iron | weapon | builtin | Uncommon | 20 | 880 |
| Tool_Sickle_Steel_Rusty | weapon | builtin | Common | 5 | 95 |
| Weapon_Bomb | weapon | builtin | Common | 20 | 663 |
| Weapon_Bomb_Continuous | weapon | builtin | Uncommon | 20 | 880 |
| Weapon_Bomb_Fire | weapon | builtin | Uncommon | 20 | 880 |
| Weapon_Bomb_Large_Fire | weapon | builtin | Uncommon | 20 | 880 |
| Weapon_Bomb_Popberry | weapon | builtin | Uncommon | 0 | 25 |
| Weapon_Bomb_Potion_Poison | weapon | builtin | Uncommon | 20 | 880 |
| Weapon_Bomb_Stun | weapon | builtin | Uncommon | 20 | 880 |
| Weapon_Claws_Tribal | weapon | builtin | Common | 60 | 3086 |
| Weapon_Kunai | weapon | builtin | Common | 40 | 1749 |
| Weapon_Shield_Adamantite | weapon | builtin | Rare | 40 | 3557 |
| Weapon_Shield_Cobalt | weapon | builtin | Rare | 35 | 2951 |
| Weapon_Shield_Copper | weapon | builtin | Uncommon | 10 | 357 |
| Weapon_Shield_Doomed | weapon | builtin | Rare | 30 | 2378 |
| Weapon_Shield_Iron | weapon | builtin | Uncommon | 20 | 943 |
| Weapon_Shield_Mithril | weapon | builtin | Epic | 50 | 7898 |
| Weapon_Shield_Onyxium | weapon | builtin | Common | 50 | 2560 |
| Weapon_Shield_Orbis_Incandescent | weapon | builtin | Common | 40 | 1873 |
| Weapon_Shield_Orbis_Knight | weapon | builtin | Common | 40 | 1873 |
| Weapon_Shield_Praetorian | weapon | builtin | Common | 40 | 1873 |
| Weapon_Shield_Rusty | weapon | builtin | Common | 15 | 474 |
| Weapon_Shield_Scrap | weapon | builtin | Uncommon | 15 | 630 |
| Weapon_Shield_Scrap_Spiked | weapon | builtin | Common | 15 | 474 |
| Weapon_Shield_Thorium | weapon | builtin | Rare | 30 | 2378 |
| Weapon_Shield_Wood | weapon | builtin | Common | 40 | 1873 |
| Weapon_Spellbook_Demon | weapon | builtin | Common | 40 | 2127 |
| Weapon_Spellbook_Fire | weapon | builtin | Common | 40 | 2127 |
| Weapon_Spellbook_Frost | weapon | builtin | Common | 40 | 2127 |
| Weapon_Spellbook_Grimoire_Brown | weapon | builtin | Common | 40 | 2127 |
| Weapon_Spellbook_Grimoire_Purple | weapon | builtin | Common | 40 | 2127 |
| Weapon_Spellbook_Rekindle_Embers | weapon | builtin | Rare | 40 | 4040 |
| Weapon_Staff_Adamantite | weapon | builtin | Rare | 40 | 4040 |
| Weapon_Staff_Bo_Bamboo | weapon | builtin | Common | 40 | 2127 |
| Weapon_Staff_Bo_Wood | weapon | builtin | Common | 40 | 2127 |
| Weapon_Staff_Bone | weapon | builtin | Common | 25 | 1102 |
| Weapon_Staff_Bronze | weapon | builtin | Common | 25 | 1102 |
| Weapon_Staff_Cane | weapon | builtin | Common | 40 | 2127 |
| Weapon_Staff_Cobalt | weapon | builtin | Rare | 35 | 3351 |
| Weapon_Staff_Copper | weapon | builtin | Uncommon | 10 | 406 |
| Weapon_Staff_Crystal_Fire_Trork | weapon | builtin | Common | 30 | 1422 |
| Weapon_Staff_Crystal_Ice | weapon | builtin | Rare | 50 | 5522 |
| Weapon_Staff_Crystal_Purple | weapon | builtin | Common | 40 | 2127 |
| Weapon_Staff_Crystal_Red | weapon | builtin | Common | 40 | 2127 |
| Weapon_Staff_Doomed | weapon | builtin | Common | 30 | 1422 |
| Weapon_Staff_Frost | weapon | builtin | Common | 40 | 2127 |
| Weapon_Staff_Iron | weapon | builtin | Uncommon | 20 | 1071 |
| Weapon_Staff_Mithril | weapon | builtin | Epic | 50 | 8970 |
| Weapon_Staff_Onion | weapon | builtin | Common | 40 | 2127 |
| Weapon_Staff_Onyxium | weapon | builtin | Common | 50 | 2908 |
| Weapon_Staff_Thorium | weapon | builtin | Rare | 30 | 2701 |
| Weapon_Staff_Wizard | weapon | builtin | Common | 40 | 2127 |
| Weapon_Staff_Wood | weapon | builtin | Common | 40 | 2127 |
| Weapon_Staff_Wood_Kweebec | weapon | builtin | Common | 20 | 806 |
| Weapon_Staff_Wood_Rotten | weapon | builtin | Common | 40 | 2127 |
| Weapon_Wand_Root | weapon | builtin | Common | 40 | 2127 |
| Weapon_Wand_Stoneskin | weapon | builtin | Common | 40 | 2127 |
| Weapon_Wand_Tribal | weapon | builtin | Common | 40 | 2127 |
| Weapon_Wand_Wood | weapon | builtin | Common | 40 | 2127 |
| Weapon_Wand_Wood_Rotten | weapon | builtin | Common | 40 | 2127 |
| Weapon_Deployable_Healing_Totem | weapon | duntale | Common | 40 | 1749 |
| Weapon_Deployable_Slowness_Totem | weapon | duntale | Common | 40 | 1749 |
| Weapon_Deployable_Turret | weapon | duntale | Common | 40 | 1749 |
| Weapon_Shortbow_Bomb | weapon | duntale | Common | 10 | 324 |
| Weapon_Shortbow_Combat | weapon | duntale | Common | 10 | 324 |
| Weapon_Shortbow_Pull | weapon | duntale | Common | 10 | 324 |
| Weapon_Shortbow_Ricochet | weapon | duntale | Common | 10 | 324 |
| Weapon_Shortbow_Vampire | weapon | duntale | Common | 10 | 324 |
| WanMine_Void_Requiem_Scythe | weapon | wans | Relic | 65 | 3452 |

## 2. Price Audit (runtime price model replica)

| Gear level | Weapon p50 | Weapon p90 | Weapon max | Armor p50 | Armor p90 | Armor max | Max ratio W/A |
|---|---|---|---|---|---|---|---|
| 10 | 1334 | 4630 | 23766 | 1252 | 2025 | 3691 | 6.4x |
| 25 | 2119 | 7127 | 41422 | 1563 | 2527 | 4644 | 8.9x |
| 50 | 4077 | 21247 | 134766 | 2996 | 4838 | 9058 | 14.9x |
| 75 | 7471 | 39934 | 253291 | 4663 | 7526 | 14210 | 17.8x |
| 100 | 7977 | 46610 | 295641 | 5242 | 8460 | 16002 | 18.5x |

Top stat-priced weapons at gear level 75:

| Weapon | Source | Quality | ItemLevel | Buy |
|---|---|---|---|---|
| Weapon_Longsword_Praetorian | builtin | Common | 25 | 253291 |
| Weapon_Longsword_Onyxium | builtin | Epic | 50 | 149473 |
| Weapon_Battleaxe_Scythe_Void | builtin | Common | 30 | 144477 |
| Weapon_Axe_Onyxium | builtin | Epic | 50 | 137310 |
| Weapon_Club_Onyxium | builtin | Epic | 50 | 137310 |
| Mystic_Dominus_Maul | zets | Abyssal | 48 | 111374 |
| Weapon_Longsword_Mithril | builtin | Epic | 50 | 88239 |
| Weapon_Axe_Mithril | builtin | Epic | 50 | 82413 |
| Weapon_Club_Mithril | builtin | Epic | 50 | 82413 |
| WanMine_Soulblight_Longsword | wans | Relic | 60 | 76702 |

Top stat-priced armor at gear level 75:

| Armor | Source | Quality | ItemLevel | Buy |
|---|---|---|---|---|
| Armor_Adamantite_Chest | builtin | Rare | 40 | 14210 |
| Armor_Mithril_Chest | builtin | Epic | 50 | 14210 |
| Armor_Cobalt_Chest | builtin | Rare | 35 | 10655 |
| Armor_Thorium_Chest | builtin | Rare | 30 | 10655 |
| Armor_Adamantite_Legs | builtin | Rare | 40 | 10026 |
| Armor_Mithril_Legs | builtin | Epic | 50 | 10026 |
| Armor_Bronze_Chest | builtin | Uncommon | 25 | 7526 |
| Armor_Bronze_Ornate_Chest | builtin | Rare | 28 | 7526 |
| Armor_Cloth_Cindercloth_Chest | builtin | Rare | 45 | 7526 |
| Armor_Cloth_Cotton_Chest | builtin | Common | 25 | 7526 |

Structural cause of the weapon/armor asymmetry: weapon score = baseDamage × weaponMult(level) is unbounded (×7 at L100), armor score = avgDR% × 3 + health × 0.9 saturates at the 65% DR cap (`CombatScaling.MAX_ARMOR_DR`), so armor prices plateau while weapon prices keep climbing through the ^1.4 gold curve.

### Fixed-price custom items (CustomItems.BUY_PRICES, level-independent)

| Item | Buy | Sell (80%) |
|---|---|---|
| Healing_Necklace_II | 125,000 | 100,000 |
| Speed_Boots_III | 70,000 | 56,000 |
| Vampire_Juice | 50,000 | 40,000 |
| Speed_Boots_II | 45,000 | 36,000 |
| Healing_Necklace_I | 45,000 | 36,000 |
| Immunity_Trap_Ring | 35,000 | 28,000 |
| Speed_Boots_I | 30,000 | 24,000 |
| Stat_Point_Token | 7,500 | 6,000 |
| Village_Warp | 5,000 | 4,000 |
| Palporter | 2,500 | 2,000 |

Merchants also reserve catalog slots for enchant scrolls (`CatalogGenerator.RESERVED_SCROLL_ITEM_IDS`, SimpleEnchantments) — an additional gold sink that is out of scope for this pass per the rebalance brief.

## 3. NPC Roster & Loot Coverage

- SpawnPool roles across 7 themes: **63**
- Loot tables shipped: **63** (41 base, 18 variant overlays, 4 chest)
- Roster roles with NO loot table: **24** — scaled NPCs drop NOTHING because `NpcLootSystem` suppresses engine drops for all scaled NPCs
- Summoned roles with NO loot table: **7**

### Roster roles missing a loot table

| Role | Themes (floor range) | Base HP |
|---|---|---|
| Emberwulf | Volcanic F15-45 | 193 |
| Fen_Stalker | Temple_Dark F15-40 | 74 |
| Feran_Windwalker | Volcanic F25-70 | 61 |
| Goblin_Hermit | Crypt F5-30 | 38 |
| Golem_Crystal_Earth | Crypt F20-70 | 160 |
| Larva_Silk | Hive F15-50 | 25 |
| Leopard_Snow | Arcane F20-70 | 103 |
| Outlander_Hunter | Arcane F1-40 | 61 |
| Outlander_Peon | Arcane F5-30 | 81 |
| Outlander_Priest | Arcane F10-35 | 103 |
| Scorpion | Hive F20-70 | 124 |
| Skeleton_Burnt_Alchemist | Temple_Dark F10-70 | 103 |
| Skeleton_Frost_Fighter | Temple_Dark F10-70 | 74 |
| Slug_Magma | Volcanic F30-70 | 103 |
| Spawn_Void | Crypt F30-70 | 193 |
| Spectre_Void | Temple_Dark F30-70 | 41 |
| Spider | Hive F25-70 | 61 |
| Spider_Cave | Hive F45-70 | 74 |
| Toad_Rhino | Mushroom F25-70 | 124 |
| Toad_Rhino_Magma | Volcanic F25-70 | 124 |
| Trork_Doctor_Witch | Mushroom F25-70 | 74 |
| Trork_Sentry | Mushroom F5-45 | 61 |
| Werewolf | Temple_Dark F5-70 | 283 |
| Yeti | Arcane F15-70 | 226 |

### Summon-based roles missing a loot table

| Role |
|---|
| Skeleton |
| Wolf_Black |
| Wolf_Outlander_Priest |
| Wolf_Outlander_Sorcerer |
| Wolf_Trork_Hunter |
| Wolf_Trork_Shaman |
| Wolf_Wife |

### Summon edges discovered in role data

| Summoner | Summoned | Via | Summoner in roster? |
|---|---|---|---|
| Outlander_Priest | Wolf_Outlander_Priest | SummonKind | yes |
| Outlander_Sorcerer | Wolf_Outlander_Sorcerer | SummonKind | yes |
| Trork_Doctor_Witch | Wolf_Trork_Shaman | inherited from Template_Trork_Mage | yes |
| Trork_Shaman | Wolf_Trork_Shaman | inherited from Template_Trork_Mage | yes |

Runtime summon allowlist (BuiltInNpcSpawnScalingSystem): Skeleton, Scarak_Louse, Wolf_Outlander_Sorcerer, Wolf_Outlander_Priest, Wolf_Trork_Shaman, Wolf_Trork_Hunter, Wolf_Wife, Wolf_Black. `Skeleton`, `Scarak_Louse`, `Wolf_Wife`, and `Wolf_Black` have no SummonKind in role data — they spawn via other paths (spell interactions, Hive `Deco_Scarak_Eggsacks` props at SpawnChance 0.3 / MaxPerRoom 3 in COMBAT rooms, etc.).

### Orphan base tables (no SpawnPool role, not a known summon)

| Table |
|---|
| Risen_Knight |
| Trork_Guard |

### Variant overlay gaps

- Roster roles spawning ELITE without `_Elite` overlay (falls back to base table): Feran_Longtooth, Goblin_Duke, Goblin_Lobber, Goblin_Ogre, Goblin_Scavenger, Golem_Crystal_Flame, Outlander_Berserker, Scarak_Defender, Scarak_Fighter_Royal_Guard, Shadow_Knight, Skeleton_Mage, Spirit_Ember, Trork_Mauler, Trork_Warrior, Wraith
- Roster roles spawning BOSS without `_Boss` overlay: —

## 4. Drop Economics

- Base `DropChance` across 59 NPC tables: min 0.32, median 0.52, max 1.00
- Luck config (RpgConfig.json): LuckMaxDropBonus=0.3, LuckHalfPoint=20.0, LuckLevelsPerBonusRoll=15

### Current Luck curve vs target

| Luck | Drop bonus | Bonus rolls | Eff. chance (base 0.32) | Eff. chance (median base) |
|---|---|---|---|---|
| 0 | +0.0pp | 0 | 32% | 52% |
| 10 | +10.0pp | 0 | 42% | 62% |
| 20 | +15.0pp | 1 | 47% | 67% |
| 30 | +18.0pp | 2 | 50% | 70% |
| 50 | +21.4pp | 3 | 53% | 73% |

Target curve from rebalance brief: ~10% base, ~40% at Luck 30, ~80% at Luck 50. That curve is ACCELERATING; the current bonus is a saturating hyperbolic (`RpgStatEffects.hyperbolic`), which mathematically cannot produce it — the balancing phase needs a formula change, not just config tuning.

### Expected value per kill (gold EV includes ×npcLevel gold scaling)

| Table | Lvl | Chance L0 | Gold L0 | Items L0 | Gold L20 | Items L20 | Gold L50 | Items L50 |
|---|---|---|---|---|---|---|---|---|
| Feran_Longtooth | 5 | 40% | 4.1 | 0.16 | 11.3 | 0.45 | 25.3 | 1.01 |
| Feran_Longtooth | 15 | 40% | 11.4 | 0.18 | 31.2 | 0.51 | 69.7 | 1.13 |
| Feran_Longtooth | 30 | 40% | 22.7 | 0.18 | 62.4 | 0.51 | 139.5 | 1.13 |
| Feran_Longtooth | 50 | 40% | 37.8 | 0.18 | 104.1 | 0.51 | 232.4 | 1.13 |
| Feran_Sharptooth | 5 | 35% | 1.8 | 0.17 | 5.1 | 0.49 | 11.6 | 1.10 |
| Feran_Sharptooth | 15 | 35% | 5.4 | 0.17 | 15.4 | 0.49 | 34.7 | 1.10 |
| Feran_Sharptooth | 30 | 35% | 10.8 | 0.17 | 30.7 | 0.49 | 69.4 | 1.10 |
| Feran_Sharptooth | 50 | 35% | 17.9 | 0.17 | 51.2 | 0.49 | 115.7 | 1.10 |
| Ghoul | 5 | 82% | 38.4 | 0.34 | 90.9 | 0.80 | 187.4 | 1.66 |
| Ghoul | 15 | 82% | 115.2 | 0.34 | 272.6 | 0.80 | 562.2 | 1.66 |
| Ghoul | 30 | 82% | 230.5 | 0.34 | 545.3 | 0.80 | 1124.3 | 1.66 |
| Ghoul | 50 | 82% | 384.1 | 0.34 | 908.8 | 0.80 | 1873.9 | 1.66 |
| Ghoul_Boss | 5 | 100% | 7.1 | 0.90 | 14.3 | 1.81 | 28.6 | 3.62 |
| Ghoul_Boss | 15 | 100% | 21.4 | 0.90 | 42.9 | 1.81 | 85.7 | 3.62 |
| Ghoul_Boss | 30 | 100% | 39.1 | 0.91 | 78.3 | 1.83 | 156.5 | 3.65 |
| Ghoul_Boss | 50 | 100% | 65.2 | 0.91 | 130.4 | 1.83 | 260.9 | 3.65 |
| Goblin_Duke | 5 | 90% | 49.1 | 0.35 | 109.1 | 0.79 | 218.2 | 1.58 |
| Goblin_Duke | 15 | 90% | 147.3 | 0.35 | 327.3 | 0.79 | 654.5 | 1.58 |
| Goblin_Duke | 30 | 90% | 294.5 | 0.35 | 654.5 | 0.79 | 1309.1 | 1.58 |
| Goblin_Duke | 50 | 90% | 490.9 | 0.35 | 1090.9 | 0.79 | 2181.8 | 1.58 |
| Goblin_Duke_Boss | 5 | 100% | 7.1 | 0.90 | 14.3 | 1.81 | 28.6 | 3.62 |
| Goblin_Duke_Boss | 15 | 100% | 21.4 | 0.90 | 42.9 | 1.81 | 85.7 | 3.62 |
| Goblin_Duke_Boss | 30 | 100% | 39.1 | 0.91 | 78.3 | 1.83 | 156.5 | 3.65 |
| Goblin_Duke_Boss | 50 | 100% | 65.2 | 0.91 | 130.4 | 1.83 | 260.9 | 3.65 |
| Goblin_Lobber | 5 | 42% | 4.0 | 0.19 | 10.9 | 0.52 | 24.2 | 1.15 |
| Goblin_Lobber | 15 | 42% | 12.0 | 0.19 | 32.6 | 0.52 | 72.7 | 1.15 |
| Goblin_Lobber | 30 | 42% | 22.6 | 0.20 | 61.4 | 0.56 | 136.6 | 1.24 |
| Goblin_Lobber | 50 | 42% | 37.7 | 0.20 | 102.3 | 0.56 | 227.7 | 1.24 |
| Goblin_Miner | 5 | 40% | 2.0 | 0.20 | 5.6 | 0.54 | 12.6 | 1.20 |
| Goblin_Miner | 15 | 40% | 6.1 | 0.20 | 16.9 | 0.54 | 37.7 | 1.20 |
| Goblin_Miner | 30 | 40% | 12.3 | 0.20 | 33.8 | 0.54 | 75.5 | 1.20 |
| Goblin_Miner | 50 | 40% | 20.5 | 0.20 | 56.3 | 0.54 | 125.8 | 1.20 |
| Goblin_Ogre | 5 | 46% | 11.0 | 0.18 | 29.3 | 0.49 | 64.7 | 1.08 |
| Goblin_Ogre | 15 | 46% | 33.1 | 0.18 | 87.8 | 0.49 | 194.2 | 1.08 |
| Goblin_Ogre | 30 | 46% | 66.2 | 0.18 | 175.7 | 0.49 | 388.4 | 1.08 |
| Goblin_Ogre | 50 | 46% | 110.4 | 0.18 | 292.8 | 0.49 | 647.3 | 1.08 |
| Goblin_Scavenger | 5 | 42% | 4.3 | 0.18 | 11.6 | 0.48 | 25.7 | 1.07 |
| Goblin_Scavenger | 15 | 42% | 12.8 | 0.18 | 34.7 | 0.48 | 77.2 | 1.07 |
| Goblin_Scavenger | 30 | 42% | 25.6 | 0.18 | 69.4 | 0.48 | 154.4 | 1.07 |
| Goblin_Scavenger | 50 | 42% | 42.6 | 0.18 | 115.6 | 0.48 | 257.3 | 1.07 |
| Goblin_Scrapper | 5 | 40% | 3.8 | 0.19 | 10.3 | 0.51 | 23.0 | 1.14 |
| Goblin_Scrapper | 15 | 40% | 11.2 | 0.19 | 30.9 | 0.51 | 69.1 | 1.14 |
| Goblin_Scrapper | 30 | 40% | 22.5 | 0.19 | 61.9 | 0.51 | 138.2 | 1.14 |
| Goblin_Scrapper | 50 | 40% | 37.5 | 0.19 | 103.1 | 0.51 | 230.4 | 1.14 |
| Golem_Crystal_Earth_Boss | 5 | 100% | 6.5 | 0.91 | 13.0 | 1.83 | 26.1 | 3.65 |
| Golem_Crystal_Earth_Boss | 15 | 100% | 19.6 | 0.91 | 39.1 | 1.83 | 78.3 | 3.65 |
| Golem_Crystal_Earth_Boss | 30 | 100% | 39.1 | 0.91 | 78.3 | 1.83 | 156.5 | 3.65 |
| Golem_Crystal_Earth_Boss | 50 | 100% | 65.2 | 0.91 | 130.4 | 1.83 | 260.9 | 3.65 |
| Golem_Crystal_Flame | 5 | 78% | 30.4 | 0.31 | 72.5 | 0.74 | 155.1 | 1.59 |
| Golem_Crystal_Flame | 15 | 78% | 91.3 | 0.31 | 217.6 | 0.74 | 465.3 | 1.59 |
| Golem_Crystal_Flame | 30 | 78% | 182.5 | 0.31 | 435.2 | 0.74 | 930.7 | 1.59 |
| Golem_Crystal_Flame | 50 | 78% | 304.2 | 0.31 | 725.4 | 0.74 | 1551.1 | 1.59 |
| Golem_Firesteel | 5 | 82% | 50.0 | 0.26 | 118.2 | 0.63 | 243.8 | 1.29 |
| Golem_Firesteel | 15 | 82% | 149.9 | 0.26 | 354.7 | 0.63 | 731.2 | 1.29 |
| Golem_Firesteel | 30 | 82% | 299.8 | 0.26 | 709.3 | 0.63 | 1462.5 | 1.29 |
| Golem_Firesteel | 50 | 82% | 499.7 | 0.26 | 1182.2 | 0.63 | 2437.5 | 1.29 |
| Golem_Firesteel_Boss | 5 | 100% | 7.1 | 0.90 | 14.3 | 1.81 | 28.6 | 3.62 |
| Golem_Firesteel_Boss | 15 | 100% | 21.4 | 0.90 | 42.9 | 1.81 | 85.7 | 3.62 |
| Golem_Firesteel_Boss | 30 | 100% | 39.1 | 0.91 | 78.3 | 1.83 | 156.5 | 3.65 |
| Golem_Firesteel_Boss | 50 | 100% | 65.2 | 0.91 | 130.4 | 1.83 | 260.9 | 3.65 |
| Outlander_Berserker | 5 | 38% | 3.4 | 0.19 | 9.4 | 0.52 | 21.2 | 1.17 |
| Outlander_Berserker | 15 | 38% | 10.1 | 0.19 | 28.3 | 0.52 | 63.5 | 1.17 |
| Outlander_Berserker | 30 | 38% | 20.3 | 0.19 | 56.6 | 0.52 | 126.9 | 1.17 |
| Outlander_Berserker | 50 | 38% | 33.8 | 0.19 | 94.3 | 0.52 | 211.5 | 1.17 |
| Outlander_Brute | 5 | 72% | 36.2 | 0.24 | 87.4 | 0.57 | 187.8 | 1.23 |
| Outlander_Brute | 15 | 72% | 108.6 | 0.24 | 262.3 | 0.57 | 563.5 | 1.23 |
| Outlander_Brute | 30 | 72% | 217.1 | 0.24 | 524.7 | 0.57 | 1126.9 | 1.23 |
| Outlander_Brute | 50 | 72% | 361.9 | 0.24 | 874.5 | 0.57 | 1878.2 | 1.23 |
| Outlander_Brute_Boss | 5 | 100% | 7.1 | 0.90 | 14.3 | 1.81 | 28.6 | 3.62 |
| Outlander_Brute_Boss | 15 | 100% | 21.4 | 0.90 | 42.9 | 1.81 | 85.7 | 3.62 |
| Outlander_Brute_Boss | 30 | 100% | 39.1 | 0.91 | 78.3 | 1.83 | 156.5 | 3.65 |
| Outlander_Brute_Boss | 50 | 100% | 65.2 | 0.91 | 130.4 | 1.83 | 260.9 | 3.65 |
| Outlander_Marauder | 5 | 52% | 12.9 | 0.22 | 33.2 | 0.56 | 72.7 | 1.23 |
| Outlander_Marauder | 15 | 52% | 38.6 | 0.22 | 99.5 | 0.56 | 218.1 | 1.23 |
| Outlander_Marauder | 30 | 52% | 77.2 | 0.22 | 199.0 | 0.56 | 436.3 | 1.23 |
| Outlander_Marauder | 50 | 52% | 128.7 | 0.22 | 331.7 | 0.56 | 727.2 | 1.23 |
| Outlander_Marauder_Elite | 5 | 72% | 29.8 | 0.28 | 71.9 | 0.67 | 154.4 | 1.45 |
| Outlander_Marauder_Elite | 15 | 72% | 89.3 | 0.28 | 215.7 | 0.67 | 463.3 | 1.45 |
| Outlander_Marauder_Elite | 30 | 72% | 178.5 | 0.28 | 431.4 | 0.67 | 926.7 | 1.45 |
| Outlander_Marauder_Elite | 50 | 72% | 297.6 | 0.28 | 719.1 | 0.67 | 1544.4 | 1.45 |
| Outlander_Sorcerer | 5 | 42% | 7.7 | 0.18 | 21.0 | 0.49 | 46.7 | 1.10 |
| Outlander_Sorcerer | 15 | 42% | 23.2 | 0.18 | 62.9 | 0.49 | 140.0 | 1.10 |
| Outlander_Sorcerer | 30 | 42% | 46.4 | 0.18 | 125.8 | 0.49 | 280.0 | 1.10 |
| Outlander_Sorcerer | 50 | 42% | 77.3 | 0.18 | 209.7 | 0.49 | 466.7 | 1.10 |
| Outlander_Stalker | 5 | 36% | 2.3 | 0.17 | 6.6 | 0.49 | 15.0 | 1.10 |
| Outlander_Stalker | 15 | 36% | 7.0 | 0.17 | 19.9 | 0.49 | 44.9 | 1.10 |
| Outlander_Stalker | 30 | 36% | 14.1 | 0.17 | 39.9 | 0.49 | 89.8 | 1.10 |
| Outlander_Stalker | 50 | 36% | 23.4 | 0.17 | 66.4 | 0.49 | 149.6 | 1.10 |
| Risen_Knight | 5 | 52% | 13.0 | 0.21 | 33.5 | 0.55 | 73.4 | 1.21 |
| Risen_Knight | 15 | 52% | 39.0 | 0.21 | 100.5 | 0.55 | 220.3 | 1.21 |
| Risen_Knight | 30 | 52% | 78.0 | 0.21 | 201.0 | 0.55 | 440.6 | 1.21 |
| Risen_Knight | 50 | 52% | 130.0 | 0.21 | 335.0 | 0.55 | 734.3 | 1.21 |
| Risen_Knight_Elite | 5 | 74% | 30.9 | 0.28 | 74.3 | 0.68 | 159.4 | 1.46 |
| Risen_Knight_Elite | 15 | 74% | 92.7 | 0.28 | 223.0 | 0.68 | 478.1 | 1.46 |
| Risen_Knight_Elite | 30 | 74% | 185.4 | 0.28 | 445.9 | 0.68 | 956.3 | 1.46 |
| Risen_Knight_Elite | 50 | 74% | 309.0 | 0.28 | 743.2 | 0.68 | 1593.8 | 1.46 |
| Scarak_Broodmother | 5 | 76% | 39.1 | 0.27 | 93.7 | 0.65 | 200.6 | 1.39 |
| Scarak_Broodmother | 15 | 76% | 117.4 | 0.27 | 281.1 | 0.65 | 601.9 | 1.39 |
| Scarak_Broodmother | 30 | 76% | 234.8 | 0.27 | 562.2 | 0.65 | 1203.9 | 1.39 |
| Scarak_Broodmother | 50 | 76% | 391.3 | 0.27 | 937.0 | 0.65 | 2006.4 | 1.39 |
| Scarak_Broodmother_Boss | 5 | 100% | 7.1 | 0.90 | 14.3 | 1.81 | 28.6 | 3.62 |
| Scarak_Broodmother_Boss | 15 | 100% | 21.4 | 0.90 | 42.9 | 1.81 | 85.7 | 3.62 |
| Scarak_Broodmother_Boss | 30 | 100% | 39.1 | 0.91 | 78.3 | 1.83 | 156.5 | 3.65 |
| Scarak_Broodmother_Boss | 50 | 100% | 65.2 | 0.91 | 130.4 | 1.83 | 260.9 | 3.65 |
| Scarak_Defender | 5 | 42% | 4.5 | 0.19 | 12.3 | 0.52 | 27.4 | 1.17 |
| Scarak_Defender | 15 | 42% | 13.6 | 0.19 | 37.0 | 0.52 | 82.3 | 1.17 |
| Scarak_Defender | 30 | 42% | 27.2 | 0.19 | 73.9 | 0.52 | 164.6 | 1.17 |
| Scarak_Defender | 50 | 42% | 45.4 | 0.19 | 123.2 | 0.52 | 274.3 | 1.17 |
| Scarak_Fighter | 5 | 40% | 2.0 | 0.20 | 5.4 | 0.56 | 12.1 | 1.25 |
| Scarak_Fighter | 15 | 40% | 5.9 | 0.20 | 16.2 | 0.56 | 36.3 | 1.25 |
| Scarak_Fighter | 30 | 40% | 11.8 | 0.20 | 32.5 | 0.56 | 72.5 | 1.25 |
| Scarak_Fighter | 50 | 40% | 19.7 | 0.20 | 54.1 | 0.56 | 120.8 | 1.25 |
| Scarak_Fighter_Royal_Guard | 5 | 42% | 6.3 | 0.19 | 17.1 | 0.52 | 38.1 | 1.15 |
| Scarak_Fighter_Royal_Guard | 15 | 42% | 18.9 | 0.19 | 51.3 | 0.52 | 114.2 | 1.15 |
| Scarak_Fighter_Royal_Guard | 30 | 42% | 37.8 | 0.19 | 102.6 | 0.52 | 228.3 | 1.15 |
| Scarak_Fighter_Royal_Guard | 50 | 42% | 63.0 | 0.19 | 171.0 | 0.52 | 380.6 | 1.15 |
| Scarak_Louse | 5 | 32% | 1.9 | 0.07 | 5.5 | 0.21 | 12.5 | 0.47 |
| Scarak_Louse | 15 | 32% | 5.6 | 0.07 | 16.4 | 0.21 | 37.4 | 0.47 |
| Scarak_Louse | 30 | 32% | 11.2 | 0.07 | 32.9 | 0.21 | 74.8 | 0.47 |
| Scarak_Louse | 50 | 32% | 18.7 | 0.07 | 54.8 | 0.21 | 124.7 | 0.47 |
| Scarak_Seeker | 5 | 42% | 4.2 | 0.18 | 11.5 | 0.48 | 25.6 | 1.07 |
| Scarak_Seeker | 15 | 42% | 11.6 | 0.20 | 31.5 | 0.54 | 70.1 | 1.20 |
| Scarak_Seeker | 30 | 42% | 23.2 | 0.20 | 63.0 | 0.54 | 140.2 | 1.20 |
| Scarak_Seeker | 50 | 42% | 38.7 | 0.20 | 105.0 | 0.54 | 233.7 | 1.20 |
| Scorpion_Boss | 5 | 100% | 6.5 | 0.91 | 13.0 | 1.83 | 26.1 | 3.65 |
| Scorpion_Boss | 15 | 100% | 19.6 | 0.91 | 39.1 | 1.83 | 78.3 | 3.65 |
| Scorpion_Boss | 30 | 100% | 39.1 | 0.91 | 78.3 | 1.83 | 156.5 | 3.65 |
| Scorpion_Boss | 50 | 100% | 65.2 | 0.91 | 130.4 | 1.83 | 260.9 | 3.65 |
| Shadow_Knight | 5 | 90% | 47.3 | 0.34 | 105.2 | 0.76 | 210.3 | 1.53 |
| Shadow_Knight | 15 | 90% | 142.0 | 0.34 | 315.5 | 0.76 | 630.9 | 1.53 |
| Shadow_Knight | 30 | 90% | 283.9 | 0.34 | 630.9 | 0.76 | 1261.9 | 1.53 |
| Shadow_Knight | 50 | 90% | 473.2 | 0.34 | 1051.5 | 0.76 | 2103.1 | 1.53 |
| Shadow_Knight_Boss | 5 | 100% | 6.5 | 0.91 | 13.0 | 1.83 | 26.1 | 3.65 |
| Shadow_Knight_Boss | 15 | 100% | 19.6 | 0.91 | 39.1 | 1.83 | 78.3 | 3.65 |
| Shadow_Knight_Boss | 30 | 100% | 39.1 | 0.91 | 78.3 | 1.83 | 156.5 | 3.65 |
| Shadow_Knight_Boss | 50 | 100% | 65.2 | 0.91 | 130.4 | 1.83 | 260.9 | 3.65 |
| Skeleton_Archer | 5 | 45% | 6.2 | 0.20 | 16.7 | 0.53 | 36.9 | 1.18 |
| Skeleton_Archer | 15 | 45% | 18.7 | 0.20 | 50.0 | 0.53 | 110.7 | 1.18 |
| Skeleton_Archer | 30 | 45% | 37.5 | 0.20 | 100.0 | 0.53 | 221.4 | 1.18 |
| Skeleton_Archer | 50 | 45% | 62.5 | 0.20 | 166.7 | 0.53 | 369.0 | 1.18 |
| Skeleton_Fighter | 5 | 45% | 7.1 | 0.19 | 18.9 | 0.51 | 41.8 | 1.14 |
| Skeleton_Fighter | 15 | 45% | 21.2 | 0.19 | 56.6 | 0.51 | 125.3 | 1.14 |
| Skeleton_Fighter | 30 | 45% | 42.4 | 0.19 | 113.1 | 0.51 | 250.5 | 1.14 |
| Skeleton_Fighter | 50 | 45% | 70.7 | 0.19 | 188.6 | 0.51 | 417.6 | 1.14 |
| Skeleton_Knight | 5 | 50% | 11.9 | 0.20 | 30.9 | 0.53 | 67.9 | 1.16 |
| Skeleton_Knight | 15 | 50% | 35.6 | 0.20 | 92.7 | 0.53 | 203.7 | 1.16 |
| Skeleton_Knight | 30 | 50% | 71.3 | 0.20 | 185.3 | 0.53 | 407.4 | 1.16 |
| Skeleton_Knight | 50 | 50% | 118.8 | 0.20 | 308.9 | 0.53 | 678.9 | 1.16 |
| Skeleton_Knight_Elite | 5 | 70% | 26.2 | 0.26 | 63.8 | 0.64 | 137.1 | 1.37 |
| Skeleton_Knight_Elite | 15 | 70% | 78.8 | 0.26 | 191.2 | 0.64 | 411.4 | 1.37 |
| Skeleton_Knight_Elite | 30 | 70% | 157.5 | 0.26 | 382.5 | 0.64 | 822.9 | 1.37 |
| Skeleton_Knight_Elite | 50 | 70% | 262.5 | 0.26 | 637.5 | 0.64 | 1371.4 | 1.37 |
| Skeleton_Mage | 5 | 43% | 8.1 | 0.18 | 21.7 | 0.49 | 48.3 | 1.09 |
| Skeleton_Mage | 15 | 43% | 24.2 | 0.18 | 65.2 | 0.49 | 145.0 | 1.09 |
| Skeleton_Mage | 30 | 43% | 48.4 | 0.18 | 130.5 | 0.49 | 289.9 | 1.09 |
| Skeleton_Mage | 50 | 43% | 80.6 | 0.18 | 217.5 | 0.49 | 483.2 | 1.09 |
| Skeleton_Soldier | 5 | 40% | 4.2 | 0.25 | 11.6 | 0.68 | 26.0 | 1.51 |
| Skeleton_Soldier | 15 | 40% | 12.7 | 0.25 | 34.9 | 0.68 | 78.0 | 1.51 |
| Skeleton_Soldier | 30 | 40% | 25.4 | 0.25 | 69.8 | 0.68 | 155.9 | 1.51 |
| Skeleton_Soldier | 50 | 40% | 42.3 | 0.25 | 116.3 | 0.68 | 259.9 | 1.51 |
| Spawn_Void_Boss | 5 | 100% | 6.5 | 0.91 | 13.0 | 1.83 | 26.1 | 3.65 |
| Spawn_Void_Boss | 15 | 100% | 19.6 | 0.91 | 39.1 | 1.83 | 78.3 | 3.65 |
| Spawn_Void_Boss | 30 | 100% | 39.1 | 0.91 | 78.3 | 1.83 | 156.5 | 3.65 |
| Spawn_Void_Boss | 50 | 100% | 65.2 | 0.91 | 130.4 | 1.83 | 260.9 | 3.65 |
| Spirit_Ember | 5 | 45% | 8.9 | 0.20 | 23.8 | 0.52 | 52.6 | 1.15 |
| Spirit_Ember | 15 | 45% | 26.7 | 0.20 | 71.3 | 0.52 | 157.9 | 1.15 |
| Spirit_Ember | 30 | 45% | 53.5 | 0.20 | 142.6 | 0.52 | 315.8 | 1.15 |
| Spirit_Ember | 50 | 45% | 89.2 | 0.20 | 237.7 | 0.52 | 526.4 | 1.15 |
| Toad_Rhino_Boss | 5 | 100% | 6.5 | 0.91 | 13.0 | 1.83 | 26.1 | 3.65 |
| Toad_Rhino_Boss | 15 | 100% | 19.6 | 0.91 | 39.1 | 1.83 | 78.3 | 3.65 |
| Toad_Rhino_Boss | 30 | 100% | 39.1 | 0.91 | 78.3 | 1.83 | 156.5 | 3.65 |
| Toad_Rhino_Boss | 50 | 100% | 65.2 | 0.91 | 130.4 | 1.83 | 260.9 | 3.65 |
| Toad_Rhino_Magma_Boss | 5 | 100% | 6.5 | 0.91 | 13.0 | 1.83 | 26.1 | 3.65 |
| Toad_Rhino_Magma_Boss | 15 | 100% | 19.6 | 0.91 | 39.1 | 1.83 | 78.3 | 3.65 |
| Toad_Rhino_Magma_Boss | 30 | 100% | 39.1 | 0.91 | 78.3 | 1.83 | 156.5 | 3.65 |
| Toad_Rhino_Magma_Boss | 50 | 100% | 65.2 | 0.91 | 130.4 | 1.83 | 260.9 | 3.65 |
| Trork_Brawler | 5 | 37% | 1.9 | 0.18 | 5.4 | 0.50 | 12.1 | 1.13 |
| Trork_Brawler | 15 | 37% | 5.7 | 0.18 | 16.1 | 0.50 | 36.2 | 1.13 |
| Trork_Brawler | 30 | 37% | 11.5 | 0.18 | 32.2 | 0.50 | 72.5 | 1.13 |
| Trork_Brawler | 50 | 37% | 19.1 | 0.18 | 53.7 | 0.50 | 120.8 | 1.13 |
| Trork_Chieftain | 5 | 86% | 37.0 | 0.40 | 86.0 | 0.93 | 171.9 | 1.85 |
| Trork_Chieftain | 15 | 86% | 110.9 | 0.40 | 257.9 | 0.93 | 515.7 | 1.85 |
| Trork_Chieftain | 30 | 86% | 221.8 | 0.40 | 515.7 | 0.93 | 1031.4 | 1.85 |
| Trork_Chieftain | 50 | 86% | 369.6 | 0.40 | 859.5 | 0.93 | 1719.0 | 1.85 |
| Trork_Chieftain_Boss | 5 | 100% | 7.1 | 0.90 | 14.3 | 1.81 | 28.6 | 3.62 |
| Trork_Chieftain_Boss | 15 | 100% | 21.4 | 0.90 | 42.9 | 1.81 | 85.7 | 3.62 |
| Trork_Chieftain_Boss | 30 | 100% | 39.1 | 0.91 | 78.3 | 1.83 | 156.5 | 3.65 |
| Trork_Chieftain_Boss | 50 | 100% | 65.2 | 0.91 | 130.4 | 1.83 | 260.9 | 3.65 |
| Trork_Guard | 5 | 38% | 4.4 | 0.09 | 12.2 | 0.24 | 27.4 | 0.55 |
| Trork_Guard | 15 | 38% | 9.3 | 0.17 | 26.0 | 0.48 | 58.3 | 1.08 |
| Trork_Guard | 30 | 38% | 18.7 | 0.17 | 52.0 | 0.48 | 116.7 | 1.08 |
| Trork_Guard | 50 | 38% | 31.1 | 0.17 | 86.7 | 0.48 | 194.5 | 1.08 |
| Trork_Hunter | 5 | 36% | 2.0 | 0.16 | 5.7 | 0.45 | 12.8 | 1.02 |
| Trork_Hunter | 15 | 36% | 6.0 | 0.16 | 17.0 | 0.45 | 38.3 | 1.02 |
| Trork_Hunter | 30 | 36% | 12.0 | 0.16 | 34.0 | 0.45 | 76.6 | 1.02 |
| Trork_Hunter | 50 | 36% | 20.0 | 0.16 | 56.7 | 0.45 | 127.6 | 1.02 |
| Trork_Mauler | 5 | 44% | 9.1 | 0.18 | 24.5 | 0.48 | 54.4 | 1.06 |
| Trork_Mauler | 15 | 44% | 27.4 | 0.18 | 73.6 | 0.48 | 163.2 | 1.06 |
| Trork_Mauler | 30 | 44% | 54.9 | 0.18 | 147.2 | 0.48 | 326.5 | 1.06 |
| Trork_Mauler | 50 | 44% | 91.5 | 0.18 | 245.3 | 0.48 | 544.2 | 1.06 |
| Trork_Shaman | 5 | 44% | 5.6 | 0.21 | 15.1 | 0.57 | 33.6 | 1.28 |
| Trork_Shaman | 15 | 44% | 16.9 | 0.21 | 45.4 | 0.57 | 100.7 | 1.28 |
| Trork_Shaman | 30 | 44% | 33.8 | 0.21 | 90.8 | 0.57 | 201.3 | 1.28 |
| Trork_Shaman | 50 | 44% | 56.4 | 0.21 | 151.3 | 0.57 | 335.5 | 1.28 |
| Trork_Warrior | 5 | 36% | 1.7 | 0.19 | 4.9 | 0.53 | 11.1 | 1.19 |
| Trork_Warrior | 15 | 36% | 5.2 | 0.19 | 14.8 | 0.53 | 33.3 | 1.19 |
| Trork_Warrior | 30 | 36% | 10.5 | 0.19 | 29.6 | 0.53 | 66.7 | 1.19 |
| Trork_Warrior | 50 | 36% | 17.4 | 0.19 | 49.4 | 0.53 | 111.2 | 1.19 |
| Werewolf_Boss | 5 | 100% | 7.1 | 0.90 | 14.3 | 1.81 | 28.6 | 3.62 |
| Werewolf_Boss | 15 | 100% | 21.4 | 0.90 | 42.9 | 1.81 | 85.7 | 3.62 |
| Werewolf_Boss | 30 | 100% | 39.1 | 0.91 | 78.3 | 1.83 | 156.5 | 3.65 |
| Werewolf_Boss | 50 | 100% | 65.2 | 0.91 | 130.4 | 1.83 | 260.9 | 3.65 |
| Wraith | 5 | 75% | 37.9 | 0.28 | 90.9 | 0.66 | 194.7 | 1.42 |
| Wraith | 15 | 75% | 113.6 | 0.28 | 272.6 | 0.66 | 584.2 | 1.42 |
| Wraith | 30 | 75% | 227.2 | 0.28 | 545.2 | 0.66 | 1168.4 | 1.42 |
| Wraith | 50 | 75% | 378.6 | 0.28 | 908.7 | 0.66 | 1947.3 | 1.42 |
| Wraith_Lantern | 5 | 33% | 2.0 | 0.13 | 5.7 | 0.39 | 13.0 | 0.88 |
| Wraith_Lantern | 15 | 33% | 5.9 | 0.13 | 17.2 | 0.39 | 38.9 | 0.88 |
| Wraith_Lantern | 30 | 33% | 11.8 | 0.13 | 34.3 | 0.39 | 77.9 | 0.88 |
| Wraith_Lantern | 50 | 33% | 19.7 | 0.13 | 57.2 | 0.39 | 129.8 | 0.88 |
| Yeti_Boss | 5 | 100% | 7.1 | 0.90 | 14.3 | 1.81 | 28.6 | 3.62 |
| Yeti_Boss | 15 | 100% | 21.4 | 0.90 | 42.9 | 1.81 | 85.7 | 3.62 |
| Yeti_Boss | 30 | 100% | 39.1 | 0.91 | 78.3 | 1.83 | 156.5 | 3.65 |
| Yeti_Boss | 50 | 100% | 65.2 | 0.91 | 130.4 | 1.83 | 260.9 | 3.65 |
| Zombie | 5 | 50% | 10.9 | 0.21 | 28.3 | 0.55 | 62.2 | 1.20 |
| Zombie | 15 | 50% | 32.6 | 0.21 | 84.9 | 0.55 | 186.5 | 1.20 |
| Zombie | 30 | 50% | 65.3 | 0.21 | 169.8 | 0.55 | 373.1 | 1.20 |
| Zombie | 50 | 50% | 108.8 | 0.21 | 282.9 | 0.55 | 621.8 | 1.20 |
| Zombie_Aberrant | 5 | 95% | 60.4 | 0.41 | 127.2 | 0.87 | 254.3 | 1.74 |
| Zombie_Aberrant | 15 | 95% | 181.2 | 0.41 | 381.5 | 0.87 | 763.0 | 1.74 |
| Zombie_Aberrant | 30 | 95% | 362.4 | 0.41 | 763.0 | 0.87 | 1526.1 | 1.74 |
| Zombie_Aberrant | 50 | 95% | 604.1 | 0.41 | 1271.7 | 0.87 | 2543.5 | 1.74 |
| Zombie_Aberrant_Boss | 5 | 100% | 7.1 | 0.90 | 14.3 | 1.81 | 28.6 | 3.62 |
| Zombie_Aberrant_Boss | 15 | 100% | 21.4 | 0.90 | 42.9 | 1.81 | 85.7 | 3.62 |
| Zombie_Aberrant_Boss | 30 | 100% | 39.1 | 0.91 | 78.3 | 1.83 | 156.5 | 3.65 |
| Zombie_Aberrant_Boss | 50 | 100% | 65.2 | 0.91 | 130.4 | 1.83 | 260.9 | 3.65 |

## 5. Income vs Prices Model (crude)

Estimated kills/floor = maxRooms × 0.6 × max(1, enemyDensity × maxEnemiesPerRoom). Respawning spawners, ambushes, and prop spawns (Scarak eggs) are NOT counted, so real income per floor is higher; treat ratios as upper bounds on grind.

| Floor | Themes | Est. kills | Gold/kill (Luck 0) | Gold/floor | Median weapon @F+3 | Median armor @F+3 | Floors per weapon |
|---|---|---|---|---|---|---|---|
| 1 | crypt | 12 | 0.7 | 9 | 406 | 833 | 45.9 |
| 3 | crypt | 24 | 2.2 | 54 | 433 | 869 | 8.1 |
| 5 | crypt,volcanic | 133 | 6.6 | 885 | 608 | 869 | 0.7 |
| 7 | crypt,hive,volcanic | 200 | 9.3 | 1858 | 608 | 869 | 0.3 |
| 10 | crypt,hive,temple_dark,volcanic | 223 | 20.7 | 4629 | 749 | 921 | 0.2 |
| 15 | arcane,volcanic,hive,crypt,temple_dark | 230 | 42.6 | 9826 | 943 | 1062 | 0.1 |
| 20 | crypt,arcane,hive,mushroom,temple_dark,volcanic | 87 | 67.4 | 5888 | 1253 | 1424 | 0.2 |
| 25 | crypt,arcane,hive,mushroom,temple_dark,volcanic | 48 | 83.0 | 3982 | 2127 | 1746 | 0.5 |
| 30 | crypt,arcane,hive,mushroom,temple_dark,volcanic | 48 | 102.2 | 4907 | 2701 | 2569 | 0.6 |
| 40 | crypt,arcane,hive,mushroom,temple_dark,volcanic | 48 | 174.5 | 8378 | 5984 | 3388 | 0.7 |
| 45 | crypt,arcane,hive,mushroom,temple_dark,volcanic | 24 | 204.0 | 4895 | 7564 | 3388 | 1.5 |
| 50 | crypt,arcane,hive,mushroom,temple_dark,volcanic | 48 | 236.1 | 11335 | 9679 | 4269 | 0.9 |
| 55 | crypt,arcane,hive,mushroom,temple_dark,volcanic | 24 | 259.8 | 6234 | 8511 | 4676 | 1.4 |
| 60 | crypt,arcane,hive,mushroom,temple_dark,volcanic | 48 | 283.4 | 13602 | 26670 | 5277 | 2.0 |

## 5b. Per-Theme Income Projection (Luck 0, current loot tables)

Gold per floor by theme. `—` = theme not in that floor's `theme.variants`. Hive includes Scarak_Louse spawns from egg props (~0.9 eggs per combat room). Roles with no loot table contribute ZERO gold, which drags themes with poor coverage down — see coverage below.

| Floor | Est. kills | Arcane | Crypt | Hive | Mine | Mushroom | Temple_Dark | Volcanic |
|---|---|---|---|---|---|---|---|---|
| 1 | 12 | — | 16 | — | — | — | — | — |
| 3 | 24 | — | 98 | — | — | — | — | — |
| 5 | 133 | — | 1,186 | — | — | — | — | 552 |
| 7 | 200 | — | 2,490 | 746 | — | — | — | 1,160 |
| 10 | 223 | — | 5,659 | 1,771 | — | — | 3,686 | 4,612 |
| 15 | 230 | 3,974 | 9,699 | 3,674 | — | — | 4,868 | 8,016 |
| 20 | 87 | 1,691 | 4,761 | 1,862 | — | 2,047 | 3,458 | 4,905 |
| 25 | 48 | 1,213 | 3,272 | 1,223 | — | 1,223 | 2,377 | 2,890 |
| 30 | 48 | 1,455 | 3,534 | 1,586 | — | 1,468 | 2,450 | 3,034 |
| 40 | 48 | 2,474 | 7,046 | 2,119 | — | 2,448 | 3,561 | 4,803 |
| 45 | 24 | 1,964 | 3,964 | 1,132 | — | 1,377 | 2,410 | 2,702 |
| 50 | 48 | 4,365 | 8,808 | 2,517 | — | 4,513 | 5,356 | 7,268 |
| 55 | 24 | 2,401 | 4,844 | 1,633 | — | 2,482 | 2,946 | 3,997 |
| 60 | 48 | 5,238 | 10,569 | 3,563 | — | 5,416 | 6,427 | 8,722 |

Item drops per floor by theme (inventory pressure at Luck 0):

| Floor | Est. kills | Arcane | Crypt | Hive | Mine | Mushroom | Temple_Dark | Volcanic |
|---|---|---|---|---|---|---|---|---|
| 1 | 12 | — | 2.3 | — | — | — | — | — |
| 3 | 24 | — | 4.7 | — | — | — | — | — |
| 5 | 133 | — | 23.6 | — | — | — | — | 23.1 |
| 7 | 200 | — | 35.5 | 29.1 | — | — | — | 34.6 |
| 10 | 223 | — | 43.9 | 37.2 | — | — | 26.0 | 47.0 |
| 15 | 230 | 28.3 | 47.1 | 34.3 | — | — | 22.9 | 42.9 |
| 20 | 87 | 9.0 | 16.0 | 11.8 | — | 15.4 | 10.2 | 16.6 |
| 25 | 48 | 5.2 | 8.8 | 6.5 | — | 7.4 | 5.6 | 7.8 |
| 30 | 48 | 5.2 | 7.9 | 6.8 | — | 7.4 | 4.8 | 6.8 |
| 40 | 48 | 5.6 | 8.7 | 6.4 | — | 7.1 | 3.2 | 6.0 |
| 45 | 24 | 2.9 | 4.3 | 3.1 | — | 3.6 | 2.0 | 3.0 |
| 50 | 48 | 5.9 | 8.7 | 6.1 | — | 7.8 | 3.9 | 7.3 |
| 55 | 24 | 2.9 | 4.3 | 3.6 | — | 3.9 | 2.0 | 3.6 |
| 60 | 48 | 5.9 | 8.7 | 7.1 | — | 7.8 | 3.9 | 7.3 |

Loot-table coverage by theme (worst floor; % of spawn weight that has a table):

| Theme | Coverage | Roles contributing zero |
|---|---|---|
| Arcane | 53% | Leopard_Snow, Outlander_Hunter, Outlander_Peon, Outlander_Priest, Yeti |
| Crypt | 65% | Goblin_Hermit, Golem_Crystal_Earth, Spawn_Void |
| Hive | 47% | Larva_Silk, Scorpion, Spider, Spider_Cave |
| Mushroom | 69% | Toad_Rhino, Trork_Doctor_Witch, Trork_Sentry |
| Temple_Dark | 22% | Fen_Stalker, Skeleton_Burnt_Alchemist, Skeleton_Frost_Fighter, Spectre_Void, Werewolf |
| Volcanic | 48% | Emberwulf, Feran_Windwalker, Slug_Magma, Toad_Rhino_Magma |

## 6. Caveats & Runtime Follow-ups

- `chain`/`none` weapon pricing path is an offline inference; verify in-game which of those items the merchant prices via stats vs fallback (e.g. `/merchant` + tooltip).
- Offline chain damage for `WanMine_Void_Requiem_Scythe` resolves to ~69.8; runtime uses the hand-authored KNOWN_BASE_DAMAGE=81.5 override.
- Zets adds custom qualities (Mythical/Abyssal/Celestial) and Wans uses Relic — NONE are known to `MerchantPriceRegistry.qualityCoefficient`, so their fallback prices collapse to Common-tier coefficients.
- Kills/floor is a static estimate; spawner respawns and Hive egg props inflate it.
- Chest tables (Chest_Regular/Epic/Golden/Legendary) and merchant consumables are recorded in the DB but not modeled in the income table above.

## NPC Archetype Anchors (Economy v2, W2 — derived)

Generated by `scripts/scaling/derive_archetypes.py`. Anchors are spawn-weight-
weighted medians of each archetype's member roles (power-neutral); offsets are
clamped to +/-15% (pillar P4). 64 of 70 roles hit the clamp.

| Archetype | Derived HP | Draft HP | Derived Dmg | Draft Dmg | Members |
|---|---|---|---|---|---|
| Swarm | 25 | 14 | 10.0 | 4.0 | 3 |
| Standard | 61 | 45 | 10.0 | 9.0 | 23 |
| Caster | 74 | 38 | 5.0 | 12.0 | 15 |
| Tough | 103 | 95 | 18.5 | 14.0 | 13 |
| Heavy | 226 | 190 | 10.0 | 20.0 | 4 |
| Boss | 193 | 320 | 35.0 | 26.0 | 12 |

Role membership (for manual review):

- **Swarm**: Larva_Silk, Scarak_Louse, Wraith_Lantern
- **Standard**: Fen_Stalker, Feran_Longtooth, Feran_Sharptooth, Goblin_Hermit, Goblin_Miner, Goblin_Scavenger, Goblin_Scrapper, Outlander_Hunter, Outlander_Stalker, Scarak_Seeker, Skeleton_Fighter, Skeleton_Frost_Fighter, Skeleton_Knight, Skeleton_Soldier, Spider, Spider_Cave, Trork_Brawler, Trork_Hunter, Trork_Mauler, Trork_Sentry, Trork_Warrior, Wolf_Trork_Hunter, Zombie
- **Caster**: Feran_Windwalker, Goblin_Lobber, Outlander_Priest, Outlander_Sorcerer, Skeleton_Archer, Skeleton_Burnt_Alchemist, Skeleton_Mage, Spectre_Void, Spirit_Ember, Toad_Rhino_Magma, Trork_Doctor_Witch, Trork_Shaman, Wolf_Outlander_Priest, Wolf_Outlander_Sorcerer, Wolf_Trork_Shaman
- **Tough**: Goblin_Ogre, Leopard_Snow, Outlander_Berserker, Outlander_Marauder, Outlander_Peon, Scarak_Defender, Scarak_Fighter, Scarak_Fighter_Royal_Guard, Skeleton, Slug_Magma, Toad_Rhino, Wolf_Black, Wolf_Wife
- **Heavy**: Emberwulf, Goblin_Duke, Golem_Crystal_Flame, Wraith
- **Boss**: Ghoul, Golem_Crystal_Earth, Golem_Firesteel, Outlander_Brute, Scarak_Broodmother, Scorpion, Shadow_Knight, Spawn_Void, Trork_Chieftain, Werewolf, Yeti, Zombie_Aberrant

## Authored Gear Curves (derived)

Generated by `scripts/scaling/derive_gear_curves.py`. Weapon per-hit and armor DR
are driven by gear level instead of each item's asset stats (a corrective ratio at
damage time divides out the asset number and substitutes the family anchor).

- **Melee anchor:** 12.0 per-hit @ L1, solved as `npcScaledHp(61, 1) / (5 hits * weaponMult(1))`. All melee families share it (cadence is a single Agility throttle -> equal per-hit, equal DPS).
- **Ranged anchor:** 9.0 (x0.75 of melee). Bows/crossbows/guns fire from the Secondary slot on a charge/projectile cadence, not throttle-gated — **playtest** before locking.

Melee hits-to-kill an on-level Standard (TTK in seconds depends on the live attack
cadence; the throttle floor is 400 ms @ Agility 0, so balance is tracked on the
hits-to-kill axis and confirmed in playtest):

| Level | 1 | 15 | 30 | 45 | 60 | 80 | 100 |
|---|---|---|---|---|---|---|---|
| Hits | 5.1 | 5.5 | 5.9 | 6.3 | 6.5 | 6.5 | 6.5 |

**Armor DR budget:** 10% (L1) -> 55% (L100); per-slot shares Chest .40 / Legs .25 / Head .20 / Hands .15 sum to 1.0, so a full on-level set lands on the budget curve. Combined total capped at 65%.

| Level | 1 | 15 | 30 | 45 | 60 | 80 | 100 |
|---|---|---|---|---|---|---|---|
| Full-set DR | 10% | 12% | 18% | 28% | 41% | 51% | 55% |

Mapped families (23): Arrow, Axe, Battleaxe, Bomb, Bow, Club, Crossbow, Dagger, Gun, Kunai, Longsword, Mace, Magic, Scythe, Shield, Sickle, Soulblight, Spear, Spellbook, Staff, Stick, Sword, Wand.

## Rarity derivation (derive_rarity.py)

Base rarity distribution per source (pre-promotion):

- `MOB`: Common 70%, Uncommon 25%, Rare 5%
- `ELITE`: Uncommon 45%, Rare 40%, Epic 15%
- `BOSS`: Rare 40%, Epic 40%, Legendary 20%
- `CHEST_REGULAR`: Common 35%, Uncommon 40%, Rare 25%
- `CHEST_GOLDEN`: Uncommon 35%, Rare 45%, Epic 20%
- `CHEST_EPIC`: Rare 30%, Epic 45%, Legendary 25%
- `CHEST_LEGENDARY`: Rare 35%, Epic 40%, Legendary 25%
- `MERCHANT`: Common 20%, Uncommon 45%, Rare 25%, Epic 8%, Legendary 2%

Promotion gate chance: L0 5%, L10 6%, L25 9%, L50 15%, L100 15%

Attribute value by level: L1 +1, L10 +3, L25 +6, L50 +10, L75 +14, L100 +19

Price multipliers: Common x1, Uncommon x1.15, Rare x1.4, Epic x1.9, Legendary x3


## Authored Gear Curves (derived)

Generated by `scripts/scaling/derive_gear_curves.py`. Weapon per-hit and armor DR
are driven by gear level instead of each item's asset stats (a corrective ratio at
damage time divides out the asset number and substitutes the family anchor).

- **Melee anchor:** 12.0 per-hit @ L1, solved as `npcScaledHp(61, 1) / (5 hits * weaponMult(1))`. All melee families share it (cadence is a single Agility throttle -> equal per-hit, equal DPS).
- **Ranged anchor:** 9.0 (x0.75 of melee). Bows/crossbows/guns fire from the Secondary slot on a charge/projectile cadence, not throttle-gated — **playtest** before locking.

Melee hits-to-kill an on-level Standard (TTK in seconds depends on the live attack
cadence; the throttle floor is 400 ms @ Agility 0, so balance is tracked on the
hits-to-kill axis and confirmed in playtest):

| Level | 1 | 15 | 30 | 45 | 60 | 80 | 100 |
|---|---|---|---|---|---|---|---|
| Hits | 5.1 | 5.5 | 5.9 | 6.3 | 6.5 | 6.5 | 6.5 |

**Armor DR budget:** 10% (L1) -> 55% (L100); per-slot shares Chest .40 / Legs .25 / Head .20 / Hands .15 sum to 1.0, so a full on-level set lands on the budget curve. Combined total capped at 65%.

| Level | 1 | 15 | 30 | 45 | 60 | 80 | 100 |
|---|---|---|---|---|---|---|---|
| Full-set DR | 10% | 12% | 18% | 28% | 41% | 51% | 55% |

Mapped families (23): Arrow, Axe, Battleaxe, Bomb, Bow, Club, Crossbow, Dagger, Gun, Kunai, Longsword, Mace, Magic, Scythe, Shield, Sickle, Soulblight, Spear, Spellbook, Staff, Stick, Sword, Wand.

## Rarity derivation (derive_rarity.py)

Base rarity distribution per source (pre-promotion):

- `MOB`: Common 70%, Uncommon 25%, Rare 5%
- `ELITE`: Uncommon 45%, Rare 40%, Epic 15%
- `BOSS`: Rare 40%, Epic 40%, Legendary 20%
- `CHEST_REGULAR`: Common 35%, Uncommon 40%, Rare 25%
- `CHEST_GOLDEN`: Uncommon 35%, Rare 45%, Epic 20%
- `CHEST_EPIC`: Rare 30%, Epic 45%, Legendary 25%
- `CHEST_LEGENDARY`: Rare 35%, Epic 40%, Legendary 25%
- `MERCHANT`: Common 20%, Uncommon 45%, Rare 25%, Epic 8%, Legendary 2%

Promotion gate chance: L0 5%, L10 6%, L25 9%, L50 15%, L100 15%

Attribute spec (count @ per-attribute value range, rolled independently):

- `Common` ×1: L1 1-1, L10 3-3, L25 5-5, L50 10-10, L75 14-14, L100 19-19
- `Uncommon` ×1: L1 1-3, L10 3-5, L25 5-7, L50 10-12, L75 14-16, L100 19-21
- `Rare` ×2: L1 1-3, L10 3-5, L25 5-7, L50 10-12, L75 14-16, L100 19-21
- `Epic` ×3: L1 1-4, L10 3-6, L25 5-8, L50 10-13, L75 14-17, L100 19-22
- `Legendary` ×4-5: L1 2-4, L10 4-6, L25 6-8, L50 11-13, L75 15-17, L100 20-22
- `Relic` ×5-6: L1 3-5, L10 5-7, L25 7-9, L50 12-14, L75 16-18, L100 21-23
- `Abyssal` ×6-7: L1 4-6, L10 6-8, L25 8-10, L50 13-15, L75 17-19, L100 22-24

Price multipliers: Common x1, Uncommon x1.15, Rare x1.4, Epic x1.9, Legendary x3, Relic x4.5, Abyssal x7


## Rarity derivation (derive_rarity.py)

Base rarity distribution per source (pre-promotion):

- `MOB`: Common 70%, Uncommon 25%, Rare 5%
- `ELITE`: Uncommon 45%, Rare 40%, Epic 15%
- `BOSS`: Rare 40%, Epic 40%, Legendary 20%
- `CHEST_REGULAR`: Common 35%, Uncommon 40%, Rare 25%
- `CHEST_GOLDEN`: Uncommon 35%, Rare 45%, Epic 20%
- `CHEST_EPIC`: Rare 30%, Epic 45%, Legendary 25%
- `CHEST_LEGENDARY`: Rare 35%, Epic 40%, Legendary 25%
- `MERCHANT`: Common 20%, Uncommon 45%, Rare 25%, Epic 8%, Legendary 2%

Promotion gate chance: L0 5%, L10 6%, L25 9%, L50 15%, L100 15%

Attribute spec (count @ per-attribute value range, rolled independently):

- `Common` ×0-1: L1 1-2, L15 2-3, L30 3-4, L45 4-5, L60 5-6, L80 6-7
- `Uncommon` ×1: L1 1-3, L15 2-4, L30 3-5, L45 4-6, L60 5-7, L80 6-8
- `Rare` ×2: L1 1-3, L15 2-4, L30 3-5, L45 4-6, L60 5-7, L80 6-8
- `Epic` ×3: L1 1-4, L15 2-5, L30 3-6, L45 4-7, L60 5-8, L80 6-9
- `Legendary` ×4-5: L1 1-4, L15 2-5, L30 3-6, L45 4-7, L60 5-8, L80 6-9
- `Relic` ×5-6: L1 2-5, L15 3-6, L30 4-7, L45 5-8, L60 6-9, L80 7-10
- `Abyssal` ×6-7: L1 2-6, L15 3-7, L30 4-8, L45 5-9, L60 6-10, L80 7-11

Price multipliers: Common x1, Uncommon x1.15, Rare x1.4, Epic x1.9, Legendary x3, Relic x4.5, Abyssal x7


## Rarity derivation (derive_rarity.py)

Base rarity distribution per source (pre-promotion):

- `MOB`: Common 70%, Uncommon 25%, Rare 5%
- `ELITE`: Uncommon 45%, Rare 40%, Epic 15%
- `BOSS`: Rare 20%, Epic 45%, Legendary 25%, Relic 7%, Abyssal 3%
- `CHEST_REGULAR`: Common 35%, Uncommon 40%, Rare 25%
- `CHEST_GOLDEN`: Uncommon 35%, Rare 45%, Epic 20%
- `CHEST_EPIC`: Rare 30%, Epic 45%, Legendary 25%
- `CHEST_LEGENDARY`: Rare 35%, Epic 40%, Legendary 24%, Relic 0%, Abyssal 0%
- `MERCHANT`: Common 20%, Uncommon 44%, Rare 25%, Epic 8%, Legendary 2%, Relic 1%, Abyssal 0%

Promotion gate chance: L0 5%, L10 6%, L25 9%, L50 15%, L100 15%

Attribute spec (count @ per-attribute value range, rolled independently):

- `Common` ×0-1: L1 1-2, L15 2-3, L30 3-4, L45 4-5, L60 5-6, L80 6-7
- `Uncommon` ×1: L1 1-3, L15 2-4, L30 3-5, L45 4-6, L60 5-7, L80 6-8
- `Rare` ×2: L1 1-3, L15 2-4, L30 3-5, L45 4-6, L60 5-7, L80 6-8
- `Epic` ×3: L1 1-4, L15 2-5, L30 3-6, L45 4-7, L60 5-8, L80 6-9
- `Legendary` ×4-5: L1 1-4, L15 2-5, L30 3-6, L45 4-7, L60 5-8, L80 6-9
- `Relic` ×5-6: L1 2-5, L15 3-6, L30 4-7, L45 5-8, L60 6-9, L80 7-10
- `Abyssal` ×6-7: L1 2-6, L15 3-7, L30 4-8, L45 5-9, L60 6-10, L80 7-11

Price multipliers: Common x1, Uncommon x1.15, Rare x1.4, Epic x1.9, Legendary x3, Relic x4.5, Abyssal x7

