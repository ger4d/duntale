package com.duntale.companion;

import com.duntale.db.DatabaseProvider;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import org.joml.Vector3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("CompanionService")
class CompanionServiceTest {

    @TempDir
    Path tempDir;

    private DatabaseProvider database;
    private CompanionRepository repository;

    @BeforeEach
    void setUp() throws SQLException {
        database = new DatabaseProvider();
        database.initialize(tempDir.resolve("duntale-test.db"));
        repository = new CompanionRepository(database);
        repository.initialize();
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    @DisplayName("Should not treat the default fallback role as a stored preference")
    void shouldNotTreatDefaultFallbackRoleAsStoredPreference() throws SQLException {
        CapturingCompanionService service = new CapturingCompanionService(repository);
        UUID playerId = UUID.randomUUID();

        service.spawn(null, null, playerId);

        assertEquals(CompanionService.DEFAULT_COMPANION_ROLE, service.lastRoleName);
        assertNull(service.lastDisplayName);
        assertFalse(service.hasStoredPreference(playerId));
    }

    @Test
    @DisplayName("Should persist role and display name through the service")
    void shouldPersistRoleAndDisplayNameThroughTheService() throws SQLException {
        CapturingCompanionService service = new CapturingCompanionService(repository);
        UUID playerId = UUID.randomUUID();

        service.persistPreference(playerId, "Companion_Wolf_Black", "Fen");

        assertTrue(service.hasStoredPreference(playerId));
        assertEquals(new CompanionPreference("Companion_Wolf_Black", "Fen"), repository.getProfile(playerId));
    }

    @Test
    @DisplayName("Should load the stored role and display name for auto-spawn")
    void shouldLoadStoredRoleAndDisplayNameForAutoSpawn() throws SQLException {
        CapturingCompanionService service = new CapturingCompanionService(repository);
        UUID playerId = UUID.randomUUID();
        repository.setPreference(playerId, "Companion_Wolf_Black", "Fen");

        service.spawn(null, null, playerId);

        assertEquals("Companion_Wolf_Black", service.lastRoleName);
        assertEquals("Fen", service.lastDisplayName);
    }

    private static final class CapturingCompanionService extends CompanionService {

        private String lastRoleName;
        private String lastDisplayName;

        private CapturingCompanionService(CompanionRepository repository) {
            super(null, null, null, repository);
        }

        @Override
        public ActiveCompanion summon(
                Store<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> store,
                Ref<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> playerRef,
                UUID playerId,
                String roleName,
                String displayName,
                Vector3d spawnOrigin
        ) {
            this.lastRoleName = roleName;
            this.lastDisplayName = displayName;
            return null;
        }
    }
}