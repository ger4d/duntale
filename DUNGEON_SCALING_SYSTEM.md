# Progressive Monster / Weapon / Armor Scaling System — v3-zsquad

> **Status**: DRAFT — approved for implementation  
> **Scope**: Dungeon mode, levels 1–60  
> **Date**: 2026-02-25

## TL;DR

A 1–60 level scaling system for NPCs, weapons, and armor in v3-zsquad's dungeon mode. NPC health is scaled at spawn via `EntityStatMap.putModifier()`. NPC damage and player gear stats are scaled via a custom `DamageEventSystem` that intercepts all combat damage and applies level-based multipliers — NPC levels stored in a `ConcurrentHashMap<UUID, Integer>`, gear levels stored in `ItemStack.metadata`. Scaling data is precomputed by a Python/uv tool into SQLite, then queried by the Java plugin on first access and cached in-memory.

The system covers ~200 NPC archetypes across 5 HP tiers (7–400), 24 weapon families (iLvl 3–50), and 12 armor sets (resistance 3.6%–14.4%/slot).

---

## Part 1 — Asset Discovery & Runtime Mutability

### 1.1 NPC Research

NPC definitions live at `HytaleAssets/Server/NPC/Roles/<Category>/<SubCategory>/<NPC>.json` using an **inheritance system**:

Concrete NPC (Variant)
└── Reference → Template (e.g. Template_Predator, Template_Intelligent)
└── Reference → Component files (AI behavior states, attack sequences)
└── attack interactions at Server/Item/Interactions/NPCs/


Each NPC JSON can `Modify` fields from its `Reference` template. Stats like `MaxHealth` and `MaxSpeed` are typically on the concrete NPC, while damage is defined in `_InteractionVars.Melee_Damage.BaseDamage.Physical` — sometimes inline, sometimes in the template, sometimes in a separate interaction file.

~200 unique NPC archetypes exist, organized into 5 tiers:

| Tier | HP Range | Examples |
|------|----------|----------|
| Fodder | 7–36 | Rat (21), Skeleton_Fighter (36), Larva_Silk (25) |
| Standard | 38–61 | Zombie (49), Trork_Warrior (61), Spider (61), Crawler_Void (74) |
| Tough | 74–126 | Skeleton_Knight (74), Bear_Grizzly (124), Zombie_Burnt (126) |
| Elite | 145–283 | Golem_Crystal_Earth (160), Werewolf (283), Ghoul (193) |
| Boss | 320–400 | Dragon_Fire/Frost (400), Shadow_Knight (400), Rex_Cave (400) |

**Problem**: The existing `npc-research-temp/all-npcs-stats.md` has many missing values (speed, damage, attack distance) because it doesn't resolve the inheritance chain. The `parse_assets.py` script in Part 2 (Step 6) is specifically designed to solve this — it traverses `Reference` → Template → Component → interaction files to fully resolve every NPC's stats. See Part 2, Step 6 for details.

### 1.2 Weapon Research

24 weapon families under `HytaleAssets/Server/Item/Items/Weapon/`. Weapons use a `Parent` inheritance system and define damage per attack move via `InteractionVars → <AttackName>_Damage → DamageCalculator → BaseDamage.Physical`.

Key progression (swords, Swing_Left damage):

| Material | Quality | iLvl | Swing L/R | Swing Down | Thrust | Signature |
|----------|---------|------|-----------|------------|--------|-----------|
| Crude | Common | 3 | 6 | 10 | 16 | 36 |
| Copper | Common | 10 | 8 | 14 | 21 | 50 |
| Bronze | Uncommon | 25 | 9 | 16 | 24 | 50 |
| Iron | Uncommon | 20 | 10 | 18 | 26 | 56 |
| Thorium | Rare | 30 | 12 | 22 | 32 | 70 |
| Cobalt | Rare | 35 | 12 | 22 | 32 | 70 |
| Adamantite | Rare | 40 | 14 | 28 | 41 | 86 |
| Mithril | Epic | 50 | 18 | 34 | 51 | 110 |

Other weapon types: Axes (absolute damage, ±20% variance), Daggers (fast dual-wield), Maces (slow charged), Battleaxes (heavy 2H), Longswords, Spears (throwable), Shortbows (5-stage charge), Crossbows, Staves (mana-based), Wands, Shields (defensive).

