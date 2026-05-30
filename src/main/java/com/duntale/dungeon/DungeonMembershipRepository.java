package com.duntale.dungeon;

import com.duntale.db.DatabaseProvider;
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
import java.util.Optional;
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

    /**
     * Membership lifecycle state for a dungeon instance roster row.
     *
     * <p>{@link #ACTIVE} rows count toward the per-instance member cap and represent players
     * currently participating in a run. {@link #LEFT} rows preserve history while freeing the
     * player from the one-active-instance constraint; they may be reactivated while the instance
     * is still non-ended.
     */
    public enum MembershipState {
        /** Player is currently participating in the run. */
        ACTIVE,
        /** Player left/abandoned the run but the row is retained as recoverable history. */
        LEFT
    }

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS dungeon_membership (
                player_uuid TEXT NOT NULL,
                instance_id TEXT NOT NULL,
                state TEXT NOT NULL DEFAULT 'ACTIVE',
                PRIMARY KEY (player_uuid, instance_id),
                FOREIGN KEY (instance_id) REFERENCES dungeon_instances(instance_id)
            )
            """;

    private static final String CREATE_INSTANCE_INDEX_SQL = """
            CREATE INDEX IF NOT EXISTS idx_dungeon_membership_instance_id
                ON dungeon_membership(instance_id)
            """;

    private static final String INSERT_MEMBERSHIP_SQL = """
            INSERT INTO dungeon_membership (player_uuid, instance_id, state)
            VALUES (?, ?, 'ACTIVE')
            """;

    private static final String SELECT_INSTANCE_IDS_BY_PLAYER_SQL = """
            SELECT instance_id
              FROM dungeon_membership
             WHERE player_uuid = ?
             ORDER BY instance_id
            """;

    private static final String SELECT_ACTIVE_PLAYER_UUIDS_BY_INSTANCE_SQL = """
            SELECT player_uuid
              FROM dungeon_membership
             WHERE instance_id = ?
               AND state = 'ACTIVE'
             ORDER BY player_uuid
            """;

    private static final String FIND_ACTIVE_INSTANCE_IDS_BY_PLAYER_SQL = """
            SELECT m.instance_id
              FROM dungeon_membership m
              JOIN dungeon_instances i ON m.instance_id = i.instance_id
             WHERE m.player_uuid = ?
               AND m.state = 'ACTIVE'
               AND i.state != ?
             ORDER BY m.instance_id
            """;

    private static final String COUNT_ACTIVE_MEMBERS_BY_INSTANCE_SQL = """
            SELECT COUNT(*) AS active_count
              FROM dungeon_membership
             WHERE instance_id = ?
               AND state = 'ACTIVE'
            """;

    private static final String SELECT_MEMBERSHIP_STATE_SQL = """
            SELECT state
              FROM dungeon_membership
             WHERE player_uuid = ?
               AND instance_id = ?
            """;

    private static final String SET_MEMBERSHIP_STATE_SQL = """
            UPDATE dungeon_membership
               SET state = ?
             WHERE player_uuid = ?
               AND instance_id = ?
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
            migrateStateColumn(conn);
        });
        LOGGER.at(Level.INFO).log("dungeon_membership table initialized");
    }

    /**
     * Adds the {@code state} column to pre-existing {@code dungeon_membership} tables.
     *
     * <p>{@code CREATE TABLE IF NOT EXISTS} does not add new columns to an already-present table,
     * so existing databases require an explicit {@code ALTER TABLE}. Rows created before this
     * migration default to {@link MembershipState#ACTIVE}.
     *
     * @param conn the active JDBC connection from the enclosing transaction
     * @throws SQLException if the migration query fails
     */
    private void migrateStateColumn(@Nonnull Connection conn) throws SQLException {
        boolean hasStateColumn = false;
        try (PreparedStatement ps = conn.prepareStatement("PRAGMA table_info(dungeon_membership)");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                if ("state".equalsIgnoreCase(rs.getString("name"))) {
                    hasStateColumn = true;
                    break;
                }
            }
        }
        if (!hasStateColumn) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(
                        "ALTER TABLE dungeon_membership ADD COLUMN state TEXT NOT NULL DEFAULT 'ACTIVE'");
            }
            LOGGER.at(Level.INFO).log("Migrated dungeon_membership: added state column (default ACTIVE)");
        }
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
     * Loads the {@code ACTIVE} player UUIDs currently associated with the given dungeon instance.
     *
     * <p>{@link MembershipState#LEFT} rows are excluded so gameplay rosters only ever reflect
     * current participants.
     *
     * @param instanceId the instance identifier
     * @return immutable set of active player UUIDs in the instance
     * @throws SQLException if the query fails
     */
    @Nonnull
    public Set<UUID> findPlayerIdsByInstance(@Nonnull String instanceId) throws SQLException {
        Objects.requireNonNull(instanceId, "instanceId");
        return database.read(conn -> findPlayerIdsByInstanceInTransaction(conn, instanceId));
    }

    @Nonnull
    Set<UUID> findPlayerIdsByInstanceInTransaction(@Nonnull Connection conn, @Nonnull String instanceId)
            throws SQLException {
        Objects.requireNonNull(conn, "conn");
        Objects.requireNonNull(instanceId, "instanceId");

        Set<UUID> playerIds = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(SELECT_ACTIVE_PLAYER_UUIDS_BY_INSTANCE_SQL)) {
            ps.setString(1, instanceId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    playerIds.add(UUID.fromString(rs.getString("player_uuid")));
                }
            }
        }
        return Set.copyOf(playerIds);
    }

    /**
     * Returns the instance identifier for the player's {@code ACTIVE}, non-{@code ENDED} dungeon
     * instance, if one exists.
     *
     * <p>Only {@link MembershipState#ACTIVE} rows are considered, so abandoned ({@code LEFT})
     * history in a still-running instance is ignored. A player may only be active in one
     * non-ended instance at a time; multiple active rows indicate corrupt state and raise a
     * {@link CorruptMembershipException} rather than silently choosing one.
     *
     * @param playerId the player UUID
     * @return the active non-ended instance identifier, or {@link Optional#empty()} if none
     * @throws SQLException if the query fails
     * @throws CorruptMembershipException if the player has more than one active non-ended instance
     */
    @Nonnull
    public Optional<String> findNonEndedInstanceIdByPlayer(@Nonnull UUID playerId) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        return database.read(conn -> findActiveNonEndedInstanceIdInTransaction(conn, playerId));
    }

    @Nonnull
    Optional<String> findActiveNonEndedInstanceIdInTransaction(
            @Nonnull Connection conn, @Nonnull UUID playerId) throws SQLException {
        Objects.requireNonNull(conn, "conn");
        Objects.requireNonNull(playerId, "playerId");
        List<String> instanceIds = new java.util.ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(FIND_ACTIVE_INSTANCE_IDS_BY_PLAYER_SQL)) {
            ps.setString(1, playerId.toString());
            ps.setString(2, DungeonInstanceState.ENDED.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    instanceIds.add(rs.getString("instance_id"));
                }
            }
        }
        if (instanceIds.size() > 1) {
            LOGGER.at(Level.SEVERE).log(
                    "Corrupt membership: player " + playerId + " has " + instanceIds.size()
                            + " active non-ended instances: " + instanceIds);
            throw new CorruptMembershipException(playerId, Set.copyOf(instanceIds));
        }
        return instanceIds.isEmpty() ? Optional.empty() : Optional.of(instanceIds.get(0));
    }

    /**
     * Counts the {@code ACTIVE} members of the given instance within the enclosing transaction.
     *
     * @param conn       the active JDBC connection
     * @param instanceId the instance identifier
     * @return the number of active members
     * @throws SQLException if the query fails
     */
    int countActiveMembersInTransaction(@Nonnull Connection conn, @Nonnull String instanceId)
            throws SQLException {
        Objects.requireNonNull(conn, "conn");
        Objects.requireNonNull(instanceId, "instanceId");
        try (PreparedStatement ps = conn.prepareStatement(COUNT_ACTIVE_MEMBERS_BY_INSTANCE_SQL)) {
            ps.setString(1, instanceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("active_count") : 0;
            }
        }
    }

    /**
     * Returns the membership state of a player in a specific instance, if a row exists.
     *
     * @param conn       the active JDBC connection
     * @param instanceId the instance identifier
     * @param playerId   the player UUID
     * @return the membership state, or {@link Optional#empty()} if the player has no row there
     * @throws SQLException if the query fails
     */
    @Nonnull
    Optional<MembershipState> findMembershipStateInTransaction(
            @Nonnull Connection conn, @Nonnull String instanceId, @Nonnull UUID playerId)
            throws SQLException {
        Objects.requireNonNull(conn, "conn");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(playerId, "playerId");
        try (PreparedStatement ps = conn.prepareStatement(SELECT_MEMBERSHIP_STATE_SQL)) {
            ps.setString(1, playerId.toString());
            ps.setString(2, instanceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(MembershipState.valueOf(rs.getString("state")));
            }
        }
    }

    /**
     * Marks a player's {@code ACTIVE} membership in the given instance as {@link MembershipState#LEFT}.
     *
     * @param conn       the active JDBC connection
     * @param instanceId the instance identifier
     * @param playerId   the player UUID
     * @return {@code true} if a row was updated
     * @throws SQLException if the update fails
     */
    boolean markLeftInTransaction(
            @Nonnull Connection conn, @Nonnull String instanceId, @Nonnull UUID playerId)
            throws SQLException {
        return setStateInTransaction(conn, instanceId, playerId, MembershipState.LEFT);
    }

    /**
     * Reactivates a {@link MembershipState#LEFT} membership row back to {@link MembershipState#ACTIVE}.
     *
     * @param conn       the active JDBC connection
     * @param instanceId the instance identifier
     * @param playerId   the player UUID
     * @return {@code true} if a row was updated
     * @throws SQLException if the update fails
     */
    boolean reactivateInTransaction(
            @Nonnull Connection conn, @Nonnull String instanceId, @Nonnull UUID playerId)
            throws SQLException {
        return setStateInTransaction(conn, instanceId, playerId, MembershipState.ACTIVE);
    }

    private boolean setStateInTransaction(
            @Nonnull Connection conn,
            @Nonnull String instanceId,
            @Nonnull UUID playerId,
            @Nonnull MembershipState state) throws SQLException {
        Objects.requireNonNull(conn, "conn");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(state, "state");
        try (PreparedStatement ps = conn.prepareStatement(SET_MEMBERSHIP_STATE_SQL)) {
            ps.setString(1, state.name());
            ps.setString(2, playerId.toString());
            ps.setString(3, instanceId);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Returns whether the given player belongs to any dungeon instance that is not in the
     * {@link DungeonInstanceState#ENDED} state.
     *
     * @param playerId the player UUID
     * @return {@code true} if the player has a non-ended active instance membership
     * @throws SQLException if the query fails
     */
    public boolean hasNonEndedInstance(@Nonnull UUID playerId) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        return database.read(conn -> hasNonEndedInstanceInTransaction(conn, playerId));
    }

    /**
     * Transaction-safe variant of {@link #hasNonEndedInstance(UUID)} considering only
     * {@link MembershipState#ACTIVE} rows.
     *
     * @param conn     the active JDBC connection from the enclosing transaction
     * @param playerId the player UUID
     * @return {@code true} if the player has a non-ended active instance membership
     * @throws SQLException if the query fails
     */
    boolean hasNonEndedInstanceInTransaction(@Nonnull Connection conn, @Nonnull UUID playerId)
            throws SQLException {
        Objects.requireNonNull(conn, "conn");
        Objects.requireNonNull(playerId, "playerId");
        return findActiveNonEndedInstanceIdInTransaction(conn, playerId).isPresent();
    }

    /**
     * Finds all players from the given collection that already belong to a non-{@code ENDED}
     * dungeon instance.
     *
     * <p>Intended for transactional roster validation: the caller wraps this check together
     * with instance creation and membership inserts in a single {@code database.transaction()}
     * to guarantee the one-active-instance invariant cannot be bypassed by concurrent starts.
     *
     * @param conn      the active JDBC connection from the enclosing transaction
     * @param playerIds the player UUIDs to check
     * @return immutable set of player UUIDs that have a non-ended instance (empty if none)
     * @throws SQLException if any query fails
     */
    @Nonnull
    Set<UUID> findPlayersWithNonEndedInstanceInTransaction(
            @Nonnull Connection conn,
            @Nonnull Collection<UUID> playerIds
    ) throws SQLException {
        Objects.requireNonNull(conn, "conn");
        Objects.requireNonNull(playerIds, "playerIds");
        Set<UUID> blocked = new HashSet<>();
        for (UUID playerId : playerIds) {
            Objects.requireNonNull(playerId, "playerIds contains null");
            if (hasNonEndedInstanceInTransaction(conn, playerId)) {
                blocked.add(playerId);
            }
        }
        return Set.copyOf(blocked);
    }

    /**
     * Inserts a single {@link MembershipState#ACTIVE} membership row for the given instance.
     *
     * <p>Intended for current-party-owner Continue when adding a free party member who has no
     * existing row in the target instance. Callers must ensure no row already exists for the
     * player in that instance (reactivate {@code LEFT} rows via {@link #reactivateInTransaction}).
     *
     * @param conn       the active JDBC connection from the enclosing transaction
     * @param instanceId the instance identifier
     * @param playerId   the player UUID
     * @throws SQLException if the insert fails
     */
    void addActiveMembershipInTransaction(
            @Nonnull Connection conn, @Nonnull String instanceId, @Nonnull UUID playerId)
            throws SQLException {
        addMembershipsInTransaction(conn, instanceId, List.of(playerId));
    }

    /**
     * Thrown when a player is found to have more than one {@link MembershipState#ACTIVE},
     * non-{@code ENDED} dungeon instance, which violates the one-active-instance invariant.
     */
    public static class CorruptMembershipException extends RuntimeException {

        private final transient UUID playerId;
        private final transient Set<String> instanceIds;

        /**
         * Creates a corrupt-membership exception.
         *
         * @param playerId    the offending player UUID
         * @param instanceIds the conflicting active non-ended instance identifiers
         */
        public CorruptMembershipException(@Nonnull UUID playerId, @Nonnull Set<String> instanceIds) {
            super("Player " + playerId + " has multiple active non-ended instances: " + instanceIds);
            this.playerId = playerId;
            this.instanceIds = Set.copyOf(instanceIds);
        }

        /**
         * Returns the offending player UUID.
         *
         * @return the player UUID
         */
        @Nonnull
        public UUID getPlayerId() {
            return playerId;
        }

        /**
         * Returns the conflicting active non-ended instance identifiers.
         *
         * @return the instance identifiers
         */
        @Nonnull
        public Set<String> getInstanceIds() {
            return instanceIds;
        }
    }
}
