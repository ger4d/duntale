#!/usr/bin/env python3
"""
simulate_combat.py — Time-to-kill & EHP simulations at breakpoint levels.

Reads *_scaled tables from scaling.db and runs combat simulations to
validate balance. Target TTK: 6–10s for normal mobs.

Usage:
    uv run simulate_combat.py [--db scaling.db]
"""
from __future__ import annotations

import argparse
import json
import math
import sqlite3
import sys
from dataclasses import dataclass
from pathlib import Path


BREAKPOINT_LEVELS = [1, 15, 30, 45, 60]

SCRIPT_DIR = Path(__file__).resolve().parent
GEAR_CURVES_JSON = SCRIPT_DIR.parent.parent / "src/main/resources/Server/Configs/Scaling/GearCurves.json"

# Assumptions for simulation
PLAYER_BASE_HP = 20.0          # Hytale default player HP
# Melee attack cadence is a single Agility-driven throttle (CombatScaling research):
# 400 ms @ Agility 0, identical for every weapon. Player per-hit now comes from the
# authored family anchor, not the weapon's asset damage.
PLAYER_ATTACK_SPEED = 0.4      # seconds between attacks (throttle floor @ Agility 0)
MONSTER_ATTACK_SPEED = 2.0     # seconds between attacks
PLAYER_BASE_ARMOR_DR = 0.0     # No armor by default

# Level curve (mirror of derive_gear_curves.py / CombatScaling) for the authored DR budget.
_MIDPOINT, _STEEPNESS = 50.0, 7.2 / 100
_SIG_MIN = 1.0 / (1.0 + math.exp(-_STEEPNESS * (1 - _MIDPOINT)))
_SIG_MAX = 1.0 / (1.0 + math.exp(-_STEEPNESS * (100 - _MIDPOINT)))


def _sigmoid(level: int) -> float:
    level = max(1, min(100, level))
    raw = 1.0 / (1.0 + math.exp(-_STEEPNESS * (level - _MIDPOINT)))
    return max(0.0, min((raw - _SIG_MIN) / (_SIG_MAX - _SIG_MIN), 1.0))


def load_gear_curves() -> dict:
    """Load the authored anchor + DR budget; fall back to neutral values if absent."""
    if not GEAR_CURVES_JSON.exists():
        return {"anchor": 12.0, "dr_min": 0.0, "dr_max": 0.0}
    data = json.loads(GEAR_CURVES_JSON.read_text())
    melee = next((f["AnchorDamage"] for f in data.get("WeaponFamilies", [])
                  if f.get("Name") == "Sword"), data.get("DefaultWeaponAnchor", 12.0))
    return {"anchor": melee,
            "dr_min": data.get("ArmorDrBudgetMin", 0.0),
            "dr_max": data.get("ArmorDrBudgetMax", 0.0)}


def authored_set_dr(curves: dict, level: int) -> float:
    """Full on-level set DR = the budget curve (per-slot shares sum to 1.0)."""
    return curves["dr_min"] + (curves["dr_max"] - curves["dr_min"]) * _sigmoid(level)


@dataclass
class CombatResult:
    """Result of a simulated combat encounter."""
    npc_id: str
    level: int
    npc_hp: int
    npc_damage: float
    weapon_id: str
    weapon_base_dmg: float
    weapon_mult: float
    armor_id: str | None
    armor_dr: float
    player_dps: float
    npc_dps: float
    ttk_monster: float   # Time for player to kill monster
    ttk_player: float    # Time for monster to kill player
    survival_ratio: float  # TTK_player / TTK_monster (>1 = player wins)


def simulate(
    npc_hp: int,
    npc_damage: float,
    weapon_base_dmg: float,
    weapon_mult: float,
    armor_dr: float,
) -> tuple[float, float, float, float]:
    """Return (player_dps, npc_dps, ttk_monster, ttk_player)."""
    player_damage_per_hit = weapon_base_dmg * weapon_mult
    player_dps = player_damage_per_hit / PLAYER_ATTACK_SPEED

    effective_npc_dmg = npc_damage * (1.0 - armor_dr)
    npc_dps = effective_npc_dmg / MONSTER_ATTACK_SPEED

    ttk_monster = npc_hp / player_dps if player_dps > 0 else float("inf")
    ttk_player = PLAYER_BASE_HP / npc_dps if npc_dps > 0 else float("inf")

    return player_dps, npc_dps, ttk_monster, ttk_player


