package com.duntale.zsquad.dungeon;

import com.duntale.zsquad.db.DatabaseProvider;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.logging.Level;

/**
 * SQL repository for per-floor generation config overrides.
 *
 * <p>Backs the {@code floor_config_overrides} table. Each row stores a floor level
 * and a sparse JSON map of field overrides that should be applied when generating
 * dungeons at or above that floor level (using rebase inheritance).
 *
 * @since 1.6.0
 */
public class FloorConfigRepository {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS floor_config_overrides (
                floor_level    INTEGER PRIMARY KEY,
                overrides_json TEXT NOT NULL
            )
            """;

    private static final String UPSERT_SQL = """
            INSERT INTO floor_config_overrides (floor_level, overrides_json)
            VALUES (?, ?)
            ON CONFLICT(floor_level) DO UPDATE SET overrides_json = excluded.overrides_json
            """;

    private static final String SELECT_BY_FLOOR_SQL = """
            SELECT overrides_json FROM floor_config_overrides WHERE floor_level = ?
            """;

    private static final String SELECT_ALL_SQL = """
            SELECT floor_level, overrides_json
              FROM floor_config_overrides
             ORDER BY floor_level ASC
            """;

    private static final String DELETE_SQL = """
            DELETE FROM floor_config_overrides WHERE floor_level = ?
            """;

    private static final String LIST_FLOORS_SQL = """
            SELECT floor_level FROM floor_config_overrides ORDER BY floor_level ASC
            """;

    private final DatabaseProvider database;

    /**
     * Creates a new floor config repository backed by the given database provider.
     *
     * @param database the database provider
     */
    public FloorConfigRepository(@Nonnull DatabaseProvider database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    /**
     * Creates the {@code floor_config_overrides} table if it does not already exist.
     *
     * @throws SQLException if schema creation fails
     */
    public void initialize() throws SQLException {
        database.write(conn -> {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(CREATE_TABLE_SQL);
            }
        });
        LOGGER.at(Level.INFO).log("floor_config_overrides table initialized");
    }

    /**
     * Upserts the overrides JSON for a given floor level.
     *
     * @param floorLevel    the floor level
     * @param overridesJson the sparse JSON override map
     * @throws SQLException if the upsert fails
     */
    public void save(int floorLevel, @Nonnull String overridesJson) throws SQLException {
        Objects.requireNonNull(overridesJson, "overridesJson");
        database.write(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(UPSERT_SQL)) {
                ps.setInt(1, floorLevel);
                ps.setString(2, overridesJson);
                ps.executeUpdate();
            }
        });
    }

    /**
     * Loads the overrides JSON for a given floor level.
     *
     * @param floorLevel the floor level
     * @return the stored JSON string, or empty if no override is defined for this floor
     * @throws SQLException if the query fails
     */
    @Nonnull
    public Optional<String> load(int floorLevel) throws SQLException {
        return database.read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(SELECT_BY_FLOOR_SQL)) {
                ps.setInt(1, floorLevel);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getString(1)) : Optional.<String>empty();
                }
            }
        });
    }

    /**
     * Loads all floor overrides ordered by floor level.
     *
     * @return a sorted map of floor level to overrides JSON
     * @throws SQLException if the query fails
     */
    @Nonnull
    public TreeMap<Integer, String> loadAll() throws SQLException {
        return database.read(conn -> {
            TreeMap<Integer, String> result = new TreeMap<>();
            try (PreparedStatement ps = conn.prepareStatement(SELECT_ALL_SQL);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getInt(1), rs.getString(2));
                }
            }
            return result;
        });
    }

    /**
     * Deletes all overrides for a given floor level.
     *
     * @param floorLevel the floor level to delete overrides for
     * @throws SQLException if the delete fails
     */
    public void delete(int floorLevel) throws SQLException {
        database.write(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(DELETE_SQL)) {
                ps.setInt(1, floorLevel);
                ps.executeUpdate();
            }
        });
    }

    /**
     * Returns the floor levels that have defined overrides, ordered ascending.
     *
     * @return immutable list of floor levels with defined overrides
     * @throws SQLException if the query fails
     */
    @Nonnull
    public List<Integer> listDefinedFloors() throws SQLException {
        return database.read(conn -> {
            List<Integer> floors = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(LIST_FLOORS_SQL);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    floors.add(rs.getInt(1));
                }
            }
            return List.copyOf(floors);
        });
    }
}
