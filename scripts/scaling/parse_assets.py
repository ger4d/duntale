#!/usr/bin/env python3
"""
parse_assets.py — Scan asset sources to populate *_base tables in scaling.db.

Scans one or more asset roots (HytaleAssets, the v3-zsquad mod resources, and
third-party mod jars) and records every NPC, weapon, and armor piece with a
`source` label so downstream analysis can distinguish built-in content from
modded content.

Resolves full NPC inheritance chains (Reference → Template → Component)
to extract MaxHealth, MaxSpeed, damage, attack distance, etc.
Weapons resolve damage two ways, mirroring the runtime AssetCatalog:
  1. inline   — InteractionVars[*].Interactions[*].DamageCalculator.BaseDamage
  2. chain    — Interactions.{Primary,...} → RootInteraction → Interaction assets
Weapons with no resolvable damage are KEPT with damage_method='none' because the
runtime MerchantPriceRegistry still prices them via the tier/quality fallback.

Usage:
    uv run parse_assets.py [--source label=path ...] [--db scaling.db]

Default sources (load order; later sources override same-named assets):
    builtin = <repo>/HytaleAssets
    duntale = <repo>/v3-zsquad/src/main/resources
    wans    = <repo>/server/Server/mods/WansWonderWeapon-1.0.25.jar
    zets    = <repo>/server/Server/mods/ZetsMysticWeapons-1.2.2.jar

Jar sources are transparently extracted (Server/** only) to a temp directory.
"""
from __future__ import annotations

import argparse
import json
import sqlite3
import sys
import tempfile
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any


# ── Schema DDL ────────────────────────────────────────────────────────

