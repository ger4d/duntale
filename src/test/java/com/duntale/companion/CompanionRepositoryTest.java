package com.duntale.companion;

import com.duntale.db.DatabaseProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("CompanionRepository")
class CompanionRepositoryTest {

    @TempDir
    Path tempDir;

    private DatabaseProvider database;

    @BeforeEach
    void setUp() throws SQLException {
        database = new DatabaseProvider();
        database.initialize(tempDir.resolve("duntale-test.db"));
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    @DisplayName("Should initialize the companion preference schema with display_name")
    void shouldInitializeSchemaWithDisplayName() throws SQLException {
        CompanionRepository repository = new CompanionRepository(database);

        repository.initialize();

        assertTrue(hasColumn("display_name"));
    }

    @Test
    @DisplayName("Should migrate a role-only schema by adding display_name")
    void shouldMigrateRoleOnlySchemaByAddingDisplayName() throws SQLException {
        database.write(conn -> {
            try (var stmt = conn.createStatement()) {
                stmt.execute(
                        "CREATE TABLE companion_preferences ("
                                + "uuid TEXT PRIMARY KEY, "
                                + "role_name TEXT NOT NULL"
                                + ")"
                );
            }
        });

        CompanionRepository repository = new CompanionRepository(database);
        repository.initialize();

        assertTrue(hasColumn("display_name"));
    }

    @Test
    @DisplayName("Should report preference existence from row presence")
    void shouldReportPreferenceExistenceFromRowPresence() throws SQLException {
        CompanionRepository repository = new CompanionRepository(database);
        repository.initialize();

        UUID playerId = UUID.randomUUID();

        assertFalse(repository.hasPreference(playerId));

        repository.setPreference(playerId, "Companion_Wolf_Black");

        assertTrue(repository.hasPreference(playerId));
    }

    @Test
    @DisplayName("Should round-trip role and display name")
    void shouldRoundTripRoleAndDisplayName() throws SQLException {
        CompanionRepository repository = new CompanionRepository(database);
        repository.initialize();

        UUID playerId = UUID.randomUUID();
        repository.setPreference(playerId, "Companion_Wolf_Black", "Fen");

        CompanionPreference preference = repository.getProfile(playerId);

        assertEquals(new CompanionPreference("Companion_Wolf_Black", "Fen"), preference);
        assertEquals("Companion_Wolf_Black", repository.getPreference(playerId));
    }

    @Test
    @DisplayName("Should preserve role-only writes through the legacy overload")
    void shouldPreserveRoleOnlyWritesThroughLegacyOverload() throws SQLException {
        CompanionRepository repository = new CompanionRepository(database);
        repository.initialize();

        UUID playerId = UUID.randomUUID();
        repository.setPreference(playerId, "Companion_Wolf_Black");

        CompanionPreference preference = repository.getProfile(playerId);

        assertEquals("Companion_Wolf_Black", preference.roleName());
        assertNull(preference.displayName());
    }

    private boolean hasColumn(String columnName) throws SQLException {
        return database.read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("PRAGMA table_info(companion_preferences)");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (columnName.equalsIgnoreCase(rs.getString("name"))) {
                        return true;
                    }
                }
                return false;
            }
        });
    }
}