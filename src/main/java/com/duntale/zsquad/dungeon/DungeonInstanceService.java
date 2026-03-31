package com.duntale.zsquad.dungeon;

import com.duntale.zsquad.db.DatabaseProvider;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Orchestrates dungeon instance lifecycle: creation, floor transitions, end, and runtime lookups.
 *
 * <p>This service owns the one-active-instance constraint: a player may only belong to one
 * non-{@code ENDED} instance at a time. Roster validation is performed transactionally
 * to prevent concurrent start attempts from bypassing the rule.
 *
 * @since 1.6.0
 */
public class DungeonInstanceService {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    // ============================================
    // Fields
    // ============================================

    private final DatabaseProvider database;
    private final DungeonInstanceRepository instanceRepository;
    private final DungeonMembershipRepository membershipRepository;

    // ============================================
    // Constructor
    // ============================================

    /**
     * Creates a new dungeon instance service.
     *
     * @param database             the database provider for transactional operations
     * @param instanceRepository   the dungeon instance repository
     * @param membershipRepository the dungeon membership repository
     */
    public DungeonInstanceService(
            @Nonnull DatabaseProvider database,
            @Nonnull DungeonInstanceRepository instanceRepository,
            @Nonnull DungeonMembershipRepository membershipRepository
    ) {
        this.database = Objects.requireNonNull(database, "database");
        this.instanceRepository = Objects.requireNonNull(instanceRepository, "instanceRepository");
        this.membershipRepository = Objects.requireNonNull(membershipRepository, "membershipRepository");
    }

    // ============================================
    // Public API
    // ============================================

    /**
     * Validates that no player in the given roster already belongs to a non-{@code ENDED}
     * dungeon instance, then atomically persists a new instance row and membership rows.
     *
     * <p>The entire check-and-persist sequence runs inside a single database transaction
     * so that concurrent start attempts for the same player are serialized and the second
     * attempt sees the first's membership rows.
     *
     * @param instance  the dungeon instance metadata to persist
     * @param playerIds the roster of player UUIDs to validate and register
     * @throws RosterValidationException if any player already belongs to a non-ended instance
     * @throws SQLException              if a database access error occurs (transaction is rolled back)
     */
    public void createInstance(@Nonnull DungeonInstance instance, @Nonnull Collection<UUID> playerIds)
            throws SQLException {
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(playerIds, "playerIds");
        if (playerIds.isEmpty()) {
            throw new IllegalArgumentException("playerIds must not be empty");
        }

        database.transaction(conn -> {
            validateRosterInTransaction(conn, playerIds);
            instanceRepository.createInTransaction(conn, instance);
            membershipRepository.addMembershipsInTransaction(conn, instance.instanceId(), playerIds);
            LOGGER.at(Level.INFO).log("Instance %s created with %d roster members",
                    instance.instanceId(), playerIds.size());
        });
    }

    // ============================================
    // Internal
    // ============================================

    /**
     * Validates that no player in the given roster already belongs to a non-{@code ENDED} instance.
     *
     * <p>Must be called within the same database transaction as instance creation and
     * membership insertion to prevent concurrent starts from bypassing the constraint.
     *
     * @param conn      the active JDBC connection from the enclosing transaction
     * @param playerIds the player UUIDs to validate
     * @throws RosterValidationException if any player already belongs to a non-ended instance
     * @throws SQLException              if a database access error occurs
     */
    void validateRosterInTransaction(@Nonnull Connection conn, @Nonnull Collection<UUID> playerIds)
            throws SQLException {
        Objects.requireNonNull(conn, "conn");
        Objects.requireNonNull(playerIds, "playerIds");
        Set<UUID> blocked = membershipRepository.findPlayersWithNonEndedInstanceInTransaction(conn, playerIds);
        if (!blocked.isEmpty()) {
            LOGGER.at(Level.WARNING).log(
                    "Roster validation failed — players already in active instance: %s", blocked);
            throw new RosterValidationException(blocked);
        }
    }

    // ============================================
    // Exception Types
    // ============================================

    /**
     * Thrown when roster validation fails because one or more players already belong
     * to a non-ended dungeon instance.
     *
     * @since 1.6.0
     */
    public static class RosterValidationException extends RuntimeException {

        private final Set<UUID> blockedPlayers;

        /**
         * Creates a new roster validation exception.
         *
         * @param blockedPlayers the players that failed validation
         */
        public RosterValidationException(@Nonnull Set<UUID> blockedPlayers) {
            super("Players already in active instance: " + blockedPlayers);
            this.blockedPlayers = Set.copyOf(blockedPlayers);
        }

        /**
         * Returns the player UUIDs that already belong to a non-ended instance.
         *
         * @return immutable set of blocked player UUIDs
         */
        @Nonnull
        public Set<UUID> getBlockedPlayers() {
            return blockedPlayers;
        }
    }
}
