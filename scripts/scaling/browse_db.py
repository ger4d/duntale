#!/usr/bin/env python3
"""
browse_db.py — Rich-formatted table browser for scaling.db.

Displays NPCs, weapons, and armor with proper column alignment, sorting,
filtering, and color-coded output.

Usage:
    uv run --with rich browse_db.py npc [--sort hp] [--order desc] [--count 20] [--tier Elite] [--category Undead]
    uv run --with rich browse_db.py weapon [--sort dmg] [--order desc] [--count 20] [--family Sword]
    uv run --with rich browse_db.py armor [--sort phys] [--order desc] [--count 20] [--slot Chest]
    uv run --with rich browse_db.py stats
"""
from __future__ import annotations

import argparse
import sqlite3
import sys
from pathlib import Path

from rich.console import Console
from rich.table import Table
from rich.text import Text

console = Console()

# ── Sort column mappings (alias → real column name) ───────────────────

NPC_SORT = {
    "hp": "base_hp", "dmg": "base_damage", "name": "name",
    "tier": "tier", "speed": "base_speed", "category": "category",
}

WEAPON_SORT = {
    "dmg": "base_damage", "name": "name", "family": "family",
    "level": "item_level", "quality": "quality",
}

ARMOR_SORT = {
    "phys": "phys_resist", "proj": "proj_resist", "hp": "health_bonus",
    "name": "name", "slot": "slot", "level": "item_level", "quality": "quality",
}

# ── Tier colors ───────────────────────────────────────────────────────

TIER_COLORS = {
    "Fodder": "dim", "Standard": "white", "Tough": "yellow",
    "Elite": "bold magenta", "Boss": "bold red",
}

QUALITY_COLORS = {
    "Common": "white", "Uncommon": "green", "Rare": "cyan",
    "Epic": "bold magenta", "Legendary": "bold yellow",
    "Developer": "bold red", "NPC": "dim",
}


def get_db(db_path: Path) -> sqlite3.Connection:
    if not db_path.exists():
        console.print(f"[red]Database not found:[/red] {db_path}")
        sys.exit(1)
    conn = sqlite3.connect(str(db_path))
    conn.row_factory = sqlite3.Row
    return conn


# ── NPC listing ───────────────────────────────────────────────────────

def list_npcs(conn: sqlite3.Connection, args: argparse.Namespace) -> None:
    col = NPC_SORT.get(args.sort, "base_hp")
    direction = "ASC" if args.order == "asc" else "DESC"

    query = "SELECT * FROM monsters_base"
    params: list = []
    wheres: list[str] = []

    if args.tier:
        wheres.append("tier = ?")
        params.append(args.tier)
    if args.category:
        wheres.append("category LIKE ?")
        params.append(f"%{args.category}%")

    if wheres:
        query += " WHERE " + " AND ".join(wheres)
    query += f" ORDER BY {col} {direction} LIMIT ?"
    params.append(args.count)

    rows = conn.execute(query, params).fetchall()

    table = Table(
        title=f"NPCs (sort={args.sort} {direction}, {len(rows)} results)",
        title_style="bold gold1",
        border_style="dim",
        show_lines=False,
    )
    table.add_column("Name", style="white", min_width=25)
    table.add_column("Category", style="dim")
    table.add_column("Tier", justify="center")
    table.add_column("HP", justify="right", style="green")
    table.add_column("Dmg", justify="right", style="red")
    table.add_column("Speed", justify="right")
    table.add_column("AtkDist", justify="right")
    table.add_column("AI Template", style="dim", max_width=20)

    for r in rows:
        tier_style = TIER_COLORS.get(r["tier"], "white")
        table.add_row(
            r["name"],
            r["category"],
            Text(r["tier"], style=tier_style),
            str(r["base_hp"]),
            f"{r['base_damage']:.1f}",
            f"{r['base_speed']:.0f}" if r["base_speed"] else "—",
            f"{r['attack_distance']:.1f}" if r["attack_distance"] else "—",
            r["ai_template"] or "—",
        )

    console.print(table)


