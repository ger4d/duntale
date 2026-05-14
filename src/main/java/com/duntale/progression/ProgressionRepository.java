package com.duntale.progression;

import com.duntale.db.DatabaseProvider;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import java.sql.*;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Database access for player progression data (XP &amp; levels).
 *
 * <p>Manages level thresholds and player XP/level data in SQLite.
 * Uses in-memory caching for level thresholds to reduce database queries.
 *
 * <h2>Schema:</h2>
 * <ul>
 *   <li>{@code levels} — level thresholds (level, xp_required)</li>
 *   <li>{@code player_progression} — player data (uuid, level, xp, season)</li>
 * </ul>
 *
 * <p>Adapted from {@code com.duntale.hub.core.progression.ProgressionRepository}.
 *
 * @since 1.0.0
 */
public class ProgressionRepository {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final DatabaseProvider database;

    /** Cached level thresholds (level → xp required). */
    private final NavigableMap<Integer, Long> levelThresholds = new TreeMap<>();

    /**
     * Creates a new progression repository.
     *
     * @param database the database provider
     */
    public ProgressionRepository(@Nonnull DatabaseProvider database) {
        this.database = database;
    }

    /**
     * Initialises the repository by creating the schema and loading thresholds.
     *
     * @throws SQLException if schema creation fails
     */
    public void initialize() throws SQLException {
        createSchema();
        reloadLevelThresholds();
    }

    // ── Schema ───────────────────────────────────────────────────────

    private void createSchema() throws SQLException {
        String[] schemas = {
                """
                CREATE TABLE IF NOT EXISTS levels (
                    level INTEGER PRIMARY KEY,
                    xp_required INTEGER NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS player_progression (
                    player_uuid TEXT PRIMARY KEY,
                    level INTEGER NOT NULL DEFAULT 1,
                    xp INTEGER NOT NULL DEFAULT 0,
                    season INTEGER NOT NULL DEFAULT 1,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """
        };

        database.write(conn -> {
            try (Statement stmt = conn.createStatement()) {
                for (String sql : schemas) {
                    stmt.execute(sql);
                }
            }
        });
        LOGGER.atInfo().log("Progression schema created/verified");
    }

    /**
     * Reloads level thresholds from the database.
     *
     * <p>Clears the cache and reloads all thresholds.
     */
    public void reloadLevelThresholds() {
        levelThresholds.clear();
        String sql = "SELECT level, xp_required FROM levels ORDER BY level";
        try {
            database.read(conn -> {
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {
                    while (rs.next()) {
                        levelThresholds.put(rs.getInt("level"), rs.getLong("xp_required"));
                    }
                }
                return null;
            });
        } catch (SQLException e) {
            LOGGER.atSevere().log("Failed to load level thresholds: %s", e.getMessage());
        }
        LOGGER.atInfo().log("Loaded %d level thresholds", levelThresholds.size());
    }

    // ── Level Calculation ────────────────────────────────────────────

    /**
     * Calculates the level for a given amount of total XP.
     *
     * <p>Finds the highest level whose XP threshold is &le; {@code totalXP}.
     *
     * @param totalXP the total XP amount
     * @return the calculated level (minimum 1)
     */
    public int calculateLevel(long totalXP) {
        int level = 1;
        for (Map.Entry<Integer, Long> entry : levelThresholds.entrySet()) {
            if (totalXP >= entry.getValue()) {
                level = entry.getKey();
            } else {
                break;
            }
        }
        return level;
    }

    /**
     * Gets the XP required to reach a specific level.
     *
     * @param level the level
     * @return the XP required, or 0 if level not defined
     */
    public long getXPForLevel(int level) {
        Long exactXP = levelThresholds.get(level);
        if (exactXP != null) {
            return exactXP;
        }

        // Interpolate between milestones
        Map.Entry<Integer, Long> lower = levelThresholds.floorEntry(level);
        Map.Entry<Integer, Long> higher = levelThresholds.ceilingEntry(level);

        if (lower == null) return 0;
        if (higher == null) return lower.getValue();

        int levelDiff = higher.getKey() - lower.getKey();
        long xpDiff = higher.getValue() - lower.getValue();
        int levelsFromLower = level - lower.getKey();

        return lower.getValue() + (xpDiff * levelsFromLower / levelDiff);
    }

