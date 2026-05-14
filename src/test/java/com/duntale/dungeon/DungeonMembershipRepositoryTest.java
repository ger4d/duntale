package com.duntale.dungeon;

import com.duntale.dungeongen.config.Vec3i;
import com.duntale.db.DatabaseProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DungeonMembershipRepository")
class DungeonMembershipRepositoryTest {

    @TempDir
    Path tempDir;

    private DatabaseProvider database;
    private DungeonInstanceRepository instanceRepository;
    private DungeonMembershipRepository membershipRepository;

    @BeforeEach
    void setUp() throws SQLException {
        database = new DatabaseProvider();
        database.initialize(tempDir.resolve("duntale-test.db"));

        instanceRepository = new DungeonInstanceRepository(database);
        instanceRepository.initialize();

        membershipRepository = new DungeonMembershipRepository(database);
        membershipRepository.initialize();

        instanceRepository.create(new DungeonInstance(
                "instance-a",
                "dungeon-instance-a",
                1,
                64.0D,
                new Vec3i(0, 64, 0),
                new Vec3i(30, 64, 30),
                DungeonInstanceState.ACTIVE,
                "crypt",
                "seed-a",
                1_706_000_000_000L
        ));
        instanceRepository.create(new DungeonInstance(
                "instance-b",
                "dungeon-instance-b",
                1,
                70.0D,
                new Vec3i(2, 70, 2),
                new Vec3i(32, 70, 32),
                DungeonInstanceState.ACTIVE,
                "crypt",
                "seed-b",
                1_706_000_000_500L
        ));
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    @DisplayName("Should create memberships and query them by player and instance")
    void shouldCreateMembershipsAndQueryThemByPlayerAndInstance() throws SQLException {
        UUID playerOne = UUID.randomUUID();
        UUID playerTwo = UUID.randomUUID();

        membershipRepository.addMemberships("instance-a", List.of(playerOne, playerTwo));
        membershipRepository.addMembership("instance-b", playerOne);

        assertEquals(Set.of(playerOne, playerTwo), membershipRepository.findPlayerIdsByInstance("instance-a"));
        assertEquals(Set.of("instance-a", "instance-b"), membershipRepository.findInstanceIdsByPlayer(playerOne));
        assertEquals(Set.of("instance-a"), membershipRepository.findInstanceIdsByPlayer(playerTwo));
    }

    @Test
    @DisplayName("Should persist instance and roster atomically in one shared transaction")
    void shouldPersistInstanceAndRosterAtomicallyInOneSharedTransaction() throws SQLException {
        UUID playerOne = UUID.randomUUID();
        UUID playerTwo = UUID.randomUUID();
        DungeonInstance instance = new DungeonInstance(
                "instance-c",
                "dungeon-instance-c",
                2,
                96.0D,
                new Vec3i(5, 96, 5),
                new Vec3i(45, 96, 45),
                DungeonInstanceState.CREATING,
                "catacombs",
                "seed-c",
                1_706_000_001_000L
        );

        database.transaction(conn -> {
            instanceRepository.createInTransaction(conn, instance);
            membershipRepository.addMembershipsInTransaction(conn, instance.instanceId(), List.of(playerOne, playerTwo));
            return null;
        });

        assertEquals(Optional.of(instance), instanceRepository.findById(instance.instanceId()));
        assertEquals(Set.of(playerOne, playerTwo), membershipRepository.findPlayerIdsByInstance(instance.instanceId()));
    }

    @Test
    @DisplayName("Should roll back batched membership inserts when one insert fails")
    void shouldRollBackBatchedMembershipInsertsWhenOneInsertFails() throws SQLException {
        UUID playerOne = UUID.randomUUID();

        assertThrows(SQLException.class, () ->
                membershipRepository.addMemberships("instance-a", List.of(playerOne, playerOne))
        );

        assertTrue(membershipRepository.findPlayerIdsByInstance("instance-a").isEmpty());
        assertTrue(membershipRepository.findInstanceIdsByPlayer(playerOne).isEmpty());
    }

    @Test
    @DisplayName("Should find non-ended instance ID by player")
    void shouldFindNonEndedInstanceIdByPlayer() throws SQLException {
        UUID player = UUID.randomUUID();
        membershipRepository.addMembership("instance-a", player);

        Optional<String> result = membershipRepository.findNonEndedInstanceIdByPlayer(player);

        assertTrue(result.isPresent());
        assertEquals("instance-a", result.get());
    }

    @Test
    @DisplayName("Should return empty when player's instance is ended")
    void shouldReturnEmptyWhenPlayersInstanceIsEnded() throws SQLException {
        UUID player = UUID.randomUUID();
        membershipRepository.addMembership("instance-a", player);
        instanceRepository.endInstance("instance-a");

        Optional<String> result = membershipRepository.findNonEndedInstanceIdByPlayer(player);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Should return empty when player has no instance")
    void shouldReturnEmptyWhenPlayerHasNoInstance() throws SQLException {
        UUID player = UUID.randomUUID();

        Optional<String> result = membershipRepository.findNonEndedInstanceIdByPlayer(player);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Should detect non-ended instance membership for a player")
    void shouldDetectNonEndedInstanceMembershipForPlayer() throws SQLException {
        UUID player = UUID.randomUUID();
        membershipRepository.addMembership("instance-a", player);

        assertTrue(membershipRepository.hasNonEndedInstance(player));
    }

    @Test
    @DisplayName("Should not detect membership when instance is ended")
    void shouldNotDetectMembershipWhenInstanceIsEnded() throws SQLException {
        UUID player = UUID.randomUUID();
        membershipRepository.addMembership("instance-a", player);

        instanceRepository.endInstance("instance-a");

        assertFalse(membershipRepository.hasNonEndedInstance(player));
    }

    @Test
    @DisplayName("Should not detect membership when player has no instance")
    void shouldNotDetectMembershipWhenPlayerHasNoInstance() throws SQLException {
        UUID player = UUID.randomUUID();

        assertFalse(membershipRepository.hasNonEndedInstance(player));
    }

    @Test
    @DisplayName("Should find blocked players in a roster with mixed active and ended instances")
    void shouldFindBlockedPlayersInRosterWithMixedInstances() throws SQLException {
        UUID activePlayer = UUID.randomUUID();
        UUID endedPlayer = UUID.randomUUID();
        UUID freePlayer = UUID.randomUUID();

        membershipRepository.addMembership("instance-a", activePlayer);
        membershipRepository.addMembership("instance-b", endedPlayer);
        instanceRepository.endInstance("instance-b");

        Set<UUID> blocked = database.read(conn ->
                membershipRepository.findPlayersWithNonEndedInstanceInTransaction(
                        conn, List.of(activePlayer, endedPlayer, freePlayer)));

        assertEquals(Set.of(activePlayer), blocked);
    }

    @Test
    @DisplayName("Should enforce one-active-instance atomically in a shared transaction")
    void shouldEnforceOneActiveInstanceAtomicallyInSharedTransaction() throws SQLException {
        UUID player = UUID.randomUUID();
        membershipRepository.addMembership("instance-a", player);

        DungeonInstance newInstance = new DungeonInstance(
                "instance-c",
                "dungeon-instance-c",
                1,
                80.0D,
                new Vec3i(1, 80, 1),
                new Vec3i(25, 80, 25),
                DungeonInstanceState.CREATING,
                "crypt",
                null,
                1_706_000_002_000L
        );

        assertThrows(IllegalStateException.class, () -> database.transaction(conn -> {
            Set<UUID> blocked = membershipRepository.findPlayersWithNonEndedInstanceInTransaction(
                    conn, List.of(player));
            if (!blocked.isEmpty()) {
                throw new IllegalStateException(
                        "Players already in active instance: " + blocked);
            }
            instanceRepository.createInTransaction(conn, newInstance);
            membershipRepository.addMembershipsInTransaction(conn, newInstance.instanceId(), List.of(player));
            return null;
        }));

        assertTrue(instanceRepository.findById(newInstance.instanceId()).isEmpty());
    }

    @Test
    @DisplayName("Should roll back shared instance transaction when roster insert fails")
    void shouldRollBackSharedInstanceTransactionWhenRosterInsertFails() throws SQLException {
        UUID playerOne = UUID.randomUUID();
        DungeonInstance instance = new DungeonInstance(
                "instance-d",
                "dungeon-instance-d",
                3,
                104.0D,
                new Vec3i(7, 104, 7),
                new Vec3i(55, 104, 55),
                DungeonInstanceState.CREATING,
                "ruins",
                "seed-d",
                1_706_000_001_500L
        );

        assertThrows(SQLException.class, () -> database.transaction(conn -> {
            instanceRepository.createInTransaction(conn, instance);
            membershipRepository.addMembershipsInTransaction(conn, instance.instanceId(), List.of(playerOne, playerOne));
            return null;
        }));

        assertTrue(instanceRepository.findById(instance.instanceId()).isEmpty());
        assertTrue(membershipRepository.findPlayerIdsByInstance(instance.instanceId()).isEmpty());
        assertTrue(membershipRepository.findInstanceIdsByPlayer(playerOne).isEmpty());
    }
}
