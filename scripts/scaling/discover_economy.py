#!/usr/bin/env python3
"""
discover_economy.py — Economy discovery analysis for the Duntale rebalance.

Reads the refreshed scaling.db (run parse_assets.py + generate_scaling.py first)
plus the live gameplay configs, builds discovery tables, and writes a markdown
report used as the factual basis for the economy balancing plan.

Replicates (read-only, no game code changes):
  - MerchantPriceRegistry.java   buy/sell price model
  - CombatScaling.java           normalized sigmoid, weaponMult, armorDR
  - LootTable.java / LootRollService.java   drop chance, rolls, gold × npcLevel
  - RpgStatEffects.java          Luck drop bonus + bonus rolls (RpgConfig.json)
  - CatalogGenerator.java        merchant gear level stamping [floor-4, floor+10]

Outputs:
  - scaling.db tables: spawn_roster, summon_edges, loot_tables, loot_entries, item_prices
  - docs/data-balancing/economy-discovery.md

Usage:
    uv run discover_economy.py [--db scaling.db] [--repo-root ../../..]
"""
from __future__ import annotations

import argparse
import json
import math
import sqlite3
import statistics
import sys
from datetime import date
from pathlib import Path


# ── Live formula replication (CombatScaling.java) ─────────────────────

MIN_LEVEL = 1
MAX_LEVEL = 100
MIDPOINT = MAX_LEVEL / 2.0
STEEPNESS = 7.2 / MAX_LEVEL
WEAPON_K = 7.0
ARMOR_K = 4.0
MAX_ARMOR_DR = 0.65


def _raw_sigmoid(level: float) -> float:
    return 1.0 / (1.0 + math.exp(-STEEPNESS * (level - MIDPOINT)))


_S_MIN = _raw_sigmoid(MIN_LEVEL)
_S_MAX = _raw_sigmoid(MAX_LEVEL)


def sigmoid(level: int) -> float:
    clamped = max(MIN_LEVEL, min(level, MAX_LEVEL))
    raw = _raw_sigmoid(clamped)
    return max(0.0, min((raw - _S_MIN) / (_S_MAX - _S_MIN), 1.0))


def linear(level: int) -> float:
    return (max(MIN_LEVEL, min(level, MAX_LEVEL)) - MIN_LEVEL) / (MAX_LEVEL - MIN_LEVEL)


def gear_progress(level: int) -> float:
    # Gear-only front-loaded progression (mirrors CombatScaling.gearProgress); NPC scaling stays sigmoid.
    return 0.5 * linear(level) + 0.5 * sigmoid(level)


def weapon_mult(level: int) -> float:
    return 1.0 + WEAPON_K * gear_progress(level)


def armor_dr(base_resist: float, level: int) -> float:
    resist_mult = max(1.0 + (ARMOR_K - 1.0) * sigmoid(level), 1.0)
    return min(base_resist * resist_mult, MAX_ARMOR_DR)


def clamp_level(level: int) -> int:
    return max(MIN_LEVEL, min(level, MAX_LEVEL))


# ── Price model replication (MerchantPriceRegistry.java) ──────────────

SELL_RATIO = 0.50
MIN_BUY_PRICE = 25
SCORE_TO_GOLD_SCALE = 10.0
SCORE_TO_GOLD_EXPONENT = 1.4
ARMOR_RESIST_SCORE_WEIGHT = 3.0
ARMOR_HEALTH_SCORE_WEIGHT = 0.9
QUALITY_FALLBACK_EXPONENT = 0.5

KNOWN_QUALITY_COEFF = {
    "Common": 1.0,
    "Uncommon": 1.5,
    "Rare": 2.5,
    "Epic": 5.0,
    "Legendary": 15.0,
}


def quality_coefficient(quality: str | None) -> tuple[float, bool]:
    """Return (coefficient, is_known). Unknown qualities silently price at 1.0."""
    if quality is None:
        return 1.0, True
    if quality in KNOWN_QUALITY_COEFF:
        return KNOWN_QUALITY_COEFF[quality], True
    return 1.0, False


def runtime_weapon_family(item_id: str) -> str:
    """Mirror AssetCatalog.inferWeaponFamily (used by the fallback family mult)."""
    parts = item_id.split("_")
    if len(parts) >= 2 and parts[0] == "Weapon":
        return parts[1]
    return parts[0] if parts else ""


def fallback_family_mult(family: str) -> float:
    if family in ("Bow", "Shortbow", "Crossbow", "Handgun", "Rifle"):
        return 1.20
    if family in ("Staff", "Wand", "Spellbook"):
        return 1.15
    if family == "Shield":
        return 1.05
    if family in ("Torch", "Fire"):
        return 0.90
    return 1.0


def armor_slot_mult(slot: str | None) -> float:
    return {"Chest": 1.0, "Legs": 0.85, "Head": 0.75, "Hands": 0.65}.get(slot or "", 1.0)


def score_to_gold(score: float) -> int:
    bounded = max(1.0, score)
    return max(MIN_BUY_PRICE, round(math.pow(bounded, SCORE_TO_GOLD_EXPONENT) * SCORE_TO_GOLD_SCALE))


def fallback_tier_score(item_level: int, quality: str | None) -> float:
    qc, _ = quality_coefficient(quality)
    return max(1, item_level) * math.pow(qc, QUALITY_FALLBACK_EXPONENT)


