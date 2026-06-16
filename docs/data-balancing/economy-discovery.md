# Economy Discovery Report

Status: Discovery snapshot
Generated: 2026-06-16 by `scripts/scaling/discover_economy.py`
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
| 10 | 1749 | 5691 | 31353 | 1252 | 2025 | 3691 | 8.5x |
| 25 | 2209 | 9623 | 61038 | 1563 | 2527 | 4644 | 13.1x |
| 50 | 4787 | 24944 | 158215 | 2996 | 4838 | 9058 | 17.5x |
| 75 | 7898 | 43739 | 277431 | 4663 | 7526 | 14210 | 19.5x |
| 100 | 9307 | 56192 | 356412 | 5242 | 8460 | 16002 | 22.3x |

Top stat-priced weapons at gear level 75:

| Weapon | Source | Quality | ItemLevel | Buy |
|---|---|---|---|---|
| Weapon_Longsword_Praetorian | builtin | Common | 25 | 277431 |
| Weapon_Longsword_Onyxium | builtin | Epic | 50 | 163718 |
| Weapon_Battleaxe_Scythe_Void | builtin | Common | 30 | 158246 |
| Weapon_Axe_Onyxium | builtin | Epic | 50 | 150396 |
| Weapon_Club_Onyxium | builtin | Epic | 50 | 150396 |
| Mystic_Dominus_Maul | zets | Abyssal | 48 | 121988 |
| Weapon_Longsword_Mithril | builtin | Epic | 50 | 96648 |
| Weapon_Axe_Mithril | builtin | Epic | 50 | 90267 |
| Weapon_Club_Mithril | builtin | Epic | 50 | 90267 |
| WanMine_Soulblight_Longsword | wans | Relic | 60 | 84012 |

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
| Healing_Necklace_II | 125,000 | 62,500 |
| Speed_Boots_III | 70,000 | 35,000 |
| Vampire_Juice | 50,000 | 25,000 |
| Speed_Boots_II | 45,000 | 22,500 |
| Healing_Necklace_I | 45,000 | 22,500 |
| Immunity_Trap_Ring | 35,000 | 17,500 |
| Speed_Boots_I | 30,000 | 15,000 |
| Stat_Point_Token | 7,500 | 3,750 |
| Village_Warp | 5,000 | 2,500 |
| Palporter | 2,500 | 1,250 |

Merchants also reserve catalog slots for enchant scrolls (`CatalogGenerator.RESERVED_SCROLL_ITEM_IDS`, SimpleEnchantments) — an additional gold sink that is out of scope for this pass per the rebalance brief.

## 3. NPC Roster & Loot Coverage

- SpawnPool roles across 7 themes: **63**
- Loot tables shipped: **76** (72 base, 0 variant overlays, 4 chest)
- Roster roles with NO loot table: **0** — scaled NPCs drop NOTHING because `NpcLootSystem` suppresses engine drops for all scaled NPCs
- Summoned roles with NO loot table: **0**

### Roster roles missing a loot table

| Role | Themes (floor range) | Base HP |
|---|---|---|

### Summon-based roles missing a loot table

| Role |
|---|

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

- Roster roles spawning ELITE without `_Elite` overlay (falls back to base table): Feran_Longtooth, Feran_Windwalker, Goblin_Duke, Goblin_Lobber, Goblin_Ogre, Goblin_Scavenger, Golem_Crystal_Flame, Leopard_Snow, Outlander_Berserker, Outlander_Marauder, Scarak_Defender, Scarak_Fighter_Royal_Guard, Shadow_Knight, Skeleton_Burnt_Alchemist, Skeleton_Frost_Fighter, Skeleton_Knight, Skeleton_Mage, Spirit_Ember, Toad_Rhino, Toad_Rhino_Magma, Trork_Mauler, Trork_Sentry, Trork_Warrior, Wraith
- Roster roles spawning BOSS without `_Boss` overlay: Ghoul, Goblin_Duke, Golem_Crystal_Earth, Golem_Firesteel, Outlander_Brute, Scarak_Broodmother, Scorpion, Shadow_Knight, Spawn_Void, Toad_Rhino, Toad_Rhino_Magma, Trork_Chieftain, Werewolf, Yeti, Zombie_Aberrant

