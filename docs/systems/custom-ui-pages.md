# Custom UI Pages

Status: Current
Last verified: 2026-05-14
Source docs: CUSTOM_UI_PAGES_BRIEF.md
Verified against: src/main/java/com/duntale/CustomizeCharacterPage.java, src/main/java/com/duntale/CustomizeCharacterService.java, src/main/java/com/duntale/CustomizeCharacterConfig.java, src/main/java/com/duntale/PlayerEntryService.java, src/main/java/com/duntale/DungeonEntryPage.java, src/main/java/com/duntale/DungeonInstancePortalPage.java, src/main/java/com/duntale/DuntalePlugin.java, src/main/java/com/duntale/death/DungeonDeathPage.java, src/main/java/com/duntale/death/DungeonDeathScreenSystem.java, src/main/java/com/duntale/death/DungeonRespawnService.java, src/main/java/com/duntale/rpg/StatAssignmentPage.java, src/main/java/com/duntale/rpg/StatAssignCommand.java, src/main/java/com/duntale/volume/DungeonInstancePortalTriggerService.java, src/main/resources/Common/UI/Custom/Pages/Entry/CustomizeCharacterPage.ui, src/main/resources/Common/UI/Custom/Pages/Entry/DungeonEntryPage.ui, src/main/resources/Common/UI/Custom/Pages/Entry/DungeonInstancePortalPage.ui, src/main/resources/Common/UI/Custom/Pages/StatAssignment/StatAssignmentPage.ui, src/main/resources/Server/Configs/EntryFlow/CustomizeCharacter.json, src/test/java/com/duntale/CustomizeCharacterServiceTest.java, src/test/java/com/duntale/PlayerEntryServiceTest.java, src/test/java/com/duntale/dungeon/DungeonInstanceServiceTest.java, src/test/java/com/duntale/death/DungeonDeathScreenSystemTest.java, src/test/java/com/duntale/death/DungeonRespawnServiceTest.java, src/test/java/com/duntale/volume/DungeonInstancePortalTriggerServiceTest.java

## Purpose

Document the player-facing custom UI pages that are wired to runtime code today, the states each page actually supports, and the assets or config each page depends on. This doc records shipped behavior only and retires brief-only states that are not backed by current code or resources.

## Current State

- Within the player-facing scope, the repo currently ships four custom page assets under `src/main/resources/Common/UI/Custom/Pages`: companion setup, dungeon entry, dungeon portal, and stat assignment.
- `DungeonDeathPage` is wired in Java and reopened on dungeon-death retries, but the referenced `Pages/Death/DungeonDeathPage.ui` asset is not present under `src/main/resources/Common/UI/Custom`.
- Entry and portal pages are opened by `DuntalePlugin` from either `PlayerEntryService` or the authored village TriggerVolume. Stat assignment is command-driven through `/assignstats`.
- Most failure feedback is currently delivered through chat messages and routing, not through separate alternate page layouts.

| Page | Open trigger | Lifetime | Asset status |
| --- | --- | --- | --- |
| `CustomizeCharacterPage` | Shared-world join when the player has no stored companion preference | `CantClose` | `Pages/Entry/CustomizeCharacterPage.ui` present |
| `DungeonEntryPage` | Shared-world join when the player has a stored preference and an active dungeon instance | `CantClose` | `Pages/Entry/DungeonEntryPage.ui` present |
| `DungeonInstancePortalPage` | Entering authored TriggerVolume `dungeon_instance_portal` in the village | `CanDismiss` | `Pages/Entry/DungeonInstancePortalPage.ui` present |
| `StatAssignmentPage` | `/assignstats` when the player has unassigned stat points | `CanDismiss` | `Pages/StatAssignment/StatAssignmentPage.ui` present |
| `DungeonDeathPage` | Dungeon death interception for active-instance deaths in the matching dungeon world | `CantClose` | `Pages/Death/DungeonDeathPage.ui` missing |

### Companion Setup Page

- `PlayerEntryService.resolve(...)` routes players without a stored companion preference to `CUSTOMIZE_CHARACTER`, and `DuntalePlugin.onPlayerReady(...)` opens `CustomizeCharacterPage` only after `CustomizeCharacterService.start(...)` has staged the player in the shared world.
- On page start, the service disables click-to-move, teleports the player to a setup slot, spawns a world-space preview companion, and applies a fixed server camera. If no setup slots are configured it falls back to world spawn and warns the player in chat.
- The current UI asset contains one name field, a static `Wolf` label, and a single confirm button. The companion choice is locked to `Companion_Wolf_Black`.
- The live preview is world-space rather than embedded in the page asset. `ValueChanged` events from `#CompanionName` update the preview companion nameplate through `CustomizeCharacterService.updatePreviewName(...)`.
- Confirm accepts only trimmed names of `1-24` characters using letters, digits, spaces, underscores, apostrophes, or hyphens. Invalid names, missing NPC role data, and persistence failures surface as chat messages rather than inline UI validation.
- On success, the service persists the companion preference, removes the preview companion, resets the custom camera, closes the page, and spawns the saved companion into the village flow.

