#!/usr/bin/env python3
"""
generate_scaling.py — Compute scaled stats for levels 1–60 and populate *_scaled tables.

Reads *_base tables from scaling.db, applies sigmoid scaling formulas,
and writes precomputed values to *_scaled tables.

Usage:
    uv run generate_scaling.py [--db scaling.db] [--verify]
"""
from __future__ import annotations

import argparse
import json
import math
import sqlite3
import sys
from pathlib import Path


# ── Scaling Formulas ──────────────────────────────────────────────────

def sigmoid(level: int, midpoint: float = 30.0, steepness: float = 0.12) -> float:
    """Normalized sigmoid scaling factor. S(1)≈0.03, S(30)=0.50, S(60)≈0.97."""
    return 1.0 / (1.0 + math.exp(-steepness * (level - midpoint)))


def scale_stat(base: float, level: int, k: float) -> float:
    """Apply sigmoid scaling: base × (1 + (k-1) × S(L))."""
    return base * (1.0 + (k - 1.0) * sigmoid(level))


# ── Monster Scaling Config ────────────────────────────────────────────

MONSTER_HP_K = 8.0       # Max HP multiplier at L60
MONSTER_DMG_K = 5.0      # Max damage multiplier at L60
BOSS_HP_K = 4.0          # Bosses scale more slowly
BOSS_DMG_K = 3.0

# Elite multipliers per level range
ELITE_TIERS = [
    (1, 10, 1.0, 1.0),     # No elites
    (10, 20, 1.5, 1.3),
    (20, 30, 2.0, 1.5),
    (30, 45, 2.5, 1.8),
    (45, 61, 3.0, 2.0),
]

# Weapon and armor scaling
WEAPON_DMG_K = 6.0       # Max weapon damage multiplier
ARMOR_RESIST_K = 4.0     # Max armor resistance multiplier
ARMOR_DR_CAP = 0.65      # Hard cap on damage reduction (65%)

# Safeguards
MAX_HP = 10_000
MAX_DAMAGE = 500.0
MIN_MULTIPLIER = 1.0


def get_elite_multipliers(level: int) -> tuple[float, float]:
    """Return (hp_mult, dmg_mult) for elites at the given level."""
    for low, high, hp_m, dmg_m in ELITE_TIERS:
        if low <= level < high:
            return hp_m, dmg_m
    return 1.0, 1.0


# ── Schema ────────────────────────────────────────────────────────────

SCALED_DDL = """
DROP TABLE IF EXISTS monsters_scaled;
CREATE TABLE monsters_scaled (
    npc_id          TEXT NOT NULL,
    level           INTEGER NOT NULL,
    scaled_hp       INTEGER NOT NULL,
    scaled_damage   REAL NOT NULL,
    damage_mult     REAL NOT NULL,
    effective_dps   REAL,
    elite_hp        INTEGER,
    elite_damage    REAL,
    modifiers_json  TEXT,
    PRIMARY KEY (npc_id, level)
);

DROP TABLE IF EXISTS weapons_scaled;
CREATE TABLE weapons_scaled (
    weapon_id       TEXT NOT NULL,
    level           INTEGER NOT NULL,
    damage_mult     REAL NOT NULL,
    effective_dps   REAL,
    modifiers_json  TEXT,
    PRIMARY KEY (weapon_id, level)
);

DROP TABLE IF EXISTS armor_scaled;
CREATE TABLE armor_scaled (
    armor_id        TEXT NOT NULL,
    level           INTEGER NOT NULL,
    resist_mult     REAL NOT NULL,
    effective_dr    REAL NOT NULL,
    effective_ehp_bonus REAL,
    modifiers_json  TEXT,
    PRIMARY KEY (armor_id, level)
);

CREATE INDEX IF NOT EXISTS idx_monsters_scaled_level ON monsters_scaled(level, npc_id);
CREATE INDEX IF NOT EXISTS idx_weapons_scaled_level ON weapons_scaled(level, weapon_id);
CREATE INDEX IF NOT EXISTS idx_armor_scaled_level ON armor_scaled(level, armor_id);
"""


