# Economy v2 — Balancing Plan (Proposal)

Status: Proposal — awaiting approval
Drafted: 2026-06-12
Governed by: economy-design-pillars.md
Data basis: economy-discovery.md + scaling.db discovery tables

All concrete numbers below are DRAFT anchors to be validated with the offline
pipeline (`discover_economy.py` becomes the regression harness: re-run after
each tuning change and diff projections against the targets in W6).

---

## W1. Durability removal (P1) — small, independent

- Strip `brokenFactor` from `CombatScalingSystem`; gear contributes full
  effectiveness regardless of durability.
- Strip the durability sell factor from `MerchantService`.
- Remove `Tool_Repair_Kit_Iron` entries from `CatalogGenerator` (frees one
  consumable slot + removes a 25k price point).
- Prevent durability loss on Duntale-managed gear (runtime: keep durability
  pinned to max, or asset overlay with no `DurabilityLossOnHit`). Decide
  mechanism at implementation (`/dun-specify`).

## W2. Mob archetype anchors (P4) — foundation

Draft anchor table (base stats at L1; scaled by the existing CombatScaling
sigmoid: HP ×(1+8s), damage ×(1+5s)):

| Archetype | Base HP | Base dmg | Role examples |
|---|---|---|---|
| Swarm | 14 | 4 | Scarak_Louse, Larva_Silk |
| Standard | 45 | 9 | Zombie, Scarak_Fighter, Trork_Warrior |
| Caster | 38 | 12 | Skeleton_Mage, Trork_Shaman, Outlander_Sorcerer |
| Tough | 95 | 14 | Golem_Crystal_Flame, Toad_Rhino, Leopard_Snow |
| Heavy | 190 | 20 | Werewolf, Yeti, Golem_Firesteel, Spawn_Void |
| Boss-base | 320 | 26 | roles spawned as instance bosses |

- Per-role flavor offset: ±15% max, authored in one mapping table
  (role → archetype, hp_offset, dmg_offset) covering all 63 roster roles +
  8 summons. Applied by `NpcScalingApplicator` instead of asset base stats.
- Elite/Boss variant multipliers: **deferred to W6** (decided 2026-06-13
  during W2 specification). Re-deriving them before player damage exists (W3)
  would tune against a moving target; the existing stepped tables work on
  normalized bases in the interim. Draft for W6: Elite ×2.5 HP / ×1.5 dmg
  flat; Boss keeps a level-stepped table tuned against archetype anchors.
  The 10k `MAX_SCALED_HP` cap in `NpcScalingApplicator` is also W6's
  (boss multipliers × anchors can exceed it at high floors).
- TTK validation targets (draft): on-level player kills a Standard in ~5 hits,
  Swarm in 1–2, Heavy in ~12; survives ~8–10 on-level Standard hits at
  mid armor. Validated via `simulate_combat.py` after W3.

## W3. Authored gear curves (P2) — depends on W2

- Weapon per-hit damage = `familyAnchor × levelCurve(level) × rarityNudge`
  - `levelCurve` = existing `CombatScaling.weaponMult` sigmoid (1× → 7×).
  - `familyAnchor` set so all families have equal DPS at equal level
    (per-hit differs by attack period: daggers low/fast, mace high/slow).
    Anchors derived from W2 TTK targets.
  - `rarityNudge`: Common 1.000, Uncommon 1.015, Rare 1.030, Epic 1.050,
    Legendary 1.075 (cap per P2).
- Armor authored per slot: slot share of a total on-level DR budget
  (draft: Chest 40%, Legs 25%, Head 20%, Hands 15% of a 55% on-level total,
  cap remains 65%) + flat HP per slot per level.
- Built-in asset stats stop being read for player gear; mod/third-party items
  (Wans/Zets) automatically inherit sane power from their stamped level —
  fixes the Praetorian problem and the Relic/Mythical pricing holes at once.

**Resolved during W3 specification (2026-06-13):**

