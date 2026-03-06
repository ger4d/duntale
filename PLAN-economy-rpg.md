# Implementation Plan: Economy + RPG Stats

**Target project**: `/home/gpmod/lab/duntale/v3-zsquad`
**Package root**: `com.duntale.zsquad`

---

## Implementation Progress

| Round | Commit | Status | Description |
|-------|--------|--------|-------------|
| 1 | `62c68ff` | **Done** | Foundation: `DatabaseConnection`, `RpgStat`, `RpgConstants`, `RpgProfile`, `CurrencyDrop`, `Gold_Coin.json` |
| 2 | `7859991` | **Done** | Repositories + formulas: `GoldRepository`, `RpgRepository`, `RpgStatEffects` |
| 3 | `6367ba3` | **Done** | Services: `GoldService`, `RpgService` |
| 4 | `27e6159` | **Done** | Commands + ECS: `/gold`, `/stat`, `GoldPickupSystem`, `RpgDamageScalingSystem` |
| 5 | `a70d62f` | **Done** | Gameplay hooks: Speed/Agility in movement, Luck in loot, CurrencyDrop tagging |
| 6 | `aae4380` | **Done** | Progression: `ProgressionRepository`, `ProgressionService`, `LevelUpResult`, XP-on-kill |
| 7 | `7d19272` | **Done** | Plugin integration: all services/systems/commands/events wired in `ZSquadPlugin` |
| 8 | — | **Done** | Economy wiring: listener-based scoreboard updates, Gold_Coin in all loot tables, Arcane enemy loot tables, MerchantCommand real catalog, merchant buy→sell tags + close cleanup, MerchantWindow |

**New files created**: 19 (15 Java + 1 JSON asset + 3 progression)  
**Files modified**: 9 (`ZSquadPlugin`, `ClickToMoveManager`, `MovementHelper`, `LootTable`, `NpcLootSystem`, `GoldService`, `ProgressionService`, `RpgService`, `MerchantPriceRegistry`)

### Remaining (deferred to future work)

| Item | Reason Deferred |
|------|------------------|
| `StatPointReward.java` | Needs Reward interface infrastructure from duntale-dev |
| `StatAssignmentPage.java` | Needs `InteractiveCustomUIPage<T>` pattern research + .ui template |
| Vitality/Stamina engine modifiers | Needs `EntityStatMap.putModifier()` API verification on live server |
| ~~Gold drops in loot tables~~ | ~~Done (Round 8)~~ |
| ~~Level thresholds seeding~~ | ~~Done (Round 6)~~ |

---

## Part 1: Gold Economy

### Design

**Persistence**: SQLite — survives world resets, cross-world, queryable. Shared `DatabaseConnection` for all tables.

**Gold Item Flow**:
1. Custom `Gold_Coin` item defined as a Hytale item asset JSON (placed in mod asset pack)
2. NPCs drop gold via `LootEntry.Simple` with `quantityMin`/`quantityMax` — no changes to `LootEntry` sealed interface
3. `NpcLootSystem` adds `PreventPickup` + `CurrencyDrop` components to spawned gold item entities
4. `GoldPickupSystem` — a custom `EntityTickingSystem` — queries only `CurrencyDrop`-tagged entities near players, converts quantity to currency balance, deletes the entity, and plays a pickup notification
5. `GoldService` manages the persistent balance via SQLite

### Proposed Files

```
db/
└── DatabaseConnection.java       — Shared SQLite connection lifecycle

economy/
├── CurrencyDrop.java             — Singleton ECS marker component for gold item entities
├── GoldRepository.java           — SQL CRUD for gold balances
├── GoldService.java              — Public API: getBalance, addGold, removeGold, hasEnough
├── GoldPickupSystem.java         — ECS EntityTickingSystem: auto-picks up Gold_Coin items
└── GoldCommand.java              — /gold [check|give|take|set] <player> <amount> (admin debug)
```

### Database Schema

```sql
CREATE TABLE IF NOT EXISTS player_gold (
    uuid    TEXT PRIMARY KEY,
    balance BIGINT NOT NULL DEFAULT 0
);
```

### `GoldRepository`

Methods:
- `long getBalance(UUID)` — returns 0 if no row
- `void setBalance(UUID, long)`
- `void addBalance(UUID, long delta)` — `INSERT ... ON CONFLICT DO UPDATE SET balance = balance + ?`
- `void initialize()` — creates table

### `GoldService`

```java
public class GoldService {
    private static final long MAX_BALANCE = 999_999_999L;
    
    long getBalance(UUID playerId);
    boolean addGold(UUID playerId, long amount);        // returns false if amount < 0; clamps to MAX_BALANCE
    boolean removeGold(UUID playerId, long amount);     // returns false if insufficient
    boolean hasEnough(UUID playerId, long cost);
    boolean transfer(UUID from, UUID to, long amount);  // truly atomic via DB transaction
}
```

All mutating methods log `LOGGER.atInfo()` with player UUID, amount, old balance, new balance for debugging.

**`transfer()` atomicity**: Uses `Connection.setAutoCommit(false)` + `try { ... commit(); } catch { rollback(); } finally { setAutoCommit(true); }` to ensure a crash between debit and credit doesn't lose gold.

### Gold Item Asset

**File**: `src/main/resources/Server/Item/Items/Currency/Gold_Coin.json`

The mod's `manifest.json` already has `"IncludesAssetPack": true`. The engine merges mod assets into the global registry at load. Item ID = filename without extension = `"Gold_Coin"`.

```json
{
  "TranslationProperties": {
    "Name": "server.items.Gold_Coin.name"
  },
  "Icon": "Icons/ItemsGenerated/Ingredient_Stick.png",
  "Model": "Items/Ingredients/Stick.blockymodel",
  "Texture": "Items/Ingredients/Stick_Texture.png",
  "PlayerAnimationsId": "Item",
  "ItemSoundSetId": "ISS_Default",
  "MaxStack": 100,
  "Tags": { "Type": ["Currency"] },
  "DropOnDeath": false
}
```

> **TODO**: Replace Model/Texture/Icon with real gold coin art assets.

**Programmatic creation**: `new ItemStack("Gold_Coin", quantity)` — the `itemId` matches the filename. Validated at runtime via `Item.getAssetMap().getAsset("Gold_Coin")`.

### `CurrencyDrop` Marker Component

A dedicated singleton `Component<EntityStore>` registered via `getEntityStoreRegistry().registerComponent()`. Purpose: cleanly identify gold item entities without piggy-backing on engine's `PreventPickup` (which is also used for NPC-held equipment, etc.).

```java
public final class CurrencyDrop implements Component<EntityStore> {
    public static final CurrencyDrop INSTANCE = new CurrencyDrop();
    // + getComponentType() static accessor after registration
}
```

### Gold Pickup System

**Engine pickup pipeline** (from research):

