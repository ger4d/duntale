package com.duntale.zsquad.db;

import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

/**
 * Provider-owned database access for the ZSquad plugin.
 *
 * <p>Manages a single SQLite {@link Connection} behind an exclusive {@link ReentrantLock}.
 * All database work flows through callback-based methods ({@link #read}, {@link #write},
 * {@link #transaction}) — no raw connection is ever exposed to callers.
 *
 * <p>The classloader workaround required by the Hytale runtime is applied internally
 * to every operation, so repositories and services never need to manage it.
 *
 * @since 1.5.0
 */
public final class DatabaseProvider {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final ReentrantLock lock = new ReentrantLock();
    private final ClassLoader jdbcClassLoader;

    @Nullable
    private Connection connection;
    private boolean initialized;

    public DatabaseProvider() {
        this.jdbcClassLoader = getClass().getClassLoader();
    }

    /**
     * Opens a SQLite connection to the given database file.
     *
     * <p>Creates the file if it does not exist. Configures WAL journal mode,
     * busy timeout, foreign keys, and synchronous mode.
     *
     * <p>Rejects double initialization with a logged warning.
     *
     * @param dbPath the path to the SQLite database file
     * @throws SQLException if the connection cannot be established
     */
    public void initialize(@Nonnull Path dbPath) throws SQLException {
        lock.lock();
        try {
            if (initialized && connection != null && !connection.isClosed()) {
                LOGGER.at(Level.WARNING).log("DatabaseProvider already initialized — ignoring duplicate call");
                return;
            }

            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException e) {
                throw new SQLException("SQLite JDBC driver not found on classpath", e);
            }

            String url = "jdbc:sqlite:" + dbPath.toAbsolutePath();
            connection = withClassLoader(() -> {
                try {
                    return DriverManager.getConnection(url);
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            });

            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA busy_timeout=5000");
                stmt.execute("PRAGMA synchronous=NORMAL");
                stmt.execute("PRAGMA foreign_keys=ON");
            }

            initialized = true;
            LOGGER.at(Level.INFO).log("DatabaseProvider initialized at %s", dbPath.toAbsolutePath());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Executes a read operation against the database.
     *
     * <p>In the current implementation, this acquires the same exclusive lock as
     * {@link #write} — the distinction is semantic only, to allow future read/write
     * separation without changing call sites.
     *
     * @param action the read operation
     * @param <T>    the return type
     * @return the result of the operation
     * @throws SQLException if a database access error occurs
     */
    @Nonnull
    public <T> T read(@Nonnull SqlFunction<T> action) throws SQLException {
        return execute(action);
    }

    /**
     * Executes a write operation against the database.
     *
     * @param action the write operation
     * @throws SQLException if a database access error occurs
     */
    public void write(@Nonnull SqlConsumer action) throws SQLException {
        execute(conn -> {
            action.accept(conn);
            return null;
        });
    }

    /**
     * Executes a write operation that returns a result.
     *
     * @param action the write operation
     * @param <T>    the return type
     * @return the result of the operation
     * @throws SQLException if a database access error occurs
     */
    @Nonnull
    public <T> T writeReturning(@Nonnull SqlFunction<T> action) throws SQLException {
        return execute(action);
    }

    /**
     * Executes a transactional operation against the database.
     *
     * <p>Disables auto-commit, executes the action, and commits on success.
     * On failure, rolls back and re-throws. Auto-commit is always restored in {@code finally}.
     *
     * @param action the transactional operation
     * @param <T>    the return type
     * @return the result of the operation
     * @throws SQLException if a database access error occurs (transaction is rolled back)
     */
    @Nonnull
    public <T> T transaction(@Nonnull SqlFunction<T> action) throws SQLException {
        lock.lock();
        try {
            Connection conn = requireConnection();
            return withClassLoader(() -> {
                try {
                    boolean wasAutoCommit = conn.getAutoCommit();
                    conn.setAutoCommit(false);
                    try {
                        T result = action.apply(conn);
                        conn.commit();
                        return result;
                    } catch (SQLException | RuntimeException e) {
                        try {
                            conn.rollback();
                        } catch (SQLException rollbackEx) {
                            e.addSuppressed(rollbackEx);
                        }
                        throw e;
                    } finally {
                        try {
                            conn.setAutoCommit(wasAutoCommit);
                        } catch (SQLException restoreEx) {
                            LOGGER.at(Level.WARNING).log("Failed to restore autoCommit: %s", restoreEx.getMessage());
                        }
                    }
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            throw unwrapSQLException(e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Executes a void transactional operation against the database.
     *
     * @param action the transactional operation
     * @throws SQLException if a database access error occurs (transaction is rolled back)
     */
    public void transaction(@Nonnull SqlConsumer action) throws SQLException {
        transaction(conn -> {
            action.accept(conn);
            return null;
        });
    }

    /**
     * Closes the database connection and releases resources.
     */
    public void close() {
        lock.lock();
        try {
            if (connection != null) {
                try {
                    connection.close();
                    LOGGER.at(Level.INFO).log("DatabaseProvider connection closed");
                } catch (SQLException e) {
                    LOGGER.at(Level.WARNING).log("Error closing database connection: %s", e.getMessage());
                }
                connection = null;
            }
            initialized = false;
        } finally {
            lock.unlock();
        }
    }

    // ── Internal ─────────────────────────────────────────────────────

    private <T> T execute(@Nonnull SqlFunction<T> action) throws SQLException {
        lock.lock();
        try {
            Connection conn = requireConnection();
            return withClassLoader(() -> {
                try {
                    return action.apply(conn);
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            throw unwrapSQLException(e);
        } finally {
            lock.unlock();
        }
    }

    @Nonnull
    private Connection requireConnection() {
        if (!initialized || connection == null) {
            throw new IllegalStateException("DatabaseProvider not initialized — call initialize() first");
        }
        try {
            if (connection.isClosed()) {
                throw new IllegalStateException("DatabaseProvider connection is closed");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to check connection state", e);
        }
        return connection;
    }

    private <T> T withClassLoader(@Nonnull java.util.function.Supplier<T> supplier) {
        ClassLoader prev = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(jdbcClassLoader);
        try {
            return supplier.get();
        } finally {
            Thread.currentThread().setContextClassLoader(prev);
        }
    }

    private static SQLException unwrapSQLException(@Nonnull RuntimeException exception) {
        if (exception.getCause() instanceof SQLException sqlException) {
            return sqlException;
        }
        throw exception;
    }
}
