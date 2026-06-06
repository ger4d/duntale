package com.duntale.rpg;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@link RpgStatEffects} reads its tuning live from {@link RpgConfig#values()} rather
 * than from inlined constants, by installing a custom snapshot via the test hook.
 */
@DisplayName("RpgStatEffects (config-driven)")
class RpgStatEffectsConfigTest {

    @AfterEach
    void resetConfig() {
        RpgConfig.resetForTest();
    }

    /** Distinctive snapshot whose values differ from the compile-time defaults. */
    private static RpgConfigValues customValues() {
        return new RpgConfigValues(
                0, 100,
                100.0f, 0.0f, 1.0f,            // speed -> flat 100
                0.0f, 25.0f,                   // strength bonus 0 -> multiplier 1.0
                0.30f, 20.0f, 7,               // luck: 7 levels per bonus roll
                3.0f,                          // stamina per point 3
                400_000_000L, 0.65f, 25.0f, 140_000_000L,
                0.40f, 30.0f,
                2.0f,                          // vitality per point 2
                999L
        );
    }

    @Test
    @DisplayName("compute* reflect the installed snapshot, not the defaults")
    void computeReflectsSnapshot() {
        RpgConfig.installForTest(customValues());

        assertEquals(100.0f, RpgStatEffects.computeMoveSpeed(50), 1e-4f);
        assertEquals(1.0f, RpgStatEffects.computeStrengthMultiplier(50), 1e-4f);
        assertEquals(12.0f, RpgStatEffects.computeStaminaBonus(4), 1e-4f);
        assertEquals(10.0f, RpgStatEffects.computeVitalityBonus(5), 1e-4f);
        assertEquals(2, RpgStatEffects.computeLuckBonusRolls(14));
    }

    @Test
    @DisplayName("After reset, compute* fall back to the default snapshot")
    void resetRestoresDefaults() {
        RpgConfig.installForTest(customValues());
        RpgConfig.resetForTest();

        // Default speedBase is 8.0; flat-100 custom value must no longer apply.
        assertEquals(RpgConstants.SPEED_BASE, RpgStatEffects.computeMoveSpeed(0), 1e-4f);
    }
}