| Path | System | Scope |
|---|---|---|
| Auto-pickup | `PlayerItemEntityPickupSystem` | Proximity-based EntityTickingSystem. Queries items WITHOUT `PreventPickup` or `Interactable`. |
| Interaction | `PickupItemInteraction` | Triggered when item asset has `InteractionType.Pickup` configured. |
| Harvest/Farm | `ItemUtils.interactivelyPickupItem()` | Only path that fires `InteractivelyPickupItemEvent`. Not relevant here. |

**Strategy**: `PreventPickup` (engine ignores) + `CurrencyDrop` (our query targets).

1. When `NpcLootSystem` spawns a gold drop, add both components to the `Holder` **before** `addEntities()`:
   ```java
   Holder<EntityStore>[] holders = ItemComponent.generateItemDrops(...);
   for (Holder<EntityStore> h : holders) {
       if (isGoldItem(h)) {
           h.addComponent(PreventPickup.getComponentType(), PreventPickup.INSTANCE);
           h.addComponent(CurrencyDrop.getComponentType(), CurrencyDrop.INSTANCE);
       }
   }
   commandBuffer.addEntities(holders, AddReason.SPAWN);
   ```

2. `GoldPickupSystem` extends `EntityTickingSystem<EntityStore>`:
   - **Query**: `ItemComponent` + `CurrencyDrop` + `TransformComponent` — only matches our gold entities, zero false positives
   - **Tick**: For each gold item entity:
     - Check `pickupDelay` has elapsed
     - Use `SpatialResource` (player spatial index) to find nearest player within pickup radius
     - Resolve player UUID → `GoldService.addGold(uuid, itemStack.getQuantity())`
     - `commandBuffer.removeEntity(itemRef, RemoveReason.REMOVE)`
     - Send pickup notification: chat message ("+ 5 Gold (Total: 150)") + sound

---

## Part 2: RPG Stats

### Design Philosophy

**User-facing stat values are consecutive natural numbers**: 1, 2, 3, 4, 5, etc. Easy for players to understand and for designers to assign. Internally, each stat is transformed via a well-defined formula to produce the actual gameplay effect.

**Diminishing returns scaling**: Combat-affecting stats use hyperbolic formulas `MAX_BONUS × (level / (level + K))` where K is the "half-point" — the level at which you get 50% of the maximum possible bonus. This provides:
- Near-linear growth at low levels (feels rewarding)
- Diminishing returns at high levels (prevents broken builds)
- Hard asymptotic cap (can never be exceeded regardless of level)

**Resource stats** (Stamina, Vitality) use linear scaling since they represent predictable capacity increases.

### Architecture

```
rpg/
├── RpgStat.java                  — Enum: SPEED, STRENGTH, LUCK, STAMINA, AGILITY, RESISTANCE, VITALITY
├── RpgProfile.java               — Per-player stat values (Map<RpgStat, Integer>)
├── RpgService.java               — Public API: getStat, setStat, addStat, getProfile
├── RpgRepository.java            — SQLite persistence
├── RpgStatEffects.java           — Static utility: computes effective values from stat levels
├── RpgDamageScalingSystem.java   — DamageEventSystem (FilterDamage) for Strength + Resistance
├── RpgConstants.java             — Tunable scaling formulas + stat bounds
└── RpgStatCommand.java           — /stat [check|set|add] <player> <stat> <value> (admin debug)
```

### `RpgStat` Enum

```java
public enum RpgStat {
    SPEED,       // affects CTM movement velocity
    STRENGTH,    // affects outgoing damage (multiplier)
    LUCK,        // affects loot drop chance + bonus rolls
    STAMINA,     // affects max Stamina entity stat
    AGILITY,     // affects CTM attack throttle interval
    RESISTANCE,  // reduces incoming damage (% DR)
    VITALITY;    // increases max Health
}
```

### Database Schema

```sql
CREATE TABLE IF NOT EXISTS player_stats (
    uuid   TEXT NOT NULL,
    stat   TEXT NOT NULL,
    value  INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (uuid, stat)
);
```

### `RpgRepository`

Methods:
- `RpgProfile loadProfile(UUID)` — loads all stats for a player
- `void saveStat(UUID, RpgStat, int)`
- `void saveProfile(UUID, RpgProfile)` — batch upsert
- `void initialize()` — creates table

### `RpgService`

```java
public class RpgService {
    private final Map<UUID, RpgProfile> cache = new ConcurrentHashMap<>();
    
    RpgProfile getProfile(UUID playerId);           // lazy-loads from DB
    int getStat(UUID playerId, RpgStat stat);
    void setStat(UUID playerId, RpgStat stat, int value);  // clamps to [0, MAX], updates cache + DB
    void addStat(UUID playerId, RpgStat stat, int delta);
    void onPlayerJoin(UUID playerId);               // pre-loads + applies Vitality/Stamina modifiers
    void onPlayerLeave(UUID playerId);              // evicts cache, removes stat modifiers
}
```

### Stat Bounds

All stats: **MIN = 0, MAX = 100**. Enforced in `RpgService.setStat()` and `addStat()` via clamping. Log a warning if clamped.

### How Each Stat Affects Gameplay

All formulas use the **hyperbolic diminishing returns** pattern unless noted:

```
effective = BASE + MAX_BONUS × (level / (level + K))
```

Where `K` = half-point level (at level K, you get 50% of MAX_BONUS).

#### 1. SPEED → CTM Movement Velocity

**Hook**: `MovementHelper.sendVelocity()` uses constant `MOVE_SPEED = 8.0`. Add `float moveSpeed` parameter to `sendVelocity()` and `beginMovement()`.

| Level | Speed (blocks/sec) | % Increase |
|---|---|---|
| 0 | 8.00 | 0% |
| 10 | 9.14 | +14% |
| 25 | 10.00 | +25% |
| 50 | 10.67 | +33% |
| 100 | 11.20 | +40% |

```
Formula: 8.0 + 4.0 × (level / (level + 25))
K = 25, MAX_BONUS = 4.0 (caps at 12.0 blocks/sec asymptotically)
```

#### 2. STRENGTH → Outgoing Damage Multiplier

**Hook**: `RpgDamageScalingSystem` in FilterDamage group. Player→NPC only.

| Level | Multiplier |
|---|---|
| 0 | 1.00× |
| 10 | 1.29× |
| 25 | 1.50× |
| 50 | 1.67× |
| 100 | 1.80× |

```
Formula: 1.0 + 1.0 × (level / (level + 25))
K = 25, MAX_BONUS = 1.0 (caps at 2.0× asymptotically)
```

**Compatibility with CombatScalingSystem**: Composes as independent multiplicative factors: `baseDmg × weaponLevelMult × strengthMult`. CombatScaling is equipment-level-based, Strength is RPG-attribute-based.

#### 3. LUCK → Loot Drop Chance + Bonus Rolls

**Hook**: `LootTable.roll()` overload + `NpcLootSystem` attacker resolution.

