package com.duntale.rpg;

import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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

    /** Supplies the equipped-gear attribute bonus per stat. Defaults to zero (no gear layer). */
    private GearBonusProvider gearBonusProvider = (playerId, stat) -> 0;

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
    public void setStatChangeListener(@Nullable StatChangeListener listener) {
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
     * <p>This is the persisted base stat (assigned points). Gameplay sites that should reflect
     * equipped-gear attribute bonuses read {@link #getEffectiveStat(UUID, RpgStat)} instead.
     *
     * @param playerId the player's UUID
     * @param stat     the stat to query
     * @return the stat value, or {@code 0} if the profile cannot be loaded
     */
    public int getStat(@Nonnull UUID playerId, @Nonnull RpgStat stat) {
        return getProfile(playerId).getStat(stat);
    }

    /**
     * Returns the effective value of a single stat: the persisted base plus the equipped-gear
     * attribute bonus, clamped to the configured stat bounds.
     *
     * <p>The persisted {@link RpgProfile} is never mutated by gear — the bonus is summed live from
     * {@link #setGearBonusProvider(GearBonusProvider) the gear bonus provider} on every read, so
     * unequipping or relogging cleanly drops the effect back to the base.
     *
     * @param playerId the player's UUID
     * @param stat     the stat to query
     * @return the effective (base + gear) stat value, clamped to {@code [minStat, maxStat]}
     */
    public int getEffectiveStat(@Nonnull UUID playerId, @Nonnull RpgStat stat) {
        int base = getStat(playerId, stat);
        int bonus = gearBonusProvider.bonus(playerId, stat);
        RpgConfigValues config = RpgConfig.values();
        return Math.clamp((long) base + bonus, config.minStat(), config.maxStat());
    }

    /**
     * Sets the provider that supplies the equipped-gear attribute bonus per stat. Wired once at
     * plugin startup to {@code GearAttributeService::getBonus}.
     *
     * @param provider the gear bonus provider, or {@code null} to reset to a zero bonus
     */
    public void setGearBonusProvider(@Nullable GearBonusProvider provider) {
        this.gearBonusProvider = provider != null ? provider : (playerId, stat) -> 0;
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
     * Supplies the equipped-gear attribute bonus for a player's stat.
     *
     * <p>Decouples {@link RpgService} from {@code GearAttributeService} to avoid a construction-time
     * cycle (RpgService &rarr; GearAttributeService &rarr; RpgService): the service is wired in after
     * both exist via {@link #setGearBonusProvider(GearBonusProvider)}.
     */
    @FunctionalInterface
    public interface GearBonusProvider {

        /**
         * Returns the equipped-gear bonus for a stat (zero when no gear contributes).
         *
         * @param playerId the player's UUID
         * @param stat     the stat
         * @return the additive gear bonus
         */
        int bonus(@Nonnull UUID playerId, @Nonnull RpgStat stat);
    }

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