    /**
     * Gets the maximum defined level.
     *
     * @return the highest level in the thresholds, or 1 if empty
     */
    public int getMaxLevel() {
        return levelThresholds.isEmpty() ? 1 : levelThresholds.lastKey();
    }

    // ── Player Data ──────────────────────────────────────────────────

    /**
     * Gets a player's current level.
     *
     * @param playerId the player's UUID
     * @return the player's level (default 1)
     */
    public int getLevel(@Nonnull UUID playerId) {
        String sql = "SELECT level FROM player_progression WHERE player_uuid = ?";
        try {
            return database.read(conn -> {
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, playerId.toString());
                    try (ResultSet rs = stmt.executeQuery()) {
                        return rs.next() ? rs.getInt("level") : 1;
                    }
                }
            });
        } catch (SQLException e) {
            LOGGER.atWarning().log("Error getting level for %s: %s", playerId, e.getMessage());
            return 1;
        }
    }

    /**
     * Gets a player's total XP.
     *
     * @param playerId the player's UUID
     * @return the player's total XP (default 0)
     */
    public long getXP(@Nonnull UUID playerId) {
        String sql = "SELECT xp FROM player_progression WHERE player_uuid = ?";
        try {
            return database.read(conn -> {
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, playerId.toString());
                    try (ResultSet rs = stmt.executeQuery()) {
                        return rs.next() ? rs.getLong("xp") : 0L;
                    }
                }
            });
        } catch (SQLException e) {
            LOGGER.atWarning().log("Error getting XP for %s: %s", playerId, e.getMessage());
            return 0;
        }
    }

    /**
     * Gets a player's current season.
     *
     * @param playerId the player's UUID
     * @return the player's season (default 1)
     */
    public int getSeason(@Nonnull UUID playerId) {
        String sql = "SELECT season FROM player_progression WHERE player_uuid = ?";
        try {
            return database.read(conn -> {
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, playerId.toString());
                    try (ResultSet rs = stmt.executeQuery()) {
                        return rs.next() ? rs.getInt("season") : 1;
                    }
                }
            });
        } catch (SQLException e) {
            LOGGER.atWarning().log("Error getting season for %s: %s", playerId, e.getMessage());
            return 1;
        }
    }

    /**
     * Ensures a player exists in the database.
     *
     * @param playerId the player's UUID
     */
    public void ensurePlayerExists(@Nonnull UUID playerId) {
        String sql = """
                INSERT INTO player_progression (player_uuid, level, xp, updated_at)
                VALUES (?, 1, 0, CURRENT_TIMESTAMP)
                ON CONFLICT (player_uuid) DO NOTHING
                """;
        try {
            database.write(conn -> {
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, playerId.toString());
                    stmt.executeUpdate();
                }
            });
        } catch (SQLException e) {
            LOGGER.atWarning().log("Failed to ensure player exists for %s: %s", playerId, e.getMessage());
        }
    }

    /**
     * Saves a player's progression data.
     *
     * @param playerId the player's UUID
     * @param level    the player's level
     * @param xp       the player's total XP
     */
    public void saveProgress(@Nonnull UUID playerId, int level, long xp) {
        String sql = """
                INSERT INTO player_progression (player_uuid, level, xp, updated_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (player_uuid)
                DO UPDATE SET level = ?, xp = ?, updated_at = CURRENT_TIMESTAMP
                """;
        try {
            database.write(conn -> {
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, playerId.toString());
                    stmt.setInt(2, level);
                    stmt.setLong(3, xp);
                    stmt.setInt(4, level);
                    stmt.setLong(5, xp);
                    stmt.executeUpdate();
                }
            });
        } catch (SQLException e) {
            LOGGER.atWarning().log("Failed to save progress for %s: %s", playerId, e.getMessage());
        }
    }
}
