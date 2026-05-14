package com.duntale.progression;

import com.duntale.db.DatabaseProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("ProgressionRepository")
class ProgressionRepositoryTest {

    @TempDir
    Path tempDir;

    private DatabaseProvider database;
    private ProgressionRepository repository;

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    @DisplayName("Should seed bundled levels into an empty database")
    void shouldSeedBundledLevelsIntoEmptyDatabase() throws SQLException {
        openDatabase();

        repository.initialize();

        assertEquals(100, countLevels());
        assertEquals(1, getDataVersion("levels"));
        assertEquals(1, repository.calculateLevel(1049));
        assertEquals(2, repository.calculateLevel(1050));
        assertEquals(100, repository.calculateLevel(68_898_035L));
        assertEquals(68_898_035L, repository.getXPForLevel(100));
        assertEquals(100, repository.getMaxLevel());
    }

    @Test
    @DisplayName("Should replace stale levels with bundled levels")
    void shouldReplaceStaleLevelsWithBundledLevels() throws SQLException {
        openDatabase();
        createMinimalProgressionSchema();
        insertLevel(1, 999L);
        upsertDataVersion("levels", 0);

        repository.initialize();

        assertEquals(100, countLevels());
        assertEquals(1, getDataVersion("levels"));
        assertEquals(0L, getXPForLevel(1));
        assertEquals(1_050L, getXPForLevel(2));
    }

    @Test
    @DisplayName("Should keep current-version levels unchanged")
    void shouldKeepCurrentVersionLevelsUnchanged() throws SQLException {
        openDatabase();
        createMinimalProgressionSchema();
        insertLevel(1, 12_345L);
        upsertDataVersion("levels", 1);

        repository.initialize();

        assertEquals(1, countLevels());
        assertEquals(1, getDataVersion("levels"));
        assertEquals(12_345L, getXPForLevel(1));
        assertEquals(1, repository.getMaxLevel());
    }

    private void openDatabase() throws SQLException {
        database = new DatabaseProvider();
        database.initialize(tempDir.resolve("duntale-test.db"));
        repository = new ProgressionRepository(database);
    }

    private void createMinimalProgressionSchema() throws SQLException {
        database.write(conn -> {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS levels (
                            level INTEGER PRIMARY KEY,
                            xp_required INTEGER NOT NULL
                        )
                        """);
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS data_versions (
                            dataset TEXT PRIMARY KEY,
                            version INTEGER NOT NULL,
                            applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                        )
                        """);
            }
        });
    }

    private void insertLevel(int level, long xpRequired) throws SQLException {
        String sql = "INSERT INTO levels (level, xp_required) VALUES (?, ?)";
        database.write(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, level);
                stmt.setLong(2, xpRequired);
                stmt.executeUpdate();
            }
        });
    }

    private void upsertDataVersion(String dataset, int version) throws SQLException {
        String sql = """
                INSERT INTO data_versions (dataset, version, applied_at)
                VALUES (?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (dataset)
                DO UPDATE SET version = ?, applied_at = CURRENT_TIMESTAMP
                """;
        database.write(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, dataset);
                stmt.setInt(2, version);
                stmt.setInt(3, version);
                stmt.executeUpdate();
            }
        });
    }

    private int countLevels() throws SQLException {
        return readInt("SELECT COUNT(*) FROM levels");
    }

    private int getDataVersion(String dataset) throws SQLException {
        String sql = "SELECT version FROM data_versions WHERE dataset = ?";
        return database.read(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, dataset);
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next() ? rs.getInt("version") : -1;
                }
            }
        });
    }

    private long getXPForLevel(int level) throws SQLException {
        String sql = "SELECT xp_required FROM levels WHERE level = ?";
        return database.read(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, level);
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next() ? rs.getLong("xp_required") : -1L;
                }
            }
        });
    }

    private int readInt(String sql) throws SQLException {
        return database.read(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        });
    }
}