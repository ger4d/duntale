#!/usr/bin/env python3
"""
parse_assets.py — Scan HytaleAssets to populate *_base tables in scaling.db.

Resolves full NPC inheritance chains (Reference → Template → Component)
to extract MaxHealth, MaxSpeed, damage, attack distance, etc.
Also parses weapon and armor definitions.

Usage:
    uv run parse_assets.py [--assets-root ../../HytaleAssets] [--db scaling.db]
"""
from __future__ import annotations

import argparse
import json
import math
import os
import re
import sqlite3
import sys
from pathlib import Path
from typing import Any


# ── Schema DDL ────────────────────────────────────────────────────────

SCHEMA_DDL = """
CREATE TABLE IF NOT EXISTS monsters_base (
    npc_id          TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    category        TEXT NOT NULL,
    tier            TEXT NOT NULL,
    base_hp         INTEGER NOT NULL,
    base_damage     REAL NOT NULL,
    base_speed      REAL,
    attack_distance REAL,
    ai_template     TEXT,
    view_range      REAL,
    hearing_range   REAL,
    extra_json      TEXT
);

CREATE TABLE IF NOT EXISTS weapons_base (
    weapon_id       TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    family          TEXT NOT NULL,
    quality         TEXT NOT NULL,
    item_level      INTEGER NOT NULL,
    base_damage     REAL NOT NULL,
    attack_moves_json TEXT
);

CREATE TABLE IF NOT EXISTS armor_base (
    armor_id        TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    slot            TEXT NOT NULL,
    quality         TEXT NOT NULL,
    item_level      INTEGER NOT NULL,
    phys_resist     REAL NOT NULL,
    proj_resist     REAL NOT NULL,
    health_bonus    INTEGER NOT NULL DEFAULT 0,
    special         TEXT
);

CREATE TABLE IF NOT EXISTS scaling_config (
    key             TEXT PRIMARY KEY,
    value           TEXT NOT NULL
);
"""


def init_db(db_path: str) -> sqlite3.Connection:
    """Create/open the database and ensure schema exists."""
    conn = sqlite3.connect(db_path)
    conn.executescript(SCHEMA_DDL)
    conn.commit()
    return conn


# ── JSON Helpers ──────────────────────────────────────────────────────

def load_json(path: Path) -> dict | None:
    """Load a JSON file, returning None on error."""
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except (json.JSONDecodeError, OSError) as e:
        print(f"  WARN: Could not load {path}: {e}", file=sys.stderr)
        return None


def deep_merge(base: dict, overlay: dict) -> dict:
    """Recursively merge overlay into base. Overlay values take precedence."""
    result = dict(base)
    for key, value in overlay.items():
        if key in result and isinstance(result[key], dict) and isinstance(value, dict):
            result[key] = deep_merge(result[key], value)
        else:
            result[key] = value
    return result


# ── NPC Parsing ───────────────────────────────────────────────────────

def discover_npc_files(roles_root: Path) -> dict[str, Path]:
    """Build name→path map for all NPC role JSON files."""
    name_to_path: dict[str, Path] = {}
    for json_path in roles_root.rglob("*.json"):
        name = json_path.stem  # filename without .json
        name_to_path[name] = json_path
    return name_to_path


def resolve_compute(data: dict, params: dict) -> Any:
    """If data is { "Compute": "X" }, resolve from params. Otherwise return data."""
    if isinstance(data, dict) and "Compute" in data:
        param_name = data["Compute"]
        if param_name in params:
            param_entry = params[param_name]
            if isinstance(param_entry, dict) and "Value" in param_entry:
                return param_entry["Value"]
        return None
    return data