| Level | Drop Chance Bonus | Bonus Rolls |
|---|---|---|
| 0 | +0% | 0 |
| 10 | +10% | 0 |
| 15 | +11.7% | 1 |
| 25 | +16.7% | 1 |
| 30 | +18% | 2 |
| 50 | +21.4% | 3 |

```
Drop bonus: 0.30 × (level / (level + 20))     — K=20, caps at +30%
Bonus rolls: floor(level / 15)                  — 1 extra roll per 15 levels
adjustedDropChance = min(1.0, baseDropChance + dropBonus)
```

**Attacker Resolution** (required for Luck): `NpcLootSystem.onComponentAdded()` receives `DeathComponent` which stores the killing `Damage` via `deathInfo` field. Resolution path:
1. `DeathComponent` → `getDeathInfo()` → `Damage`
2. `Damage.getSource()` → `Damage.EntitySource` → `getRef()` → attacker entity ref
3. Attacker ref → `UUIDComponent` → `UUID` → `RpgService.getStat(uuid, LUCK)`

> **Verify during implementation**: Confirm `DeathComponent` exposes `getDeathInfo()` getter (field is `private transient`). Fallback: Track last attacker per NPC UUID in a separate `Map` populated from the damage event system.

#### 4. STAMINA → Max Stamina Entity Stat

**Hook**: Engine's `EntityStatMap.putModifier()` with `ModifierTarget.MAX`.

| Level | Max Stamina Bonus |
|---|---|
| 0 | +0 |
| 10 | +50 |
| 25 | +125 |
| 50 | +250 |

```
Formula: level × 5.0 (linear — resource capacity, predictable)
Applied as: putModifier(staminaIndex, "RPG_STAMINA", StaticModifier(MAX, ADDITIVE, amount))
```

Multiple MAX modifiers with different keys stack additively in `computeModifiers()` (confirmed from engine source).

#### 5. AGILITY → CTM Attack Throttle Reduction

**Hook**: `ClickToMoveManager` passes `ATTACK_THROTTLE_NS` to `AttackHandler.tryAttack()` — already parameterized.

| Level | Throttle (ms) | % Faster |
|---|---|---|
| 0 | 400 | 0% |
| 10 | 326 | -19% |
| 25 | 270 | -33% |
| 50 | 227 | -43% |
| 100 | 192 | -52% |

```
Formula: max(140, 400 × (1.0 - 0.65 × (level / (level + 25))))
K = 25, MAX_REDUCTION = 0.65 (floor at 140ms = 35% of base)
```

#### 6. RESISTANCE (NEW) → Incoming Damage Reduction

**Hook**: `RpgDamageScalingSystem` — NPC→Player damage path. Applied **multiplicatively** with CombatScalingSystem's armor DR (not additive, to prevent >100% DR).

| Level | DR from Resistance | Combined with 30% armor |
|---|---|---|
| 0 | 0% | 30% |
| 10 | 10% | 37% |
| 25 | 18.2% | 43.6% |
| 50 | 25% | 47.5% |
| 100 | 30.8% | 52.2% |

```
Formula: 0.40 × (level / (level + 30))
K = 30, MAX_DR = 0.40 (caps at 40% asymptotically)
Combined: finalDamage = damage × (1 - armorDR) × (1 - resistanceDR)
```

**Integration with CombatScalingSystem**: CombatScaling already applies armor DR in Case 1 (NPC→Player). `RpgDamageScalingSystem` runs as a **separate pass** after CombatScaling, applying Resistance as an additional multiplicative layer. This ensures total DR approaches but never reaches 100%.

#### 7. VITALITY (NEW) → Max Health

**Hook**: Engine's `EntityStatMap.putModifier()` with `ModifierTarget.MAX` on the Health stat.

| Level | Max Health Bonus |
|---|---|
| 0 | +0 |
| 10 | +100 |
| 25 | +250 |
| 50 | +500 |

```
Formula: level × 10.0 (linear — health is a predictable resource)
Applied as: putModifier(healthIndex, "RPG_VITALITY", StaticModifier(MAX, ADDITIVE, amount))
```

**Coexistence with armor stats**: Engine's `computeModifiers()` sums ALL additive MAX modifiers across all keys, then applies. So `RPG_VITALITY` (+100 from vitality level 10) and any armor-based health modifier (different key, e.g. `"ARMOR_HP"`) stack additively. Each system uses its own unique modifier key — they don't interfere.

Applied in `RpgService.onPlayerJoin()` and updated in `setStat()`. Removed in `onPlayerLeave()` via `removeModifier()`.

### Scaling Constants

```java
public final class RpgConstants {
    // ── Stat bounds ──────────────────────────────────────────────
    public static final int MIN_STAT = 0;
    public static final int MAX_STAT = 100;
    
    // ── Speed (CTM) ──────────────────────────────────────────────
    public static final float SPEED_BASE = 8.0f;
    public static final float SPEED_MAX_BONUS = 4.0f;      // asymptotic cap: 12.0
    public static final float SPEED_HALF_POINT = 25.0f;
    
    // ── Strength ─────────────────────────────────────────────────
    public static final float STRENGTH_MAX_BONUS = 1.0f;    // asymptotic cap: 2.0×
    public static final float STRENGTH_HALF_POINT = 25.0f;
    
    // ── Luck ─────────────────────────────────────────────────────
    public static final float LUCK_MAX_DROP_BONUS = 0.30f;  // asymptotic cap: +30%
    public static final float LUCK_HALF_POINT = 20.0f;
    public static final int LUCK_LEVELS_PER_BONUS_ROLL = 15;
    
    // ── Stamina ──────────────────────────────────────────────────
    public static final float STAMINA_PER_POINT = 5.0f;     // linear
    
    // ── Agility (CTM) ────────────────────────────────────────────
    public static final long AGILITY_BASE_THROTTLE_NS = 400_000_000L;
    public static final float AGILITY_MAX_REDUCTION = 0.65f; // max 65% reduction
    public static final float AGILITY_HALF_POINT = 25.0f;
    public static final long AGILITY_MIN_THROTTLE_NS = 140_000_000L;
    
    // ── Resistance ───────────────────────────────────────────────
    public static final float RESISTANCE_MAX_DR = 0.40f;    // asymptotic cap: 40%
    public static final float RESISTANCE_HALF_POINT = 30.0f;
    
    // ── Vitality ─────────────────────────────────────────────────
    public static final float VITALITY_HP_PER_POINT = 10.0f; // linear
    
    // ── Gold ─────────────────────────────────────────────────────
    public static final long MAX_GOLD_BALANCE = 999_999_999L;
}
```

### Formula Helper

```java
// Utility in RpgStatEffects or RpgConstants:
static float hyperbolic(int level, float maxBonus, float halfPoint) {
    return maxBonus * (level / (level + halfPoint));
}
```

---

## Part 3: Progression & Stat Acquisition

### Design

