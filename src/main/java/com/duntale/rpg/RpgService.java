package com.duntale.rpg;

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

    /** Number of stat points granted per level gained. */
    public static final int POINTS_PER_LEVEL = 3;

    private final RpgRepository repository;
    private final Map<UUID, RpgProfile> cache = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> unassignedPointsCache = new ConcurrentHashMap<>();

    /** Optional listener notified after stat value changes. */
    private StatChangeListener statChangeListener;

    /**
     * Creates a new RPG service backed by the given repository.
     *
     * @param repository the RPG repository for persistence
     */
    public RpgService(@Nonnull RpgRepository repository) {
        this.repository = repository;
    }

    /**
     * Sets the listener notified after any stat value changes.
     *
     * @param listener the listener, or {@code null} to remove
     */
    public void setStatChangeListener(@javax.annotation.Nullable StatChangeListener listener) {
        this.statChangeListener = listener;
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
        RpgConfigValues config = RpgConfig.values();
        int clamped = Math.clamp(value, config.minStat(), config.maxStat());
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

        StatChangeListener listener = this.statChangeListener;
        if (listener != null) {
            listener.onStatChanged(playerId, stat, clamped);
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

    // ── Stat Points ──────────────────────────────────────────────────

    /**
     * Returns the number of unassigned stat points for the given player.
     *
     * @param playerId the player's UUID
     * @return the unassigned points count (lazy-loaded from DB)
     */
    public int getUnassignedPoints(@Nonnull UUID playerId) {
        return unassignedPointsCache.computeIfAbsent(playerId, id -> {
            try {
                return repository.loadUnassignedPoints(id);
            } catch (SQLException e) {
                LOGGER.at(Level.SEVERE).log("Failed to load unassigned points for %s: %s", id, e.getMessage());
                return 0;
            }
        });
    }

    /**
     * Grants stat points to the player (e.g. on level-up).
     *
     * @param playerId the player's UUID
     * @param points   the number of points to grant (must be positive)
     */
    public void grantStatPoints(@Nonnull UUID playerId, int points) {
        if (points <= 0) return;

        int current = getUnassignedPoints(playerId);
        int updated = current + points;
        unassignedPointsCache.put(playerId, updated);

        try {
            repository.saveUnassignedPoints(playerId, updated);
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Failed to save unassigned points for %s: %s", playerId, e.getMessage());
        }
    }

    /**
     * Assigns one stat point from the unassigned pool to the given stat.
     *
     * @param playerId the player's UUID
     * @param stat     the stat to increment
     * @return {@code true} if the point was assigned, {@code false} if no points available
     *         or the stat is already at max
     */
    public boolean assignPoint(@Nonnull UUID playerId, @Nonnull RpgStat stat) {
        int currentStat = getStat(playerId, stat);
        if (currentStat >= RpgConfig.values().maxStat()) {
            return false;
        }

        // Atomically decrement unassigned points; abort if none available
        boolean[] succeeded = { false };
        unassignedPointsCache.compute(playerId, (key, current) -> {
            int available = (current != null) ? current : 0;
            if (available <= 0) {
                return current;
            }
            succeeded[0] = true;
            return available - 1;
        });

        if (!succeeded[0]) {
            return false;
        }

        int newUnassigned = getUnassignedPoints(playerId);
        setStat(playerId, stat, currentStat + 1);

        try {
            repository.saveUnassignedPoints(playerId, newUnassigned);
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Failed to save unassigned points for %s: %s", playerId, e.getMessage());
        }
        return true;
    }

    // ── Player Lifecycle ─────────────────────────────────────────────

    /**
     * Pre-loads the player's RPG profile into the cache on join.
     *
     * <p>Entity stat modifiers (Vitality, Stamina) are applied separately by
     * {@link RpgStatApplicator} once the player entity is ready in a world.
     *
     * @param playerId the player's UUID
     */
    public void onPlayerJoin(@Nonnull UUID playerId) {
        RpgProfile profile = getProfile(playerId);
        int unassigned = getUnassignedPoints(playerId);
        LOGGER.at(Level.INFO).log("Pre-loaded RPG profile for %s — stats: %s, unassigned: %d",
                playerId, profile.getAll(), unassigned);
    }

    /**
     * Evicts the player's RPG profile and unassigned points from the cache on leave.
     *
     * @param playerId the player's UUID
     */
    public void onPlayerLeave(@Nonnull UUID playerId) {
        cache.remove(playerId);
        unassignedPointsCache.remove(playerId);
        LOGGER.at(Level.INFO).log("Evicted RPG profile cache for %s", playerId);
    }

    // ── Listener ─────────────────────────────────────────────────────

    /**
     * Listener interface for RPG stat changes.
     */
    @FunctionalInterface
    public interface StatChangeListener {

        /**
         * Called after a player's stat value has changed.
         *
         * @param playerId the player's UUID
         * @param stat     the stat that changed
         * @param newValue the new stat value
         */
        void onStatChanged(@Nonnull UUID playerId, @Nonnull RpgStat stat, int newValue);
    }
}
