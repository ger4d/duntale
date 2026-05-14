package com.duntale.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DatabaseProvider")
class DatabaseProviderTest {

    @TempDir
    Path tempDir;

    private DatabaseProvider database;

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    @DisplayName("Should create missing parent directories before opening SQLite database")
    void shouldCreateMissingParentDirectoriesBeforeOpeningSqliteDatabase() throws SQLException {
        Path dbPath = tempDir.resolve("mods").resolve("com.duntale_Duntale").resolve("duntale.db");

        database = new DatabaseProvider();
        database.initialize(dbPath);

        assertTrue(Files.isDirectory(dbPath.getParent()));
        assertTrue(Files.exists(dbPath));
    }
}