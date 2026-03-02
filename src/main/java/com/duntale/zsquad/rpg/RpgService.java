package com.duntale.zsquad.rpg;

import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Public API for RPG stat operations.
 *
 * <p>Maintains an in-memory cache of player profiles backed by {@link RpgRepository}.
 * SQL exceptions are caught internally and never propagate out of public API methods.
 */
public class RpgService {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final RpgRepository repository;
    private final Map<UUID, RpgProfile> cache = new ConcurrentHashMap<>();

    /**
     * Creates a new RPG service backed by the given repository.
     *
     * @param repository the RPG repository for persistence
     */
    public RpgService(@Nonnull RpgRepository repository) {
        this.repository = repository;
    }

    /**
     * Returns the RPG profile for the given player, lazy-loading from the database
     * on first access.
     *
     * @param playerId the player's UUID
     * @return the player's RPG profile, or a new empty profile if loading fails
     */
    @Nonnull
    public RpgProfile getProfile(@Nonnull UUID playerId) {
        return cache.computeIfAbsent(playerId, id -> {
            try {
                return repository.loadProfile(id);
            } catch (SQLException e) {
                LOGGER.at(Level.SEVERE).log("Failed to load RPG profile for %s: %s", id, e.getMessage());
                return new RpgProfile();
            }
        });
    }

    /**
     * Returns the value of a single stat for the given player.
     *
     * @param playerId the player's UUID
     * @param stat     the stat to query
     * @return the stat value, or {@code 0} if the profile cannot be loaded
     */
    public int getStat(@Nonnull UUID playerId, @Nonnull RpgStat stat) {
        return getProfile(playerId).getStat(stat);
    }

    /**
     * Sets a single stat for the given player, clamping the value to
     * [{@link RpgConstants#MIN_STAT}, {@link RpgConstants#MAX_STAT}].
     *
     * <p>Updates both the in-memory cache and the database.
     *
     * @param playerId the player's UUID
     * @param stat     the stat to set
     * @param value    the desired value (will be clamped)
     */
    public void setStat(@Nonnull UUID playerId, @Nonnull RpgStat stat, int value) {
        int clamped = Math.clamp(value, RpgConstants.MIN_STAT, RpgConstants.MAX_STAT);
        if (clamped != value) {
            LOGGER.at(Level.WARNING).log("Stat %s for %s clamped from %d to %d",
                    stat, playerId, value, clamped);
        }

        RpgProfile profile = getProfile(playerId);
        profile.setStat(stat, clamped);

        try {
            repository.saveStat(playerId, stat, clamped);
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Failed to save stat %s for %s: %s",
                    stat, playerId, e.getMessage());
        }
    }

    /**
     * Adds a delta to a single stat for the given player.
     *
     * <p>The resulting value is clamped to [{@link RpgConstants#MIN_STAT}, {@link RpgConstants#MAX_STAT}].
     *
     * @param playerId the player's UUID
     * @param stat     the stat to modify
     * @param delta    the amount to add (may be negative)
     */
    public void addStat(@Nonnull UUID playerId, @Nonnull RpgStat stat, int delta) {
        setStat(playerId, stat, getStat(playerId, stat) + delta);
    }

    /**
     * Pre-loads the player's RPG profile into the cache on join.
     *
     * <p>Entity stat modifiers (Vitality, Stamina) will be applied in the plugin
     * integration round once the player entity reference is available.
     *
     * @param playerId the player's UUID
     */
    public void onPlayerJoin(@Nonnull UUID playerId) {
        RpgProfile profile = getProfile(playerId);
        LOGGER.at(Level.INFO).log("Pre-loaded RPG profile for %s — stats: %s", playerId, profile.getAll());
    }

    /**
     * Evicts the player's RPG profile from the cache on leave.
     *
     * @param playerId the player's UUID
     */
    public void onPlayerLeave(@Nonnull UUID playerId) {
        cache.remove(playerId);
        LOGGER.at(Level.INFO).log("Evicted RPG profile cache for %s", playerId);
    }
}
