package com.duntale.loot;

import com.duntale.config.asset.RarityConfigAsset;
import com.duntale.rpg.RpgStat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RarityRegistry")
class RarityRegistryTest {

    private static RarityConfigAsset.LadderEntry mobLadder() {
        return new RarityConfigAsset.LadderEntry("MOB", new RarityConfigAsset.RarityWeightEntry[]{
                new RarityConfigAsset.RarityWeightEntry("Common", 70),
                new RarityConfigAsset.RarityWeightEntry("Uncommon", 25),
                new RarityConfigAsset.RarityWeightEntry("Rare", 5),
                new RarityConfigAsset.RarityWeightEntry("Bogus", 99)
        });
    }

    private static RarityRegistry.Snapshot snapshot() {
        return RarityRegistry.build(
                new RarityConfigAsset.LadderEntry[]{mobLadder()},
                new RarityConfigAsset.PromotionConfig(0.05f, 0.10f, 1.3f, 50f,
                        new RarityConfigAsset.TierWeightEntry[]{
                                new RarityConfigAsset.TierWeightEntry(1, 80),
                                new RarityConfigAsset.TierWeightEntry(2, 20)
                        }, 0.0f),
                new RarityConfigAsset.AttributesConfig(
                        new RarityConfigAsset.AttributeCountEntry[]{
                                new RarityConfigAsset.AttributeCountEntry("Common", 0, 1, 1, 1),
                                new RarityConfigAsset.AttributeCountEntry("Rare", 2, 2, 1, 3)
                        },
                        new String[]{"STRENGTH", "SPEED", "BOGUS_STAT"},
                        15),
                new RarityConfigAsset.PriceMultiplierEntry[]{
                        new RarityConfigAsset.PriceMultiplierEntry("Rare", 1.4f),
                        new RarityConfigAsset.PriceMultiplierEntry("Legendary", 3.0f)
                },
                new RarityConfigAsset.DisplayEntry[]{
                        new RarityConfigAsset.DisplayEntry("Legendary", "#FF8800", "Legendary")
                });
    }

    @Test
    @DisplayName("Should report loaded only with at least one ladder")
    void shouldReportLoaded() {
        assertFalse(RarityRegistry.forTest(RarityRegistry.Snapshot.EMPTY).isLoaded());
        assertTrue(RarityRegistry.forTest(snapshot()).isLoaded());
    }

    @Test
    @DisplayName("Should resolve a source ladder and skip unknown rarities")
    void shouldResolveLadder() {
        RarityRegistry registry = RarityRegistry.forTest(snapshot());
        List<RarityRegistry.WeightedRarity> ladder = registry.ladder(RaritySource.MOB);
        assertEquals(3, ladder.size()); // "Bogus" skipped
        assertSame(Rarity.COMMON, ladder.get(0).rarity());
        assertEquals(70, ladder.get(0).weight());
        assertTrue(registry.ladder(RaritySource.BOSS).isEmpty());
        assertSame(Rarity.RARE, registry.topRarity(RaritySource.MOB));
    }

    @Test
    @DisplayName("Should resolve promotion, attribute, price, and display tuning")
    void shouldResolveTuning() {
        RarityRegistry registry = RarityRegistry.forTest(snapshot());

        RarityRegistry.Promotion promo = registry.promotion();
        assertEquals(0.05f, promo.baseChance());
        assertEquals(50f, promo.luckRef());
        assertEquals(2, promo.tierWeights().size());

        assertEquals(2, registry.attrCount(Rarity.RARE).min());
        assertEquals(2, registry.attrCount(Rarity.RARE).max());
        assertEquals(1, registry.attrCount(Rarity.RARE).valueMin());
        assertEquals(3, registry.attrCount(Rarity.RARE).valueMax());
        assertEquals(RarityRegistry.AttrCount.NONE, registry.attrCount(Rarity.EPIC));
        assertEquals(List.of(RpgStat.STRENGTH, RpgStat.SPEED), registry.eligibleStats()); // BOGUS_STAT skipped

        assertEquals(1.4f, registry.priceMult(Rarity.RARE));
        assertEquals(1.0f, registry.priceMult(Rarity.COMMON)); // unmapped -> default
        assertEquals(1.0f, registry.priceMult(null));

        assertEquals("#FF8800", registry.displayColor(Rarity.LEGENDARY));
        assertEquals("Legendary", registry.displayName(Rarity.LEGENDARY));
        assertEquals("#AAAAAA", registry.displayColor(Rarity.COMMON)); // unmapped -> default
        assertEquals("Common", registry.displayName(Rarity.COMMON)); // unmapped -> id fallback
    }

    @Test
    @DisplayName("Should degrade to empty defaults for an unloaded registry")
    void shouldDegradeForEmpty() {
        RarityRegistry registry = RarityRegistry.forTest(RarityRegistry.Snapshot.EMPTY);
        assertTrue(registry.ladder(RaritySource.MOB).isEmpty());
        assertEquals(1.0f, registry.priceMult(Rarity.LEGENDARY));
        assertEquals(RarityRegistry.AttrCount.NONE, registry.attrCount(Rarity.RARE));
        assertSame(RarityRegistry.Promotion.NONE, registry.promotion());
    }
}