Stats are acquired through a **Progression system** (copied from `duntale-dev`) that tracks player XP and levels. On level-up, players receive stat points they can assign.

**Kill → XP → Level → Stat Points → Assign via UI**

### Progression System (Copy from duntale-dev)

Copy and adapt from `com.duntale.hub.core.progression`:
- `ProgressionRepository` — SQLite schema: `levels` (thresholds), `player_progression` (uuid, level, xp, season)
- `ProgressionService` — `grantXP()`, `getLevel()`, `getLevelProgress()`, `setLevelUpListener()`
- `LevelUpResult` — record: xpGranted, totalXP, oldLevel, newLevel, leveledUp

Copy and adapt from `com.duntale.hub.core.reward`:
- `Reward` interface — `getType()`, `apply(RewardContext)`, `getDescription()`
- `RewardDeliveryService` — processes events, checks conditions, delivers rewards
- `RewardRepository` — reward definitions + tracking granted rewards

**New**: `StatPointReward implements Reward` — grants N unassigned stat points to the player.

### Kill Detection → XP

When an NPC dies, `NpcLootSystem.onComponentAdded()` already resolves the attacker (see Luck attacker resolution). Extend to also call `ProgressionService.grantXP(attackerUuid, xpAmount)` where `xpAmount` is determined by the NPC's level from `NpcLevelRegistry`.

### Stat Point Assignment (Custom UI Page)

Players open a stat assignment page (via NPC interaction or command) to spend unassigned points.

**Engine pattern**: `OpenCustomUIInteraction` + `InteractiveCustomUIPage<T>`:
- `OpenCustomUIInteraction.registerSimple(plugin, StatAssignmentPage.class, "stat_assignment", playerRef -> new StatAssignmentPage(playerRef, rpgService))`
- `StatAssignmentPage extends InteractiveCustomUIPage<StatAssignmentData>` — sends current stats + unassigned points to client, receives stat allocation choices back via `handleDataEvent()`

### New Files (Progression)

```
progression/
├── ProgressionRepository.java    — Copy from duntale-dev, adapt to local DB
├── ProgressionService.java       — Copy from duntale-dev
├── LevelUpResult.java            — Copy from duntale-dev
├── StatPointReward.java          — Custom Reward: grants unassigned stat points
└── StatAssignmentPage.java       — InteractiveCustomUIPage for spending points
```

---

## Part 4: Plugin Integration

### ZSquadPlugin changes

```
setup():
  1. DatabaseConnection.initialize(dataDir / "zsquad.db")
  2. GoldRepository.initialize() → GoldService
  3. RpgRepository.initialize() → RpgService
  4. ProgressionRepository.initialize() → ProgressionService
  5. Register CurrencyDrop component (ECS)
  6. Register GoldPickupSystem (ECS)
  7. Register RpgDamageScalingSystem (ECS, FilterDamage)
  8. Register StatAssignmentPage via OpenCustomUIInteraction.registerSimple()
  9. Register /gold and /stat commands
  10. Register player join/leave events → RpgService.onPlayerJoin/Leave
  11. Hook ProgressionService.setLevelUpListener() → StatPointReward delivery

shutdown():
  12. DatabaseConnection.close()
```

### Modified Files

| File | Change |
|---|---|
| `loot/LootTable.java` | Add `roll(npcLevel, luckLevel)` overload with drop bonus + bonus rolls |
| `loot/NpcLootSystem.java` | Resolve attacker from DeathComponent, pass Luck to roll, grant XP, mark gold with CurrencyDrop |
| `camera/MovementHelper.java` | Add `float moveSpeed` parameter to `sendVelocity()` and `beginMovement()` |
| `camera/ClickToMoveManager.java` | Compute per-player `throttleNs` and `moveSpeed` from RpgService |
| `ZSquadPlugin.java` | Wire DB, services, systems, commands, ECS component registration |

---

## File Summary

| Package | File | New/Modified | Purpose |
|---|---|---|---|
| `db/` | `DatabaseConnection.java` | New | Shared SQLite connection |
| `economy/` | `CurrencyDrop.java` | New | ECS marker component for gold items |
| `economy/` | `GoldRepository.java` | New | SQL CRUD for gold |
| `economy/` | `GoldService.java` | New | Public gold API (MAX_BALANCE cap, atomic transfer, logging) |
| `economy/` | `GoldPickupSystem.java` | New | Convert gold items to currency |
| `economy/` | `GoldCommand.java` | New | Admin debug command |
| `rpg/` | `RpgStat.java` | New | 7-stat enum |
| `rpg/` | `RpgProfile.java` | New | Per-player stat snapshot |
| `rpg/` | `RpgRepository.java` | New | SQL CRUD for stats |
| `rpg/` | `RpgService.java` | New | Stats API + cache + bounds enforcement |
| `rpg/` | `RpgStatEffects.java` | New | Formula computations (hyperbolic helper) |
| `rpg/` | `RpgDamageScalingSystem.java` | New | Strength (outgoing) + Resistance (incoming) |
| `rpg/` | `RpgConstants.java` | New | All tunable constants + stat bounds |
| `rpg/` | `RpgStatCommand.java` | New | Admin debug command |
| `progression/` | `ProgressionRepository.java` | New (copy) | XP + level persistence |
| `progression/` | `ProgressionService.java` | New (copy) | XP granting, level calc |
| `progression/` | `LevelUpResult.java` | New (copy) | Level-up result record |
| `progression/` | `StatPointReward.java` | **Deferred** | Grants stat points on level-up (needs Reward interface) |
| `progression/` | `StatAssignmentPage.java` | **Deferred** | CustomUI page for spending points (needs UI template) |
| `loot/` | `LootTable.java` | Modified | Luck overload |
| `loot/` | `NpcLootSystem.java` | Modified | Attacker resolution, Luck, XP grant, CurrencyDrop |
| `camera/` | `MovementHelper.java` | Modified | Dynamic speed parameter |
| `camera/` | `ClickToMoveManager.java` | Modified | Dynamic throttle + speed |
| — | `ZSquadPlugin.java` | Modified | Wire everything |

**Total**: ~20 new files, ~9 modified files. (**19 implemented**, 2 deferred)

---

## Implementation Phases

### Phase 1: Core Infrastructure — COMPLETE
- [x] `DatabaseConnection`, `GoldRepository`, `GoldService`
- [x] `RpgStat`, `RpgProfile`, `RpgRepository`, `RpgService`, `RpgConstants`
- [x] Gold_Coin.json asset + `CurrencyDrop` component
- [x] `/gold` and `/stat` admin commands

### Phase 2: Gameplay Hooks — COMPLETE
- [x] `RpgStatEffects` formula computations
- [x] `RpgDamageScalingSystem` (Strength outgoing + Resistance incoming)
- [x] `GoldPickupSystem` (auto-pickup → balance)
- [x] `MovementHelper` + `ClickToMoveManager` integration (Speed + Agility)
- [ ] Vitality + Stamina engine stat modifiers — **deferred** (needs `EntityStatMap` API verification)
- [x] `NpcLootSystem` updates (attacker resolution, Luck, gold CurrencyDrop)
- [x] `LootTable.roll()` Luck overload