### Dungeon Entry Page

- `PlayerEntryService.resolve(...)` routes players with both a stored companion preference and a non-ended active instance to `DUNGEON_ENTRY`, and `DuntalePlugin.onPlayerReady(...)` opens `DungeonEntryPage` in the shared world.
- The asset is a fixed two-choice page with `Continue` and `Village` buttons. It does not render floor-specific or pending-state variants inside the page itself.
- `Continue` calls `DungeonInstanceService.resolveContinueRoute(...)`. When the route targets an `ACTIVE` instance, the page closes and the player is teleported to that instance's persisted entrance position.
- If the active instance is still `CREATING` or `TRANSITIONING`, the page stays open and the player receives a retry-later chat message. If no instance can be resumed, or the target world is unavailable, the player is routed back to the village.
- `Village` always closes the page, restores built-in controls, teleports the player to the shared-world spawn, and sends an `Entering village.` message.

### Dungeon Portal Page

- `DungeonInstancePortalTriggerService` matches only exact `ENTER` events for the authored TriggerVolume id `dungeon_instance_portal`, and `DuntalePlugin.onDungeonInstancePortalTrigger(...)` opens `DungeonInstancePortalPage` when the player is not already viewing another custom page.
- The page has two shipped content states inside one asset. `PortalMode.NO_INSTANCE` shows `Enter` plus `Cancel`. `PortalMode.EXISTING_INSTANCE` shows `Continue`, `New Dungeon`, and `Cancel`.
- In `NO_INSTANCE`, the prompt text states that the player is about to start a fresh floor-1 run and the state label reads `No active dungeon run`.
- In `EXISTING_INSTANCE`, the prompt text switches to saved-progress messaging and the state label shows `Current Floor N`. If the page is constructed without an instance snapshot, the label falls back to `Current run: unavailable`.
- `Enter` closes the page and starts a new dungeon instance for `DUNGEON_START_FLOOR`, which is currently `1`.
- `Continue` delegates to the same resume handler as `DungeonEntryPage`, including the same pending, unavailable-world, and village-fallback behavior.
- `New Dungeon` closes the page, force-ends the current active instance if one exists, and then starts a fresh floor-1 run. Failures are reported through chat after the page closes.
- `Cancel` just closes the page.

### Stat Assignment Page

- `StatAssignCommand` is the only verified open path for `StatAssignmentPage`. The page opens only when `/assignstats` is used and the player has at least one unassigned stat point.
- The asset shows `Available Points` plus one row each for Strength, Speed, Agility, Resistance, Luck, Vitality, and Stamina.
- `StatAssignmentPage.build(...)` populates each stat value from `RpgService.getProfile(...)` and binds each `+` button to an `AssignStat` event.
- The button bindings are intentionally unlocked by passing `false` to `addEventBinding(...)`, so rapid repeated clicks are allowed.
- On success, the page refreshes all displayed values in place and the player gets a chat message showing the assigned stat and remaining point count.
- On failure, the page stays open and the player receives either `No stat points available.` or an `already at max` message. No verified NPC interaction currently opens this page.

### Dungeon Death Page

- `DungeonDeathScreenSystem` runs before the built-in death screen and opens `DungeonDeathPage` only when `DungeonRespawnService.resolveContext(...)` finds an `ACTIVE` instance in the same world where the death occurred.
- The authored page asset is present in source at `src/main/resources/Common/UI/Custom/Pages/Death/DungeonDeathPage.ui`. `build(...)` currently fills the death reason or fallback message, current floor label, current gold balance, and current-floor cost.
- The current-floor button disables when `balance < currentFloorCost`.
- `DungeonRespawnService` still calculates `floorLevel * 500` gold for current-floor respawn and `floorLevel * 300` gold for lower-floor restart, but the authored page only exposes the current-floor and village actions.
- Any button press immediately disables the visible actions before delegating to the plugin handler.
- `Current Floor` respawns the player into the same active instance after charging gold. Failures refund the charge and reopen the death page when the death component is still present.
- `Village` is free. It respawns the player, waits for respawn teleport state to settle, force-ends the dungeon instance when one is still available, and routes the player to the village. Failures reopen the page when possible.

## Implementation Map