# ── Weapon listing ────────────────────────────────────────────────────

def list_weapons(conn: sqlite3.Connection, args: argparse.Namespace) -> None:
    col = WEAPON_SORT.get(args.sort, "base_damage")
    direction = "ASC" if args.order == "asc" else "DESC"

    query = "SELECT * FROM weapons_base"
    params: list = []
    wheres: list[str] = []

    if args.family:
        wheres.append("family LIKE ?")
        params.append(f"%{args.family}%")

    if wheres:
        query += " WHERE " + " AND ".join(wheres)
    query += f" ORDER BY {col} {direction} LIMIT ?"
    params.append(args.count)

    rows = conn.execute(query, params).fetchall()

    table = Table(
        title=f"Weapons (sort={args.sort} {direction}, {len(rows)} results)",
        title_style="bold gold1",
        border_style="dim",
        show_lines=False,
    )
    table.add_column("Name", style="white", min_width=30)
    table.add_column("Family", style="cyan")
    table.add_column("Quality", justify="center")
    table.add_column("Lvl", justify="right")
    table.add_column("Dmg", justify="right", style="red")

    for r in rows:
        q_style = QUALITY_COLORS.get(r["quality"], "white")
        table.add_row(
            r["name"],
            r["family"],
            Text(r["quality"], style=q_style),
            str(r["item_level"]),
            f"{r['base_damage']:.1f}",
        )

    console.print(table)


# ── Armor listing ─────────────────────────────────────────────────────

def list_armor(conn: sqlite3.Connection, args: argparse.Namespace) -> None:
    col = ARMOR_SORT.get(args.sort, "phys_resist")
    direction = "ASC" if args.order == "asc" else "DESC"

    query = "SELECT * FROM armor_base"
    params: list = []
    wheres: list[str] = []

    if args.slot:
        wheres.append("slot LIKE ?")
        params.append(f"%{args.slot}%")

    if wheres:
        query += " WHERE " + " AND ".join(wheres)
    query += f" ORDER BY {col} {direction} LIMIT ?"
    params.append(args.count)

    rows = conn.execute(query, params).fetchall()

    table = Table(
        title=f"Armor (sort={args.sort} {direction}, {len(rows)} results)",
        title_style="bold gold1",
        border_style="dim",
        show_lines=False,
    )
    table.add_column("Name", style="white", min_width=25)
    table.add_column("Slot", style="cyan")
    table.add_column("Quality", justify="center")
    table.add_column("Lvl", justify="right")
    table.add_column("Phys%", justify="right", style="green")
    table.add_column("Proj%", justify="right", style="yellow")
    table.add_column("HP+", justify="right", style="magenta")
    table.add_column("Special", style="dim")

    for r in rows:
        q_style = QUALITY_COLORS.get(r["quality"], "white")
        table.add_row(
            r["name"],
            r["slot"],
            Text(r["quality"], style=q_style),
            str(r["item_level"]),
            f"{r['phys_resist'] * 100:.1f}",
            f"{r['proj_resist'] * 100:.1f}",
            str(r["health_bonus"]) if r["health_bonus"] else "—",
            r["special"] or "—",
        )

    console.print(table)


# ── Stats summary ─────────────────────────────────────────────────────

