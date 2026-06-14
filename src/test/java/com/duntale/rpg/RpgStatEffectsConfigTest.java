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
                0.50f, 2.0f, 40, 0.90f,        // luck drop curve: coeff 0.50, exp 2.0, ref 40, maxChance 0.90
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
        // ref 40 -> luck 40 normalizes to 1.0: 0.10 + 0.50 * 1^2 = 0.60.
        // Deltas are 1e-6 because the coefficient/maxChance are stored as float.
        assertEquals(0.60, RpgStatEffects.computeLuckDropChance(0.10, 40), 1e-6);
        // base + full bonus exceeds the snapshot's 0.90 maxChance -> clamped.
        assertEquals(0.90, RpgStatEffects.computeLuckDropChance(0.80, 40), 1e-6);
    }

    @Test
    @DisplayName("Default Luck drop-chance curve hits the briefed anchors")
    void defaultLuckDropCurve() {
        RpgConfig.resetForTest();

        assertEquals(0.10, RpgStatEffects.computeLuckDropChance(0.10, 0), 1e-6);
        assertEquals(0.41, RpgStatEffects.computeLuckDropChance(0.10, 30), 0.01);
        assertEquals(0.80, RpgStatEffects.computeLuckDropChance(0.10, 50), 1e-6);
        // Luck is clamped to the reference, so beyond it the chance stops climbing.
        assertEquals(0.80, RpgStatEffects.computeLuckDropChance(0.10, 100), 1e-6);
        // A high base chance plus the full bonus is capped at the max-chance clamp.
        assertEquals(0.95, RpgStatEffects.computeLuckDropChance(0.50, 50), 1e-6);
        assertEquals(0.50, RpgStatEffects.computeLuckDropChance(0.50, 0), 1e-6);
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
