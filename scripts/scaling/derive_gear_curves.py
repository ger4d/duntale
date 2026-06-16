# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Derive authored gear-power curves (weapon anchors + armor DR budget).

Severs the coupling between an item's hand-authored asset stats and its in-game
power. Weapon per-hit and armor damage reduction are driven by gear *level* (plus
an optional rarity nudge) instead of each item's individual asset numbers, so
outliers normalize to their family/slot and third-party items inherit sane power
from their stamped level alone.

Reads ``scaling.db`` (``weapons_base`` for the family roster, ``armor_base`` for
the slot roster) and emits ``GearCurves.json`` — the config asset consumed by
``GearCurveRegistry`` / ``CombatScalingSystem``.

Method:
  1. Melee anchor from the W-prior TTK targets. The Standard NPC archetype anchors
     at 61 base HP; with the shared level curve ``weaponMult`` the on-level scaled
     HP is ``npcScaledHp(61, level)``. Target ~TARGET_HITS player hits to kill an
     on-level Standard. Because melee attack cadence is a single Agility throttle
     (identical for every weapon), equal per-hit means equal DPS, so all melee
     families share one anchor. Solve
     ``anchor = npcScaledHp(61, REF_LEVEL) / (TARGET_HITS * weaponMult(REF_LEVEL))``
     and report the resulting hits-to-kill band across breakpoints.
  2. Ranged anchor from the same per-hit basis, scaled by RANGED_RATIO and flagged
     "playtest" (ranged cadence is charge/projectile, not the melee throttle).
  3. Armor DR budget Min/Max chosen so a full on-level set survives the EHP target
     band; the per-slot shares sum to 1.0, so a complete on-level set lands on the
     budget curve and the combined total is capped downstream at MAX_ARMOR_DR.
  4. Rarity nudges and slot shares emitted from the design ladder.

