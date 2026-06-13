# Economy v2 — Design Pillars

Status: Decided
Decided: 2026-06-12 (design session, post-discovery)
Data basis: economy-discovery.md (same date)

These are the agreed game-design decisions that bound the economy rebalance.
Balancing work MUST conform to these pillars; changing a pillar requires a new
design decision, not a tuning pass.

## P1. No durability

Gear is fully indestructible. Remove all durability coupling:

- `CombatScalingSystem.brokenFactor` (7% effectiveness at 0 durability) — remove
- `MerchantService` durability sell-price factor (5% floor) — remove
- `Tool_Repair_Kit_Iron` (25,000g) — remove from merchant stock
- No repair mechanics, no kills-per-weapon math anywhere in balance models

Rationale: matches genre references (Fate, Minecraft Dungeons); removes an
entire variable class from every EV/price calculation.

## P2. Authored gear power — level is the power axis

Built-in asset damage/resists are REPLACED by authored curves:

- Weapon damage = f(family, gear level) × rarity nudge. Built-in `BaseDamage`
  is ignored (discovery: L25 weapons span 26–223 damage with zero
  quality correlation — e.g. Weapon_Longsword_Praetorian, 223 dmg, "Common").
- Rarity damage nudge is small and capped: **max ~+7.5% at top rarity**.
  A Common L20 weapon genuinely beats a Legendary L3.
- Armor: authored DR/EHP per slot per level, same principle.

## P3. Rarity = attributes, rolled at generation time

- Rarity is OURS, stamped in item metadata at generation (like
  `duntale_weapon_level` today), NOT the built-in asset quality.
- Any base item can be **promoted** to a higher rarity — built-in asset
  quality is only the cosmetic default. Promotion is a two-step roll, BOTH
  steps influenced by Luck: (1) does the item promote at all? (2) how many
  rarity tiers does it jump?
  - Known caveat: inventory border color stays asset-driven (client side);
    real rarity is displayed via tooltip/name styling (DynamicTooltipsLib).
- Rarity grants bonus RPG-stat attributes (Strength/Speed/Agility/Vitality/
  Luck/Resistance) as tie-breakers; attribute count/strength scales with
  rarity, values scale with gear level. Attributes affect price.
- This is a new system to implement (metadata + equip hooks + tooltips).

## P4. Mob stats normalized to archetypes

- 5–6 archetypes (e.g. swarm / standard / tough / heavy / caster / boss) with
  fixed HP+damage anchors per level; every role maps to one archetype with a
  small (±15%) flavor offset.
  (Discovery: "Standard"-tier roster bases span HP 38–74, dmg 5–29 — flat
  elite/boss multipliers amplify this spread today.)
- Elite/Boss multipliers become safe uniform multipliers on normalized bases.

## P5. Loot source ladder (rarity by source)

- **Regular mobs**: Common / Common+ gear, mostly gold
- **Elites**: higher rarity than regular mobs
- **Bosses**: top-3 rarities; when the roll lands on gold instead of gear, the
  gold amount = **50% of what the BEST possible gear roll would be worth**
  (top rarity at the boss's gear level)
- **Regular chests**: a swap-worthy item — good enough to consider equipping,
  not so good it lasts many floors. Higher chest tiers shift rarity up.
- **Merchant**: usually slightly better than the player's current gear (for a
  player who keeps upgrading), with a mid chance of very good stock
- **Custom big-ticket items** (Speed Boots, Healing Necklaces, …): also
  obtainable from chests at very low chance, increasing with floor level

## P6. Progression cadence

- Players are expected to **swap gear every 2–3 floors** (via chests, drops,
  and merchant combined).
- Big fixed-price custom items: re-price them; attainable by **floor 25–30+**.

## P7. Death economics

- A death costs **~1–2 floors of net income**; an on-level player is expected
  to die roughly every 5–10 floors.
- Current respawn constants (floor×500 / floor×300) get re-derived from the
  new income curve.

## P8. Faucets

- **Resale ratio: 50%** (down from 80%).
- Gold drops get boosted so direct gold remains a relevant faucet alongside
  selling outgrown gear (today selling dominates ~4–8×).

## P9. Theme income parity, with a Hive exception

- Themes are balanced to roughly equal gold/floor overall.
- **Hive** is the deliberate exception (very high NPC volume by design — egg
  props, swarms). Its economy is contained by restricting Hive to specific
  floor-band pools (e.g. 10–15, 20–25) rather than nerfing the swarm feel.
- Swarm-archetype NPCs (Scarak_Louse etc.) drop small gold only, no gear spam.

## Out of scope (for now)

- SimpleEnchantments scrolls/enchants — revisit in a later pass.
- XP curve balancing — only gold/loot/pricing in this effort (XP-richness of
  swarm themes is acknowledged and kept).
