package com.duntale.death;

import com.duntale.db.DatabaseProvider;
import com.duntale.dungeon.DungeonInstanceRepository;
import com.duntale.dungeon.DungeonInstanceService;
import com.duntale.dungeon.DungeonMembershipRepository;
import com.duntale.dungeon.FloorConfigAssetRepository;
import com.duntale.dungeon.FloorConfigService;
import com.duntale.dungeon.PartyService;
import com.duntale.economy.GoldRepository;
import com.duntale.economy.GoldService;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DungeonDeathScreenSystem")
class DungeonDeathScreenSystemTest {

    @TempDir
    Path tempDir;

    private DatabaseProvider database;
    private DungeonRespawnService respawnService;

    @BeforeEach
    void setUp() throws SQLException {
        database = new DatabaseProvider();
        database.initialize(tempDir.resolve("duntale-test.db"));

        DungeonInstanceRepository instanceRepository = new DungeonInstanceRepository(database);
        instanceRepository.initialize();
        DungeonMembershipRepository membershipRepository = new DungeonMembershipRepository(database);
        membershipRepository.initialize();
        DungeonInstanceService dungeonInstanceService = new DungeonInstanceService(
                database,
                instanceRepository,
                membershipRepository,
                new PartyService(),
            new FloorConfigService(new FloorConfigAssetRepository())
        );

        GoldRepository goldRepository = new GoldRepository(database);
        goldRepository.initialize();
        respawnService = new DungeonRespawnService(dungeonInstanceService, new GoldService(goldRepository));
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    @DisplayName("Should depend on running before built-in player death screen")
    void shouldRunBeforeBuiltInPlayerDeathScreen() {
        DungeonDeathScreenSystem system = new DungeonDeathScreenSystem(respawnService);

        assertTrue(system.getDependencies().stream().anyMatch(dependency ->
                dependency.getOrder() == Order.BEFORE
                        && dependency instanceof SystemDependency<?, ?> systemDependency
                        && systemDependency.getSystemClass().equals(DeathSystems.PlayerDeathScreen.class)));
    }

    @Test
    @DisplayName("Should require respawn service")
    void shouldRequireRespawnService() {
        assertThrows(NullPointerException.class, () -> new DungeonDeathScreenSystem(null));
    }
}