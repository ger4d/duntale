# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Derive net income per floor I(F), the gold-faucet split, and the respawn cost curve.

I(F) is the single driver the rest of the reconciliation pass hangs off (gear prices, death costs,
custom-item prices). It is defined here as the value-neutral total a non-farming player nets per
floor at the corrected 0.50 resale:

    I(F) = direct_gold_per_floor(F) + sell_fodder_per_floor(F)

with both terms replicated from the live drop model (mirrors LootRollService / the discovery script):
``direct = est_kills * Σ weight_share·goldEV(role, F)`` and
``sell_fodder = est_kills * Σ weight_share·itemEV(role, F) * gearValue(F)``, where
``gearValue(F)`` is the median on-level merchant buy price x 0.50 resale.

The faucet target is a 50/50 split (direct gold / sell-fodder). We SOLVE one global scale ``s`` on
the per-archetype gold quantities so the aggregate direct-gold faucet lands on ``0.50·I(F)`` —
preserving each archetype's relative gold structure rather than hand-raising any single value. The
scale is clamped to ``>= 1.0``: lowering gold would shrink the Luck-independent faucet and push the
gear-value EV(50)/EV(0) ratio over the 6x budget that ``derive_luck_budget.py`` enforces, and that
budget must not give (the income/split gives instead). The solved ``s`` and the resulting per-theme
split are reported so the tension is visible.

Death economics: respawn ≈ 1.25·I_smooth(F), restart-lower ≈ 0.6·respawn — written as a per-floor-band
``RespawnSchedule`` (a compressed step table over the smooth budget) plus the 0.6 restart fraction in
the ``Pricing.json`` config asset consumed by ``DungeonRespawnService``.

