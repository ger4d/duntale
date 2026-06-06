package com.duntale.rpg;

import com.duntale.config.asset.RpgConfigAsset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link RpgConfigValues} defaults and asset mapping.
 */
@DisplayName("RpgConfigValues")
class RpgConfigValuesTest {

    @Test
    @DisplayName("DEFAULTS mirror the RpgConstants compile-time values")
    void defaultsMirrorConstants() {
        RpgConfigValues d = RpgConfigValues.DEFAULTS;
        assertEquals(RpgConstants.MIN_STAT, d.minStat());
        assertEquals(RpgConstants.MAX_STAT, d.maxStat());
        assertEquals(RpgConstants.SPEED_BASE, d.speedBase());
        assertEquals(RpgConstants.SPEED_MAX_BONUS, d.speedMaxBonus());
        assertEquals(RpgConstants.SPEED_HALF_POINT, d.speedHalfPoint());
        assertEquals(RpgConstants.LUCK_LEVELS_PER_BONUS_ROLL, d.luckLevelsPerBonusRoll());
        assertEquals(RpgConstants.AGILITY_BASE_THROTTLE_NS, d.agilityBaseThrottleNs());
        assertEquals(RpgConstants.AGILITY_MIN_THROTTLE_NS, d.agilityMinThrottleNs());
        assertEquals(RpgConstants.VITALITY_HP_PER_POINT, d.vitalityHpPerPoint());
        assertEquals(RpgConstants.MAX_GOLD_BALANCE, d.maxGoldBalance());
    }

    @Test
    @DisplayName("fromAsset of a default-valued asset equals DEFAULTS")
    void fromDefaultAssetEqualsDefaults() {
        // A freshly constructed asset has every field initialized to its RpgConstants default.
        assertEquals(RpgConfigValues.DEFAULTS, RpgConfigValues.fromAsset(new RpgConfigAsset()));
    }
}
