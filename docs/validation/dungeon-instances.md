# Dungeon Instance Validation

Status: Current
Last verified: 2026-05-14
Source docs: DUNGEON_INSTANCE_PLAN.md, Validation.md
Verified against: src/main/java/com/duntale/dungeon/, src/main/java/com/duntale/command/DungeonCommand.java, src/main/java/com/duntale/command/PartyCommand.java, src/main/java/com/duntale/portal/DungeonEndPortalService.java, src/main/java/com/duntale/volume/DungeonInstancePortalTriggerService.java, src/main/java/com/duntale/death/, src/main/java/com/duntale/PlayerEntryService.java, src/main/resources/Server/GameplayConfigs/Dungeon.json, src/main/resources/Server/Configs/FloorConfig/*.json, src/test/java/com/duntale/dungeon/, src/test/java/com/duntale/portal/, src/test/java/com/duntale/volume/, src/test/java/com/duntale/death/, src/test/java/com/duntale/PlayerEntryServiceTest.java

## Purpose

Provide the current manual validation checklist and the exact automated test entry points for dungeon-instance behavior.

## Current State

- The canonical behavior description lives in [Dungeon Instances](../systems/dungeon-instances.md).
- Automated tests cover most service and helper logic: persistence, roster validation, startup recovery, transitions, cleanup, continue routing, party disconnect handling, floor-config resolution, portal parsing, and dungeon death services.
- Manual validation is still required for custom-page flow, TriggerVolume registration inside a live world, command output text, teleport timing, and camera handoff.

## Manual Validation

1. Verify party setup before a run.
   - Run `/party create`, `/party invite <player>`, and `/party list`.
   - Expect the owner and invited player to appear in the same roster. Disconnecting the owner should disband the party, and disconnecting a member should remove only that member.

2. Verify fresh run creation from the shared world.
   - Enter the authored shared-world TriggerVolume `dungeon_instance_portal` or run `/dungeon start` as a player.
   - If the player has no active run, expect the portal page to offer a fresh entry action. After creation finishes, expect `/dungeon list` to show an `ACTIVE` instance, a world named `dungeon-{uuid}`, and entrance and exit coordinates in `/dungeon info <id>`.

3. Verify roster handoff into the instance.
   - Start a run while in a party.
   - Expect every party member to land in the same dungeon world and `/dungeon player <uuid>` to resolve the same instance ID for all transferred players.

4. Verify continue and village routing.
   - Disconnect while in an active dungeon, reconnect, and use the entry page `Continue` action.
   - Expect teleport to the persisted entrance and a `Continuing your dungeon run...` message. If the run is `CREATING` or `TRANSITIONING`, expect a retry-later message instead. If the run is ended or its world is unavailable, expect fallback to the village.

5. Verify portal-based floor transition.
   - Use `/dungeon tpout` to move near the current floor exit, then walk into the dynamic end portal, or use `/dungeon transition <instanceId>` as an operator.
   - Expect a new world name ending in `-f2`, updated entrance and exit coordinates in `/dungeon info`, the full roster teleported to the new entrance, and the previous world scheduled for removal when empty.

6. Verify portal page behavior when an active run already exists.
   - Re-enter the authored shared-world dungeon portal while the player already has an active run.
   - Expect `Continue` to resume that run and `New Dungeon` to force-end the current run before starting a fresh floor-1 instance.

7. Verify end and cleanup behavior.
   - Run `/dungeon end <instanceId>`.
   - Expect the instance to become `ENDED`, online roster members still in the tracked dungeon world to evacuate to the village, and the ended run to stop resolving through `Continue`.

8. Verify dungeon death flow.
   - Die inside an active dungeon instance with enough gold for the current-floor option.
   - Expect `DungeonDeathPage` instead of the built-in death menu. The current-floor button should cost `floorLevel * 500` gold. No lower-floor restart button should be shown. The village action should end the run and route the player out.

9. Verify restart recovery.
   - Restart the server with at least one active dungeon instance.
   - Expect the active world and instance row to remain usable, the exit portal to be backfilled after world load, and `Continue` to resume the run. If you can force an interrupted creation or transition in a dev environment, expect startup recovery to end `CREATING` runs and revert `TRANSITIONING` runs to `ACTIVE`.

## Automated Validation

- Core lifecycle and recovery:

```bash
cd /home/gpmod/lab/duntale/v3-zsquad && ./gradlew test --tests "com.duntale.dungeon.DungeonInstanceServiceTest"
```

- Persistence repositories:

```bash
cd /home/gpmod/lab/duntale/v3-zsquad && ./gradlew test --tests "com.duntale.dungeon.DungeonInstanceRepositoryTest" --tests "com.duntale.dungeon.DungeonMembershipRepositoryTest"
```

- Party behavior and disconnect cleanup:

```bash
cd /home/gpmod/lab/duntale/v3-zsquad && ./gradlew test --tests "com.duntale.dungeon.PartyServiceTest"
```

- Floor-config layering and theme selection:

```bash
cd /home/gpmod/lab/duntale/v3-zsquad && ./gradlew test --tests "com.duntale.dungeon.FloorConfigServiceTest"
```

- Entry routing and fail-closed behavior:

```bash
cd /home/gpmod/lab/duntale/v3-zsquad && ./gradlew test --tests "com.duntale.PlayerEntryServiceTest"
```

- Shared-world and dynamic portal helpers:

```bash
cd /home/gpmod/lab/duntale/v3-zsquad && ./gradlew test --tests "com.duntale.volume.DungeonInstancePortalTriggerServiceTest" --tests "com.duntale.portal.DungeonEndPortalServiceTest"
```

- Dungeon death pricing and interception order:

```bash
cd /home/gpmod/lab/duntale/v3-zsquad && ./gradlew test --tests "com.duntale.death.DungeonRespawnServiceTest" --tests "com.duntale.death.DungeonDeathScreenSystemTest"
```

- Full targeted regression pass for all dungeon-instance-adjacent suites listed above:

```bash
cd /home/gpmod/lab/duntale/v3-zsquad && ./gradlew test \
  --tests "com.duntale.dungeon.DungeonInstanceServiceTest" \
  --tests "com.duntale.dungeon.DungeonInstanceRepositoryTest" \
  --tests "com.duntale.dungeon.DungeonMembershipRepositoryTest" \
  --tests "com.duntale.dungeon.PartyServiceTest" \
  --tests "com.duntale.dungeon.FloorConfigServiceTest" \
  --tests "com.duntale.PlayerEntryServiceTest" \
  --tests "com.duntale.volume.DungeonInstancePortalTriggerServiceTest" \
  --tests "com.duntale.portal.DungeonEndPortalServiceTest" \
  --tests "com.duntale.death.DungeonRespawnServiceTest" \
  --tests "com.duntale.death.DungeonDeathScreenSystemTest"
```

## Known Gaps

- There are no dedicated automated tests for `DungeonCommand`, `PartyCommand`, `DungeonEntryPage`, or `DungeonInstancePortalPage`, so manual validation still owns command text, page copy, and button wiring.
- Unit tests use fake runtime adapters for most lifecycle branches. Live server validation is still needed for engine-managed world removal, TriggerVolume registration timing, and camera or click-to-move handoff during teleports.

## Related Docs

- [Dungeon Instances](../systems/dungeon-instances.md)