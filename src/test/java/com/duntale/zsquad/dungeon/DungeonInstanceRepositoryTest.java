package com.duntale.zsquad.dungeon;

import com.duntale.dungeongen.config.Vec3i;
import com.duntale.zsquad.db.DatabaseProvider;
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
        database.initialize(tempDir.resolve("zsquad-test.db"));
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
}