- **Attack speed (researched):** CTM melee attack cadence is a single
  Agility-driven throttle (`computeAttackThrottleNs`: 400 ms @ Agility 0 →
  140 ms floor), identical for every weapon — *not* per-weapon timing. So the
  "per-hit differs by attack period (daggers fast / mace slow)" assumption above
  does **not** hold: equal per-hit ⇒ equal DPS. Consequence: no per-family
  attack-speed table and no parser change; **all melee families share one anchor**,
  and a separate **Ranged** class anchor (bows/crossbows/flamethrower —
  Secondary-slot, charge/projectile cadence, not throttle-gated) is set by
  derivation + playtest. Implemented as a corrective ratio
  (`anchor / assetBaseDamage`) over the live asset per-hit, reusing `AssetCatalog`'s
  per-weapon base damage — so the Praetorian fix is automatic.
- **Armor HP deferred to W4:** W3 authors **DR only** (a clean damage-time formula
  swap). Flat-HP-per-slot authoring *and* suppression of the engine's native armor
  HP move to W4, which builds the equip/unequip stat hooks for rarity attributes
  anyway. Engine still grants asset armor HP in the interim — neutralized in W4.
- **Pricing untouched:** `MerchantPriceRegistry` keeps the old asset axis through
  W3–W5; the interim power/price mismatch is reconciled by the W6 single-
  `combatValue` rework.
- **Rarity nudge** plumbed now but **inert** (new `duntale_rarity` metadata seam
  defaults to Common/1.0 until W4 stamps it).

**Implemented (2026-06-13):** `GearCurveConfigAsset` + hot-reloadable
`GearCurveRegistry` (mirroring `NpcArchetypes`), `CombatScaling.armorBudgetDR` /
`weaponAuthoredPerHit`, and the damage-time rewire in `CombatScalingSystem`
(weapon corrective ratio `anchor/assetPerHit`, armor slot-share DR). Derived by
`scripts/scaling/derive_gear_curves.py`:
- Melee anchor **12.0** per-hit @ L1 (solved for ~5 hits to kill an on-level
  Standard at the level floor); all melee families share it. Ranged **9.0**
  (×0.75, **playtest**). Hits-to-kill an on-level Standard stays ~5.1–6.5 across
  L1–L100.
- Armor DR budget **10% → 55%** (L1 → L100), per-slot Chest .40 / Legs .25 /
  Head .20 / Hands .15, combined cap unchanged at 65%.
- **Open playtest item:** `simulate_combat.py` was switched to the authored anchor
  + the Agility throttle floor (400 ms @ Agility 0). On that floor the *seconds*-TTK
  reads below the legacy 6–10 s band, because the real melee swing cadence (windup +
  recovery, not just the throttle floor) isn't modeled. Balance is therefore tracked
  on the **hits-to-kill** axis (~5–6, on target); the seconds-band needs in-game
  confirmation. Elite/Boss seconds-TTK/LETHAL flags in the sim are dominated by the
  variant HP/damage multipliers still **deferred to W6** (see W2 note), not the
  on-level Standard axis.

## W4. Rarity + attribute system (P3, P5) — new feature, biggest

- Rarity rolled at generation per source ladder (draft odds):

| Source | Common | Uncommon | Rare | Epic | Legendary |
|---|---|---|---|---|---|
| Regular mob | 70% | 25% | 5% | — | — |
| Elite | — | 45% | 40% | 15% | — |
| Boss (gear roll) | — | — | 40% | 40% | 20% |
| Chest Regular | 35% | 40% | 25% | — | — |
| Chest Epic | — | 30% | 45% | 25% | — |
| Chest Golden/Legendary | — | — | 35% | 40% | 25% |
| Merchant stock | 20% | 45% | 25% | 8% | 2% |

- Boss gold-roll: when the boss roll lands on gold instead of gear, the payout
  is `0.5 × sellValue(bestRarity, bossGearLevel)` — anchored to the BEST
  possible gear outcome of that boss's ladder, not the gear that would have
  rolled. (Definition of "worth" = sell value; draft, revisit if gold rolls
  feel strictly worse than sell-fodder gear.)