def resolve_npc(
    name: str,
    name_to_path: dict[str, Path],
    cache: dict[str, dict],
    depth: int = 0,
) -> dict | None:
    """Recursively resolve an NPC's full definition through inheritance."""
    if depth > 20:
        print(f"  WARN: Max depth reached resolving {name}", file=sys.stderr)
        return None

    if name in cache:
        return cache[name]

    path = name_to_path.get(name)
    if path is None:
        return None

    data = load_json(path)
    if data is None:
        return None

    npc_type = data.get("Type", "")

    if npc_type == "Generic":
        # Self-contained — resolve parameters in place
        params = data.get("Parameters", {})
        resolved = dict(data)
        for key in list(resolved.keys()):
            resolved[key] = resolve_compute(resolved[key], params)
        cache[name] = resolved
        return resolved

    if npc_type == "Variant":
        ref_name = data.get("Reference")
        if not ref_name:
            cache[name] = data
            return data

        parent = resolve_npc(ref_name, name_to_path, cache, depth + 1)
        if parent is None:
            cache[name] = data
            return data

        # Merge parameters: child overrides parent
        parent_params = parent.get("Parameters", {})
        child_params = data.get("Parameters", {})
        merged_params = deep_merge(parent_params, child_params)

        # Start with parent definition
        resolved = dict(parent)
        resolved["Parameters"] = merged_params

        # Apply Modify block
        modify = data.get("Modify", {})
        for key, value in modify.items():
            if key == "_InteractionVars":
                # Merge into parent's InteractionVars
                existing = resolved.get("InteractionVars", {})
                resolved["InteractionVars"] = deep_merge(existing, value)
            elif isinstance(value, dict) and "Compute" in value:
                resolved[key] = resolve_compute(value, merged_params)
            else:
                resolved[key] = value

        # Also resolve any remaining Compute references in top-level fields
        for key in list(resolved.keys()):
            if isinstance(resolved[key], dict) and "Compute" in resolved[key]:
                resolved[key] = resolve_compute(resolved[key], merged_params)

        resolved["_resolved_from"] = name
        resolved["Type"] = "Variant"
        cache[name] = resolved
        return resolved

    if npc_type == "Abstract":
        # Templates — resolve parameters but mark as non-spawnable
        params = data.get("Parameters", {})
        resolved = dict(data)
        for key in list(resolved.keys()):
            if isinstance(resolved[key], dict) and "Compute" in resolved[key]:
                resolved[key] = resolve_compute(resolved[key], params)
        cache[name] = resolved
        return resolved

    # Unknown type
    cache[name] = data
    return data


def extract_primary_damage(interaction_vars: dict | None) -> tuple[float, dict]:
    """Extract primary damage and all attack move damages from InteractionVars.

    Returns (primary_damage, all_moves_dict).
    primary_damage is the first/average melee damage found.
    """
    if not interaction_vars:
        return 0.0, {}

    all_moves: dict[str, float] = {}
    damages: list[float] = []

    for var_name, var_data in interaction_vars.items():
        if not isinstance(var_data, dict):
            continue
        interactions = var_data.get("Interactions", [])
        if not isinstance(interactions, list):
            continue
        for interaction in interactions:
            if not isinstance(interaction, dict):
                continue
            dc = interaction.get("DamageCalculator", {})
            if not isinstance(dc, dict):
                continue
            base_damage = dc.get("BaseDamage", {})
            if not isinstance(base_damage, dict):
                continue
            # Sum all damage types (Physical, Fire, etc.)
            total = sum(v for v in base_damage.values() if isinstance(v, (int, float)) and v > 0)
            if total > 0:
                all_moves[var_name] = total
                damages.append(total)

    if not damages:
        return 0.0, all_moves

    # Primary = average of all attack damages (better than just first)
    primary = sum(damages) / len(damages)
    return primary, all_moves


def extract_attack_distance(data: dict) -> float | None:
    """Try to extract attack distance from resolved NPC data."""
    # Check common locations
    for key in ("AttackDistance", "AttackRange"):
        val = data.get(key)
        if isinstance(val, (int, float)) and val > 0:
            return float(val)

    # Check inside Parameters
    params = data.get("Parameters", {})
    for key in ("AttackDistance", "AttackRange", "Melee_Attack_Distance"):
        if key in params:
            param = params[key]
            if isinstance(param, dict) and "Value" in param:
                val = param["Value"]
                if isinstance(val, (int, float)) and val > 0:
                    return float(val)
            elif isinstance(param, (int, float)) and param > 0:
                return float(param)

    return None


