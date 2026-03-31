package com.duntale.zsquad.dungeon;

import com.duntale.dungeongen.config.Vec3i;
import com.duntale.zsquad.db.DatabaseProvider;
import com.duntale.zsquad.dungeon.DungeonInstanceService.RosterValidationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DungeonInstanceService")
class DungeonInstanceServiceTest {

    @TempDir
    Path tempDir;

    private DatabaseProvider database;
    private DungeonInstanceRepository instanceRepository;
    private DungeonMembershipRepository membershipRepository;
    private DungeonInstanceService service;

    @BeforeEach
    void setUp() throws SQLException {
        database = new DatabaseProvider();
        database.initialize(tempDir.resolve("zsquad-test.db"));

        instanceRepository = new DungeonInstanceRepository(database);
        instanceRepository.initialize();

        membershipRepository = new DungeonMembershipRepository(database);
        membershipRepository.initialize();

        service = new DungeonInstanceService(database, instanceRepository, membershipRepository);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    // ============================================
    // createInstance — one-active-instance rule
    // ============================================

    @Nested
    @DisplayName("createInstance")
    class CreateInstance {

        @Test
        @DisplayName("Should create instance and memberships for free players")
        void shouldCreateInstanceAndMembershipsForFreePlayers() throws SQLException {
            UUID playerA = UUID.randomUUID();
            UUID playerB = UUID.randomUUID();
            DungeonInstance instance = testInstance("inst-1", "world-1");

            service.createInstance(instance, List.of(playerA, playerB));

            assertEquals(Optional.of(instance), instanceRepository.findById("inst-1"));
            assertEquals(Set.of(playerA, playerB), membershipRepository.findPlayerIdsByInstance("inst-1"));
        }

        @Test
        @DisplayName("Should reject when any roster member has a non-ended instance")
        void shouldRejectWhenAnyRosterMemberHasActiveInstance() throws SQLException {
            UUID activePlayer = UUID.randomUUID();
            UUID freePlayer = UUID.randomUUID();

            service.createInstance(testInstance("inst-1", "world-1"), List.of(activePlayer));

            RosterValidationException ex = assertThrows(RosterValidationException.class,
                    () -> service.createInstance(
                            testInstance("inst-2", "world-2"),
                            List.of(activePlayer, freePlayer)));

            assertEquals(Set.of(activePlayer), ex.getBlockedPlayers());

            // Verify the second instance was NOT persisted (transaction rolled back)
            assertTrue(instanceRepository.findById("inst-2").isEmpty());
            assertTrue(membershipRepository.findPlayerIdsByInstance("inst-2").isEmpty());
        }

        @Test
        @DisplayName("Should allow roster when player's prior instance is ended")
        void shouldAllowRosterWhenPriorInstanceIsEnded() throws SQLException {
            UUID player = UUID.randomUUID();

            service.createInstance(testInstance("inst-1", "world-1"), List.of(player));
            instanceRepository.endInstance("inst-1");

            assertDoesNotThrow(() ->
                    service.createInstance(testInstance("inst-2", "world-2"), List.of(player)));

            assertEquals(Optional.of(testInstance("inst-2", "world-2")),
                    instanceRepository.findById("inst-2"));
        }

        @Test
        @DisplayName("Should serialize concurrent start attempts for the same player")
        void shouldSerializeConcurrentStartAttemptsForSamePlayer() throws Exception {
            UUID player = UUID.randomUUID();
            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);

            Future<CreateAttemptResult> firstAttempt = executor.submit(() -> attemptCreateInstance(
                    testInstance("inst-1", "world-1"),
                    List.of(player),
                    ready,
                    start
            ));
            Future<CreateAttemptResult> secondAttempt = executor.submit(() -> attemptCreateInstance(
                    testInstance("inst-2", "world-2"),
                    List.of(player),
                    ready,
                    start
            ));

            try {
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();

                CreateAttemptResult firstResult = firstAttempt.get(5, TimeUnit.SECONDS);
                CreateAttemptResult secondResult = secondAttempt.get(5, TimeUnit.SECONDS);

                List<CreateAttemptResult> results = List.of(firstResult, secondResult);
                long successCount = results.stream().filter(CreateAttemptResult::wasCreated).count();
                long blockedCount = results.stream()
                        .filter(result -> result.blockedPlayers() != null)
                        .count();

                assertEquals(1L, successCount);
                assertEquals(1L, blockedCount);

                for (CreateAttemptResult result : results) {
                    assertNull(result.unexpectedFailure());
                    if (result.blockedPlayers() != null) {
                        assertEquals(Set.of(player), result.blockedPlayers());
                    }
                }
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }

            int persistedInstanceCount = 0;
            if (instanceRepository.findById("inst-1").isPresent()) {
                persistedInstanceCount++;
            }
            if (instanceRepository.findById("inst-2").isPresent()) {
                persistedInstanceCount++;
            }
            assertEquals(1, persistedInstanceCount);
        }

        @Test
        @DisplayName("Should reject when party member is blocked even if owner is free")
        void shouldRejectWhenPartyMemberIsBlockedEvenIfOwnerIsFree() throws SQLException {
            UUID blockedMember = UUID.randomUUID();
            UUID freeOwner = UUID.randomUUID();

            service.createInstance(testInstance("inst-1", "world-1"), List.of(blockedMember));

            RosterValidationException ex = assertThrows(RosterValidationException.class,
                    () -> service.createInstance(
                            testInstance("inst-2", "world-2"),
                            List.of(freeOwner, blockedMember)));

            assertEquals(Set.of(blockedMember), ex.getBlockedPlayers());
        }

        @Test
        @DisplayName("Should reject empty roster")
        void shouldRejectEmptyRoster() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.createInstance(testInstance("inst-1", "world-1"), List.of()));
        }

        @Test
        @DisplayName("Should roll back instance row when membership insert fails")
        void shouldRollBackInstanceRowWhenMembershipInsertFails() throws SQLException {
            UUID player = UUID.randomUUID();

            // First: create a valid instance with this player
            service.createInstance(testInstance("inst-1", "world-1"), List.of(player));

            // Second: attempt with duplicate player triggers constraint violation
            assertThrows(Exception.class,
                    () -> service.createInstance(testInstance("inst-2", "world-2"), List.of(player)));

            // The second instance should not exist
            assertTrue(instanceRepository.findById("inst-2").isEmpty());
        }
    }

    // ============================================
    // Helpers
    // ============================================

    private CreateAttemptResult attemptCreateInstance(
            DungeonInstance instance,
            List<UUID> playerIds,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                return CreateAttemptResult.failure(new IllegalStateException("Timed out waiting for start latch"));
            }
            service.createInstance(instance, playerIds);
            return CreateAttemptResult.success();
        } catch (DungeonInstanceService.RosterValidationException ex) {
            return CreateAttemptResult.blockedResult(ex.getBlockedPlayers());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return CreateAttemptResult.failure(ex);
        } catch (Exception ex) {
            return CreateAttemptResult.failure(ex);
        }
    }

    private record CreateAttemptResult(
            boolean created,
            Set<UUID> blockedPlayers,
            Exception unexpectedFailure
    ) {

        private boolean wasCreated() {
            return created;
        }

        private static CreateAttemptResult success() {
            return new CreateAttemptResult(true, null, null);
        }

        private static CreateAttemptResult blockedResult(Set<UUID> blockedPlayers) {
            return new CreateAttemptResult(false, Set.copyOf(blockedPlayers), null);
        }

        private static CreateAttemptResult failure(Exception unexpectedFailure) {
            return new CreateAttemptResult(false, null, unexpectedFailure);
        }
    }

    private static DungeonInstance testInstance(String instanceId, String worldName) {
        return new DungeonInstance(
                instanceId,
                worldName,
                1,
                64.0D,
                new Vec3i(0, 64, 0),
                new Vec3i(30, 64, 30),
                DungeonInstanceState.CREATING,
                "crypt",
                null,
                1_706_000_000_000L
        );
    }
}