### Phase 3: Progression — COMPLETE
- [x] Copy + adapt Progression system from duntale-dev
- [x] Kill detection → XP grant in `NpcLootSystem` (BASE_XP=10 × npcLevel)
- [x] Levels table seeded (100 rows from duntale.db)
- [x] `ProgressionService.onPlayerJoin()` wired in `ZSquadPlugin.onPlayerConnect()`
- [x] XP grant verified: fires once per NPC kill only (DeathComponent added on death, not damage)
- [x] **XP curve rebalanced for PvE** — thresholds multiplied by 5× (original curve was tuned for rare PvP kills; PvE mobs die much faster)
- [x] Stat point rewards on level-up — `ProgressionService.setLevelUpListener()` → `RpgService.grantStatPoints(uuid, POINTS_PER_LEVEL=3)`
- [x] `StatAssignmentPage` (InteractiveCustomUIPage + `.ui` template) + `/assignstats` command

### Phase 4: Enhancements — COMPLETE
- [x] **Death penalty**: 10% gold loss on player death — `PlayerDeathPenaltySystem` extends `DeathSystems.OnDeathSystem`, registered as ECS system
- [x] **Custom HUD/Scoreboard**: `ZSquadScoreboard` extends `CustomUIHud` + `ZSquadScoreboardData` builder + `ZSquadScoreboard.ui` template — shows gold, level, XP bar, 7 RPG stats. Created on player connect, updated on level-up
- [x] **Level-up + Stat Point Assignment**: `RpgService` extended with unassigned points cache (`POINTS_PER_LEVEL=3`). `StatAssignmentPage` (InteractiveCustomUIPage + `.ui` template) with per-stat "+" buttons. `/assignstats` player command. Level-up listener wired in `ZSquadPlugin`
- [x] **Merchant system**: `MerchantPriceRegistry` (pricing from `ScalingDataCache`), `MerchantContainer` (buy/sell zones with gold validation gates), `MerchantService` (session management + transaction handling with reentrancy guard), `MerchantTooltipProvider` (DynamicTooltips buy/sell price display), `CatalogEntry` record, `/merchant` debug command. Container-based approach via `setPageWithWindows(Page.Bench, ..., ContainerWindow(...))`
- ~~**Temporary stat buffs**~~: **Will not do** — YAGNI, no clear use case yet

### Phase 5: Economy Wiring (Round 8) — COMPLETE
- [x] **Listener-based scoreboard updates**: `GoldService.GoldChangeListener`, `ProgressionService.XPGrantListener`, `RpgService.StatChangeListener` — all wired in `ZSquadPlugin` to call `updateScoreboard()` on gold change, XP grant (non-levelup), and stat change
- [x] **Gold_Coin in all loot tables**: Added `LootEntry.Simple("Gold_Coin", ...)` to all 12 existing NPC loot tables with zone-scaled quantities (Trork 1-3g, Goblin 2-5g, Skeleton 3-10g, Zombie 5-12g)
- [x] **Arcane floor enemy loot tables**: Added 5 new Outlander NPC loot tables (Stalker, Berserker, Sorcerer, Marauder, Brute boss) with 2 rolls, 0.80 drop chance, and 10-25g gold drops
- [x] **MerchantCommand real catalog**: Replaced hardcoded `TEST_CATALOG` with dynamic catalog sourced from `MerchantPriceRegistry.getItemIds()`, sorted by price ascending, capped at 24 items
- [x] **MerchantPriceRegistry name format fix**: Added `toAssetId()` to convert space-separated names from `ScalingDataCache` to underscore-separated asset IDs. Added `getItemIds()` returning `Set<String>`
- [x] **Merchant buy→sell tags**: After purchase, `tagBoughtItemInInventory()` scans player inventory for the just-bought item, strips `META_BUY_PRICE` + `META_GOLD` display tags, and applies `META_SELL_PRICE` with the sell price
- [x] **Merchant close cleanup**: `MerchantWindow` (extends `ContainerWindow`) overrides `onClose0()` → calls `MerchantService.cleanupInventoryMetadata()` which strips all `META_BUY_PRICE` + `META_GOLD` tags from player inventory. Leaves `META_SELL_PRICE` intact for tooltip display outside merchant

---

## Part 5: Merchant System

### Engine Research Summary

Investigated three approaches for the merchant UI:

1. **`InteractiveCustomUIPage` (BarterPage pattern)** — Click-based ItemSlot + buttons. No drag-and-drop (CustomPage and InventoryPage are mutually exclusive on the client). Requires `.ui` templates, event codecs, and manual inventory rendering.

2. **`openCustomPageWithWindows()`** — Exists in API but is broken. Sends CustomPage + OpenWindow packets, but window rendering only fires on `InventoryPage` (which is unmounted when CustomPage is shown). Definitively unusable.

3. **`setPageWithWindows(Page.Bench, ..., ContainerWindow(...))`** — Opens the native inventory page (Page.Bench) with a custom `ItemContainer` as a window alongside the player's inventory. **Full native drag-and-drop** between player inventory and the custom container. Proven by HytaleVault plugin ([github.com/joogiebear/HytaleVault](https://github.com/joogiebear/HytaleVault)).

**Decision: Use Container-based approach (option 3).** Native drag-and-drop via `setPageWithWindows(Page.Bench, ...)` with a custom `MerchantContainer`. Buy/sell prices displayed via DynamicTooltipsLib on item tooltips. No `.ui` templates needed. No custom event codecs needed.

### Design

**Container-based merchant with drag-and-drop + dynamic tooltips:**

The merchant window is a custom `ItemContainer` opened alongside the player's inventory via `setPageWithWindows(Page.Bench, ...)`. The container is divided into two zones:

- **Buy zone** (top slots): Pre-populated with display items the merchant sells. Player takes items from buy zone → gold deducted, slot refilled.
- **Sell zone** (bottom slots): Empty. Player drags items from inventory into sell zone → gold credited, item consumed.

DynamicTooltipsLib displays buy/sell prices and gold balance on each item's tooltip.

### How It Works

**Opening**: Player interacts with merchant NPC → `MerchantService.openMerchant()`:
1. Creates `MerchantContainer` (capacity = buyCatalogSize + sellSlots)
2. Populates buy zone with display ItemStacks including price metadata
3. Wraps in `ContainerWindow`, opens with `pageManager.setPageWithWindows(ref, store, Page.Bench, true, containerWindow)`
4. Player sees native Hytale inventory UI + merchant container — full drag-and-drop

**Buying**: Player takes item from buy zone (drag to inventory):
1. Engine calls `cantRemoveFromSlot(slot)` → checks `goldService.hasEnough(playerId, buyPrice)` → if insufficient, move blocked (item snaps back)
2. If affordable: engine moves item to player inventory → `changeEvent` fires
3. Change handler: deducts gold, refills display item in buy slot, updates tooltips, sends chat notification

