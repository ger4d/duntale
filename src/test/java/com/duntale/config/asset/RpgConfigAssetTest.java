package com.duntale.config.asset;

import com.duntale.rpg.RpgConfigValues;
import com.duntale.rpg.RpgConstants;
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
    @DisplayName("Clamps maxStat >= minStat, half-points >= 0, and the Luck drop-curve guards")
    void clampsInvalidValues() {
        RpgConfigAsset asset = new RpgConfigAsset();
        asset.minStat = 10;
        asset.maxStat = -5;                 // below minStat
        asset.speedHalfPoint = -3.0f;       // negative denominator
        asset.agilityHalfPoint = -1.0f;
        asset.luckDropCoefficient = -0.5f;  // negative bonus
        asset.luckDropExponent = 0.0f;      // a 0 exponent would grant the full bonus at any luck
        asset.luckDropReference = 0;        // divisor
        asset.luckDropMaxChance = 1.5f;     // above 1.0

        RpgConfigValues v = RpgConfigValues.fromAsset(asset);

        assertEquals(10, v.minStat());
        assertEquals(10, v.maxStat());
        assertEquals(0.0f, v.speedHalfPoint());
        assertEquals(0.0f, v.agilityHalfPoint());
        assertEquals(0.0f, v.luckDropCoefficient());
        assertEquals(RpgConstants.LUCK_DROP_EXPONENT, v.luckDropExponent());
        assertEquals(1, v.luckDropReference());
        assertEquals(1.0f, v.luckDropMaxChance());
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
