# Duntale Documentation

## About

Duntale is a Hytale dungeon-crawling RPG focused on repeatable runs that start in a shared village and branch into procedurally generated dungeon floors. Players choose a companion, head out solo or in parties of up to six, fight floor-scaled enemies, collect loot, earn gold and XP, spend stat points, trade with dungeon merchants, and push deeper floors through a persistent run and re-entry loop.

## Installation instructions

To run Duntale in Hytale, install these runtime dependencies in your client Mods folder:

- [Duntale.jar](https://github.com/ger4d/duntale/releases) from the latest Duntale release.
- [DungeonGen.jar](https://github.com/ger4d/hy-dungeon-generator/releases/) from the latest DungeonGen release.
- [DynamicTooltipsLib.jar](https://www.curseforge.com/hytale/mods/dynamictooltipslib).

Install them with this flow:

1. Open the Hytale launcher and set the patchline to `pre-release`.
2. Update Hytale to the latest available `pre-release` build.
3. Open the Hytale Mods folder.
4. On Windows, the default path is `C:\Users\YourUser\AppData\Roaming\Hytale\data\pre-release\Mods` or `%appdata%\Hytale\data\pre-release\Mods`.
5. Copy the latest `Duntale.jar`, `DungeonGen.jar`, and `DynamicTooltipsLib.jar` into that Mods folder.
6. Restart the Hytale client.
7. Create a new Creative world.
8. Before confirming world creation, click the settings gear, open the Mods tab, and enable the Duntale mod.
9. Apply the settings and finish creating the world.

When Duntale is enabled, Hytale should automatically enable DungeonGen and DynamicTooltipsLib because they are declared as dependencies.

This directory is the canonical reference for how the project currently works. It covers the shipped runtime systems, asset and config ownership, validation checklists, balancing references, and active plans. Older root-level Markdown files are treated as legacy source material and are archived under `docs/archive/legacy-root-md/` after their current claims have been extracted or retired.

## Documentation Map

- `architecture/` - current project architecture and module ownership.
- `systems/` - current behavior for implemented gameplay and runtime systems.
- `data-balancing/` - loot, NPC, music, and content-balancing references.
- `validation/` - manual validation checklists and test entry points.
- `plans/` - active roadmaps, refactor plans, and explicitly future work.
- `research/` - historical research that is useful but not canonical current behavior.