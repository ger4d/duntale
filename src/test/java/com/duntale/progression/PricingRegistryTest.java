package com.duntale.progression;

import com.duntale.config.asset.PricingConfigAsset.CustomItemPriceEntry;
import com.duntale.config.asset.PricingConfigAsset.RespawnBandEntry;
import com.duntale.config.asset.PricingConfigAsset.VariantStepEntry;
import com.duntale.progression.PricingRegistry.Snapshot;
import com.duntale.progression.PricingRegistry.VariantStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PricingRegistry")
class PricingRegistryTest {

    private static final RespawnBandEntry[] RESPAWN_SCHEDULE = {
            new RespawnBandEntry(1, 1_000L),
            new RespawnBandEntry(10, 5_000L),
            new RespawnBandEntry(30, 15_000L),
    };
    private static final VariantStepEntry[] ELITE_STEPS = {
            new VariantStepEntry(0.5f, 5.5f, 2.0f),
            new VariantStepEntry(0.0f, 2.5f, 1.25f),
    };
    private static final VariantStepEntry[] BOSS_STEPS = {
            new VariantStepEntry(0.0f, 10.0f, 2.0f),
    };
    private static final CustomItemPriceEntry[] CUSTOM_PRICES = {
            new CustomItemPriceEntry("Speed_Boots_I", 30_000L),
            new CustomItemPriceEntry("Healing_Necklace_II", 125_000L),
    };

    private static Snapshot snapshot() {
        return PricingRegistry.build(
                12.0, 1.6, 0.8, 40L, 0.5,
                RESPAWN_SCHEDULE, ELITE_STEPS, BOSS_STEPS, CUSTOM_PRICES);
    }

    @Test
    @DisplayName("Should map scalar tuning, respawn schedule, variant steps, and custom prices")
    void shouldMapEntries() {
        PricingRegistry registry = PricingRegistry.forTest(snapshot());

        assertTrue(registry.isLoaded());
        assertEquals(12.0, registry.goldMappingScale(), 1e-6);
        assertEquals(1.6, registry.goldMappingExponent(), 1e-6);
        assertEquals(0.8, registry.armorEhpDrWeight(), 1e-6);
        assertEquals(40L, registry.minBuyPrice());
        assertEquals(0.5, registry.respawnRestartFraction(), 1e-6);

        assertTrue(registry.hasRespawnSchedule());
        assertEquals(1_000L, registry.resolveRespawnCost(5));    // band [1, 10)
        assertEquals(5_000L, registry.resolveRespawnCost(10));   // band [10, 30)
        assertEquals(15_000L, registry.resolveRespawnCost(45));  // top band

        assertTrue(registry.hasVariantSteps());
        List<VariantStep> elite = registry.eliteVariantSteps();
        assertEquals(2, elite.size());
        assertEquals(0.5f, elite.get(0).minLevelRatio(), 1e-4f);
        assertEquals(5.5f, elite.get(0).hpMult(), 1e-4f);
        assertEquals(1, registry.bossVariantSteps().size());

        assertEquals(30_000L, registry.customPrice("Speed_Boots_I"));
        assertEquals(125_000L, registry.customPrice("Healing_Necklace_II"));
        assertNull(registry.customPrice("Not_A_Custom_Item"));
    }

    @Test
    @DisplayName("Should report not-loaded for the empty snapshot and serve hard-coded defaults")
    void shouldDegradeForEmptySnapshot() {
        PricingRegistry registry = PricingRegistry.forTest(Snapshot.EMPTY);

        assertFalse(registry.isLoaded());
        assertFalse(registry.hasRespawnSchedule());
        assertFalse(registry.hasVariantSteps());
        assertEquals(10.0, registry.goldMappingScale(), 1e-6);
        assertEquals(1.4, registry.goldMappingExponent(), 1e-6);
        assertEquals(1.0, registry.armorEhpDrWeight(), 1e-6);
        assertEquals(25L, registry.minBuyPrice());
        assertEquals(0.6, registry.respawnRestartFraction(), 1e-6);
        assertNull(registry.resolveRespawnCost(20));
        assertTrue(registry.eliteVariantSteps().isEmpty());
        assertNull(registry.customPrice("Speed_Boots_I"));
    }

    @Test
    @DisplayName("Should skip blank-id custom price entries")
    void shouldSkipBlankCustomPriceEntries() {
        Snapshot snapshot = PricingRegistry.build(
                10.0, 1.4, 1.0, 25L, 0.6,
                new RespawnBandEntry[0], new VariantStepEntry[0], new VariantStepEntry[0],
                new CustomItemPriceEntry[]{
                        new CustomItemPriceEntry("", 999L),
                        new CustomItemPriceEntry("Palporter", 2_500L),
                });

        PricingRegistry registry = PricingRegistry.forTest(snapshot);
        assertEquals(1, registry.customPrices().size());
        assertEquals(2_500L, registry.customPrice("Palporter"));
    }
}
