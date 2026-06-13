package com.duntale.loot;

import com.duntale.rpg.RpgStat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Rarity model")
class RarityModelTest {

    @Nested
    @DisplayName("Rarity")
    class RarityTest {

        @Test
        @DisplayName("Should resolve rarity from its id case-insensitively")
        void shouldResolveFromId() {
            assertSame(Rarity.LEGENDARY, Rarity.fromId("Legendary"));
            assertSame(Rarity.RARE, Rarity.fromId("rare"));
            assertNull(Rarity.fromId("Mythic"));
            assertNull(Rarity.fromId(null));
        }

        @Test
        @DisplayName("Should promote up the ladder and cap at Abyssal")
        void shouldPromoteAndCap() {
            assertSame(Rarity.RARE, Rarity.COMMON.promote(2));
            assertSame(Rarity.ABYSSAL, Rarity.EPIC.promote(3)); // Epic(3) + 3 = Abyssal(6)
            assertSame(Rarity.RELIC, Rarity.LEGENDARY.next());  // Legendary(4) + 1 = Relic(5)
            assertSame(Rarity.ABYSSAL, Rarity.ABYSSAL.next());  // capped at the ceiling
            assertSame(Rarity.ABYSSAL, Rarity.LEGENDARY.promote(5)); // capped
            assertSame(Rarity.COMMON, Rarity.COMMON.promote(0));
            assertSame(Rarity.COMMON, Rarity.COMMON.promote(-1));
        }

        @Test
        @DisplayName("Should round-trip tier index")
        void shouldRoundTripTierIndex() {
            for (Rarity rarity : Rarity.values()) {
                assertSame(rarity, Rarity.fromTierIndex(rarity.tierIndex()));
            }
            assertSame(Rarity.COMMON, Rarity.fromTierIndex(-5));
            assertSame(Rarity.ABYSSAL, Rarity.fromTierIndex(99));
        }
    }

    @Nested
    @DisplayName("GearAttribute")
    class GearAttributeTest {

        @Test
        @DisplayName("Should round-trip encode/decode")
        void shouldRoundTrip() {
            List<GearAttribute> attributes = List.of(
                    new GearAttribute(RpgStat.STRENGTH, 5),
                    new GearAttribute(RpgStat.VITALITY, 3));
            String encoded = GearAttribute.encode(attributes);
            assertEquals("STRENGTH:5;VITALITY:3", encoded);
            assertEquals(attributes, GearAttribute.decode(encoded));
        }

        @Test
        @DisplayName("Should decode empty/null to an empty list")
        void shouldDecodeEmpty() {
            assertTrue(GearAttribute.decode(null).isEmpty());
            assertTrue(GearAttribute.decode("").isEmpty());
            assertEquals("", GearAttribute.encode(List.of()));
        }

        @Test
        @DisplayName("Should skip malformed and unknown-stat pairs")
        void shouldSkipMalformed() {
            List<GearAttribute> decoded = GearAttribute.decode("STRENGTH:5;GARBAGE;MYSTERY:2;SPEED:x;LUCK:4");
            assertEquals(List.of(
                    new GearAttribute(RpgStat.STRENGTH, 5),
                    new GearAttribute(RpgStat.LUCK, 4)), decoded);
        }
    }
}