## 4. Drop Economics

- Base `DropChance` across 72 NPC tables: min 0.00, median 0.10, max 0.52
- Luck config (RpgConfig.json): LuckMaxDropBonus=0.3, LuckHalfPoint=20.0, LuckLevelsPerBonusRoll=15

### Current Luck curve vs target

| Luck | Drop bonus | Bonus rolls | Eff. chance (base 0.32) | Eff. chance (median base) |
|---|---|---|---|---|
| 0 | +0.0pp | 0 | 32% | 10% |
| 10 | +10.0pp | 0 | 42% | 20% |
| 20 | +15.0pp | 1 | 47% | 25% |
| 30 | +18.0pp | 2 | 50% | 28% |
| 50 | +21.4pp | 3 | 53% | 31% |

Target curve from rebalance brief: ~10% base, ~40% at Luck 30, ~80% at Luck 50. That curve is ACCELERATING; the current bonus is a saturating hyperbolic (`RpgStatEffects.hyperbolic`), which mathematically cannot produce it — the balancing phase needs a formula change, not just config tuning.

### Expected value per kill (gold EV includes ×npcLevel gold scaling)

| Table | Lvl | Chance L0 | Gold L0 | Items L0 | Gold L20 | Items L20 | Gold L50 | Items L50 |
|---|---|---|---|---|---|---|---|---|
| Emberwulf | 5 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Emberwulf | 15 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Emberwulf | 30 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Emberwulf | 50 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Fen_Stalker | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Fen_Stalker | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Fen_Stalker | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Fen_Stalker | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Feran_Longtooth | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Feran_Longtooth | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Feran_Longtooth | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Feran_Longtooth | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Feran_Sharptooth | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Feran_Sharptooth | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Feran_Sharptooth | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Feran_Sharptooth | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Feran_Windwalker | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Feran_Windwalker | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Feran_Windwalker | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Feran_Windwalker | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Ghoul | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Ghoul | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Ghoul | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Ghoul | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Goblin_Duke | 5 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Goblin_Duke | 15 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Goblin_Duke | 30 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Goblin_Duke | 50 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Goblin_Hermit | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Goblin_Hermit | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Goblin_Hermit | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Goblin_Hermit | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Goblin_Lobber | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Goblin_Lobber | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Goblin_Lobber | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Goblin_Lobber | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Goblin_Miner | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Goblin_Miner | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Goblin_Miner | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Goblin_Miner | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Goblin_Ogre | 5 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Goblin_Ogre | 15 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Goblin_Ogre | 30 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Goblin_Ogre | 50 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Goblin_Scavenger | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Goblin_Scavenger | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Goblin_Scavenger | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Goblin_Scavenger | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Goblin_Scrapper | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Goblin_Scrapper | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Goblin_Scrapper | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Goblin_Scrapper | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Golem_Crystal_Earth | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Golem_Crystal_Earth | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Golem_Crystal_Earth | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Golem_Crystal_Earth | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Golem_Crystal_Flame | 5 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Golem_Crystal_Flame | 15 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Golem_Crystal_Flame | 30 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Golem_Crystal_Flame | 50 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Golem_Firesteel | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Golem_Firesteel | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Golem_Firesteel | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Golem_Firesteel | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Larva_Silk | 5 | 0% | 0.0 | 0.00 | 0.0 | 0.00 | 0.0 | 0.00 |
| Larva_Silk | 15 | 0% | 0.0 | 0.00 | 0.0 | 0.00 | 0.0 | 0.00 |
| Larva_Silk | 30 | 0% | 0.0 | 0.00 | 0.0 | 0.00 | 0.0 | 0.00 |
| Larva_Silk | 50 | 0% | 0.0 | 0.00 | 0.0 | 0.00 | 0.0 | 0.00 |
| Leopard_Snow | 5 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Leopard_Snow | 15 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Leopard_Snow | 30 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Leopard_Snow | 50 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Outlander_Berserker | 5 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Outlander_Berserker | 15 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Outlander_Berserker | 30 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Outlander_Berserker | 50 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Outlander_Brute | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Outlander_Brute | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Outlander_Brute | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Outlander_Brute | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Outlander_Hunter | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Outlander_Hunter | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Outlander_Hunter | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Outlander_Hunter | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Outlander_Marauder | 5 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Outlander_Marauder | 15 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Outlander_Marauder | 30 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Outlander_Marauder | 50 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Outlander_Peon | 5 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Outlander_Peon | 15 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Outlander_Peon | 30 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Outlander_Peon | 50 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Outlander_Priest | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Outlander_Priest | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Outlander_Priest | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Outlander_Priest | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Outlander_Sorcerer | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Outlander_Sorcerer | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Outlander_Sorcerer | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Outlander_Sorcerer | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Outlander_Stalker | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Outlander_Stalker | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Outlander_Stalker | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Outlander_Stalker | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Risen_Knight | 5 | 52% | 13.0 | 0.21 | 33.5 | 0.55 | 73.4 | 1.21 |
| Risen_Knight | 15 | 52% | 39.0 | 0.21 | 100.5 | 0.55 | 220.3 | 1.21 |
| Risen_Knight | 30 | 52% | 78.0 | 0.21 | 201.0 | 0.55 | 440.6 | 1.21 |
| Risen_Knight | 50 | 52% | 130.0 | 0.21 | 335.0 | 0.55 | 734.3 | 1.21 |
| Scarak_Broodmother | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Scarak_Broodmother | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Scarak_Broodmother | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Scarak_Broodmother | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Scarak_Defender | 5 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Scarak_Defender | 15 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Scarak_Defender | 30 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Scarak_Defender | 50 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Scarak_Fighter | 5 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Scarak_Fighter | 15 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Scarak_Fighter | 30 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Scarak_Fighter | 50 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Scarak_Fighter_Royal_Guard | 5 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Scarak_Fighter_Royal_Guard | 15 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Scarak_Fighter_Royal_Guard | 30 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Scarak_Fighter_Royal_Guard | 50 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Scarak_Louse | 5 | 0% | 0.0 | 0.00 | 0.0 | 0.00 | 0.0 | 0.00 |
| Scarak_Louse | 15 | 0% | 0.0 | 0.00 | 0.0 | 0.00 | 0.0 | 0.00 |
| Scarak_Louse | 30 | 0% | 0.0 | 0.00 | 0.0 | 0.00 | 0.0 | 0.00 |
| Scarak_Louse | 50 | 0% | 0.0 | 0.00 | 0.0 | 0.00 | 0.0 | 0.00 |
| Scarak_Seeker | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Scarak_Seeker | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Scarak_Seeker | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Scarak_Seeker | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Scorpion | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Scorpion | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Scorpion | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Scorpion | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Shadow_Knight | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Shadow_Knight | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Shadow_Knight | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Shadow_Knight | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Skeleton | 5 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Skeleton | 15 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Skeleton | 30 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Skeleton | 50 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Skeleton_Archer | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Skeleton_Archer | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Skeleton_Archer | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Skeleton_Archer | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Skeleton_Burnt_Alchemist | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Skeleton_Burnt_Alchemist | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Skeleton_Burnt_Alchemist | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Skeleton_Burnt_Alchemist | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Skeleton_Fighter | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Skeleton_Fighter | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Skeleton_Fighter | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Skeleton_Fighter | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Skeleton_Frost_Fighter | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Skeleton_Frost_Fighter | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Skeleton_Frost_Fighter | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Skeleton_Frost_Fighter | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Skeleton_Knight | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Skeleton_Knight | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Skeleton_Knight | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Skeleton_Knight | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Skeleton_Mage | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Skeleton_Mage | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Skeleton_Mage | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Skeleton_Mage | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Skeleton_Soldier | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Skeleton_Soldier | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Skeleton_Soldier | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Skeleton_Soldier | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Slug_Magma | 5 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Slug_Magma | 15 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Slug_Magma | 30 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Slug_Magma | 50 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Spawn_Void | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Spawn_Void | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Spawn_Void | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Spawn_Void | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Spectre_Void | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Spectre_Void | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Spectre_Void | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Spectre_Void | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Spider | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Spider | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Spider | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Spider | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Spider_Cave | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Spider_Cave | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Spider_Cave | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Spider_Cave | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Spirit_Ember | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Spirit_Ember | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Spirit_Ember | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Spirit_Ember | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Toad_Rhino | 5 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Toad_Rhino | 15 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Toad_Rhino | 30 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Toad_Rhino | 50 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Toad_Rhino_Magma | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Toad_Rhino_Magma | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Toad_Rhino_Magma | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Toad_Rhino_Magma | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Trork_Brawler | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Trork_Brawler | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Trork_Brawler | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Trork_Brawler | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Trork_Chieftain | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Trork_Chieftain | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Trork_Chieftain | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Trork_Chieftain | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Trork_Doctor_Witch | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Trork_Doctor_Witch | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Trork_Doctor_Witch | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Trork_Doctor_Witch | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Trork_Guard | 5 | 38% | 4.4 | 0.09 | 12.2 | 0.24 | 27.4 | 0.55 |
| Trork_Guard | 15 | 38% | 9.3 | 0.17 | 26.0 | 0.48 | 58.3 | 1.08 |
| Trork_Guard | 30 | 38% | 18.7 | 0.17 | 52.0 | 0.48 | 116.7 | 1.08 |
| Trork_Guard | 50 | 38% | 31.1 | 0.17 | 86.7 | 0.48 | 194.5 | 1.08 |
| Trork_Hunter | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Trork_Hunter | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Trork_Hunter | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Trork_Hunter | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Trork_Mauler | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Trork_Mauler | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Trork_Mauler | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Trork_Mauler | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Trork_Sentry | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Trork_Sentry | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Trork_Sentry | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Trork_Sentry | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Trork_Shaman | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Trork_Shaman | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Trork_Shaman | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Trork_Shaman | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Trork_Warrior | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Trork_Warrior | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Trork_Warrior | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Trork_Warrior | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Werewolf | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Werewolf | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Werewolf | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Werewolf | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Wolf_Black | 5 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Wolf_Black | 15 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Wolf_Black | 30 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Wolf_Black | 50 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Wolf_Outlander_Priest | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Wolf_Outlander_Priest | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Wolf_Outlander_Priest | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Wolf_Outlander_Priest | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Wolf_Outlander_Sorcerer | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Wolf_Outlander_Sorcerer | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Wolf_Outlander_Sorcerer | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Wolf_Outlander_Sorcerer | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Wolf_Trork_Hunter | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Wolf_Trork_Hunter | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Wolf_Trork_Hunter | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Wolf_Trork_Hunter | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Wolf_Trork_Shaman | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Wolf_Trork_Shaman | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Wolf_Trork_Shaman | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Wolf_Trork_Shaman | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Wolf_Wife | 5 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Wolf_Wife | 15 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Wolf_Wife | 30 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Wolf_Wife | 50 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Wraith | 5 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Wraith | 15 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Wraith | 30 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Wraith | 50 | 12% | 0.0 | 0.12 | 0.0 | 0.54 | 0.0 | 1.34 |
| Wraith_Lantern | 5 | 0% | 0.0 | 0.00 | 0.0 | 0.00 | 0.0 | 0.00 |
| Wraith_Lantern | 15 | 0% | 0.0 | 0.00 | 0.0 | 0.00 | 0.0 | 0.00 |
| Wraith_Lantern | 30 | 0% | 0.0 | 0.00 | 0.0 | 0.00 | 0.0 | 0.00 |
| Wraith_Lantern | 50 | 0% | 0.0 | 0.00 | 0.0 | 0.00 | 0.0 | 0.00 |
| Yeti | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Yeti | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Yeti | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Yeti | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Zombie | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Zombie | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Zombie | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Zombie | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Zombie_Aberrant | 5 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Zombie_Aberrant | 15 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Zombie_Aberrant | 30 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |
| Zombie_Aberrant | 50 | 10% | 0.0 | 0.10 | 0.0 | 0.50 | 0.0 | 1.26 |

