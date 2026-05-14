package com.duntale.progression;

import com.duntale.db.DatabaseProvider;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
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
 *   <li>{@code data_versions} — project-owned bootstrap data versions</li>
 *   <li>{@code player_progression} — player data (uuid, level, xp, season)</li>
 * </ul>
 *
 * <p>Adapted from {@code com.duntale.hub.core.progression.ProgressionRepository}.
 *
 * @since 1.0.0
 */
public class ProgressionRepository {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String LEVELS_DATASET = "levels";
    private static final String LEVELS_RESOURCE = "levels.csv";
    private static final String LEVELS_HEADER = "level,xp_required";

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
        applyBundledLevelsIfNeeded();
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
                CREATE TABLE IF NOT EXISTS data_versions (
                    dataset TEXT PRIMARY KEY,
                    version INTEGER NOT NULL,
                    applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
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

    private void applyBundledLevelsIfNeeded() throws SQLException {
        BundledLevels bundledLevels = loadBundledLevels();
        Integer currentVersion = getDataVersion(LEVELS_DATASET);
        if (currentVersion != null && currentVersion >= bundledLevels.version()) {
            LOGGER.atInfo().log("Levels dataset already at version %d", currentVersion);
            return;
        }

        database.transaction(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM levels")) {
                stmt.executeUpdate();
            }

            String insertLevelSql = "INSERT INTO levels (level, xp_required) VALUES (?, ?)";
            try (PreparedStatement stmt = conn.prepareStatement(insertLevelSql)) {
                for (LevelThreshold threshold : bundledLevels.thresholds()) {
                    stmt.setInt(1, threshold.level());
                    stmt.setLong(2, threshold.xpRequired());
                    stmt.addBatch();
                }
                stmt.executeBatch();
            }

            String upsertVersionSql = """
                    INSERT INTO data_versions (dataset, version, applied_at)
                    VALUES (?, ?, CURRENT_TIMESTAMP)
                    ON CONFLICT (dataset)
                    DO UPDATE SET version = ?, applied_at = CURRENT_TIMESTAMP
                    """;
            try (PreparedStatement stmt = conn.prepareStatement(upsertVersionSql)) {
                stmt.setString(1, LEVELS_DATASET);
                stmt.setInt(2, bundledLevels.version());
                stmt.setInt(3, bundledLevels.version());
                stmt.executeUpdate();
            }
        });

        LOGGER.atInfo().log(
                "Applied bundled levels dataset version %d with %d thresholds",
                bundledLevels.version(),
                bundledLevels.thresholds().size()
        );
    }

    private Integer getDataVersion(@Nonnull String dataset) throws SQLException {
        String sql = "SELECT version FROM data_versions WHERE dataset = ?";
        return database.read(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, dataset);
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next() ? rs.getInt("version") : null;
                }
            }
        });
    }

    @Nonnull
    private BundledLevels loadBundledLevels() throws SQLException {
        try (InputStream stream = ProgressionRepository.class.getResourceAsStream(LEVELS_RESOURCE)) {
            if (stream == null) {
                throw new SQLException("Missing bundled levels resource: " + LEVELS_RESOURCE);
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                int version = parseVersion(reader.readLine());
                String header = reader.readLine();
                if (!LEVELS_HEADER.equals(header)) {
                    throw new SQLException("Invalid bundled levels header: " + header);
                }

                List<LevelThreshold> thresholds = new ArrayList<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    thresholds.add(parseThreshold(line));
                }

                if (thresholds.isEmpty()) {
                    throw new SQLException("Bundled levels resource contains no thresholds");
                }
                return new BundledLevels(version, List.copyOf(thresholds));
            }
        } catch (IOException e) {
            throw new SQLException("Failed to read bundled levels resource", e);
        }
    }

    private int parseVersion(String line) throws SQLException {
        if (line == null || !line.startsWith("# version=")) {
            throw new SQLException("Missing bundled levels version header");
        }
        try {
            return Integer.parseInt(line.substring("# version=".length()).trim());
        } catch (NumberFormatException e) {
            throw new SQLException("Invalid bundled levels version: " + line, e);
        }
    }

    @Nonnull
    private LevelThreshold parseThreshold(@Nonnull String line) throws SQLException {
        String[] parts = line.split(",", -1);
        if (parts.length != 2) {
            throw new SQLException("Invalid bundled levels row: " + line);
        }
        try {
            return new LevelThreshold(Integer.parseInt(parts[0]), Long.parseLong(parts[1]));
        } catch (NumberFormatException e) {
            throw new SQLException("Invalid bundled levels row: " + line, e);
        }
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

    private record BundledLevels(int version, @Nonnull List<LevelThreshold> thresholds) {
    }

    private record LevelThreshold(int level, long xpRequired) {
    }
}