This script OWNS the per-archetype ``goldQuantityMin/Max`` in ``archetype_loot_templates.json`` and
the ``RespawnCostPerFloor``/``RespawnRestartFraction`` keys in ``Pricing.json`` (read-merge-write so
``derive_prices.py``'s keys are preserved). It then upserts an idempotent discovery-doc section.

Run:  uv run derive_income.py
Then: uv run generate_loot_tables.py && uv run derive_luck_budget.py   (budget must stay PASS)
"""

from __future__ import annotations

import json
import math
import sqlite3
import statistics
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parent.parent
DB_PATH = SCRIPT_DIR / "scaling.db"
TEMPLATES_PATH = SCRIPT_DIR / "archetype_loot_templates.json"
ARCHETYPES_PATH = ROOT / "src/main/resources/Server/Configs/Scaling/NpcArchetypes.json"
PRICING_PATH = ROOT / "src/main/resources/Server/Configs/Scaling/Pricing.json"
FLOOR_DIR = ROOT / "src/main/resources/Server/Configs/FloorConfig"
THEMES_DIR = ROOT.parent / "dungeon-gen/src/main/resources/Server/Configs/DungeonGen/Themes"
DISCOVERY_DOC = ROOT / "docs/data-balancing/economy-discovery.md"
REPORT_ANCHOR = "## Income & gold-split derivation (derive_income.py)"

RESALE = 0.50               # P8 resale ratio (matches discover_economy / derive_luck_budget)
GOLD_FAUCET_SHARE = 0.50    # target direct-gold share of I(F); the rest is sell-fodder
RESPAWN_INCOME_MULT = 1.25  # respawn cost as a multiple of I(F) (midpoint of the 1.0-1.5x band)
RESTART_FRACTION = 0.6      # restart-lower cost as a fraction of the current-floor respawn cost
GOLD_SCALE_MIN = 1.0        # never lower gold below current (protects the <=6x Luck budget)
GOLD_SCALE_MAX = 1.5        # guardrail against runaway gold inflation from the solve
CALIBRATION_MAX_FLOOR = 30  # floors 1-30 are the well-tuned baseline


# ── Level curve (mirrors com.duntale.progression.CombatScaling) ──────────────
MIN_LEVEL, MAX_LEVEL = 1, 100
_MIDPOINT = MAX_LEVEL / 2.0
_STEEPNESS = 7.2 / MAX_LEVEL


def _raw_sigmoid(level: float) -> float:
    return 1.0 / (1.0 + math.exp(-_STEEPNESS * (level - _MIDPOINT)))


_SIG_MIN = _raw_sigmoid(MIN_LEVEL)
_SIG_MAX = _raw_sigmoid(MAX_LEVEL)


def clamp_level(level: int) -> int:
    return max(MIN_LEVEL, min(level, MAX_LEVEL))


def load_json(path: Path) -> dict:
    return json.loads(path.read_text())


# ── Drop EV per kill (from the BASELINE templates, so I(F) is pipeline-independent) ──
# Income is grounded in the W5 baseline gold/drop authored per archetype rather than the regenerated
# loot tables — otherwise the gold the solve writes would feed back into the next I(F), making the
# derivation non-idempotent across the full pipeline. goldEV mirrors LootRollService.scaleGold
# (goldChance x per-level base x npcLevel); itemEV is the gear-pool drop chance (~1 gear roll/kill).
def role_ev(role: str, level: int, templates: dict, baseline: dict, role_to_arch: dict) -> tuple[float, float]:
    """Return (gold EV, item-count EV) per kill for a role at an NPC level, from baseline authoring."""
    arch = role_to_arch.get(role)
    if arch is None or arch not in templates:
        return 0.0, 0.0
    tmpl = templates[arch]
    base_min, base_max = baseline.get(arch, [tmpl["goldQuantityMin"], tmpl["goldQuantityMax"]])
    avg_qty = (base_min + base_max) / 2.0
    gold = tmpl["goldChance"] * avg_qty * (level if level > 1 else 1)
    return gold, tmpl["gearDropChance"]


def load_baseline(tmpl_doc: dict) -> dict:
    """The stable W5 gold floors used as the income basis (captured on first derive_income run)."""
    return tmpl_doc.get("_goldBaseline") or {
        name: [t["goldQuantityMin"], t["goldQuantityMax"]] for name, t in tmpl_doc["templates"].items()}


def role_archetype_map() -> dict[str, str]:
    return {r["Role"]: r["Archetype"] for r in load_json(ARCHETYPES_PATH)["Roles"]}


def gear_value(conn: sqlite3.Connection, floor: int) -> float:
    """Median on-level merchant gear value = median buy price (near floor) x resale."""
    audit_level = conn.execute(
        "SELECT MIN(level) FROM item_prices WHERE level >= ?", (clamp_level(floor),)).fetchone()[0]
    if audit_level is None:
        audit_level = conn.execute("SELECT MAX(level) FROM item_prices").fetchone()[0]
    buys = [row[0] for row in conn.execute(
        "SELECT buy_stats FROM item_prices WHERE level = ? AND category IN ('weapon', 'armor') "
        "AND buy_stats IS NOT NULL AND buy_stats > 0 AND item_level BETWEEN ? AND ?",
        (audit_level, max(0, floor - 15), floor + 15))]
    return (statistics.median(buys) if buys else 0.0) * RESALE


# ── Smooth income budget (the §1.5.2 fitted spine) ───────────────────────────
# The raw per-floor I(F) inherits every spawn-count wiggle (the F5-15 density spike, etc.). Per the
# design decision we fit a SMOOTH monotonic budget on floors 1-30 and extend it through F70, then
# drive respawn (and, in derive_prices.py, gear price + custom-item) off the smooth curve so floor
# *texture* can stay lumpy while the progression spine is well-behaved. We fit a power law
# I_smooth(F) = c · F^p via log-log least squares on the well-tuned 1-30 band: it starts low at F1
# (income grows mainly with kill pacing, not gear value), is monotonic, and extends naturally to F70.
def fit_income_curve(per_floor: list[tuple], max_floor: int = CALIBRATION_MAX_FLOOR) -> tuple[float, float]:
    pts = [(math.log(f), math.log(inc)) for f, _k, _d, _s, inc, _g, _t in per_floor
           if f <= max_floor and f > 0 and inc > 0]
    if len(pts) < 2:
        return (per_floor[0][4] if per_floor else 1.0), 1.0
    n = len(pts)
    mx = sum(x for x, _y in pts) / n
    my = sum(y for _x, y in pts) / n
    var = sum((x - mx) ** 2 for x, _y in pts)
    cov = sum((x - mx) * (y - my) for x, y in pts)
    p = (cov / var) if var > 0 else 1.0
    c = math.exp(my - p * mx)
    return c, p


def income_smooth(floor: int, curve: tuple[float, float]) -> float:
    """Smooth I(F) at any integer floor from the fitted power law I_smooth(F) = c · F^p."""
    c, p = curve
    return c * (max(1, floor) ** p)


# ── Floor / roster model ─────────────────────────────────────────────────────
def floor_rows() -> list[dict]:
    """Per-floor density/room/theme parameters from the FloorConfig assets."""
    rows = []
    for path in sorted(FLOOR_DIR.glob("*.json")):
        overrides = load_json(path).get("Overrides", {})
        rows.append({
            "floor": int(path.stem),
            "density": overrides.get("layout.enemyDensity", 0.4),
            "max_rooms": overrides.get("layout.maxRooms", 20),
            "max_per_room": overrides.get("layout.maxEnemiesPerRoom", 5),
            "variants": [v.lower() for v in overrides.get("theme.variants", [])],
        })
    return rows


def theme_stem_map() -> dict[str, str]:
    return {p.stem.lower(): p.stem for p in THEMES_DIR.glob("*.json")} if THEMES_DIR.exists() else {}


def est_kills(row: dict) -> float:
    return row["max_rooms"] * 0.6 * max(1.0, row["density"] * row["max_per_room"])


def compute_per_floor(conn: sqlite3.Connection) -> list[tuple]:
    """Per-floor income tuples (floor, est_kills, direct, sell_fodder, I, gearValue, per_theme_direct).

    Pure (no writes) so derive_prices.py can reuse the same I(F) driver.
    """
    roster = conn.execute(
        "SELECT role, theme, min_floor, max_floor, weight FROM spawn_roster").fetchall()
    tmpl_doc = load_json(TEMPLATES_PATH)
    templates = tmpl_doc["templates"]
    baseline = load_baseline(tmpl_doc)
    role_to_arch = role_archetype_map()
    stems = theme_stem_map()
    floors = floor_rows()
    # Active themes are exactly those a FloorConfig references; the Mine theme is shelved (in the
    # roster but in no FloorConfig), so its roles never enter the income solve.
    active_stems = {stems.get(v) for row in floors for v in row["variants"]} - {None}

    def ev(role: str, level: int) -> tuple[float, float]:
        return role_ev(role, level, templates, baseline, role_to_arch)

    def mapped(role: str) -> bool:
        return role_to_arch.get(role) in templates

    per_floor = []  # (floor, est_kills, direct_cur, sellfodder_cur, I, gearValue, per_theme_direct)
    for row in floors:
        floor = row["floor"]
        kills = est_kills(row)
        gv = gear_value(conn, floor)
        floor_variants = {stems.get(v) for v in row["variants"]} - {None}
        # Pool every active-theme role spawning at this floor that maps to an archetype template.
        pool = [(role, weight) for role, theme, mnf, mxf, weight in roster
                if theme in active_stems and theme in floor_variants
                and mnf <= floor <= mxf and mapped(role)]
        total_w = sum(w for _r, w in pool)
        gold_pk = 0.0
        item_pk = 0.0
        per_theme_direct: dict[str, float] = {}
        if total_w > 0:
            for role, weight in pool:
                g, it = ev(role, floor)
                gold_pk += (weight / total_w) * g
                item_pk += (weight / total_w) * it
            # Per-theme direct gold (for the +/-20% split check).
            for theme in floor_variants:
                t_pool = [(r, w) for r, th, mnf, mxf, w in roster
                          if th == theme and mnf <= floor <= mxf and mapped(r)]
                t_w = sum(w for _r, w in t_pool)
                if t_w > 0:
                    t_gold = sum((w / t_w) * ev(r, floor)[0] for r, w in t_pool)
                    per_theme_direct[theme] = kills * t_gold
        direct = kills * gold_pk
        sellfodder = kills * item_pk * gv
        income = direct + sellfodder
        per_floor.append((floor, kills, direct, sellfodder, income, gv, per_theme_direct))
    return per_floor


def main() -> int:
    conn = sqlite3.connect(DB_PATH)
    per_floor = compute_per_floor(conn)

    # ── Smooth income budget I_smooth(F) = c · F^p, fit on floors 1-30 ──
    curve = fit_income_curve(per_floor)
    ism = {f: income_smooth(f, curve) for f, _k, _d, _s, _i, _g, _t in per_floor}

    # ── Solve the global gold scale toward the 50/50 faucet target (vs the smooth budget) ──
    num = sum(direct * GOLD_FAUCET_SHARE * ism[f] for f, _k, direct, _s, _i, _g, _t in per_floor)
    den = sum(direct * direct for _f, _k, direct, _s, _i, _g, _t in per_floor)
    solved_scale = (num / den) if den > 0 else 1.0
    gold_scale = min(GOLD_SCALE_MAX, max(GOLD_SCALE_MIN, solved_scale))

    # ── Respawn schedule: cost(F) = round(1.25 · I_smooth(F)) as a compressed per-floor-band table ──
    # I_smooth is smooth/monotonic, so the death cost tracks income across the WHOLE range (not just an
    # anchor floor). Run-length compression keeps only the bands where the cost actually changes.
    respawn_schedule = []
    prev_cost = None
    for floor in sorted(ism):
        cost = max(1, round(RESPAWN_INCOME_MULT * ism[floor]))
        if cost != prev_cost:
            respawn_schedule.append({"MinFloor": floor, "Cost": cost})
            prev_cost = cost

    # ── Write the solved gold quantities back into the templates ──
    # Scale from a captured baseline (the W5 floors), not the current values, so re-running is
    # idempotent instead of compounding the scale each pass.
    tmpl_doc = load_json(TEMPLATES_PATH)
    baseline = tmpl_doc.get("_goldBaseline")
    if baseline is None:
        baseline = {name: [tmpl["goldQuantityMin"], tmpl["goldQuantityMax"]]
                    for name, tmpl in tmpl_doc["templates"].items()}
        tmpl_doc["_goldBaseline"] = baseline
    for name, tmpl in tmpl_doc["templates"].items():
        base_min, base_max = baseline.get(name, [tmpl["goldQuantityMin"], tmpl["goldQuantityMax"]])
        tmpl["goldQuantityMin"] = max(1, round(base_min * gold_scale))
        tmpl["goldQuantityMax"] = max(tmpl["goldQuantityMin"], round(base_max * gold_scale))
    tmpl_doc["_comment"] = (
        "Per-archetype loot authoring template for generate_loot_tables.py (offline only; never "
        "loaded at runtime). gearDropChance is the GEAR pool chance (Luck-boosted in game); goldChance "
        "is the independent gold pool chance; goldQuantity* is a small PER-LEVEL base that the runtime "
        "LootRollService.scaleGold multiplies by npcLevel. Swarm is gold-only. goldQuantity* are SOLVED "
        "by derive_income.py from the per-floor income split (a single scale on the W5 floors so the "
        f"direct-gold faucet tracks {GOLD_FAUCET_SHARE:.0%} of I(F)); they are not hand-raised.")
    TEMPLATES_PATH.write_text(json.dumps(tmpl_doc, indent=2) + "\n")

    # ── Merge the respawn schedule into Pricing.json (preserve derive_prices.py keys) ──
    pricing = load_json(PRICING_PATH) if PRICING_PATH.exists() else {}
    pricing.pop("RespawnCostPerFloor", None)  # superseded by the per-floor-band schedule
    pricing["RespawnRestartFraction"] = RESTART_FRACTION
    pricing["RespawnSchedule"] = respawn_schedule
    PRICING_PATH.write_text(json.dumps(pricing, indent=2) + "\n")

    conn.close()

    # ── Console report ──
    print(f"Wrote solved gold quantities (scale {gold_scale:.3f}) -> {TEMPLATES_PATH.relative_to(ROOT)}")
    print(f"Wrote respawn schedule ({len(respawn_schedule)} bands, restart {RESTART_FRACTION:g}) "
          f"-> {PRICING_PATH.relative_to(ROOT)}")
    print(f"Smooth-budget fit c={curve[0]:.1f}, p={curve[1]:.3f} (I_smooth(F) = c · F^p); "
          f"faucet scale {solved_scale:.3f} -> applied {gold_scale:.3f} "
          f"(clamped [{GOLD_SCALE_MIN:g}, {GOLD_SCALE_MAX:g}] so the Luck budget holds).")
    print()
    print("Per-floor: raw I(F), smooth I(F), shipped respawn (= round(1.25 · I_smooth)):")
    print(f"  {'floor':>5}{'kills':>7}{'I_raw':>9}{'I_smooth':>10}{'respawn':>9}")
    for floor, kills, direct, sellf, inc, _gv, _t in per_floor:
        print(f"  {floor:>5}{kills:>7.0f}{inc:>9.0f}{ism[floor]:>10.0f}"
              f"{RESPAWN_INCOME_MULT * ism[floor]:>9.0f}")

    upsert_report(per_floor, ism, curve, solved_scale, gold_scale, respawn_schedule)
    print(f"\nUpserted report -> {DISCOVERY_DOC.relative_to(ROOT)}")
    return 0


def upsert_report(per_floor: list, ism: dict, curve: tuple, solved_scale: float,
                  gold_scale: float, respawn_schedule: list) -> None:
    """Replace (or append) the report section in the discovery doc — idempotent across re-runs."""
    if not DISCOVERY_DOC.parent.exists():
        return
    calib = [p for p in per_floor if p[0] <= CALIBRATION_MAX_FLOOR]
    deep = [p for p in per_floor if p[0] > CALIBRATION_MAX_FLOOR]

    def band_residual(rows):
        errs = [abs(ism[f] - inc) / inc for f, _k, _d, _s, inc, _g, _t in rows if inc > 0]
        return (sum(errs) / len(errs)) if errs else 0.0

    def split_pct(rows):
        tgt = sum(GOLD_FAUCET_SHARE * ism[f] for f, _k, _d, _s, _i, _g, _t in rows)
        got = sum(gold_scale * direct for _f, _k, direct, _s, _i, _g, _t in rows)
        return (got / tgt) if tgt else 0.0
    body = [
        f"I(F) is the value-neutral net income per floor (direct gold + full sell-fodder at {RESALE:g} "
        f"resale). Per the smooth-budget decision a monotonic spine I_smooth(F) = {curve[0]:.1f}·F^{curve[1]:.3f} "
        "is fit on floors 1-30 and extended through F70, so the lumpy floor texture (the F5-15 density "
        "spike, etc.) does not distort the progression curve; respawn, gear price, and custom-item prices "
        "all drive off I_smooth, not raw kills.",
        "",
        f"Smooth-fit mean relative error: floors 1-{CALIBRATION_MAX_FLOOR} {band_residual(calib):.1%} "
        f"(small — tracks the well-tuned band), floors {CALIBRATION_MAX_FLOOR + 1}-{MAX_LEVEL} "
        f"{band_residual(deep):.1%} (large by design — those floors sagged; extending the curve is the fix).",
        "",
        f"Gold faucet: solve scaled the W5 floors by {solved_scale:.3f} (applied {gold_scale:.3f}, clamped "
        f">= {GOLD_SCALE_MIN:g} so lowering gold never breaches the Luck budget). Direct-gold vs the "
        f"{GOLD_FAUCET_SHARE:.0%} target post-scale: floors 1-{CALIBRATION_MAX_FLOOR} {split_pct(calib):.0%}, "
        f"floors {CALIBRATION_MAX_FLOOR + 1}-{MAX_LEVEL} {split_pct(deep):.0%}. Respawn = "
        f"{RESPAWN_INCOME_MULT:g}·I_smooth as a {len(respawn_schedule)}-band schedule, restart "
        f"{RESTART_FRACTION:g}x.",
        "",
        "| Floor | est kills | raw I(F) | smooth I(F) | respawn (shipped) |",
        "|---|---|---|---|---|",
    ]
    for floor, kills, _d, _s, inc, _g, _t in per_floor:
        body.append(f"| {floor} | {kills:.0f} | {inc:.0f} | {ism[floor]:.0f} | "
                    f"{RESPAWN_INCOME_MULT * ism[floor]:.0f} |")
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
