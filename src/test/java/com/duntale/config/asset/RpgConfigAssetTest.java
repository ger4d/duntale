package com.duntale.config.asset;

import com.duntale.rpg.RpgConfigValues;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link RpgConfigValues#fromAsset} defensive clamping. Lives in the asset's
 * package so it can set the protected asset fields directly without the asset store.
 */
@DisplayName("RpgConfigAsset -> RpgConfigValues clamping")
class RpgConfigAssetTest {

    @Test
    @DisplayName("Clamps maxStat >= minStat, half-points >= 0, and luckLevelsPerBonusRoll >= 1")
    void clampsInvalidValues() {
        RpgConfigAsset asset = new RpgConfigAsset();
        asset.minStat = 10;
        asset.maxStat = -5;                 // below minStat
        asset.speedHalfPoint = -3.0f;       // negative denominator
        asset.agilityHalfPoint = -1.0f;
        asset.luckLevelsPerBonusRoll = 0;   // divisor

        RpgConfigValues v = RpgConfigValues.fromAsset(asset);

        assertEquals(10, v.minStat());
        assertEquals(10, v.maxStat());
        assertEquals(0.0f, v.speedHalfPoint());
        assertEquals(0.0f, v.agilityHalfPoint());
        assertEquals(1, v.luckLevelsPerBonusRoll());
    }

    @Test
    @DisplayName("Passes through valid overridden values")
    void passesThroughValidValues() {
        RpgConfigAsset asset = new RpgConfigAsset();
        asset.speedBase = 16.0f;
        asset.maxStat = 50;
        asset.maxGoldBalance = 1_000L;

        RpgConfigValues v = RpgConfigValues.fromAsset(asset);

        assertEquals(16.0f, v.speedBase());
        assertEquals(50, v.maxStat());
        assertEquals(1_000L, v.maxGoldBalance());
    }
}
