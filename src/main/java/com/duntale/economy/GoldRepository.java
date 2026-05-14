package com.duntale.economy;

import com.duntale.db.DatabaseProvider;
import com.duntale.rpg.RpgConstants;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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

    private final DatabaseProvider database;

    /**
     * Creates a new gold repository backed by the given database provider.
     *
     * @param database the database provider
     */
    public GoldRepository(@Nonnull DatabaseProvider database) {
        this.database = database;
    }

    /**
     * Creates the {@code player_gold} table if it does not already exist.
     *
     * @throws SQLException if table creation fails
     */
    public void initialize() throws SQLException {
        database.write(conn -> {
            try (var stmt = conn.createStatement()) {
                stmt.execute(CREATE_TABLE_SQL);
            }
        });
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
        return database.read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(SELECT_BALANCE_SQL)) {
                ps.setString(1, playerId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong("balance") : 0L;
                }
            }
        });
    }

    /**
     * Sets the gold balance for the given player (upsert).
     *
     * @param playerId the player's UUID
     * @param balance  the new balance value
     * @throws SQLException if the upsert fails
     */
    public void setBalance(@Nonnull UUID playerId, long balance) throws SQLException {
        database.write(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(UPSERT_BALANCE_SQL)) {
                ps.setString(1, playerId.toString());
                ps.setLong(2, balance);
                ps.executeUpdate();
            }
        });
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
        database.write(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(ADD_BALANCE_SQL)) {
                ps.setString(1, playerId.toString());
                ps.setLong(2, delta);
                ps.setLong(3, delta);
                ps.executeUpdate();
            }
        });
    }

    /**
     * Atomically transfers gold from one player to another.
     *
     * <p>Returns {@code false} for business-rule rejection (insufficient funds) with a clean
     * commit and no data changes. Throws {@link SQLException} only on actual database failures,
     * causing the provider to roll back.
     *
     * @param from   the sender's UUID
     * @param to     the receiver's UUID
     * @param amount the amount of gold to transfer (must be positive)
     * @return a {@link TransferResult} with balances on success, or {@link TransferResult#INSUFFICIENT_FUNDS} if the sender lacks funds
     * @throws SQLException if a database access error occurs
     */
    @Nonnull
    public TransferResult transfer(@Nonnull UUID from, @Nonnull UUID to, long amount) throws SQLException {
        return database.transaction(conn -> {
            long fromBalance;
            try (PreparedStatement ps = conn.prepareStatement(SELECT_BALANCE_SQL)) {
                ps.setString(1, from.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    fromBalance = rs.next() ? rs.getLong("balance") : 0L;
                }
            }

            if (fromBalance < amount) {
                return TransferResult.INSUFFICIENT_FUNDS;
            }

            long toBalance;
            try (PreparedStatement ps = conn.prepareStatement(SELECT_BALANCE_SQL)) {
                ps.setString(1, to.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    toBalance = rs.next() ? rs.getLong("balance") : 0L;
                }
            }

            long newFromBalance = fromBalance - amount;
            long newToBalance = Math.min(toBalance + amount, RpgConstants.MAX_GOLD_BALANCE);

            try (PreparedStatement ps = conn.prepareStatement(UPSERT_BALANCE_SQL)) {
                ps.setString(1, from.toString());
                ps.setLong(2, newFromBalance);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(UPSERT_BALANCE_SQL)) {
                ps.setString(1, to.toString());
                ps.setLong(2, newToBalance);
                ps.executeUpdate();
            }

            return new TransferResult(true, fromBalance, newFromBalance, toBalance, newToBalance);
        });
    }

    /**
     * Result of a gold transfer operation.
     *
     * @param success        whether the transfer succeeded
     * @param fromOldBalance the sender's balance before the transfer
     * @param fromNewBalance the sender's balance after the transfer
     * @param toOldBalance   the receiver's balance before the transfer
     * @param toNewBalance   the receiver's balance after the transfer
     */
    public record TransferResult(
            boolean success,
            long fromOldBalance,
            long fromNewBalance,
            long toOldBalance,
            long toNewBalance
    ) {
        static final TransferResult INSUFFICIENT_FUNDS = new TransferResult(false, 0, 0, 0, 0);
    }
}
