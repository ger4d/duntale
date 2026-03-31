package com.duntale.zsquad.dungeon;

import com.duntale.dungeongen.config.Vec3i;
import com.duntale.zsquad.db.DatabaseProvider;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;

/**
 * SQL repository for persisted dungeon instance metadata.
 *
 * <p>Backs the {@code dungeon_instances} table used by the dungeon instance flow.
 * Stores the current world, floor metadata, generated entrance/exit positions, lifecycle state,
 * theme, seed, and creation timestamp for each instance.
 *
 * <h2>Usage:</h2>
 * <pre>{@code
 * DungeonInstanceRepository repository = new DungeonInstanceRepository(databaseProvider);
 * repository.initialize();
 *
 * DungeonInstance instance = repository.findById(instanceId).orElseThrow();
 * repository.endInstance(instance.instanceId());
 * }</pre>
 *
 * @since 1.6.0
 */
public class DungeonInstanceRepository {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS dungeon_instances (
                instance_id TEXT PRIMARY KEY,
                world_name  TEXT NOT NULL,
                floor_level INTEGER NOT NULL DEFAULT 1,
                floor_y     REAL NOT NULL,
                entrance_x  REAL NOT NULL,
                entrance_y  REAL NOT NULL,
                entrance_z  REAL NOT NULL,
                exit_x      REAL NOT NULL,
                exit_y      REAL NOT NULL,
                exit_z      REAL NOT NULL,
                state       TEXT NOT NULL DEFAULT 'CREATING',
                theme       TEXT NOT NULL,
                seed        TEXT,
                created_at  INTEGER NOT NULL
            )
            """;

    private static final String CREATE_WORLD_NAME_INDEX_SQL = """
            CREATE UNIQUE INDEX IF NOT EXISTS idx_dungeon_instances_world_name
                ON dungeon_instances(world_name)
            """;

    private static final String INSERT_INSTANCE_SQL = """
            INSERT INTO dungeon_instances (
                instance_id,
                world_name,
                floor_level,
                floor_y,
                entrance_x,
                entrance_y,
                entrance_z,
                exit_x,
                exit_y,
                exit_z,
                state,
                theme,
                seed,
                created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_BY_ID_SQL = """
            SELECT instance_id,
                   world_name,
                   floor_level,
                   floor_y,
                   entrance_x,
                   entrance_y,
                   entrance_z,
                   exit_x,
                   exit_y,
                   exit_z,
                   state,
                   theme,
                   seed,
                   created_at
              FROM dungeon_instances
             WHERE instance_id = ?
            """;

    private static final String SELECT_BY_WORLD_NAME_SQL = """
            SELECT instance_id,
                   world_name,
                   floor_level,
                   floor_y,
                   entrance_x,
                   entrance_y,
                   entrance_z,
                   exit_x,
                   exit_y,
                   exit_z,
                   state,
                   theme,
                   seed,
                   created_at
              FROM dungeon_instances
             WHERE world_name = ?
            """;

    private static final String SELECT_ALL_SQL = """
            SELECT instance_id,
                   world_name,
                   floor_level,
                   floor_y,
                   entrance_x,
                   entrance_y,
                   entrance_z,
                   exit_x,
                   exit_y,
                   exit_z,
                   state,
                   theme,
                   seed,
                   created_at
              FROM dungeon_instances
             ORDER BY created_at ASC
            """;

    private static final String UPDATE_INSTANCE_SQL = """
            UPDATE dungeon_instances
               SET world_name = ?,
                   floor_level = ?,
                   floor_y = ?,
                   entrance_x = ?,
                   entrance_y = ?,
                   entrance_z = ?,
                   exit_x = ?,
                   exit_y = ?,
                   exit_z = ?,
                   state = ?,
                   theme = ?,
                   seed = ?
             WHERE instance_id = ?
            """;

    private static final String END_INSTANCE_SQL = """
            UPDATE dungeon_instances
               SET state = ?
             WHERE instance_id = ?
            """;

    private final DatabaseProvider database;

    /**
     * Creates a new dungeon instance repository backed by the given database provider.
     *
     * @param database the database provider
     */
    public DungeonInstanceRepository(@Nonnull DatabaseProvider database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    /**
     * Creates the {@code dungeon_instances} table and its lookup index if they do not already exist.
     *
     * @throws SQLException if schema creation fails
     */
    public void initialize() throws SQLException {
        database.transaction(conn -> {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(CREATE_TABLE_SQL);
                stmt.execute(CREATE_WORLD_NAME_INDEX_SQL);
            }
        });
        LOGGER.at(Level.INFO).log("dungeon_instances table initialized");
    }

    /**
     * Inserts a new persisted dungeon instance row.
     *
     * @param instance the instance metadata to insert
     * @throws SQLException if the insert fails
     */
    public void create(@Nonnull DungeonInstance instance) throws SQLException {
        Objects.requireNonNull(instance, "instance");
        database.write(conn -> {
            createInTransaction(conn, instance);
        });
    }

    void createInTransaction(@Nonnull Connection conn, @Nonnull DungeonInstance instance) throws SQLException {
        Objects.requireNonNull(conn, "conn");
        Objects.requireNonNull(instance, "instance");
        try (PreparedStatement ps = conn.prepareStatement(INSERT_INSTANCE_SQL)) {
            bindCreate(ps, instance);
            requireSingleRow(ps.executeUpdate(), "insert dungeon instance " + instance.instanceId());
        }
    }

    /**
     * Loads a dungeon instance by its unique instance identifier.
     *
     * @param instanceId the instance identifier
     * @return the persisted instance metadata, or {@link Optional#empty()} if no row exists
     * @throws SQLException if the query fails
     */
    @Nonnull
    public Optional<DungeonInstance> findById(@Nonnull String instanceId) throws SQLException {
        Objects.requireNonNull(instanceId, "instanceId");
        return database.read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(SELECT_BY_ID_SQL)) {
                ps.setString(1, instanceId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
                }
            }
        });
    }

    /**
     * Loads a dungeon instance by its current world name.
     *
     * @param worldName the world name
     * @return the persisted instance metadata, or {@link Optional#empty()} if no row exists
     * @throws SQLException if the query fails
     */
    @Nonnull
    public Optional<DungeonInstance> findByWorldName(@Nonnull String worldName) throws SQLException {
        Objects.requireNonNull(worldName, "worldName");
        return database.read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(SELECT_BY_WORLD_NAME_SQL)) {
                ps.setString(1, worldName);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
                }
            }
        });
    }

    /**
     * Loads all persisted dungeon instances ordered by creation time.
     *
     * @return immutable list of persisted dungeon instances
     * @throws SQLException if the query fails
     */
    @Nonnull
    public List<DungeonInstance> findAll() throws SQLException {
        return database.read(conn -> {
            List<DungeonInstance> instances = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(SELECT_ALL_SQL);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    instances.add(mapRow(rs));
                }
            }
            return List.copyOf(instances);
        });
    }

    /**
     * Updates the mutable metadata for a persisted dungeon instance row.
     *
     * <p>The instance identifier and creation timestamp remain unchanged.
     *
     * @param instance the updated instance metadata
     * @throws SQLException if the update fails or no matching row exists
     */
    public void update(@Nonnull DungeonInstance instance) throws SQLException {
        Objects.requireNonNull(instance, "instance");
        database.write(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(UPDATE_INSTANCE_SQL)) {
                bindMutableFields(ps, instance);
                ps.setString(13, instance.instanceId());
                requireSingleRow(ps.executeUpdate(), "update dungeon instance " + instance.instanceId());
            }
        });
    }

    /**
     * Marks the given dungeon instance as ended.
     *
     * @param instanceId the instance identifier
     * @throws SQLException if the update fails or no matching row exists
     */
    public void endInstance(@Nonnull String instanceId) throws SQLException {
        Objects.requireNonNull(instanceId, "instanceId");
        database.write(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(END_INSTANCE_SQL)) {
                ps.setString(1, DungeonInstanceState.ENDED.name());
                ps.setString(2, instanceId);
                requireSingleRow(ps.executeUpdate(), "end dungeon instance " + instanceId);
            }
        });
    }

    private static void bindCreate(@Nonnull PreparedStatement ps, @Nonnull DungeonInstance instance)
            throws SQLException {
        ps.setString(1, instance.instanceId());
        bindMutableFields(ps, instance, 2);
        ps.setLong(14, instance.createdAt());
    }

    private static void bindMutableFields(@Nonnull PreparedStatement ps, @Nonnull DungeonInstance instance)
            throws SQLException {
        bindMutableFields(ps, instance, 1);
    }

    private static void bindMutableFields(
            @Nonnull PreparedStatement ps,
            @Nonnull DungeonInstance instance,
            int startIndex
    ) throws SQLException {
        int index = startIndex;
        ps.setString(index++, instance.worldName());
        ps.setInt(index++, instance.floorLevel());
        ps.setDouble(index++, instance.floorY());
        ps.setDouble(index++, instance.entrancePosition().x());
        ps.setDouble(index++, instance.entrancePosition().y());
        ps.setDouble(index++, instance.entrancePosition().z());
        ps.setDouble(index++, instance.exitPosition().x());
        ps.setDouble(index++, instance.exitPosition().y());
        ps.setDouble(index++, instance.exitPosition().z());
        ps.setString(index++, instance.state().name());
        ps.setString(index++, instance.theme());
        ps.setString(index, instance.seed());
    }

    @Nonnull
    private static DungeonInstance mapRow(@Nonnull ResultSet rs) throws SQLException {
        return new DungeonInstance(
                rs.getString("instance_id"),
                rs.getString("world_name"),
                rs.getInt("floor_level"),
                rs.getDouble("floor_y"),
                new Vec3i(
                        readWholeCoordinate(rs, "entrance_x"),
                        readWholeCoordinate(rs, "entrance_y"),
                        readWholeCoordinate(rs, "entrance_z")
                ),
                new Vec3i(
                        readWholeCoordinate(rs, "exit_x"),
                        readWholeCoordinate(rs, "exit_y"),
                        readWholeCoordinate(rs, "exit_z")
                ),
                readState(rs.getString("state")),
                rs.getString("theme"),
                rs.getString("seed"),
                rs.getLong("created_at")
        );
    }

    private static int readWholeCoordinate(@Nonnull ResultSet rs, @Nonnull String columnName) throws SQLException {
        double value = rs.getDouble(columnName);
        double rounded = Math.rint(value);
        if (Math.abs(value - rounded) > 1.0E-9D) {
            throw new SQLException("Column " + columnName + " contains non-integral coordinate " + value);
        }
        return (int) rounded;
    }

    @Nonnull
    private static DungeonInstanceState readState(@Nonnull String storedValue) throws SQLException {
        try {
            return DungeonInstanceState.valueOf(storedValue);
        } catch (IllegalArgumentException e) {
            throw new SQLException("Unknown dungeon instance state: " + storedValue, e);
        }
    }

    private static void requireSingleRow(int affectedRows, @Nonnull String action) throws SQLException {
        if (affectedRows != 1) {
            throw new SQLException("Expected 1 row to " + action + " but affected " + affectedRows);
        }
    }
}
