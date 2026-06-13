package com.duntale.loot;

import com.duntale.config.asset.RarityConfigAsset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for the rarity roll (base ladder, Luck promotion, attribute ranges). The
 * metadata-stamping path ({@code applyToGearDrops} writing {@code duntale_rarity}/
 * {@code duntale_attributes}) is a thin {@code GearLevelService} wrapper exercised in-game and by
 * the merchant/loot integration; it is kept out of this unit to avoid an {@code Item} asset-store
 * dependency that conflicts with the core-asset-store test setup under Gradle's fork distribution.
 */
@DisplayName("RarityRollService")
class RarityRollServiceTest {

    private static RarityConfigAsset.LadderEntry ladder(String source, Object... rarityWeightPairs) {
        RarityConfigAsset.RarityWeightEntry[] weights =
                new RarityConfigAsset.RarityWeightEntry[rarityWeightPairs.length / 2];
        for (int i = 0; i < weights.length; i++) {
            weights[i] = new RarityConfigAsset.RarityWeightEntry(
                    (String) rarityWeightPairs[i * 2], (int) rarityWeightPairs[i * 2 + 1]);
        }
        return new RarityConfigAsset.LadderEntry(source, weights);
    }

    private static RarityRollService service(RarityConfigAsset.LadderEntry ladder,
                                             RarityConfigAsset.PromotionConfig promotion,
                                             RarityConfigAsset.AttributesConfig attributes) {
        RarityRegistry.Snapshot snapshot = RarityRegistry.build(
                new RarityConfigAsset.LadderEntry[]{ladder},
                promotion,
                attributes,
                new RarityConfigAsset.PriceMultiplierEntry[0],
                new RarityConfigAsset.DisplayEntry[0]);
        return new RarityRollService(RarityRegistry.forTest(snapshot));
    }

    private static RarityConfigAsset.PromotionConfig noPromotion() {
        return new RarityConfigAsset.PromotionConfig(0f, 0f, 1f, 50f,
                new RarityConfigAsset.TierWeightEntry[0], 0f);
    }

    private static RarityConfigAsset.AttributesConfig noAttributes() {
        return new RarityConfigAsset.AttributesConfig(
                new RarityConfigAsset.AttributeCountEntry[0], new String[0], 0);
    }

    @Test
    @DisplayName("Should roll base rarities in proportion to ladder weights")
    void shouldRollBaseByWeight() {
        RarityRollService service = service(
                ladder("MOB", "Common", 70, "Uncommon", 25, "Rare", 5),
                noPromotion(), noAttributes());
        Random rng = new Random(1234);

        Map<Rarity, Integer> counts = new EnumMap<>(Rarity.class);
        for (int i = 0; i < 20_000; i++) {
            counts.merge(service.rollBase(RaritySource.MOB, rng), 1, Integer::sum);
        }
        assertTrue(counts.get(Rarity.COMMON) > counts.get(Rarity.UNCOMMON),
                "Common should outweigh Uncommon");
        assertTrue(counts.get(Rarity.UNCOMMON) > counts.getOrDefault(Rarity.RARE, 0),
                "Uncommon should outweigh Rare");
    }

    @Test
    @DisplayName("Should not promote at zero luck and always promote at the luck reference")
    void shouldPromoteWithLuck() {
        // baseChance 0, luckCoeff 1, exp 1, ref 50 -> chance(0)=0, chance(50)=1.0
        RarityRollService service = service(
                ladder("MOB", "Common", 100),
                new RarityConfigAsset.PromotionConfig(0f, 1f, 1f, 50f,
                        new RarityConfigAsset.TierWeightEntry[]{new RarityConfigAsset.TierWeightEntry(1, 100)}, 0f),
                noAttributes());
        Random rng = new Random(99);

        for (int i = 0; i < 500; i++) {
            assertSame(Rarity.COMMON, service.promote(Rarity.COMMON, 0, rng));
        }
        for (int i = 0; i < 500; i++) {
            assertSame(Rarity.UNCOMMON, service.promote(Rarity.COMMON, 50, rng));
        }
    }

    @Test
    @DisplayName("Should cap promotion at Abyssal")
    void shouldCapPromotion() {
        RarityRollService service = service(
                ladder("MOB", "Common", 100),
                new RarityConfigAsset.PromotionConfig(0f, 1f, 1f, 50f,
                        new RarityConfigAsset.TierWeightEntry[]{new RarityConfigAsset.TierWeightEntry(3, 100)}, 0f),
                noAttributes());
        Random rng = new Random(7);

        // Already at the ceiling -> unchanged.
        assertSame(Rarity.ABYSSAL, service.promote(Rarity.ABYSSAL, 50, rng));
        for (int i = 0; i < 200; i++) {
            assertSame(Rarity.ABYSSAL, service.promote(Rarity.EPIC, 50, rng)); // Epic(3)+3 = Abyssal(6)
            assertSame(Rarity.ABYSSAL, service.promote(Rarity.LEGENDARY, 50, rng)); // Legendary(4)+3 capped
        }
    }

    @Test
    @DisplayName("Should roll a distinct stat per attribute, each independently within the level-scaled range")
    void shouldRollAttributes() {
        RarityRollService service = service(
                ladder("MOB", "Rare", 100),
                noPromotion(),
                new RarityConfigAsset.AttributesConfig(
                        new RarityConfigAsset.AttributeCountEntry[]{
                                new RarityConfigAsset.AttributeCountEntry("Rare", 2, 2, 1, 3)},
                        new String[]{"STRENGTH", "SPEED", "AGILITY", "VITALITY", "LUCK", "RESISTANCE"},
                        15));
        Random rng = new Random(3);

        // L30 range with step 15: shift = 30/15 = 2 -> [1+2, 3+2] = [3, 5]
        int[] range = service.attributeValueRange(Rarity.RARE, 30);
        assertEquals(3, range[0]);
        assertEquals(5, range[1]);

        List<GearAttribute> attributes = service.rollAttributes(Rarity.RARE, 30, rng);
        assertEquals(2, attributes.size());
        assertEquals(2, attributes.stream().map(GearAttribute::stat).distinct().count());
        attributes.forEach(a -> assertTrue(a.value() >= range[0] && a.value() <= range[1],
                "attribute value " + a.value() + " within [" + range[0] + ", " + range[1] + "]"));
    }

    @Test
    @DisplayName("Should roll no attributes for the empty spec / unloaded registry")
    void shouldRollNoAttributesWhenUnspecified() {
        RarityRollService loaded = service(ladder("MOB", "Rare", 100), noPromotion(), noAttributes());
        assertTrue(loaded.rollAttributes(Rarity.RARE, 50, new Random(1)).isEmpty());

        RarityRollService unloaded = new RarityRollService(RarityRegistry.forTest(RarityRegistry.Snapshot.EMPTY));
        assertSame(Rarity.COMMON, unloaded.rollBase(RaritySource.MOB, new Random(1)));
        assertTrue(unloaded.rollAttributes(Rarity.LEGENDARY, 50, new Random(1)).isEmpty());
    }
}