def generate_monster_scaling(conn: sqlite3.Connection) -> int:
    """Generate scaled stats for all monsters at levels 1–60."""
    rows = conn.execute(
        "SELECT npc_id, tier, base_hp, base_damage FROM monsters_base"
    ).fetchall()

    count = 0
    for npc_id, tier, base_hp, base_damage in rows:
        is_boss = tier == "Boss"
        hp_k = BOSS_HP_K if is_boss else MONSTER_HP_K
        dmg_k = BOSS_DMG_K if is_boss else MONSTER_DMG_K

        for level in range(1, 61):
            s = sigmoid(level)

            scaled_hp = int(round(base_hp * (1.0 + (hp_k - 1.0) * s)))
            scaled_hp = min(scaled_hp, MAX_HP)
            scaled_hp = max(scaled_hp, base_hp)

            dmg_mult = max(1.0 + (dmg_k - 1.0) * s, MIN_MULTIPLIER)
            scaled_damage = min(base_damage * dmg_mult, MAX_DAMAGE)

            # Assume ~1 attack per 2s for basic DPS estimate
            effective_dps = scaled_damage / 2.0

            # Elite values
            elite_hp_mult, elite_dmg_mult = get_elite_multipliers(level)
            elite_hp = int(round(scaled_hp * elite_hp_mult))
            elite_hp = min(elite_hp, MAX_HP)
            elite_damage = min(scaled_damage * elite_dmg_mult, MAX_DAMAGE)

            modifiers = {
                "hp_multiplier": round(scaled_hp / base_hp, 3),
                "dmg_multiplier": round(dmg_mult, 3),
                "sigmoid": round(s, 4),
            }
            if is_boss:
                modifiers["boss"] = True

            conn.execute(
                """INSERT INTO monsters_scaled
                   (npc_id, level, scaled_hp, scaled_damage, damage_mult,
                    effective_dps, elite_hp, elite_damage, modifiers_json)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                (
                    npc_id, level, scaled_hp, round(scaled_damage, 2),
                    round(dmg_mult, 4), round(effective_dps, 2),
                    elite_hp, round(elite_damage, 2),
                    json.dumps(modifiers),
                ),
            )
            count += 1

    conn.commit()
    print(f"  Generated {count} monster scaling rows ({len(rows)} NPCs × 60 levels)")
    return count


def generate_weapon_scaling(conn: sqlite3.Connection) -> int:
    """Generate scaled damage multipliers for all weapons at levels 1–60."""
    rows = conn.execute(
        "SELECT weapon_id, base_damage FROM weapons_base"
    ).fetchall()

    count = 0
    for weapon_id, base_damage in rows:
        for level in range(1, 61):
            s = sigmoid(level)
            dmg_mult = max(1.0 + (WEAPON_DMG_K - 1.0) * s, MIN_MULTIPLIER)

            # Assume 1.5s attack cycle for DPS estimate
            effective_dps = (base_damage * dmg_mult) / 1.5

            modifiers = {
                "dmg_multiplier": round(dmg_mult, 3),
                "sigmoid": round(s, 4),
            }

            conn.execute(
                """INSERT INTO weapons_scaled
                   (weapon_id, level, damage_mult, effective_dps, modifiers_json)
                   VALUES (?, ?, ?, ?, ?)""",
                (
                    weapon_id, level, round(dmg_mult, 4),
                    round(effective_dps, 2), json.dumps(modifiers),
                ),
            )
            count += 1

    conn.commit()
    print(f"  Generated {count} weapon scaling rows ({len(rows)} weapons × 60 levels)")
    return count


def generate_armor_scaling(conn: sqlite3.Connection) -> int:
    """Generate scaled resistance values for all armor at levels 1–60."""
    rows = conn.execute(
        "SELECT armor_id, phys_resist, health_bonus FROM armor_base"
    ).fetchall()

    count = 0
    for armor_id, phys_resist, health_bonus in rows:
        for level in range(1, 61):
            s = sigmoid(level)
            resist_mult = max(1.0 + (ARMOR_RESIST_K - 1.0) * s, MIN_MULTIPLIER)

            # Effective DR with diminishing returns + hard cap
            effective_dr = phys_resist * resist_mult
            effective_dr = min(effective_dr, ARMOR_DR_CAP)

            # EHP bonus from this piece: health_bonus / (1 - DR) - health_bonus
            if effective_dr < 1.0:
                ehp_bonus = health_bonus / (1.0 - effective_dr) - health_bonus
            else:
                ehp_bonus = health_bonus * 10  # cap

            modifiers = {
                "resist_multiplier": round(resist_mult, 3),
                "sigmoid": round(s, 4),
            }

            conn.execute(
                """INSERT INTO armor_scaled
                   (armor_id, level, resist_mult, effective_dr, effective_ehp_bonus,
                    modifiers_json)
                   VALUES (?, ?, ?, ?, ?, ?)""",
                (
                    armor_id, level, round(resist_mult, 4),
                    round(effective_dr, 6), round(ehp_bonus, 2),
                    json.dumps(modifiers),
                ),
            )
            count += 1

    conn.commit()
    print(f"  Generated {count} armor scaling rows ({len(rows)} pieces × 60 levels)")
    return count


def verify_scaling(conn: sqlite3.Connection) -> bool:
    """Verify all scaled values are within bounds."""
    print("\n── Verification ──")
    ok = True

    # Check monster HP bounds
    row = conn.execute("SELECT COUNT(*) FROM monsters_scaled WHERE scaled_hp > ?", (MAX_HP,)).fetchone()
    if row[0] > 0:
        print(f"  FAIL: {row[0]} monster rows exceed max HP {MAX_HP}")
        ok = False

    # Check damage bounds
    row = conn.execute("SELECT COUNT(*) FROM monsters_scaled WHERE scaled_damage > ?", (MAX_DAMAGE,)).fetchone()
    if row[0] > 0:
        print(f"  FAIL: {row[0]} monster rows exceed max damage {MAX_DAMAGE}")
        ok = False

    # Check multiplier floors
    row = conn.execute("SELECT COUNT(*) FROM monsters_scaled WHERE damage_mult < ?", (MIN_MULTIPLIER,)).fetchone()
    if row[0] > 0:
        print(f"  FAIL: {row[0]} monster rows have damage_mult < {MIN_MULTIPLIER}")
        ok = False

    row = conn.execute("SELECT COUNT(*) FROM weapons_scaled WHERE damage_mult < ?", (MIN_MULTIPLIER,)).fetchone()
    if row[0] > 0:
        print(f"  FAIL: {row[0]} weapon rows have damage_mult < {MIN_MULTIPLIER}")
        ok = False

    row = conn.execute("SELECT COUNT(*) FROM armor_scaled WHERE effective_dr > ?", (ARMOR_DR_CAP + 0.001,)).fetchone()
    if row[0] > 0:
        print(f"  FAIL: {row[0]} armor rows exceed DR cap {ARMOR_DR_CAP}")
        ok = False

    # Spot-check: Zombie at L30 should have ~4.5× HP
    row = conn.execute(
        "SELECT scaled_hp, damage_mult FROM monsters_scaled WHERE npc_id = 'Zombie' AND level = 30"
    ).fetchone()
    if row:
        hp_mult = row[0] / 49.0  # base Zombie HP
        if not (3.5 <= hp_mult <= 5.5):
            print(f"  WARN: Zombie L30 HP mult = {hp_mult:.2f} (expected ~4.5)")
        else:
            print(f"  OK: Zombie L30 HP = {row[0]} (×{hp_mult:.2f}), Dmg mult = ×{row[1]:.2f}")

    if ok:
        print("  All checks passed!")
    return ok


def print_sample_table(conn: sqlite3.Connection):
    """Print sample scaling values for key NPCs at breakpoint levels."""
    print("\n── Sample Scaling Table (Monsters) ──")
    levels = [1, 10, 15, 20, 30, 40, 45, 50, 60]
    npcs = ["Zombie", "Skeleton_Fighter", "Bear_Grizzly", "Werewolf", "Shadow_Knight", "Dragon_Fire"]

    header = f"{'NPC':<22s}" + "".join(f"{'L'+str(l):>8s}" for l in levels)
    print(header)
    print("-" * len(header))

    for npc_id in npcs:
        # Get base HP
        base_row = conn.execute("SELECT base_hp FROM monsters_base WHERE npc_id = ?", (npc_id,)).fetchone()
        if not base_row:
            continue

        row_hp = f"{npc_id:<22s}"
        row_dmg = f"{'  (dmg mult)':<22s}"

        for level in levels:
            scaled = conn.execute(
                "SELECT scaled_hp, damage_mult FROM monsters_scaled WHERE npc_id = ? AND level = ?",
                (npc_id, level),
            ).fetchone()
            if scaled:
                row_hp += f"{scaled[0]:>8d}"
                row_dmg += f"{'×'+f'{scaled[1]:.2f}':>8s}"
            else:
                row_hp += f"{'—':>8s}"
                row_dmg += f"{'—':>8s}"

        print(row_hp)
        print(row_dmg)

    print("\n── Sample Scaling Table (Weapons) ──")
    header = f"{'Weapon':<30s}" + "".join(f"{'L'+str(l):>8s}" for l in levels)
    print(header)
    print("-" * len(header))

    weapons = ["Weapon_Sword_Cobalt", "Weapon_Axe_Cobalt", "Weapon_Sword_Mithril"]
    for w_id in weapons:
        base_row = conn.execute("SELECT base_damage FROM weapons_base WHERE weapon_id = ?", (w_id,)).fetchone()
        if not base_row:
            continue
        row = f"{w_id:<30s}"
        for level in levels:
            scaled = conn.execute(
                "SELECT damage_mult FROM weapons_scaled WHERE weapon_id = ? AND level = ?",
                (w_id, level),
            ).fetchone()
            if scaled:
                effective = base_row[0] * scaled[0]
                row += f"{effective:>8.1f}"
            else:
                row += f"{'—':>8s}"
        print(row)


def main():
    parser = argparse.ArgumentParser(description="Generate scaled stats for levels 1–60")
    parser.add_argument(
        "--db",
        default=str(Path(__file__).resolve().parent / "scaling.db"),
        help="Path to SQLite database",
    )
    parser.add_argument(
        "--verify",
        action="store_true",
        help="Run verification checks after generation",
    )
    args = parser.parse_args()

    db_path = Path(args.db)
    if not db_path.exists():
        print(f"ERROR: Database not found: {db_path}", file=sys.stderr)
        print("Run parse_assets.py first to create the base tables.", file=sys.stderr)
        sys.exit(1)

    conn = sqlite3.connect(str(db_path))

    try:
        # Check base tables have data
        npc_count = conn.execute("SELECT COUNT(*) FROM monsters_base").fetchone()[0]
        weapon_count = conn.execute("SELECT COUNT(*) FROM weapons_base").fetchone()[0]
        armor_count = conn.execute("SELECT COUNT(*) FROM armor_base").fetchone()[0]

        if npc_count == 0:
            print("ERROR: monsters_base is empty. Run parse_assets.py first.", file=sys.stderr)
            sys.exit(1)

        print(f"Base data: {npc_count} NPCs, {weapon_count} weapons, {armor_count} armor")
        print()

        # Drop and recreate scaled tables (idempotent)
        conn.executescript(SCALED_DDL)

        # Generate scaling
        generate_monster_scaling(conn)
        generate_weapon_scaling(conn)
        generate_armor_scaling(conn)

        # Store config
        config = {
            "monster_hp_k": MONSTER_HP_K,
            "monster_dmg_k": MONSTER_DMG_K,
            "boss_hp_k": BOSS_HP_K,
            "boss_dmg_k": BOSS_DMG_K,
            "weapon_dmg_k": WEAPON_DMG_K,
            "armor_resist_k": ARMOR_RESIST_K,
            "armor_dr_cap": ARMOR_DR_CAP,
            "sigmoid_midpoint": 30.0,
            "sigmoid_steepness": 0.12,
        }
        conn.execute(
            "INSERT OR REPLACE INTO scaling_config (key, value) VALUES (?, ?)",
            ("scaling_parameters", json.dumps(config)),
        )
        conn.commit()

        print_sample_table(conn)

        if args.verify:
            ok = verify_scaling(conn)
            if not ok:
                sys.exit(1)

        print("\nDone!")
    finally:
        conn.close()


if __name__ == "__main__":
    main()