**Selling**: Player puts item into sell zone (drag from inventory):
1. Engine calls `cantAddToSlot(slot, item, existing)` → checks if item is sellable (exists in price registry) → if not, move blocked
2. If sellable: engine places item in sell zone → `changeEvent` fires
3. Change handler: credits gold, immediately removes item from sell slot (consumed), updates tooltips, sends chat notification

**Gold balance sync**: After every buy/sell, `MerchantTooltipProvider` is refreshed. Each buy item's metadata encodes current gold balance → tooltip shows "Your Gold: X" on every merchant item.

### Pricing System

#### Data Source: `scaling.db` (`weapon_base` + `armor_base` tables)

Already loaded by `ScalingDataCache` (existing infrastructure). The cache provides `WeaponBaseRow` (128 entries) and `ArmorBaseRow` (108 entries), each with `itemLevel`, `quality`, `baseDamage`/`physResist`.

#### Price Formula

**`buyPrice = itemLevel² × qualityCoefficient`**

| Quality | Coefficient | Rationale |
|---|---|---|
| Common | 1.0 | Starter / vendor trash |
| Uncommon | 1.5 | Standard craftable gear |
| Rare | 2.5 | Dungeon / boss drops |
| Epic | 5.0 | Endgame only |

**Armor slot multiplier**: Chest=1.0, Legs=0.75, Head=0.6, Hands=0.5

**Buy/Sell asymmetry**: Sell price = **80%** of buy price (20% gold sink).
```
buyPrice  = itemLevel² × qualityCoefficient [× slotMultiplier for armor]
sellPrice = floor(buyPrice × 0.80)
```

**Example prices:**
| Item | iLvl | Quality | Buy Price | Sell Price |
|---|---|---|---|---|
| Crude Sword | 5 | Common | 25 | 20 |
| Copper Axe | 10 | Common | 100 | 80 |
| Iron Sword | 20 | Uncommon | 600 | 480 |
| Thorium Longsword | 30 | Rare | 2,250 | 1,800 |
| Adamantite Sword | 40 | Rare | 4,000 | 3,200 |
| Mithril Sword | 50 | Epic | 12,500 | 10,000 |

**Excluded**: Developer quality, `_NPC` suffix, Technical quality (guns), joke items.

### Architecture

```
merchant/
├── MerchantPriceRegistry.java    — Price catalog (reads ScalingDataCache at init)
├── MerchantService.java          — Opens merchant windows, manages sessions, buy/sell events, inventory tag management
├── MerchantContainer.java        — Custom ItemContainer with buy/sell zones + gold checks
├── MerchantWindow.java           — ContainerWindow subclass: onClose0() triggers inventory metadata cleanup
└── MerchantTooltipProvider.java  — DynamicTooltips provider for price + balance display
```

No `.ui` templates. No custom event codecs. Pure container-based.

### `MerchantPriceRegistry`

Reads from `ScalingDataCache` (already loaded from `scaling.db`). No separate DB access needed.

```java
public class MerchantPriceRegistry {
    static final double SELL_RATIO = 0.80;

    /** Populated from ScalingDataCache.listWeapons() + listArmor() */
    void initialize(ScalingDataCache scalingCache);

    long getBuyPrice(String itemId);     // itemLevel² × qualityCoeff [× slotMult]
    long getSellPrice(String itemId);    // floor(buyPrice × SELL_RATIO)
    boolean isSellable(String itemId);   // exists in registry, not excluded

    static double qualityCoefficient(@Nullable String quality);
    static double slotMultiplier(@Nullable String slot);
}
```

### `MerchantContainer`