## 5. Income vs Prices Model (crude)

Estimated kills/floor = maxRooms × 0.6 × max(1, enemyDensity × maxEnemiesPerRoom). Respawning spawners, ambushes, and prop spawns (Scarak eggs) are NOT counted, so real income per floor is higher; treat ratios as upper bounds on grind.

| Floor | Themes | Est. kills | Gold/kill (Luck 0) | Gold/floor | Median weapon @F+3 | Median armor @F+3 | Floors per weapon |
|---|---|---|---|---|---|---|---|
| 1 | crypt | 12 | 0.0 | 0 | 439 | 833 | inf |
| 3 | crypt | 24 | 0.0 | 0 | 509 | 869 | inf |
| 5 | crypt,volcanic | 133 | 0.0 | 0 | 750 | 869 | inf |
| 7 | crypt,hive,volcanic | 200 | 0.0 | 0 | 750 | 869 | inf |
| 10 | crypt,hive,temple_dark,volcanic | 223 | 0.0 | 0 | 880 | 921 | inf |
| 15 | arcane,volcanic,hive,crypt,temple_dark | 230 | 0.0 | 0 | 1235 | 1062 | inf |
| 20 | crypt,arcane,hive,mushroom,temple_dark,volcanic | 87 | 0.0 | 0 | 1724 | 1424 | inf |
| 25 | crypt,arcane,hive,mushroom,temple_dark,volcanic | 48 | 0.0 | 0 | 2127 | 1746 | inf |
| 30 | crypt,arcane,hive,mushroom,temple_dark,volcanic | 48 | 0.0 | 0 | 3351 | 2569 | inf |
| 40 | crypt,arcane,hive,mushroom,temple_dark,volcanic | 48 | 0.0 | 0 | 7026 | 3388 | inf |
| 45 | crypt,arcane,hive,mushroom,temple_dark,volcanic | 24 | 0.0 | 0 | 8486 | 3388 | inf |
| 50 | crypt,arcane,hive,mushroom,temple_dark,volcanic | 48 | 0.0 | 0 | 10710 | 4269 | inf |
| 55 | crypt,arcane,hive,mushroom,temple_dark,volcanic | 24 | 0.0 | 0 | 9194 | 4676 | inf |
| 60 | crypt,arcane,hive,mushroom,temple_dark,volcanic | 48 | 0.0 | 0 | 29211 | 5277 | inf |

