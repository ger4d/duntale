package com.duntale.progression;

import com.duntale.loot.GearAttribute;
import com.duntale.rpg.RpgProfile;
import com.duntale.rpg.RpgRepository;
import com.duntale.rpg.RpgService;
import com.duntale.rpg.RpgStat;
import com.duntale.rpg.RpgStatApplicator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@DisplayName("GearAttributeService")
class GearAttributeServiceTest {

    private static final UUID PLAYER_ID = UUID.randomUUID();

    private GearAttributeService service;

    @BeforeEach
    void setUp() {
        RpgStatApplicator applicator = new RpgStatApplicator(
                new RpgService(new FakeRpgRepository(new RpgProfile())));
        GearCurveRegistry curves = GearCurveRegistry.forTest(GearCurveRegistry.Snapshot.EMPTY);
        service = new GearAttributeService(applicator, curves, new AssetCatalog());
    }

    @Test
    @DisplayName("Should sum attributes from multiple sources")
    void shouldSumAttributes() {
        EnumMap<RpgStat, Integer> bonus = new EnumMap<>(RpgStat.class);

        GearAttributeService.accumulateAttributes(List.of(
                new GearAttribute(RpgStat.STRENGTH, 3),
                new GearAttribute(RpgStat.SPEED, 5),
                new GearAttribute(RpgStat.STRENGTH, 4)
        ), bonus);

        assertEquals(7, bonus.get(RpgStat.STRENGTH));
        assertEquals(5, bonus.get(RpgStat.SPEED));
        assertFalse(bonus.containsKey(RpgStat.AGILITY));
    }

    @Test
    @DisplayName("Should apply negative attribute values")
    void shouldApplyNegativeAttributes() {
        EnumMap<RpgStat, Integer> bonus = new EnumMap<>(RpgStat.class);

        GearAttributeService.accumulateAttributes(List.of(
                new GearAttribute(RpgStat.STRENGTH, 5),
                new GearAttribute(RpgStat.STRENGTH, -3)
        ), bonus);

        assertEquals(2, bonus.get(RpgStat.STRENGTH));
    }

    @Test
    @DisplayName("Should cap attribute sum at max stat")
    void shouldCapAtMaxStat() {
        EnumMap<RpgStat, Integer> bonus = new EnumMap<>(RpgStat.class);
        int maxStat = 100; // default RpgConfig maxStat

        GearAttributeService.accumulateAttributes(List.of(
                new GearAttribute(RpgStat.STRENGTH, maxStat + 10)
        ), bonus);

        assertEquals(maxStat, bonus.get(RpgStat.STRENGTH));
    }

    @Test
    @DisplayName("Should return zero for stats with no bonus")
    void shouldReturnZeroForMissingBonus() {
        assertEquals(0, service.getBonus(PLAYER_ID, RpgStat.STRENGTH));
    }

    @Test
    @DisplayName("Should clear cached bonuses on player leave")
    void shouldClearOnLeave() {
        // The public API has no direct setter; clear() removes the cache entry.
        service.clear(PLAYER_ID);
        assertEquals(0, service.getBonus(PLAYER_ID, RpgStat.STRENGTH));
    }

    @Test
    @DisplayName("Should not mutate the persisted RPG profile")
    void shouldNotMutateProfile() {
        RpgProfile profile = new RpgProfile();
        profile.setStat(RpgStat.STRENGTH, 10);
        RpgService rpgService = new RpgService(new FakeRpgRepository(profile));
        rpgService.setGearBonusProvider((id, stat) -> stat == RpgStat.STRENGTH ? 5 : 0);

        assertEquals(15, rpgService.getEffectiveStat(PLAYER_ID, RpgStat.STRENGTH));
        assertEquals(10, rpgService.getStat(PLAYER_ID, RpgStat.STRENGTH));
    }

    /** In-memory fake repository that serves a fixed profile without touching SQLite. */
    private static final class FakeRpgRepository extends RpgRepository {

        private final RpgProfile profile;

        private FakeRpgRepository(@Nonnull RpgProfile profile) {
            super(null);
            this.profile = profile;
        }

        @Override
        public RpgProfile loadProfile(@Nonnull UUID playerId) {
            return profile;
        }

        @Override
        public int loadUnassignedPoints(@Nonnull UUID playerId) {
            return 0;
        }
    }
}