- Rarity promotion (P3): two-step roll, both steps Luck-influenced:
  1. Promote at all? draft: `5% + 10% × (luck/50)^1.3`
  2. Tiers jumped: weighted ladder where +1 dominates and +2/+3 weights grow
     with Luck; hard-capped at Legendary.
  Draft numbers; tuned under the W5 luck power budget.
- Attributes per rarity (draft): Common 0–1, Uncommon 1, Rare 2, Epic 3,
  Legendary 4–5; each attribute = one RPG stat with value scaling by gear
  level (e.g. +2 at L10 → +12 at L60).
- Implementation: rarity + attributes in item metadata; equip/unequip hooks
  apply RPG stat bonuses; DynamicTooltipsLib displays rarity color + attribute
  lines; merchant pricing reads rarity/attributes.
- Custom big-ticket items appear in chests at very low, floor-scaled chance
  (draft: 0.5% at F10 → 3% at F50 in Golden/Legendary chests only).

**Resolved during W4 implementation (2026-06-13):**

- **Attributes on weapons AND armor.** Armor contributes via `InventoryChangeEvent`
  (4 armor slots); the held weapon contributes only while it is the active hotbar
  slot, recomputed on `InventorySetActiveSlotEvent`. Attributes are also recomputed
  on **world-join / transition** (player-ready hook) so the held weapon's attributes
  apply immediately without waiting for a slot swap.
- **Effective-stat layer (no profile mutation):** `RpgService.getEffectiveStat = base
  + gear bonus`, summed live from a `GearBonusProvider` (set to
  `GearAttributeService::getBonus`); the persisted `RpgProfile` is never touched.
  Live-read stats (Strength/Resistance/Speed/Agility/Luck) pick the bonus up on their
  next read; ceiling stats (Vitality/Stamina) re-assert via `RpgStatApplicator`. The
  scoreboard HUD now sources **effective** stats.
- **Promotion on all player-context sources:** NPC drops (killer Luck), chests (opener
  Luck), merchant (opener Luck at catalog generation). Chests moved from pre-fill to
  **roll-on-first-open** via a new `UseBlockEvent.Pre` system; the container being
  non-empty is the filled-once guard (survives relog without a flag).
