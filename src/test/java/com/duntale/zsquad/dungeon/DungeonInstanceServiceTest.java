package com.duntale.zsquad.dungeon;

import com.duntale.dungeongen.config.DungeonConfig;
import com.duntale.dungeongen.config.Vec3i;
import com.duntale.dungeongen.generator.GenerationResult;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    @DisplayName("createInstance persistence")
    class CreateInstancePersistence {

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

            service.createInstance(testInstance("inst-1", "world-1"), List.of(player));

            assertThrows(Exception.class,
                    () -> service.createInstance(testInstance("inst-2", "world-2"), List.of(player)));

            assertTrue(instanceRepository.findById("inst-2").isEmpty());
        }
    }

    // ============================================
    // createInstance — Batch 5 runtime flow
    // ============================================

    @Nested
    @DisplayName("createInstance runtime flow")
    class CreateInstanceRuntimeFlow {

        @Test
        @DisplayName("Should keep instance creating until the initial transfer completes")
        void shouldKeepInstanceCreatingUntilTheInitialTransferCompletes() throws SQLException {
            FakeRuntime runtime = new FakeRuntime();
            runtime.deferWorldCreation();
            runtime.deferGeneration();
            runtime.deferTeleport();
            service = new DungeonInstanceService(
                    database,
                    instanceRepository,
                    membershipRepository,
                    new PartyService(),
                    runtime
            );

            UUID player = UUID.randomUUID();
            CompletableFuture<DungeonInstance> future = service.createInstance(List.of(player), 1, "Crypt");

            List<DungeonInstance> pendingInstances = instanceRepository.findAll();
            assertEquals(1, pendingInstances.size());
            DungeonInstance pending = pendingInstances.get(0);
            assertEquals(DungeonInstanceState.CREATING, pending.state());
            assertEquals("crypt", pending.theme());
            assertFalse(future.isDone());

            runtime.completeWorldCreation();
            assertFalse(future.isDone());

            runtime.completeGeneration(successGenerationResult());
            DungeonInstance ready = instanceRepository.findById(pending.instanceId()).orElseThrow();
            assertEquals(DungeonInstanceState.CREATING, ready.state());
            assertEquals(new Vec3i(5, 1, 7), ready.entrancePosition());
            assertEquals(new Vec3i(30, 1, 31), ready.exitPosition());
            assertFalse(future.isDone());

            runtime.completeTeleport();
            DungeonInstance active = future.join();

            assertEquals(pending.instanceId(), active.instanceId());
            assertEquals(DungeonInstanceState.ACTIVE, active.state());
            assertEquals(Optional.of(active), instanceRepository.findById(active.instanceId()));
            assertEquals(Set.of(player), membershipRepository.findPlayerIdsByInstance(active.instanceId()));
            assertEquals(Set.of(player), Set.copyOf(runtime.teleportedPlayers));
            assertEquals(active.worldName(), runtime.createdWorldNames.get(0));
            assertEquals(active.entrancePosition(), runtime.finalizedEntrances.get(active.worldName()));
            assertTrue(runtime.cleanedWorlds.isEmpty());
            assertNotNull(runtime.generatedConfig);
            assertTrue(runtime.generatedConfig.assemble());
            assertEquals("crypt", runtime.generatedConfig.theme().palette());
        }

        @Test
        @DisplayName("Should assemble the party roster when creating for a player")
        void shouldAssembleThePartyRosterWhenCreatingForAPlayer() throws SQLException {
            FakeRuntime runtime = new FakeRuntime();
            PartyService partyService = new PartyService();
            service = new DungeonInstanceService(
                    database,
                    instanceRepository,
                    membershipRepository,
                    partyService,
                    runtime
            );

            UUID owner = UUID.randomUUID();
            UUID member = UUID.randomUUID();
            assertTrue(partyService.createParty(owner));
            assertEquals(PartyService.InviteResult.SUCCESS, partyService.invitePlayer(owner, member));

            DungeonInstance active = service.createInstanceForPlayer(owner, 1, "crypt").join();

            assertEquals(Set.of(owner, member), membershipRepository.findPlayerIdsByInstance(active.instanceId()));
            assertEquals(Set.of(owner, member), Set.copyOf(runtime.teleportedPlayers));
        }

        @Test
        @DisplayName("Should clean up the failed world and end the instance when generation fails")
        void shouldCleanUpTheFailedWorldAndEndTheInstanceWhenGenerationFails() throws SQLException {
            FakeRuntime failingRuntime = new FakeRuntime();
            failingRuntime.nextGenerationResult = generationResult(
                    new Vec3i(5, 1, 7),
                    new Vec3i(30, 1, 31),
                    "assembly failed"
            );
            service = new DungeonInstanceService(
                    database,
                    instanceRepository,
                    membershipRepository,
                    new PartyService(),
                    failingRuntime
            );

            UUID player = UUID.randomUUID();
            CompletableFuture<DungeonInstance> future = service.createInstance(List.of(player), 1, "crypt");

            CompletionException exception = assertThrows(CompletionException.class, future::join);
            assertEquals("assembly failed", exception.getCause().getMessage());

            List<DungeonInstance> persisted = instanceRepository.findAll();
            assertEquals(1, persisted.size());
            DungeonInstance failed = persisted.get(0);
            DungeonInstance failedPersisted = instanceRepository.findById(failed.instanceId()).orElseThrow();
            assertEquals(DungeonInstanceState.ENDED, failedPersisted.state());
            assertEquals(List.of(failed.worldName()), failingRuntime.cleanedWorlds);

            FakeRuntime retryRuntime = new FakeRuntime();
            DungeonInstanceService retryService = new DungeonInstanceService(
                    database,
                    instanceRepository,
                    membershipRepository,
                    new PartyService(),
                    retryRuntime
            );

            DungeonInstance retried = retryService.createInstance(List.of(player), 1, "crypt").join();
            assertEquals(DungeonInstanceState.ACTIVE, retried.state());
            assertEquals(Set.of(player), membershipRepository.findPlayerIdsByInstance(retried.instanceId()));
        }
    }

    // ============================================
    // loadOnStartup
    // ============================================

    @Nested
    @DisplayName("loadOnStartup")
    class LoadOnStartup {

        @Test
        @DisplayName("Should complete without error when no instances exist")
        void shouldCompleteWithoutErrorWhenNoInstancesExist() {
            DungeonInstanceService freshService = new DungeonInstanceService(
                    database, instanceRepository, membershipRepository);
            assertDoesNotThrow(freshService::loadOnStartup);
        }

        @Test
        @DisplayName("Should preserve active instances on startup")
        void shouldPreserveActiveInstancesOnStartup() throws SQLException {
            UUID player = UUID.randomUUID();
            service.createInstance(
                    testInstanceWithState("inst-1", "world-1", DungeonInstanceState.ACTIVE),
                    List.of(player));

            FakeRuntime restartRuntime = new FakeRuntime();
            DungeonInstanceService freshService = new DungeonInstanceService(
                    database, instanceRepository, membershipRepository, new PartyService(), restartRuntime);
            freshService.loadOnStartup();

            DungeonInstance loaded = instanceRepository.findById("inst-1").orElseThrow();
            assertEquals(DungeonInstanceState.ACTIVE, loaded.state());
            assertTrue(restartRuntime.cleanedWorlds.isEmpty());
        }

        @Test
        @DisplayName("Should end interrupted CREATING instances and clean up their worlds on startup")
        void shouldEndInterruptedCreatingInstancesAndCleanUpTheirWorldsOnStartup() throws SQLException {
            UUID player = UUID.randomUUID();
            service.createInstance(testInstance("inst-1", "world-1"), List.of(player));

            FakeRuntime restartRuntime = new FakeRuntime();
            DungeonInstanceService freshService = new DungeonInstanceService(
                    database, instanceRepository, membershipRepository, new PartyService(), restartRuntime);
            freshService.loadOnStartup();

            DungeonInstance loaded = instanceRepository.findById("inst-1").orElseThrow();
            assertEquals(DungeonInstanceState.ENDED, loaded.state());
            assertEquals(List.of("world-1"), restartRuntime.cleanedWorlds);
        }

        @Test
        @DisplayName("Should revert interrupted TRANSITIONING instances to ACTIVE on startup")
        void shouldRevertInterruptedTransitioningInstancesToActiveOnStartup() throws SQLException {
            UUID player = UUID.randomUUID();
            service.createInstance(
                    testInstanceWithState("inst-1", "world-1", DungeonInstanceState.TRANSITIONING),
                    List.of(player));

            FakeRuntime restartRuntime = new FakeRuntime();
            DungeonInstanceService freshService = new DungeonInstanceService(
                    database, instanceRepository, membershipRepository, new PartyService(), restartRuntime);
            freshService.loadOnStartup();

            DungeonInstance loaded = instanceRepository.findById("inst-1").orElseThrow();
            assertEquals(DungeonInstanceState.ACTIVE, loaded.state());
            assertTrue(restartRuntime.cleanedWorlds.isEmpty());
        }

        @Test
        @DisplayName("Should handle mixed states on startup and only clean up CREATING worlds")
        void shouldHandleMixedStatesOnStartupAndOnlyCleanUpCreatingWorlds() throws SQLException {
            UUID p1 = UUID.randomUUID();
            UUID p2 = UUID.randomUUID();
            UUID p3 = UUID.randomUUID();

            service.createInstance(
                    testInstanceWithState("inst-active", "world-active", DungeonInstanceState.ACTIVE),
                    List.of(p1));
            service.createInstance(
                    testInstance("inst-creating", "world-creating"),
                    List.of(p2));
            service.createInstance(
                    testInstanceWithState("inst-transitioning", "world-transitioning", DungeonInstanceState.TRANSITIONING),
                    List.of(p3));

            FakeRuntime restartRuntime = new FakeRuntime();
            DungeonInstanceService freshService = new DungeonInstanceService(
                    database, instanceRepository, membershipRepository, new PartyService(), restartRuntime);
            freshService.loadOnStartup();

            assertEquals(DungeonInstanceState.ACTIVE,
                    instanceRepository.findById("inst-active").orElseThrow().state());
            assertEquals(DungeonInstanceState.ENDED,
                    instanceRepository.findById("inst-creating").orElseThrow().state());
            assertEquals(DungeonInstanceState.ACTIVE,
                    instanceRepository.findById("inst-transitioning").orElseThrow().state());
            assertEquals(List.of("world-creating"), restartRuntime.cleanedWorlds);
        }
    }

    // ============================================
    // getActiveInstance + getInstanceByWorld
    // ============================================

    @Nested
    @DisplayName("runtime lookups")
    class RuntimeLookups {

        @Test
        @DisplayName("Should return active instance for player")
        void shouldReturnActiveInstanceForPlayer() throws SQLException {
            UUID player = UUID.randomUUID();
            DungeonInstance instance = testInstanceWithState("inst-1", "world-1", DungeonInstanceState.ACTIVE);
            service.createInstance(instance, List.of(player));

            DungeonInstance result = service.getActiveInstance(player);

            assertNotNull(result);
            assertEquals("inst-1", result.instanceId());
            assertEquals(DungeonInstanceState.ACTIVE, result.state());
        }

        @Test
        @DisplayName("Should return null when player has no instance")
        void shouldReturnNullWhenPlayerHasNoInstance() throws SQLException {
            UUID player = UUID.randomUUID();

            assertNull(service.getActiveInstance(player));
        }

        @Test
        @DisplayName("Should return null when player's instance is ended")
        void shouldReturnNullWhenPlayersInstanceIsEnded() throws SQLException {
            UUID player = UUID.randomUUID();
            DungeonInstance instance = testInstanceWithState("inst-1", "world-1", DungeonInstanceState.ACTIVE);
            service.createInstance(instance, List.of(player));
            instanceRepository.endInstance("inst-1");

            assertNull(service.getActiveInstance(player));
        }

        @Test
        @DisplayName("Should return instance by world name")
        void shouldReturnInstanceByWorldName() throws SQLException {
            UUID player = UUID.randomUUID();
            DungeonInstance instance = testInstance("inst-1", "world-1");
            service.createInstance(instance, List.of(player));

            DungeonInstance result = service.getInstanceByWorld("world-1");

            assertNotNull(result);
            assertEquals("inst-1", result.instanceId());
        }

        @Test
        @DisplayName("Should return null for unknown world name")
        void shouldReturnNullForUnknownWorldName() throws SQLException {
            assertNull(service.getInstanceByWorld("nonexistent-world"));
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

    private static final class FakeRuntime implements DungeonInstanceService.RuntimeAdapter {

        private final List<String> createdWorldNames = new ArrayList<>();
        private final List<String> cleanedWorlds = new ArrayList<>();
        private final List<UUID> teleportedPlayers = new ArrayList<>();

        private DungeonConfig generatedConfig;
        private CompletableFuture<DungeonInstanceService.InstanceWorld> deferredWorldCreation;
        private CompletableFuture<GenerationResult> deferredGeneration;
        private CompletableFuture<Void> deferredTeleport;
        private GenerationResult nextGenerationResult = successGenerationResult();
        private String deferredWorldName;
        private final java.util.Map<String, Vec3i> finalizedEntrances = new java.util.HashMap<>();

        @Override
        public CompletableFuture<DungeonInstanceService.InstanceWorld> createWorld(
                String worldName,
                int floorLevel,
                String seed,
                Vec3i origin
        ) {
            createdWorldNames.add(worldName);
            if (deferredWorldCreation != null) {
                deferredWorldName = worldName;
                return deferredWorldCreation;
            }
            return CompletableFuture.completedFuture(new FakeWorld(worldName));
        }

        @Override
        public CompletableFuture<GenerationResult> generate(DungeonConfig config) {
            generatedConfig = config;
            if (deferredGeneration != null) {
                return deferredGeneration;
            }
            return CompletableFuture.completedFuture(nextGenerationResult);
        }

        @Override
        public CompletableFuture<Void> finalizeWorld(
                DungeonInstanceService.InstanceWorld world,
                Vec3i origin,
                Vec3i entrancePosition,
                GenerationResult result
        ) {
            finalizedEntrances.put(world.worldName(), entrancePosition);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> teleportRoster(
                Collection<UUID> playerIds,
                DungeonInstanceService.InstanceWorld world,
                Vec3i entrancePosition
        ) {
            teleportedPlayers.addAll(playerIds);
            if (deferredTeleport != null) {
                return deferredTeleport;
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void cleanupWorld(String worldName) {
            cleanedWorlds.add(worldName);
        }

        private void deferWorldCreation() {
            deferredWorldCreation = new CompletableFuture<>();
        }

        private void deferGeneration() {
            deferredGeneration = new CompletableFuture<>();
        }

        private void completeWorldCreation() {
            deferredWorldCreation.complete(new FakeWorld(deferredWorldName));
        }

        private void completeGeneration(GenerationResult result) {
            deferredGeneration.complete(result);
        }

        private void deferTeleport() {
            deferredTeleport = new CompletableFuture<>();
        }

        private void completeTeleport() {
            deferredTeleport.complete(null);
        }
    }

    private record FakeWorld(String worldName) implements DungeonInstanceService.InstanceWorld {
    }

    private static GenerationResult successGenerationResult() {
        return generationResult(new Vec3i(5, 1, 7), new Vec3i(30, 1, 31), null);
    }

    private static GenerationResult generationResult(Vec3i entrance, Vec3i exit, String assemblyError) {
        return new GenerationResult(
                "seed-1",
                8,
                7,
                1024,
                4,
                List.of(),
                1,
                List.of(),
                entrance,
                exit,
                20L,
                10L,
                assemblyError
        );
    }

    private static DungeonInstance testInstance(String instanceId, String worldName) {
        return testInstanceWithState(instanceId, worldName, DungeonInstanceState.CREATING);
    }

    private static DungeonInstance testInstanceWithState(
            String instanceId,
            String worldName,
            DungeonInstanceState state
    ) {
        return new DungeonInstance(
                instanceId,
                worldName,
                1,
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
