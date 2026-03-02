package com.duntale.zsquad.db;

import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Shared SQLite connection lifecycle for the ZSquad plugin.
 *
 * <p>Opens a single database connection used by all repositories.
 * Configures WAL journal mode and a busy timeout for concurrent access.
 *
 * <p>All JDBC calls made from the WorldThread must go through
 * {@link #withJdbcClassLoader(Supplier)} to ensure the shaded SQLite
 * driver classloader is on the thread context.
 */
public final class DatabaseConnection {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    @Nullable
    private Connection connection;

    /** The classloader that loaded the SQLite JDBC driver — needed for WorldThread JDBC calls. */
    private final ClassLoader jdbcClassLoader;

    public DatabaseConnection() {
        this.jdbcClassLoader = getClass().getClassLoader();
    }

    /**
     * Opens a SQLite connection to the given database file.
     *
     * <p>Creates the file if it does not exist. Configures WAL journal mode
     * and a 5-second busy timeout.
     *
     * @param dbPath the path to the SQLite database file
     * @throws SQLException if the connection cannot be established
     */
    public void initialize(@Nonnull Path dbPath) throws SQLException {
        if (connection != null && !connection.isClosed()) {
            LOGGER.at(Level.WARNING).log("DatabaseConnection already initialized — ignoring duplicate call");
            return;
        }

        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver not found on classpath", e);
        }

        String url = "jdbc:sqlite:" + dbPath.toAbsolutePath();
        connection = withJdbcClassLoader(() -> {
            try {
                return DriverManager.getConnection(url);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA busy_timeout=5000");
        }

        LOGGER.at(Level.INFO).log("SQLite database opened at %s", dbPath.toAbsolutePath());
    }

    /**
     * Returns the active database connection.
     *
     * @return the active connection
     * @throws IllegalStateException if the connection has not been initialized or is closed
     */
    @Nonnull
    public Connection getConnection() {
        if (connection == null) {
            throw new IllegalStateException("DatabaseConnection not initialized — call initialize() first");
        }
        try {
            if (connection.isClosed()) {
                throw new IllegalStateException("DatabaseConnection is closed");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to check connection state", e);
        }
        return connection;
    }

    /**
     * Closes the database connection.
     */
    public void close() {
        if (connection != null) {
            try {
                connection.close();
                LOGGER.at(Level.INFO).log("SQLite database connection closed");
            } catch (SQLException e) {
                LOGGER.at(Level.WARNING).log("Error closing database connection: %s", e.getMessage());
            }
            connection = null;
        }
    }

    /**
     * Runs a JDBC operation with the plugin classloader set as the thread context classloader.
     *
     * <p>Required because JDBC calls from the WorldThread use a different context classloader
     * that cannot resolve shaded SQLite classes.
     *
     * @param supplier the operation to run
     * @param <T>      the return type
     * @return the result of the operation
     */
    @Nonnull
    public <T> T withJdbcClassLoader(@Nonnull Supplier<T> supplier) {
        ClassLoader prev = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(jdbcClassLoader);
        try {
            return supplier.get();
        } finally {
            Thread.currentThread().setContextClassLoader(prev);
        }
    }
}
