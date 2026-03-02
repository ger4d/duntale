# Implementation Plan: Economy + RPG Stats

**Target project**: `/home/gpmod/lab/duntale/v3-zsquad`
**Package root**: `com.duntale.zsquad`

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
| `progression/` | `StatPointReward.java` | New | Grants stat points on level-up |
| `progression/` | `StatAssignmentPage.java` | New | CustomUI page for spending points |
| `loot/` | `LootTable.java` | Modified | Luck overload |
| `loot/` | `NpcLootSystem.java` | Modified | Attacker resolution, Luck, XP grant, CurrencyDrop |
| `camera/` | `MovementHelper.java` | Modified | Dynamic speed parameter |
| `camera/` | `ClickToMoveManager.java` | Modified | Dynamic throttle + speed |
| — | `ZSquadPlugin.java` | Modified | Wire everything |

**Total**: ~19 new files, ~5 modified files.

---

## Implementation Phases

### Phase 1: Core Infrastructure
- `DatabaseConnection`, `GoldRepository`, `GoldService`
- `RpgStat`, `RpgProfile`, `RpgRepository`, `RpgService`, `RpgConstants`
- Gold_Coin.json asset + `CurrencyDrop` component
- `/gold` and `/stat` admin commands

### Phase 2: Gameplay Hooks
- `RpgStatEffects` formula computations
- `RpgDamageScalingSystem` (Strength outgoing + Resistance incoming)
- `GoldPickupSystem` (auto-pickup → balance)
- `MovementHelper` + `ClickToMoveManager` integration (Speed + Agility)
- Vitality + Stamina engine stat modifiers
- `NpcLootSystem` updates (attacker resolution, Luck, gold CurrencyDrop)
- `LootTable.roll()` Luck overload

### Phase 3: Progression
- Copy + adapt Progression system from duntale-dev
- Kill detection → XP grant in `NpcLootSystem`
- `StatPointReward` for level-up rewards
- `StatAssignmentPage` (InteractiveCustomUIPage)

### Phase 4: Enhancements (Future)
- **Custom HUD/Scoreboard**: Gold balance + stats display (pattern: `BaseScoreboard extends CustomUIHud` from duntale-dev, with `.ui` template + `UICommandBuilder` updates)
- **Merchant system**: NPC shops to spend gold on items/stat resets (gold sink)
- **Death penalty**: Optional % gold loss on death (trivial: `GoldService.removeGold(uuid, balance * penalty)`)
- **Temporary stat buffs**: Potions, area effects — requires extending `RpgProfile` with transient modifier list

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

---

## Research Resolution Log

- [x] **Custom item definition**: Gold_Coin.json → `src/main/resources/Server/Item/Items/Currency/`. Mod's `IncludesAssetPack: true` merges assets.
- [x] **Item pickup interception**: `PreventPickup` + `CurrencyDrop` components. Custom `EntityTickingSystem` handles proximity detection.
- [x] **CombatScalingSystem compatibility**: Independent multiplicative factors. Resistance applied as separate pass.
- [x] **Max HP modifier stacking**: Confirmed from `EntityStatValue.computeModifiers()` — all ADDITIVE MAX modifiers sum, different keys coexist.
- [x] **Attacker resolution**: `DeathComponent.deathInfo` → `Damage.getSource()` → `EntitySource.getRef()` (verify getter exists during impl)
- [x] **Progression system**: Reuse duntale-dev's `ProgressionService`/`ProgressionRepository` pattern
- [x] **Custom UI for stat assignment**: `InteractiveCustomUIPage<T>` + `OpenCustomUIInteraction.registerSimple()`
