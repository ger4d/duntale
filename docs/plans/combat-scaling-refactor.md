# Combat Scaling Refactor

Status: Active Plan
Last verified: 2026-05-14
Source docs: COMBAT_SCALE_REFACTOR_PLAN.md, DUNGEON_SCALING_SYSTEM.md
Verified against: src/main/java/com/duntale/progression/, src/main/java/com/duntale/companion/, src/main/java/com/duntale/spawner/, src/main/java/com/duntale/loot/, src/main/java/com/duntale/rpg/, scripts/scaling/, scripts/scaling/scaling.db, src/main/resources/Server/Configs/LootTables/*.json, src/test/java/com/duntale/loot/

## Purpose

Track the remaining cleanup around combat scaling after the major runtime rewrite already landed. This plan owns future alignment, testing, and behavior clarification work, not the current live system description.

## Current Priority

- Treat the Java runtime as the canonical source of combat behavior today.
- Before changing formulas again, add characterization tests around the current damage order, spawn-level selection, and companion scaling.
- Resolve the mismatch between the offline `scripts/scaling` dataset and the current Java runtime before using `scaling.db` as a tuning reference again.
- Do not re-plan already landed work. The following legacy plan items are already in source and are not active tasks now: runtime DB removal from the combat path, `CombatScalingComponent` replacing `NpcLevelRegistry`, companion-specific spawn scaling, and direct item-asset armor-resistance reads.

## Scope

- In scope: formula and system-order test coverage, runtime and offline pipeline alignment, companion friendly-fire policy, and tooling or documentation changes needed to keep scaling ownership clear.
- Out of scope: dungeon layout retuning in `FloorConfig`, new loot-table content authoring, new NPC or gear assets, and broad economy or merchant rebalance work.

## Tasks

| ID | Priority | Status | Current evidence | Task |
|---|---|---|---|---|
| CSR-001 | P0 | Ready | `CombatScaling.java` is runtime-authoritative, but `generate_scaling.py` still writes boss-specific `k` values and precomputed elite columns into `scaling.db`. | Decide whether `scripts/scaling` is a maintained parity dataset or an analysis-only toolchain. If parity is required, update the generator and metadata to match live Java formulas. If not, relabel the pipeline and docs so it stops implying runtime equivalence. |
| CSR-002 | P0 | Ready | No dedicated test files were found for `CombatScaling`, `CombatScalingSystem`, `LeveledNpcSpawner`, `CompanionSpawner`, `CompanionService` scaling updates, `SpawnerTickSystem`, or `RpgDamageScalingSystem`. | Add characterization tests for formula outputs, variant multipliers, item metadata scaling, player-versus-NPC and NPC-versus-player damage ordering, companion scaling updates, and spawner level clamping and variant mapping. |
| CSR-003 | P1 | Ready | `CombatScalingSystem` skips scaling when a player hits a companion, but it does not cancel the hit. | Make the player-to-companion damage rule explicit. Either keep the current base-damage behavior and test it, or block companion friendly fire in the combat layer. |
| CSR-004 | P1 | Ready | The repository contains a populated `scaling.db` snapshot and script metadata, but there is no automated drift check against runtime formulas. | Add a lightweight consistency check or exported sample report so balancing data can detect drift between Java formulas and the offline dataset without manual inspection. |

## Dependencies

- CSR-002 should land before CSR-001 formula changes so current behavior is captured before the pipeline is updated.
- CSR-001 should land before any future balance pass that cites `scaling.db` as evidence.
- CSR-003 can be characterized first, but a gameplay decision is needed before changing behavior.
- CSR-004 becomes easier once CSR-001 establishes whether the offline pipeline is parity-critical or analysis-only.

## Risks

- Syncing the offline pipeline to runtime formulas can silently change balance assumptions used in past notes or spreadsheets.
- Combat order is layered today: base combat scaling, then RPG Strength and Resistance, then loot and XP side effects. Changes without tests risk subtle regressions.
- Companion damage-policy changes affect both solo progression feel and accidental pet kills, so they should not be changed without explicit coverage and validation.

## Related Docs

- [Combat Scaling](../systems/combat-scaling.md)
- [Scaling Data Pipeline](../data-balancing/scaling-data-pipeline.md)