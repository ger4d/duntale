package com.duntale.zsquad.death;

import com.duntale.dungeongen.config.Vec3i;
import com.duntale.zsquad.db.DatabaseProvider;
import com.duntale.zsquad.dungeon.DungeonInstance;
import com.duntale.zsquad.dungeon.DungeonInstanceRepository;
import com.duntale.zsquad.dungeon.DungeonInstanceService;
import com.duntale.zsquad.dungeon.DungeonInstanceState;
import com.duntale.zsquad.dungeon.DungeonMembershipRepository;
import com.duntale.zsquad.dungeon.FloorConfigRepository;
import com.duntale.zsquad.dungeon.FloorConfigService;
import com.duntale.zsquad.dungeon.PartyService;
import com.duntale.zsquad.economy.GoldRepository;
import com.duntale.zsquad.economy.GoldService;
import com.hypixel.hytale.server.core.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DungeonRespawnService")
class DungeonRespawnServiceTest {

    @TempDir
    Path tempDir;

    private DatabaseProvider database;
    private DungeonInstanceService dungeonInstanceService;
    private GoldService goldService;
    private DungeonRespawnService service;

    @BeforeEach
    void setUp() throws SQLException {
        database = new DatabaseProvider();
        database.initialize(tempDir.resolve("zsquad-test.db"));

        DungeonInstanceRepository instanceRepository = new DungeonInstanceRepository(database);
        instanceRepository.initialize();

        DungeonMembershipRepository membershipRepository = new DungeonMembershipRepository(database);
        membershipRepository.initialize();

        FloorConfigRepository floorConfigRepository = new FloorConfigRepository(database);
        floorConfigRepository.initialize();
        FloorConfigService floorConfigService = new FloorConfigService(floorConfigRepository);

        dungeonInstanceService = new DungeonInstanceService(
                database,
                instanceRepository,
                membershipRepository,
                new PartyService(),
                floorConfigService
        );

        GoldRepository goldRepository = new GoldRepository(database);
        goldRepository.initialize();
        goldService = new GoldService(goldRepository);
        service = new DungeonRespawnService(dungeonInstanceService, goldService);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    @DisplayName("Should calculate paid option costs from current floor")
    void shouldCalculateCosts() {
        assertEquals(1500L, service.currentFloorCost(3));
        assertEquals(900L, service.lowerFloorCost(3));
    }

    @Test
    @DisplayName("Should reject invalid cost floor")
    void shouldRejectInvalidCostFloor() {
        assertThrows(IllegalArgumentException.class, () -> service.currentFloorCost(0));
        assertThrows(IllegalArgumentException.class, () -> service.lowerFloorCost(0));
    }

    @Test
    @DisplayName("Should mark lower-floor option unavailable on floor one")
    void shouldMarkLowerFloorUnavailableOnFloorOne() {
        assertFalse(service.lowerFloorAvailable(testInstance("inst-1", "world-1", 1, DungeonInstanceState.ACTIVE)));
        assertTrue(service.lowerFloorAvailable(testInstance("inst-2", "world-2", 2, DungeonInstanceState.ACTIVE)));
    }

    @Test
    @DisplayName("Should resolve context for active instance in matching world")
    void shouldResolveContextForActiveMatchingWorld() throws SQLException {
        UUID player = UUID.randomUUID();
        DungeonInstance instance = testInstance("inst-1", "world-1", 3, DungeonInstanceState.ACTIVE);
        dungeonInstanceService.createInstance(instance, List.of(player));
        goldService.addGold(player, 2500L);
        Message deathReason = Message.raw("fell to a trap");

        Optional<DungeonDeathContext> result = service.resolveContext(player, "world-1", deathReason);

        assertTrue(result.isPresent());
        DungeonDeathContext context = result.orElseThrow();
        assertEquals(instance, context.instance());
        assertSame(deathReason, context.deathReason());
        assertEquals(2500L, context.balance());
        assertEquals(1500L, context.currentFloorCost());
        assertEquals(900L, context.lowerFloorCost());
        assertTrue(context.lowerFloorAvailable());
    }

    @Test
    @DisplayName("Should not resolve context for non-dungeon or non-active deaths")
    void shouldRejectUnmatchedContexts() throws SQLException {
        UUID activePlayer = UUID.randomUUID();
        DungeonInstance active = testInstance("inst-1", "world-1", 2, DungeonInstanceState.ACTIVE);
        dungeonInstanceService.createInstance(active, List.of(activePlayer));

        UUID creatingPlayer = UUID.randomUUID();
        DungeonInstance creating = testInstance("inst-2", "world-2", 2, DungeonInstanceState.CREATING);
        dungeonInstanceService.createInstance(creating, List.of(creatingPlayer));

        assertTrue(service.resolveContext(activePlayer, "village", null).isEmpty());
        assertTrue(service.resolveContext(creatingPlayer, "world-2", null).isEmpty());
        assertTrue(service.resolveContext(UUID.randomUUID(), "world-1", null).isEmpty());
    }

    @Test
    @DisplayName("Should deduct paid option cost only when balance is sufficient")
    void shouldChargeOnlyWithSufficientBalance() {
        UUID player = UUID.randomUUID();
        goldService.addGold(player, 1000L);

        assertTrue(service.chargeGold(player, 900L));
        assertEquals(100L, goldService.getBalance(player));
        assertFalse(service.chargeGold(player, 500L));
        assertEquals(100L, goldService.getBalance(player));
    }

    @Test
    @DisplayName("Should refund a failed paid option")
    void shouldRefundFailedPaidOption() {
        UUID player = UUID.randomUUID();
        goldService.addGold(player, 1000L);

        assertTrue(service.chargeGold(player, 900L));
        assertTrue(service.refundGold(player, 900L));

        assertEquals(1000L, goldService.getBalance(player));
    }

    private static DungeonInstance testInstance(
            String instanceId,
            String worldName,
            int floorLevel,
            DungeonInstanceState state
    ) {
        return new DungeonInstance(
                instanceId,
                worldName,
                floorLevel,
                64.0D,
                new Vec3i(0, 64, 0),
                new Vec3i(30, 64, 30),
                state,
                "crypt",
                null,
                1_706_000_000_000L
        );
    }
}