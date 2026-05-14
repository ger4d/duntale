package com.duntale.progression;

/**
 * Result of granting XP to a player.
 *
 * <p>Contains information about whether the player leveled up and
 * how many levels were gained.
 *
 * <h2>Usage:</h2>
 * <pre>{@code
 * LevelUpResult result = progressionService.grantXP(playerId, 100);
 * if (result.leveledUp()) {
 *     // Send level-up message to player
 * }
 * }</pre>
 *
 * @param xpGranted the amount of XP that was granted
 * @param totalXP   the player's total XP after granting
 * @param oldLevel  the player's level before granting XP
 * @param newLevel  the player's level after granting XP
 * @param leveledUp true if the player gained at least one level
 * @since 1.0.0
 */
public record LevelUpResult(
        long xpGranted,
        long totalXP,
        int oldLevel,
        int newLevel,
        boolean leveledUp
) {

    /**
     * Get the number of levels gained.
     *
     * @return the number of levels gained (0 if no level up)
     */
    public int levelsGained() {
        return newLevel - oldLevel;
    }
}