- `src/main/java/com/duntale/PlayerEntryService.java` decides whether shared-world join opens customization, dungeon entry, or no page.
- `src/main/java/com/duntale/CustomizeCharacterPage.java` and `src/main/java/com/duntale/CustomizeCharacterService.java` own companion setup UI events, setup-slot staging, preview companion management, validation, and completion.
- `src/main/java/com/duntale/DungeonEntryPage.java` owns the join-time resume or village choice UI.
- `src/main/java/com/duntale/DungeonInstancePortalPage.java` plus `src/main/java/com/duntale/volume/DungeonInstancePortalTriggerService.java` own the village portal prompt and its two content states.
- `src/main/java/com/duntale/rpg/StatAssignCommand.java` and `src/main/java/com/duntale/rpg/StatAssignmentPage.java` own stat-point spending UI.
- `src/main/java/com/duntale/death/DungeonDeathScreenSystem.java`, `src/main/java/com/duntale/death/DungeonDeathPage.java`, and `src/main/java/com/duntale/death/DungeonRespawnService.java` own dungeon death interception, pricing, and retry behavior.
- `src/main/java/com/duntale/DuntalePlugin.java` wires all open points, page action handlers, village routing, and death-page reopen behavior.

## Data, Assets, And Config

- Shipped player-facing page assets currently present in source are:
  - `src/main/resources/Common/UI/Custom/Pages/Entry/CustomizeCharacterPage.ui`
  - `src/main/resources/Common/UI/Custom/Pages/Entry/DungeonEntryPage.ui`
  - `src/main/resources/Common/UI/Custom/Pages/Entry/DungeonInstancePortalPage.ui`
  - `src/main/resources/Common/UI/Custom/Pages/StatAssignment/StatAssignmentPage.ui`
- `src/main/resources/Server/Configs/EntryFlow/CustomizeCharacter.json` currently configures one companion-setup slot at `(4, 67, 192)` with `PlayerYaw: 0` and `CameraYaw: -180`.
- The same config currently sets companion setup camera distance to `8.5`, pitch to `-0.12`, height offset to `5`, and companion preview offset to `(1.25, 0.0, 0.75)`.
- No page-specific resource file for `DungeonDeathPage` exists in source, even though the page class references `Pages/Death/DungeonDeathPage.ui`.
- No separate page config assets were verified for dungeon entry, dungeon portal, or stat assignment. Their current labels and structure are defined directly in the `.ui` assets and Java page classes.

## Validation

- `CustomizeCharacterServiceTest` verifies setup-slot reservation, round-robin selection, deterministic occupied-slot fallback, empty-slot fallback, and reservation release behavior.
- `PlayerEntryServiceTest` verifies the three shared-world join destinations: customization, dungeon entry, and direct village routing, including fail-closed behavior when persistence lookups throw.
- `DungeonInstancePortalTriggerServiceTest` verifies the exact authored portal volume id `dungeon_instance_portal` and confirms that only `ENTER` events open the page.
- `DungeonInstanceServiceTest` verifies continue-route behavior for `ACTIVE`, `CREATING`, `TRANSITIONING`, and ended instances. Those tests back the runtime behavior behind both `DungeonEntryPage` and portal-page `Continue`.
- `DungeonDeathScreenSystemTest` verifies that dungeon death interception runs before the built-in player death screen.
- `DungeonRespawnServiceTest` verifies current-floor and lower-floor price calculations, floor-one lower-floor unavailability, active-world context resolution, and gold charge or refund behavior.
- No dedicated automated tests verify the exact rendered text, widget ids, or end-to-end button wiring for `CustomizeCharacterPage`, `DungeonEntryPage`, `DungeonInstancePortalPage`, `StatAssignmentPage`, or the missing `DungeonDeathPage` asset.

## Known Gaps

- `DungeonDeathPage` is not fully shipped as a source asset. The Java page class exists, but `src/main/resources/Common/UI/Custom/Pages/Death/DungeonDeathPage.ui` is missing.
- Companion-setup validation, dungeon-entry retry states, and portal-start failure states are not represented as alternate page layouts. Current failures mostly surface as chat messages while the same page remains open or closes.
- The companion preview is world-space only. The shipped `CustomizeCharacterPage.ui` asset has no embedded model preview widget or inline validation label.
- `StatAssignmentPage` JavaDoc mentions command or NPC interaction, but only the `/assignstats` command path is verified in current code.
- Only one companion setup slot is configured in `CustomizeCharacter.json`, so concurrent customization sessions have no alternate authored staging location.

## Related Docs

- [dungeon-instances.md](../systems/dungeon-instances.md)
- [economy-rpg.md](../systems/economy-rpg.md)
- [click-to-move.md](../systems/click-to-move.md)
- [dungeon-instances.md](../validation/dungeon-instances.md)