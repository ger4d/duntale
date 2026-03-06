package com.duntale.zsquad.progression;

import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing player XP and levels.
 *
 * <p>Handles XP granting, level calculation, and notifies listeners when players
 * level up. It does NOT know about rewards — those are handled by separate services
 * listening to level-up events.
 *
 * <h2>Usage:</h2>
 * <pre>{@code
 * ProgressionService progressionService = new ProgressionService(repository);
 *
 * // Set up level-up listener (e.g., for stat point rewards)
 * progressionService.setLevelUpListener((playerId, newLevel) -> {
 *     rpgService.addStat(playerId, RpgStat.LUCK, 1);
 * });
 *
 * // Grant XP (e.g., on NPC kill)
 * LevelUpResult result = progressionService.grantXP(playerId, 50);
 * if (result.leveledUp()) {
 *     // Send level-up message to player
 * }
 * }</pre>
 *
 * <p>Adapted from {@code com.duntale.hub.core.progression.ProgressionService}.
 *
 * @since 1.0.0
 */
public class ProgressionService {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final ProgressionRepository repository;

    /** Per-player locks to prevent race conditions during XP grants. */
    private final ConcurrentHashMap<UUID, Object> playerLocks = new ConcurrentHashMap<>();

    /** Callback for level-up notifications. */
    private LevelUpListener levelUpListener;

    /** Callback for every XP grant (regardless of level-up). */
    private XPGrantListener xpGrantListener;

    /**
     * Creates a new progression service.
     *
     * @param repository the progression repository for database access
     */
    public ProgressionService(@Nonnull ProgressionRepository repository) {
        this.repository = repository;
    }

    // ── XP & Level Management ────────────────────────────────────────

    /**
     * Grants XP to a player and checks for level-up.
     *
     * <p>If the player levels up, the level-up listener is notified
     * for each level gained (in case of multiple level-ups from a single grant).
     *
     * <p>This method is thread-safe per player — concurrent XP grants to the
     * same player are serialised via per-player locks.
     *
     * @param playerId the player's UUID
     * @param amount   the amount of XP to grant (must be positive)
     * @return the result containing old/new level and whether leveled up
     */
    @Nonnull
    public LevelUpResult grantXP(@Nonnull UUID playerId, long amount) {
        if (amount <= 0) {
            int currentLevel = repository.getLevel(playerId);
            long currentXP = repository.getXP(playerId);
            return new LevelUpResult(0, currentXP, currentLevel, currentLevel, false);
        }

        Object lock = playerLocks.computeIfAbsent(playerId, k -> new Object());

        LevelUpResult result;
        synchronized (lock) {
            long oldXP = repository.getXP(playerId);
            int oldLevel = repository.getLevel(playerId);

            long newXP = oldXP + amount;
            int newLevel = repository.calculateLevel(newXP);

            repository.saveProgress(playerId, newLevel, newXP);

            result = new LevelUpResult(amount, newXP, oldLevel, newLevel, newLevel > oldLevel);
        }

        // Notify listener outside the lock to prevent potential deadlocks
        if (result.leveledUp() && levelUpListener != null) {
            for (int level = result.oldLevel() + 1; level <= result.newLevel(); level++) {
                LOGGER.atInfo().log("Player %s leveled up to %d", playerId, level);
                levelUpListener.onLevelUp(playerId, level);
            }
        }

        // Notify XP grant listener (fires on every grant, not just level-ups)
        XPGrantListener xpListener = this.xpGrantListener;
        if (xpListener != null) {
            xpListener.onXPGranted(playerId, amount, result);
        }

        return result;
    }

    // ── Queries ──────────────────────────────────────────────────────

    /**
     * Gets a player's current level.
     *
     * @param playerId the player's UUID
     * @return the player's level (minimum 1)
     */
    public int getLevel(@Nonnull UUID playerId) {
        return repository.getLevel(playerId);
    }

    /**
     * Gets a player's total XP.
     *
     * @param playerId the player's UUID
     * @return the player's total XP
     */
    public long getXP(@Nonnull UUID playerId) {
        return repository.getXP(playerId);
    }