def weapon_buy_price(item_id: str, base_damage: float, item_level: int,
                     quality: str | None, level: int) -> tuple[int, bool]:
    """Return (buy_price, used_fallback) for a weapon at the given gear level."""
    pricing_level = clamp_level(level if level > 0 else max(item_level, 1))
    if base_damage > 0:
        return score_to_gold(base_damage * weapon_mult(pricing_level)), False
    family = runtime_weapon_family(item_id)
    score = fallback_tier_score(item_level, quality) * fallback_family_mult(family)
    return score_to_gold(score), True


def armor_buy_price(phys: float, proj: float, health: int, slot: str | None,
                    item_level: int, quality: str | None, level: int) -> tuple[int, bool]:
    """Return (buy_price, used_fallback) for an armor piece at the given gear level."""
    pricing_level = clamp_level(level if level > 0 else max(item_level, 1))
    phys_dr = armor_dr(phys, pricing_level) if phys > 0 else 0.0
    proj_dr = armor_dr(proj, pricing_level) if proj > 0 else 0.0
    sources = (1 if phys_dr > 0 else 0) + (1 if proj_dr > 0 else 0)
    avg_resist_pct = ((phys_dr + proj_dr) / sources) * 100.0 if sources else 0.0
    score = avg_resist_pct * ARMOR_RESIST_SCORE_WEIGHT + max(0, health) * ARMOR_HEALTH_SCORE_WEIGHT
    if score > 0:
        return score_to_gold(score), False
    return score_to_gold(fallback_tier_score(item_level, quality) * armor_slot_mult(slot)), True


# ── Luck model replication (RpgStatEffects.java + RpgConfig.json) ─────

class LuckModel:
    def __init__(self, rpg_config: dict):
        self.max_drop_bonus = rpg_config.get("LuckMaxDropBonus", 0.30)
        self.half_point = rpg_config.get("LuckHalfPoint", 20.0)
        self.levels_per_bonus_roll = rpg_config.get("LuckLevelsPerBonusRoll", 15)

    def drop_bonus(self, luck: int) -> float:
        if luck <= 0:
            return 0.0
        return self.max_drop_bonus * (luck / (luck + self.half_point))

    def bonus_rolls(self, luck: int) -> int:
        if luck <= 0:
            return 0
        return luck // self.levels_per_bonus_roll

    def effective_chance(self, base_chance: float, luck: int) -> float:
        if luck <= 0:
            return base_chance
        return min(1.0, base_chance + self.drop_bonus(luck))


# Fixed unit buy prices for authored custom items — mirrors CustomItems.BUY_PRICES.
# These are level-independent and resold at SELL_RATIO.
CUSTOM_BUY_PRICES = {
    "Immunity_Trap_Ring": 35_000,
    "Speed_Boots_I": 30_000,
    "Speed_Boots_II": 45_000,
    "Speed_Boots_III": 70_000,
    "Healing_Necklace_I": 45_000,
    "Healing_Necklace_II": 125_000,
    "Vampire_Juice": 50_000,
    "Stat_Point_Token": 7_500,
    "Palporter": 2_500,
    "Village_Warp": 5_000,
}

# Built-in summoned roles scaled at runtime — mirrors
# BuiltInNpcSpawnScalingSystem.ALLOWLISTED_SPECIAL_ROLES.
RUNTIME_SUMMON_ALLOWLIST = [
    "Skeleton", "Scarak_Louse", "Wolf_Outlander_Sorcerer", "Wolf_Outlander_Priest",
    "Wolf_Trork_Shaman", "Wolf_Trork_Hunter", "Wolf_Wife", "Wolf_Black",
]

PRICE_LEVELS = [1, 5, 10, 15, 20, 25, 30, 40, 50, 60, 75, 90, 100]
LUCK_POINTS = [0, 10, 20, 30, 50]
EV_LEVELS = [5, 15, 30, 50]


# ── Discovery table DDL ───────────────────────────────────────────────

DISCOVERY_DDL = """
DROP TABLE IF EXISTS spawn_roster;
CREATE TABLE spawn_roster (
    role        TEXT NOT NULL,
    theme       TEXT NOT NULL,
    min_floor   INTEGER NOT NULL,
    max_floor   INTEGER NOT NULL,
    weight      REAL NOT NULL,
    variants    TEXT NOT NULL,
    PRIMARY KEY (role, theme)
);

DROP TABLE IF EXISTS summon_edges;
CREATE TABLE summon_edges (
    summoner    TEXT NOT NULL,
    summoned    TEXT NOT NULL,
    via         TEXT NOT NULL,
    PRIMARY KEY (summoner, summoned)
);

DROP TABLE IF EXISTS loot_tables;
CREATE TABLE loot_tables (
    table_id    TEXT PRIMARY KEY,
    rolls       INTEGER NOT NULL,
    drop_chance REAL NOT NULL,
    entry_count INTEGER NOT NULL,
    gold_weight REAL NOT NULL,
    total_weight REAL NOT NULL
);

DROP TABLE IF EXISTS loot_entries;
CREATE TABLE loot_entries (
    table_id    TEXT NOT NULL,
    idx         INTEGER NOT NULL,
    type        TEXT NOT NULL,
    item_id     TEXT NOT NULL,
    weight      REAL NOT NULL,
    gear_type   TEXT,
    gear_min    INTEGER,
    gear_max    INTEGER,
    min_floor   INTEGER,
    max_floor   INTEGER,
    min_npc_level INTEGER,
    qty_min     INTEGER,
    qty_max     INTEGER,
    PRIMARY KEY (table_id, idx)
);

DROP TABLE IF EXISTS item_prices;
CREATE TABLE item_prices (
    item_id     TEXT NOT NULL,
    category    TEXT NOT NULL,
    source      TEXT NOT NULL,
    quality     TEXT,
    quality_known INTEGER NOT NULL,
    item_level  INTEGER NOT NULL,
    damage_method TEXT,
    level       INTEGER NOT NULL,
    buy_stats   INTEGER,
    buy_fallback INTEGER NOT NULL,
    runtime_path TEXT NOT NULL,
    PRIMARY KEY (item_id, level)
);
"""