- **Merchant rarity price multiplier added now** (buy at catalog generation; sell reads
  the stack's stamped rarity). Boss gold-roll pays `0.5 × referenceGearValue(npcLevel,
  topRarity)` from `MerchantPriceRegistry` (draft anchor, refined under W6).
- **Armor-HP authoring + engine-HP suppression done here** (the W3 deferral): authored
  `ArmorHpPerSlot` shares + an HP budget curve (`CombatScaling.armorBudgetHp`); applied
  as `Duntale_ArmorHp` plus `Duntale_ArmorHpSuppress = -assetHealthBonus` Health-MAX
  modifiers so the engine's per-piece armor HP is neutralized. Gated on the curve being
  loaded — until then the engine's native armor HP is left intact (safe degrade).
- **Config-driven + hot-reloadable:** all tuning lives in
  `Server/Configs/Scaling/Rarity.json` (`RarityConfigAsset`/`RarityRegistry`), produced
  by `scripts/scaling/derive_rarity.py`. Empty/unloaded → no rarity stamping at all.
- **Luck is an eligible gear attribute — intentional, bounded loop (resolved A):** all loot
  generation reads **effective** Luck (base + gear), so Luck gear compounds into loot quality
  (drop chance + rarity promotion → more/rarer loot → more Luck gear). Kept deliberately; it is
  bounded because promotion chance saturates at `LuckRef` and Luck competes with 5 other stats
  per attribute slot. Revisit only if a Luck-stack build proves dominant in W5 tuning.
- **Merchant rarity is rolled per gear item** (each entry gets its own base roll + two-step
  promotion), not once per catalog; the whole catalog is then cached on the merchant so the first
  opener's Luck locks the rolls for that instance.

## W5. Loot tables + luck rework (P5, P8, P9)

- Author archetype-template loot tables; instantiate for the 24 missing roster
  roles + 7 summons; regenerate existing 41 to the new model. Swarm archetype:
  gold-only, small amounts.
- Base `DropChance` ≈ **0.10** for gear (gold can sit on a separate, higher
  chance so the gold faucet stays steady — P8).
- New luck formula (accelerating, replaces hyperbolic for drop chance):
  `chance(luck) = base + 0.70 × (luck/50)^1.6`, clamped at 0.95.
  Hits the brief: 0.10 base → ~0.41 @30 → 0.80 @50. New RpgConfig keys;
  `RpgStatEffects` change + tests. Bonus-roll mechanic re-evaluated (likely
  removed or pushed to very high luck, since it multiplies inventory pressure).
- **Luck power budget**: Luck now has three loot effects (drop chance,
  promotion chance, promotion tier). They are tuned TOGETHER against one
  guardrail: total loot value EV per kill at Luck 50 stays within a target
  multiple of Luck 0 (draft: ≤6×). Validated in the harness by computing
  value-per-kill as f(luck) across archetypes.
- Variant overlays (`_Elite`/`_Boss`) become thin rarity-shift wrappers over
  the archetype template instead of hand-authored item lists.

**Implemented (2026-06-14):**

- **Independent gear/gold pools.** `LootTable` now rolls a gear pool
  (`entries`/`DropChance`, Luck-boosted) and a gold pool
  (`GoldEntries`/`GoldChance`, Luck-independent) separately;
  `LootTableConfig` parses both and allows a gold-only table (empty gear
  `Entries`) for swarm roles.
- **Boss exclusivity (P5).** NORMAL/ELITE roll both pools independently; BOSS
  rolls gear first and only falls back to the gold reward when no gear drops, so
  the "gold instead of gear" boss payout (`bossGold`) is preserved. The variant
  rarity-shift wrapper was already shipped in W4 (base-table fallback + the
  variant `RaritySource`), so no new wrapper code was needed — only deleting the
  18 `_Elite`/`_Boss` overlays. Signature boss weapons are intentionally dropped.
- **Accelerating Luck drop curve.** `computeLuckDropBonus`/`computeLuckBonusRolls`
  (and the three old `Luck*` RpgConfig keys) are removed; replaced by
  `computeLuckDropChance(base, luck) = min(maxChance, base + coeff·(min(luck,ref)/ref)^exp)`.
  Final keys: `LuckDropCoefficient 0.70`, `LuckDropExponent 1.6`,
  `LuckDropReference 50`, `LuckDropMaxChance 0.95` (0.10 base → ~0.41 @30 → 0.80
  @50). Luck is clamped to the reference, so the per-kill bonus cannot grow
  unbounded. The bonus-roll mechanic is removed.
- **Loot tables regenerated** by `scripts/scaling/generate_loot_tables.py` +
  `archetype_loot_templates.json` (idempotent; writes `loot_table_manifest.json`):
  39 existing base tables transformed in place (thematic **gear** preserved, gold
  moved to the new gold pool, gear `DropChance` 0.10–0.12), 31 new tables created
  for the 24 missing roster roles + 7 summons (DB-queried, level-banded gear),
  swarm roles set gold-only. Gold quantity is **archetype-template-driven** (not
  preserved per-table) so every table matches the validated Luck budget; W6
  refines the amounts. The 2 orphan tables (Risen_Knight, Trork_Guard) are left
  untouched. Hand-authored tables opt out of regeneration with a top-level
  `"Authored": true` flag (runtime-inert). Summons get full archetype tables (farm
  risk accepted; revisit in W6).
- **Luck power budget** derived + validated by
  `scripts/scaling/derive_luck_budget.py` (owns the `Rarity.json` `Promotion`
  block from here on; idempotent report). Promotion tuned gentle — gate L0 5% →
  L50 13%, rarity-value uplift only ~1.02× — so the Luck loot-value ratio is
  dominated by the drop curve, not promotion. Final promotion:
  `BaseChance 0.05, LuckCoeff 0.08, LuckExp 1.2, tiers 85/12/3`. The script
  computes the **actual total loot-value EV(50)/EV(0)** per archetype (gear value
  grounded in on-level buy price × 0.5 resale + the template gold faucet, at
  L10/30/50) and **fails (non-zero exit) on any breach** of the ≤6× guardrail.
  Current result: every archetype within budget (worst 5.79× for Standard/Caster
  @L10). The drop curve alone is ~8× on gear, so the budget holds only because the
  Luck-independent gold faucet dilutes the total — gold floors were set to satisfy
  it; W6 refines them via the income split. Rarity ladder is the full 7 tiers
  (Relic/Abyssal); promotion caps at Abyssal.

## W6. Income & price targets (P6, P7, P8, P9) — the tuning pass

Single price axis for gear: `buy = g(combatValue(level)) × rarityPriceMult`,
where combatValue is DPS-equivalent for weapons and EHP-equivalent for armor —
this removes the structural weapon/armor asymmetry (armor no longer priced on
a capped DR%).

Targets (validated by re-running the projection):

- Net income per floor `I(F)` defined first, with an explicit faucet split
  target (draft: ~50% direct gold drops / ~50% sell-fodder at 50% resale).
  Gold quantities per kill are then SOLVED from that split using the per-theme
  kills/floor projections — not hand-raised. Derivation recorded in the
  harness output so every gold value has backed reasoning. Themes within ±20%
  of `I(F)` — Hive exception handled by floor-band pools, not by nerfing
  swarm counts.
- Median on-level merchant gear ≈ **2–3 × I(F)** (supports the 2–3 floor swap
  cadence with chest/drop gear filling between).
- Death: respawn ≈ **1–1.5 × I(F)**; restart-lower ≈ 0.6 × that. Replace the
  flat ×500/×300 constants with the income-derived curve.
- Custom items re-priced so the 30–45k tier lands ~floor 25 and the top tier
  by ~floor 30+ for a non-farming player (P6).
- Elite/Boss variant multipliers re-derived on normalized archetype bases,
  plus the 10k NPC HP cap (deferred here from W2 — see W2 note).
- Floor configs: revisit the enemy-density spike at floors 5–15
  (0.74–1.0 × 10–16/room vs 0.4 × 5 elsewhere) — either embrace it as the
  designed mid-game rush and budget income around it, or flatten it.
  Also: **Mine theme is in no FloorConfig** — confirm shelved vs missing.

## W7. Sequencing & validation

1. W1 durability (independent, ships first)
2. W2 archetypes (foundation)
3. W3 gear curves (needs W2 TTK anchors)
4. W4 rarity/attributes (new feature, parallel with W3 after design freeze)
5. W5 loot tables + luck (needs W2 archetypes, W4 rarity ladder)
6. W6 tuning pass (needs all above; offline projection as regression harness)

Each workstream goes through `/dun-specify` for its implementation plan, and
MUST include a **value-derivation task**: the draft numbers above are starting
anchors only — the actual values are computed during that workstream's
execution from its governing targets (W2/W3: TTK anchors; W4/W5: luck power
budget + rarity ladder; W6: income split), validated with the harness, and the
derivation recorded. W6 is the global reconciliation pass and may adjust
values set by earlier workstreams once all systems interact.

After every tuning change: `uv run parse_assets.py && uv run
generate_scaling.py && uv run discover_economy.py` and diff section 5/5b
projections against the W6 targets.

## Interpretation points — RESOLVED 2026-06-12

1. Boss gold roll pays 50% of the BEST possible gear roll's worth (not the
   rolled gear). → encoded in W4.
2. Gold faucet boost must be statistically derived from the income split
   targets via the harness, with recorded reasoning — never hand-raised.
   → encoded in W6.
3. Rarity promotion is a two-step roll (promote? / how far?), both steps
   Luck-influenced, tuned inside the W5 luck power budget. → encoded in W4/W5.
