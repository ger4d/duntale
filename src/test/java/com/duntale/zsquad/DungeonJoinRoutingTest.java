package com.duntale.zsquad;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DungeonJoinRouting")
class DungeonJoinRoutingTest {

    @Nested
    @DisplayName("resolve")
    class Resolve {

        @Test
        @DisplayName("Should route non-shared reconnects through the shared menu world")
        void shouldRouteNonSharedReconnectsThroughSharedMenuWorld() {
            DungeonJoinRouting.JoinRoutingDecision decision =
                    DungeonJoinRouting.resolve("dungeon-123", "hub-world");

            assertEquals("hub-world", decision.targetWorldName());
            assertTrue(decision.openEntryMenu());
        }

        @Test
        @DisplayName("Should still open the entry menu when already joining into the shared world")
        void shouldStillOpenEntryMenuWhenAlreadyJoiningIntoSharedWorld() {
            DungeonJoinRouting.JoinRoutingDecision decision =
                    DungeonJoinRouting.resolve("hub-world", "hub-world");

            assertEquals("hub-world", decision.targetWorldName());
            assertTrue(decision.openEntryMenu());
        }

        @Test
        @DisplayName("Should preserve the engine-selected world when no shared world exists")
        void shouldPreserveEngineSelectedWorldWhenNoSharedWorldExists() {
            DungeonJoinRouting.JoinRoutingDecision decision =
                    DungeonJoinRouting.resolve("dungeon-123", null);

            assertEquals("dungeon-123", decision.targetWorldName());
            assertFalse(decision.openEntryMenu());
        }
    }
}
