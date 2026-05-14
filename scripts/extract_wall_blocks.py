#!/usr/bin/env python3
"""
Extract structural (wall/ceiling) block asset keys from dungeon-gen theme files.

These are the block types that the BlockOcclusionManager should target for
replacement — blocks that form walls, ceilings, and pillars that can occlude
the player from overhead/isometric camera views. Decorative blocks (torches,
furniture, plants, rubble, fluids, etc.) are excluded.

Usage:
    python3 scripts/extract_wall_blocks.py

Reads:  ../dungeon-gen/assets/Server/Configs/DungeonGen/Themes/*.json
Prints: Sorted, deduplicated list of structural block asset keys.
"""

import json
import os
import sys
from pathlib import Path

# Palette keys whose blocks form structural elements above the player.
# These are the keys that produce walls, ceilings, pillars — things that
# would visually block the player from a top-down or isometric camera.
#
# Excluded keys and rationale:
#   Floor           — below the player, never occludes from above
#   OvergrowthBlocks— thin/transparent vines & moss, visually non-occluding
#   RubbleBlocks    — floor-level debris at Y=1
#   FluidBlock      — floor-level pools
#   SecondaryFluidBlock — floor-level lava/pools
#
# Included even if NOT currently placed by the generator (Stairs, Slab,
# AccentBlock) for forward-compatibility.
STRUCTURAL_PALETTE_KEYS = [
    "PrimaryWall",
    "SecondaryWall",
    "Ceiling",
    "PillarBase",
    "PillarMiddle",
    "Stairs",
    "Slab",
    "DecayVariants",   # structural replacements for walls/ceilings
    "AccentBlock",
]


def extract_structural_blocks(themes_dir: Path) -> set[str]:
    """Parse all theme JSON files and collect structural block asset keys."""
    blocks: set[str] = set()

    json_files = sorted(themes_dir.glob("*.json"))
    if not json_files:
        print(f"ERROR: No .json files found in {themes_dir}", file=sys.stderr)
        sys.exit(1)

    for theme_path in json_files:
        with open(theme_path, "r") as f:
            theme = json.load(f)

        palette = theme.get("Palette", {})
        theme_name = theme_path.stem

        for key in STRUCTURAL_PALETTE_KEYS:
            value = palette.get(key)
            if value is None:
                continue

            if isinstance(value, str):
                blocks.add(value)
            elif isinstance(value, list):
                for item in value:
                    if isinstance(item, str):
                        blocks.add(item)

        # Log per-theme extraction
        theme_blocks = set()
        for key in STRUCTURAL_PALETTE_KEYS:
            value = palette.get(key)
            if value is None:
                continue
            if isinstance(value, str):
                theme_blocks.add(value)
            elif isinstance(value, list):
                theme_blocks.update(v for v in value if isinstance(v, str))
        print(f"  {theme_name}: {len(theme_blocks)} structural block types")

    return blocks


def main():
    # Resolve paths relative to this script
    script_dir = Path(__file__).resolve().parent
    # Navigate from this module's scripts directory to dungeon-gen/assets/...
    themes_dir = script_dir.parent.parent / "dungeon-gen" / "assets" / "Server" / "Configs" / "DungeonGen" / "Themes"

    if not themes_dir.is_dir():
        print(f"ERROR: Themes directory not found: {themes_dir}", file=sys.stderr)
        sys.exit(1)

    print(f"Reading themes from: {themes_dir}")
    print()

    blocks = extract_structural_blocks(themes_dir)
    sorted_blocks = sorted(blocks)

    print()
    print(f"=== {len(sorted_blocks)} unique structural block asset keys ===")
    print()
    for block in sorted_blocks:
        print(f"  {block}")

    # Output as a Java-ready Set.of() snippet
    print()
    print("=== Java Set.of() snippet ===")
    print()
    print("private static final Set<String> WALL_BLOCK_KEYS = Set.of(")
    for i, block in enumerate(sorted_blocks):
        comma = "," if i < len(sorted_blocks) - 1 else ""
        print(f'        "{block}"{comma}')
    print(");")


if __name__ == "__main__":
    main()