## 5b. Per-Theme Income Projection (Luck 0, current loot tables)

Gold per floor by theme. `—` = theme not in that floor's `theme.variants`. Hive includes Scarak_Louse spawns from egg props (~0.9 eggs per combat room). Roles with no loot table contribute ZERO gold, which drags themes with poor coverage down — see coverage below.

| Floor | Est. kills | Arcane | Crypt | Hive | Mine | Mushroom | Temple_Dark | Volcanic |
|---|---|---|---|---|---|---|---|---|
| 1 | 12 | — | 0 | — | — | — | — | — |
| 3 | 24 | — | 0 | — | — | — | — | — |
| 5 | 133 | — | 0 | — | — | — | — | 0 |
| 7 | 200 | — | 0 | 0 | — | — | — | 0 |
| 10 | 223 | — | 0 | 0 | — | — | 0 | 0 |
| 15 | 230 | 0 | 0 | 0 | — | — | 0 | 0 |
| 20 | 87 | 0 | 0 | 0 | — | 0 | 0 | 0 |
| 25 | 48 | 0 | 0 | 0 | — | 0 | 0 | 0 |
| 30 | 48 | 0 | 0 | 0 | — | 0 | 0 | 0 |
| 40 | 48 | 0 | 0 | 0 | — | 0 | 0 | 0 |
| 45 | 24 | 0 | 0 | 0 | — | 0 | 0 | 0 |
| 50 | 48 | 0 | 0 | 0 | — | 0 | 0 | 0 |
| 55 | 24 | 0 | 0 | 0 | — | 0 | 0 | 0 |
| 60 | 48 | 0 | 0 | 0 | — | 0 | 0 | 0 |

