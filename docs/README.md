# Duntale Documentation

Status: Current
Last verified: 2026-05-14
Source docs: root-level Markdown inventory from the Duntale module
Verified against: repository file inventory and source tree shape

## Purpose

This directory contains the cleaned, canonical documentation for the Duntale project. Root-level Markdown files are treated as legacy source material and are archived under `docs/archive/legacy-root-md/` after their current claims have been extracted or retired.

## Documentation Map

- `architecture/` - current project architecture and module ownership.
- `systems/` - current behavior for implemented gameplay and runtime systems.
- `data-balancing/` - loot, NPC, music, and content-balancing references.
- `validation/` - manual validation checklists and test entry points.
- `plans/` - active roadmaps, refactor plans, and explicitly future work.
- `research/` - historical research that is useful but not canonical current behavior.
- `archive/` - legacy root-level Markdown files preserved after migration.
- `_migration/` - cleanup inventory, claim ownership, and rewrite coordination notes.

## Standard Format

Every canonical document should start with this metadata block:

```markdown
# Title

Status: Current | Active Plan | Historical
Last verified: YYYY-MM-DD
Source docs: source file list
Verified against: code, resources, config, schema, tests, or "Not verified"
```

Current system docs should then use these sections unless a section is genuinely irrelevant:

```markdown
## Purpose
## Current State
## Implementation Map
## Data, Assets, And Config
## Validation
## Known Gaps
## Related Docs
```

Plans should use `Purpose`, `Current Priority`, `Scope`, `Tasks`, `Dependencies`, `Risks`, and `Related Docs`.

Research/history docs should use `Purpose`, `Historical Context`, `Findings`, `Still Relevant`, `Superseded Or Retired`, and `Related Docs`.

## Verification Rules

- Treat Java source, assets, configs, scripts, schemas, and tests as source of truth.
- Do not preserve a current-behavior claim unless it has repository evidence.
- Move unverified or outdated claims into a historical or open-question section.
- Feature docs own behavior. Validation docs own how to test behavior. Roadmap docs own future work.
- Avoid copying the same explanation into multiple docs; link to the canonical owner instead.