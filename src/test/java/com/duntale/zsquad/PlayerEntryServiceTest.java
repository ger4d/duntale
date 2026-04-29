package com.duntale.zsquad;

import com.duntale.dungeongen.config.Vec3i;
import com.duntale.zsquad.dungeon.DungeonInstance;
import com.duntale.zsquad.dungeon.DungeonInstanceState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("PlayerEntryService")
class PlayerEntryServiceTest {

    private static final UUID PLAYER_ID = UUID.fromString("0d93f9dc-d554-4c69-b118-00ac8ecf493f");

    @Nested
    @DisplayName("resolve")
    class Resolve {

        @Test
        @DisplayName("Should route players without a stored preference into customization")
        void shouldRoutePlayersWithoutStoredPreferenceIntoCustomization() {
            PlayerEntryService service = new PlayerEntryService(
                    playerId -> false,
                    playerId -> null
            );

            PlayerEntryService.EntryDecision decision = service.resolve(PLAYER_ID, "dungeon-123", "hub-world");

            assertEquals("hub-world", decision.targetWorldName());
            assertEquals(PlayerEntryService.EntryDestination.CUSTOMIZE_CHARACTER, decision.destination());
        }

        @Test
        @DisplayName("Should route players with an active instance into dungeon entry")
        void shouldRoutePlayersWithActiveInstanceIntoDungeonEntry() {
            PlayerEntryService service = new PlayerEntryService(
                    playerId -> true,
                    playerId -> activeInstance()
            );

            PlayerEntryService.EntryDecision decision = service.resolve(PLAYER_ID, "dungeon-123", "hub-world");

            assertEquals("hub-world", decision.targetWorldName());
            assertEquals(PlayerEntryService.EntryDestination.DUNGEON_ENTRY, decision.destination());
        }

        @Test
        @DisplayName("Should route players with a completed profile and no active instance to village")
        void shouldRoutePlayersWithCompletedProfileAndNoActiveInstanceToVillage() {
            PlayerEntryService service = new PlayerEntryService(
                    playerId -> true,
                    playerId -> null
            );

            PlayerEntryService.EntryDecision decision = service.resolve(PLAYER_ID, "dungeon-123", "hub-world");

            assertEquals("hub-world", decision.targetWorldName());
            assertEquals(PlayerEntryService.EntryDestination.VILLAGE, decision.destination());
        }

        @Test
        @DisplayName("Should preserve the current world when no shared world exists")
        void shouldPreserveCurrentWorldWhenNoSharedWorldExists() {
            PlayerEntryService service = new PlayerEntryService(
                    playerId -> true,
                    playerId -> activeInstance()
            );

            PlayerEntryService.EntryDecision decision = service.resolve(PLAYER_ID, "dungeon-123", null);

            assertEquals("dungeon-123", decision.targetWorldName());
            assertEquals(PlayerEntryService.EntryDestination.VILLAGE, decision.destination());
        }

        @Test
        @DisplayName("Should fail closed to shared-world village routing when preference lookup fails")
        void shouldFailClosedWhenPreferenceLookupFails() {
            PlayerEntryService service = new PlayerEntryService(
                    playerId -> {
                        throw new SQLException("boom");
                    },
                    playerId -> activeInstance()
            );

            PlayerEntryService.EntryDecision decision = service.resolve(PLAYER_ID, "dungeon-123", "hub-world");

            assertEquals("hub-world", decision.targetWorldName());
            assertEquals(PlayerEntryService.EntryDestination.VILLAGE, decision.destination());
        }

        @Test
        @DisplayName("Should fail closed to shared-world village routing when active instance lookup fails")
        void shouldFailClosedWhenActiveInstanceLookupFails() {
            PlayerEntryService service = new PlayerEntryService(
                    playerId -> true,
                    playerId -> {
                        throw new SQLException("boom");
                    }
            );

            PlayerEntryService.EntryDecision decision = service.resolve(PLAYER_ID, "dungeon-123", "hub-world");

            assertEquals("hub-world", decision.targetWorldName());
            assertEquals(PlayerEntryService.EntryDestination.VILLAGE, decision.destination());
        }
    }

    private static DungeonInstance activeInstance() {
        return new DungeonInstance(
                "instance-1",
                "dungeon-123",
                1,
                64.0,
                new Vec3i(0, 64, 0),
                new Vec3i(1, 64, 1),
                DungeonInstanceState.ACTIVE,
                "crypt",
                null,
                0L
        );
    }
}