Item drops per floor by theme (inventory pressure at Luck 0):

| Floor | Est. kills | Arcane | Crypt | Hive | Mine | Mushroom | Temple_Dark | Volcanic |
|---|---|---|---|---|---|---|---|---|
| 1 | 12 | — | 1.2 | — | — | — | — | — |
| 3 | 24 | — | 2.4 | — | — | — | — | — |
| 5 | 133 | — | 13.3 | — | — | — | — | 13.3 |
| 7 | 200 | — | 20.0 | 12.9 | — | — | — | 20.0 |
| 10 | 223 | — | 22.7 | 19.8 | — | — | 18.3 | 23.3 |
| 15 | 230 | 25.2 | 23.4 | 17.4 | — | — | 19.5 | 24.4 |
| 20 | 87 | 9.7 | 8.9 | 6.4 | — | 8.7 | 7.5 | 9.4 |
| 25 | 48 | 5.3 | 4.9 | 3.7 | — | 4.9 | 4.1 | 5.1 |
| 30 | 48 | 5.3 | 4.9 | 4.3 | — | 4.9 | 4.2 | 5.2 |
| 40 | 48 | 5.2 | 4.9 | 4.2 | — | 4.9 | 4.9 | 5.3 |
| 45 | 24 | 2.7 | 2.5 | 2.1 | — | 2.4 | 2.5 | 2.7 |
| 50 | 48 | 5.4 | 4.9 | 4.2 | — | 4.9 | 5.0 | 5.3 |
| 55 | 24 | 2.7 | 2.5 | 2.6 | — | 2.5 | 2.5 | 2.6 |
| 60 | 48 | 5.4 | 4.9 | 5.1 | — | 4.9 | 5.0 | 5.3 |

