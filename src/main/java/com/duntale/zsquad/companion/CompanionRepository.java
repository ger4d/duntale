package com.duntale.zsquad.companion;

import com.duntale.zsquad.db.DatabaseProvider;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.logging.Level;

/**
 * SQL repository for player companion role preferences.
 *
 * <p>Backs the {@code companion_preferences} table keyed by player UUID.
 * All operations use {@link PreparedStatement} with try-with-resources.
 *
 * @since 1.5.0
 */
public class CompanionRepository {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final String CREATE_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS companion_preferences ("
                    + "uuid      TEXT PRIMARY KEY, "
                    + "role_name TEXT NOT NULL"
                    + ")";

    private static final String SELECT_PREFERENCE_SQL =
            "SELECT role_name FROM companion_preferences WHERE uuid = ?";

    private static final String UPSERT_PREFERENCE_SQL =
            "INSERT INTO companion_preferences (uuid, role_name) VALUES (?, ?) "
                    + "ON CONFLICT(uuid) DO UPDATE SET role_name = excluded.role_name";

    private static final String DELETE_PREFERENCE_SQL =
            "DELETE FROM companion_preferences WHERE uuid = ?";

    private final DatabaseProvider database;

    /**
     * Creates a new companion repository backed by the given database provider.
     *
     * @param database the database provider
     */
    public CompanionRepository(@Nonnull DatabaseProvider database) {
        this.database = database;
    }

    /**
     * Creates the {@code companion_preferences} table if it does not already exist.
     *
     * @throws SQLException if table creation fails
     */
    public void initialize() throws SQLException {
        database.write(conn -> {
            try (var stmt = conn.createStatement()) {
                stmt.execute(CREATE_TABLE_SQL);
            }
        });
        LOGGER.at(Level.INFO).log("companion_preferences table initialized");
    }

    /**
     * Returns the stored companion role preference for the given player.
     *
     * @param playerId the player's UUID
     * @return the role name, or {@code null} if no preference exists
     * @throws SQLException if the query fails
     */
    @Nullable
    public String getPreference(@Nonnull UUID playerId) throws SQLException {
        return database.read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(SELECT_PREFERENCE_SQL)) {
                ps.setString(1, playerId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString("role_name") : null;
                }
            }
        });
    }

    /**
     * Stores or updates the companion role preference for the given player.
     *
     * @param playerId the player's UUID
     * @param roleName the NPC role name
     * @throws SQLException if the upsert fails
     */
    public void setPreference(@Nonnull UUID playerId, @Nonnull String roleName) throws SQLException {
        database.write(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(UPSERT_PREFERENCE_SQL)) {
                ps.setString(1, playerId.toString());
                ps.setString(2, roleName);
                ps.executeUpdate();
            }
        });
    }

    /**
     * Deletes the companion role preference for the given player.
     *
     * @param playerId the player's UUID
     * @throws SQLException if the delete fails
     */
    public void deletePreference(@Nonnull UUID playerId) throws SQLException {
        database.write(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(DELETE_PREFERENCE_SQL)) {
                ps.setString(1, playerId.toString());
                ps.executeUpdate();
            }
        });
    }
}