def load_json(path: Path) -> dict:
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


# ── Builders ──────────────────────────────────────────────────────────

def build_spawn_roster(conn: sqlite3.Connection, themes_dir: Path) -> dict[str, list[dict]]:
    """Populate spawn_roster from dungeon-gen theme SpawnPools. Returns role→pools."""
    roster: dict[str, list[dict]] = {}
    for theme_path in sorted(themes_dir.glob("*.json")):
        theme = theme_path.stem
        data = load_json(theme_path)
        for pool in data.get("SpawnPool", []):
            role = pool.get("NpcRole")
            if not role:
                continue
            row = {
                "role": role,
                "theme": theme,
                "min_floor": int(pool.get("MinFloor", 1)),
                "max_floor": int(pool.get("MaxFloor", MAX_LEVEL)),
                "weight": float(pool.get("Weight", 1.0)),
                "variants": ",".join(pool.get("Variants", ["NORMAL"])),
            }
            roster.setdefault(role, []).append(row)
            conn.execute(
                """INSERT OR REPLACE INTO spawn_roster
                   (role, theme, min_floor, max_floor, weight, variants)
                   VALUES (:role, :theme, :min_floor, :max_floor, :weight, :variants)""",
                row,
            )
    conn.commit()
    return roster


def build_summon_edges(conn: sqlite3.Connection, roles_dirs: list[Path]) -> dict[str, tuple[str, str]]:
    """Populate summon_edges by scanning role JSONs for SummonKind declarations.

    Catches three authoring styles:
      - direct `SummonKind: "Role"` (top level or inside Modify)
      - template `Parameters.SummonKind.Value` defaults
      - variants that inherit a template default via their Reference
    """
    direct: dict[str, str] = {}
    template_default: dict[str, str] = {}
    references: dict[str, str] = {}

    for roles_dir in roles_dirs:
        if not roles_dir.exists():
            continue
        for path in roles_dir.rglob("*.json"):
            try:
                data = load_json(path)
            except (json.JSONDecodeError, OSError):
                continue
            stem = path.stem
            ref = data.get("Reference")
            if isinstance(ref, str):
                references[stem] = ref

            summon = data.get("SummonKind")
            if not isinstance(summon, str):
                modify = data.get("Modify", {})
                summon = modify.get("SummonKind") if isinstance(modify, dict) else None
            if isinstance(summon, str):
                direct[stem] = summon

            params = data.get("Parameters", {})
            param = params.get("SummonKind") if isinstance(params, dict) else None
            if isinstance(param, dict) and isinstance(param.get("Value"), str):
                template_default[stem] = param["Value"]
                if isinstance(summon, str) is False and stem not in direct and not stem.startswith("Template_"):
                    direct[stem] = param["Value"]

    edges: dict[str, tuple[str, str]] = {}
    for stem, kind in direct.items():
        edges[stem] = (kind, "SummonKind")
    # Variants inherit the template's default summon unless they override it
    for stem, ref in references.items():
        if stem not in edges and ref in template_default:
            edges[stem] = (template_default[ref], f"inherited from {ref}")

    for summoner, (summoned, via) in edges.items():
        conn.execute(
            "INSERT OR REPLACE INTO summon_edges (summoner, summoned, via) VALUES (?, ?, ?)",
            (summoner, summoned, via),
        )
    conn.commit()
    return edges