SCHEMA_DDL = """
DROP TABLE IF EXISTS monsters_base;
CREATE TABLE monsters_base (
    npc_id          TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    source          TEXT NOT NULL DEFAULT 'builtin',
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

DROP TABLE IF EXISTS weapons_base;
CREATE TABLE weapons_base (
    weapon_id       TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    source          TEXT NOT NULL DEFAULT 'builtin',
    family          TEXT NOT NULL,
    quality         TEXT NOT NULL,
    item_level      INTEGER NOT NULL,
    base_damage     REAL NOT NULL,
    damage_method   TEXT NOT NULL DEFAULT 'none',
    attack_moves_json TEXT
);

DROP TABLE IF EXISTS armor_base;
CREATE TABLE armor_base (
    armor_id        TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    source          TEXT NOT NULL DEFAULT 'builtin',
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
    """Create/open the database and (re)create the base-table schema."""
    conn = sqlite3.connect(db_path)
    conn.executescript(SCHEMA_DDL)
    conn.commit()
    return conn


# ── Source handling ───────────────────────────────────────────────────

@dataclass(frozen=True)
class AssetSource:
    """A labeled asset root containing a Server/ directory."""
    label: str
    root: Path


def prepare_sources(specs: list[str], repo_root: Path, temp_dir: Path) -> list[AssetSource]:
    """Resolve --source label=path specs (or defaults) into AssetSource roots.

    Jar paths are extracted (Server/** and manifest.json) into temp_dir/<label>.
    """
    if not specs:
        specs = [
            f"builtin={repo_root / 'HytaleAssets'}",
            f"duntale={repo_root / 'v3-zsquad' / 'src' / 'main' / 'resources'}",
            f"wans={repo_root / 'server' / 'Server' / 'mods' / 'WansWonderWeapon-1.0.25.jar'}",
            f"zets={repo_root / 'server' / 'Server' / 'mods' / 'ZetsMysticWeapons-1.2.2.jar'}",
        ]

    sources: list[AssetSource] = []
    for spec in specs:
        if "=" not in spec:
            print(f"ERROR: --source must be label=path, got: {spec}", file=sys.stderr)
            sys.exit(1)
        label, _, raw_path = spec.partition("=")
        path = Path(raw_path)
        if not path.exists():
            print(f"  WARN: source '{label}' not found at {path}, skipping", file=sys.stderr)
            continue

        if path.suffix.lower() in (".jar", ".zip"):
            extract_root = temp_dir / label
            extract_root.mkdir(parents=True, exist_ok=True)
            with zipfile.ZipFile(path) as zf:
                members = [m for m in zf.namelist() if m.startswith("Server/")]
                zf.extractall(extract_root, members)
            sources.append(AssetSource(label, extract_root))
        else:
            sources.append(AssetSource(label, path))

    if not sources:
        print("ERROR: no usable asset sources", file=sys.stderr)
        sys.exit(1)
    return sources


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

def discover_npc_files(sources: list[AssetSource]) -> tuple[dict[str, Path], dict[str, str], dict[str, Path]]:
    """Build name→path, name→source, and name→roles_root maps across all sources."""
    name_to_path: dict[str, Path] = {}
    name_to_source: dict[str, str] = {}
    name_to_root: dict[str, Path] = {}
    for src in sources:
        roles_root = src.root / "Server" / "NPC" / "Roles"
        if not roles_root.exists():
            continue
        for json_path in roles_root.rglob("*.json"):
            name = json_path.stem  # filename without .json
            name_to_path[name] = json_path
            name_to_source[name] = src.label
            name_to_root[name] = roles_root
    return name_to_path, name_to_source, name_to_root


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
    primary_damage is the average melee damage found.
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


def parse_npcs(sources: list[AssetSource], conn: sqlite3.Connection) -> int:
    """Parse all NPCs from every source and insert into monsters_base. Returns count."""
    name_to_path, name_to_source, name_to_root = discover_npc_files(sources)
    print(f"Scanning NPCs from {len(sources)} sources...")
    print(f"  Found {len(name_to_path)} NPC role files")

    cache: dict[str, dict] = {}
    inserted = 0

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
        category = infer_category(path, name_to_root[name])
        tier = classify_tier(max_health)
        ai_template = infer_ai_template(resolved)

        extra = {}
        if all_moves:
            extra["attack_moves"] = all_moves
        attitude = resolved.get("DefaultPlayerAttitude")
        if attitude:
            extra["player_attitude"] = attitude
        summon_kind = resolved.get("SummonKind")
        if isinstance(summon_kind, str) and summon_kind:
            extra["summon_kind"] = summon_kind

        try:
            conn.execute(
                """INSERT OR REPLACE INTO monsters_base
                   (npc_id, name, source, category, tier, base_hp, base_damage, base_speed,
                    attack_distance, ai_template, view_range, hearing_range, extra_json)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                (
                    name,
                    name.replace("_", " "),
                    name_to_source[name],
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


# ── Item Discovery (weapons + armor) ──────────────────────────────────

def discover_items(sources: list[AssetSource]) -> dict[str, tuple[Path, dict, str]]:
    """Load all item JSON files across sources, returning name→(path, data, source).

    Later sources override earlier ones for the same asset name, matching the
    server's mod-pack override order.
    """
    result: dict[str, tuple[Path, dict, str]] = {}
    for src in sources:
        items_root = src.root / "Server" / "Item" / "Items"
        if not items_root.exists():
            continue
        for json_path in items_root.rglob("*.json"):
            data = load_json(json_path)
            if data is not None:
                result[json_path.stem] = (json_path, data, src.label)
    return result


def build_interaction_indexes(sources: list[AssetSource]) -> tuple[dict[str, dict], dict[str, dict]]:
    """Index RootInteraction and Interaction assets by stem across all sources."""
    roots: dict[str, dict] = {}
    interactions: dict[str, dict] = {}
    for src in sources:
        for subfolder, target in (("RootInteractions", roots), ("Interactions", interactions)):
            folder = src.root / "Server" / "Item" / subfolder
            if not folder.exists():
                continue
            for json_path in folder.rglob("*.json"):
                data = load_json(json_path)
                if data is not None:
                    target[json_path.stem] = data
    return roots, interactions


def resolve_item(
    name: str,
    all_items: dict[str, tuple[Path, dict, str]],
    cache: dict[str, dict],
    depth: int = 0,
) -> dict | None:
    """Resolve an item's full Parent inheritance chain into one merged dict."""
    if name in cache:
        return cache[name]
    entry = all_items.get(name)
    if entry is None or depth > 12:
        return None

    _, data, _ = entry
    parent_name = data.get("Parent")
    base: dict = {}
    if isinstance(parent_name, str):
        parent = resolve_item(parent_name, all_items, cache, depth + 1)
        if parent:
            base = parent
    merged = deep_merge(base, data)
    cache[name] = merged
    return merged


def _walk_interaction_graph(
    node: Any,
    doc_name: str,
    interactions: dict[str, dict],
    visited: set[str],
    moves: dict[str, float],
) -> None:
    """Recursively collect DamageCalculator.BaseDamage totals reachable from node.

    Interaction chains nest through Charging/Chaining "Next" maps, "Interactions"
    lists, and "Parent" references, so any string anywhere in a reachable document
    that names another interaction asset is followed (each document only once).
    """
    if isinstance(node, str):
        if node in visited:
            return
        data = interactions.get(node)
        if data is None:
            return
        visited.add(node)
        _walk_interaction_graph(data, node, interactions, visited, moves)
        return

    if isinstance(node, list):
        for child in node:
            _walk_interaction_graph(child, doc_name, interactions, visited, moves)
        return

    if not isinstance(node, dict):
        return

    dc = node.get("DamageCalculator")
    if isinstance(dc, dict):
        base_damage = dc.get("BaseDamage")
        if isinstance(base_damage, dict):
            total = sum(v for v in base_damage.values() if isinstance(v, (int, float)) and v > 0)
            if total > 0:
                moves[doc_name] = max(moves.get(doc_name, 0.0), total)

    for value in node.values():
        _walk_interaction_graph(value, doc_name, interactions, visited, moves)


def chain_damage(
    merged_item: dict,
    roots: dict[str, dict],
    interactions: dict[str, dict],
) -> tuple[float, dict]:
    """Extract damage via Interactions.{slot} → RootInteraction → Interaction chains.

    Used for items (e.g. WansWonderWeapon) that author damage in standalone
    interaction assets instead of inline InteractionVars. The runtime AssetCatalog
    only inspects direct root children, but authored damage commonly sits several
    Charging/Chaining hops deep, so we walk the full reachable interaction graph.
    """
    refs = merged_item.get("Interactions")
    if not isinstance(refs, dict):
        return 0.0, {}

    moves: dict[str, float] = {}
    visited: set[str] = set()
    for slot, root_id in refs.items():
        if not isinstance(root_id, str):
            continue
        root = roots.get(root_id)
        if not isinstance(root, dict):
            continue
        _walk_interaction_graph(root, f"{root_id}:{slot}", interactions, visited, moves)

    if not moves:
        return 0.0, {}
    return sum(moves.values()) / len(moves), moves


def is_skippable_item(name: str, quality: str) -> bool:
    """Mirror the runtime AssetCatalog skip rules for templates/debug items."""
    if name.startswith("Template_") or quality in ("Template", "Developer", "Technical", "Debug", "QA"):
        return True
    lower = name.lower()
    return "debug" in lower or "test" in lower or "_qa_" in lower


def is_npc_item(name: str) -> bool:
    """Mirror runtime AssetCatalog NPC-held item detection."""
    lower = name.lower()
    return lower.endswith("_npc") or "_npc_" in lower


def parse_weapons(
    all_items: dict[str, tuple[Path, dict, str]],
    roots: dict[str, dict],
    interactions: dict[str, dict],
    conn: sqlite3.Connection,
) -> int:
    """Parse all weapons and insert into weapons_base. Returns count."""
    cache: dict[str, dict] = {}
    inserted = 0

    for name in sorted(all_items):
        path, _, source = all_items[name]
        merged = resolve_item(name, all_items, cache)
        if merged is None:
            continue

        # Weapon = has a Weapon config (runtime: item.getWeapon() != null)
        # or lives under an Items/**/Weapon/ folder.
        if not isinstance(merged.get("Weapon"), dict) and "Weapon" not in path.parts:
            continue

        quality = merged.get("Quality", "")
        if is_skippable_item(name, quality) or is_npc_item(name):
            continue

        # Skip arrows and ammo (runtime does the same)
        lower = name.lower()
        if "arrow" in lower or "ammo" in lower:
            continue

        # Extract family from Tags or directory
        tags = merged.get("Tags", {})
        family_list = tags.get("Family", []) if isinstance(tags, dict) else []
        if family_list and isinstance(family_list, list):
            family = family_list[0]
        else:
            family = path.parent.name

        item_level = merged.get("ItemLevel", 0)
        if not isinstance(item_level, int):
            item_level = 0

        # Damage resolution: inline InteractionVars first, then interaction chains
        primary_damage, all_moves = extract_primary_damage(merged.get("InteractionVars"))
        damage_method = "inline"
        if primary_damage <= 0:
            primary_damage, all_moves = chain_damage(merged, roots, interactions)
            damage_method = "chain" if primary_damage > 0 else "none"

        try:
            conn.execute(
                """INSERT OR REPLACE INTO weapons_base
                   (weapon_id, name, source, family, quality, item_level, base_damage,
                    damage_method, attack_moves_json)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                (
                    name,
                    name.replace("_", " "),
                    source,
                    family,
                    quality if quality else "Common",
                    item_level,
                    round(primary_damage, 2),
                    damage_method,
                    json.dumps(all_moves) if all_moves else None,
                ),
            )
            inserted += 1
        except sqlite3.Error as e:
            print(f"  WARN: Failed to insert weapon {name}: {e}", file=sys.stderr)

    conn.commit()
    print(f"  Inserted {inserted} weapons into weapons_base")
    return inserted


def parse_armor(
    all_items: dict[str, tuple[Path, dict, str]],
    conn: sqlite3.Connection,
) -> int:
    """Parse all armor and insert into armor_base. Returns count."""
    cache: dict[str, dict] = {}
    inserted = 0

    for name in sorted(all_items):
        path, _, source = all_items[name]
        merged = resolve_item(name, all_items, cache)
        if merged is None:
            continue

        armor_stats = merged.get("Armor")
        if not isinstance(armor_stats, dict) or not armor_stats:
            continue

        quality = merged.get("Quality", "")
        if is_skippable_item(name, quality) or is_npc_item(name):
            continue

        slot = armor_stats.get("ArmorSlot", "Unknown")
        item_level = merged.get("ItemLevel", 0)
        if not isinstance(item_level, int):
            item_level = 0

        # Extract Physical resistance
        phys_resist = 0.0
        dr = armor_stats.get("DamageResistance", {})
        phys_entries = dr.get("Physical", []) if isinstance(dr, dict) else []
        if isinstance(phys_entries, list):
            for entry in phys_entries:
                if isinstance(entry, dict):
                    phys_resist += entry.get("Amount", 0)

        # Extract Projectile resistance
        proj_resist = 0.0
        proj_entries = dr.get("Projectile", []) if isinstance(dr, dict) else []
        if isinstance(proj_entries, list):
            for entry in proj_entries:
                if isinstance(entry, dict):
                    proj_resist += entry.get("Amount", 0)

        # Extract health bonus
        health_bonus = 0
        stat_mods = armor_stats.get("StatModifiers", {})
        health_entries = stat_mods.get("Health", []) if isinstance(stat_mods, dict) else []
        if isinstance(health_entries, list):
            for entry in health_entries:
                if isinstance(entry, dict):
                    health_bonus += int(entry.get("Amount", 0))

        # Special features
        specials = []
        dce = armor_stats.get("DamageClassEnhancement", {})
        if isinstance(dce, dict):
            for dc_name, dc_entries in dce.items():
                if isinstance(dc_entries, list):
                    for entry in dc_entries:
                        if isinstance(entry, dict):
                            amt = entry.get("Amount", 0)
                            specials.append(f"{dc_name} +{amt*100:.0f}%")

        try:
            conn.execute(
                """INSERT OR REPLACE INTO armor_base
                   (armor_id, name, source, slot, quality, item_level, phys_resist,
                    proj_resist, health_bonus, special)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                (
                    name,
                    name.replace("_", " "),
                    source,
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
    parser = argparse.ArgumentParser(description="Parse asset sources into scaling.db base tables")
    parser.add_argument(
        "--source",
        action="append",
        default=[],
        metavar="LABEL=PATH",
        help="Asset source as label=path (dir with Server/, or a mod .jar). "
             "Repeatable; defaults to builtin + duntale + wans + zets.",
    )
    parser.add_argument(
        "--db",
        default=str(Path(__file__).resolve().parent / "scaling.db"),
        help="Path to SQLite database (default: scaling.db in this directory)",
    )
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parent.parent.parent.parent

    with tempfile.TemporaryDirectory(prefix="duntale-assets-") as temp_dir:
        sources = prepare_sources(args.source, repo_root, Path(temp_dir))

        print("Asset sources:")
        for src in sources:
            print(f"  {src.label:8s} → {src.root}")
        print(f"Database: {args.db}")
        print()

        conn = init_db(args.db)

        try:
            npc_count = parse_npcs(sources, conn)
            print()

            all_items = discover_items(sources)
            roots, interactions = build_interaction_indexes(sources)
            print(f"Scanning items: {len(all_items)} item files, "
                  f"{len(roots)} root interactions, {len(interactions)} interactions")
            weapon_count = parse_weapons(all_items, roots, interactions, conn)
            armor_count = parse_armor(all_items, conn)
            print()

            print(f"Done! Parsed {npc_count} NPCs, {weapon_count} weapons, {armor_count} armor pieces.")

            # Store metadata
            conn.execute(
                "INSERT OR REPLACE INTO scaling_config (key, value) VALUES (?, ?)",
                ("parse_assets_sources", json.dumps({s.label: str(s.root) for s in sources})),
            )
            conn.execute(
                "INSERT OR REPLACE INTO scaling_config (key, value) VALUES (?, ?)",
                ("parse_assets_counts", json.dumps({"npcs": npc_count, "weapons": weapon_count, "armor": armor_count})),
            )
            conn.commit()

            # Print summary
            print("\n── Summary ──")
            for row in conn.execute("SELECT source, COUNT(*) FROM monsters_base GROUP BY source ORDER BY source"):
                print(f"  NPCs   [{row[0]:8s}]: {row[1]}")
            for row in conn.execute("SELECT source, COUNT(*) FROM weapons_base GROUP BY source ORDER BY source"):
                print(f"  Weapons[{row[0]:8s}]: {row[1]}")
            for row in conn.execute("SELECT source, COUNT(*) FROM armor_base GROUP BY source ORDER BY source"):
                print(f"  Armor  [{row[0]:8s}]: {row[1]}")
            print()
            for row in conn.execute(
                "SELECT damage_method, COUNT(*) FROM weapons_base GROUP BY damage_method ORDER BY damage_method"
            ):
                print(f"  Weapon damage via {row[0]:7s}: {row[1]}")
        finally:
            conn.close()


if __name__ == "__main__":
    main()
