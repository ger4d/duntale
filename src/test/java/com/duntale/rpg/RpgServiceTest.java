package com.duntale.rpg;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("RpgService effective stats")
class RpgServiceTest {

    private RpgService rpgService;
    private UUID playerId;

    @BeforeEach
    void setUp() {
        RpgProfile profile = new RpgProfile();
        profile.setStat(RpgStat.STRENGTH, 10);
        profile.setStat(RpgStat.SPEED, 98);
        rpgService = new RpgService(new FakeRpgRepository(profile));
        playerId = UUID.randomUUID();
    }

    @Test
    @DisplayName("Should add the gear bonus to the base without mutating the profile")
    void shouldAddGearBonus() {
        rpgService.setGearBonusProvider((id, stat) -> stat == RpgStat.STRENGTH ? 5 : 0);

        assertEquals(15, rpgService.getEffectiveStat(playerId, RpgStat.STRENGTH));
        // Base (persisted) is untouched by gear.
        assertEquals(10, rpgService.getStat(playerId, RpgStat.STRENGTH));
    }

    @Test
    @DisplayName("Should clamp the effective stat to the configured maximum")
    void shouldClampToMax() {
        rpgService.setGearBonusProvider((id, stat) -> 10);

        // base 98 + 10 = 108 -> clamped to default maxStat (100)
        assertEquals(100, rpgService.getEffectiveStat(playerId, RpgStat.SPEED));
        assertEquals(98, rpgService.getStat(playerId, RpgStat.SPEED));
    }

    @Test
    @DisplayName("Should default to base when no provider is wired")
    void shouldDefaultToBase() {
        assertEquals(10, rpgService.getEffectiveStat(playerId, RpgStat.STRENGTH));

        rpgService.setGearBonusProvider((id, stat) -> 7);
        assertEquals(17, rpgService.getEffectiveStat(playerId, RpgStat.STRENGTH));

        rpgService.setGearBonusProvider(null);
        assertEquals(10, rpgService.getEffectiveStat(playerId, RpgStat.STRENGTH));
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