### 1.3 Armor Research

12 armor sets, 4 slots each (Head/Chest/Hands/Legs). Uses **multiplicative** damage resistance.

Full set comparison (total Physical Resist / +Health):

| Set | Quality | iLvl | Total Phys Resist | Total +Health | Special |
|-----|---------|------|-------------------|---------------|---------|
| Copper | Common | 10 | ~19% | +33 | — |
| Leather_Light | Common | 15 | ~28% | +52 | — |
| Iron | Uncommon | 20 | ~28% | +52 | — |
| Bronze | Uncommon | 25 | ~28% | +52 | — |
| Trork | Uncommon | 25 | ~28% | +52 | Charged +16% |
| Thorium | Rare | 30 | ~32% | +61 | Poison resist |
| Cobalt | Rare | 35 | ~32% | +61 | Signature +16% |
| Adamantite | Rare | 40 | ~36% | +71 | Light +16% |
| Mithril | Epic | 50 | ~36% | +71 | — |
| Onyxium | Epic | 50 | ~28% | +52 | +Mana |

Slot distribution is consistent: Chest ~36%, Legs ~28%, Head ~20%, Hands ~16%.

### 1.4 Runtime Mutability Classification

| Field | Mutable? | Method | Notes |
|-------|----------|--------|-------|
| NPC Max Health | **YES** | `EntityStatMap.putModifier("LevelScale", StaticModifier(MAX, ADDITIVE, delta))` | Stacks with engine's `"NPC_Max"` modifier |
| NPC Current Health | **YES** | `EntityStatMap.setStatValue()` / `maximizeStatValue()` | |
| NPC Damage Output | **YES (indirect)** | `DamageEventSystem` → `damage.setAmount(scaled)` in FilterDamage group | Check `damage.getSource() instanceof Damage.EntitySource` → resolve `NPCEntity` |
| NPC Speed | **NO (skipped)** | Requires `EntityEffect` JSON assets with `HorizontalSpeedMultiplier` | Deferred to future iteration |
| Weapon Base Damage | **NO (asset)** | `DamageCalculator.BaseDamage` is interaction-defined, shared/immutable | Scale via damage event interception using `ItemStack.metadata` |
| Armor Resistance | **NO (asset)** | `ItemArmor.DamageResistance` is asset-defined | Scale via damage event interception |
| Item Durability | **YES** | `ItemStack.durability` / `maxDurability` | Per-stack, direct setter |
| Entity Scale | **YES** | `EntityScaleComponent.setScale(float)` | Visual scaling for boss/elite variants |
| Entity Display Name | **YES** | Replace `DisplayNameComponent` via `putComponent()` | For level prefixes like `"[Lv.30] Zombie"` |
| Any Entity Stat (Oxygen, Stamina, Mana, etc.) | **YES** | `EntityStatMap.putModifier()` / `setStatValue()` | Full stat system access |

### 1.5 Key API Details

**Health modification at spawn** (in `postSpawn` callback):
- Get `EntityStatMap` via `EntityStatsModule.get().getEntityStatMapComponentType()`
- `statMap.putModifier(healthIdx, "LevelScale", new StaticModifier(MAX, ADDITIVE, additionalHP))`
- `statMap.maximizeStatValue(healthIdx)` to fill to new max
- Stat index: `EntityStatType.getAssetMap().getIndex("Health")`

**Damage interception**:
- `DamageEventSystem` in `DamageModule.get().getFilterDamageGroup()`
- `damage.getSource() instanceof Damage.EntitySource es` → `es.getRef()` for attacker entity
- `damage.setAmount(float)` to modify, `damage.cancel()` to prevent

**Gear metadata**:
- `ItemStack.withMetadata(String key, Codec<T>, T value)` → returns new ItemStack (immutable pattern)
- `ItemStack.getFromMetadataOrNull(String key, Codec<T> codec)` → typed read

---

## Part 2 — SQLite Data Layer & Asset Parsing

### 2.1 Project Structure

v3-zsquad/scripts/scaling/
├── pyproject.toml # uv project, stdlib only (sqlite3)
├── parse_assets.py # Step 6: Scan HytaleAssets → populate *_base tables
├── generate_scaling.py # Step 7: Compute 60 levels per entity → *_scaled tables
├── simulate_combat.py # Step 8: TTK/EHP simulations → markdown report
└── scaling.db # Output SQLite database