    /**
     * Gets a player's current season.
     *
     * @param playerId the player's UUID
     * @return the player's season number
     */
    public int getSeason(@Nonnull UUID playerId) {
        return repository.getSeason(playerId);
    }

    /**
     * Gets XP needed for the next level.
     *
     * @param playerId the player's UUID
     * @return XP remaining to next level, or 0 if at max level
     */
    public long getXPForNextLevel(@Nonnull UUID playerId) {
        int level = getLevel(playerId);
        long currentXP = getXP(playerId);

        int nextLevel = level + 1;
        if (nextLevel > repository.getMaxLevel()) {
            return 0;
        }

        long nextLevelXP = repository.getXPForLevel(nextLevel);
        return Math.max(0, nextLevelXP - currentXP);
    }

    /**
     * Gets progress to next level as a fraction (0.0–1.0).
     *
     * @param playerId the player's UUID
     * @return progress fraction (1.0 if at max level)
     */
    public double getLevelProgress(@Nonnull UUID playerId) {
        int level = getLevel(playerId);
        long currentXP = getXP(playerId);

        long currentLevelXP = repository.getXPForLevel(level);
        long nextLevelXP = repository.getXPForLevel(level + 1);

        if (level >= repository.getMaxLevel()) {
            return 1.0;
        }

        long xpIntoLevel = currentXP - currentLevelXP;
        long xpForLevel = nextLevelXP - currentLevelXP;

        return xpForLevel > 0 ? (double) xpIntoLevel / xpForLevel : 0.0;
    }

    /**
     * Gets the maximum defined level.
     *
     * @return the highest achievable level
     */
    public int getMaxLevel() {
        return repository.getMaxLevel();
    }

    /**
     * Gets XP required to reach a specific level.
     *
     * @param level the target level
     * @return the total XP required
     */
    public long getXPForLevel(int level) {
        return repository.getXPForLevel(level);
    }

    // ── Listener ─────────────────────────────────────────────────────

    /**
     * Sets the listener for level-up events.
     *
     * <p>This is typically set to trigger stat-point rewards when players level up.
     *
     * @param listener the listener to notify on level-up
     */
    public void setLevelUpListener(@Nonnull LevelUpListener listener) {
        this.levelUpListener = listener;
    }

    /**
     * Sets the listener for every XP grant event (regardless of level-up).
     *
     * @param listener the listener to notify on XP grant
     */
    public void setXPGrantListener(@Nonnull XPGrantListener listener) {
        this.xpGrantListener = listener;
    }

    /**
     * Listener interface for level-up events.
     */
    @FunctionalInterface
    public interface LevelUpListener {

        /**
         * Called when a player levels up.
         *
         * <p>This is called once per level gained. If a player gains
         * multiple levels from a single XP grant, this is called for each.
         *
         * @param playerId the player's UUID
         * @param newLevel the new level achieved
         */
        void onLevelUp(@Nonnull UUID playerId, int newLevel);
    }

    /**
     * Listener interface for XP grant events.
     */
    @FunctionalInterface
    public interface XPGrantListener {

        /**
         * Called after XP is granted to a player.
         *
         * @param playerId the player's UUID
         * @param amount   the XP amount granted
         * @param result   the level-up result with old/new levels
         */
        void onXPGranted(@Nonnull UUID playerId, long amount, @Nonnull LevelUpResult result);
    }

    /**
     * Reloads level thresholds from the database.
     *
     * <p>Call this after modifying level thresholds directly in the database.
     */
    public void reload() {
        repository.reloadLevelThresholds();
    }

    /**
     * Ensures the player has a progression row in the database.
     *
     * <p>Call on player connect to guarantee the row exists before
     * any XP grant or level query.
     *
     * @param playerId the player's UUID
     */
    public void onPlayerJoin(@Nonnull UUID playerId) {
        repository.ensurePlayerExists(playerId);
    }

    /**
     * Cleans up per-player locks for a disconnecting player.
     *
     * @param playerId the player's UUID
     */
    public void onPlayerLeave(@Nonnull UUID playerId) {
        playerLocks.remove(playerId);
    }
}
