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
import sqlite3
import sys
from dataclasses import dataclass
from pathlib import Path


BREAKPOINT_LEVELS = [1, 15, 30, 45, 60]

# Assumptions for simulation
PLAYER_BASE_HP = 20.0          # Hytale default player HP
PLAYER_ATTACK_SPEED = 1.5      # seconds between attacks
MONSTER_ATTACK_SPEED = 2.0     # seconds between attacks
PLAYER_BASE_ARMOR_DR = 0.0     # No armor by default


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

    # Get a representative mid-tier weapon for each level
    weapons = conn.execute(
        "SELECT weapon_id, base_damage FROM weapons_base ORDER BY base_damage DESC"
    ).fetchall()

    if not weapons:
        print("  No weapons in database, using default damage", file=sys.stderr)
        weapons = [("Default_Sword", 7.0)]

    # Pick one weapon: median-ish by damage
    mid_weapon = weapons[len(weapons) // 2]
    weapon_id, weapon_base_dmg = mid_weapon

    # Get a representative armor piece
    armor_row = conn.execute(
        "SELECT armor_id, phys_resist FROM armor_base ORDER BY phys_resist DESC LIMIT 1"
    ).fetchone()

    # Get a diverse set of NPCs (sample by tier)
    npcs = conn.execute(
        "SELECT npc_id, tier, base_hp, base_damage FROM monsters_base "
        "WHERE base_damage > 0 ORDER BY base_hp"
    ).fetchall()

    for level in BREAKPOINT_LEVELS:
        # Get weapon mult at this level
        w_row = conn.execute(
            "SELECT damage_mult FROM weapons_scaled WHERE weapon_id = ? AND level = ?",
            (weapon_id, level),
        ).fetchone()
        w_mult = w_row[0] if w_row else 1.0

        # Armor: use matching level if available
        armor_id = None
        armor_dr = PLAYER_BASE_ARMOR_DR
        if armor_row:
            armor_id = armor_row[0]
            a_row = conn.execute(
                "SELECT effective_dr FROM armor_scaled WHERE armor_id = ? AND level = ?",
                (armor_id, level),
            ).fetchone()
            if a_row:
                armor_dr = a_row[0]

        for npc_id, tier, base_hp, base_damage in npcs:
            m_row = conn.execute(
                "SELECT scaled_hp, scaled_damage FROM monsters_scaled WHERE npc_id = ? AND level = ?",
                (npc_id, level),
            ).fetchone()
            if not m_row:
                continue

            npc_hp, npc_damage = m_row

            player_dps, npc_dps, ttk_m, ttk_p = simulate(
                npc_hp, npc_damage, weapon_base_dmg, w_mult, armor_dr
            )

            survival_ratio = ttk_p / ttk_m if ttk_m > 0 and ttk_m != float("inf") else float("inf")

            results.append(CombatResult(
                npc_id=npc_id,
                level=level,
                npc_hp=npc_hp,
                npc_damage=npc_damage,
                weapon_id=weapon_id,
                weapon_base_dmg=weapon_base_dmg,
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
