# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Derive the Duntale rarity tuning (ladders, Luck promotion, attributes, prices, display).

Rarity is Duntale-owned and decoupled from the engine's cosmetic asset quality. This
script emits ``Rarity.json`` — the config asset consumed by ``RarityRegistry`` /
``RarityRollService`` / ``MerchantPriceRegistry`` / ``GearScalingTooltipProvider``.

The tuning is design-driven (no DB read): per-source base ladders skew rarer for
elites/bosses/premium chests; a two-step Luck promotion lifts player-context rolls;
rarity grants a small, level-scaled RPG-stat attribute budget; and a tight price
ladder and display palette round it out. The promotion probabilities and price
multipliers here are documented W4 drafts — final values are retuned later under the
luck power budget and the holistic combat-value repricing.

Run:  uv run derive_rarity.py
"""

from __future__ import annotations

import json
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
OUT_JSON = SCRIPT_DIR.parent.parent / "src/main/resources/Server/Configs/Scaling/Rarity.json"
DISCOVERY_DOC = SCRIPT_DIR.parent.parent / "docs/data-balancing/economy-discovery.md"

# ── Base ladders: source -> [(rarity, weight), ...] ─────────────────────────
# Higher-tier sources skew toward rarer base rolls before Luck promotion. Relic/Abyssal sit at the
# top of the ladder and are seeded only into the premium player-context sources, at a generosity of
# Boss (rare) > Merchant (very rare) > Chest_Legendary (super rare); Luck promotion adds more on top.
# Weights are relative WITHIN a ladder, so larger totals (Merchant/Chest) just buy sub-1% precision.
LADDERS: dict[str, list[tuple[str, int]]] = {
    "MOB":             [("Common", 70), ("Uncommon", 25), ("Rare", 5)],
    "ELITE":           [("Uncommon", 45), ("Rare", 40), ("Epic", 15)],
    # Boss mostly drops Epic/Legendary (70%), with a rare Relic (7%) / Abyssal (3%) chance.
    "BOSS":            [("Rare", 20), ("Epic", 45), ("Legendary", 25), ("Relic", 7), ("Abyssal", 3)],
    "CHEST_REGULAR":   [("Common", 35), ("Uncommon", 40), ("Rare", 25)],
    "CHEST_GOLDEN":    [("Uncommon", 35), ("Rare", 45), ("Epic", 20)],
    "CHEST_EPIC":      [("Rare", 30), ("Epic", 45), ("Legendary", 25)],
    # Super rare: Relic 0.4% / Abyssal 0.1% (per 1000) on top of the unchanged Rare/Epic/Legendary mix.
    "CHEST_LEGENDARY": [("Rare", 350), ("Epic", 400), ("Legendary", 245), ("Relic", 4), ("Abyssal", 1)],
    # Very rare: Relic 0.8% / Abyssal 0.2% (per 1000).
    "MERCHANT":        [("Common", 200), ("Uncommon", 440), ("Rare", 250), ("Epic", 80),
                        ("Legendary", 20), ("Relic", 8), ("Abyssal", 2)],
}

# ── Two-step Luck promotion (W4 draft; retuned under the luck budget in W5) ──
# Step 1 gate: p = BaseChance + LuckCoeff * (min(luck, LuckRef) / LuckRef) ** LuckExp
# Step 2: weighted tier jump (capped at Legendary). TierLuckShift biases higher jumps
# as Luck approaches its reference (0.0 = off in the draft).
PROMOTION = {
    "BaseChance": 0.05,
    "LuckCoeff": 0.10,
    "LuckExp": 1.3,
    "LuckRef": 50,
    "TierWeights": [(1, 80), (2, 15), (3, 5)],
    "TierLuckShift": 0.0,
}

# ── Rarity-granted attributes ───────────────────────────────────────────────
# Per rarity: (count_min, count_max, value_min@L1, value_max@L1). Each attribute is a DISTINCT
# eligible stat whose value is rolled INDEPENDENTLY in the rarity's range — so e.g. a Rare can roll
# "+1 Luck, +3 Strength", not two identical values. The range shifts up by a flat step:
# +1 to both ends every ATTR_VALUE_LEVEL_STEP levels (floor(level / step)). The min is shared across
# Common..Legendary at each level; Relic/Abyssal carry a +1 via their L1 anchor of 2. Common is 0-1
# attributes (it may roll none).
ATTR_PER_RARITY: list[tuple[str, int, int, int, int]] = [
    # rarity        count        value@L1
    ("Common",      0, 1,        1, 2),
    ("Uncommon",    1, 1,        1, 3),
    ("Rare",        2, 2,        1, 3),
    ("Epic",        3, 3,        1, 4),
    ("Legendary",   4, 5,        1, 4),
    ("Relic",       5, 6,        2, 5),
    ("Abyssal",     6, 7,        2, 6),
]
# LUCK is intentionally eligible: gear Luck raises effective Luck, which feeds drop chance and
# rarity promotion, so Luck gear compounds into loot quality (a deliberate, self-reinforcing build).
# This is bounded — promotion chance saturates at LuckRef, and Luck competes with the other stats per
# attribute slot — so it is a gentle gradient, not an exploit. All 7 RPG stats are eligible so the
# top rarities (Abyssal up to 7 attributes) can fill every distinct slot.
ELIGIBLE_STATS = ["STRENGTH", "SPEED", "AGILITY", "VITALITY", "STAMINA", "LUCK", "RESISTANCE"]
ATTR_VALUE_LEVEL_STEP = 15  # +1 to the value range every 15 gear levels

# ── Merchant price multipliers (a deliberately tight ladder) ────────────────
PRICE_MULTIPLIERS: list[tuple[str, float]] = [
    ("Common", 1.0),
    ("Uncommon", 1.15),
    ("Rare", 1.4),
    ("Epic", 1.9),
    ("Legendary", 3.0),
    ("Relic", 4.5),
    ("Abyssal", 7.0),
]

# ── Display palette (tooltip color + name) ──────────────────────────────────
# Relic/Abyssal colors match the third-party quality assets they map to (WansWonderWeapon's "Relic"
# #ff00d8 and ZetsMysticWeapons' "Abyssal" #c6193d), so our tooltip line and the engine chrome agree.
DISPLAY: list[tuple[str, str, str]] = [
    ("Common", "#AAAAAA", "Common"),
    ("Uncommon", "#55FF55", "Uncommon"),
    ("Rare", "#5599FF", "Rare"),
    ("Epic", "#AA55FF", "Epic"),
    ("Legendary", "#FF8800", "Legendary"),
    ("Relic", "#FF00D8", "Relic"),
    ("Abyssal", "#C6193D", "Abyssal"),
]

BREAKPOINT_LEVELS = [1, 15, 30, 45, 60, 80]


def attr_range(value_min: int, value_max: int, level: int) -> tuple[int, int]:
    """Level-scaled per-attribute value range (matches RarityRollService.attributeValueRange)."""
    shift = max(0, level) // ATTR_VALUE_LEVEL_STEP if ATTR_VALUE_LEVEL_STEP > 0 else 0
    lo = max(1, value_min + shift)
    hi = max(lo, value_max + shift)
    return lo, hi


def promotion_chance(luck: int) -> float:
    normalized = min(max(luck, 0), PROMOTION["LuckRef"]) / PROMOTION["LuckRef"]
    chance = PROMOTION["BaseChance"] + PROMOTION["LuckCoeff"] * normalized ** PROMOTION["LuckExp"]
    return min(1.0, max(0.0, chance))


def main() -> int:
    payload = {
        "Ladders": [
            {"Source": src, "Weights": [{"Rarity": r, "W": w} for r, w in weights]}
            for src, weights in LADDERS.items()
        ],
        "Promotion": {
            "BaseChance": PROMOTION["BaseChance"],
            "LuckCoeff": PROMOTION["LuckCoeff"],
            "LuckExp": PROMOTION["LuckExp"],
            "LuckRef": PROMOTION["LuckRef"],
            "TierWeights": [{"Tiers": t, "W": w} for t, w in PROMOTION["TierWeights"]],
            "TierLuckShift": PROMOTION["TierLuckShift"],
        },
        "Attributes": {
            "PerRarity": [
                {"Rarity": r, "Min": cmin, "Max": cmax, "ValueMin": vmin, "ValueMax": vmax}
                for r, cmin, cmax, vmin, vmax in ATTR_PER_RARITY
            ],
            "EligibleStats": ELIGIBLE_STATS,
            "ValueLevelStep": ATTR_VALUE_LEVEL_STEP,
        },
        "PriceMultipliers": [{"Rarity": r, "Mult": m} for r, m in PRICE_MULTIPLIERS],
        "Display": [{"Rarity": r, "Color": c, "Name": n} for r, c, n in DISPLAY],
    }

    OUT_JSON.parent.mkdir(parents=True, exist_ok=True)
    OUT_JSON.write_text(json.dumps(payload, indent=2) + "\n")

    # ── Console report ────────────────────────────────────────────────
    print(f"Wrote {OUT_JSON.relative_to(SCRIPT_DIR.parent.parent)}")
    print()
    print("Base rarity distribution per source (pre-promotion):")
    for src, weights in LADDERS.items():
        total = sum(w for _, w in weights)
        dist = "  ".join(f"{r}:{w / total:.0%}" for r, w in weights)
        print(f"  {src:<16} {dist}")
    print()
    print("Promotion gate chance across Luck breakpoints:")
    print("  " + "  ".join(f"L{lk}:{promotion_chance(lk):.0%}" for lk in [0, 10, 25, 50, 100]))
    print()
    print("Attribute spec (count @ per-attribute value range, rolled independently):")
    for r, cmin, cmax, vmin, vmax in ATTR_PER_RARITY:
        count = f"{cmin}" if cmin == cmax else f"{cmin}-{cmax}"
        bands = "  ".join(f"L{lvl}:{attr_range(vmin, vmax, lvl)[0]}-{attr_range(vmin, vmax, lvl)[1]}"
                          for lvl in BREAKPOINT_LEVELS)
        print(f"  {r:<11} x{count:<4} {bands}")
    print()
    print("Price multiplier ladder:")
    print("  " + "  ".join(f"{r}:x{m:g}" for r, m in PRICE_MULTIPLIERS))

    append_discovery_report()
    if DISCOVERY_DOC.exists():
        rel = DISCOVERY_DOC.relative_to(SCRIPT_DIR.parent.parent)
        print(f"\nAppended a '## Rarity derivation' report to {rel}")
        print("  NOTE: this append is NOT idempotent — re-running stacks duplicate sections. "
              "Trim older copies in the doc if you re-run.")
    return 0


def append_discovery_report() -> None:
    # NOTE: appends unconditionally (matches the sibling derive_* scripts). Re-running this script
    # accumulates duplicate "## Rarity derivation" sections; the runner is expected to trim stale
    # copies. Kept as a plain append for parity with derive_gear_curves.py rather than rewriting
    # the doc in place.
    if not DISCOVERY_DOC.parent.exists():
        return
    lines = ["", "## Rarity derivation (derive_rarity.py)", ""]
    lines.append("Base rarity distribution per source (pre-promotion):")
    lines.append("")
    for src, weights in LADDERS.items():
        total = sum(w for _, w in weights)
        dist = ", ".join(f"{r} {w / total:.0%}" for r, w in weights)
        lines.append(f"- `{src}`: {dist}")
    lines.append("")
    lines.append("Promotion gate chance: "
                 + ", ".join(f"L{lk} {promotion_chance(lk):.0%}" for lk in [0, 10, 25, 50, 100]))
    lines.append("")
    lines.append("Attribute spec (count @ per-attribute value range, rolled independently):")
    lines.append("")
    for r, cmin, cmax, vmin, vmax in ATTR_PER_RARITY:
        count = f"{cmin}" if cmin == cmax else f"{cmin}-{cmax}"
        bands = ", ".join(f"L{lvl} {attr_range(vmin, vmax, lvl)[0]}-{attr_range(vmin, vmax, lvl)[1]}"
                          for lvl in BREAKPOINT_LEVELS)
        lines.append(f"- `{r}` ×{count}: {bands}")
    lines.append("")
    lines.append("Price multipliers: "
                 + ", ".join(f"{r} x{m:g}" for r, m in PRICE_MULTIPLIERS))
    lines.append("")
    with DISCOVERY_DOC.open("a") as fh:
        fh.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    raise SystemExit(main())
