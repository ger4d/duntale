package com.duntale.progression;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("CombatScaling.turretDamageMult")
class CombatScalingTurretTest {

    @Test
    @DisplayName("Should be approximately 1.0 at the minimum level")
    void shouldBeApproximatelyOneAtMinLevel() {
        assertEquals(1.0f, CombatScaling.turretDamageMult(CombatScaling.MIN_LEVEL), 0.05f);
    }

    @Test
    @DisplayName("Should strictly increase with level")
    void shouldStrictlyIncreaseWithLevel() {
        assertTrue(CombatScaling.turretDamageMult(50) > CombatScaling.turretDamageMult(1));
        assertTrue(CombatScaling.turretDamageMult(100) > CombatScaling.turretDamageMult(50));
    }

    @Test
    @DisplayName("Should never drop below 1.0 across the supported range")
    void shouldNeverDropBelowOne() {
        for (int level = CombatScaling.MIN_LEVEL; level <= CombatScaling.MAX_LEVEL; level++) {
            float mult = CombatScaling.turretDamageMult(level);
            assertTrue(mult >= 1.0f, "turretDamageMult(" + level + ") = " + mult + " should be >= 1.0");
            assertTrue(Float.isFinite(mult), "turretDamageMult(" + level + ") should be finite");
        }
    }

    @Test
    @DisplayName("Should clamp scaling at the maximum level")
    void shouldClampScalingAtMaxLevel() {
        assertEquals(CombatScaling.turretDamageMult(CombatScaling.MAX_LEVEL),
                CombatScaling.turretDamageMult(CombatScaling.MAX_LEVEL + 50));
    }
}
