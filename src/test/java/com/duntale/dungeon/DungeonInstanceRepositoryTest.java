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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DungeonInstanceRepository")
class DungeonInstanceRepositoryTest {

    @TempDir
    Path tempDir;

    private DatabaseProvider database;
    private DungeonInstanceRepository repository;

    @BeforeEach
    void setUp() throws SQLException {
        database = new DatabaseProvider();
        database.initialize(tempDir.resolve("duntale-test.db"));
        repository = new DungeonInstanceRepository(database);
        repository.initialize();
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    @DisplayName("Should create and load dungeon instance rows")
    void shouldCreateAndLoadDungeonInstanceRows() throws SQLException {
        DungeonInstance instance = new DungeonInstance(
                "instance-alpha",
                "dungeon-instance-alpha",
                1,
                64.0D,
                new Vec3i(10, 64, 12),
                new Vec3i(48, 64, 50),
                DungeonInstanceState.CREATING,
                "crypt",
                "seed-123",
                1_706_000_000_000L
        );

        repository.create(instance);

        assertEquals(Optional.of(instance), repository.findById(instance.instanceId()));
        assertEquals(Optional.of(instance), repository.findByWorldName(instance.worldName()));
        assertEquals(List.of(instance), repository.findAll());
    }

    @Test
    @DisplayName("Should update and end dungeon instance rows")
    void shouldUpdateAndEndDungeonInstanceRows() throws SQLException {
        DungeonInstance instance = new DungeonInstance(
                "instance-beta",
                "dungeon-instance-beta",
                1,
                72.0D,
                new Vec3i(4, 72, 4),
                new Vec3i(20, 72, 20),
                DungeonInstanceState.CREATING,
                "crypt",
                null,
                1_706_000_000_100L
        );
        repository.create(instance);

        DungeonInstance updated = new DungeonInstance(
                instance.instanceId(),
                "dungeon-instance-beta-floor-2",
                2,
                88.0D,
                new Vec3i(8, 88, 8),
                new Vec3i(40, 88, 40),
                DungeonInstanceState.ACTIVE,
                "catacombs",
                "seed-456",
                instance.createdAt()
        );

        repository.update(updated);
        assertEquals(Optional.of(updated), repository.findById(updated.instanceId()));

        repository.endInstance(updated.instanceId());

        Optional<DungeonInstance> ended = repository.findById(updated.instanceId());
        assertTrue(ended.isPresent());
        assertEquals(DungeonInstanceState.ENDED, ended.orElseThrow().state());
    }

    @Test
    @DisplayName("Should return only non-ended instances from findAllNonEnded")
    void shouldReturnOnlyNonEndedInstancesFromFindAllNonEnded() throws SQLException {
        DungeonInstance active = new DungeonInstance(
                "inst-active",
                "dungeon-active",
                1,
                64.0D,
                new Vec3i(0, 64, 0),
                new Vec3i(30, 64, 30),
                DungeonInstanceState.ACTIVE,
                "crypt",
                null,
                1_706_000_000_000L
        );
        DungeonInstance creating = new DungeonInstance(
                "inst-creating",
                "dungeon-creating",
                1,
                64.0D,
                new Vec3i(0, 64, 0),
                new Vec3i(30, 64, 30),
                DungeonInstanceState.CREATING,
                "crypt",
                null,
                1_706_000_000_100L
        );
        DungeonInstance ended = new DungeonInstance(
                "inst-ended",
                "dungeon-ended",
                1,
                64.0D,
                new Vec3i(0, 64, 0),
                new Vec3i(30, 64, 30),
                DungeonInstanceState.ENDED,
                "crypt",
                null,
                1_706_000_000_200L
        );

        repository.create(active);
        repository.create(creating);
        repository.create(ended);

        List<DungeonInstance> nonEnded = repository.findAllNonEnded();

        assertEquals(2, nonEnded.size());
        assertEquals("inst-active", nonEnded.get(0).instanceId());
        assertEquals("inst-creating", nonEnded.get(1).instanceId());
    }

    @Test
    @DisplayName("Should claim transition state from ACTIVE and return the instance")
    void shouldClaimTransitionStateFromActive() throws SQLException {
        DungeonInstance instance = new DungeonInstance(
                "inst-claim-t",
                "dungeon-claim-t",
                1,
                64.0D,
                new Vec3i(0, 64, 0),
                new Vec3i(30, 64, 30),
                DungeonInstanceState.ACTIVE,
                "crypt",
                null,
                1_706_000_000_000L
        );
        repository.create(instance);

        Optional<DungeonInstance> claimed = repository.claimTransitionState("inst-claim-t");

        assertTrue(claimed.isPresent());
        assertEquals(DungeonInstanceState.TRANSITIONING,
                repository.findById("inst-claim-t").orElseThrow().state());
    }

    @Test
    @DisplayName("Should reject transition claim when instance is not ACTIVE")
    void shouldRejectTransitionClaimWhenNotActive() throws SQLException {
        DungeonInstance instance = new DungeonInstance(
                "inst-claim-t2",
                "dungeon-claim-t2",
                1,
                64.0D,
                new Vec3i(0, 64, 0),
                new Vec3i(30, 64, 30),
                DungeonInstanceState.CREATING,
                "crypt",
                null,
                1_706_000_000_000L
        );
        repository.create(instance);

        Optional<DungeonInstance> claimed = repository.claimTransitionState("inst-claim-t2");

        assertTrue(claimed.isEmpty());
        assertEquals(DungeonInstanceState.CREATING,
                repository.findById("inst-claim-t2").orElseThrow().state());
    }

    @Test
    @DisplayName("Should claim end state from ACTIVE")
    void shouldClaimEndStateFromActive() throws SQLException {
        DungeonInstance instance = new DungeonInstance(
                "inst-claim-e",
                "dungeon-claim-e",
                1,
                64.0D,
                new Vec3i(0, 64, 0),
                new Vec3i(30, 64, 30),
                DungeonInstanceState.ACTIVE,
                "crypt",
                null,
                1_706_000_000_000L
        );
        repository.create(instance);

        assertTrue(repository.claimEndState("inst-claim-e"));
        assertEquals(DungeonInstanceState.ENDED,
                repository.findById("inst-claim-e").orElseThrow().state());
    }

    @Test
    @DisplayName("Should reject end state claim when instance is not ACTIVE")
    void shouldRejectEndStateClaimWhenNotActive() throws SQLException {
        DungeonInstance instance = new DungeonInstance(
                "inst-claim-e2",
                "dungeon-claim-e2",
                1,
                64.0D,
                new Vec3i(0, 64, 0),
                new Vec3i(30, 64, 30),
                DungeonInstanceState.TRANSITIONING,
                "crypt",
                null,
                1_706_000_000_000L
        );
        repository.create(instance);

        assertFalse(repository.claimEndState("inst-claim-e2"));
        assertEquals(DungeonInstanceState.TRANSITIONING,
                repository.findById("inst-claim-e2").orElseThrow().state());
    }

    @Test
    @DisplayName("Should update instance only when the current state matches")
    void shouldUpdateInstanceOnlyWhenCurrentStateMatches() throws SQLException {
        DungeonInstance transitioning = new DungeonInstance(
                "inst-update-guard",
                "dungeon-update-guard",
                1,
                64.0D,
                new Vec3i(0, 64, 0),
                new Vec3i(30, 64, 30),
                DungeonInstanceState.TRANSITIONING,
                "crypt",
                null,
                1_706_000_000_000L
        );
        repository.create(transitioning);

        DungeonInstance updated = new DungeonInstance(
                "inst-update-guard",
                "dungeon-update-guard-f2",
                2,
                72.0D,
                new Vec3i(5, 72, 5),
                new Vec3i(40, 72, 40),
                DungeonInstanceState.ACTIVE,
                "catacombs",
                "seed-2",
                transitioning.createdAt()
        );

        assertTrue(repository.updateIfState(updated, DungeonInstanceState.TRANSITIONING));
        assertEquals(Optional.of(updated), repository.findById(updated.instanceId()));
    }

    @Test
    @DisplayName("Should reject guarded update when the current state differs")
    void shouldRejectGuardedUpdateWhenCurrentStateDiffers() throws SQLException {
        DungeonInstance ended = new DungeonInstance(
                "inst-update-guard-2",
                "dungeon-update-guard-2",
                1,
                64.0D,
                new Vec3i(0, 64, 0),
                new Vec3i(30, 64, 30),
                DungeonInstanceState.ENDED,
                "crypt",
                null,
                1_706_000_000_000L
        );
        repository.create(ended);

        DungeonInstance updated = new DungeonInstance(
                "inst-update-guard-2",
                "dungeon-update-guard-2-f2",
                2,
                72.0D,
                new Vec3i(5, 72, 5),
                new Vec3i(40, 72, 40),
                DungeonInstanceState.ACTIVE,
                "catacombs",
                "seed-2",
                ended.createdAt()
        );

        assertFalse(repository.updateIfState(updated, DungeonInstanceState.TRANSITIONING));
        assertEquals(Optional.of(ended), repository.findById(ended.instanceId()));
    }
}
