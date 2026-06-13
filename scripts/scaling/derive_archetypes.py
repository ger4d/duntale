# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Derive NPC archetype anchors + per-role mapping.

Normalizes enemy NPC stats: instead of each role keeping its wildly non-uniform
authored HP/damage, every role maps to one of six shared archetype anchors plus a
small flavor offset, so same-archetype enemies stay comparable.

Reads ``scaling.db`` (monsters_base ⋈ spawn_roster ⋈ summon_edges + the runtime
summon allowlist) and emits ``NpcArchetypes.json`` — the config asset consumed by
``NpcArchetypeRegistry`` / ``NpcScalingApplicator``.

Method:
  1. Classify each role into one of six archetypes (Swarm / Standard / Caster /
     Tough / Heavy / Boss) by a documented heuristic (dedicated-boss variant,
     caster keyword / ranged signal, then HP band). Written for manual review.
  2. Derive each anchor as the spawn-weight-weighted MEDIAN of its member roles'
     parsed stats — a power-neutral introduction (the median role is unchanged).
     A hand-tuned DRAFT table is printed alongside as a sanity reference.
  3. Per-role flavor offset = clamp(roleStat / anchor - 1, ±0.15); the clamp keeps
     outliers from defeating the normalization. AssetBaseDamage is the role's parsed
     average attack damage (fallback 5.0 when absent), used by the runtime corrective
     damage ratio to retarget the role's average damage to its anchor.