Extends `ItemContainer` (same pattern as HytaleVault's `VaultContainer`). Manages buy/sell zone logic and gold validation in slot filters.

```java
public class MerchantContainer extends ItemContainer {
    private final short buyCapacity;      // number of buy slots (top)
    private final short sellCapacity;     // number of sell slots (bottom)
    private final MerchantPriceRegistry priceRegistry;
    private final GoldService goldService;
    private UUID playerId;                // set per-session

    /** Buy zone = slots [0, buyCapacity). Sell zone = [buyCapacity, capacity). */
    boolean isBuySlot(short slot) { return slot < buyCapacity; }
    boolean isSellSlot(short slot) { return slot >= buyCapacity; }

    @Override
    protected boolean cantRemoveFromSlot(short slot) {
        if (isBuySlot(slot)) {
            // Block if player can't afford the item
            ItemStack item = internal_getSlot(slot);
            if (item == null) return true;
            long price = priceRegistry.getBuyPrice(item.getItemId());
            return !goldService.hasEnough(playerId, price);
        }
        return false; // sell zone: allow removal (shouldn't happen — items consumed)
    }

    @Override
    protected boolean cantAddToSlot(short slot, ItemStack item, ItemStack existing) {
        if (isBuySlot(slot)) return true; // can't put items into buy zone
        // Sell zone: only accept sellable items
        return !priceRegistry.isSellable(item.getItemId());
    }
}
```

### `MerchantService`

Manages merchant sessions per-player (similar to VaultUI's VaultSession pattern).

```java
public class MerchantService {
    record MerchantSession(UUID playerId, MerchantContainer container, ContainerWindow window,
                           List<CatalogEntry> catalog) {}

    private final Map<UUID, MerchantSession> openSessions = new ConcurrentHashMap<>();

    /** Opens a merchant for the player with the given catalog. */
    void openMerchant(Player player, PlayerRef playerRef, Ref<EntityStore> ref,
                      Store<EntityStore> store, List<CatalogEntry> catalog);

    /** Closes the merchant session. */
    void closeMerchant(UUID playerId);

    /**
     * Called by MerchantContainer's changeEvent.
     * - Item removed from buy zone → deduct gold, refill slot, notify, tag bought item with sell price
     * - Item added to sell zone → credit gold, consume item, notify
     */
    void handleContainerChange(UUID playerId, ItemContainerChangeEvent event);

    /** Tags the just-bought item in player inventory with META_SELL_PRICE, stripping display metadata. */
    private void tagBoughtItemInInventory(PlayerRef playerRef, String itemId, long sellPrice);

    /** Strips merchant display metadata from all inventory items. Called from MerchantWindow.onClose0(). */
    void cleanupInventoryMetadata(UUID playerId, Ref<EntityStore> ref, ComponentAccessor<EntityStore> accessor);
}
```

### `MerchantTooltipProvider`

Implements `TooltipProvider` from DynamicTooltipsLib. Adds price and gold balance to item tooltips.

```java
public class MerchantTooltipProvider implements TooltipProvider {
    @Override
    public TooltipData getTooltipData(String itemId, String metadata) {
        // 1. Check for merchant buy metadata: "merchant_buy_price:600,merchant_gold:12345"
        //    → Shows: "Buy: 600 Gold" and "Your Gold: 12,345"
        if (metadata != null && metadata.contains("merchant_buy_price")) {
            long price = extractLong(metadata, "merchant_buy_price");
            long gold = extractLong(metadata, "merchant_gold");
            return TooltipData.builder()
                .hashInput("merch_buy:" + price + ":" + gold)
                .addLine(colorTag("#FFD700", "Buy: " + price + " Gold"))
                .addLine(colorTag("#AAAAAA", "Your Gold: " + formatGold(gold)))
                .build();
        }

        // 2. For any sellable item (always shows sell value)
        if (priceRegistry.isSellable(itemId)) {
            long sellPrice = priceRegistry.getSellPrice(itemId);
            return TooltipData.builder()
                .hashInput("merch_sell:" + sellPrice)
                .addLine(colorTag("#55FF55", "Sell: " + sellPrice + " Gold"))
                .build();
        }
        return null;
    }
}
```

**Key**: Buy items have price/balance embedded in ItemStack metadata (BsonDocument). The tooltip provider reads this metadata to show buy price + gold balance. For sell items, the sell price is deterministic per item ID so no metadata injection is needed — the provider calculates it from the registry.

**After each transaction**: Update all buy item metadata with the new gold balance → window auto-syncs (engine dirty flag) → tooltip provider generates updated text.

### Container Layout (Visual)

```
┌─────────────────────────────────────────┐
│         MERCHANT WINDOW                 │
│  ┌─────────────────────────────┐        │
│  │  BUY ZONE (pre-populated)  │        │
│  │  [Crude Axe] [Iron Sword]  │        │
│  │  [Copper Helm] [Bronze Leg]│        │  ← Drag FROM here to buy
│  │  [Thorium Chest] [...]     │        │     (gold checked, refilled)
│  ├─────────────────────────────┤        │
│  │  SELL ZONE (empty)         │        │
│  │  [ ] [ ] [ ] [ ]          │        │  ← Drag TO here to sell
│  │  [ ] [ ] [ ] [ ]          │        │     (gold credited, item consumed)
│  └─────────────────────────────┘        │
│                                         │
│  PLAYER INVENTORY (native)              │
│  [Hotbar] [Storage] [Armor] [Backpack]  │
└─────────────────────────────────────────┘
```

Tooltips (via DynamicTooltipsLib):
- Buy zone items: "Iron Sword / Lv.20 Dungeon Weapon / Power: 23.2 / **Buy: 600 Gold** / **Your Gold: 12,345**"
- Player inventory items: "Iron Sword / Lv.18 Dungeon Weapon / Power: 21.0 / **Sell: 480 Gold**"

### Merchant Catalog Configuration

Each merchant NPC has a **catalog** defining what it sells:
```java
record CatalogEntry(String itemId, int level) {}
// e.g. CatalogEntry("Weapon_Axe_Crude", 5) → sells a Lv5 Crude Axe
```

Catalogs are registered per-merchant in `ZSquadPlugin.setup()`. Different merchants specialize (beginner weapons, advanced armor, etc.).

### Integration

- **Dependency**: DynamicTooltipsLib (already `compileOnly` in build.gradle.kts, optional at runtime)
- **NPC interaction**: `/merchant` command for testing, NPC interaction for production
- **Session lifecycle**: `MerchantService.openMerchant()` on interact, `closeMerchant()` on page close / disconnect
- **Gold sync**: `MerchantContainer.registerChangeEvent()` → `MerchantService.handleContainerChange()` → `GoldService`
- **Tooltip refresh**: After each transaction, update buy item metadata → triggers re-send via engine's `PlayerSendInventorySystem`

### Key Engine APIs Used

| API | Usage |
|---|---|
| `ItemContainer` (extend) | Custom container with buy/sell zones |
| `ContainerWindow` (extend → `MerchantWindow`) | Wraps MerchantContainer; overrides `onClose0()` for inventory cleanup |
| `ContainerWindow.onClose0(Ref, ComponentAccessor)` | Fires when player dismisses window — triggers metadata cleanup |
| `PageManager.setPageWithWindows(Page.Bench, ...)` | Opens native inventory + merchant window |
| `ItemContainer.registerChangeEvent()` | Listen for buy/sell transactions |
| `ItemContainer.cantRemoveFromSlot()` | Block buy if insufficient gold |
| `ItemContainer.cantAddToSlot()` | Block sell if item not sellable |
| `ScalingDataCache` | Existing weapon/armor data for pricing |
| `DynamicTooltipsApi.registerProvider()` | Price + balance tooltip display |
| `ItemStack.withMetadata(key, codec, value)` | Embed buy price + gold in display items; pass `null` to remove a key |
| `ItemStack.getFromMetadataOrNull(key, codec)` | Check if metadata key exists without deprecated `getMetadata()` |
| `Player.getInventory().getHotbar()/getStorage()` | Iterate player inventory containers for tag operations |

### Reference: HytaleVault Pattern

The [HytaleVault](https://github.com/joogiebear/HytaleVault) plugin demonstrates this approach:
- `VaultContainer extends ItemContainer` — custom container with `cantAddToSlot` blacklist filtering
- `VaultUI.openVault()` → `pageManager.setPageWithWindows(ref, store, Page.Bench, true, new ContainerWindow(container))`
- `container.registerChangeEvent()` for real-time persistence
- Engine auto-syncs window changes to client via `PlayerSendInventorySystem`

---

## Design Decisions Log

| Decision | Rationale |
|---|---|
| SQLite writes on WorldThread | DB is small, write latency is ~1-10ms. Premature to async. Revisit if profiling shows bottleneck. |
| `CurrencyDrop` marker instead of `PreventPickup` query | `PreventPickup` is used by the engine for unrelated purposes. Dedicated marker = zero false positives, no per-tick string comparison. |
| Hyperbolic formulas for combat stats | Diminishing returns prevent runaway scaling. Linear resource stats (Stamina/Vitality) are predictable capacity. |
| Resistance multiplicative with armor DR | `(1-armorDR) × (1-resistanceDR)` prevents total DR from exceeding 100%. Additive would allow it. |
| Vitality uses separate modifier key | `"RPG_VITALITY"` key coexists with armor's health modifier key. Engine sums all ADDITIVE MAX modifiers per stat. |
| No temporary modifiers for v1 | YAGNI. Data model uses `int` base stats only. Adding transient modifiers later = extend `RpgProfile`. |
| No death penalty for v1 | Design decision — documented for future consideration. |
| XP curve 5× rebalance | Original levels table from duntale-dev was tuned for PvP (rare kills). PvE dungeon mobs die much more frequently — one floor was granting 13+ levels. Multiplied all thresholds by 5× so early floors grant 2-3 levels. Can be adjusted further after playtesting. |
| Temp stat buffs = won't do | YAGNI — no concrete use case. Can revisit if potion/buff system is needed later. |
| Merchant uses Container + Page.Bench, not InteractiveCustomUIPage | `openCustomPageWithWindows()` is broken (Windows only render on InventoryPage, which is unmounted when CustomPage is shown). `InteractiveCustomUIPage` (BarterPage) works but is click-only. HytaleVault plugin proves `setPageWithWindows(Page.Bench, ..., ContainerWindow(customContainer))` gives full native drag-and-drop. Container-based is simpler (no `.ui` template, no event codecs) and better UX. |
| Sell price = 80% of buy price | 20% gold sink on every sell. Prevents infinite gold via buy-sell loops and gives gold a natural drain alongside the death penalty. |
| Price = itemLevel² × qualityCoefficient | Uses actual `item_level` and `quality` from `scaling.db`'s `weapon_base`/`armor_base` tables via existing `ScalingDataCache`. Quadratic scaling creates natural exponential curve. No separate DB loading needed. |
| Tooltip-based price display | DynamicTooltipsLib (already integrated) shows buy/sell prices on item tooltips. Buy items embed price + gold balance in BsonDocument metadata. Sell prices are deterministic per item ID — shown globally on all sellable items. No custom UI needed for price display. |
| Buy→sell tag conversion on purchase | After a player takes an item from the buy zone, `tagBoughtItemInInventory()` finds the item in inventory and replaces `META_BUY_PRICE`+`META_GOLD` (display-only) with `META_SELL_PRICE` (persistent sell value). This lets DynamicTooltips show sell price on items the player owns. |
| `MerchantWindow` extends `ContainerWindow` | Subclass instead of raw `ContainerWindow` to hook `onClose0()`. Triggers `cleanupInventoryMetadata()` which strips `META_BUY_PRICE` and `META_GOLD` from all inventory items. Leaves `META_SELL_PRICE` intact — it's useful outside the merchant for tooltip display. |
| Listener pattern for scoreboard updates | Added `@FunctionalInterface` listeners (`GoldChangeListener`, `XPGrantListener`, `StatChangeListener`) on existing services instead of event bus. Single listener per service is sufficient — only `ZSquadPlugin` subscribes. Avoids over-engineering an event system for 3 consumers. |
| `MerchantPriceRegistry.toAssetId()` | `ScalingDataCache` stores item names with spaces (e.g. "Crude Sword") but the Hytale asset registry uses underscores ("Crude_Sword"). The registry normalizes names to asset IDs at cache time via `name.replace(' ', '_')`. |

---

## Research Resolution Log

- [x] **Custom item definition**: Gold_Coin.json → `src/main/resources/Server/Item/Items/Currency/`. Mod's `IncludesAssetPack: true` merges assets.
- [x] **Item pickup interception**: `PreventPickup` + `CurrencyDrop` components. Custom `EntityTickingSystem` handles proximity detection.
- [x] **CombatScalingSystem compatibility**: Independent multiplicative factors. Resistance applied as separate pass.
- [x] **Max HP modifier stacking**: Confirmed from `EntityStatValue.computeModifiers()` — all ADDITIVE MAX modifiers sum, different keys coexist.
- [x] **Attacker resolution**: `DeathComponent.deathInfo` → `Damage.getSource()` → `EntitySource.getRef()` (verify getter exists during impl)
- [x] **Progression system**: Reuse duntale-dev's `ProgressionService`/`ProgressionRepository` pattern
- [x] **Custom UI for stat assignment**: `InteractiveCustomUIPage<T>` + `OpenCustomUIInteraction.registerSimple()`
- [x] **Merchant UI approach (revised)**: Three approaches investigated: (1) `InteractiveCustomUIPage` (BarterPage pattern) — works but click-only, requires `.ui` template + event codecs. (2) `openCustomPageWithWindows()` — broken (client's Window rendering only fires on InventoryPage, which is unmounted when CustomPage is shown). (3) `setPageWithWindows(Page.Bench, ..., ContainerWindow(customContainer))` — full native drag-and-drop between player inventory and custom container, proven by HytaleVault plugin. **Chose option 3**: container-based with DynamicTooltips for pricing.
- [x] **Container-based merchant (HytaleVault pattern)**: HytaleVault's `VaultContainer extends ItemContainer` + `ContainerWindow` + `setPageWithWindows(Page.Bench, ...)` gives native drag-and-drop. The engine's `ItemContainer` supports: `cantAddToSlot` / `cantRemoveFromSlot` for validation, `registerChangeEvent` for transaction monitoring, automatic window sync via `PlayerSendInventorySystem` dirty-flag system. Custom container per-session, no codec required for runtime-only containers.
- [x] **DynamicTooltips for merchant pricing**: DynamicTooltipsLib already integrated (compileOnly dependency, `GearScalingTooltipProvider` registered). `TooltipProvider.getTooltipData(itemId, metadata)` reads item metadata — buy items embed price + gold balance in `BsonDocument` metadata. Sell prices are deterministic per item ID via `ScalingDataCache`. The provider doesn't receive player UUID, but buy items carry player-specific data in their metadata.
- [x] **Backpack/Inventory rendering**: Backpack is part of `Inventory` data model (sections: storage, armor, hotbar, utility, backpack). Synced to client via `UpdatePlayerInventory` packet. Native inventory UI is entirely client-side — cannot embed it in a `.ui` template. Server reads inventory via `Player.getInventory().getCombinedHotbarFirst()`.
- [x] **Client drag-and-drop (definitive)**: Researched C# client source (`~/client-src/Hytale-C-/`). Three independent blockers for CustomPage drag-and-drop: (1) `CustomPage` and `InventoryPage` are mutually exclusive — `PageContainer.Clear()` then adds one, in `OnPageChanged()`. (2) `CustomPage` has its own `_pageDesktop` separate from the main `Desktop`; drag state lives on InGameView, unreachable from CustomPage. (3) CustomPage's event bindings don't support `Dropped` callback signature. However, `Page.Bench` (InventoryPage) supports full drag-and-drop natively — this is the correct approach for container-based merchant.
- [x] **Weapon/armor pricing data**: `scaling.db` `weapon_base` (128 weapons) + `armor_base` (108 pieces) already loaded by `ScalingDataCache`. Provides `itemLevel`, `quality`, `baseDamage`/`physResist` for all items. Formula `itemLevel² × qualityCoefficient` auto-scales. `ScalingDataCache.listWeapons()` and `listArmor()` provide bulk access for price registry initialization.
- [x] **ItemContainer transaction model**: All moves between containers use `ItemContainer.moveItemStackFromSlotToSlot()` with nested write locks. Change events fire via `registerChangeEvent(Consumer<ItemContainerChangeEvent>)`. `cantRemoveFromSlot` / `cantAddToSlot` run before the move — can dynamically check gold balance if the container stores a player UUID reference. Engine auto-syncs window state to client every tick via dirty-flag polling in `PlayerSendInventorySystem`.