Loot-table coverage by theme (worst floor; % of spawn weight that has a table):

| Theme | Coverage | Roles contributing zero |
|---|---|---|
| Arcane | 100% | — |
| Crypt | 100% | — |
| Hive | 100% | — |
| Mushroom | 100% | — |
| Temple_Dark | 100% | — |
| Volcanic | 100% | — |

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
| Hits | 5.1 | 4.3 | 4.6 | 5.4 | 6.0 | 6.1 | 5.7 |

**Armor DR budget:** 10% (L1) -> 62% (L100); per-slot shares Chest .40 / Legs .25 / Head .20 / Hands .15 sum to 1.0, so a full on-level set lands on the budget curve. Combined total capped at 65%.

| Level | 1 | 15 | 30 | 45 | 60 | 80 | 100 |
|---|---|---|---|---|---|---|---|
| Full-set DR | 10% | 15% | 22% | 32% | 43% | 55% | 62% |

Mapped families (23): Arrow, Axe, Battleaxe, Bomb, Bow, Club, Crossbow, Dagger, Gun, Kunai, Longsword, Mace, Magic, Scythe, Shield, Sickle, Soulblight, Spear, Spellbook, Staff, Stick, Sword, Wand.

## Income & gold-split derivation (derive_income.py)

I(F) is the value-neutral net income per floor (direct gold + full sell-fodder at 0.5 resale). Per the smooth-budget decision a monotonic spine I_smooth(F) = 690.3·F^0.969 is fit on floors 1-30 and extended through F70, so the lumpy floor texture (the F5-15 density spike, etc.) does not distort the progression curve; respawn, gear price, and custom-item prices all drive off I_smooth, not raw kills.

Smooth-fit mean relative error: floors 1-30 75.1% (small — tracks the well-tuned band), floors 31-100 106.6% (large by design — those floors sagged; extending the curve is the fix).

Gold faucet: solve scaled the W5 floors by 1.783 (applied 1.500, clamped >= 1 so lowering gold never breaches the Luck budget). Direct-gold vs the 50% target post-scale: floors 1-30 124%, floors 31-100 54%. Respawn = 1.25·I_smooth as a 14-band schedule, restart 0.6x.

| Floor | est kills | raw I(F) | smooth I(F) | respawn (shipped) |
|---|---|---|---|---|
| 1 | 12 | 381 | 690 | 863 |
| 3 | 24 | 960 | 2002 | 2502 |
| 5 | 133 | 6664 | 3284 | 4105 |
| 7 | 200 | 10905 | 4550 | 5688 |
| 10 | 223 | 14434 | 6429 | 8037 |
| 15 | 230 | 19974 | 9524 | 11905 |
| 20 | 87 | 10517 | 12587 | 15733 |
| 25 | 48 | 6762 | 15625 | 19532 |
| 30 | 48 | 8515 | 18645 | 23306 |
| 40 | 48 | 12773 | 24641 | 30801 |
| 45 | 24 | 8953 | 27620 | 34525 |
| 50 | 48 | 19514 | 30589 | 38237 |
| 55 | 24 | 14161 | 33550 | 41937 |
| 60 | 48 | 58800 | 36501 | 45627 |