def extract_speed(data: dict) -> float | None:
    """Extract MaxSpeed from resolved NPC data."""
    speed = data.get("MaxSpeed")
    if isinstance(speed, (int, float)) and speed > 0:
        return float(speed)

    # Check MotionControllerList for MaxWalkSpeed
    mcl = data.get("MotionControllerList", [])
    if isinstance(mcl, list):
        for mc in mcl:
            if isinstance(mc, dict):
                ws = mc.get("MaxWalkSpeed")
                if isinstance(ws, (int, float)) and ws > 0:
                    return float(ws)

    # Check Parameters
    params = data.get("Parameters", {})
    if "MaxSpeed" in params:
        val = params["MaxSpeed"]
        if isinstance(val, dict) and "Value" in val:
            v = val["Value"]
            if isinstance(v, (int, float)):
                return float(v)
        elif isinstance(val, (int, float)):
            return float(val)

    return None


def extract_view_range(data: dict) -> float | None:
    """Extract view/detection range from NPC data."""
    params = data.get("Parameters", {})
    for key in ("ViewRange", "DetectionRange", "AbsoluteDetectionRange"):
        if key in params:
            val = params[key]
            if isinstance(val, dict) and "Value" in val:
                v = val["Value"]
                if isinstance(v, (int, float)):
                    return float(v)
        val = data.get(key)
        if isinstance(val, (int, float)):
            return float(val)
    return None


def extract_hearing_range(data: dict) -> float | None:
    """Extract hearing range from NPC data."""
    params = data.get("Parameters", {})
    for key in ("HearingRange",):
        if key in params:
            val = params[key]
            if isinstance(val, dict) and "Value" in val:
                v = val["Value"]
                if isinstance(v, (int, float)):
                    return float(v)
        val = data.get(key)
        if isinstance(val, (int, float)):
            return float(val)
    return None


def classify_tier(hp: int) -> str:
    """Classify NPC tier based on base HP."""
    if hp <= 36:
        return "Fodder"
    elif hp <= 74:
        return "Standard"
    elif hp <= 145:
        return "Tough"
    elif hp <= 320:
        return "Elite"
    else:
        return "Boss"


def infer_category(file_path: Path, roles_root: Path) -> str:
    """Infer NPC category from directory path."""
    try:
        rel = file_path.relative_to(roles_root)
        parts = rel.parts
        if len(parts) >= 2:
            # Use the top-level directory as category
            cat = parts[0]
            if cat.startswith("_"):
                return "Core"
            return cat
        # File is at the root — no meaningful category
        return "Unknown"
    except ValueError:
        pass
    return "Unknown"


def infer_ai_template(data: dict) -> str | None:
    """Infer AI template name from the reference chain."""
    ref = data.get("Reference")
    if isinstance(ref, str) and ref.startswith("Template_"):
        return ref
    return None


def should_skip_npc(name: str, data: dict, resolved: dict | None = None) -> bool:
    """Return True if this NPC should be excluded from the base table."""
    npc_type = data.get("Type", "")

    # Skip non-spawnable types
    if npc_type in ("Abstract", "Component"):
        return True

    # Skip test/development NPCs
    lower = name.lower()
    if "test" in lower or "debug" in lower or "dev_" in lower:
        return True

    # Skip placeholder/utility NPCs (Empty_Role, Static, etc.)
    if lower in ("empty_role", "static", "static2", "static3", "static4"):
        return True

    # Skip invulnerable NPCs (Template_Placeholder display-only NPCs)
    if resolved:
        if resolved.get("Invulnerable") is True:
            return True
        ref = data.get("Reference", "")
        if ref == "Template_Placeholder":
            return True

    # Skip _Core utility entries in test directories
    if "_Core" in str(data.get("_source_path", "")):
        is_test_dir = "Tests_Development" in str(data.get("_source_path", ""))
        if is_test_dir:
            return True

    return False


