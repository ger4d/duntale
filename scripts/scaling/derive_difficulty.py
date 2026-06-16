# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Derive per-floor encounter difficulty: the Challenge(F) budget, the per-floor Elite-rate and
flat difficulty multiplier, and the Elite/Boss variant multiplier tables.

Companion to ``derive_income.py``. That script fits a smooth income spine ``I_smooth(F)``; this one
fits the matching CHALLENGE spine and solves the per-floor knobs that make a hand-authored floor's
texture land on it. The two budgets share the same shape so reward keeps tracking threat: a sparse
floor is fewer, nastier fights (more Elites and/or a flat HP/damage bump) rather than a coast, and a
dense wave floor needs no compensation (the wave is the challenge).

What it does:
  1. Fits ``Challenge(F) = c * F^p`` via log-log least squares on floors 1-30 (the well-tuned band,
     same fitting approach as ``derive_income.fit_income_curve``) and extends it through F70. The raw
     per-floor sample is ``est_kills(F) * base_per_enemy_threat(F)`` where the base threat is the
     floor roster's weight-averaged on-level archetype HP x damage (NORMAL variant).
  2. Re-derives the Elite/Boss variant multiplier tables to deliberate targets (the difficulty lever
     now the HP cap is removed): an Elite is ~2x a same-archetype NORMAL kill (HP-driven TTK), a Boss
     ramps to a designed top-floor HP (now uncapped). Written into ``Pricing.json``.
  3. Solves per floor: raise ``eliteRate`` first up to a cap, then a flat ``difficultyMult`` for the
     remainder, so ``est_kills(F) * effective_per_enemy_threat = Challenge_smooth(F)``. Dense spike
     floors solve to ~0 compensation; sparse floors to higher values. Both knobs are clamped. Written
     into the FloorConfig assets' ``Overrides``.
  4. Upserts an idempotent report into the discovery doc.

Idempotency: the threat baseline, the Challenge fit, and the variant targets are all derived from the
fixed archetype anchors + level curve + FloorConfig texture (enemy density / rooms), never from this
script's own previously-written ``combat.*`` outputs, so re-running produces identical files.

Run order:  ... derive_income.py -> derive_prices.py -> derive_difficulty.py -> generate_loot_tables.py
            -> derive_luck_budget.py   (the Luck budget must stay PASS)
