package com.duntale.rpg;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link RpgStatApplicator}'s stat-to-modifier mapping and bonus logic.
 *
 * <p>The ECS application path ({@code reassert}/{@code applyDelta}) is not unit-tested: it
 * depends on the runtime {@code EntityStatType} asset map and a live {@code EntityStatMap},
 * matching the convention in {@code NpcScalingApplicatorTest}. These tests cover the
 * decision logic the design's correctness rests on: namespaced keys and the scaling formulas.
 */
@DisplayName("RpgStatApplicator")
class RpgStatApplicatorTest {

    @Test
    @DisplayName("Should bind Vitality to Health and Stamina to Stamina")
    void shouldBindStatsToEntityStats() {
        assertEquals(RpgStatApplicator.HEALTH_STAT, RpgStatApplicator.BINDINGS.get(RpgStat.VITALITY).statId());
        assertEquals(RpgStatApplicator.STAMINA_STAT, RpgStatApplicator.BINDINGS.get(RpgStat.STAMINA).statId());
    }

    @Test
    @DisplayName("Should only manage Vitality and Stamina")
    void shouldOnlyManageVitalityAndStamina() {
        assertEquals(2, RpgStatApplicator.BINDINGS.size());
        for (RpgStat stat : RpgStat.values()) {
            if (stat != RpgStat.VITALITY && stat != RpgStat.STAMINA) {
                assertNull(RpgStatApplicator.BINDINGS.get(stat), "Unexpected binding for " + stat);
            }
        }
    }

    @Test
    @DisplayName("Should use namespaced modifier keys distinct from built-in and NPC keys")
    void shouldUseNamespacedModifierKeys() {
        String vitalityKey = RpgStatApplicator.BINDINGS.get(RpgStat.VITALITY).modifierKey();
        String staminaKey = RpgStatApplicator.BINDINGS.get(RpgStat.STAMINA).modifierKey();

        assertNotEquals(vitalityKey, staminaKey);
        for (String key : new String[]{vitalityKey, staminaKey}) {
            assertTrue(key.startsWith("Duntale_"), key + " must be namespaced");
            // Must not collide with the NPC health-scaling key or built-in reserved keys.
            assertNotEquals("Duntale_LevelScale", key);
            assertFalse(key.startsWith("Armor_"), key + " collides with armor modifiers");
            assertFalse(key.startsWith("Effect_"), key + " collides with effect modifiers");
            assertFalse(key.startsWith("*Weapon_"), key + " collides with weapon modifiers");
            assertFalse(key.startsWith("*Utility_"), key + " collides with utility modifiers");
        }
    }

    @Test
    @DisplayName("Should compute bonuses from the Vitality/Stamina scaling formulas")
    void shouldComputeBonusesFromFormulas() {
        assertEquals(RpgStatEffects.computeVitalityBonus(10), RpgStatApplicator.bonusFor(RpgStat.VITALITY, 10));
        assertEquals(RpgStatEffects.computeStaminaBonus(7), RpgStatApplicator.bonusFor(RpgStat.STAMINA, 7));
        // Sanity-check the underlying magnitudes (10 HP/pt, 5 stamina/pt).
        assertEquals(100.0f, RpgStatApplicator.bonusFor(RpgStat.VITALITY, 10));
        assertEquals(35.0f, RpgStatApplicator.bonusFor(RpgStat.STAMINA, 7));
    }

    @Test
    @DisplayName("Should yield zero bonus for unmanaged stats")
    void shouldYieldZeroBonusForUnmanagedStats() {
        assertEquals(0.0f, RpgStatApplicator.bonusFor(RpgStat.SPEED, 50));
        assertEquals(0.0f, RpgStatApplicator.bonusFor(RpgStat.STRENGTH, 99));
    }

    @Test
    @DisplayName("Should yield zero bonus at level zero")
    void shouldYieldZeroBonusAtLevelZero() {
        assertEquals(0.0f, RpgStatApplicator.bonusFor(RpgStat.VITALITY, 0));
        assertEquals(0.0f, RpgStatApplicator.bonusFor(RpgStat.STAMINA, 0));
    }
}
