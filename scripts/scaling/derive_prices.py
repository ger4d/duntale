# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Derive the combat-value gold mapping, armor EHP weight, gear prices, and custom-item prices.

Companion to ``derive_income.py``. That script defines the smooth per-floor income budget
``I_smooth(F)``; this one prices gear off a single combat-value axis (replacing the weapon-unbounded /
armor-capped asymmetry in ``MerchantPriceRegistry``) so the median on-level price tracks the design
target, and reprices the big-ticket custom items against cumulative income.

What it solves (read-merge-write so ``derive_income.py``'s respawn schedule is preserved):
  1. ``ArmorEhpDrWeight`` (k_dr): balances the armor effective-HP value toward the weapon DPS-equivalent
     value at a reference floor. Note armor value leans on the authored HP budget (a W3 asset), so the
     DR term has limited leverage and k_dr can saturate — the achieved weapon/armor median ratio is
     reported, and the goal is a BOUNDED band (not the old 6x..18x gap), not perfect parity.
  2. ``GoldMappingScale`` / ``GoldMappingExponent``: solved so the median on-level combat-value price
     tracks ``2.5 x I_smooth(F)`` across floors (median on-level gear ~ 2-3x a floor of income — the
     gear-swap-cadence pillar).
  3. ``CustomItemPrices``: solved against cumulative income — the 30-45k tier anchored near the gold a
     non-farming player has banked by ~floor 25, the top tier by ~floor 30+, preserving each item's
     relative ordering.

The HP cap was removed (NpcScalingApplicator no longer clamps), so this script no longer writes
``MaxScaledHp``. The Elite/Boss multiplier tables are owned by ``derive_difficulty.py`` (where they are
the per-floor difficulty lever); this script does not touch them, so re-running it preserves whatever
that script wrote.

Run:  uv run derive_prices.py
"""

from __future__ import annotations

import json
import math
import sqlite3
import statistics
from pathlib import Path

import derive_income  # sibling script: reuse the smooth I(F) budget + paths

SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parent.parent
DB_PATH = SCRIPT_DIR / "scaling.db"
GEAR_CURVES_PATH = ROOT / "src/main/resources/Server/Configs/Scaling/GearCurves.json"
PRICING_PATH = ROOT / "src/main/resources/Server/Configs/Scaling/Pricing.json"
DISCOVERY_DOC = ROOT / "docs/data-balancing/economy-discovery.md"
REPORT_ANCHOR = "## Pricing & variant derivation (derive_prices.py)"

MIN_BUY_PRICE = 25
EXPONENT_GRID = [1.2, 1.3, 1.4, 1.5, 1.6]
KDR_REF_FLOOR = 50          # reference floor for balancing armor vs weapon value
KDR_BOUNDS = (0.1, 12.0)    # widened: lets the solve push armor up without the old hard 5.0 clamp
PRICE_MEDIAN_TO_INCOME = 2.5  # median on-level gear price target = this x I_smooth(F) (the 2-3x pillar)
CUM_MAX_FLOOR = 70          # cumulative income horizon for custom-item reachability
CUSTOM_TIER_LOW, CUSTOM_TIER_HIGH = 30_000, 45_000  # the "mid" big-ticket band anchored to cumI(25)
CUSTOM_ANCHOR_FLOOR = 25
CUSTOM_ROUND = 500          # round solved custom prices to a clean step

# Stable baseline = the authored CustomItems.BUY_PRICES (relative ordering source). Solving from this
# fixed baseline (not the previously-written Pricing.json values) keeps the solve idempotent instead of
# compounding the cumulative-income scale each run.
CUSTOM_BASELINE = {
    "Immunity_Trap_Ring": 35_000, "Speed_Boots_I": 30_000, "Speed_Boots_II": 45_000,
    "Speed_Boots_III": 70_000, "Healing_Necklace_I": 45_000, "Healing_Necklace_II": 125_000,
    "Vampire_Juice": 50_000, "Stat_Point_Token": 7_500, "Palporter": 2_500, "Village_Warp": 5_000,
}

MIN_LEVEL, MAX_LEVEL = 1, 100
MAX_ARMOR_DR = 0.65
_MIDPOINT = MAX_LEVEL / 2.0
_STEEPNESS = 7.2 / MAX_LEVEL
_WEAPON_K = 7.0


def _raw_sigmoid(level: float) -> float:
    return 1.0 / (1.0 + math.exp(-_STEEPNESS * (level - _MIDPOINT)))


_SIG_MIN = _raw_sigmoid(MIN_LEVEL)
_SIG_MAX = _raw_sigmoid(MAX_LEVEL)


def sigmoid(level: int) -> float:
    level = max(MIN_LEVEL, min(MAX_LEVEL, level))
    raw = _raw_sigmoid(level)
    denom = _SIG_MAX - _SIG_MIN
    return max(0.0, min((raw - _SIG_MIN) / denom, 1.0)) if denom > 0 else 0.0


def linear(level: int) -> float:
    return (max(MIN_LEVEL, min(MAX_LEVEL, level)) - MIN_LEVEL) / (MAX_LEVEL - MIN_LEVEL)


def gear_progress(level: int) -> float:
    # Gear-only front-loaded progression (mirrors CombatScaling.gearProgress).
    return 0.5 * linear(level) + 0.5 * sigmoid(level)


def weapon_mult(level: int) -> float:
    return 1.0 + _WEAPON_K * gear_progress(level)


# ── Combat-value replication (mirrors MerchantPriceRegistry) ─────────────────
def load_json(path: Path) -> dict:
    return json.loads(path.read_text())


def gear_curves() -> dict:
    gc = load_json(GEAR_CURVES_PATH)
    return {
        "default_anchor": gc["DefaultWeaponAnchor"],
        "anchors": {e["Name"]: e["AnchorDamage"] for e in gc["WeaponFamilies"]},
        "dr_min": gc["ArmorDrBudgetMin"],
        "dr_max": gc["ArmorDrBudgetMax"],
        "hp_min": gc.get("ArmorHpBudgetMin", 0.0),
        "hp_max": gc.get("ArmorHpBudgetMax", 0.0),
        "dr_shares": {e["Slot"]: e["DrShare"] for e in gc["ArmorSlots"]},
        "hp_shares": {e["Slot"]: e["HpShare"] for e in gc.get("ArmorHpPerSlot", [])},
    }


def weapon_cv(curves: dict, family: str, level: int) -> float:
    anchor = curves["anchors"].get(family, curves["default_anchor"])
    return anchor * weapon_mult(level)


def armor_cv(curves: dict, slot: str, level: int, k_dr: float) -> float:
    s = gear_progress(level)
    hp_term = curves["hp_shares"].get(slot, 0.0) * (
        curves["hp_min"] + (curves["hp_max"] - curves["hp_min"]) * s)
    slot_dr = curves["dr_shares"].get(slot, 0.0) * (
        curves["dr_min"] + (curves["dr_max"] - curves["dr_min"]) * s)
    total_dr = min(MAX_ARMOR_DR, curves["dr_min"] + (curves["dr_max"] - curves["dr_min"]) * s)
    return hp_term + k_dr * slot_dr / (1.0 - total_dr)


def current_median_buy(conn: sqlite3.Connection, floor: int) -> float:
    """Old asset-stat median price (diagnostic only — shows the re-level magnitude)."""
    audit = conn.execute(
        "SELECT MIN(level) FROM item_prices WHERE level >= ?",
        (max(MIN_LEVEL, min(floor, MAX_LEVEL)),)).fetchone()[0]
    if audit is None:
        audit = conn.execute("SELECT MAX(level) FROM item_prices").fetchone()[0]
    buys = [r[0] for r in conn.execute(
        "SELECT buy_stats FROM item_prices WHERE level = ? AND category IN ('weapon', 'armor') "
        "AND buy_stats IS NOT NULL AND buy_stats > 0 AND item_level BETWEEN ? AND ?",
        (audit, max(0, floor - 15), floor + 15))]
    return statistics.median(buys) if buys else 0.0


def solve_k_dr(curves: dict) -> float:
    """Balance mean armor value against mean weapon value at the reference floor (asymmetry fix)."""
    families = list(curves["anchors"]) or [None]
    slots = list(set(curves["dr_shares"]) | set(curves["hp_shares"]))
    mean_weapon = statistics.mean(weapon_cv(curves, f, KDR_REF_FLOOR) for f in families)
    s = sigmoid(KDR_REF_FLOOR)
    total_dr = min(MAX_ARMOR_DR, curves["dr_min"] + (curves["dr_max"] - curves["dr_min"]) * s)
    hp_terms = [curves["hp_shares"].get(sl, 0.0) * (
        curves["hp_min"] + (curves["hp_max"] - curves["hp_min"]) * s) for sl in slots]
    dr_factors = [curves["dr_shares"].get(sl, 0.0) * (
        curves["dr_min"] + (curves["dr_max"] - curves["dr_min"]) * s) / (1.0 - total_dr)
        for sl in slots]
    mean_hp = statistics.mean(hp_terms) if hp_terms else 0.0
    mean_dr = statistics.mean(dr_factors) if dr_factors else 0.0
    if mean_dr <= 0:
        return 1.0
    return max(KDR_BOUNDS[0], min(KDR_BOUNDS[1], (mean_weapon - mean_hp) / mean_dr))


def median_combat_value(curves: dict, families: list, slots: list, floor: int, k_dr: float) -> float:
    cvs = [weapon_cv(curves, f, floor) for f in families] + \
          [armor_cv(curves, sl, floor, k_dr) for sl in slots]
    return statistics.median(cvs) if cvs else 1.0


def solve_custom_prices(curve: tuple) -> tuple[list, dict]:
    """Reprice custom items against cumulative income from the fixed baseline, preserving ordering."""
    cum = {}
    running = 0.0
    for floor in range(1, CUM_MAX_FLOOR + 1):
        running += derive_income.income_smooth(floor, curve)
        cum[floor] = running
    tier = [buy for buy in CUSTOM_BASELINE.values() if CUSTOM_TIER_LOW <= buy <= CUSTOM_TIER_HIGH]
    anchor_now = statistics.median(tier) if tier else statistics.median(list(CUSTOM_BASELINE.values()))
    factor = (cum[CUSTOM_ANCHOR_FLOOR] / anchor_now) if anchor_now > 0 else 1.0
    solved = []
    for item_id, base in CUSTOM_BASELINE.items():
        priced = max(CUSTOM_ROUND, round(base * factor / CUSTOM_ROUND) * CUSTOM_ROUND)
        solved.append({"ItemId": item_id, "Buy": priced})
    return solved, cum


def main() -> int:
    conn = sqlite3.connect(DB_PATH)
    curves = gear_curves()
    per_floor = derive_income.compute_per_floor(conn)
    curve = derive_income.fit_income_curve(per_floor)
    floors = [p[0] for p in per_floor]
    ism = {f: derive_income.income_smooth(f, curve) for f in floors}

    families = list(curves["anchors"])
    slots = list(set(curves["dr_shares"]) | set(curves["hp_shares"]))

    k_dr = solve_k_dr(curves)

    median_cv = {f: median_combat_value(curves, families, slots, f, k_dr) for f in floors}
    current_price = {f: current_median_buy(conn, f) for f in floors}

    # Solve scale/exponent so the median on-level price tracks 2.5 x I_smooth(F).
    usable = [f for f in floors if ism[f] > 0 and median_cv[f] > 0]
    target = {f: PRICE_MEDIAN_TO_INCOME * ism[f] for f in usable}
    best = None
    for exp in EXPONENT_GRID:
        xs = [median_cv[f] ** exp for f in usable]
        num = sum(x * target[f] for x, f in zip(xs, usable))
        den = sum(x * x for x in xs)
        scale = (num / den) if den > 0 else 10.0
        err = sum(((scale * x - target[f]) / target[f]) ** 2 for x, f in zip(xs, usable))
        if best is None or err < best[0]:
            best = (err, exp, scale)
    _err, gold_exp, gold_scale = best
    gold_scale = round(gold_scale, 4)

    # Custom big-ticket prices solved against cumulative income from the fixed baseline.
    custom_prices, cum = solve_custom_prices(curve)

    # Diagnostic: achieved weapon-median vs armor-median value at the reference floor.
    weapon_med = statistics.median(weapon_cv(curves, f, KDR_REF_FLOOR) for f in families)
    armor_med = statistics.median(armor_cv(curves, sl, KDR_REF_FLOOR, k_dr) for sl in slots)
    asym_ratio = (weapon_med / armor_med) if armor_med > 0 else 0.0

    # ── Merge into Pricing.json (preserve derive_income.py's respawn schedule) ──
    pricing = load_json(PRICING_PATH) if PRICING_PATH.exists() else {}
    pricing.pop("MaxScaledHp", None)  # cap removed: NpcScalingApplicator no longer clamps
    pricing["GoldMappingScale"] = gold_scale
    pricing["GoldMappingExponent"] = gold_exp
    pricing["ArmorEhpDrWeight"] = round(k_dr, 4)
    pricing["MinBuyPrice"] = MIN_BUY_PRICE
    # EliteVariantSteps / BossVariantSteps are owned by derive_difficulty.py (the per-floor difficulty
    # lever); left untouched here so re-running price derivation preserves them.
    pricing["CustomItemPrices"] = custom_prices
    PRICING_PATH.write_text(json.dumps(pricing, indent=2) + "\n")
    conn.close()

    # ── Console report ──
    print(f"Wrote pricing tuning -> {PRICING_PATH.relative_to(ROOT)}")
    print(f"Gold mapping: scale {gold_scale}, exponent {gold_exp} "
          f"(median on-level price -> {PRICE_MEDIAN_TO_INCOME:g} x I_smooth).")
    print(f"Armor EHP DR weight k_dr: {k_dr:.3f}; achieved weapon/armor median value ratio "
          f"{asym_ratio:.2f} at L{KDR_REF_FLOOR} (bounded band, not perfect parity).")
    print()
    print("Median on-level gear price (new axis) vs current vs target (2.5 x I_smooth):")
    print(f"  {'floor':>5}{'new':>9}{'current':>9}{'I_smooth':>10}{'new/I':>8}")
    for f in floors:
        new_price = max(MIN_BUY_PRICE, round(median_cv[f] ** gold_exp * gold_scale))
        ratio = (new_price / ism[f]) if ism[f] else 0.0
        print(f"  {f:>5}{new_price:>9}{current_price[f]:>9.0f}{ism[f]:>10.0f}{ratio:>8.2f}")

    upsert_report(gold_scale, gold_exp, k_dr, asym_ratio, per_floor, ism, median_cv,
                  current_price, cum, custom_prices)
    print(f"\nUpserted report -> {DISCOVERY_DOC.relative_to(ROOT)}")
    return 0


def upsert_report(gold_scale, gold_exp, k_dr, asym_ratio, per_floor, ism, median_cv,
                  current_price, cum, custom_prices) -> None:
    if not DISCOVERY_DOC.parent.exists():
        return
    floors = [p[0] for p in per_floor]
    body = [
        f"Single combat-value price axis: weapon value = family anchor x weaponMult(level) "
        f"(a DPS-equivalent), armor value = slot HP share + k_dr x DR share / (1 - totalDR) (an "
        f"effective-HP equivalent). k_dr = {k_dr:.3f}; achieved weapon/armor median value ratio "
        f"{asym_ratio:.2f} at L{KDR_REF_FLOOR}. Armor leans on the authored HP budget (a W3 asset), so "
        "the DR term has limited leverage and k_dr can saturate — the win is a BOUNDED band replacing "
        "the old weapon-unbounded / armor-capped 6x..18x gap, not perfect parity.",
        "",
        f"Gold mapping price = round(combatValue^{gold_exp} x {gold_scale}), solved so the median "
        f"on-level price tracks {PRICE_MEDIAN_TO_INCOME:g} x I_smooth(F) (median gear ~ 2-3x a floor of "
        "income — the gear-swap-cadence pillar). The HP cap was removed, so MaxScaledHp is no longer "
        "written; the Elite/Boss multiplier tables are owned by derive_difficulty.py (the per-floor "
        "difficulty lever) and are not touched here.",
        "",
        "| Floor | new median price | current price | I_smooth(F) | new/I_smooth |",
        "|---|---|---|---|---|",
    ]
    for f in floors:
        new_price = max(MIN_BUY_PRICE, round(median_cv[f] ** gold_exp * gold_scale))
        ratio = (new_price / ism[f]) if ism[f] else 0.0
        body.append(f"| {f} | {new_price} | {current_price[f]:.0f} | {ism[f]:.0f} | {ratio:.2f} |")
    body += [
        "",
        "Custom big-ticket prices solved against cumulative income (30-45k tier anchored to "
        f"cumI({CUSTOM_ANCHOR_FLOOR}); ordering preserved):",
        "",
        "| Item | Buy | reachable ~floor |",
        "|---|---|---|",
    ]
    for entry in custom_prices:
        reach = next((f for f in range(1, CUM_MAX_FLOOR + 1) if cum.get(f, 0) >= entry["Buy"]),
                     CUM_MAX_FLOOR)
        body.append(f"| {entry['ItemId']} | {entry['Buy']} | {reach} |")
    new_section = [REPORT_ANCHOR, ""] + body

    if not DISCOVERY_DOC.exists():
        DISCOVERY_DOC.write_text("\n".join(new_section) + "\n")
        return
    doc = DISCOVERY_DOC.read_text().splitlines()
    out: list[str] = []
    i, n, replaced = 0, len(doc), False
    while i < n:
        if doc[i].strip() == REPORT_ANCHOR:
            i += 1
            while i < n and not doc[i].startswith("## "):
                i += 1
            if not replaced:
                out.extend(new_section)
                out.append("")
                replaced = True
            continue
        out.append(doc[i])
        i += 1
    if not replaced:
        if out and out[-1].strip() != "":
            out.append("")
        out.extend(new_section)
    DISCOVERY_DOC.write_text("\n".join(out).rstrip() + "\n")


if __name__ == "__main__":
    raise SystemExit(main())
