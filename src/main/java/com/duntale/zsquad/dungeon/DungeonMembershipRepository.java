package com.duntale.zsquad.dungeon;

import com.duntale.zsquad.db.DatabaseProvider;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * SQL repository for dungeon instance membership rows.
 *
 * <p>Backs the {@code dungeon_membership} table that links players to dungeon instances.
 * Query helpers support lookups by player UUID and by instance identifier.
 *
 * <h2>Usage:</h2>
 * <pre>{@code
 * DungeonMembershipRepository repository = new DungeonMembershipRepository(databaseProvider);
 * repository.initialize();
 *
 * repository.addMemberships(instanceId, List.of(playerOne, playerTwo));
 * Set<UUID> roster = repository.findPlayerIdsByInstance(instanceId);
 * }</pre>
 *
 * @since 1.6.0
 */
public class DungeonMembershipRepository {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS dungeon_membership (
                player_uuid TEXT NOT NULL,
                instance_id TEXT NOT NULL,
                PRIMARY KEY (player_uuid, instance_id),
                FOREIGN KEY (instance_id) REFERENCES dungeon_instances(instance_id)
            )
            """;

    private static final String CREATE_INSTANCE_INDEX_SQL = """
            CREATE INDEX IF NOT EXISTS idx_dungeon_membership_instance_id
                ON dungeon_membership(instance_id)
            """;

    private static final String INSERT_MEMBERSHIP_SQL = """
            INSERT INTO dungeon_membership (player_uuid, instance_id)
            VALUES (?, ?)
            """;

    private static final String SELECT_INSTANCE_IDS_BY_PLAYER_SQL = """
            SELECT instance_id
              FROM dungeon_membership
             WHERE player_uuid = ?
             ORDER BY instance_id
            """;

    private static final String SELECT_PLAYER_UUIDS_BY_INSTANCE_SQL = """
            SELECT player_uuid
              FROM dungeon_membership
             WHERE instance_id = ?
             ORDER BY player_uuid
            """;

    private final DatabaseProvider database;

    /**
     * Creates a new dungeon membership repository backed by the given database provider.
     *
     * @param database the database provider
     */
    public DungeonMembershipRepository(@Nonnull DatabaseProvider database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    /**
     * Creates the {@code dungeon_membership} table and its instance index if they do not exist.
     *
     * @throws SQLException if schema creation fails
     */
    public void initialize() throws SQLException {
        database.transaction(conn -> {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(CREATE_TABLE_SQL);
                stmt.execute(CREATE_INSTANCE_INDEX_SQL);
            }
        });
        LOGGER.at(Level.INFO).log("dungeon_membership table initialized");
    }

    /**
     * Inserts a single membership row linking a player to a dungeon instance.
     *
     * @param instanceId the instance identifier
     * @param playerId   the player UUID
     * @throws SQLException if the insert fails
     */
    public void addMembership(@Nonnull String instanceId, @Nonnull UUID playerId) throws SQLException {
        addMemberships(instanceId, List.of(playerId));
    }

    /**
     * Inserts multiple membership rows for the same dungeon instance in a single transaction.
     *
     * <p>If any insert fails, the entire batch is rolled back.
     *
     * @param instanceId the instance identifier
     * @param playerIds  the player UUIDs to insert
     * @throws SQLException if any insert fails
     */
    public void addMemberships(@Nonnull String instanceId, @Nonnull Collection<UUID> playerIds)
            throws SQLException {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(playerIds, "playerIds");

        database.transaction(conn -> {
            addMembershipsInTransaction(conn, instanceId, playerIds);
        });
    }

    void addMembershipsInTransaction(
            @Nonnull Connection conn,
            @Nonnull String instanceId,
            @Nonnull Collection<UUID> playerIds
    ) throws SQLException {
        Objects.requireNonNull(conn, "conn");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(playerIds, "playerIds");

        try (PreparedStatement ps = conn.prepareStatement(INSERT_MEMBERSHIP_SQL)) {
            for (UUID playerId : playerIds) {
                Objects.requireNonNull(playerId, "playerIds contains null");
                ps.setString(1, playerId.toString());
                ps.setString(2, instanceId);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * Loads all instance identifiers currently associated with the given player UUID.
     *
     * @param playerId the player UUID
     * @return immutable set of instance identifiers for that player
     * @throws SQLException if the query fails
     */
    @Nonnull
    public Set<String> findInstanceIdsByPlayer(@Nonnull UUID playerId) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        return database.read(conn -> {
            Set<String> instanceIds = new HashSet<>();
            try (PreparedStatement ps = conn.prepareStatement(SELECT_INSTANCE_IDS_BY_PLAYER_SQL)) {
                ps.setString(1, playerId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        instanceIds.add(rs.getString("instance_id"));
                    }
                }
            }
            return Set.copyOf(instanceIds);
        });
    }

    /**
     * Loads all player UUIDs currently associated with the given dungeon instance.
     *
     * @param instanceId the instance identifier
     * @return immutable set of player UUIDs in the instance
     * @throws SQLException if the query fails
     */
    @Nonnull
    public Set<UUID> findPlayerIdsByInstance(@Nonnull String instanceId) throws SQLException {
        Objects.requireNonNull(instanceId, "instanceId");
        return database.read(conn -> {
            Set<UUID> playerIds = new HashSet<>();
            try (PreparedStatement ps = conn.prepareStatement(SELECT_PLAYER_UUIDS_BY_INSTANCE_SQL)) {
                ps.setString(1, instanceId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        playerIds.add(UUID.fromString(rs.getString("player_uuid")));
                    }
                }
            }
            return Set.copyOf(playerIds);
        });
    }
}