def parse_npcs(roles_root: Path, conn: sqlite3.Connection) -> int:
    """Parse all NPCs and insert into monsters_base. Returns count."""
    print(f"Scanning NPCs from {roles_root}...")
    name_to_path = discover_npc_files(roles_root)
    print(f"  Found {len(name_to_path)} NPC role files")

    cache: dict[str, dict] = {}
    inserted = 0

    conn.execute("DELETE FROM monsters_base")

    for name, path in sorted(name_to_path.items()):
        resolved = resolve_npc(name, name_to_path, cache)
        if resolved is None:
            continue

        resolved["_source_path"] = str(path)

        # Load original file data for skip checks (Reference, Type)
        original_data = load_json(path) or {}
        if should_skip_npc(name, original_data, resolved):
            continue

        # Extract MaxHealth
        max_health = resolved.get("MaxHealth")
        if not isinstance(max_health, (int, float)) or max_health <= 0:
            continue  # No valid health — skip

        max_health = int(max_health)

        # Extract damage from InteractionVars
        iv = resolved.get("InteractionVars", {})
        primary_damage, all_moves = extract_primary_damage(iv)

        # Fallback: if no damage found, use default NPC base damage (5)
        if primary_damage <= 0:
            primary_damage = 5.0

        speed = extract_speed(resolved)
        attack_dist = extract_attack_distance(resolved)
        view_range = extract_view_range(resolved)
        hearing_range = extract_hearing_range(resolved)
        category = infer_category(path, roles_root)
        tier = classify_tier(max_health)
        ai_template = infer_ai_template(resolved)

        extra = {}
        if all_moves:
            extra["attack_moves"] = all_moves
        attitude = resolved.get("DefaultPlayerAttitude")
        if attitude:
            extra["player_attitude"] = attitude

        try:
            conn.execute(
                """INSERT OR REPLACE INTO monsters_base
                   (npc_id, name, category, tier, base_hp, base_damage, base_speed,
                    attack_distance, ai_template, view_range, hearing_range, extra_json)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                (
                    name,
                    name.replace("_", " "),
                    category,
                    tier,
                    max_health,
                    round(primary_damage, 2),
                    speed,
                    attack_dist,
                    ai_template,
                    view_range,
                    hearing_range,
                    json.dumps(extra) if extra else None,
                ),
            )
            inserted += 1
        except sqlite3.Error as e:
            print(f"  WARN: Failed to insert {name}: {e}", file=sys.stderr)

    conn.commit()
    print(f"  Inserted {inserted} NPCs into monsters_base")
    return inserted


# ── Weapon Parsing ────────────────────────────────────────────────────

def discover_item_files(items_root: Path, subfolder: str) -> dict[str, tuple[Path, dict]]:
    """Load all item JSON files from a subfolder, returning name→(path, data)."""
    result: dict[str, tuple[Path, dict]] = {}
    target = items_root / subfolder
    if not target.exists():
        print(f"  WARN: {target} not found", file=sys.stderr)
        return result
    for json_path in target.rglob("*.json"):
        data = load_json(json_path)
        if data is not None:
            result[json_path.stem] = (json_path, data)
    return result


def resolve_weapon_damage(
    data: dict, all_weapons: dict[str, tuple[Path, dict]]
) -> tuple[float, dict]:
    """Extract primary damage and all attack moves from a weapon.

    Handles Parent inheritance: if the weapon has a Parent, we merge
    the parent's InteractionVars as fallback.
    """
    # Resolve parent chain for InteractionVars
    parent_name = data.get("Parent")
    parent_iv: dict = {}
    if parent_name and parent_name in all_weapons:
        _, parent_data = all_weapons[parent_name]
        parent_iv = parent_data.get("InteractionVars", {})

    iv = data.get("InteractionVars", {})
    merged_iv = deep_merge(parent_iv, iv)

    return extract_primary_damage(merged_iv)


def parse_weapons(items_root: Path, conn: sqlite3.Connection) -> int:
    """Parse all weapons and insert into weapons_base. Returns count."""
    print(f"Scanning weapons from {items_root / 'Weapon'}...")
    all_weapons = discover_item_files(items_root, "Weapon")
    print(f"  Found {len(all_weapons)} weapon files")

    conn.execute("DELETE FROM weapons_base")
    inserted = 0

    for name, (path, data) in sorted(all_weapons.items()):
        # Skip templates
        quality = data.get("Quality", "")
        if quality == "Template" or name.startswith("Template_"):
            continue

        # Skip arrows, ammo, debug items
        lower = name.lower()
        if "arrow" in lower or "ammo" in lower or "debug" in lower or "test" in lower:
            continue

        # Extract family from Tags or directory
        tags = data.get("Tags", {})
        family_list = tags.get("Family", [])
        if family_list and isinstance(family_list, list):
            family = family_list[0]
        else:
            # Infer from parent directory name
            family = path.parent.name

        item_level = data.get("ItemLevel", 0)
        if not isinstance(item_level, int):
            item_level = 0

        primary_damage, all_moves = resolve_weapon_damage(data, all_weapons)

        if primary_damage <= 0:
            continue  # No damage defined — skip

        try:
            conn.execute(
                """INSERT OR REPLACE INTO weapons_base
                   (weapon_id, name, family, quality, item_level, base_damage, attack_moves_json)
                   VALUES (?, ?, ?, ?, ?, ?, ?)""",
                (
                    name,
                    name.replace("_", " "),
                    family,
                    quality if quality else "Common",
                    item_level,
                    round(primary_damage, 2),
                    json.dumps(all_moves) if all_moves else None,
                ),
            )
            inserted += 1
        except sqlite3.Error as e:
            print(f"  WARN: Failed to insert weapon {name}: {e}", file=sys.stderr)

    conn.commit()
    print(f"  Inserted {inserted} weapons into weapons_base")
    return inserted


# ── Armor Parsing ─────────────────────────────────────────────────────

def resolve_armor_stats(
    data: dict, all_armor: dict[str, tuple[Path, dict]]
) -> dict:
    """Extract armor stats, resolving Parent inheritance."""
    parent_name = data.get("Parent")
    parent_armor: dict = {}
    if parent_name and parent_name in all_armor:
        _, parent_data = all_armor[parent_name]
        parent_armor = parent_data.get("Armor", {})

    armor = data.get("Armor", {})
    merged = deep_merge(parent_armor, armor)
    return merged


def parse_armor(items_root: Path, conn: sqlite3.Connection) -> int:
    """Parse all armor and insert into armor_base. Returns count."""
    print(f"Scanning armor from {items_root / 'Armor'}...")
    all_armor = discover_item_files(items_root, "Armor")
    print(f"  Found {len(all_armor)} armor files")

    conn.execute("DELETE FROM armor_base")
    inserted = 0

    for name, (path, data) in sorted(all_armor.items()):
        # Skip templates
        quality = data.get("Quality", "")
        if quality == "Template" or name.startswith("Template_"):
            continue
        if name.startswith("Armor_Iron_") and not data.get("Parent"):
            # Iron is the base — include it
            pass

        lower = name.lower()
        if "debug" in lower or "test" in lower or "qa" in lower:
            continue
        if quality in ("Debug", "QA"):
            continue

        armor_stats = resolve_armor_stats(data, all_armor)
        if not armor_stats:
            continue

        slot = armor_stats.get("ArmorSlot", "Unknown")
        item_level = data.get("ItemLevel", 0)
        if not isinstance(item_level, int):
            item_level = 0

        # Extract Physical resistance
        phys_resist = 0.0
        dr = armor_stats.get("DamageResistance", {})
        phys_entries = dr.get("Physical", [])
        if isinstance(phys_entries, list):
            for entry in phys_entries:
                if isinstance(entry, dict):
                    phys_resist += entry.get("Amount", 0)

        # Extract Projectile resistance
        proj_resist = 0.0
        proj_entries = dr.get("Projectile", [])
        if isinstance(proj_entries, list):
            for entry in proj_entries:
                if isinstance(entry, dict):
                    proj_resist += entry.get("Amount", 0)

        # Extract health bonus
        health_bonus = 0
        stat_mods = armor_stats.get("StatModifiers", {})
        health_entries = stat_mods.get("Health", [])
        if isinstance(health_entries, list):
            for entry in health_entries:
                if isinstance(entry, dict):
                    health_bonus += int(entry.get("Amount", 0))

        # Special features
        specials = []
        dce = armor_stats.get("DamageClassEnhancement", {})
        for dc_name, dc_entries in dce.items():
            if isinstance(dc_entries, list):
                for entry in dc_entries:
                    if isinstance(entry, dict):
                        amt = entry.get("Amount", 0)
                        specials.append(f"{dc_name} +{amt*100:.0f}%")

        # Infer family from Tags
        tags = data.get("Tags", {})
        family_list = tags.get("Family", [])
        family = family_list[0] if family_list else path.parent.name

        try:
            conn.execute(
                """INSERT OR REPLACE INTO armor_base
                   (armor_id, name, slot, quality, item_level, phys_resist,
                    proj_resist, health_bonus, special)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                (
                    name,
                    name.replace("_", " "),
                    slot,
                    quality if quality else "Common",
                    item_level,
                    round(phys_resist, 6),
                    round(proj_resist, 6),
                    health_bonus,
                    ", ".join(specials) if specials else None,
                ),
            )
            inserted += 1
        except sqlite3.Error as e:
            print(f"  WARN: Failed to insert armor {name}: {e}", file=sys.stderr)

    conn.commit()
    print(f"  Inserted {inserted} armor pieces into armor_base")
    return inserted


