# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Derive AND validate the Luck loot power budget.

Luck has three loot effects that must be tuned TOGETHER:
  1. gear drop chance      (RpgConfig accelerating curve)
  2. rarity promotion gate (Rarity.json Promotion)
  3. rarity promotion tier (Rarity.json Promotion TierWeights)

Guardrail: the loot-VALUE expected per kill at effective Luck 50 must stay within a target multiple
(``VALUE_RATIO_BUDGET``, 6x) of Luck 0. Gear value is grounded in gold via on-level buy prices
(``item_prices``) x rarity price multiplier x resale; the gold pool is Luck-INDEPENDENT, so it only
dilutes the ratio. The drop-chance curve alone multiplies gear *value* ~7-8x (0.10 -> 0.80), so the
budget is met only because the gold faucet pulls the total down — this script computes the ACTUAL
total EV(50)/EV(0) using each archetype template's gold and FAILS (non-zero exit) if any
archetype/level breaches the budget. Promotion is kept gentle so the rarity-mix uplift does not
inflate the ratio further.

This script OWNS the Rarity.json ``Promotion`` block (it runs after derive_rarity.py and rewrites
only that block, preserving ladders/attributes/prices/display). It then upserts an idempotent report
section into the discovery doc (re-running replaces the section, it does not duplicate it).