def show_stats(conn: sqlite3.Connection) -> None:
    npc_count = conn.execute("SELECT COUNT(*) FROM monsters_base").fetchone()[0]
    wpn_count = conn.execute("SELECT COUNT(*) FROM weapons_base").fetchone()[0]
    arm_count = conn.execute("SELECT COUNT(*) FROM armor_base").fetchone()[0]

    table = Table(title="Database Summary", title_style="bold gold1", border_style="dim")
    table.add_column("Table", style="cyan")
    table.add_column("Count", justify="right", style="green")

    table.add_row("monsters_base", str(npc_count))
    table.add_row("weapons_base", str(wpn_count))
    table.add_row("armor_base", str(arm_count))
    console.print(table)

    # Tier breakdown
    tier_table = Table(title="NPC Tier Breakdown", title_style="bold gold1", border_style="dim")
    tier_table.add_column("Tier", justify="center")
    tier_table.add_column("Count", justify="right")
    tier_table.add_column("Avg HP", justify="right")
    tier_table.add_column("Avg Dmg", justify="right")

    for row in conn.execute(
        "SELECT tier, COUNT(*) as cnt, AVG(base_hp) as avg_hp, AVG(base_damage) as avg_dmg "
        "FROM monsters_base GROUP BY tier ORDER BY avg_hp DESC"
    ):
        style = TIER_COLORS.get(row["tier"], "white")
        tier_table.add_row(
            Text(row["tier"], style=style),
            str(row["cnt"]),
            f"{row['avg_hp']:.0f}",
            f"{row['avg_dmg']:.1f}",
        )
    console.print(tier_table)

    # Category breakdown
    cat_table = Table(title="NPC Category Breakdown", title_style="bold gold1", border_style="dim")
    cat_table.add_column("Category")
    cat_table.add_column("Count", justify="right")

    for row in conn.execute(
        "SELECT category, COUNT(*) as cnt FROM monsters_base GROUP BY category ORDER BY cnt DESC"
    ):
        cat_table.add_row(row["category"], str(row["cnt"]))
    console.print(cat_table)

    # Weapon family breakdown
    fam_table = Table(title="Weapon Family Breakdown", title_style="bold gold1", border_style="dim")
    fam_table.add_column("Family", style="cyan")
    fam_table.add_column("Count", justify="right")
    fam_table.add_column("Avg Dmg", justify="right")

    for row in conn.execute(
        "SELECT family, COUNT(*) as cnt, AVG(base_damage) as avg_dmg "
        "FROM weapons_base GROUP BY family ORDER BY avg_dmg DESC"
    ):
        fam_table.add_row(row["family"], str(row["cnt"]), f"{row['avg_dmg']:.1f}")
    console.print(fam_table)


# ── CLI ───────────────────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser(description="Browse scaling.db with Rich tables")
    parser.add_argument("--db", default="scaling.db", help="Path to scaling.db")
    sub = parser.add_subparsers(dest="command")

    # npc
    npc_p = sub.add_parser("npc", help="List NPCs")
    npc_p.add_argument("--sort", default="hp", choices=list(NPC_SORT.keys()))
    npc_p.add_argument("--order", default="desc", choices=["asc", "desc"])
    npc_p.add_argument("--count", type=int, default=30)
    npc_p.add_argument("--tier", help="Filter by tier (Fodder, Standard, Tough, Elite, Boss)")
    npc_p.add_argument("--category", help="Filter by category")

    # weapon
    wpn_p = sub.add_parser("weapon", help="List weapons")
    wpn_p.add_argument("--sort", default="dmg", choices=list(WEAPON_SORT.keys()))
    wpn_p.add_argument("--order", default="desc", choices=["asc", "desc"])
    wpn_p.add_argument("--count", type=int, default=30)
    wpn_p.add_argument("--family", help="Filter by weapon family")

    # armor
    arm_p = sub.add_parser("armor", help="List armor")
    arm_p.add_argument("--sort", default="phys", choices=list(ARMOR_SORT.keys()))
    arm_p.add_argument("--order", default="desc", choices=["asc", "desc"])
    arm_p.add_argument("--count", type=int, default=30)
    arm_p.add_argument("--slot", help="Filter by armor slot")

    # stats
    sub.add_parser("stats", help="Show database summary statistics")

    args = parser.parse_args()
    if not args.command:
        parser.print_help()
        sys.exit(1)

    db_path = Path(args.db)
    conn = get_db(db_path)

    try:
        match args.command:
            case "npc":
                list_npcs(conn, args)
            case "weapon":
                list_weapons(conn, args)
            case "armor":
                list_armor(conn, args)
            case "stats":
                show_stats(conn)
    finally:
        conn.close()


if __name__ == "__main__":
    main()