## Pricing & variant derivation (derive_prices.py)

Single combat-value price axis: weapon value = family anchor x weaponMult(level) (a DPS-equivalent), armor value = slot HP share + k_dr x DR share / (1 - totalDR) (an effective-HP equivalent). k_dr = 12.000; achieved weapon/armor median value ratio 1.32 at L50. Armor leans on the authored HP budget (a W3 asset), so the DR term has limited leverage and k_dr can saturate — the win is a BOUNDED band replacing the old weapon-unbounded / armor-capped 6x..18x gap, not perfect parity.

Gold mapping price = round(combatValue^1.6 x 130.0967), solved so the median on-level price tracks 2.5 x I_smooth(F) (median gear ~ 2-3x a floor of income — the gear-swap-cadence pillar). The HP cap was removed, so MaxScaledHp is no longer written; the Elite/Boss multiplier tables are owned by derive_difficulty.py (the per-floor difficulty lever) and are not touched here.

| Floor | new median price | current price | I_smooth(F) | new/I_smooth |
|---|---|---|---|---|
| 1 | 6934 | 585 | 690 | 10.05 |
| 3 | 7918 | 646 | 2002 | 3.96 |
| 5 | 8979 | 744 | 3284 | 2.73 |
| 7 | 10122 | 869 | 4550 | 2.22 |
| 10 | 12006 | 869 | 6429 | 1.87 |
| 15 | 15677 | 1051 | 9524 | 1.65 |
| 20 | 20169 | 1441 | 12587 | 1.60 |
| 25 | 25708 | 1594 | 15625 | 1.65 |
| 30 | 32557 | 1972 | 18645 | 1.75 |
| 40 | 51127 | 3041 | 24641 | 2.07 |
| 45 | 63009 | 4838 | 27620 | 2.28 |
| 50 | 76350 | 5233 | 30589 | 2.50 |
| 55 | 90629 | 8088 | 33550 | 2.70 |
| 60 | 105183 | 19776 | 36501 | 2.88 |

Custom big-ticket prices solved against cumulative income (30-45k tier anchored to cumI(25); ordering preserved):

| Item | Buy | reachable ~floor |
|---|---|---|
| Immunity_Trap_Ring | 180500 | 24 |
| Speed_Boots_I | 154500 | 22 |
| Speed_Boots_II | 232000 | 27 |
| Speed_Boots_III | 361000 | 34 |
| Healing_Necklace_I | 232000 | 27 |
| Healing_Necklace_II | 644500 | 45 |
| Vampire_Juice | 257500 | 29 |
| Stat_Point_Token | 38500 | 11 |
| Palporter | 13000 | 6 |
| Village_Warp | 26000 | 9 |

## Encounter pacing & difficulty derivation (derive_difficulty.py)

Challenge(F) is the per-floor threat budget (weight-averaged on-level archetype HP x damage times est_kills). Per the smooth-budget decision a monotonic spine Challenge(F) = 8647.4*F^1.287 is fit on floors 1-30 and extended through F70, so the lumpy floor texture (the F5-15 density spike, etc.) stays hand-authored while the difficulty SPINE is well-behaved. The per-floor knobs close the gap: a sparse floor gets more Elites and/or a flat multiplier; a dense wave floor gets ~none.

Smooth-fit mean relative error: floors 1-30 71.0%, floors 31-100 34.4% (the deep floors sagged in raw threat; extending the spine is the fix, mirroring the income derivation).

Realization policy (resolved): raise the Elite rate first (visible variety) up to 0.35, then a flat difficultyMult in [1, 3] for the remainder. Elite/NORMAL threat ratio 3.51. Inert default (eliteRate 0.0, difficultyMult 1.0) leaves a floor unchanged until these overrides are authored.

