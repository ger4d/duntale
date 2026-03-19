package com.duntale.zsquad.rpg;

import com.duntale.zsquad.db.DatabaseProvider;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * SQL CRUD repository for per-player RPG stat profiles.
 *
 * <p>All operations use {@link PreparedStatement} with try-with-resources.
 * The backing table is {@code player_stats} keyed by {@code (uuid, stat)}.
 */
public class RpgRepository {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final String CREATE_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS player_stats ("
                    + "uuid   TEXT NOT NULL, "
                    + "stat   TEXT NOT NULL, "
                    + "value  INTEGER NOT NULL DEFAULT 0, "
                    + "PRIMARY KEY (uuid, stat)"
                    + ")";

    private static final String SELECT_PROFILE_SQL =
            "SELECT stat, value FROM player_stats WHERE uuid = ?";

    private static final String UPSERT_STAT_SQL =
            "INSERT INTO player_stats (uuid, stat, value) VALUES (?, ?, ?) "
                    + "ON CONFLICT(uuid, stat) DO UPDATE SET value = excluded.value";

    /** Special key used to store unassigned stat points in the same table. */
    static final String UNASSIGNED_POINTS_KEY = "UNASSIGNED_POINTS";

    private static final String SELECT_UNASSIGNED_SQL =
            "SELECT value FROM player_stats WHERE uuid = ? AND stat = '" + UNASSIGNED_POINTS_KEY + "'";

    private static final String UPSERT_UNASSIGNED_SQL =
            "INSERT INTO player_stats (uuid, stat, value) VALUES (?, '" + UNASSIGNED_POINTS_KEY + "', ?) "
                    + "ON CONFLICT(uuid, stat) DO UPDATE SET value = excluded.value";

    private final DatabaseProvider database;

    /**
     * Creates a new RPG repository backed by the given database provider.
     *
     * @param database the database provider
     */
    public RpgRepository(@Nonnull DatabaseProvider database) {
        this.database = database;
    }

    /**
     * Creates the {@code player_stats} table if it does not already exist.
     *
     * @throws SQLException if table creation fails
     */
    public void initialize() throws SQLException {
        database.write(conn -> {
            try (var stmt = conn.createStatement()) {
                stmt.execute(CREATE_TABLE_SQL);
            }
        });
        LOGGER.at(Level.INFO).log("player_stats table initialized");
    }

    /**
     * Loads the full RPG profile for the given player.
     *
     * <p>If no rows exist for the player, returns a new empty profile with all stats at 0.
     *
     * @param playerId the player's UUID
     * @return the player's RPG profile
     * @throws SQLException if the query fails
     */
    @Nonnull
    public RpgProfile loadProfile(@Nonnull UUID playerId) throws SQLException {
        return database.read(conn -> {
            Map<RpgStat, Integer> stats = new EnumMap<>(RpgStat.class);
            try (PreparedStatement ps = conn.prepareStatement(SELECT_PROFILE_SQL)) {
                ps.setString(1, playerId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String statName = rs.getString("stat");
                        int value = rs.getInt("value");
                        if (UNASSIGNED_POINTS_KEY.equals(statName)) {
                            continue;
                        }
                        try {
                            RpgStat stat = RpgStat.valueOf(statName);
                            stats.put(stat, value);
                        } catch (IllegalArgumentException e) {
                            LOGGER.at(Level.WARNING).log(
                                    "Unknown stat '%s' for player %s — skipping", statName, playerId);
                        }
                    }
                }
            }
            return stats.isEmpty() ? new RpgProfile() : new RpgProfile(stats);
        });
    }

    /**
     * Upserts a single stat value for the given player.
     *
     * @param playerId the player's UUID
     * @param stat     the stat to save
     * @param value    the stat value
     * @throws SQLException if the upsert fails
     */
    public void saveStat(@Nonnull UUID playerId, @Nonnull RpgStat stat, int value) throws SQLException {
        database.write(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(UPSERT_STAT_SQL)) {
                ps.setString(1, playerId.toString());
                ps.setString(2, stat.name());
                ps.setInt(3, value);
                ps.executeUpdate();
            }
        });
    }

    /**
     * Loads the unassigned stat points for the given player.
     *
     * @param playerId the player's UUID
     * @return the number of unassigned points, or {@code 0} if none
     * @throws SQLException if the query fails
     */
    public int loadUnassignedPoints(@Nonnull UUID playerId) throws SQLException {
        return database.read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(SELECT_UNASSIGNED_SQL)) {
                ps.setString(1, playerId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt("value") : 0;
                }
            }
        });
    }

    /**
     * Saves the unassigned stat points for the given player.
     *
     * @param playerId the player's UUID
     * @param points   the number of unassigned points
     * @throws SQLException if the upsert fails
     */
    public void saveUnassignedPoints(@Nonnull UUID playerId, int points) throws SQLException {
        database.write(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(UPSERT_UNASSIGNED_SQL)) {
                ps.setString(1, playerId.toString());
                ps.setInt(2, points);
                ps.executeUpdate();
            }
        });
    }

    /**
     * Batch-upserts all stats from the given profile for the given player.
     *
     * <p>Uses a single {@link PreparedStatement} with batch execution for efficiency.
     *
     * @param playerId the player's UUID
     * @param profile  the RPG profile to save
     * @throws SQLException if the batch upsert fails
     */
    public void saveProfile(@Nonnull UUID playerId, @Nonnull RpgProfile profile) throws SQLException {
        database.write(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(UPSERT_STAT_SQL)) {
                String uuid = playerId.toString();
                for (Map.Entry<RpgStat, Integer> entry : profile.getAll().entrySet()) {
                    ps.setString(1, uuid);
                    ps.setString(2, entry.getKey().name());
                    ps.setInt(3, entry.getValue());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }
}
