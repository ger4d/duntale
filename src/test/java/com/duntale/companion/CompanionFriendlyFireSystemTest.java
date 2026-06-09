package com.duntale.companion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("CompanionFriendlyFireSystem")
class CompanionFriendlyFireSystemTest {

    @Nested
    @DisplayName("shouldCancelDamage")
    class ShouldCancelDamage {

        @Test
        @DisplayName("Should cancel damage dealt by a player")
        void shouldCancelPlayerDamage() {
            assertTrue(CompanionFriendlyFireSystem.shouldCancelDamage(true, false));
        }

        @Test
        @DisplayName("Should cancel damage dealt by a turret/deployable")
        void shouldCancelDeployableDamage() {
            assertTrue(CompanionFriendlyFireSystem.shouldCancelDamage(false, true));
        }

        @Test
        @DisplayName("Should cancel when the source is both a player and a deployable")
        void shouldCancelWhenBoth() {
            assertTrue(CompanionFriendlyFireSystem.shouldCancelDamage(true, true));
        }

        @Test
        @DisplayName("Should allow damage from enemy NPCs / environment (neither player nor deployable)")
        void shouldAllowOtherSources() {
            assertFalse(CompanionFriendlyFireSystem.shouldCancelDamage(false, false));
        }
    }
}
