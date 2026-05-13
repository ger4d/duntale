package com.duntale.zsquad.dungeon;

import com.duntale.dungeongen.config.DungeonConfig;
import com.duntale.dungeongen.config.Vec3i;
import com.duntale.dungeongen.generator.GenerationResult;
import com.duntale.zsquad.db.DatabaseProvider;
import com.duntale.zsquad.dungeon.DungeonInstanceService.RosterValidationException;
import com.hypixel.hytale.math.vector.Transform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DungeonInstanceService")
class DungeonInstanceServiceTest {

    @TempDir
    Path tempDir;

    private DatabaseProvider database;
    private DungeonInstanceRepository instanceRepository;
    private DungeonMembershipRepository membershipRepository;
    private FloorConfigService floorConfigService;
    private DungeonInstanceService service;

    @BeforeEach
    void setUp() throws SQLException {
        database = new DatabaseProvider();
        database.initialize(tempDir.resolve("zsquad-test.db"));

        instanceRepository = new DungeonInstanceRepository(database);
        instanceRepository.initialize();

        membershipRepository = new DungeonMembershipRepository(database);
        membershipRepository.initialize();

        FloorConfigRepository floorConfigRepository = new FloorConfigRepository(database);
        floorConfigRepository.initialize();
        floorConfigService = new FloorConfigService(floorConfigRepository, TreeMap::new);

        service = new DungeonInstanceService(database, instanceRepository, membershipRepository, new PartyService(), floorConfigService);
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
                    floorConfigService,
                    runtime
            );

            UUID player = UUID.randomUUID();
            CompletableFuture<DungeonInstance> future = service.createInstance(List.of(player), 1);

            List<DungeonInstance> pendingInstances = instanceRepository.findAll();
            assertEquals(1, pendingInstances.size());
            DungeonInstance pending = pendingInstances.get(0);
            assertEquals(DungeonInstanceState.CREATING, pending.state());
            assertEquals(20.0D, pending.floorY());
            assertEquals("crypt", pending.theme());
            assertFalse(future.isDone());

            runtime.completeWorldCreation();
            assertFalse(future.isDone());