### 2.2 Schema DDL

```sql
-- Base stat tables (populated by parse_assets.py)
CREATE TABLE monsters_base (
    npc_id          TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    category        TEXT NOT NULL,       -- Undead, Intelligent, Creature, Boss, Void, etc.
    tier            TEXT NOT NULL,       -- Fodder, Standard, Tough, Elite, Boss
    base_hp         INTEGER NOT NULL,
    base_damage     REAL NOT NULL,       -- Primary melee physical damage
    base_speed      REAL,                -- MaxSpeed from role
    attack_distance REAL,                -- Melee attack distance
    ai_template     TEXT,                -- Template_Predator, Template_Intelligent, etc.
    view_range      REAL,
    hearing_range   REAL,
    extra_json      TEXT                 -- JSON blob for additional parameters
);

CREATE TABLE weapons_base (
    weapon_id       TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    family          TEXT NOT NULL,       -- Sword, Axe, Daggers, Mace, etc.
    quality         TEXT NOT NULL,       -- Common, Uncommon, Rare, Epic, Legendary
    item_level      INTEGER NOT NULL,
    base_damage     REAL NOT NULL,       -- Primary attack damage (Swing L/R for swords)
    attack_moves_json TEXT               -- JSON: all attack moves with individual damages
);

CREATE TABLE armor_base (
    armor_id        TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    slot            TEXT NOT NULL,       -- Head, Chest, Hands, Legs
    quality         TEXT NOT NULL,
    item_level      INTEGER NOT NULL,
    phys_resist     REAL NOT NULL,       -- Multiplicative decimal (e.g. 0.064 = 6.4%)
    proj_resist     REAL NOT NULL,
    health_bonus    INTEGER NOT NULL DEFAULT 0,
    special         TEXT                 -- Set bonus description
);

-- Precomputed scaling tables (generated by generate_scaling.py)
CREATE TABLE monsters_scaled (
    npc_id          TEXT NOT NULL,
    level           INTEGER NOT NULL,
    scaled_hp       INTEGER NOT NULL,
    scaled_damage   REAL NOT NULL,
    damage_mult     REAL NOT NULL,       -- Multiplier applied to base damage
    effective_dps   REAL,                -- Estimated DPS output
    elite_hp        INTEGER,             -- HP with elite multiplier
    elite_damage    REAL,                -- Damage with elite multiplier
    modifiers_json  TEXT,                -- JSON: per-stat modifiers applied
    PRIMARY KEY (npc_id, level)
);

CREATE TABLE weapons_scaled (
    weapon_id       TEXT NOT NULL,
    level           INTEGER NOT NULL,
    damage_mult     REAL NOT NULL,       -- Multiplier applied to all weapon damage
    effective_dps   REAL,                -- Estimated sustained DPS
    modifiers_json  TEXT,
    PRIMARY KEY (weapon_id, level)
);

CREATE TABLE armor_scaled (
    armor_id        TEXT NOT NULL,
    level           INTEGER NOT NULL,
    resist_mult     REAL NOT NULL,       -- Multiplier on base resistance
    effective_dr    REAL NOT NULL,       -- Final damage reduction %
    effective_ehp_bonus REAL,            -- Effective HP contribution
    modifiers_json  TEXT,
    PRIMARY KEY (armor_id, level)
);

-- Configuration for regeneration audit
CREATE TABLE scaling_config (
    key             TEXT PRIMARY KEY,
    value           TEXT NOT NULL
);

-- Indexes for fast lookup
CREATE INDEX idx_monsters_scaled_level ON monsters_scaled(level, npc_id);
CREATE INDEX idx_weapons_scaled_level ON weapons_scaled(level, weapon_id);
CREATE INDEX idx_armor_scaled_level ON armor_scaled(level, armor_id);

Extensibility: Adding new NPCs/weapons/armor = add rows to *_base tables, re-run generate_scaling.py. The modifiers_json column stores arbitrary per-level overrides (e.g., boss-specific rules). scaling_config stores formula parameters so regeneration is auditable.

2.3 Indexing Strategy
Primary keys (entity_id, level) on scaled tables for exact lookups
Secondary index (level, entity_id) for "get all scaled stats at level X" queries (dungeon initialization)
SQLite page size aligned to 4KB (default) — each scaled table ~200 entities × 60 levels = 12,000 rows, trivial
2.4 Regeneration Safety
generate_scaling.py is idempotent: it drops and recreates all *_scaled tables on every run. The *_base tables are never modified by the generator — only by parse_assets.py. This means you can safely re-run scaling generation after tuning formulas without re-parsing assets.

2.5 Java-Side Caching
New ScalingDataCache class:

Opens SQLite connection on construction (path from config or hardcoded plugins/ZSquad/scaling.db)
On first access per (npcId, level): executes SELECT * FROM monsters_scaled WHERE npc_id = ? AND level = ?, caches result in ConcurrentHashMap<String, MonsterScaledData> keyed by "npcId:level"
Same pattern for weapons and armor
Cache size bounded by actual spawns (~200 NPCs × 60 levels = 12,000 max entries, ~1MB)
Uses try-with-resources + PreparedStatement for all DB access
Graceful degradation: if DB missing, log warning and return base stats (multiplier = 1.0)
2.6 parse_assets.py — Full Inheritance Resolution (Critical Step)
This is the most complex script. It must fully resolve the NPC inheritance chain to fill in all missing stat values. The existing all-npcs-stats.md is incomplete because it only reads top-level NPC files without traversing Reference → Template → Component → interaction chains.

Inheritance resolution algorithm:

Load all NPC JSON files from Roles recursively
Build a reference graph: For each NPC with "Type": "Variant", follow its "Reference" field to the template. Templates may themselves reference other templates.
Deep merge: Apply the NPC's "Modify" block on top of the resolved template. Fields in Modify override template values; unspecified fields inherit.
Resolve _InteractionVars: Damage is defined in _InteractionVars.Melee_Damage (or similar keys). If not present on the NPC, check the template. If the template references a Component, check the Component file.
Resolve attack interactions: For NPCs whose damage comes from Server/Item/Interactions/NPCs/<NPC_Name>/ files, parse those JSON files to extract DamageCalculator.BaseDamage.Physical.
Resolve Parameters: Some NPCs use parameterized values (e.g., "$MaxHealth": { "Description": "...", "Value": 49 }). These are referenced in Modify blocks as "$MaxHealth" and must be substituted.
Handle special cases:
Patrol/Wander variants share parent stats (skip or merge)
Template_Placeholder (Dragons) — stats come from the boss encounter system
NPCs with "Type": "Component" are AI pieces, not spawnable entities (skip)
NPCs with "Type": "Abstract" are templates (include as reference, don't add to base table)
Output: Populates monsters_base table with fully resolved stats for every spawnable Variant NPC.

Weapon and armor parsing is simpler — Parent inheritance is typically one level deep, and stats are explicitly defined per material tier. Parse Weapon and Armor → populate weapons_base and armor_base.

Part 3 — Scaling & Balancing Algorithm (Level 1–60)
3.1 Scaling Model: Hybrid Sigmoid
Linear growth from 1–20 (tutorial/early game), soft exponential 20–45 (mid-game power ramp), dampened logarithmic 45–60 (endgame plateau). This avoids exponential runaway while preserving the "getting stronger" feel.

Base formula (applies to HP, damage, and multipliers):

`Scaled(L) = Base × (1 + (k - 1) × S(L))`

Where `S(L)` is the normalized sigmoid scaling factor:

`S(L) = 1 / (1 + e^(-0.12 × (L - 30)))`

And `k` is the max multiplier at level 60 (different per stat type).

The sigmoid is centered at L=30 (midpoint of 1-60), with steepness 0.12 giving a smooth S-curve. At L=1, `S(1) ≈ 0.03`; at L=30, `S(30) = 0.50`; at L=60, `S(60) ≈ 0.97`.

3.2 Monster Formulas
| Stat | `k` (max mult) | Formula |
|---|---|---|
| HP | 8.0 | `HP(L) = BaseHP × (1 + 7.0 × S(L))` |
| Damage | 5.0 | `Dmg(L) = BaseDmg × (1 + 4.0 × S(L))` |
Scaling factor table:

| Level | `S(L)` | HP Mult | Damage Mult |
|---|---|---|---|
| 1 | 0.029 | ×1.20 | ×1.12 |
| 10 | 0.083 | ×1.58 | ×1.33 |
| 15 | 0.142 | ×1.99 | ×1.57 |
| 20 | 0.232 | ×2.62 | ×1.93 |
| 30 | 0.500 | ×4.50 | ×3.00 |
| 40 | 0.768 | ×6.38 | ×4.07 |
| 45 | 0.858 | ×7.01 | ×4.43 |
| 50 | 0.917 | ×7.42 | ×4.67 |
| 60 | 0.971 | ×7.80 | ×4.88 |
Elite multipliers (applied on top of level scaling):

| Level Range | Elite HP Mult | Elite Damage Mult |
|---|---|---|
| 1–10 | ×1.0 (no elites) | ×1.0 |
| 10–20 | ×1.5 | ×1.3 |
| 20–30 | ×2.0 | ×1.5 |
| 30–45 | ×2.5 | ×1.8 |
| 45–60 | ×3.0 | ×2.0 |
3.3 Weapon Damage Scaling
Applied via damage event interception when a player attacks with a leveled weapon.

| Stat | `k` | Formula |
|---|---|---|
| Damage mult | 6.0 | `WeaponMult(L) = 1 + 5.0 × S(L)` |
| Level | Weapon Mult |
|---|---|
| 1 | ×1.15 |
| 15 | ×1.71 |
| 30 | ×3.50 |
| 45 | ×5.29 |
| 60 | ×5.86 |
Example: Cobalt Sword Swing_Left (12 base) at Level 30 → `12 × 3.50 = 42` damage.

3.4 Armor Defense Scaling
Applied as additional damage reduction in the DamageEventSystem, after vanilla armor reduction.

`DR(L) = BaseResist × (1 + 3.0 × S(L))`

With a hard cap at 0.65 (65% max reduction) to prevent infinite tanking.

| Level | Full Cobalt Set (32% base) → Effective DR |
|---|---|
| 1 | 34.8% |
| 15 | 45.6% |
| 30 | 56.0% |
| 45 | 62.2% |
| 60 | 64.3% |
Diminishing returns are inherent: going from 60% → 65% (5 percentage points over 15 levels) vs 32% → 56% (24 points over 29 levels).

3.5 Cross-System Combat Balancing
Player vs Zombie (baseline)
Setup: Player with Cobalt Sword (12 base swing) + Full Cobalt Armor (32% base resist, +61 HP → 161 total HP).

Zombie: 49 base HP, 18 base damage. Attack rate ~1 hit/2 seconds. Player sword attack chain ~1.5s cycle (Swing L → Swing R → Swing Down → Thrust = 4 hits averaging ~14 damage per hit at base).

| Metric | Level 1 | Level 15 | Level 30 | Level 45 | Level 60 |
|---|---|---|---|---|---|
| Zombie HP | 59 | 97 | 221 | 344 | 383 |
| Zombie Damage/hit | 20 | 28 | 54 | 80 | 88 |
| Player DPS (chain avg) | 10.7 | 16.0 | 32.7 | 49.4 | 54.7 |
| Player Effective DR | 34.8% | 45.6% | 56.0% | 62.2% | 64.3% |
| Player EHP | 247 | 296 | 366 | 426 | 451 |
| TTK (player kills zombie) | 5.5s | 6.1s | 6.8s | 7.0s | 7.0s |
| Time zombie kills player | 24.7s | 21.1s | 13.6s | 10.7s | 10.2s |
| TTK Ratio (survival margin) | 4.5:1 | 3.5:1 | 2.0:1 | 1.5:1 | 1.5:1 |
Analysis: TTK stays in the 5.5–7.0 second band for normal mobs (target: 6–10s). The survival margin narrows from 4.5:1 (trivial) to 1.5:1 (tense) — making endgame combat feel dangerous without being unfair.

Player vs Elite Zombie (Level 30)
Elite HP: `221 × 2.5 = 553`
Elite Damage: `54 × 1.8 = 97`/hit
Player DPS: `32.7`
TTK: `553 / 32.7 = 16.9s`
Time elite kills player: `366 / (97 / 2) = 7.5s`
Elites require kiting, potions, or party support — ~17s fight vs ~7.5s lethal window.

Player vs Shadow_Knight Boss (Level 45)
Boss HP: `400 × 7.01 = 2804` (bosses use `k=4.0`: `400 × (1 + 3.0 × 0.858) = 1430`)
Boss Damage: `119 × (1 + 2.0 × 0.858) = 323`/hit
Player DPS: `49.4`
TTK: `1430 / 49.4 = 28.9s` — half-minute boss fight
Boss kills player: `426 / (323 / 3) = 3.95s` — must dodge/block
3.6 Party Scaling
For future implementation. Per extra player:

| Party Size | Monster HP Mult | Monster Damage Mult | Aggro Switch Rate |
|---|---|---|---|
| 1 (solo) | ×1.0 | ×1.0 | — |
| 2 | ×1.4 | ×1.1 | 1/2 per 5s |
| 3 | ×1.8 | ×1.2 | 1/3 per 5s |
| 4 | ×2.2 | ×1.3 | 1/4 per 5s |
Part 4 — Java Implementation Design
4.1 New Package: com.duntale.zsquad.progression/
ScalingDataCache
Opens SQLite connection on construction
Lazy-loads scaled data: getMonsterScaled(String npcId, int level) → MonsterScaledData record
Same for getWeaponMultiplier(String weaponId, int level) and getArmorMultiplier(String armorId, int level)
Internal cache: ConcurrentHashMap<String, MonsterScaledData> keyed by "npcId:level"
Uses try-with-resources + PreparedStatement
Graceful degradation: if DB missing or query fails, return base multiplier (1.0) + log warning
NpcLevelRegistry
ConcurrentHashMap<UUID, NpcLevelData> mapping spawned NPC UUIDs to level info
Record: NpcLevelData(int level, boolean elite, String npcId, float damageMultiplier)
Populated at spawn time by LeveledNpcSpawner
Cleaned on entity death/despawn (via damage inspect system or periodic purge)
LeveledNpcSpawner
Takes ScalingDataCache and NpcLevelRegistry as constructor dependencies
Main method: spawn(Store<EntityStore> store, String roleName, Vector3d position, int level, boolean elite)
Flow:
Resolve role index: NPCPlugin.get().getIndex(roleName) — fail if -1
Query ScalingDataCache.getMonsterScaled(roleName, level)
Call NPCPlugin.get().spawnEntity(store, roleIndex, position, null, null, postSpawnCallback)
In postSpawn callback:
Get EntityStatMap via EntityStatsModule.get().getEntityStatMapComponentType()
Get health index: EntityStatType.getAssetMap().getIndex("Health")
Get role's base HP: npcEntity.getRole().getInitialMaxHealth()
Compute delta: scaledHP - initialMaxHealth (scaledHP includes ±5% random variance)
statMap.putModifier(healthIdx, "LevelScale", new StaticModifier(MAX, ADDITIVE, delta))
statMap.maximizeStatValue(healthIdx) — fill to new max
Register in NpcLevelRegistry
Replace DisplayNameComponent with level prefix (e.g., "[Lv.30] Zombie")
If elite: EntityScaleComponent.setScale(1.2f) for visual distinction
CombatScalingSystem
Extends DamageEventSystem
Group: DamageModule.get().getFilterDamageGroup()
Dependencies: AFTER ArmorDamageReduction.class (vanilla armor applies first)
Query: AllLegacyLivingEntityTypesQuery.INSTANCE
handle() logic:
NPC → Player damage: damage.getSource() instanceof Damage.EntitySource es → get attacker's NPCEntity → look up UUID in NpcLevelRegistry → damage.setAmount(base × npcLevelData.damageMultiplier())
Player → NPC damage: Check if target has NPCEntity. Check if attacker is EntitySource with player inventory. Read equipped weapon's ItemStack.metadata for weapon level. Query ScalingDataCache.getWeaponMultiplier(). damage.setAmount(base × weaponMult)
Armor defense boost: When target is a player, read equipped armor metadata for gear level. Compute additional DR from ScalingDataCache.getArmorMultiplier(). Apply: damage.setAmount(current × (1 - additionalDR))
GearLevelService
Static utility for weapon/armor level metadata
setWeaponLevel(ItemStack stack, int level) → stack.withMetadata("zsquad_weapon_level", IntCodec.INSTANCE, level)
getWeaponLevel(ItemStack stack) → stack.getFromMetadataOrNull("zsquad_weapon_level", IntCodec.INSTANCE) — returns null if unleveled
Same for armor: setArmorLevel / getArmorLevel with key "zsquad_armor_level"
4.2 Registration in ZSquadPlugin.setup()
Instantiate ScalingDataCache with DB path
Instantiate NpcLevelRegistry
Instantiate LeveledNpcSpawner(scalingDataCache, npcLevelRegistry)
Register CombatScalingSystem(npcLevelRegistry, scalingDataCache) via this.getEntityStoreRegistry().registerSystem()
Register /dspawn command with LeveledNpcSpawner dependency
Part 5 — Testing Command: /dspawn
5.1 Command Structure
Root command DSpawnCommand extends CommandBase, with subcommands:

/dspawn <npc> <count> <level>
Extends AbstractPlayerCommand (world-thread safe)
Args: npc (STRING, required), count (INTEGER, required), level (INTEGER, required)
Validation:
NPCPlugin.get().getIndex(npc) != -1 — NPC exists
level in [1, 60]
count in [1, 20]
For each count: call LeveledNpcSpawner.spawn() with random scatter ±3 blocks
Print per-NPC summary:
Print timing:
/dspawn elite <npc> <count> <level>
Same as above but sets elite = true
Elite multipliers applied on top
Print:
/dspawn info <npc> <level>
Dry run — no spawning
Queries ScalingDataCache and prints full stat breakdown:
Part 6 — Advanced Considerations
6.1 Random Stat Variance
±5% on all scaled values, applied at spawn time:

Stored in NpcLevelData for consistent damage multiplier during the entity's lifetime.

6.2 Boss Rules
Bosses (Dragon_Fire, Dragon_Frost, Shadow_Knight, Rex_Cave) use a separate curve with `k=4.0` instead of 8.0 for HP and `k=3.0` instead of 5.0 for damage. This prevents extreme TTK at high levels. Boss flag detected from tier = "Boss" in monsters_base.

6.3 Nightmare Difficulty
Global multipliers stored in scaling_config:

nightmare_hp_mult = 1.5
nightmare_damage_mult = 1.3
Applied on top of level scaling in LeveledNpcSpawner — read from ScalingDataCache config on spawn.

6.4 Safeguards
Max HP clamped to 10,000 (prevents UI overflow)
Max damage clamped to 500 per hit (prevents one-shots)
All multipliers floored at 1.0 (entities never weaker than base)
NpcLevelRegistry cleanup: entries removed when NPC health reaches 0 (via InspectDamage system) + periodic purge every 60s for stale entries
Integer overflow protection: all computations in double, cast to int/float only at final assignment
6.5 Future-Proofing
Adding new NPCs: add row to monsters_base (via parse_assets.py re-run), re-run generate_scaling.py. No Java changes.
Adding new weapons/armor: same pattern — re-parse assets, regenerate scaling.
Speed scaling: add EntityEffect JSON assets with HorizontalSpeedMultiplier tiers (e.g., 6 speed levels), apply via EffectControllerComponent.addEffect() in LeveledNpcSpawner.
Custom stat types: EntityStatType.getAssetMap() supports additional asset-defined stats — new stats can be scaled by adding them to scaling_config.
Verification
Python pipeline: uv run parse_assets.py → uv run generate_scaling.py → uv run simulate_combat.py — outputs markdown with TTK/EHP tables
generate_scaling.py --verify: Validates all scaled values are within bounds (HP ≤ 10,000, damage ≤ 500, multipliers ≥ 1.0)
Unit tests: ScalingDataCacheTest (mock SQLite), CombatScalingSystemTest (mock Damage events with EntitySource)
In-game dry run: /dspawn info Zombie 30 → confirm HP matches simulate_combat.py output
In-game live test: /dspawn Zombie 1 30 → spawn, attack, confirm TTK ~7s with Cobalt gear
Edge cases: /dspawn Zombie 1 1 (minimum), /dspawn Dragon_Fire 1 60 (maximum), /dspawn elite Zombie 20 60 (mass elite spawn)
## 6.6 Decisions

| Decision | Chosen | Over | Reason |
|---|---|---|---|
| Gear scaling approach | Damage event interception | Player-level modifiers, pre-baked assets, hybrid | Per-item granularity via ItemStack.metadata, no JSON asset overhead |
| SQLite access pattern | Python precomputes + Java queries and caches | Offline-only, runtime-only | Offline validation + combat simulation; Java cache avoids per-spawn latency |
| NPC level storage | ConcurrentHashMap in NpcLevelRegistry | NPC ValueStore slots | ValueStore slots are role-defined at build time, can\'t add custom slots |
| Scaling curve | Sigmoid (`S(L)`) | Linear, pure exponential | Natural S-curve prevents runaway while maintaining meaningful progression |
| Speed scaling | Deferred | JSON speed effect assets now | Requires asset files, not critical for core balance loop |
| NPC stat filling | Full inheritance resolution in parse_assets.py | Manual table completion | Automated, reproducible, handles ~200 NPCs with template chains |

---

## Part 7 — Implementation Progress

### 7.1 Dungeon Generator UI (`/generate` Command) — DONE

**Files created/modified:**
- `v3-zsquad/.../command/GenerateCommand.java` — `/generate` player command, opens `DungeonGeneratePage`
- `v3-zsquad/.../command/DungeonGeneratePage.java` — `InteractiveCustomUIPage` with full dungeon config form
- `v3-zsquad/.../Pages/Generate/DungeonGeneratePage.ui` — UI layout DSL (920×700, 10 config sections)

**Features implemented:**
1. **UI form** with 10 sections: General, Size, Rooms, Corridors, Features, Navigation, Enemies, Architecture, Theme, Pacing
2. **Config persistence**: Saves/loads last-used values as JSON (`generate-config.json` in plugin data dir)
3. **Origin coords persistence**: Loads saved origin on re-open instead of resetting to player position
4. **Clear before generation**: Runs `/clear` command with calculated bounds + 2.5s delay before assembly starts
5. **Status feedback**: Labels update to show generation progress ("Generating...", "Assembling...", errors)

**Bug fixes applied:**
- NumberField sends `Integer` not `String` → all 12 numeric fields use `Codec.INTEGER`
- UI spacing/padding rewrite for readability (row heights 44px, FontSize:14, panel titles 34px)

### 7.2 Spawner Entity Creation Fix — DONE

**Critical bug**: `SpawnerFactory.createSpawners()` was **never called** after dungeon assembly. Spawner definitions were generated by the pipeline but no ECS entities were created, so NPCs never spawned.

**Files modified:**
- `dungeon-gen/.../generator/GenerationResult.java` — Added `List<SpawnerDefinition> spawnerDefinitions` field
- `dungeon-gen/.../generator/GenerationOrchestrator.java` — Passes `List.copyOf(spawners)` to `GenerationResult`
- `v3-zsquad/.../spawner/SpawnerFactory.java` — Added `createSpawners(Store, List<SpawnerDefinition>, Vec3i)` overload + per-spawner debug logging
- `v3-zsquad/.../spawner/SpawnerTickSystem.java` — Added spawn success/failure logging in `handleActive`
- `v3-zsquad/.../command/DungeonGeneratePage.java` — Calls `SpawnerFactory.createSpawners()` after assembly completes on world thread

**Runtime chain**: `SpawnerFactory` → ECS entity with `SpawnerComponent` + `TransformComponent` → `SpawnerTickSystem` (3Hz) checks player proximity → `LeveledNpcSpawner.spawn()` creates NPC with scaled stats.

### 7.3 Plugin Wiring Fixes — DONE

- `manifest.json` — Added `"com.duntale:DungeonGen": ">=1.0.0-SNAPSHOT"` dependency
- `ZSquadPlugin.java` — Moved `GenerationOrchestrator` init from `setup()` to `start()` (DungeonGen plugin must be loaded first)

### 7.4 Remaining Work

- [ ] **Python scaling pipeline** (Part 2): `parse_assets.py`, `generate_scaling.py`, `simulate_combat.py`
- [ ] **ScalingDataCache** (Part 4.1): SQLite queries + in-memory caching
- [ ] **NpcLevelRegistry** (Part 4.1): UUID → NpcLevelData mapping
- [ ] **CombatScalingSystem** (Part 4.1): DamageEventSystem for NPC/weapon/armor scaling
- [ ] **GearLevelService** (Part 4.1): ItemStack metadata for weapon/armor levels
- [ ] `/dspawn` command (Part 5): Testing command for spawning leveled NPCs
- [ ] Spawner in-game verification: Confirm spawners are creating NPCs and scaling works end-to-end