def run_simulations(conn: sqlite3.Connection) -> list[CombatResult]:
    """Run combat simulations for all breakpoint levels."""
    results: list[CombatResult] = []

    # Player per-hit now comes from the authored melee anchor on the level curve, not from any
    # individual weapon's asset damage. Reuse weapons_scaled only for the level curve (damage_mult).
    curves = load_gear_curves()
    anchor = curves["anchor"]
    weapon_id = "AuthoredMelee"

    # Any weapon row carries the same level curve; pick one to read damage_mult per level.
    curve_row = conn.execute("SELECT weapon_id FROM weapons_base LIMIT 1").fetchone()
    curve_weapon = curve_row[0] if curve_row else None

    # Get a diverse set of NPCs (sample by tier)
    npcs = conn.execute(
        "SELECT npc_id, tier, base_hp, base_damage FROM monsters_base "
        "WHERE base_damage > 0 ORDER BY base_hp"
    ).fetchall()

    for level in BREAKPOINT_LEVELS:
        # Get the level curve (weaponMult) at this level
        w_row = conn.execute(
            "SELECT damage_mult FROM weapons_scaled WHERE weapon_id = ? AND level = ?",
            (curve_weapon, level),
        ).fetchone() if curve_weapon else None
        w_mult = w_row[0] if w_row else 1.0

        # Authored armor: a full on-level set DR equals the budget curve.
        armor_id = "AuthoredSet"
        armor_dr = authored_set_dr(curves, level)

        for npc_id, tier, base_hp, base_damage in npcs:
            m_row = conn.execute(
                "SELECT scaled_hp, scaled_damage FROM monsters_scaled WHERE npc_id = ? AND level = ?",
                (npc_id, level),
            ).fetchone()
            if not m_row:
                continue

            npc_hp, npc_damage = m_row

            player_dps, npc_dps, ttk_m, ttk_p = simulate(
                npc_hp, npc_damage, anchor, w_mult, armor_dr
            )

            survival_ratio = ttk_p / ttk_m if ttk_m > 0 and ttk_m != float("inf") else float("inf")

            results.append(CombatResult(
                npc_id=npc_id,
                level=level,
                npc_hp=npc_hp,
                npc_damage=npc_damage,
                weapon_id=weapon_id,
                weapon_base_dmg=anchor,
                weapon_mult=w_mult,
                armor_id=armor_id,
                armor_dr=armor_dr,
                player_dps=player_dps,
                npc_dps=npc_dps,
                ttk_monster=ttk_m,
                ttk_player=ttk_p,
                survival_ratio=survival_ratio,
            ))

    return results


def print_report(results: list[CombatResult]):
    """Print a formatted combat simulation report."""
    print("=" * 100)
    print("COMBAT SIMULATION REPORT")
    print("=" * 100)
    print(f"Player HP: {PLAYER_BASE_HP}  |  Attack Speed: {PLAYER_ATTACK_SPEED}s  |  Monster Attack Speed: {MONSTER_ATTACK_SPEED}s")
    print()

    for level in BREAKPOINT_LEVELS:
        level_results = [r for r in results if r.level == level]
        if not level_results:
            continue

        sample = level_results[0]
        print(f"──── Level {level} ────")
        print(f"  Weapon: {sample.weapon_id} (base {sample.weapon_base_dmg}, ×{sample.weapon_mult:.2f})")
        if sample.armor_id:
            print(f"  Armor:  {sample.armor_id} (DR {sample.armor_dr:.1%})")
        print()

        header = f"  {'NPC':<25s} {'HP':>6s} {'NpcDmg':>7s} {'PlrDPS':>7s} {'NpcDPS':>7s} {'TTK(m)':>7s} {'TTK(p)':>7s} {'Ratio':>7s} {'Status':>8s}"
        print(header)
        print("  " + "-" * (len(header) - 2))

        for r in level_results:
            ttk_m_str = f"{r.ttk_monster:.1f}s" if r.ttk_monster < 999 else "∞"
            ttk_p_str = f"{r.ttk_player:.1f}s" if r.ttk_player < 999 else "∞"
            ratio_str = f"{r.survival_ratio:.2f}" if r.survival_ratio < 999 else "∞"

            # Status based on TTK
            if r.ttk_monster < 4.0:
                status = "TOO-EASY"
            elif r.ttk_monster <= 10.0:
                status = "  OK"
            elif r.ttk_monster <= 15.0:
                status = "  LONG"
            else:
                status = "TOO-HARD"

            if r.survival_ratio < 1.0:
                status = "LETHAL!!"

            print(f"  {r.npc_id:<25s} {r.npc_hp:>6d} {r.npc_damage:>7.1f} {r.player_dps:>7.1f} {r.npc_dps:>7.1f} {ttk_m_str:>7s} {ttk_p_str:>7s} {ratio_str:>7s} {status:>8s}")

        print()

    # Summary stats
    print("──── Summary ────")
    for level in BREAKPOINT_LEVELS:
        level_results = [r for r in results if r.level == level]
        if not level_results:
            continue

        avg_ttk = sum(r.ttk_monster for r in level_results if r.ttk_monster < 999) / max(
            len([r for r in level_results if r.ttk_monster < 999]), 1
        )
        lethal = sum(1 for r in level_results if r.survival_ratio < 1.0)
        too_easy = sum(1 for r in level_results if r.ttk_monster < 4.0)
        ok_count = sum(1 for r in level_results if 4.0 <= r.ttk_monster <= 10.0)

        target = "TARGET: 6-10s"
        print(f"  L{level:>2d}: Avg TTK = {avg_ttk:.1f}s | OK: {ok_count}/{len(level_results)} | "
              f"Too Easy: {too_easy} | Lethal: {lethal} | {target}")


def main():
    parser = argparse.ArgumentParser(description="Combat simulation for scaling validation")
    parser.add_argument(
        "--db",
        default=str(Path(__file__).resolve().parent / "scaling.db"),
        help="Path to SQLite database",
    )
    args = parser.parse_args()

    db_path = Path(args.db)
    if not db_path.exists():
        print(f"ERROR: Database not found: {db_path}", file=sys.stderr)
        print("Run parse_assets.py and generate_scaling.py first.", file=sys.stderr)
        sys.exit(1)

    conn = sqlite3.connect(str(db_path))

    try:
        # Verify scaled tables exist
        tables = {r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'").fetchall()}
        required = {"monsters_scaled", "weapons_scaled", "armor_scaled"}
        missing = required - tables
        if missing:
            print(f"ERROR: Missing tables: {missing}", file=sys.stderr)
            print("Run generate_scaling.py first.", file=sys.stderr)
            sys.exit(1)

        results = run_simulations(conn)
        print_report(results)
    finally:
        conn.close()


if __name__ == "__main__":
    main()