Variant tables (re-derived; the difficulty lever now the HP cap is removed): an Elite is ~2x a same-archetype NORMAL kill (HP-driven TTK), and the Boss HP ramps to a deliberate, uncapped ceiling (F60 boss ~49,122 HP).

| Floor | est kills | raw Challenge | smooth Challenge | eliteRate | difficultyMult |
|---|---|---|---|---|---|
| 1 | 12 | 6168 | 8647 | 0.160 | 1.000 |
| 3 | 24 | 13169 | 35560 | 0.350 | 1.437 |
| 5 | 133 | 139163 | 68627 | 0.000 | 1.000 |
| 7 | 200 | 223386 | 105820 | 0.000 | 1.000 |
| 10 | 223 | 343706 | 167468 | 0.000 | 1.000 |
| 15 | 230 | 582989 | 282208 | 0.000 | 1.000 |
| 20 | 87 | 346507 | 408668 | 0.071 | 1.000 |
| 25 | 48 | 247904 | 544626 | 0.350 | 1.169 |
| 30 | 48 | 372181 | 688666 | 0.339 | 1.000 |
| 40 | 48 | 919165 | 997264 | 0.034 | 1.000 |
| 45 | 24 | 686175 | 1160502 | 0.275 | 1.000 |
| 50 | 48 | 1957424 | 1329040 | 0.000 | 1.000 |
| 55 | 24 | 1331046 | 1502492 | 0.051 | 1.000 |
| 60 | 48 | 3323275 | 1680536 | 0.000 | 1.000 |

Re-derived Elite variant steps (HpMult ~ TTK ratio vs a same-archetype NORMAL):

| MinLevelRatio | HpMult | DamageMult |
|---|---|---|
| 0.75 | 2.5 | 2 |
| 0.5 | 2.3 | 1.8 |
| 0.3333 | 2.1 | 1.6 |
| 0.1667 | 2 | 1.4 |
| 0 | 1.8 | 1.25 |

Re-derived Boss variant steps (HpMult solved from explicit boss-HP targets at each band's start level against the Boss anchor + level curve):

| MinLevelRatio | start level | HpMult | DamageMult | boss HP @ start |
|---|---|---|---|---|
| 0.75 | 75 | 39.44 | 5.2 | 61,079 |
| 0.5 | 50 | 39.44 | 4.5 | 37,997 |
| 0.3333 | 33 | 17.38 | 3.2 | 8,998 |
| 0.1667 | 17 | 8.76 | 2.5 | 2,499 |
| 0 | 1 | 1.55 | 2 | 299 |

## Luck power-budget derivation (derive_luck_budget.py)

Promotion gate: L0 5.0%, L50 13.0% (BaseChance 0.05, LuckCoeff 0.08, LuckExp 1.2, tiers [(1, 85), (2, 12), (3, 3)]). Rarity-value uplift 1.020x.

Drop chance (@0.10 base) and promotion gate across Luck:

| Luck | 0 | 10 | 20 | 30 | 40 | 50 |
|---|---|---|---|---|---|---|
| drop | 0.10 | 0.15 | 0.26 | 0.41 | 0.59 | 0.80 |
| gate | 0.05 | 0.06 | 0.08 | 0.09 | 0.11 | 0.13 |

Total loot-value EV(50)/EV(0) per archetype (budget 6x, gear value grounded in on-level buy price x 0.5 resale + template gold):

| archetype | L10 | L30 | L50 | worst | verdict |
|---|---|---|---|---|---|
| Standard | 5.51 | 4.55 | 4.61 | 5.51 | PASS |
| Caster | 5.51 | 4.55 | 4.61 | 5.51 | PASS |
| Tough | 5.04 | 4.27 | 4.32 | 5.04 | PASS |
| Heavy | 4.60 | 3.79 | 3.84 | 4.60 | PASS |
| Boss | 4.43 | 3.49 | 3.54 | 4.43 | PASS |

The drop-chance curve alone is ~8x at the 0.10 base, so the budget holds only because the Luck-independent gold faucet dilutes the total; promotion is kept gentle so the rarity-mix uplift adds little. Gold quantities here are starting floors — the income pass refines them.