def build_loot_tables(conn: sqlite3.Connection, loot_dir: Path) -> dict[str, dict]:
    """Populate loot_tables/loot_entries from the LootTables asset JSONs."""
    tables: dict[str, dict] = {}
    for table_path in sorted(loot_dir.glob("*.json")):
        table_id = table_path.stem
        data = load_json(table_path)
        entries = data.get("Entries", [])
        gold_weight = sum(
            float(e.get("Weight", 1.0)) for e in entries if e.get("ItemId") == "Gold_Coin"
        )
        total_weight = sum(float(e.get("Weight", 1.0)) for e in entries)
        tables[table_id] = data
        conn.execute(
            """INSERT OR REPLACE INTO loot_tables
               (table_id, rolls, drop_chance, entry_count, gold_weight, total_weight)
               VALUES (?, ?, ?, ?, ?, ?)""",
            (table_id, int(data.get("Rolls", 1)), float(data.get("DropChance", 1.0)),
             len(entries), gold_weight, total_weight),
        )
        for idx, e in enumerate(entries):
            conn.execute(
                """INSERT OR REPLACE INTO loot_entries
                   (table_id, idx, type, item_id, weight, gear_type, gear_min, gear_max,
                    min_floor, max_floor, min_npc_level, qty_min, qty_max)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                (table_id, idx, e.get("Type", "SIMPLE"), e.get("ItemId", ""),
                 float(e.get("Weight", 1.0)), e.get("GearType"),
                 e.get("GearLevelMin"), e.get("GearLevelMax"),
                 e.get("MinFloorLevel"), e.get("MaxFloorLevel"), e.get("MinNpcLevel"),
                 e.get("QuantityMin"), e.get("QuantityMax")),
            )
    conn.commit()
    return tables


def build_item_prices(conn: sqlite3.Connection) -> None:
    """Populate item_prices for all weapons/armor at the audit levels."""
    for (item_id, source, family, quality, item_level, base_damage, damage_method) in conn.execute(
        "SELECT weapon_id, source, family, quality, item_level, base_damage, damage_method FROM weapons_base"
    ).fetchall():
        if quality in ("Developer", "Technical"):
            continue
        _, quality_known = quality_coefficient(quality)
        runtime_path = "stats" if damage_method == "inline" else (
            "known-override" if item_id == "WanMine_Void_Requiem_Scythe" else "fallback?"
        )
        for level in PRICE_LEVELS:
            buy_stats = None
            if base_damage > 0:
                buy_stats, _ = weapon_buy_price(item_id, base_damage, item_level, quality, level)
            buy_fallback, _ = weapon_buy_price(item_id, 0.0, item_level, quality, level)
            conn.execute(
                """INSERT OR REPLACE INTO item_prices
                   (item_id, category, source, quality, quality_known, item_level,
                    damage_method, level, buy_stats, buy_fallback, runtime_path)
                   VALUES (?, 'weapon', ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                (item_id, source, quality, int(quality_known), item_level,
                 damage_method, level, buy_stats, buy_fallback, runtime_path),
            )

    for (item_id, source, slot, quality, item_level, phys, proj, health) in conn.execute(
        "SELECT armor_id, source, slot, quality, item_level, phys_resist, proj_resist, health_bonus FROM armor_base"
    ).fetchall():
        if quality in ("Developer", "Technical"):
            continue
        _, quality_known = quality_coefficient(quality)
        has_stats = phys > 0 or proj > 0 or health > 0
        for level in PRICE_LEVELS:
            buy_stats = None
            if has_stats:
                buy_stats, _ = armor_buy_price(phys, proj, health, slot, item_level, quality, level)
            buy_fallback, _ = armor_buy_price(0, 0, 0, slot, item_level, quality, level)
            conn.execute(
                """INSERT OR REPLACE INTO item_prices
                   (item_id, category, source, quality, quality_known, item_level,
                    damage_method, level, buy_stats, buy_fallback, runtime_path)
                   VALUES (?, 'armor', ?, ?, ?, ?, NULL, ?, ?, ?, ?)""",
                (item_id, source, quality, int(quality_known), item_level,
                 level, buy_stats, buy_fallback, "stats" if has_stats else "fallback?"),
            )
    conn.commit()


# ── Loot expected value ───────────────────────────────────────────────

def table_ev(table: dict, level: int, luck: LuckModel, luck_level: int) -> dict:
    """Expected gold + item drops per kill for one loot table at one NPC level."""
    entries = table.get("Entries", [])
    eligible = []
    for e in entries:
        if e.get("MinFloorLevel") is not None and level < e["MinFloorLevel"]:
            continue
        if e.get("MaxFloorLevel") is not None and level > e["MaxFloorLevel"]:
            continue
        if e.get("MinNpcLevel") is not None and level < e["MinNpcLevel"]:
            continue
        eligible.append(e)

    total_w = sum(float(e.get("Weight", 1.0)) for e in eligible)
    if total_w <= 0:
        return {"gold": 0.0, "items": 0.0, "chance": 0.0, "rolls": 0}

    chance = luck.effective_chance(float(table.get("DropChance", 1.0)), luck_level)
    rolls = int(table.get("Rolls", 1)) + luck.bonus_rolls(luck_level)

    gold_per_roll = 0.0
    item_share = 0.0
    for e in eligible:
        share = float(e.get("Weight", 1.0)) / total_w
        if e.get("ItemId") == "Gold_Coin":
            avg_qty = (e.get("QuantityMin", 1) + e.get("QuantityMax", 1)) / 2.0
            # LootRollService.scaleGold: gold quantity × npcLevel (when level > 1)
            gold_per_roll += share * avg_qty * (level if level > 1 else 1)
        else:
            item_share += share

    return {
        "gold": chance * rolls * gold_per_roll,
        "items": chance * rolls * item_share,
        "chance": chance,
        "rolls": rolls,
    }


# ── Report helpers ────────────────────────────────────────────────────

def percentile(values: list[float], pct: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    k = (len(ordered) - 1) * pct
    lower = math.floor(k)
    upper = math.ceil(k)
    if lower == upper:
        return ordered[int(k)]
    return ordered[lower] * (upper - k) + ordered[upper] * (k - lower)


def md_table(headers: list[str], rows: list[list]) -> str:
    out = ["| " + " | ".join(headers) + " |",
           "|" + "|".join("---" for _ in headers) + "|"]
    for row in rows:
        out.append("| " + " | ".join(str(c) for c in row) + " |")
    return "\n".join(out)


# ── Main ──────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="Economy discovery analysis")
    parser.add_argument("--db", default=str(Path(__file__).resolve().parent / "scaling.db"))
    parser.add_argument(
        "--repo-root",
        default=str(Path(__file__).resolve().parent.parent.parent.parent),
        help="Path to the duntale repo root",
    )
    args = parser.parse_args()

    repo = Path(args.repo_root)
    v3 = repo / "v3-zsquad" / "src" / "main" / "resources" / "Server" / "Configs"
    themes_dir = repo / "dungeon-gen" / "src" / "main" / "resources" / "Server" / "Configs" / "DungeonGen" / "Themes"
    loot_dir = v3 / "LootTables"
    rpg_config_path = v3 / "Rpg" / "RpgConfig.json"
    floor_dir = v3 / "FloorConfig"
    report_path = repo / "v3-zsquad" / "docs" / "data-balancing" / "economy-discovery.md"

    for required in (themes_dir, loot_dir, rpg_config_path, floor_dir):
        if not required.exists():
            print(f"ERROR: missing {required}", file=sys.stderr)
            sys.exit(1)

    conn = sqlite3.connect(args.db)
    conn.executescript(DISCOVERY_DDL)

    luck = LuckModel(load_json(rpg_config_path))

    roster = build_spawn_roster(conn, themes_dir)
    roles_dirs = [
        repo / "HytaleAssets" / "Server" / "NPC" / "Roles",
        repo / "v3-zsquad" / "src" / "main" / "resources" / "Server" / "NPC" / "Roles",
    ]
    summons = build_summon_edges(conn, roles_dirs)
    tables = build_loot_tables(conn, loot_dir)
    build_item_prices(conn)

    table_ids = set(tables)
    roster_roles = set(roster)
    summoned_roles = set(RUNTIME_SUMMON_ALLOWLIST)
    for summoned, _via in summons.values():
        summoned_roles.add(summoned)

    # ── Coverage analysis ────────────────────────────────────────────
    missing_roster = sorted(roster_roles - table_ids)
    missing_summons = sorted(summoned_roles - table_ids)
    base_ids = {t for t in table_ids if not (t.endswith("_Elite") or t.endswith("_Boss") or t.startswith("Chest_"))}
    overlay_ids = table_ids - base_ids - {t for t in table_ids if t.startswith("Chest_")}
    orphan_base = sorted(
        t for t in base_ids
        if t not in roster_roles and t not in summoned_roles
    )

    # Variant overlay coverage: roster entries that can spawn ELITE/BOSS
    needs_elite = sorted({r for r, pools in roster.items()
                          if any("ELITE" in p["variants"] for p in pools)})
    needs_boss = sorted({r for r, pools in roster.items()
                         if any("BOSS" in p["variants"] for p in pools)})
    missing_elite = [r for r in needs_elite if f"{r}_Elite" not in table_ids and r in table_ids]
    missing_boss = [r for r in needs_boss if f"{r}_Boss" not in table_ids and r in table_ids]

    # ── Price audit ──────────────────────────────────────────────────
    def runtime_buy(row) -> int:
        # Best estimate of what runtime charges: stats price when stats are
        # runtime-visible, fallback price otherwise.
        item_id, buy_stats, buy_fallback, runtime_path = row
        if runtime_path == "stats" and buy_stats is not None:
            return buy_stats
        if runtime_path == "known-override":
            kb, _ = weapon_buy_price(item_id, 81.5, 0, None, 0)
            return kb
        return buy_fallback

    price_summary: dict[int, dict[str, list[int]]] = {}
    for level in PRICE_LEVELS:
        price_summary[level] = {"weapon": [], "armor": []}
        for row in conn.execute(
            "SELECT item_id, buy_stats, buy_fallback, runtime_path FROM item_prices "
            "WHERE level = ? AND category = 'weapon'", (level,)
        ):
            price_summary[level]["weapon"].append(runtime_buy(row))
        for row in conn.execute(
            "SELECT item_id, buy_stats, buy_fallback, runtime_path FROM item_prices "
            "WHERE level = ? AND category = 'armor'", (level,)
        ):
            price_summary[level]["armor"].append(runtime_buy(row))

    top_weapons = conn.execute(
        "SELECT item_id, source, quality, item_level, buy_stats FROM item_prices "
        "WHERE level = 75 AND category = 'weapon' AND buy_stats IS NOT NULL "
        "ORDER BY buy_stats DESC LIMIT 10"
    ).fetchall()
    top_armor = conn.execute(
        "SELECT item_id, source, quality, item_level, buy_stats FROM item_prices "
        "WHERE level = 75 AND category = 'armor' AND buy_stats IS NOT NULL "
        "ORDER BY buy_stats DESC LIMIT 10"
    ).fetchall()

    fallback_items = conn.execute(
        "SELECT DISTINCT item_id, category, source, quality, item_level, buy_fallback FROM item_prices "
        "WHERE runtime_path != 'stats' AND level = 50 ORDER BY category, source, item_id"
    ).fetchall()
    unknown_quality = conn.execute(
        "SELECT DISTINCT item_id, category, source, quality, item_level FROM item_prices "
        "WHERE quality_known = 0 ORDER BY source, item_id"
    ).fetchall()

    # ── Loot EV analysis ─────────────────────────────────────────────
    base_chances = [float(t.get("DropChance", 1.0)) for tid, t in tables.items()
                    if not tid.startswith("Chest_")]

    luck_rows = []
    for lvl in LUCK_POINTS:
        bonus = luck.drop_bonus(lvl)
        luck_rows.append([
            lvl, f"+{bonus*100:.1f}pp", luck.bonus_rolls(lvl),
            f"{min(1.0, 0.32 + bonus)*100:.0f}%",
            f"{min(1.0, statistics.median(base_chances) + bonus)*100:.0f}%",
        ])

    ev_rows = []
    for table_id in sorted(tables):
        if table_id.startswith("Chest_"):
            continue
        t = tables[table_id]
        for level in EV_LEVELS:
            ev0 = table_ev(t, level, luck, 0)
            ev20 = table_ev(t, level, luck, 20)
            ev50 = table_ev(t, level, luck, 50)
            ev_rows.append([
                table_id, level,
                f"{ev0['chance']*100:.0f}%",
                f"{ev0['gold']:.1f}", f"{ev0['items']:.2f}",
                f"{ev20['gold']:.1f}", f"{ev20['items']:.2f}",
                f"{ev50['gold']:.1f}", f"{ev50['items']:.2f}",
            ])

    # ── Income vs price model per floor config ───────────────────────
    income_rows = []
    for floor_path in sorted(floor_dir.glob("*.json")):
        floor = int(floor_path.stem)
        overrides = load_json(floor_path).get("Overrides", {})
        density = overrides.get("layout.enemyDensity", 0.4)
        max_rooms = overrides.get("layout.maxRooms", 20)
        max_per_room = overrides.get("layout.maxEnemiesPerRoom", 5)
        themes = ",".join(overrides.get("theme.variants", []))

        # Crude initial-population estimate: ~60% of rooms are combat rooms,
        # each holding density × maxEnemiesPerRoom enemies (≥1). Respawning
        # spawners and prop spawns (Scarak eggs) add more over a run.
        est_kills = max_rooms * 0.6 * max(1.0, density * max_per_room)

        # Average gold/kill across roster roles active at this floor that HAVE a table
        gold_per_kill = []
        for role, pools in roster.items():
            if role not in tables:
                continue
            for p in pools:
                if p["min_floor"] <= floor <= p["max_floor"]:
                    gold_per_kill.append(table_ev(tables[role], floor, luck, 0)["gold"])
                    break
        avg_gold = statistics.mean(gold_per_kill) if gold_per_kill else 0.0
        gold_per_floor = est_kills * avg_gold

        # Median merchant prices at the stamped gear level (mid of [floor-4, floor+10])
        gear_level = clamp_level(floor + 3)
        median_prices = {}
        for category in ("weapon", "armor"):
            buys = [runtime_buy(r) for r in conn.execute(
                "SELECT item_id, buy_stats, buy_fallback, runtime_path FROM item_prices "
                "WHERE level = (SELECT MIN(level) FROM item_prices WHERE level >= ?) "
                "AND category = ? AND item_level BETWEEN ? AND ?",
                (gear_level, category, max(0, floor - 15), floor + 15),
            )]
            median_prices[category] = statistics.median(buys) if buys else 0
        wprice = median_prices["weapon"]
        aprice = median_prices["armor"]
        income_rows.append([
            floor, themes, f"{est_kills:.0f}", f"{avg_gold:.1f}",
            f"{gold_per_floor:.0f}", f"{wprice:.0f}", f"{aprice:.0f}",
            f"{(wprice / gold_per_floor):.1f}" if gold_per_floor else "inf",
        ])

    # ── Per-theme income projection ──────────────────────────────────
    # Theme availability per floor comes from FloorConfig theme.variants
    # (lowercase) mapped onto the Theme asset stems.
    theme_stems = {p.stem.lower(): p.stem for p in themes_dir.glob("*.json")}
    theme_names = sorted(theme_stems.values())

    # Hive Deco_Scarak_Eggsacks props: SpawnChance 0.3 × MaxPerRoom 3 in COMBAT
    # rooms; each stepped-on egg spawns one Scarak_Louse at floor level.
    EGGS_PER_COMBAT_ROOM = 0.3 * 3

    theme_income_rows = []
    theme_pressure_rows = []
    theme_coverage: dict[str, tuple[float, list[str]]] = {}
    for floor_path in sorted(floor_dir.glob("*.json")):
        floor = int(floor_path.stem)
        overrides = load_json(floor_path).get("Overrides", {})
        density = overrides.get("layout.enemyDensity", 0.4)
        max_rooms = overrides.get("layout.maxRooms", 20)
        max_per_room = overrides.get("layout.maxEnemiesPerRoom", 5)
        variants = [theme_stems.get(v.lower()) for v in overrides.get("theme.variants", [])]
        combat_rooms = max_rooms * 0.6
        est_kills = combat_rooms * max(1.0, density * max_per_room)

        gold_cells = []
        item_cells = []
        for theme in theme_names:
            if theme not in variants:
                gold_cells.append("—")
                item_cells.append("—")
                continue
            pool = [(r, p) for r, pools in roster.items() for p in pools
                    if p["theme"] == theme and p["min_floor"] <= floor <= p["max_floor"]]
            total_w = sum(p["weight"] for _, p in pool)
            if total_w <= 0:
                gold_cells.append("0")
                item_cells.append("0")
                continue
            gold_pk = 0.0
            items_pk = 0.0
            covered_w = 0.0
            for role, p in pool:
                if role in tables:
                    ev = table_ev(tables[role], floor, luck, 0)
                    gold_pk += (p["weight"] / total_w) * ev["gold"]
                    items_pk += (p["weight"] / total_w) * ev["items"]
                    covered_w += p["weight"]
            kills = est_kills
            gold_floor = kills * gold_pk
            items_floor = kills * items_pk
            if theme == "Hive" and "Scarak_Louse" in tables:
                louse_ev = table_ev(tables["Scarak_Louse"], floor, luck, 0)
                lice = combat_rooms * EGGS_PER_COMBAT_ROOM
                gold_floor += lice * louse_ev["gold"]
                items_floor += lice * louse_ev["items"]
            coverage_pct = covered_w / total_w * 100
            missing_here = sorted({r for r, _ in pool if r not in tables})
            prev_pct, prev_missing = theme_coverage.get(theme, (100.0, []))
            if coverage_pct < prev_pct or not prev_missing:
                theme_coverage[theme] = (coverage_pct, missing_here)
            gold_cells.append(f"{gold_floor:,.0f}")
            item_cells.append(f"{items_floor:.1f}")
        theme_income_rows.append([floor, f"{est_kills:.0f}"] + gold_cells)
        theme_pressure_rows.append([floor, f"{est_kills:.0f}"] + item_cells)

    # ── Write report ─────────────────────────────────────────────────
    lines: list[str] = []
    add = lines.append
    add("# Economy Discovery Report")
    add("")
    add(f"Status: Discovery snapshot")
    add(f"Generated: {date.today().isoformat()} by `scripts/scaling/discover_economy.py`")
    add("Inputs: refreshed `scaling.db` (multi-source: builtin, duntale, wans, zets), "
        "dungeon-gen theme SpawnPools, LootTables assets, RpgConfig.json, FloorConfig assets.")
    add("Formula replication sources: `CombatScaling.java`, `MerchantPriceRegistry.java`, "
        "`LootTable.java`, `LootRollService.java`, `RpgStatEffects.java`, `CatalogGenerator.java`.")
    add("")

    add("## 1. Item Catalog Inventory")
    add("")
    rows = conn.execute(
        "SELECT category, source, COUNT(DISTINCT item_id) FROM item_prices GROUP BY category, source"
    ).fetchall()
    add(md_table(["Category", "Source", "Items"], [list(r) for r in rows]))
    add("")
    method_rows = conn.execute(
        "SELECT damage_method, COUNT(*) FROM weapons_base GROUP BY damage_method"
    ).fetchall()
    add("Weapon damage resolution (offline parser): " +
        ", ".join(f"{m}={c}" for m, c in method_rows) +
        ". `inline` is runtime-visible; `chain`/`none` likely fall back to tier/quality "
        "pricing at runtime (needs a one-off runtime spot check).")
    add("")

    add("### Items with unknown pricing quality (priced as Common, coeff 1.0)")
    add("")
    add(md_table(["Item", "Category", "Source", "Quality", "ItemLevel"],
                 [list(r) for r in unknown_quality]))
    add("")
    add("### Items likely priced via fallback at runtime (no runtime-visible stats)")
    add("")
    add(md_table(["Item", "Category", "Source", "Quality", "ItemLevel", "Fallback buy@50"],
                 [list(r) for r in fallback_items]))
    add("")

    add("## 2. Price Audit (runtime price model replica)")
    add("")
    rows = []
    for level in [10, 25, 50, 75, 100]:
        w = price_summary[level]["weapon"]
        a = price_summary[level]["armor"]
        rows.append([
            level,
            f"{percentile(w, 0.5):.0f}", f"{percentile(w, 0.9):.0f}", f"{max(w):.0f}",
            f"{percentile(a, 0.5):.0f}", f"{percentile(a, 0.9):.0f}", f"{max(a):.0f}",
            f"{max(w)/max(a):.1f}x",
        ])
    add(md_table(
        ["Gear level", "Weapon p50", "Weapon p90", "Weapon max",
         "Armor p50", "Armor p90", "Armor max", "Max ratio W/A"], rows))
    add("")
    add("Top stat-priced weapons at gear level 75:")
    add("")
    add(md_table(["Weapon", "Source", "Quality", "ItemLevel", "Buy"], [list(r) for r in top_weapons]))
    add("")
    add("Top stat-priced armor at gear level 75:")
    add("")
    add(md_table(["Armor", "Source", "Quality", "ItemLevel", "Buy"], [list(r) for r in top_armor]))
    add("")
    add("Structural cause of the weapon/armor asymmetry: weapon score = baseDamage × "
        "weaponMult(level) is unbounded (×7 at L100), armor score = avgDR% × 3 + health × 0.9 "
        "saturates at the 65% DR cap (`CombatScaling.MAX_ARMOR_DR`), so armor prices plateau "
        "while weapon prices keep climbing through the ^1.4 gold curve.")
    add("")
    add("### Fixed-price custom items (CustomItems.BUY_PRICES, level-independent)")
    add("")
    add(md_table(
        ["Item", "Buy", "Sell (80%)"],
        [[item, f"{price:,}", f"{math.floor(price * SELL_RATIO):,}"]
         for item, price in sorted(CUSTOM_BUY_PRICES.items(), key=lambda kv: -kv[1])]))
    add("")
    add("Merchants also reserve catalog slots for enchant scrolls "
        "(`CatalogGenerator.RESERVED_SCROLL_ITEM_IDS`, SimpleEnchantments) — an additional "
        "gold sink that is out of scope for this pass per the rebalance brief.")
    add("")

    add("## 3. NPC Roster & Loot Coverage")
    add("")
    add(f"- SpawnPool roles across {len(list(themes_dir.glob('*.json')))} themes: **{len(roster_roles)}**")
    add(f"- Loot tables shipped: **{len(table_ids)}** ({len(base_ids)} base, "
        f"{len(overlay_ids)} variant overlays, {len([t for t in table_ids if t.startswith('Chest_')])} chest)")
    add(f"- Roster roles with NO loot table: **{len(missing_roster)}** — scaled NPCs drop "
        "NOTHING because `NpcLootSystem` suppresses engine drops for all scaled NPCs")
    add(f"- Summoned roles with NO loot table: **{len(missing_summons)}**")
    add("")
    add("### Roster roles missing a loot table")
    add("")
    rows = []
    for role in missing_roster:
        pools = roster[role]
        themes = ", ".join(f"{p['theme']} F{p['min_floor']}-{p['max_floor']}" for p in pools)
        hp = conn.execute("SELECT base_hp FROM monsters_base WHERE npc_id = ?", (role,)).fetchone()
        rows.append([role, themes, hp[0] if hp else "?"])
    add(md_table(["Role", "Themes (floor range)", "Base HP"], rows))
    add("")
    add("### Summon-based roles missing a loot table")
    add("")
    add(md_table(["Role"], [[r] for r in missing_summons]))
    add("")
    add("### Summon edges discovered in role data")
    add("")
    add(md_table(
        ["Summoner", "Summoned", "Via", "Summoner in roster?"],
        [[s, kind, via, "yes" if s in roster_roles else "no"]
         for s, (kind, via) in sorted(summons.items())]))
    add("")
    add(f"Runtime summon allowlist (BuiltInNpcSpawnScalingSystem): {', '.join(RUNTIME_SUMMON_ALLOWLIST)}. "
        "`Skeleton`, `Scarak_Louse`, `Wolf_Wife`, and `Wolf_Black` have no SummonKind in role data — "
        "they spawn via other paths (spell interactions, Hive `Deco_Scarak_Eggsacks` props at "
        "SpawnChance 0.3 / MaxPerRoom 3 in COMBAT rooms, etc.).")
    add("")
    add("### Orphan base tables (no SpawnPool role, not a known summon)")
    add("")
    add(md_table(["Table"], [[t] for t in orphan_base]))
    add("")
    add(f"### Variant overlay gaps")
    add("")
    add(f"- Roster roles spawning ELITE without `_Elite` overlay (falls back to base table): "
        f"{', '.join(missing_elite) or '—'}")
    add(f"- Roster roles spawning BOSS without `_Boss` overlay: {', '.join(missing_boss) or '—'}")
    add("")

    add("## 4. Drop Economics")
    add("")
    add(f"- Base `DropChance` across {len(base_chances)} NPC tables: "
        f"min {min(base_chances):.2f}, median {statistics.median(base_chances):.2f}, "
        f"max {max(base_chances):.2f}")
    add(f"- Luck config (RpgConfig.json): LuckMaxDropBonus={luck.max_drop_bonus}, "
        f"LuckHalfPoint={luck.half_point}, LuckLevelsPerBonusRoll={luck.levels_per_bonus_roll}")
    add("")
    add("### Current Luck curve vs target")
    add("")
    add(md_table(
        ["Luck", "Drop bonus", "Bonus rolls", "Eff. chance (base 0.32)", "Eff. chance (median base)"],
        luck_rows))
    add("")
    add("Target curve from rebalance brief: ~10% base, ~40% at Luck 30, ~80% at Luck 50. "
        "That curve is ACCELERATING; the current bonus is a saturating hyperbolic "
        "(`RpgStatEffects.hyperbolic`), which mathematically cannot produce it — "
        "the balancing phase needs a formula change, not just config tuning.")
    add("")
    add("### Expected value per kill (gold EV includes ×npcLevel gold scaling)")
    add("")
    add(md_table(
        ["Table", "Lvl", "Chance L0", "Gold L0", "Items L0",
         "Gold L20", "Items L20", "Gold L50", "Items L50"],
        ev_rows))
    add("")

    add("## 5. Income vs Prices Model (crude)")
    add("")
    add("Estimated kills/floor = maxRooms × 0.6 × max(1, enemyDensity × maxEnemiesPerRoom). "
        "Respawning spawners, ambushes, and prop spawns (Scarak eggs) are NOT counted, so "
        "real income per floor is higher; treat ratios as upper bounds on grind.")
    add("")
    add(md_table(
        ["Floor", "Themes", "Est. kills", "Gold/kill (Luck 0)", "Gold/floor",
         "Median weapon @F+3", "Median armor @F+3", "Floors per weapon"],
        income_rows))
    add("")

    add("## 5b. Per-Theme Income Projection (Luck 0, current loot tables)")
    add("")
    add("Gold per floor by theme. `—` = theme not in that floor's `theme.variants`. "
        "Hive includes Scarak_Louse spawns from egg props "
        f"(~{0.3*3:.1f} eggs per combat room). Roles with no loot table contribute ZERO "
        "gold, which drags themes with poor coverage down — see coverage below.")
    add("")
    add(md_table(["Floor", "Est. kills"] + theme_names, theme_income_rows))
    add("")
    add("Item drops per floor by theme (inventory pressure at Luck 0):")
    add("")
    add(md_table(["Floor", "Est. kills"] + theme_names, theme_pressure_rows))
    add("")
    add("Loot-table coverage by theme (worst floor; % of spawn weight that has a table):")
    add("")
    add(md_table(
        ["Theme", "Coverage", "Roles contributing zero"],
        [[t, f"{pct:.0f}%", ", ".join(missing) or "—"]
         for t, (pct, missing) in sorted(theme_coverage.items())]))
    add("")

    add("## 6. Caveats & Runtime Follow-ups")
    add("")
    add("- `chain`/`none` weapon pricing path is an offline inference; verify in-game which "
        "of those items the merchant prices via stats vs fallback (e.g. `/merchant` + tooltip).")
    add("- Offline chain damage for `WanMine_Void_Requiem_Scythe` resolves to ~69.8; runtime "
        "uses the hand-authored KNOWN_BASE_DAMAGE=81.5 override.")
    add("- Zets adds custom qualities (Mythical/Abyssal/Celestial) and Wans uses Relic — "
        "NONE are known to `MerchantPriceRegistry.qualityCoefficient`, so their fallback "
        "prices collapse to Common-tier coefficients.")
    add("- Kills/floor is a static estimate; spawner respawns and Hive egg props inflate it.")
    add("- Chest tables (Chest_Regular/Epic/Golden/Legendary) and merchant consumables are "
        "recorded in the DB but not modeled in the income table above.")
    add("")

    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text("\n".join(lines), encoding="utf-8")
    conn.close()

    print(f"Report written: {report_path}")
    print(f"Roster roles: {len(roster_roles)}, loot tables: {len(table_ids)}, "
          f"missing roster tables: {len(missing_roster)}, missing summon tables: {len(missing_summons)}")


if __name__ == "__main__":
    main()