"""

from __future__ import annotations

import json
import math
import sqlite3
from pathlib import Path

import derive_income  # sibling script: reuse floor/roster helpers + the level curve

SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parent.parent
DB_PATH = SCRIPT_DIR / "scaling.db"
ARCHETYPES_PATH = ROOT / "src/main/resources/Server/Configs/Scaling/NpcArchetypes.json"
PRICING_PATH = ROOT / "src/main/resources/Server/Configs/Scaling/Pricing.json"
FLOOR_DIR = ROOT / "src/main/resources/Server/Configs/FloorConfig"
DISCOVERY_DOC = ROOT / "docs/data-balancing/economy-discovery.md"
REPORT_ANCHOR = "## Encounter pacing & difficulty derivation (derive_difficulty.py)"

CALIBRATION_MAX_FLOOR = 30  # floors 1-30 are the well-tuned baseline (matches derive_income)

# ── Per-floor solve policy (resolved design decisions) ──────────────────────
# Realize difficulty as visible VARIETY first: raise the Elite rate up to a cap, then fall back to a
# flat HP/damage multiplier for any remaining gap. Both are clamped so HP/damage stay sane now that
# the upper HP clamp is gone (these caps are the guardrail).
ELITE_RATE_MAX = 0.35
DIFFICULTY_MULT_MIN, DIFFICULTY_MULT_MAX = 1.0, 3.0

# ── Variant re-derivation targets ───────────────────────────────────────────
# Elite: an Elite should take ~2x as long to kill as the same-archetype NORMAL at the same floor.
# Because the Elite HP multiplier scales the SAME role's on-level HP, a ~flat multiplier IS the TTK
# ratio, so the band table is gently rising around the target rather than steep. Damage is kept
# threatening but well short of a one-shot at on-level armor.
ELITE_BANDS = [  # (minLevelRatio, hpMult, damageMult) -- highest-ratio-first
    (0.75, 2.5, 2.0),
    (0.50, 2.3, 1.8),
    (0.3333, 2.1, 1.6),
    (0.1667, 2.0, 1.4),
    (0.0, 1.8, 1.25),
]
# Boss: ramp the (now uncapped) top-floor boss HP to a deliberate ceiling. The bands are solved from
# explicit boss-HP targets at each band's start level against the Boss anchor + level curve, so the
# numbers are a consequence of the design target, not magic. The active dungeon tops out near F60, so
# the band covering F50-74 is tuned to land the top-floor boss in the ~40-50k HP band.
BOSS_HP_TARGETS = {  # band start level -> intended boss HP at that level (uncapped)
    1: 300,
    17: 2_500,
    33: 9_000,
    50: 38_000,
    75: 60_000,
}
BOSS_DAMAGE_BANDS = {  # band start level -> boss damage multiplier (a threatening but survivable ramp)
    1: 2.0,
    17: 2.5,
    33: 3.2,
    50: 4.5,
    75: 5.2,
}

# ── Level curve (reuse derive_income's mirror of CombatScaling) ──────────────
MIN_LEVEL, MAX_LEVEL = derive_income.MIN_LEVEL, derive_income.MAX_LEVEL
sigmoid = derive_income._raw_sigmoid  # raw; normalized below to match CombatScaling.sigmoid
_SIG_MIN = derive_income._SIG_MIN
_SIG_MAX = derive_income._SIG_MAX


def level_sigmoid(level: int) -> float:
    """Normalized 0..1 level curve, identical to CombatScaling.sigmoid."""
    level = max(MIN_LEVEL, min(MAX_LEVEL, level))
    raw = derive_income._raw_sigmoid(level)
    denom = _SIG_MAX - _SIG_MIN
    return max(0.0, min((raw - _SIG_MIN) / denom, 1.0)) if denom > 0 else 0.0


_NPC_HP_K = 8.0   # mirrors CombatScaling.NPC_HP_K
_NPC_DMG_K = 5.0  # mirrors CombatScaling.NPC_DMG_K


def npc_scaled_hp(base_hp: float, level: int) -> float:
    """NORMAL-variant scaled HP, mirroring CombatScaling.npcScaledHp(baseHp, level, NORMAL)."""
    return base_hp + _NPC_HP_K * base_hp * level_sigmoid(level)


def npc_damage_mult(level: int) -> float:
    """NORMAL-variant damage multiplier, mirroring CombatScaling.npcDamageMult(level, NORMAL)."""
    return 1.0 + _NPC_DMG_K * level_sigmoid(level)


def threshold(ratio: float) -> int:
    """Band start level for a minLevelRatio, mirroring CombatScaling.threshold."""
    return max(MIN_LEVEL, round(MAX_LEVEL * ratio))


# ── Archetype anchors (the threat proxy basis) ───────────────────────────────
def load_json(path: Path) -> dict:
    return json.loads(path.read_text())


def archetype_anchors() -> dict[str, tuple[float, float]]:
    """Archetype name -> (baseHp, baseDamage) from the normalized anchors."""
    doc = load_json(ARCHETYPES_PATH)
    return {a["Name"]: (a["BaseHp"], a["BaseDamage"]) for a in doc["Archetypes"]}


# ── Per-floor base threat (weight-averaged on-level archetype HP x damage) ────
def per_floor_threat(conn: sqlite3.Connection) -> list[dict]:
    """Per-floor (floor, est_kills, base_threat, raw_challenge) from the roster + anchors.

    base_threat = weight-averaged (anchor HP at floor) x (anchor damage at floor) over the floor's
    active-theme roster, NORMAL variant. Pools roles exactly like derive_income.compute_per_floor so
    the Challenge spine and the Income spine see the same roster.
    """
    roster = conn.execute(
        "SELECT role, theme, min_floor, max_floor, weight FROM spawn_roster").fetchall()
    role_to_arch = derive_income.role_archetype_map()
    anchors = archetype_anchors()
    stems = derive_income.theme_stem_map()
    floors = derive_income.floor_rows()
    active_stems = {stems.get(v) for row in floors for v in row["variants"]} - {None}

    rows = []
    for row in floors:
        floor = row["floor"]
        kills = derive_income.est_kills(row)
        floor_variants = {stems.get(v) for v in row["variants"]} - {None}
        pool = [(role, weight) for role, theme, mnf, mxf, weight in roster
                if theme in active_stems and theme in floor_variants
                and mnf <= floor <= mxf and role_to_arch.get(role) in anchors]
        total_w = sum(w for _r, w in pool)
        base_threat = 0.0
        if total_w > 0:
            dmg_mult = npc_damage_mult(floor)
            for role, weight in pool:
                base_hp, base_dmg = anchors[role_to_arch[role]]
                hp = npc_scaled_hp(base_hp, floor)
                dmg = base_dmg * dmg_mult
                base_threat += (weight / total_w) * (hp * dmg)
        rows.append({
            "floor": floor,
            "kills": kills,
            "base_threat": base_threat,
            "raw_challenge": kills * base_threat,
        })
    return rows


# ── Challenge spine: Challenge(F) = c * F^p fit on floors 1-30 ────────────────
# Same log-log least-squares fit as derive_income.fit_income_curve, but on this script's own
# (floor, raw_challenge) samples so it does not depend on the income tuple shape.
def fit_challenge_curve(rows: list[dict]) -> tuple[float, float]:
    pts = [(math.log(r["floor"]), math.log(r["raw_challenge"]))
           for r in rows if r["floor"] <= CALIBRATION_MAX_FLOOR
           and r["floor"] > 0 and r["raw_challenge"] > 0]
    if len(pts) < 2:
        first = next((r["raw_challenge"] for r in rows if r["raw_challenge"] > 0), 1.0)
        return first, 1.0
    n = len(pts)
    mx = sum(x for x, _y in pts) / n
    my = sum(y for _x, y in pts) / n
    var = sum((x - mx) ** 2 for x, _y in pts)
    cov = sum((x - mx) * (y - my) for x, y in pts)
    p = (cov / var) if var > 0 else 1.0
    c = math.exp(my - p * mx)
    return c, p


def challenge_smooth(floor: int, curve: tuple[float, float]) -> float:
    c, p = curve
    return c * (max(1, floor) ** p)


# ── Variant table re-derivation ──────────────────────────────────────────────
def derive_boss_bands() -> list[tuple[float, float, float]]:
    """Boss (minLevelRatio, hpMult, damageMult) solved from the explicit HP targets + anchor curve."""
    boss_anchor_hp = archetype_anchors()["Boss"][0]
    bands = []
    for ratio in sorted({r for r in (0.75, 0.5, 0.3333, 0.1667, 0.0)}, reverse=True):
        start_level = threshold(ratio)
        target_hp = BOSS_HP_TARGETS[start_level]
        base_hp = npc_scaled_hp(boss_anchor_hp, start_level)
        hp_mult = round(target_hp / base_hp, 2) if base_hp > 0 else 1.0
        dmg_mult = BOSS_DAMAGE_BANDS[start_level]
        bands.append((ratio, hp_mult, dmg_mult))
    # Enforce monotonic non-decreasing HpMult as the band start level rises (higher bands >= lower).
    # The sigmoid flattens near the top, so a higher band's solved mult can dip below the band beneath
    # it, which would invert on-level boss HP at the band boundary. bands is highest-ratio-first; walk
    # from the lowest ratio upward, raising each higher band to at least the band below it.
    running = 0.0
    for i in range(len(bands) - 1, -1, -1):
        ratio, hp_mult, dmg_mult = bands[i]
        hp_mult = max(hp_mult, running)
        running = hp_mult
        bands[i] = (ratio, hp_mult, dmg_mult)
    return bands


def elite_threat_mult() -> float:
    """Representative Elite/NORMAL threat ratio (HP x damage uplift) used in the per-floor solve.

    Averaged over the Elite bands so the per-floor solve uses a single stable scalar; an Elite costs
    its HP-mult longer to kill AND hits its damage-mult harder, so its threat contribution is the
    product relative to a NORMAL of the same role.
    """
    ratios = [hp * dmg for _r, hp, dmg in ELITE_BANDS]
    return sum(ratios) / len(ratios)


# ── Per-floor knob solve (elites first, then a flat multiplier) ──────────────
def solve_floor_knobs(rows: list[dict], curve: tuple[float, float],
                      elite_mult: float) -> dict[int, tuple[float, float]]:
    """floor -> (eliteRate, difficultyMult) so est_kills * effective = Challenge_smooth(F)."""
    out = {}
    for r in rows:
        floor = r["floor"]
        kills = r["kills"]
        base_threat = r["base_threat"]
        target = challenge_smooth(floor, curve)
        raw = kills * base_threat
        if raw <= 0 or target <= 0:
            out[floor] = (0.0, 1.0)
            continue
        # Required total per-enemy threat uplift over NORMAL to hit the budget.
        need = target / raw
        if need <= 1.0:
            # The hand-authored texture already meets or exceeds the budget (e.g. a dense wave floor):
            # no compensation.
            out[floor] = (0.0, 1.0)
            continue
        # Stage 1: spend the gap on the Elite rate first (visible variety), up to the cap.
        # A floor with elite rate e raises per-enemy threat by (1 + e*(elite_mult - 1)).
        elite_rate_for_need = (need - 1.0) / (elite_mult - 1.0) if elite_mult > 1.0 else ELITE_RATE_MAX
        elite_rate = min(ELITE_RATE_MAX, max(0.0, elite_rate_for_need))
        achieved = 1.0 + elite_rate * (elite_mult - 1.0)
        # Stage 2: cover the remainder with a flat difficulty multiplier, clamped.
        remainder = need / achieved if achieved > 0 else need
        difficulty_mult = min(DIFFICULTY_MULT_MAX, max(DIFFICULTY_MULT_MIN, remainder))
        out[floor] = (round(elite_rate, 4), round(difficulty_mult, 4))
    return out


# ── Asset writers (read-merge-write; idempotent) ─────────────────────────────
def _fmt_num(value: float) -> str:
    """Render a knob as a compact JSON number (drop the trailing .0 on whole numbers)."""
    if value == int(value):
        return str(int(value))
    # Trim to 4 decimals without float-repr noise (e.g. 1.4374, not 1.4373999999999999).
    return f"{value:.4f}".rstrip("0").rstrip(".")


def write_floor_overrides(knobs: dict[int, tuple[float, float]]) -> int:
    """Insert combat.eliteRate / combat.difficultyMult into each FloorConfig asset's Overrides.

    Format-preserving: the FloorConfig assets are authored with mixed indentation, inline arrays, and
    no trailing newline (some are 2-space, some 4-space, some full-precision floats). Re-serializing
    the whole document would churn every line, so this does a surgical text edit instead — it strips
    any prior combat.* lines (idempotency) and inserts the two keys just before the Overrides object's
    closing brace, matching the file's own indent. The other keys are left byte-for-byte unchanged.
    """
    written = 0
    for path in sorted(FLOOR_DIR.glob("*.json")):
        floor = int(path.stem)
        if floor not in knobs:
            continue
        elite_rate, difficulty_mult = knobs[floor]
        text = path.read_text()
        lines = text.split("\n")

        # Drop any previously written combat.* lines so re-runs replace rather than duplicate.
        lines = [ln for ln in lines if '"combat.eliteRate"' not in ln
                 and '"combat.difficultyMult"' not in ln]

        # Find the Overrides closing brace: the line that closes the object opened by "Overrides": {.
        open_idx = next(i for i, ln in enumerate(lines) if '"Overrides"' in ln)
        depth = 0
        close_idx = None
        for i in range(open_idx, len(lines)):
            depth += lines[i].count("{") - lines[i].count("}")
            if depth == 0:
                close_idx = i
                break
        if close_idx is None:
            raise ValueError(f"could not locate Overrides closing brace in {path.name}")

        # The last value line before the close; use its indent and ensure it ends with a comma so the
        # appended keys are valid JSON. (This line closes the previous property's value, so adding a
        # trailing comma is always correct here.)
        last_val_idx = close_idx - 1
        indent = lines[last_val_idx][:len(lines[last_val_idx]) - len(lines[last_val_idx].lstrip())]
        if not lines[last_val_idx].rstrip().endswith(","):
            lines[last_val_idx] = lines[last_val_idx].rstrip() + ","

        new_lines = [
            f'{indent}"combat.eliteRate": {_fmt_num(elite_rate)},',
            f'{indent}"combat.difficultyMult": {_fmt_num(difficulty_mult)}',
        ]
        lines[close_idx:close_idx] = new_lines
        path.write_text("\n".join(lines))
        written += 1
    return written


def write_variant_tables(elite_bands: list, boss_bands: list) -> None:
    """Re-emit EliteVariantSteps / BossVariantSteps into Pricing.json (preserve the other keys)."""
    pricing = load_json(PRICING_PATH) if PRICING_PATH.exists() else {}
    pricing["EliteVariantSteps"] = [
        {"MinLevelRatio": r, "HpMult": hp, "DamageMult": dmg} for r, hp, dmg in elite_bands]
    pricing["BossVariantSteps"] = [
        {"MinLevelRatio": r, "HpMult": hp, "DamageMult": dmg} for r, hp, dmg in boss_bands]
    PRICING_PATH.write_text(json.dumps(pricing, indent=2) + "\n")


def main() -> int:
    conn = sqlite3.connect(DB_PATH)
    rows = per_floor_threat(conn)
    conn.close()

    curve = fit_challenge_curve(rows)
    elite_bands = ELITE_BANDS
    boss_bands = derive_boss_bands()
    elite_mult = elite_threat_mult()
    knobs = solve_floor_knobs(rows, curve, elite_mult)

    written = write_floor_overrides(knobs)
    write_variant_tables(elite_bands, boss_bands)

    # ── Console report ──
    print(f"Wrote combat.* overrides to {written} FloorConfig assets -> "
          f"{FLOOR_DIR.relative_to(ROOT)}")
    print(f"Wrote re-derived variant tables -> {PRICING_PATH.relative_to(ROOT)}")
    print(f"Challenge spine fit c={curve[0]:.1f}, p={curve[1]:.3f} (Challenge(F) = c * F^p); "
          f"Elite/NORMAL threat ratio {elite_mult:.2f}.")
    boss_anchor_hp = archetype_anchors()["Boss"][0]
    top_hp = npc_scaled_hp(boss_anchor_hp, 60) * next(
        hp for r, hp, _d in boss_bands if 60 >= threshold(r))
    print(f"Top-floor (F60) boss HP target ~{top_hp:.0f} (uncapped).")
    print()
    print("Per-floor: raw Challenge, smooth Challenge, solved eliteRate / difficultyMult:")
    print(f"  {'floor':>5}{'kills':>7}{'C_raw':>11}{'C_smooth':>11}{'eliteRate':>10}{'diffMult':>9}")
    for r in rows:
        floor = r["floor"]
        er, dm = knobs[floor]
        print(f"  {floor:>5}{r['kills']:>7.0f}{r['raw_challenge']:>11.0f}"
              f"{challenge_smooth(floor, curve):>11.0f}{er:>10.3f}{dm:>9.3f}")

    upsert_report(rows, curve, knobs, elite_bands, boss_bands, elite_mult)
    print(f"\nUpserted report -> {DISCOVERY_DOC.relative_to(ROOT)}")
    return 0


def upsert_report(rows, curve, knobs, elite_bands, boss_bands, elite_mult) -> None:
    """Replace (or append) the report section in the discovery doc — idempotent across re-runs."""
    if not DISCOVERY_DOC.parent.exists():
        return
    calib = [r for r in rows if r["floor"] <= CALIBRATION_MAX_FLOOR]
    deep = [r for r in rows if r["floor"] > CALIBRATION_MAX_FLOOR]

    def band_residual(band_rows):
        errs = [abs(challenge_smooth(r["floor"], curve) - r["raw_challenge"]) / r["raw_challenge"]
                for r in band_rows if r["raw_challenge"] > 0]
        return (sum(errs) / len(errs)) if errs else 0.0

    boss_anchor_hp = archetype_anchors()["Boss"][0]

    def boss_hp_at(level: int) -> float:
        hp_mult = next(hp for r, hp, _d in boss_bands if level >= threshold(r))
        return npc_scaled_hp(boss_anchor_hp, level) * hp_mult

    body = [
        "Challenge(F) is the per-floor threat budget (weight-averaged on-level archetype HP x damage "
        "times est_kills). Per the smooth-budget decision a monotonic spine Challenge(F) = "
        f"{curve[0]:.1f}*F^{curve[1]:.3f} is fit on floors 1-{CALIBRATION_MAX_FLOOR} and extended "
        "through F70, so the lumpy floor texture (the F5-15 density spike, etc.) stays hand-authored "
        "while the difficulty SPINE is well-behaved. The per-floor knobs close the gap: a sparse floor "
        "gets more Elites and/or a flat multiplier; a dense wave floor gets ~none.",
        "",
        f"Smooth-fit mean relative error: floors 1-{CALIBRATION_MAX_FLOOR} {band_residual(calib):.1%}, "
        f"floors {CALIBRATION_MAX_FLOOR + 1}-{MAX_LEVEL} {band_residual(deep):.1%} (the deep floors "
        "sagged in raw threat; extending the spine is the fix, mirroring the income derivation).",
        "",
        "Realization policy (resolved): raise the Elite rate first (visible variety) up to "
        f"{ELITE_RATE_MAX:g}, then a flat difficultyMult in "
        f"[{DIFFICULTY_MULT_MIN:g}, {DIFFICULTY_MULT_MAX:g}] for the remainder. Elite/NORMAL threat "
        f"ratio {elite_mult:.2f}. Inert default (eliteRate 0.0, difficultyMult 1.0) leaves a floor "
        "unchanged until these overrides are authored.",
        "",
        "Variant tables (re-derived; the difficulty lever now the HP cap is removed): an Elite is "
        "~2x a same-archetype NORMAL kill (HP-driven TTK), and the Boss HP ramps to a deliberate, "
        f"uncapped ceiling (F60 boss ~{boss_hp_at(60):,.0f} HP).",
        "",
        "| Floor | est kills | raw Challenge | smooth Challenge | eliteRate | difficultyMult |",
        "|---|---|---|---|---|---|",
    ]
    for r in rows:
        floor = r["floor"]
        er, dm = knobs[floor]
        body.append(f"| {floor} | {r['kills']:.0f} | {r['raw_challenge']:.0f} | "
                    f"{challenge_smooth(floor, curve):.0f} | {er:.3f} | {dm:.3f} |")
    body += [
        "",
        "Re-derived Elite variant steps (HpMult ~ TTK ratio vs a same-archetype NORMAL):",
        "",
        "| MinLevelRatio | HpMult | DamageMult |",
        "|---|---|---|",
    ]
    for ratio, hp, dmg in elite_bands:
        body.append(f"| {ratio:g} | {hp:g} | {dmg:g} |")
    body += [
        "",
        "Re-derived Boss variant steps (HpMult solved from explicit boss-HP targets at each band's "
        "start level against the Boss anchor + level curve):",
        "",
        "| MinLevelRatio | start level | HpMult | DamageMult | boss HP @ start |",
        "|---|---|---|---|---|",
    ]
    for ratio, hp, dmg in boss_bands:
        start = threshold(ratio)
        body.append(f"| {ratio:g} | {start} | {hp:g} | {dmg:g} | {boss_hp_at(start):,.0f} |")
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
