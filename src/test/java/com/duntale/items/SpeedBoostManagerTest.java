package com.duntale.items;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("SpeedBoostManager")
class SpeedBoostManagerTest {

    private static final double EPSILON = 1e-9;

    @Test
    @DisplayName("Should return the applied bonus before it expires")
    void shouldReturnAppliedBonusBeforeExpiry() {
        SpeedBoostManager manager = new SpeedBoostManager();
        UUID player = UUID.randomUUID();

        manager.apply(player, 3.0, 10.0);

        assertEquals(3.0, manager.getBonus(player), EPSILON);
    }

    @Test
    @DisplayName("Should return zero once the boost has expired")
    void shouldReturnZeroOnceExpired() throws InterruptedException {
        SpeedBoostManager manager = new SpeedBoostManager();
        UUID player = UUID.randomUUID();

        manager.apply(player, 4.0, 0.05);
        Thread.sleep(80);

        assertEquals(0.0, manager.getBonus(player), EPSILON);
    }

    @Test
    @DisplayName("Should return zero for a player with no active boost")
    void shouldReturnZeroForUnknownPlayer() {
        SpeedBoostManager manager = new SpeedBoostManager();

        assertEquals(0.0, manager.getBonus(UUID.randomUUID()), EPSILON);
    }

    @Test
    @DisplayName("Should overwrite an existing boost with the newest bonus")
    void shouldOverwriteWithNewestBoost() {
        SpeedBoostManager manager = new SpeedBoostManager();
        UUID player = UUID.randomUUID();

        manager.apply(player, 2.0, 10.0);
        manager.apply(player, 4.0, 10.0);

        assertEquals(4.0, manager.getBonus(player), EPSILON);
    }

    @Test
    @DisplayName("Should clear an active boost")
    void shouldClearActiveBoost() {
        SpeedBoostManager manager = new SpeedBoostManager();
        UUID player = UUID.randomUUID();

        manager.apply(player, 5.0, 10.0);
        manager.clear(player);

        assertEquals(0.0, manager.getBonus(player), EPSILON);
    }
}
