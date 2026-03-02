package com.duntale.zsquad.economy;

import com.duntale.zsquad.db.DatabaseConnection;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.logging.Level;

/**
 * SQL CRUD repository for player gold balances.
 *
 * <p>All operations use {@link PreparedStatement} with try-with-resources.
 * The backing table is {@code player_gold} keyed by player UUID.
 */
public class GoldRepository {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final String CREATE_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS player_gold ("
                    + "uuid    TEXT PRIMARY KEY, "
                    + "balance BIGINT NOT NULL DEFAULT 0"
                    + ")";

    private static final String SELECT_BALANCE_SQL =
            "SELECT balance FROM player_gold WHERE uuid = ?";

    private static final String UPSERT_BALANCE_SQL =
            "INSERT INTO player_gold (uuid, balance) VALUES (?, ?) "
                    + "ON CONFLICT(uuid) DO UPDATE SET balance = excluded.balance";

    private static final String ADD_BALANCE_SQL =
            "INSERT INTO player_gold (uuid, balance) VALUES (?, ?) "
                    + "ON CONFLICT(uuid) DO UPDATE SET balance = balance + ?";

    private final DatabaseConnection database;

    /**
     * Creates a new gold repository backed by the given database connection.
     *
     * @param database the shared database connection
     */
    public GoldRepository(@Nonnull DatabaseConnection database) {
        this.database = database;
    }

    /**
     * Creates the {@code player_gold} table if it does not already exist.
     *
     * @throws SQLException if table creation fails
     */
    public void initialize() throws SQLException {
        try (Statement stmt = database.getConnection().createStatement()) {
            stmt.execute(CREATE_TABLE_SQL);
        }
        LOGGER.at(Level.INFO).log("player_gold table initialized");
    }

    /**
     * Returns the gold balance for the given player.
     *
     * @param playerId the player's UUID
     * @return the player's balance, or {@code 0} if no row exists
     * @throws SQLException if the query fails
     */
    public long getBalance(@Nonnull UUID playerId) throws SQLException {
        try (PreparedStatement ps = database.getConnection().prepareStatement(SELECT_BALANCE_SQL)) {
            ps.setString(1, playerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("balance");
                }
                return 0;
            }
        }
    }

    /**
     * Sets the gold balance for the given player (upsert).
     *
     * @param playerId the player's UUID
     * @param balance  the new balance value
     * @throws SQLException if the upsert fails
     */
    public void setBalance(@Nonnull UUID playerId, long balance) throws SQLException {
        try (PreparedStatement ps = database.getConnection().prepareStatement(UPSERT_BALANCE_SQL)) {
            ps.setString(1, playerId.toString());
            ps.setLong(2, balance);
            ps.executeUpdate();
        }
    }

    /**
     * Atomically adds a delta to the player's gold balance.
     *
     * <p>If no row exists, inserts the delta as the initial balance.
     * If a row exists, increments the balance by the given delta.
     *
     * @param playerId the player's UUID
     * @param delta    the amount to add (may be negative)
     * @throws SQLException if the upsert fails
     */
    public void addBalance(@Nonnull UUID playerId, long delta) throws SQLException {
        try (PreparedStatement ps = database.getConnection().prepareStatement(ADD_BALANCE_SQL)) {
            ps.setString(1, playerId.toString());
            ps.setLong(2, delta);
            ps.setLong(3, delta);
            ps.executeUpdate();
        }
    }

    /**
     * Exposes the underlying JDBC connection for use in atomic multi-statement
     * transactions (e.g., gold transfers between players).
     *
     * @return the active database connection
     */
    @Nonnull
    public Connection getDirectConnection() {
        return database.getConnection();
    }
}
