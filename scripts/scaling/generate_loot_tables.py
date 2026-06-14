# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Author NPC loot tables from per-archetype templates.

Every spawnable role gets one ``Server/Configs/LootTables/<Role>.json`` with two independent
pools (gear + gold), matching the runtime ``LootTable``:

- Roles that already have a base table are *transformed* in place: their thematic gear entries are
  preserved, any inline ``Gold_Coin`` entry is moved into the new ``GoldEntries`` pool, and the gear
  ``DropChance`` / gold ``GoldChance`` are reset to the archetype template. This keeps hand-picked,
  theme-appropriate gear while adopting the gear/gold split.
- Roles with no table yet (the missing roster roles + summons) are *created* from scratch by querying
  ``scaling.db`` for level-banded weapons/armor in the archetype's preferred families/slots.
- ``Swarm`` roles are gold-only (any gear is stripped) — they drop small gold, never gear.

Role -> archetype comes from ``NpcArchetypes.json`` (the W2 mapping). Gear stamp level bands come from
each role's spawn floor range (``spawn_roster``), or, for summons, their summoner's range.

A table carrying a top-level ``"Authored": true`` flag is left untouched (hand-authored override that
survives regeneration). The flag is generator-only — the runtime asset codec ignores unknown keys.

Final gold quantities and the gear/gold balance are reconciled later in the income pass; the values
here are starting anchors.

