# RPG Dungeon System — Overview & Tuning Guide

> High-level documentation for the Economy, RPG Stats and Progression systems in ZSquad.
> Target audience: developers and game designers.

---

## Table of Contents

1. [System Architecture](#system-architecture)
2. [Gold Economy](#gold-economy)
3. [RPG Stats](#rpg-stats)
4. [Progression (XP & Levels)](#progression-xp--levels)
5. [Gameplay Integration Points](#gameplay-integration-points)
6. [Tuning Guide](#tuning-guide)

---

## System Architecture

The RPG dungeon system is built on three pillars wired together through `ZSquadPlugin`:

```
┌──────────────┐     ┌──────────────┐     ┌──────────────────┐
│  Gold Economy │     │  RPG Stats   │     │   Progression    │
│              │     │              │     │                  │
│ GoldService  │     │ RpgService   │     │ ProgressionSvc   │
│ GoldRepo     │     │ RpgRepo      │     │ ProgressionRepo  │
│ GoldPickup   │     │ StatEffects  │     │ LevelUpResult    │
│ GoldCommand  │     │ DamageScale  │     │                  │
│ CurrencyDrop │     │ StatCommand  │     │                  │
└──────┬───────┘     └──────┬───────┘     └────────┬─────────┘
       │                    │                      │
       └────────────────────┼──────────────────────┘
                            │
                    ┌───────▼────────┐
                    │ DatabaseConnection │  ← single SQLite file (zsquad.db)
                    └────────────────┘
```

**Storage**: All three systems share a single SQLite database (`<dataDir>/zsquad.db`) managed by `DatabaseConnection`. Writes happen on the WorldThread (latency ~1–10ms for small tables).

**ECS Integration**: Two custom ECS systems hook into the Hytale entity pipeline:
- `GoldPickupSystem` (EntityTickingSystem) — converts gold item entities into currency
- `RpgDamageScalingSystem` (DamageEventSystem) — applies Strength and Resistance to damage

**Admin Commands**: `/gold` and `/stat` provide debug/admin access to inspect and mutate player data.

---

## Gold Economy

### Flow

```
NPC dies → NpcLootSystem rolls loot → Gold_Coin item entities spawn
    ↓ (tagged with PreventPickup + CurrencyDrop)
GoldPickupSystem detects player within 2.5 blocks
    ↓
GoldService.addGold(uuid, quantity) → SQLite update
    ↓
Chat notification: "+ 5 Gold (Total: 150)"
Entity removed from world
```

### Key Constraints

| Property | Value |
|----------|-------|
| Max balance | 999,999,999 gold |
| Gold item ID | `Gold_Coin` |
| Pickup radius | 2.5 blocks |
| Stack size | 100 per item entity |
| Drop on death | No |

### Architecture

- **`GoldRepository`** — Raw SQL CRUD. `player_gold` table with `uuid` (PK) and `balance` columns.
- **`GoldService`** — Public API with clamping, logging, and atomic `transfer()` via manual transactions.
- **`GoldPickupSystem`** — ECS tick system. Queries `ItemComponent + CurrencyDrop + TransformComponent`. Finds nearest player, awards gold, removes entity.
- **`CurrencyDrop`** — Singleton ECS marker component. Registered in `ZSquadPlugin.setup()`.

### `/gold` Command

```
/gold check <player>           — Show balance
/gold give <player> <amount>   — Add gold
/gold take <player> <amount>   — Remove gold
/gold set <player> <amount>    — Set exact balance
```

---

## RPG Stats

### The Seven Stats

| Stat | Type | Effect | Scaling |
|------|------|--------|---------|
| **Speed** | Hyperbolic | CTM movement velocity (blocks/sec) | 8.0 + 4.0 × L/(L+25) |
| **Strength** | Hyperbolic | Outgoing damage multiplier | 1.0 + 1.0 × L/(L+25) |
| **Luck** | Hyperbolic + Linear | Drop chance bonus + bonus loot rolls | 0.30 × L/(L+20) ; floor(L/15) rolls |
| **Stamina** | Linear | Max stamina bonus | L × 5.0 |
| **Agility** | Hyperbolic | Attack cooldown reduction | 400ms × (1 − 0.65 × L/(L+25)), floor 140ms |
| **Resistance** | Hyperbolic | Incoming damage reduction (multiplicative with armor) | 0.40 × L/(L+30) |
| **Vitality** | Linear | Max health bonus | L × 10.0 |

All stats range from **0 to 100**. Values are clamped in `RpgService`.

### Hyperbolic Formula

The core formula providing diminishing returns:

```
bonus = MAX_BONUS × (level / (level + HALF_POINT))
```

- At `level = 0`: bonus = 0
- At `level = HALF_POINT`: bonus = 50% of MAX_BONUS
- At `level → ∞`: bonus → MAX_BONUS (never reached)

This prevents any single stat from becoming broken at high levels while still feeling rewarding at low levels.

### Where Stats Hook In

| Stat | Code Location | How |
|------|---------------|-----|
| Speed | `ClickToMoveManager.getPlayerMoveSpeed()` → `MovementHelper.sendVelocity()` | Per-player velocity sent in ChangeVelocity packet |
| Strength | `RpgDamageScalingSystem` (Player→NPC path) | Multiplies damage amount |
| Resistance | `RpgDamageScalingSystem` (NPC→Player path) | Reduces damage multiplicatively after armor DR |
| Agility | `ClickToMoveManager.getPlayerAttackThrottle()` → `AttackHandler.tryAttack()` | Per-player attack cooldown in nanoseconds |
| Luck | `NpcLootSystem` → `LootTable.roll(npcLevel, luckLevel)` | Drop chance bonus + bonus rolls |
| Stamina | *(deferred — needs EntityStatMap.putModifier)* | Additive MAX modifier on Stamina entity stat |
| Vitality | *(deferred — needs EntityStatMap.putModifier)* | Additive MAX modifier on Health entity stat |

### Architecture

- **`RpgStat`** — 7-value enum.
- **`RpgProfile`** — `EnumMap<RpgStat, Integer>` snapshot per player.
- **`RpgRepository`** — SQL CRUD. `player_stats` table with `(uuid, stat, value)` composite PK.
- **`RpgService`** — Public API with `ConcurrentHashMap` cache. Lazy-loads on first access, pre-loads on join, evicts on leave.
- **`RpgStatEffects`** — Pure static methods. All formula computations live here.
- **`RpgConstants`** — All tunable numbers in one place.
- **`RpgDamageScalingSystem`** — DamageEventSystem running AFTER `CombatScalingSystem`. Handles both Strength (outgoing) and Resistance (incoming).

### `/stat` Command

```
/stat check <player>                 — Show all stats
/stat check <player> <stat>          — Show one stat
/stat set <player> <stat> <value>    — Set stat value
/stat add <player> <stat> <value>    — Add to stat value
```

---

## Progression (XP & Levels)

### Flow

```
NPC killed → NpcLootSystem resolves attacker UUID
    ↓
ProgressionService.grantXP(uuid, BASE_XP × npcLevel)
    ↓
New total XP → calculateLevel() via threshold table
    ↓ (if leveled up)
LevelUpListener.onLevelUp(uuid, newLevel) called per level gained
```

### XP Formula

`xpAmount = BASE_XP_PER_KILL × npcLevel` where `BASE_XP_PER_KILL = 10`

| NPC Level | XP per Kill |
|-----------|-------------|
| 5 | 50 |
| 10 | 100 |
| 20 | 200 |
| 30 | 300 |

### Level Thresholds

Levels are determined by a `levels` table in the database mapping `level → xp_required`. This table is currently empty and must be seeded (see [Tuning: Level Thresholds](#level-thresholds-1)).

When a player's total XP crosses a threshold, they advance to that level. Multiple level-ups from a single XP grant trigger the listener once per level.

### Architecture

- **`ProgressionRepository`** — SQLite CRUD. Two tables: `levels` (thresholds) and `player_progression` (uuid, level, xp, season). `NavigableMap` cache for thresholds.
- **`ProgressionService`** — `grantXP()` with per-player locks, `LevelUpListener` callback, query methods.
- **`LevelUpResult`** — Record: `xpGranted`, `totalXP`, `oldLevel`, `newLevel`, `leveledUp`.

### Deferred: Stat Point Rewards

The plan calls for `StatPointReward` (grants unassigned stat points on level-up) and `StatAssignmentPage` (Custom UI for spending points). These require:
1. A Reward interface (copy from duntale-dev)
2. An `InteractiveCustomUIPage<T>` template + `.ui` file

Until implemented, stats can be assigned via the `/stat` admin command for testing.

---

## Gameplay Integration Points

### Damage Pipeline

```
Base damage (weapon)
  × CombatScalingSystem multiplier (weapon gear level vs NPC level)
  × RpgDamageScalingSystem:
      Player→NPC: × Strength multiplier
      NPC→Player: × (1 − Resistance DR)
= Final damage
```

Resistance stacks **multiplicatively** with armor DR: `(1 − armorDR) × (1 − resistanceDR)`. This ensures total DR asymptotically approaches but never reaches 100%.

### Loot Pipeline

```
NPC dies → NpcLootSystem (DeathSystems.OnDeathSystem)
  1. Suppress default drops (DeathConfig.ItemsLossMode.NONE)
  2. Resolve attacker (DeathComponent → Damage → EntitySource → UUIDComponent)
  3. Get attacker's Luck stat
  4. Roll loot table: LootTable.roll(npcLevel, luckLevel)
     - adjustedDropChance = min(1.0, base + Luck drop bonus)
     - totalRolls = base + Luck bonus rolls
  5. Spawn item entities
  6. Tag Gold_Coin entities with CurrencyDrop + PreventPickup
  7. Grant XP: ProgressionService.grantXP(attackerUuid, 10 × npcLevel)
```

### Movement Pipeline

```
ClickToMoveManager.tickMovement()
  → getPlayerMoveSpeed(uuid): RpgService.getStat(SPEED) → RpgStatEffects.computeMoveSpeed()
  → MovementHelper.sendVelocity(... moveSpeed)

ClickToMoveManager.tickMovement() / updateTarget()
  → getPlayerAttackThrottle(uuid): RpgService.getStat(AGILITY) → RpgStatEffects.computeAttackThrottleNs()
  → AttackHandler.tryAttack(... throttleNs)
```

### Player Lifecycle

```
PlayerConnectEvent  → RpgService.onPlayerJoin(uuid)   — pre-loads profile into cache
PlayerDisconnectEvent → RpgService.onPlayerLeave(uuid)  — evicts cache
                      → ProgressionService.onPlayerLeave(uuid) — cleans up locks
```

---

## Tuning Guide

All tunable constants live in a single file: `rpg/RpgConstants.java`. Changes require a rebuild + server restart.

### Stat Scaling

Each hyperbolic stat has two knobs:

| Constant | Effect | Higher value means... |
|----------|--------|----------------------|
| `MAX_BONUS` | Asymptotic cap | Stronger maximum effect |
| `HALF_POINT` | Level for 50% bonus | Slower progression (more spread out) |

**Example — making Speed more impactful:**
```java
SPEED_MAX_BONUS = 6.0f;    // was 4.0 → now caps at 14.0 blocks/sec
SPEED_HALF_POINT = 20.0f;  // was 25 → reaches 50% faster
```

**Example — making Resistance less dominant:**
```java
RESISTANCE_MAX_DR = 0.25f;      // was 0.40 → caps at 25% instead of 40%
RESISTANCE_HALF_POINT = 40.0f;  // was 30 → takes longer to scale up
```

Linear stats (Stamina, Vitality) have a single `PER_POINT` constant:
```java
VITALITY_HP_PER_POINT = 5.0f;  // was 10 → halves the HP gain per level
```

### Reference Tables

Use these to validate changes. The formula `MAX_BONUS × L/(L + K)` produces:

| Level | K=20 | K=25 | K=30 |
|-------|------|------|------|
| 5 | 20.0% | 16.7% | 14.3% |
| 10 | 33.3% | 28.6% | 25.0% |
| 25 | 55.6% | 50.0% | 45.5% |
| 50 | 71.4% | 66.7% | 62.5% |
| 75 | 78.9% | 75.0% | 71.4% |
| 100 | 83.3% | 80.0% | 76.9% |

(Values shown as % of MAX_BONUS)

### Stat Bounds

```java
MIN_STAT = 0;    // floor for all stats
MAX_STAT = 100;  // ceiling for all stats (clamped in RpgService)
```

Raising `MAX_STAT` beyond 100 changes the effective range of all hyperbolic formulas. At `MAX_STAT = 200` with `K = 25`, the cap becomes 88.9% of MAX_BONUS instead of 80%.

### Gold Economy

| Constant | Current | Effect |
|----------|---------|--------|
| `MAX_GOLD_BALANCE` | 999,999,999 | Hard cap on player gold |
| Pickup radius | 2.5 blocks (in `GoldPickupSystem`) | How close player must be to collect |

To add gold drops to NPCs, add `LootEntry.Simple("Gold_Coin", minQty, maxQty, weight)` entries to loot tables in `ZSquadPlugin.registerLootTables()`.

**Example:**
```java
lootTableRegistry.register("Trork_Warrior", new LootTable(List.of(
    new LootEntry.Simple("Gold_Coin", 3, 8, 5.0),    // ← gold drop
    new LootEntry.Leveled("Weapon_Axe_Crude", ...),
    // ...
), 1, 0.35));
```

### XP per Kill

In `NpcLootSystem`:
```java
private static final long BASE_XP_PER_KILL = 10;
// XP = BASE_XP_PER_KILL × npcLevel
```

| Change | Result |
|--------|--------|
| Increase `BASE_XP_PER_KILL` to 20 | Players level twice as fast |
| Change formula to `BASE_XP × npcLevel²` | Exponential XP from high-level mobs |
| Add XP scaling by player level delta | Reduced XP from mobs far below player level |

### Level Thresholds

The `levels` table in SQLite defines the XP needed for each level. It must be seeded before progression works. Format:

```sql
INSERT INTO levels (level, xp_required) VALUES
    (1, 0),
    (2, 100),
    (3, 250),
    (4, 500),
    (5, 800),
    (10, 5000),
    (15, 15000),
    (20, 35000),
    (25, 70000),
    (30, 120000);
```

Intermediate levels are **linearly interpolated** between defined milestones. You only need to define key milestone levels — the `ProgressionRepository.getXPForLevel()` method interpolates the rest.

**Suggested approach for initial seeding:**
1. Connect to `zsquad.db` with any SQLite client
2. Insert milestone rows into the `levels` table
3. Call `/stat` to test that XP grants produce expected level-ups

Or create a `scripts/seed-levels.sql` and run it on server start.

### Agility (Attack Speed)

The attack throttle has a hard floor to prevent extreme attack speeds:

```java
AGILITY_MIN_THROTTLE_NS = 140_000_000L;  // 140ms minimum between attacks
```

At ~7 attacks/second this is already very fast for click-to-move gameplay. Lowering this further risks desyncs and animation issues.

### Luck (Loot)

Luck has two independent effects:

1. **Drop chance bonus** (hyperbolic): `adjustedDropChance = min(1.0, base + bonus)`  
   For a table with `dropChance = 0.35`, Luck 25 gives `0.35 + 0.167 = 0.517` (51.7%).

2. **Bonus rolls** (linear step): `floor(level / LUCK_LEVELS_PER_BONUS_ROLL)`  
   At `LUCK_LEVELS_PER_BONUS_ROLL = 15`: Luck 15 = +1 roll, Luck 30 = +2 rolls, etc.

To make Luck more generous, lower `LUCK_LEVELS_PER_BONUS_ROLL` (e.g., 10 = bonus roll every 10 levels instead of 15).

### Damage Scaling Interaction

The full damage chain for Player→NPC:
```
finalDmg = baseDmg × CombatScaling(weaponLevel, npcLevel) × Strength(playerStrength)
```

For NPC→Player:
```
finalDmg = baseDmg × CombatScaling(npcLevel, armorLevel) × (1 − Resistance(playerResistance))
```

`CombatScalingSystem` handles gear-vs-NPC level scaling. `RpgDamageScalingSystem` handles RPG attribute scaling. They are **independent multiplicative layers** — changing one doesn't affect the other's math.