Run:  uv run derive_gear_curves.py
"""

from __future__ import annotations

import json
import math
import sqlite3
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
DB_PATH = SCRIPT_DIR / "scaling.db"
OUT_JSON = SCRIPT_DIR.parent.parent / "src/main/resources/Server/Configs/Scaling/GearCurves.json"
DISCOVERY_DOC = SCRIPT_DIR.parent.parent / "docs/data-balancing/economy-discovery.md"

# ── Level curve (must mirror com.duntale.progression.CombatScaling) ──────────
MIN_LEVEL, MAX_LEVEL = 1, 100
_MIDPOINT = MAX_LEVEL / 2.0
_STEEPNESS = 7.2 / MAX_LEVEL
_WEAPON_K = 7.0
_NPC_HP_K = 8.0
MAX_ARMOR_DR = 0.65


def _raw_sigmoid(level: int) -> float:
    return 1.0 / (1.0 + math.exp(-_STEEPNESS * (level - _MIDPOINT)))


_SIG_MIN = _raw_sigmoid(MIN_LEVEL)
_SIG_MAX = _raw_sigmoid(MAX_LEVEL)


def sigmoid(level: int) -> float:
    level = max(MIN_LEVEL, min(MAX_LEVEL, level))
    raw = _raw_sigmoid(level)
    denom = _SIG_MAX - _SIG_MIN
    if denom <= 0:
        return 0.0
    return max(0.0, min((raw - _SIG_MIN) / denom, 1.0))


def linear(level: int) -> float:
    return (max(MIN_LEVEL, min(MAX_LEVEL, level)) - MIN_LEVEL) / (MAX_LEVEL - MIN_LEVEL)


def gear_progress(level: int) -> float:
    # Gear-only front-loaded progression (mirrors CombatScaling.gearProgress); NPC scaling stays sigmoid.
    return 0.5 * linear(level) + 0.5 * sigmoid(level)


def weapon_mult(level: int) -> float:
    return 1.0 + _WEAPON_K * gear_progress(level)


def npc_scaled_hp(base_hp: int, level: int) -> int:
    return round(base_hp + _NPC_HP_K * base_hp * sigmoid(level))


# ── Tunables ─────────────────────────────────────────────────────────────────
STANDARD_BASE_HP = 61      # Standard archetype anchor (from NpcArchetypes.json)
REF_LEVEL = 1              # anchor solved at the level floor (weaponMult == 1.0)
TARGET_HITS = 5.0          # player hits to kill an on-level Standard
RANGED_RATIO = 0.75        # ranged per-hit relative to melee (playtest seed)

ARMOR_DR_BUDGET_MIN = 0.10  # total on-level DR at level 1
ARMOR_DR_BUDGET_MAX = 0.62  # total on-level DR at the level ceiling (< MAX_ARMOR_DR)

# Authored armor flat-HP budget (W3 deferral, activated in W4). A full on-level set adds this
# much max-HP via the slot shares below; it replaces the engine's per-piece asset armor HP, which
# is suppressed in parallel (Duntale_ArmorHpSuppress). Draft magnitudes — retuned in W5/W6 against
# the EHP target band alongside the DR budget.
ARMOR_HP_BUDGET_MIN = 30.0   # total on-level authored armor HP at level 1
ARMOR_HP_BUDGET_MAX = 320.0  # total on-level authored armor HP at the level ceiling

# Ranged families fire from the Secondary slot on a charge/projectile cadence — not
# gated by the melee Agility throttle, so they get a separate anchor.
RANGED_FAMILIES = {"Bow", "Crossbow", "Gun"}

RARITY_NUDGES = [
    ("Common", 1.000),
    ("Uncommon", 1.015),
    ("Rare", 1.030),
    ("Epic", 1.050),
    ("Legendary", 1.075),
]

ARMOR_SLOTS = [
    ("Chest", 0.40),
    ("Legs", 0.25),
    ("Head", 0.20),
    ("Hands", 0.15),
]

BREAKPOINT_LEVELS = [1, 15, 30, 45, 60, 80, 100]


def round_half(value: float) -> float:
    """Round to the nearest 0.5 for clean config tunables."""
    return round(value * 2) / 2


def derive_melee_anchor() -> float:
    scaled_hp = npc_scaled_hp(STANDARD_BASE_HP, REF_LEVEL)
    raw = scaled_hp / (TARGET_HITS * weapon_mult(REF_LEVEL))
    return round_half(raw)


def hits_to_kill(anchor: float, level: int) -> float:
    return npc_scaled_hp(STANDARD_BASE_HP, level) / (anchor * weapon_mult(level))


def main() -> int:
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row

    families = sorted({
        r["family"] for r in conn.execute("SELECT DISTINCT family FROM weapons_base")
        if r["family"]
    })
    db_slots = sorted({
        r["slot"] for r in conn.execute("SELECT DISTINCT slot FROM armor_base")
        if r["slot"]
    })

    melee_anchor = derive_melee_anchor()
    ranged_anchor = round_half(melee_anchor * RANGED_RATIO)

    weapon_family_entries = []
    for fam in families:
        anchor = ranged_anchor if fam in RANGED_FAMILIES else melee_anchor
        weapon_family_entries.append({"Name": fam, "AnchorDamage": anchor})

    payload = {
        "DefaultWeaponAnchor": melee_anchor,
        "ArmorDrBudgetMin": ARMOR_DR_BUDGET_MIN,
        "ArmorDrBudgetMax": ARMOR_DR_BUDGET_MAX,
        "WeaponFamilies": weapon_family_entries,
        "RarityNudges": [{"Rarity": r, "Multiplier": m} for r, m in RARITY_NUDGES],
        "ArmorSlots": [{"Slot": s, "DrShare": d} for s, d in ARMOR_SLOTS],
        "ArmorHpBudgetMin": ARMOR_HP_BUDGET_MIN,
        "ArmorHpBudgetMax": ARMOR_HP_BUDGET_MAX,
        # HP shares mirror the DR shares so a full on-level set lands on the HP budget curve.
        "ArmorHpPerSlot": [{"Slot": s, "HpShare": d} for s, d in ARMOR_SLOTS],
    }

    OUT_JSON.parent.mkdir(parents=True, exist_ok=True)
    OUT_JSON.write_text(json.dumps(payload, indent=2) + "\n")

    # ── Console report ────────────────────────────────────────────────
    print(f"Wrote {OUT_JSON.relative_to(SCRIPT_DIR.parent.parent)}")
    print(f"Melee anchor: {melee_anchor}  (ref L{REF_LEVEL}, target {TARGET_HITS:.0f} hits)")
    print(f"Ranged anchor: {ranged_anchor}  (x{RANGED_RATIO:.2f} of melee — PLAYTEST)")
    print(f"Families mapped: {len(families)} "
          f"(ranged: {sorted(f for f in families if f in RANGED_FAMILIES)})")
    print()
    print("Melee hits-to-kill an on-level Standard across breakpoints:")
    print("  " + "  ".join(f"L{lvl}:{hits_to_kill(melee_anchor, lvl):.1f}" for lvl in BREAKPOINT_LEVELS))
    print()
    print(f"Armor DR budget: {ARMOR_DR_BUDGET_MIN:.0%} (L1) -> {ARMOR_DR_BUDGET_MAX:.0%} (L100), "
          f"combined cap {MAX_ARMOR_DR:.0%}")
    print("Full on-level set DR across breakpoints:")
    budget = lambda lvl: ARMOR_DR_BUDGET_MIN + (ARMOR_DR_BUDGET_MAX - ARMOR_DR_BUDGET_MIN) * gear_progress(lvl)
    print("  " + "  ".join(f"L{lvl}:{budget(lvl):.0%}" for lvl in BREAKPOINT_LEVELS))
    print()
    print(f"Authored armor HP budget: +{ARMOR_HP_BUDGET_MIN:.0f} (L1) -> +{ARMOR_HP_BUDGET_MAX:.0f} (L100) "
          f"(replaces engine asset armor HP)")
    hp_budget = lambda lvl: ARMOR_HP_BUDGET_MIN + (ARMOR_HP_BUDGET_MAX - ARMOR_HP_BUDGET_MIN) * gear_progress(lvl)
    print("Full on-level set HP across breakpoints:")
    print("  " + "  ".join(f"L{lvl}:+{hp_budget(lvl):.0f}" for lvl in BREAKPOINT_LEVELS))
    if set(db_slots) - {s for s, _ in ARMOR_SLOTS}:
        print(f"WARNING: DB armor slots not in share table: {set(db_slots) - {s for s, _ in ARMOR_SLOTS}}")

    append_discovery_report(melee_anchor, ranged_anchor, families, budget)
    print(f"\nAppended derivation report to {DISCOVERY_DOC.relative_to(SCRIPT_DIR.parent.parent)}")
    conn.close()
    return 0


def append_discovery_report(melee_anchor, ranged_anchor, families, budget) -> None:
    lines = ["", "## Authored Gear Curves (derived)", ""]
    lines.append("Generated by `scripts/scaling/derive_gear_curves.py`. Weapon per-hit and armor DR")
    lines.append("are driven by gear level instead of each item's asset stats (a corrective ratio at")
    lines.append("damage time divides out the asset number and substitutes the family anchor).")
    lines.append("")
    lines.append(f"- **Melee anchor:** {melee_anchor} per-hit @ L1, solved as "
                 f"`npcScaledHp(61, {REF_LEVEL}) / ({TARGET_HITS:.0f} hits * weaponMult({REF_LEVEL}))`. "
                 "All melee families share it (cadence is a single Agility throttle -> equal per-hit, "
                 "equal DPS).")
    lines.append(f"- **Ranged anchor:** {ranged_anchor} (x{RANGED_RATIO:.2f} of melee). Bows/crossbows/"
                 "guns fire from the Secondary slot on a charge/projectile cadence, not throttle-gated "
                 "— **playtest** before locking.")
    lines.append("")
    lines.append("Melee hits-to-kill an on-level Standard (TTK in seconds depends on the live attack")
    lines.append("cadence; the throttle floor is 400 ms @ Agility 0, so balance is tracked on the")
    lines.append("hits-to-kill axis and confirmed in playtest):")
    lines.append("")
    lines.append("| Level | " + " | ".join(f"{lvl}" for lvl in BREAKPOINT_LEVELS) + " |")
    lines.append("|---|" + "---|" * len(BREAKPOINT_LEVELS))
    lines.append("| Hits | " + " | ".join(f"{hits_to_kill(melee_anchor, lvl):.1f}" for lvl in BREAKPOINT_LEVELS) + " |")
    lines.append("")
    lines.append(f"**Armor DR budget:** {ARMOR_DR_BUDGET_MIN:.0%} (L1) -> {ARMOR_DR_BUDGET_MAX:.0%} "
                 f"(L100); per-slot shares Chest .40 / Legs .25 / Head .20 / Hands .15 sum to 1.0, so a "
                 f"full on-level set lands on the budget curve. Combined total capped at {MAX_ARMOR_DR:.0%}.")
    lines.append("")
    lines.append("| Level | " + " | ".join(f"{lvl}" for lvl in BREAKPOINT_LEVELS) + " |")
    lines.append("|---|" + "---|" * len(BREAKPOINT_LEVELS))
    lines.append("| Full-set DR | " + " | ".join(f"{budget(lvl):.0%}" for lvl in BREAKPOINT_LEVELS) + " |")
    lines.append("")
    lines.append(f"Mapped families ({len(families)}): {', '.join(families)}.")
    lines.append("")
    with DISCOVERY_DOC.open("a") as fh:
        fh.write("\n".join(lines))


if __name__ == "__main__":
    raise SystemExit(main())