Run:  uv run generate_loot_tables.py
"""

from __future__ import annotations

import json
import sqlite3
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parent.parent
DB_PATH = SCRIPT_DIR / "scaling.db"
TEMPLATES_PATH = SCRIPT_DIR / "archetype_loot_templates.json"
ARCHETYPES_PATH = ROOT / "src/main/resources/Server/Configs/Scaling/NpcArchetypes.json"
LOOT_DIR = ROOT / "src/main/resources/Server/Configs/LootTables"
MANIFEST_PATH = SCRIPT_DIR / "loot_table_manifest.json"

GOLD_ITEM = "Gold_Coin"
DEFAULT_ANCHOR = 20  # fallback gear-level anchor when a role has no spawn/summon floor range
LEVEL_MIN, LEVEL_MAX = 1, 100


def load_json(path: Path) -> dict:
    return json.loads(path.read_text())


def role_archetypes() -> dict[str, str]:
    """role name -> archetype name, from the W2 NpcArchetypes mapping."""
    data = load_json(ARCHETYPES_PATH)
    return {r["Role"]: r["Archetype"] for r in data.get("Roles", [])}


def role_level_anchors(conn: sqlite3.Connection, roles: set[str]) -> dict[str, int]:
    """Gear-level anchor per role = midpoint of its spawn floor range (union across themes).

    Summons (not in spawn_roster) inherit their summoner's anchor; anything still unresolved
    falls back to DEFAULT_ANCHOR.
    """
    anchors: dict[str, int] = {}
    for role, lo, hi in conn.execute(
            "select role, min(min_floor), max(max_floor) from spawn_roster group by role"):
        anchors[role] = max(LEVEL_MIN, round((lo + hi) / 2))

    summoner_of = {summoned: summoner
                   for summoner, summoned, _ in conn.execute("select * from summon_edges")}
    for role in roles:
        if role in anchors:
            continue
        summoner = summoner_of.get(role)
        anchors[role] = anchors.get(summoner, DEFAULT_ANCHOR) if summoner else DEFAULT_ANCHOR
    return anchors


def band(anchor: int, half_width: int) -> tuple[int, int]:
    return max(LEVEL_MIN, anchor - half_width), min(LEVEL_MAX, anchor + half_width)


def pick_weapons(conn: sqlite3.Connection, families: list[str], anchor: int,
                 sources: list[str], count: int) -> list[str]:
    if not families or count <= 0:
        return []
    fam_ph = ",".join("?" * len(families))
    src_ph = ",".join("?" * len(sources))
    rows = conn.execute(
        f"""select weapon_id from weapons_base
            where base_damage > 0 and family in ({fam_ph}) and source in ({src_ph})
            order by abs(item_level - ?), weapon_id""",
        (*families, *sources, anchor)).fetchall()
    return [r[0] for r in rows[:count]]


def pick_armor(conn: sqlite3.Connection, slots: list[str], anchor: int, count: int) -> list[str]:
    if not slots or count <= 0:
        return []
    picks: list[str] = []
    # One piece per slot, closest to the anchor, cycling slots until we have `count`.
    per_slot: dict[str, list[str]] = {}
    for slot in slots:
        rows = conn.execute(
            "select armor_id from armor_base where slot = ? order by abs(item_level - ?), armor_id",
            (slot, anchor)).fetchall()
        per_slot[slot] = [r[0] for r in rows]
    idx = 0
    while len(picks) < count and any(per_slot[s] for s in slots):
        slot = slots[idx % len(slots)]
        if per_slot[slot]:
            picks.append(per_slot[slot].pop(0))
        idx += 1
        if idx > len(slots) * 50:
            break
    return picks


def leveled_entry(item_id: str, gear_type: str, lo: int, hi: int, weight: float) -> dict:
    return {"Type": "LEVELED", "ItemId": item_id, "Weight": weight,
            "GearType": gear_type, "GearLevelMin": lo, "GearLevelMax": hi}


def gold_entry(qty_min: int, qty_max: int) -> dict:
    return {"Type": "SIMPLE", "ItemId": GOLD_ITEM, "Weight": 1.0,
            "QuantityMin": qty_min, "QuantityMax": qty_max}


def split_gold(entries: list[dict]) -> tuple[list[dict], list[dict]]:
    """Partition existing entries into (gear, gold) by item id."""
    gear = [e for e in entries if e.get("ItemId") != GOLD_ITEM]
    gold = [e for e in entries if e.get("ItemId") == GOLD_ITEM]
    return gear, gold


def build_table(role: str, archetype: str, template: dict, templates_meta: dict,
                conn: sqlite3.Connection, anchors: dict[str, int],
                existing: dict | None) -> tuple[dict, str]:
    """Returns (table_json, mode) where mode is 'transform' or 'create'."""
    is_swarm = archetype == "Swarm"
    anchor = anchors.get(role, DEFAULT_ANCHOR)
    lo, hi = band(anchor, templates_meta["levelBandHalfWidth"])

    # ── Gear pool ──
    if is_swarm:
        gear_entries: list[dict] = []  # gold-only
    elif existing is not None:
        gear_entries, _ = split_gold(existing.get("Entries", []))
    else:
        weapons = pick_weapons(conn, template["weaponFamilies"], anchor,
                               templates_meta["weaponSources"], 2)
        armor = pick_armor(conn, template["armorSlots"], anchor,
                           max(0, templates_meta["gearItemsPerTable"] - len(weapons)))
        gear_entries = [leveled_entry(w, "WEAPON", lo, hi, 1.0) for w in weapons]
        gear_entries += [leveled_entry(a, "ARMOR", lo, hi, 0.8) for a in armor]

    # ── Gold pool: always archetype-driven (template), for both created and transformed tables.
    #    Gold quantity is an economic value tuned per archetype (and refined in the income pass), so
    #    it must match the validated Luck budget — only the thematic GEAR entries are carried over on
    #    a transform. Sourcing gold from the template also makes regeneration fully idempotent. ──
    gold_entries = [gold_entry(template["goldQuantityMin"], template["goldQuantityMax"])]

    table = {
        "Rolls": 1,
        "DropChance": 0.0 if is_swarm else template["gearDropChance"],
        "Entries": gear_entries,
        "GoldChance": template["goldChance"],
        "GoldEntries": gold_entries,
    }
    return table, ("transform" if existing is not None else "create")


def main() -> int:
    templates_meta = load_json(TEMPLATES_PATH)
    templates = templates_meta["templates"]
    archetypes = role_archetypes()

    conn = sqlite3.connect(DB_PATH)
    anchors = role_level_anchors(conn, set(archetypes))

    LOOT_DIR.mkdir(parents=True, exist_ok=True)

    created, transformed, skipped, errors = [], [], [], []
    for role, archetype in sorted(archetypes.items()):
        out_path = LOOT_DIR / f"{role}.json"
        if out_path.exists():
            existing = load_json(out_path)
            if existing.get("Authored") is True:
                skipped.append(role)
                continue
        else:
            existing = None

        template = templates.get(archetype)
        if template is None:
            errors.append(f"{role}: no template for archetype '{archetype}'")
            continue

        table, mode = build_table(role, archetype, template, templates_meta, conn, anchors, existing)
        if not table["Entries"] and not table["GoldEntries"]:
            errors.append(f"{role}: empty gear AND gold pool (archetype '{archetype}')")
            continue
        if archetype != "Swarm" and not table["Entries"]:
            errors.append(f"{role}: empty gear pool for non-swarm archetype '{archetype}' "
                          f"(no items in band around L{anchors.get(role)})")
            continue

        out_path.write_text(json.dumps(table, indent=2) + "\n")
        (transformed if mode == "transform" else created).append(role)

    conn.close()

    manifest = {
        "counts": {"created": len(created), "transformed": len(transformed),
                   "skipped_authored": len(skipped), "errors": len(errors)},
        "created": created,
        "transformed": transformed,
        "skipped_authored": skipped,
        "errors": errors,
    }
    MANIFEST_PATH.write_text(json.dumps(manifest, indent=2) + "\n")

    print(f"Loot table generation -> {LOOT_DIR.relative_to(ROOT)}")
    print(f"  manifest  : {MANIFEST_PATH.relative_to(ROOT)}")
    print(f"  created   : {len(created)}")
    print(f"  transformed: {len(transformed)}")
    print(f"  skipped (Authored): {len(skipped)} {skipped if skipped else ''}")
    if created:
        print("  new tables: " + ", ".join(created))
    if errors:
        print("\nERRORS (no file written):")
        for e in errors:
            print(f"  - {e}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