            runtime.completeGeneration(successGenerationResult());
            DungeonInstance ready = instanceRepository.findById(pending.instanceId()).orElseThrow();
            assertEquals(DungeonInstanceState.CREATING, ready.state());
            assertEquals(new Vec3i(5, 21, 7), ready.entrancePosition());
            assertEquals(new Vec3i(30, 21, 31), ready.exitPosition());
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
            DungeonInstance finalized = runtime.finalizedInstances.get(active.worldName());
            assertNotNull(finalized);
            assertEquals(DungeonInstanceState.CREATING, finalized.state());
            assertEquals(active.floorLevel(), finalized.floorLevel());
            assertEquals(active.entrancePosition(), finalized.entrancePosition());
            assertEquals(active.exitPosition(), finalized.exitPosition());
            assertTrue(runtime.cleanedWorlds.isEmpty());
            assertNotNull(runtime.generatedConfig);
            assertTrue(runtime.generatedConfig.assemble());
            assertEquals(new Vec3i(0, 20, 0), runtime.generatedConfig.origin());
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
                    floorConfigService,
                    runtime
            );

            UUID owner = UUID.randomUUID();
            UUID member = UUID.randomUUID();
            assertTrue(partyService.createParty(owner));
            assertEquals(PartyService.InviteResult.SUCCESS, partyService.invitePlayer(owner, member));

            DungeonInstance active = service.createInstanceForPlayer(owner, 1).join();

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
                    floorConfigService,
                    failingRuntime
            );

            UUID player = UUID.randomUUID();
            CompletableFuture<DungeonInstance> future = service.createInstance(List.of(player), 1);

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
                    floorConfigService,
                    retryRuntime
            );

            DungeonInstance retried = retryService.createInstance(List.of(player), 1).join();
            assertEquals(DungeonInstanceState.ACTIVE, retried.state());
            assertEquals(Set.of(player), membershipRepository.findPlayerIdsByInstance(retried.instanceId()));
        }

        @Test
        @DisplayName("Should pick the active floor theme from resolved theme variants on creation")
        void shouldPickTheActiveFloorThemeFromResolvedThemeVariantsOnCreation() throws SQLException {
            FakeRuntime runtime = new FakeRuntime();
            floorConfigService.setOverride(1, "theme.variants", List.of("arcane", "hive"));
            service = new DungeonInstanceService(
                    database,
                    instanceRepository,
                    membershipRepository,
                    new PartyService(),
                    floorConfigService,
                    runtime,
                    theme -> Set.of("arcane", "hive").contains(theme)
            );

            UUID player = UUID.randomUUID();
            DungeonInstance active = service.createInstance(List.of(player), 1).join();

            assertTrue(Set.of("arcane", "hive").contains(active.theme()));
            assertEquals(active.theme(), runtime.generatedConfig.theme().palette());
        }

        @Test
        @DisplayName("Should fall back to crypt when resolved themes are unavailable")
        void shouldFallBackToCryptWhenResolvedThemesAreUnavailable() throws SQLException {
            FakeRuntime runtime = new FakeRuntime();
            floorConfigService.setOverride(1, "theme.variants", List.of("arcane", "hive"));
            service = new DungeonInstanceService(
                    database,
                    instanceRepository,
                    membershipRepository,
                    new PartyService(),
                    floorConfigService,
                    runtime,
                    theme -> false
            );

            UUID player = UUID.randomUUID();
            DungeonInstance active = service.createInstance(List.of(player), 1).join();

            assertEquals("crypt", active.theme());
            assertEquals("crypt", runtime.generatedConfig.theme().palette());
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
                    database, instanceRepository, membershipRepository, new PartyService(), floorConfigService);
            assertDoesNotThrow(freshService::loadOnStartup);
        }

        @Test
        @DisplayName("Should preserve active instances on startup")
        void shouldPreserveActiveInstancesOnStartup() throws SQLException {
            UUID player = UUID.randomUUID();
            service.createInstance(
                    testInstanceWithState("inst-1", "world-1", DungeonInstanceState.ACTIVE),
                    List.of(player));

            FakeRuntime restartRuntime = restartRuntimeAfterWorldsLoaded();
            DungeonInstanceService freshService = new DungeonInstanceService(
                    database, instanceRepository, membershipRepository, new PartyService(), floorConfigService, restartRuntime);
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

            FakeRuntime restartRuntime = restartRuntimeAfterWorldsLoaded();
            DungeonInstanceService freshService = new DungeonInstanceService(
                    database, instanceRepository, membershipRepository, new PartyService(), floorConfigService, restartRuntime);
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

            FakeRuntime restartRuntime = restartRuntimeAfterWorldsLoaded();
            DungeonInstanceService freshService = new DungeonInstanceService(
                    database, instanceRepository, membershipRepository, new PartyService(), floorConfigService, restartRuntime);
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

            FakeRuntime restartRuntime = restartRuntimeAfterWorldsLoaded();
            DungeonInstanceService freshService = new DungeonInstanceService(
                    database, instanceRepository, membershipRepository, new PartyService(), floorConfigService, restartRuntime);
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
    // transitionFloor
    // ============================================

    @Nested
    @DisplayName("transitionFloor")
    class FloorTransition {

        @Test
        @DisplayName("Should transition active instance to next floor")
        void shouldTransitionActiveInstanceToNextFloor() throws SQLException {
            FakeRuntime runtime = new FakeRuntime();
            service = new DungeonInstanceService(
                    database, instanceRepository, membershipRepository, new PartyService(), floorConfigService, runtime);

            UUID player = UUID.randomUUID();
            DungeonInstance active = service.createInstance(List.of(player), 1).join();
            assertEquals(DungeonInstanceState.ACTIVE, active.state());
            assertEquals(1, active.floorLevel());

            DungeonInstance transitioned = service.transitionFloor(active.instanceId()).join();

            assertEquals(active.instanceId(), transitioned.instanceId());
            assertEquals(DungeonInstanceState.ACTIVE, transitioned.state());
            assertEquals(2, transitioned.floorLevel());
            assertEquals(new Vec3i(5, 21, 7), transitioned.entrancePosition());
            assertEquals(new Vec3i(30, 21, 31), transitioned.exitPosition());
            assertTrue(transitioned.worldName().contains("-f2"));

            DungeonInstance finalized = runtime.finalizedInstances.get(transitioned.worldName());
            assertNotNull(finalized);
            assertEquals(DungeonInstanceState.TRANSITIONING, finalized.state());
            assertEquals(2, finalized.floorLevel());
            assertEquals(transitioned.entrancePosition(), finalized.entrancePosition());
            assertEquals(transitioned.exitPosition(), finalized.exitPosition());

            DungeonInstance persisted = instanceRepository.findById(active.instanceId()).orElseThrow();
            assertEquals(transitioned, persisted);
            assertEquals(Set.of(player), membershipRepository.findPlayerIdsByInstance(active.instanceId()));
            assertEquals(new Vec3i(0, 20, 0), runtime.generatedConfig.origin());
        }

        @Test
        @DisplayName("Should arm old world for removal after transition")
        void shouldArmOldWorldForRemovalAfterTransition() throws SQLException {
            FakeRuntime runtime = new FakeRuntime();
            service = new DungeonInstanceService(
                    database, instanceRepository, membershipRepository, new PartyService(), floorConfigService, runtime);

            UUID player = UUID.randomUUID();
            DungeonInstance active = service.createInstance(List.of(player), 1).join();
            String oldWorldName = active.worldName();

            service.transitionFloor(active.instanceId()).join();

            assertEquals(List.of(oldWorldName), runtime.armedWorlds);
            assertTrue(runtime.cleanedWorlds.isEmpty());
        }

        @Test
        @DisplayName("Should teleport roster to new world entrance")
        void shouldTeleportRosterToNewWorldEntrance() throws SQLException {
            FakeRuntime runtime = new FakeRuntime();
            service = new DungeonInstanceService(
                    database, instanceRepository, membershipRepository, new PartyService(), floorConfigService, runtime);

            UUID playerA = UUID.randomUUID();
            UUID playerB = UUID.randomUUID();
            PartyService partyService = new PartyService();
            service = new DungeonInstanceService(
                    database, instanceRepository, membershipRepository, partyService, floorConfigService, runtime);
            assertTrue(partyService.createParty(playerA));
            assertEquals(PartyService.InviteResult.SUCCESS, partyService.invitePlayer(playerA, playerB));

            DungeonInstance active = service.createInstanceForPlayer(playerA, 1).join();
            runtime.teleportedPlayers.clear();

            service.transitionFloor(active.instanceId()).join();

            assertEquals(Set.of(playerA, playerB), Set.copyOf(runtime.teleportedPlayers));
        }

        @Test
        @DisplayName("Should reject transition when instance is not active")
        void shouldRejectTransitionWhenInstanceIsNotActive() throws SQLException {
            UUID player = UUID.randomUUID();
            DungeonInstance creating = testInstance("inst-1", "world-1");
            service.createInstance(creating, List.of(player));

            assertThrows(IllegalStateException.class,
                    () -> service.transitionFloor("inst-1"));
        }

        @Test
        @DisplayName("Should reject transition when instance is already transitioning")
        void shouldRejectTransitionWhenInstanceIsAlreadyTransitioning() throws SQLException {
            UUID player = UUID.randomUUID();
            DungeonInstance transitioning = testInstanceWithState(
                    "inst-1", "world-1", DungeonInstanceState.TRANSITIONING);
            service.createInstance(transitioning, List.of(player));

            assertThrows(IllegalStateException.class,
                    () -> service.transitionFloor("inst-1"));
        }

        @Test
        @DisplayName("Should reject transition for nonexistent instance")
        void shouldRejectTransitionForNonexistentInstance() {
            assertThrows(IllegalStateException.class,
                    () -> service.transitionFloor("nonexistent"));
        }

        @Test
        @DisplayName("Should clean up new world and revert to active on generation failure")
        void shouldCleanUpNewWorldAndRevertToActiveOnGenerationFailure() throws SQLException {
            FakeRuntime runtime = new FakeRuntime();
            service = new DungeonInstanceService(
                    database, instanceRepository, membershipRepository, new PartyService(), floorConfigService, runtime);

            UUID player = UUID.randomUUID();
            DungeonInstance active = service.createInstance(List.of(player), 1).join();
            String oldWorldName = active.worldName();

            runtime.nextGenerationResult = generationResult(
                    new Vec3i(5, 1, 7),
                    new Vec3i(30, 1, 31),
                    "assembly failed"
            );

            CompletableFuture<DungeonInstance> future = service.transitionFloor(active.instanceId());
            CompletionException ex = assertThrows(CompletionException.class, future::join);
            assertEquals("assembly failed", ex.getCause().getMessage());

            DungeonInstance reverted = instanceRepository.findById(active.instanceId()).orElseThrow();
            assertEquals(DungeonInstanceState.ACTIVE, reverted.state());
            assertEquals(1, reverted.floorLevel());
            assertEquals(oldWorldName, reverted.worldName());
            assertTrue(runtime.armedWorlds.isEmpty());
            assertEquals(1, runtime.cleanedWorlds.size());
            assertTrue(runtime.cleanedWorlds.get(0).contains("-f2"));
        }

        @Test
        @DisplayName("Should keep instance transitioning until teleport completes")
        void shouldKeepInstanceTransitioningUntilTeleportCompletes() throws SQLException {
            FakeRuntime runtime = new FakeRuntime();
            service = new DungeonInstanceService(
                    database, instanceRepository, membershipRepository, new PartyService(), floorConfigService, runtime);

            UUID player = UUID.randomUUID();
            DungeonInstance active = service.createInstance(List.of(player), 1).join();

            runtime.deferTeleport();
            CompletableFuture<DungeonInstance> future = service.transitionFloor(active.instanceId());

            DungeonInstance midTransition = instanceRepository.findById(active.instanceId()).orElseThrow();
            assertEquals(DungeonInstanceState.TRANSITIONING, midTransition.state());
            assertEquals(2, midTransition.floorLevel());
            assertFalse(future.isDone());

            runtime.completeTeleport();

            DungeonInstance transitioned = future.join();
            assertEquals(DungeonInstanceState.ACTIVE, transitioned.state());
            assertEquals(2, transitioned.floorLevel());
        }

        @Test
        @DisplayName("Should allow consecutive floor transitions")
        void shouldAllowConsecutiveFloorTransitions() throws SQLException {
            FakeRuntime runtime = new FakeRuntime();
            service = new DungeonInstanceService(
                    database, instanceRepository, membershipRepository, new PartyService(), floorConfigService, runtime);

            UUID player = UUID.randomUUID();
            DungeonInstance floor1 = service.createInstance(List.of(player), 1).join();

            DungeonInstance floor2 = service.transitionFloor(floor1.instanceId()).join();
            assertEquals(2, floor2.floorLevel());

            DungeonInstance floor3 = service.transitionFloor(floor1.instanceId()).join();
            assertEquals(3, floor3.floorLevel());
            assertTrue(floor3.worldName().contains("-f3"));

            assertEquals(2, runtime.armedWorlds.size());
        }

        @Test
        @DisplayName("Should reject concurrent transition attempts via atomic state claim")
        void shouldRejectConcurrentTransitionAttemptsViaAtomicStateClaim() throws Exception {
            FakeRuntime runtime = new FakeRuntime();
            service = new DungeonInstanceService(
                    database, instanceRepository, membershipRepository, new PartyService(), floorConfigService, runtime);

            UUID player = UUID.randomUUID();
            DungeonInstance active = service.createInstance(List.of(player), 1).join();

            // Defer teleport only after the initial createInstance(...) join above.
            // Doing this earlier also stalls floor-1 activation and makes the test hang.
            runtime.deferTeleport();
            CompletableFuture<DungeonInstance> first = service.transitionFloor(active.instanceId());
            assertFalse(first.isDone());

            assertThrows(IllegalStateException.class,
                    () -> service.transitionFloor(active.instanceId()));

            runtime.completeTeleport();
            DungeonInstance transitioned = first.join();
            assertEquals(DungeonInstanceState.ACTIVE, transitioned.state());
            assertEquals(2, transitioned.floorLevel());
        }

        @Test
        @DisplayName("Should not revert metadata when arm world removal fails after transfer")
        void shouldNotRevertMetadataWhenArmWorldRemovalFailsAfterTransfer() throws SQLException {
            FakeRuntime runtime = new FakeRuntime();
            service = new DungeonInstanceService(
                    database, instanceRepository, membershipRepository, new PartyService(), floorConfigService, runtime);

            UUID player = UUID.randomUUID();
            DungeonInstance active = service.createInstance(List.of(player), 1).join();
            String oldWorldName = active.worldName();

            runtime.armWorldRemovalFailure = new IllegalStateException("World not found");

            CompletableFuture<DungeonInstance> future = service.transitionFloor(active.instanceId());
            CompletionException ex = assertThrows(CompletionException.class, future::join);
            assertEquals("World not found", ex.getCause().getMessage());

            DungeonInstance persisted = instanceRepository.findById(active.instanceId()).orElseThrow();
            assertEquals(DungeonInstanceState.ACTIVE, persisted.state());
            assertEquals(2, persisted.floorLevel());
            assertTrue(persisted.worldName().contains("-f2"));
            assertFalse(persisted.worldName().equals(oldWorldName));
            assertTrue(runtime.cleanedWorlds.isEmpty());
            assertTrue(runtime.armedWorlds.isEmpty());

            DungeonInstanceService.ContinueRoute route = service.resolveContinueRoute(player);
            assertTrue(route.routesToInstance());
            assertFalse(route.isPending());
        }

        @Test
        @DisplayName("Should retry final activation persistence and keep active metadata")
        void shouldKeepNewWorldMetadataWhenFinalActivationPersistenceFails() throws SQLException {
            FakeRuntime runtime = new FakeRuntime();
            instanceRepository = new FailingActiveStateRepository(database, 1);
            service = new DungeonInstanceService(
                    database, instanceRepository, membershipRepository, new PartyService(), floorConfigService, runtime);

            UUID player = UUID.randomUUID();
            DungeonInstance active = service.createInstance(List.of(player), 1).join();

            DungeonInstance transitioned = service.transitionFloor(active.instanceId()).join();

            assertEquals(DungeonInstanceState.ACTIVE, transitioned.state());
            assertEquals(2, transitioned.floorLevel());
            DungeonInstance persisted = instanceRepository.findById(active.instanceId()).orElseThrow();
            assertEquals(DungeonInstanceState.ACTIVE, persisted.state());
            assertEquals(transitioned.worldName(), persisted.worldName());
            assertEquals(2, persisted.floorLevel());

            DungeonInstanceService.ContinueRoute route = service.resolveContinueRoute(player);
            assertTrue(route.routesToInstance());
            assertFalse(route.isPending());
        }

        @Test
        @DisplayName("Should keep Continue routing available when final activation persistence stays down")
        void shouldKeepContinueRoutingAvailableWhenFinalActivationPersistenceStaysDown() throws SQLException {
            FakeRuntime runtime = new FakeRuntime();
            instanceRepository = new FailingActiveStateRepository(database, 2);
            service = new DungeonInstanceService(
                    database, instanceRepository, membershipRepository, new PartyService(), floorConfigService, runtime);

            UUID player = UUID.randomUUID();
            DungeonInstance floor1 = service.createInstance(List.of(player), 1).join();

            CompletionException ex = assertThrows(CompletionException.class,
                    () -> service.transitionFloor(floor1.instanceId()).join());
            assertEquals("forced ACTIVE update failure", ex.getCause().getMessage());
            assertEquals(List.of(floor1.worldName()), runtime.armedWorlds);

            DungeonInstance persisted = instanceRepository.findById(floor1.instanceId()).orElseThrow();
            assertEquals(DungeonInstanceState.TRANSITIONING, persisted.state());
            assertEquals(2, persisted.floorLevel());

            DungeonInstanceService.ContinueRoute route = service.resolveContinueRoute(player);
            assertTrue(route.routesToInstance());
            assertFalse(route.isPending());
            assertNotNull(route.instance());
            assertEquals(DungeonInstanceState.ACTIVE, route.instance().state());
            assertEquals(2, route.instance().floorLevel());

            DungeonInstance floor3 = service.transitionFloor(floor1.instanceId()).join();
            assertEquals(DungeonInstanceState.ACTIVE, floor3.state());
            assertEquals(3, floor3.floorLevel());
        }

        @Test
        @DisplayName("Should pick the next floor theme from resolved variants during transition")
        void shouldPickTheNextFloorThemeFromResolvedVariantsDuringTransition() throws SQLException {
            FakeRuntime runtime = new FakeRuntime();
            floorConfigService.setOverride(1, "theme.variants", List.of("crypt"));
            floorConfigService.setOverride(2, "theme.variants", List.of("arcane", "hive"));
            service = new DungeonInstanceService(
                    database,
                    instanceRepository,
                    membershipRepository,
                    new PartyService(),
                    floorConfigService,
                    runtime,
                    theme -> Set.of("crypt", "arcane", "hive").contains(theme)
            );

            UUID player = UUID.randomUUID();
            DungeonInstance floor1 = service.createInstance(List.of(player), 1).join();
            DungeonInstance floor2 = service.transitionFloor(floor1.instanceId()).join();

            assertEquals("crypt", floor1.theme());
            assertTrue(Set.of("arcane", "hive").contains(floor2.theme()));
            assertEquals(floor2.theme(), runtime.generatedConfig.theme().palette());
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
        @DisplayName("Should resolve Continue route to the player's active instance")
        void shouldResolveContinueRouteToActiveInstance() throws SQLException {
            UUID player = UUID.randomUUID();
            DungeonInstance instance = testInstanceWithState("inst-1", "world-1", DungeonInstanceState.ACTIVE);
            service.createInstance(instance, List.of(player));

            DungeonInstanceService.ContinueRoute route = service.resolveContinueRoute(player);

            assertTrue(route.routesToInstance());
            assertFalse(route.isPending());
            assertEquals(instance, route.instance());
        }

        @Test
        @DisplayName("Should return null when player has no instance")
        void shouldReturnNullWhenPlayerHasNoInstance() throws SQLException {
            UUID player = UUID.randomUUID();

            assertNull(service.getActiveInstance(player));
        }

        @Test
        @DisplayName("Should resolve Continue route to shared world when player has no instance")
        void shouldResolveContinueRouteToSharedWorldWhenPlayerHasNoInstance() throws SQLException {
            UUID player = UUID.randomUUID();

            DungeonInstanceService.ContinueRoute route = service.resolveContinueRoute(player);

            assertFalse(route.routesToInstance());
            assertFalse(route.isPending());
            assertNull(route.instance());
        }

        @Test
        @DisplayName("Should keep Continue pending while instance is creating")
        void shouldKeepContinuePendingWhileInstanceIsCreating() throws SQLException {
            UUID player = UUID.randomUUID();
            DungeonInstance instance = testInstanceWithState("inst-1", "world-1", DungeonInstanceState.CREATING);
            service.createInstance(instance, List.of(player));

            DungeonInstanceService.ContinueRoute route = service.resolveContinueRoute(player);

            assertFalse(route.routesToInstance());
            assertTrue(route.isPending());
            assertEquals(DungeonInstanceState.CREATING, route.instance().state());
        }

        @Test
        @DisplayName("Should keep Continue pending while instance is transitioning")
        void shouldKeepContinuePendingWhileInstanceIsTransitioning() throws SQLException {
            UUID player = UUID.randomUUID();
            DungeonInstance instance = testInstanceWithState("inst-1", "world-1", DungeonInstanceState.TRANSITIONING);
            service.createInstance(instance, List.of(player));

            DungeonInstanceService.ContinueRoute route = service.resolveContinueRoute(player);

            assertFalse(route.routesToInstance());
            assertTrue(route.isPending());
            assertEquals(DungeonInstanceState.TRANSITIONING, route.instance().state());
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
    // endInstance
    // ============================================

    @Nested
    @DisplayName("endInstance")
    class EndInstanceFlow {

        @Test
        @DisplayName("Should end active instance, evacuate roster, and arm world for removal")
        void shouldEndActiveInstanceEvacuateAndArm() throws SQLException {
            FakeRuntime runtime = new FakeRuntime();
            service = new DungeonInstanceService(
                    database, instanceRepository, membershipRepository, new PartyService(), floorConfigService, runtime);

            UUID playerA = UUID.randomUUID();
            UUID playerB = UUID.randomUUID();
            DungeonInstance instance = service.createInstance(List.of(playerA, playerB), 1).join();

            service.endInstance(instance.instanceId()).join();

            DungeonInstance ended = instanceRepository.findById(instance.instanceId()).orElseThrow();
            assertEquals(DungeonInstanceState.ENDED, ended.state());
            assertTrue(runtime.evacuatedPlayers.containsAll(List.of(playerA, playerB)));
            assertEquals(List.of(instance.worldName()), runtime.evacuationSourceWorlds);
            assertEquals(List.of(instance.worldName()), runtime.armedWorlds);
        }

        @Test
        @DisplayName("Should retry cleanup when instance is already ended")
        void shouldRetryCleanupWhenAlreadyEnded() throws SQLException {
            FakeRuntime runtime = new FakeRuntime();
            service = new DungeonInstanceService(
                    database, instanceRepository, membershipRepository, new PartyService(), floorConfigService, runtime);

            UUID player = UUID.randomUUID();
            DungeonInstance instance = testInstanceWithState("inst-1", "world-1", DungeonInstanceState.ACTIVE);
            service.createInstance(instance, List.of(player));
            instanceRepository.endInstance("inst-1");

            service.endInstance("inst-1").join();

            DungeonInstance ended = instanceRepository.findById("inst-1").orElseThrow();
            assertEquals(DungeonInstanceState.ENDED, ended.state());
            assertEquals(List.of("world-1"), runtime.evacuationSourceWorlds);
            assertEquals(List.of("world-1"), runtime.armedWorlds);
        }

        @Test
        @DisplayName("Should reject ending non-existent instance")
        void shouldRejectEndingNonExistentInstance() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.endInstance("no-such-instance"));
        }

        @Test
        @DisplayName("Should reject ending instance in CREATING state")
        void shouldRejectEndingCreatingInstance() throws SQLException {
            UUID player = UUID.randomUUID();
            DungeonInstance instance = testInstanceWithState("inst-1", "world-1", DungeonInstanceState.CREATING);
            service.createInstance(instance, List.of(player));

            assertThrows(IllegalStateException.class,
                    () -> service.endInstance("inst-1"));
        }

        @Test
        @DisplayName("Should reject ending instance in TRANSITIONING state")
        void shouldRejectEndingTransitioningInstance() throws SQLException {
            UUID player = UUID.randomUUID();
            DungeonInstance instance = testInstanceWithState("inst-1", "world-1", DungeonInstanceState.TRANSITIONING);
            service.createInstance(instance, List.of(player));

            assertThrows(IllegalStateException.class,
                    () -> service.endInstance("inst-1"));
        }

        @Test
        @DisplayName("Should make ended instance unreachable via Continue")
        void shouldMakeEndedInstanceUnreachableViaContinue() throws SQLException {
            FakeRuntime runtime = new FakeRuntime();
            service = new DungeonInstanceService(
                    database, instanceRepository, membershipRepository, new PartyService(), floorConfigService, runtime);

            UUID player = UUID.randomUUID();
            DungeonInstance instance = service.createInstance(List.of(player), 1).join();

            DungeonInstanceService.ContinueRoute routeBefore = service.resolveContinueRoute(player);
            assertTrue(routeBefore.routesToInstance());

            service.endInstance(instance.instanceId()).join();

            DungeonInstanceService.ContinueRoute routeAfter = service.resolveContinueRoute(player);
            assertFalse(routeAfter.routesToInstance());
            assertNull(routeAfter.instance());
        }

        @Test
        @DisplayName("Should clear runtime override when ending instance")
        void shouldClearRuntimeOverrideWhenEnding() throws SQLException {
            FakeRuntime runtime = new FakeRuntime();
            instanceRepository = new FailingActiveStateRepository(database, 2);
            service = new DungeonInstanceService(
                    database, instanceRepository, membershipRepository, new PartyService(), floorConfigService, runtime);

            UUID player = UUID.randomUUID();
            DungeonInstance floor1 = service.createInstance(List.of(player), 1).join();

            // Force a post-transfer persistence failure to create a runtime override
            assertThrows(CompletionException.class,
                    () -> service.transitionFloor(floor1.instanceId()).join());

            // Continue should still work via runtime override
            DungeonInstanceService.ContinueRoute overrideRoute = service.resolveContinueRoute(player);
            assertTrue(overrideRoute.routesToInstance());

            // Now repair the override (so claimEndState can work on ACTIVE)
            service.endInstance(floor1.instanceId()).join();

            // After end, Continue should not route
            DungeonInstanceService.ContinueRoute routeAfterEnd = service.resolveContinueRoute(player);
            assertFalse(routeAfterEnd.routesToInstance());
            assertNull(routeAfterEnd.instance());
        }

        @Test
        @DisplayName("Should surface evacuation failure while keeping end state claimed")
        void shouldSurfaceEvacuationFailureWhileKeepingEndStateClaimed() throws SQLException {
            FakeRuntime runtime = new FakeRuntime();
            runtime.evacuationFailure = new RuntimeException("evacuation failed");
            service = new DungeonInstanceService(
                    database, instanceRepository, membershipRepository, new PartyService(), floorConfigService, runtime);

            UUID player = UUID.randomUUID();
            DungeonInstance instance = service.createInstance(List.of(player), 1).join();

            CompletionException ex = assertThrows(
                    CompletionException.class,
                    () -> service.endInstance(instance.instanceId()).join());

            DungeonInstance ended = instanceRepository.findById(instance.instanceId()).orElseThrow();
            assertEquals(DungeonInstanceState.ENDED, ended.state());
            assertTrue(ex.getCause() instanceof IllegalStateException);
            assertTrue(ex.getCause().getMessage().contains("evacuation"));
            assertEquals(List.of(instance.worldName()), runtime.armedWorlds);
        }

        @Test
        @DisplayName("Should surface world removal arming failure")
        void shouldSurfaceWorldRemovalArmingFailure() throws SQLException {
            FakeRuntime runtime = new FakeRuntime();
            runtime.armWorldRemovalFailure = new RuntimeException("arm failed");
            service = new DungeonInstanceService(
                    database, instanceRepository, membershipRepository, new PartyService(), floorConfigService, runtime);

            UUID player = UUID.randomUUID();
            DungeonInstance instance = service.createInstance(List.of(player), 1).join();

            CompletionException ex = assertThrows(
                    CompletionException.class,
                    () -> service.endInstance(instance.instanceId()).join());

            DungeonInstance ended = instanceRepository.findById(instance.instanceId()).orElseThrow();
            assertEquals(DungeonInstanceState.ENDED, ended.state());
            assertTrue(ex.getCause() instanceof IllegalStateException);
            assertTrue(ex.getCause().getMessage().contains("arming"));
            assertTrue(runtime.evacuatedPlayers.contains(player));
        }

        @Test
        @DisplayName("Should keep instance retryable when roster lookup fails during end preparation")
        void shouldKeepInstanceRetryableWhenRosterLookupFailsDuringEndPreparation() throws SQLException {
            FakeRuntime runtime = new FakeRuntime();
            membershipRepository = new FailingRosterLookupMembershipRepository(database, 1);
            membershipRepository.initialize();
            service = new DungeonInstanceService(
                    database, instanceRepository, membershipRepository, new PartyService(), floorConfigService, runtime);

            UUID player = UUID.randomUUID();
            DungeonInstance instance = service.createInstance(List.of(player), 1).join();

            assertThrows(SQLException.class, () -> service.endInstance(instance.instanceId()));

            DungeonInstance stillActive = instanceRepository.findById(instance.instanceId()).orElseThrow();
            assertEquals(DungeonInstanceState.ACTIVE, stillActive.state());
            assertTrue(runtime.evacuatedPlayers.isEmpty());
            assertTrue(runtime.armedWorlds.isEmpty());

            service.endInstance(instance.instanceId()).join();

            DungeonInstance ended = instanceRepository.findById(instance.instanceId()).orElseThrow();
            assertEquals(DungeonInstanceState.ENDED, ended.state());
            assertEquals(List.of(instance.worldName()), runtime.evacuationSourceWorlds);
            assertEquals(List.of(instance.worldName()), runtime.armedWorlds);
        }

        @Test
        @DisplayName("Should allow creating a new instance after ending the previous one")
        void shouldAllowCreatingNewInstanceAfterEnding() throws SQLException {
            FakeRuntime runtime = new FakeRuntime();
            service = new DungeonInstanceService(
                    database, instanceRepository, membershipRepository, new PartyService(), floorConfigService, runtime);

            UUID player = UUID.randomUUID();
            DungeonInstance first = service.createInstance(List.of(player), 1).join();

            service.endInstance(first.instanceId()).join();

            // Player should now be free to start a new instance
            DungeonInstance second = service.createInstance(List.of(player), 1).join();
            assertEquals(DungeonInstanceState.ACTIVE, second.state());
        }
    }

    // ============================================
    // forceEndInstance
    // ============================================

    @Nested
    @DisplayName("forceEndInstance")
    class ForceEndInstanceFlow {

        @Test
        @DisplayName("Should force-end an ACTIVE instance")
        void shouldForceEndActiveInstance() throws SQLException {
            FakeRuntime runtime = new FakeRuntime();
            service = new DungeonInstanceService(
                    database, instanceRepository, membershipRepository, new PartyService(), floorConfigService, runtime);

            UUID player = UUID.randomUUID();
            DungeonInstance instance = service.createInstance(List.of(player), 1).join();

            service.forceEndInstance(instance.instanceId()).join();

            DungeonInstance ended = instanceRepository.findById(instance.instanceId()).orElseThrow();
            assertEquals(DungeonInstanceState.ENDED, ended.state());
            assertEquals(List.of(instance.worldName()), runtime.armedWorlds);
        }

        @Test
        @DisplayName("Should force-end a CREATING instance")
        void shouldForceEndCreatingInstance() throws SQLException {
            FakeRuntime runtime = new FakeRuntime();
            service = new DungeonInstanceService(
                    database, instanceRepository, membershipRepository, new PartyService(), floorConfigService, runtime);

            UUID player = UUID.randomUUID();
            DungeonInstance creating = testInstance("inst-1", "world-1");
            service.createInstance(creating, List.of(player));

            service.forceEndInstance("inst-1").join();

            DungeonInstance ended = instanceRepository.findById("inst-1").orElseThrow();
            assertEquals(DungeonInstanceState.ENDED, ended.state());
            assertEquals(List.of("world-1"), runtime.armedWorlds);
        }

        @Test
        @DisplayName("Should force-end a live TRANSITIONING instance across both transition worlds")
        void shouldForceEndLiveTransitioningInstanceAcrossBothWorlds() throws SQLException {
            FakeRuntime runtime = new FakeRuntime();
            service = new DungeonInstanceService(
                    database, instanceRepository, membershipRepository, new PartyService(), floorConfigService, runtime);

            UUID player = UUID.randomUUID();
            DungeonInstance floor1 = service.createInstance(List.of(player), 1).join();
            String oldWorldName = floor1.worldName();

            runtime.deferTeleport();
            CompletableFuture<DungeonInstance> transition = service.transitionFloor(floor1.instanceId());

            DungeonInstance transitioning = instanceRepository.findById(floor1.instanceId()).orElseThrow();
            assertEquals(DungeonInstanceState.TRANSITIONING, transitioning.state());
            String newWorldName = transitioning.worldName();

            service.forceEndInstance(floor1.instanceId()).join();
            runtime.completeTeleport();

            CompletionException transitionFailure = assertThrows(CompletionException.class, transition::join);
            assertTrue(transitionFailure.getCause().getMessage().contains("force-ended"));

            DungeonInstance ended = instanceRepository.findById(floor1.instanceId()).orElseThrow();
            assertEquals(DungeonInstanceState.ENDED, ended.state());
            assertEquals(List.of(oldWorldName, newWorldName), runtime.evacuationSourceWorlds);
            assertEquals(List.of(oldWorldName, newWorldName), runtime.armedWorlds);
        }

        @Test
        @DisplayName("Should reject force-end of a TRANSITIONING instance without live transition context")
        void shouldRejectForceEndTransitioningInstanceWithoutLiveContext() throws SQLException {
            UUID player = UUID.randomUUID();
            DungeonInstance transitioning = testInstanceWithState(
                    "inst-1", "world-1", DungeonInstanceState.TRANSITIONING);
            service.createInstance(transitioning, List.of(player));

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> service.forceEndInstance("inst-1"));
            assertTrue(ex.getMessage().contains("live transition context"));
        }

        @Test
        @DisplayName("Should reject force-end when instance is already ENDED")
        void shouldRejectForceEndWhenAlreadyEnded() throws SQLException {
            UUID player = UUID.randomUUID();
            DungeonInstance instance = testInstanceWithState(
                    "inst-1", "world-1", DungeonInstanceState.ACTIVE);
            service.createInstance(instance, List.of(player));
            instanceRepository.endInstance("inst-1");

            assertThrows(IllegalStateException.class,
                    () -> service.forceEndInstance("inst-1"));
        }

        @Test
        @DisplayName("Should reject force-end for nonexistent instance")
        void shouldRejectForceEndForNonexistentInstance() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.forceEndInstance("no-such-instance"));
        }

        @Test
        @DisplayName("Should retry cleanup after a force-end failure on a TRANSITIONING instance")
        void shouldRetryCleanupAfterForceEndFailureOnTransitioningInstance() throws SQLException {
            FakeRuntime runtime = new FakeRuntime();
            service = new DungeonInstanceService(
                    database, instanceRepository, membershipRepository, new PartyService(), floorConfigService, runtime);

            UUID player = UUID.randomUUID();
            DungeonInstance floor1 = service.createInstance(List.of(player), 1).join();
            String oldWorldName = floor1.worldName();

            runtime.deferTeleport();
            CompletableFuture<DungeonInstance> transition = service.transitionFloor(floor1.instanceId());
            String newWorldName = instanceRepository.findById(floor1.instanceId()).orElseThrow().worldName();

            runtime.armWorldRemovalFailure = new RuntimeException("arm failed");
            CompletionException ex = assertThrows(CompletionException.class,
                    () -> service.forceEndInstance(floor1.instanceId()).join());
            assertTrue(ex.getCause().getMessage().contains("removal arming failed"));

            runtime.completeTeleport();
            assertThrows(CompletionException.class, transition::join);

            runtime.armWorldRemovalFailure = null;
            runtime.evacuationSourceWorlds.clear();
            runtime.armedWorlds.clear();

            service.endInstance(floor1.instanceId()).join();

            assertEquals(List.of(oldWorldName, newWorldName), runtime.evacuationSourceWorlds);
            assertEquals(List.of(oldWorldName, newWorldName), runtime.armedWorlds);
        }

        @Test
        @DisplayName("Should allow creating new instance after force-ending a live TRANSITIONING one")
        void shouldAllowNewInstanceAfterForceEndingTransitioning() throws SQLException {
            FakeRuntime runtime = new FakeRuntime();
            service = new DungeonInstanceService(
                    database, instanceRepository, membershipRepository, new PartyService(), floorConfigService, runtime);

            UUID player = UUID.randomUUID();
            DungeonInstance floor1 = service.createInstance(List.of(player), 1).join();

            runtime.deferTeleport();
            CompletableFuture<DungeonInstance> transition = service.transitionFloor(floor1.instanceId());

            service.forceEndInstance(floor1.instanceId()).join();
            runtime.completeTeleport();
            assertThrows(CompletionException.class, transition::join);

            DungeonInstance second = service.createInstance(List.of(player), 1).join();
            assertEquals(DungeonInstanceState.ACTIVE, second.state());
        }
    }

        // ============================================
        // restartInstanceAtFloor
        // ============================================

        @Nested
        @DisplayName("restartInstanceAtFloor")
        class RestartInstanceAtFloorFlow {

        @Test
        @DisplayName("Should force-end current instance and create a new active instance at target floor")
        void shouldForceEndAndCreateNewInstanceAtTargetFloor() throws SQLException {
            FakeRuntime runtime = new FakeRuntime();
            service = new DungeonInstanceService(
                database, instanceRepository, membershipRepository, new PartyService(), floorConfigService, runtime);

            UUID playerA = UUID.randomUUID();
            UUID playerB = UUID.randomUUID();
            DungeonInstance original = service.createInstance(List.of(playerA, playerB), 3).join();
            runtime.teleportedPlayers.clear();

            DungeonInstance restarted = service.restartInstanceAtFloor(original.instanceId(), 2).join();

            assertEquals(DungeonInstanceState.ENDED,
                instanceRepository.findById(original.instanceId()).orElseThrow().state());
            assertEquals(DungeonInstanceState.ACTIVE, restarted.state());
            assertEquals(2, restarted.floorLevel());
            assertFalse(original.instanceId().equals(restarted.instanceId()));
            assertEquals(Set.of(playerA, playerB), membershipRepository.findPlayerIdsByInstance(restarted.instanceId()));
            assertEquals(Set.of(playerA, playerB), Set.copyOf(runtime.evacuatedPlayers));
            assertEquals(Set.of(playerA, playerB), Set.copyOf(runtime.teleportedPlayers));
            assertEquals(List.of(original.worldName()), runtime.armedWorlds);
        }

        @Test
        @DisplayName("Should preserve captured roster instead of current party membership")
        void shouldPreserveCapturedRoster() throws SQLException {
            FakeRuntime runtime = new FakeRuntime();
            PartyService partyService = new PartyService();
            service = new DungeonInstanceService(
                database, instanceRepository, membershipRepository, partyService, floorConfigService, runtime);

            UUID owner = UUID.randomUUID();
            UUID originalMember = UUID.randomUUID();
            UUID newPartyMember = UUID.randomUUID();
            assertTrue(partyService.createParty(owner));
            assertEquals(PartyService.InviteResult.SUCCESS, partyService.invitePlayer(owner, originalMember));
            DungeonInstance original = service.createInstanceForPlayer(owner, 3).join();
            assertTrue(partyService.leaveParty(originalMember));
            assertEquals(PartyService.InviteResult.SUCCESS, partyService.invitePlayer(owner, newPartyMember));

            DungeonInstance restarted = service.restartInstanceAtFloor(original.instanceId(), 2).join();

            assertEquals(Set.of(owner, originalMember), membershipRepository.findPlayerIdsByInstance(restarted.instanceId()));
            assertFalse(membershipRepository.findPlayerIdsByInstance(restarted.instanceId()).contains(newPartyMember));
        }

        @Test
        @DisplayName("Should reject invalid target floor")
        void shouldRejectInvalidTargetFloor() throws SQLException {
            FakeRuntime runtime = new FakeRuntime();
            service = new DungeonInstanceService(
                    database, instanceRepository, membershipRepository, new PartyService(), floorConfigService, runtime);

            UUID player = UUID.randomUUID();
            DungeonInstance original = service.createInstance(List.of(player), 2).join();

            assertThrows(IllegalArgumentException.class,
                () -> service.restartInstanceAtFloor(original.instanceId(), 0));
        }

        @Test
        @DisplayName("Should leave old instance ended when new instance creation fails")
        void shouldLeaveOldInstanceEndedWhenCreationFails() throws SQLException {
            FakeRuntime runtime = new FakeRuntime();
            service = new DungeonInstanceService(
                database, instanceRepository, membershipRepository, new PartyService(), floorConfigService, runtime);

            UUID player = UUID.randomUUID();
            DungeonInstance original = service.createInstance(List.of(player), 3).join();
            runtime.nextGenerationResult = generationResult(
                new Vec3i(5, 1, 7),
                new Vec3i(30, 1, 31),
                "restart generation failed"
            );

            CompletionException ex = assertThrows(CompletionException.class,
                () -> service.restartInstanceAtFloor(original.instanceId(), 2).join());

            assertEquals("restart generation failed", ex.getCause().getMessage());
            assertEquals(DungeonInstanceState.ENDED,
                instanceRepository.findById(original.instanceId()).orElseThrow().state());
        }
        }

    // ============================================
    // query helpers
    // ============================================

    @Nested
    @DisplayName("query helpers")
    class QueryHelpers {

        @Test
        @DisplayName("Should return instance by ID")
        void shouldReturnInstanceById() throws SQLException {
            UUID player = UUID.randomUUID();
            DungeonInstance instance = testInstanceWithState("inst-1", "world-1", DungeonInstanceState.ACTIVE);
            service.createInstance(instance, List.of(player));

            DungeonInstance result = service.getInstanceById("inst-1");

            assertNotNull(result);
            assertEquals("inst-1", result.instanceId());
        }

        @Test
        @DisplayName("Should return null for unknown ID")
        void shouldReturnNullForUnknownId() throws SQLException {
            assertNull(service.getInstanceById("nonexistent"));
        }

        @Test
        @DisplayName("Should list non-ended instances")
        void shouldListNonEndedInstances() throws SQLException {
            UUID p1 = UUID.randomUUID();
            UUID p2 = UUID.randomUUID();

            service.createInstance(
                    testInstanceWithState("inst-active", "world-active", DungeonInstanceState.ACTIVE),
                    List.of(p1));
            service.createInstance(
                    testInstanceWithState("inst-ended", "world-ended", DungeonInstanceState.ACTIVE),
                    List.of(p2));
            instanceRepository.endInstance("inst-ended");

            List<DungeonInstance> result = service.listNonEndedInstances();

            assertEquals(1, result.size());
            assertEquals("inst-active", result.get(0).instanceId());
        }

        @Test
        @DisplayName("Should return roster for instance")
        void shouldReturnRosterForInstance() throws SQLException {
            UUID playerA = UUID.randomUUID();
            UUID playerB = UUID.randomUUID();
            service.createInstance(testInstance("inst-1", "world-1"), List.of(playerA, playerB));

            Set<UUID> roster = service.getRoster("inst-1");

            assertEquals(Set.of(playerA, playerB), roster);
        }
    }

    @Nested
    @DisplayName("spawn resolution")
    class SpawnResolution {

        @Test
        @DisplayName("Should resolve shared-world spawn through the provided dispatcher")
        void shouldResolveSharedWorldSpawnThroughProvidedDispatcher() {
            AtomicBoolean dispatcherActive = new AtomicBoolean(false);
            AtomicBoolean dispatcherCalled = new AtomicBoolean(false);
            AtomicBoolean lookupCalled = new AtomicBoolean(false);
            Transform expected = new Transform(1.5D, 70.0D, 2.5D);

            Transform resolved = DungeonInstanceService.resolveSpawnOnWorldThread(
                    lookup -> {
                        dispatcherCalled.set(true);
                        dispatcherActive.set(true);
                        try {
                            return CompletableFuture.completedFuture(lookup.get());
                        } finally {
                            dispatcherActive.set(false);
                        }
                    },
                    () -> {
                        lookupCalled.set(true);
                        assertTrue(dispatcherActive.get());
                        return expected;
                    },
                    "missing spawn"
            ).join();

            assertTrue(dispatcherCalled.get());
            assertTrue(lookupCalled.get());
            assertSame(expected, resolved);
        }

        @Test
        @DisplayName("Should fail when shared-world spawn lookup returns null")
        void shouldFailWhenSharedWorldSpawnLookupReturnsNull() {
            CompletionException ex = assertThrows(
                    CompletionException.class,
                    () -> DungeonInstanceService.resolveSpawnOnWorldThread(
                            lookup -> CompletableFuture.completedFuture(lookup.get()),
                            () -> null,
                            "missing spawn"
                    ).join()
            );

            assertTrue(ex.getCause() instanceof IllegalStateException);
            assertEquals("missing spawn", ex.getCause().getMessage());
        }
    }

    // ============================================
    // Helpers
    // ============================================

    private static FakeRuntime restartRuntimeAfterWorldsLoaded() {
        return new FakeRuntime()
                .requireLoadedWorldsForCleanup()
                .markWorldsLoaded();
    }

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
        private final List<String> armedWorlds = new ArrayList<>();
        private final List<String> evacuationSourceWorlds = new ArrayList<>();
        private final List<UUID> teleportedPlayers = new ArrayList<>();
        private final List<UUID> evacuatedPlayers = new ArrayList<>();

        private DungeonConfig generatedConfig;
        private RuntimeException armWorldRemovalFailure;
        private RuntimeException evacuationFailure;
        private CompletableFuture<DungeonInstanceService.InstanceWorld> deferredWorldCreation;
        private CompletableFuture<GenerationResult> deferredGeneration;
        private CompletableFuture<Void> deferredTeleport;
        private GenerationResult nextGenerationResult = successGenerationResult();
        private String deferredWorldName;
        private final java.util.Map<String, Vec3i> finalizedEntrances = new java.util.HashMap<>();
        private final java.util.Map<String, DungeonInstance> finalizedInstances = new java.util.HashMap<>();
        private boolean cleanupRequiresLoadedWorlds;
        private boolean worldsLoadedForCleanup = true;

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
                DungeonInstance floorInstance,
                GenerationResult result
        ) {
            finalizedEntrances.put(world.worldName(), floorInstance.entrancePosition());
            finalizedInstances.put(world.worldName(), floorInstance);
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
            if (cleanupRequiresLoadedWorlds && !worldsLoadedForCleanup) {
                throw new IllegalStateException("cleanupWorld called before restart worlds were loaded");
            }
            cleanedWorlds.add(worldName);
        }

        @Override
        public CompletableFuture<Void> armWorldRemoval(String worldName) {
            if (armWorldRemovalFailure != null) {
                return CompletableFuture.failedFuture(armWorldRemovalFailure);
            }
            armedWorlds.add(worldName);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> evacuateToSharedWorld(
                Collection<UUID> playerIds,
                String sourceWorldName
        ) {
            if (evacuationFailure != null) {
                return CompletableFuture.failedFuture(evacuationFailure);
            }
            evacuationSourceWorlds.add(sourceWorldName);
            evacuatedPlayers.addAll(playerIds);
            return CompletableFuture.completedFuture(null);
        }

        private FakeRuntime requireLoadedWorldsForCleanup() {
            cleanupRequiresLoadedWorlds = true;
            worldsLoadedForCleanup = false;
            return this;
        }

        private FakeRuntime markWorldsLoaded() {
            worldsLoadedForCleanup = true;
            return this;
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

    private static final class FailingActiveStateRepository extends DungeonInstanceRepository {

        private int remainingActiveFailures;

        private FailingActiveStateRepository(DatabaseProvider database, int remainingActiveFailures) {
            super(database);
            this.remainingActiveFailures = remainingActiveFailures;
        }

        @Override
        public void update(DungeonInstance instance) throws SQLException {
            maybeFailActiveStateUpdate(instance);
            super.update(instance);
        }

        @Override
        boolean updateIfState(DungeonInstance instance, DungeonInstanceState expectedState) throws SQLException {
            maybeFailActiveStateUpdate(instance);
            return super.updateIfState(instance, expectedState);
        }

        private void maybeFailActiveStateUpdate(DungeonInstance instance) throws SQLException {
            if (instance.state() == DungeonInstanceState.ACTIVE
                    && instance.floorLevel() > 1
                    && remainingActiveFailures > 0) {
                remainingActiveFailures--;
                throw new SQLException("forced ACTIVE update failure");
            }
        }
    }

    private static final class FailingRosterLookupMembershipRepository extends DungeonMembershipRepository {

        private int remainingFailures;

        private FailingRosterLookupMembershipRepository(DatabaseProvider database, int remainingFailures) {
            super(database);
            this.remainingFailures = remainingFailures;
        }

        @Override
        Set<UUID> findPlayerIdsByInstanceInTransaction(Connection conn, String instanceId) throws SQLException {
            if (remainingFailures > 0) {
                remainingFailures--;
                throw new SQLException("forced roster lookup failure");
            }
            return super.findPlayerIdsByInstanceInTransaction(conn, instanceId);
        }
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
            0,
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