Run:  uv run derive_luck_budget.py   (exit code 0 = budget met, 1 = breached or write error)
"""

from __future__ import annotations

import json
import sqlite3
import statistics
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parent.parent
DB_PATH = SCRIPT_DIR / "scaling.db"
TEMPLATES_PATH = SCRIPT_DIR / "archetype_loot_templates.json"
RARITY_PATH = ROOT / "src/main/resources/Server/Configs/Scaling/Rarity.json"
RPGCONFIG_PATH = ROOT / "src/main/resources/Server/Configs/Rpg/RpgConfig.json"
DISCOVERY_DOC = ROOT / "docs/data-balancing/economy-discovery.md"
REPORT_ANCHOR = "## Luck power-budget derivation (derive_luck_budget.py)"

RARITIES = ["Common", "Uncommon", "Rare", "Epic", "Legendary", "Relic", "Abyssal"]
CEILING = len(RARITIES) - 1
RESALE = 0.50               # P8 resale ratio
VALUE_RATIO_BUDGET = 6.0    # guardrail: total loot-value EV(50)/EV(0) target
GATE_L0_MAX = 0.05          # promotion gate at Luck 0 must stay small
GATE_L50_MIN = 0.12         # promotion gate at Luck 50 must be meaningful
GATE_L50_MAX = 0.25         # ...but not excessive
REPORT_LEVELS = [10, 30, 50]            # NPC levels at which the value budget is checked
LUCK_REF_POINTS = [0, 10, 20, 30, 40, 50]  # Luck values reported for the drop/gate curves


def load_json(path: Path) -> dict:
    return json.loads(path.read_text())


# ── Drop-chance curve (mirrors RpgStatEffects.computeLuckDropChance) ─────────
def drop_chance(base: float, luck: int, curve: dict) -> float:
    ref = max(1, int(curve["LuckDropReference"]))
    norm = min(max(luck, 0), ref) / ref
    bonus = curve["LuckDropCoefficient"] * norm ** curve["LuckDropExponent"]
    return min(curve["LuckDropMaxChance"], base + bonus)


# ── Promotion (mirrors RarityRollService.promote + pickTiers) ────────────────
def gate_chance(luck: int, promo: dict) -> float:
    ref = max(1, int(promo["LuckRef"]))
    norm = min(max(luck, 0), ref) / ref
    return min(1.0, max(0.0, promo["BaseChance"] + promo["LuckCoeff"] * norm ** promo["LuckExp"]))


def tier_distribution(promo: dict) -> dict[int, float]:
    weights = {int(t): float(w) for t, w in promo["TierWeights"]}
    total = sum(weights.values()) or 1.0
    return {t: w / total for t, w in weights.items()}


def base_distribution(ladder: list[tuple[str, int]]) -> dict[str, float]:
    total = sum(w for _, w in ladder) or 1.0
    return {r: w / total for r, w in ladder}


def promoted_distribution(ladder: list[tuple[str, int]], luck: int, promo: dict) -> dict[str, float]:
    base = base_distribution(ladder)
    gate = gate_chance(luck, promo)
    tiers = tier_distribution(promo)
    out = {r: 0.0 for r in RARITIES}
    for rarity, p_base in base.items():
        idx = RARITIES.index(rarity)
        if idx >= CEILING:  # already at the Abyssal ceiling: cannot promote
            out[rarity] += p_base
            continue
        out[rarity] += p_base * (1.0 - gate)
        for jump, p_jump in tiers.items():
            dest = RARITIES[min(idx + jump, CEILING)]
            out[dest] += p_base * gate * p_jump
    return out


def expected_pricemult(ladder, luck, promo, price_mult) -> float:
    dist = promoted_distribution(ladder, luck, promo)
    return sum(dist[r] * price_mult[r] for r in RARITIES)


def gear_value(conn: sqlite3.Connection, level: int) -> float:
    """Representative on-level Common gear sell value = median buy price x resale."""
    vals = [row[0] for row in conn.execute(
        "select buy_stats from item_prices where category in ('weapon','armor') "
        "and level = ? and buy_stats is not null and buy_stats > 0", (level,))]
    return (statistics.median(vals) if vals else 0.0) * RESALE


def gold_per_kill(tmpl: dict, level: int) -> float:
    """Luck-independent gold value per kill: goldChance x avg(qty) x npcLevel (runtime scaleGold)."""
    avg_qty = (tmpl["goldQuantityMin"] + tmpl["goldQuantityMax"]) / 2.0
    return tmpl["goldChance"] * avg_qty * level


def tune_promotion(mob, price_mult) -> dict:
    """Grid-search the promotion gate: keep it gentle (small rarity uplift) yet meaningful."""
    candidates = []
    for base_c in (0.02, 0.03, 0.05):
        for coeff in (0.08, 0.10, 0.12, 0.15):
            for exp in (1.2, 1.3, 1.5):
                promo = {"BaseChance": base_c, "LuckCoeff": coeff, "LuckExp": exp, "LuckRef": 50,
                         "TierWeights": [(1, 85), (2, 12), (3, 3)], "TierLuckShift": 0.0}
                g0, g50 = gate_chance(0, promo), gate_chance(50, promo)
                if g0 > GATE_L0_MAX or not (GATE_L50_MIN <= g50 <= GATE_L50_MAX):
                    continue
                uplift = expected_pricemult(mob, 50, promo, price_mult) / \
                    expected_pricemult(mob, 0, promo, price_mult)
                candidates.append((uplift, abs(g50 - 0.175), promo))
    candidates.sort(key=lambda c: (c[0], c[1]))
    return candidates[0][2] if candidates else {
        "BaseChance": 0.05, "LuckCoeff": 0.10, "LuckExp": 1.3, "LuckRef": 50,
        "TierWeights": [(1, 85), (2, 12), (3, 3)], "TierLuckShift": 0.0}


def main() -> int:
    curve = load_json(RPGCONFIG_PATH)
    rarity = load_json(RARITY_PATH)
    templates = load_json(TEMPLATES_PATH)["templates"]

    ladders = {l["Source"]: [(w["Rarity"], w["W"]) for w in l["Weights"]] for l in rarity["Ladders"]}
    price_mult = {p["Rarity"]: p["Mult"] for p in rarity["PriceMultipliers"]}
    mob = ladders["MOB"]

    chosen = tune_promotion(mob, price_mult)
    m0, m50 = expected_pricemult(mob, 0, chosen, price_mult), expected_pricemult(mob, 50, chosen, price_mult)

    conn = sqlite3.connect(DB_PATH)
    gv = {L: gear_value(conn, L) for L in REPORT_LEVELS}
    conn.close()

    # ── Validate the total value budget per archetype (NORMAL / MOB source). ──
    # total EV(L) = gearDrop(luck) * E[priceMult|luck] * gearValue(level) + gold_per_kill (Luck-free)
    results = []  # (archetype, [(level, ratio), ...], worst_ratio, gold_floor@L30, passed)
    for name, tmpl in templates.items():
        if tmpl["gearDropChance"] <= 0.0:
            continue  # swarm: gold-only, no gear value ratio to bound
        per_level = []
        for L in REPORT_LEVELS:
            g = gv[L]
            ev0 = drop_chance(tmpl["gearDropChance"], 0, curve) * m0 * g
            ev50 = drop_chance(tmpl["gearDropChance"], 50, curve) * m50 * g
            gold = gold_per_kill(tmpl, L)
            per_level.append((L, (ev50 + gold) / (ev0 + gold) if (ev0 + gold) else 0.0))
        worst = max(r for _, r in per_level)
        # Gold floor (per kill @L30) the income pass would need if this archetype's gold were absent.
        g30 = gv[30]
        ev0_30 = drop_chance(tmpl["gearDropChance"], 0, curve) * m0 * g30
        ev50_30 = drop_chance(tmpl["gearDropChance"], 50, curve) * m50 * g30
        gold_floor = max(0.0, (ev50_30 - VALUE_RATIO_BUDGET * ev0_30) / (VALUE_RATIO_BUDGET - 1))
        results.append((name, per_level, worst, gold_floor, worst <= VALUE_RATIO_BUDGET))

    breached = [r[0] for r in results if not r[4]]

    # ── Write the Promotion block back into Rarity.json (preserve everything else). ──
    rarity["Promotion"] = {
        "BaseChance": chosen["BaseChance"], "LuckCoeff": chosen["LuckCoeff"],
        "LuckExp": chosen["LuckExp"], "LuckRef": chosen["LuckRef"],
        "TierWeights": [{"Tiers": t, "W": w} for t, w in chosen["TierWeights"]],
        "TierLuckShift": chosen["TierLuckShift"],
    }
    RARITY_PATH.write_text(json.dumps(rarity, indent=2) + "\n")

    # ── Console report ──
    print(f"Wrote Promotion block -> {RARITY_PATH.relative_to(ROOT)}")
    print(f"Promotion gate: L0 {gate_chance(0, chosen):.1%}, L50 {gate_chance(50, chosen):.1%} "
          f"(BaseChance {chosen['BaseChance']}, LuckCoeff {chosen['LuckCoeff']}, LuckExp {chosen['LuckExp']})")
    print(f"Rarity-value uplift E[priceMult] L50/L0 (MOB): {m50 / m0:.3f}x")
    print()
    print("Drop chance @0.10 base / promotion gate across Luck:")
    print("  Luck:  " + "  ".join(f"{lk:>4}" for lk in LUCK_REF_POINTS))
    print("  drop:  " + "  ".join(f"{drop_chance(0.10, lk, curve):>4.2f}" for lk in LUCK_REF_POINTS))
    print("  gate:  " + "  ".join(f"{gate_chance(lk, chosen):>4.2f}" for lk in LUCK_REF_POINTS))
    print()
    print(f"Total loot-value EV(50)/EV(0) per archetype (budget {VALUE_RATIO_BUDGET:g}x):")
    header = "  " + f"{'archetype':<10}" + "".join(f"L{L:>6}" for L in REPORT_LEVELS) + f"{'worst':>8}{'verdict':>9}"
    print(header)
    for name, per_level, worst, _floor, ok in results:
        cells = "".join(f"{r:>7.2f}" for _, r in per_level)
        print(f"  {name:<10}{cells}{worst:>8.2f}{'PASS' if ok else 'FAIL':>9}")
    print()
    print("gold floor /kill @L30 (info for the income pass, were the archetype's gold removed):")
    print("  " + "  ".join(f"{name}:{floor:.0f}" for name, _pl, _w, floor, _ok in results))

    upsert_report(chosen, m50 / m0, results, curve)

    if breached:
        print(f"\nBUDGET BREACHED for: {', '.join(breached)} (worst ratio > {VALUE_RATIO_BUDGET:g}x). "
              f"Raise those archetypes' gold floors in archetype_loot_templates.json.")
        return 1
    print(f"\nBudget OK: every archetype within {VALUE_RATIO_BUDGET:g}x across levels {REPORT_LEVELS}.")
    return 0


def upsert_report(chosen: dict, uplift: float, results: list, curve: dict) -> None:
    """Replace (or append) the report section in the discovery doc — idempotent across re-runs."""
    if not DISCOVERY_DOC.parent.exists():
        return
    body = [
        f"Promotion gate: L0 {gate_chance(0, chosen):.1%}, L50 {gate_chance(50, chosen):.1%} "
        f"(BaseChance {chosen['BaseChance']}, LuckCoeff {chosen['LuckCoeff']}, "
        f"LuckExp {chosen['LuckExp']}, tiers {chosen['TierWeights']}). Rarity-value uplift {uplift:.3f}x.",
        "",
        "Drop chance (@0.10 base) and promotion gate across Luck:",
        "",
        "| Luck | " + " | ".join(str(lk) for lk in LUCK_REF_POINTS) + " |",
        "|---|" + "---|" * len(LUCK_REF_POINTS),
        "| drop | " + " | ".join(f"{drop_chance(0.10, lk, curve):.2f}" for lk in LUCK_REF_POINTS) + " |",
        "| gate | " + " | ".join(f"{gate_chance(lk, chosen):.2f}" for lk in LUCK_REF_POINTS) + " |",
        "",
        f"Total loot-value EV(50)/EV(0) per archetype (budget {VALUE_RATIO_BUDGET:g}x, "
        f"gear value grounded in on-level buy price x {RESALE:g} resale + template gold):",
        "",
        "| archetype | " + " | ".join(f"L{L}" for L in REPORT_LEVELS) + " | worst | verdict |",
        "|---|" + "---|" * (len(REPORT_LEVELS) + 2),
    ]
    for name, per_level, worst, _floor, ok in results:
        cells = " | ".join(f"{r:.2f}" for _, r in per_level)
        body.append(f"| {name} | {cells} | {worst:.2f} | {'PASS' if ok else 'FAIL'} |")
    body += [
        "",
        "The drop-chance curve alone is ~8x at the 0.10 base, so the budget holds only because the "
        "Luck-independent gold faucet dilutes the total; promotion is kept gentle so the rarity-mix "
        "uplift adds little. Gold quantities here are starting floors — the income pass refines them.",
    ]
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