Run:  uv run derive_archetypes.py
"""

from __future__ import annotations

import json
import sqlite3
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
DB_PATH = SCRIPT_DIR / "scaling.db"
OUT_JSON = SCRIPT_DIR.parent.parent / "src/main/resources/Server/Configs/Scaling/NpcArchetypes.json"
DISCOVERY_DOC = SCRIPT_DIR.parent.parent / "docs/data-balancing/economy-discovery.md"

MAX_OFFSET = 0.15
DEFAULT_ASSET_DAMAGE = 5.0

# Order matters: anchors are emitted in this order.
ARCHETYPES = ["Swarm", "Standard", "Caster", "Tough", "Heavy", "Boss"]

# Hand-tuned DRAFT anchors (base HP / base damage at level 1). Sanity reference only —
# the emitted anchors are derived from the live roster below, not from this table.
DRAFT_ANCHORS = {
    "Swarm": (14, 4.0),
    "Standard": (45, 9.0),
    "Caster": (38, 12.0),
    "Tough": (95, 14.0),
    "Heavy": (190, 20.0),
    "Boss": (320, 26.0),
}

# Runtime summon allowlist (BuiltInNpcSpawnScalingSystem.ALLOWLISTED_SPECIAL_ROLES)
# plus summon_edges targets. These reach the world outside LeveledNpcSpawner.
SUMMON_ROLES = [
    "Skeleton", "Scarak_Louse",
    "Wolf_Outlander_Sorcerer", "Wolf_Outlander_Priest",
    "Wolf_Trork_Shaman", "Wolf_Trork_Hunter", "Wolf_Wife", "Wolf_Black",
]

# Fallback stats for allowlisted roles absent from scaling.db (e.g. Wolf_Wife, a
# cosmetic sibling of the Wolf_Black/Wolf_White companion-wolves).
MISSING_ROLE_FALLBACK = {
    "Wolf_Wife": {"base_hp": 103, "base_damage": 18.5, "attack_distance": 2.5},
}

CASTER_KEYWORDS = ("Mage", "Shaman", "Sorcerer", "Priest", "Witch", "Lobber",
                   "Archer", "Spirit", "Spectre", "Alchemist")
RANGED_ATTACK_DISTANCE = 10.0


def classify(role: str, hp: int, attack_distance: float | None, is_dedicated_boss: bool) -> str:
    """Heuristic archetype assignment (initial, for manual review)."""
    if is_dedicated_boss:
        return "Boss"
    if any(k in role for k in CASTER_KEYWORDS):
        return "Caster"
    if attack_distance is not None and attack_distance >= RANGED_ATTACK_DISTANCE:
        return "Caster"
    if hp <= 30:
        return "Swarm"
    if hp <= 75:
        return "Standard"
    if hp <= 150:
        return "Tough"
    return "Heavy"


def weighted_median(samples: list[tuple[float, float]]) -> float:
    """Weighted median of (value, weight) pairs."""
    if not samples:
        return 0.0
    ordered = sorted(samples, key=lambda s: s[0])
    total = sum(w for _, w in ordered)
    acc = 0.0
    for value, weight in ordered:
        acc += weight
        if acc >= total / 2.0:
            return value
    return ordered[-1][0]


def main() -> int:
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row

    # Spawn weight per role: summed across themes (a role can appear in several).
    weights: dict[str, float] = {}
    variants: dict[str, set[str]] = {}
    for row in conn.execute("SELECT role, weight, variants FROM spawn_roster"):
        weights[row["role"]] = weights.get(row["role"], 0.0) + float(row["weight"] or 0.0)
        variants.setdefault(row["role"], set()).update(
            v.strip() for v in (row["variants"] or "").split(",") if v.strip())

    roster_roles = sorted(weights.keys())
    all_roles = list(dict.fromkeys(roster_roles + SUMMON_ROLES))

    # Parsed base stats.
    def base_stats(role: str) -> dict | None:
        r = conn.execute(
            "SELECT base_hp, base_damage, attack_distance FROM monsters_base WHERE npc_id = ?",
            (role,)).fetchone()
        if r is not None:
            return {"base_hp": int(r["base_hp"] or 0),
                    "base_damage": float(r["base_damage"] or 0.0),
                    "attack_distance": r["attack_distance"]}
        return MISSING_ROLE_FALLBACK.get(role)

    roles: dict[str, dict] = {}
    skipped: list[str] = []
    for role in all_roles:
        stats = base_stats(role)
        if stats is None or stats["base_hp"] <= 0:
            skipped.append(role)
            continue
        vset = variants.get(role, set())
        # Dedicated boss: only ever spawns as a boss (no NORMAL roster variant).
        is_dedicated_boss = bool(vset) and "NORMAL" not in vset
        archetype = classify(role, stats["base_hp"], stats["attack_distance"], is_dedicated_boss)
        roles[role] = {
            "archetype": archetype,
            "base_hp": stats["base_hp"],
            "base_damage": stats["base_damage"],
            "weight": weights.get(role, 1.0) or 1.0,
        }

    # Derive anchors as weight-weighted medians of each archetype's members.
    anchors: dict[str, tuple[int, float]] = {}
    for arch in ARCHETYPES:
        members = [r for r in roles.values() if r["archetype"] == arch]
        hp_med = weighted_median([(r["base_hp"], r["weight"]) for r in members])
        dmg_samples = [(r["base_damage"], r["weight"]) for r in members if r["base_damage"] > 0]
        dmg_med = weighted_median(dmg_samples)
        if not members:  # no members classified — fall back to the draft anchor
            hp_med, dmg_med = DRAFT_ANCHORS[arch]
        anchors[arch] = (max(1, round(hp_med)), round(dmg_med or DEFAULT_ASSET_DAMAGE, 1))

    # Per-role offsets (clamped) and asset base damage.
    role_entries = []
    clamped_count = 0
    for role in sorted(roles.keys()):
        info = roles[role]
        anchor_hp, anchor_dmg = anchors[info["archetype"]]
        asset_dmg = info["base_damage"] if info["base_damage"] > 0 else DEFAULT_ASSET_DAMAGE

        raw_hp_off = info["base_hp"] / anchor_hp - 1.0
        raw_dmg_off = asset_dmg / anchor_dmg - 1.0
        hp_off = max(-MAX_OFFSET, min(MAX_OFFSET, raw_hp_off))
        dmg_off = max(-MAX_OFFSET, min(MAX_OFFSET, raw_dmg_off))
        if abs(raw_hp_off) > MAX_OFFSET or abs(raw_dmg_off) > MAX_OFFSET:
            clamped_count += 1

        role_entries.append({
            "Role": role,
            "Archetype": info["archetype"],
            "HpOffset": round(hp_off, 3),
            "DamageOffset": round(dmg_off, 3),
            "AssetBaseDamage": round(asset_dmg, 1),
        })

    payload = {
        "Archetypes": [
            {"Name": arch, "BaseHp": anchors[arch][0], "BaseDamage": anchors[arch][1]}
            for arch in ARCHETYPES
        ],
        "Roles": role_entries,
    }

    OUT_JSON.parent.mkdir(parents=True, exist_ok=True)
    OUT_JSON.write_text(json.dumps(payload, indent=2) + "\n")

    # ── Console report ────────────────────────────────────────────────
    print(f"Wrote {OUT_JSON.relative_to(SCRIPT_DIR.parent.parent)}")
    print(f"Roles mapped: {len(role_entries)}  (skipped, no base stats: {skipped or 'none'})")
    print(f"Roles with a clamped offset (|raw| > {MAX_OFFSET:.0%}): {clamped_count}")
    print()
    print(f"{'Archetype':<10} {'DerivedHp':>9} {'DraftHp':>8} {'DerivedDmg':>11} {'DraftDmg':>9} {'Members':>8}")
    for arch in ARCHETYPES:
        members = sum(1 for r in roles.values() if r["archetype"] == arch)
        dh, dd = anchors[arch]
        ph, pd = DRAFT_ANCHORS[arch]
        print(f"{arch:<10} {dh:>9} {ph:>8} {dd:>11.1f} {pd:>9.1f} {members:>8}")

    append_discovery_report(anchors, roles, role_entries, clamped_count, skipped)
    print(f"\nAppended derivation report to {DISCOVERY_DOC.relative_to(SCRIPT_DIR.parent.parent)}")
    return 0


def append_discovery_report(anchors, roles, role_entries, clamped_count, skipped) -> None:
    lines = ["", "## NPC Archetype Anchors (Economy v2, W2 — derived)", ""]
    lines.append("Generated by `scripts/scaling/derive_archetypes.py`. Anchors are spawn-weight-")
    lines.append("weighted medians of each archetype's member roles (power-neutral); offsets are")
    lines.append(f"clamped to +/-{MAX_OFFSET:.0%} (pillar P4). {clamped_count} of {len(role_entries)} roles hit the clamp.")
    if skipped:
        lines.append(f"Allowlisted roles absent from scaling.db (skipped): {', '.join(skipped)}.")
    lines.append("")
    lines.append("| Archetype | Derived HP | Draft HP | Derived Dmg | Draft Dmg | Members |")
    lines.append("|---|---|---|---|---|---|")
    for arch in ARCHETYPES:
        members = sum(1 for r in roles.values() if r["archetype"] == arch)
        dh, dd = anchors[arch]
        ph, pd = DRAFT_ANCHORS[arch]
        lines.append(f"| {arch} | {dh} | {ph} | {dd:.1f} | {pd:.1f} | {members} |")
    lines.append("")
    lines.append("Role membership (for manual review):")
    lines.append("")
    for arch in ARCHETYPES:
        members = sorted(r["Role"] for r in role_entries if r["Archetype"] == arch)
        lines.append(f"- **{arch}**: {', '.join(members)}")
    lines.append("")
    with DISCOVERY_DOC.open("a") as fh:
        fh.write("\n".join(lines))


if __name__ == "__main__":
    raise SystemExit(main())