# ── Main ──────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="Parse HytaleAssets into scaling.db base tables")
    parser.add_argument(
        "--assets-root",
        default=str(Path(__file__).resolve().parent.parent.parent.parent / "HytaleAssets"),
        help="Path to HytaleAssets root (default: ../../HytaleAssets relative to this module)",
    )
    parser.add_argument(
        "--db",
        default=str(Path(__file__).resolve().parent / "scaling.db"),
        help="Path to SQLite database (default: scaling.db in this directory)",
    )
    args = parser.parse_args()

    assets_root = Path(args.assets_root)
    if not assets_root.exists():
        print(f"ERROR: Assets root not found: {assets_root}", file=sys.stderr)
        sys.exit(1)

    roles_root = assets_root / "Server" / "NPC" / "Roles"
    items_root = assets_root / "Server" / "Item" / "Items"

    if not roles_root.exists():
        print(f"ERROR: NPC roles directory not found: {roles_root}", file=sys.stderr)
        sys.exit(1)

    print(f"Assets root: {assets_root}")
    print(f"Database: {args.db}")
    print()

    conn = init_db(args.db)

    try:
        npc_count = parse_npcs(roles_root, conn)
        print()
        weapon_count = parse_weapons(items_root, conn)
        print()
        armor_count = parse_armor(items_root, conn)
        print()

        print(f"Done! Parsed {npc_count} NPCs, {weapon_count} weapons, {armor_count} armor pieces.")

        # Store metadata
        conn.execute(
            "INSERT OR REPLACE INTO scaling_config (key, value) VALUES (?, ?)",
            ("parse_assets_root", str(assets_root)),
        )
        conn.execute(
            "INSERT OR REPLACE INTO scaling_config (key, value) VALUES (?, ?)",
            ("parse_assets_counts", json.dumps({"npcs": npc_count, "weapons": weapon_count, "armor": armor_count})),
        )
        conn.commit()

        # Print summary
        print("\n── Summary ──")
        for row in conn.execute("SELECT tier, COUNT(*) FROM monsters_base GROUP BY tier ORDER BY tier"):
            print(f"  {row[0]:10s}: {row[1]} NPCs")
        print()
        for row in conn.execute("SELECT family, COUNT(*) FROM weapons_base GROUP BY family ORDER BY family"):
            print(f"  {row[0]:12s}: {row[1]} weapons")
        print()
        for row in conn.execute("SELECT slot, COUNT(*) FROM armor_base GROUP BY slot ORDER BY slot"):
            print(f"  {row[0]:8s}: {row[1]} armor pieces")
    finally:
        conn.close()


if __name__ == "__main__":
    main()
