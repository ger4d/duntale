package com.duntale.items;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks transient, per-player move-speed bonuses granted by the Speed Boots items.
 *
 * <p>A bonus is a flat additive value (in click-to-move base-speed units) with an
 * expiry timestamp. The {@link ClickToMoveManager} queries {@link #getBonus(UUID)}
 * in its movement hot path, so this class holds no ECS references and is safe to
 * read and write from any thread (backed by a {@link ConcurrentHashMap}).
 *
 * @see SpeedBoostInteraction
 */
public class SpeedBoostManager {

    private final Map<UUID, Boost> boosts = new ConcurrentHashMap<>();

    /**
     * Applies (or overwrites) a speed bonus for the given player.
     *
     * @param playerId        the player's UUID
     * @param bonus           the flat move-speed bonus to add
     * @param durationSeconds how long the bonus lasts, in seconds
     */
    public void apply(@Nonnull UUID playerId, double bonus, double durationSeconds) {
        long expiryNanos = System.nanoTime() + (long) (durationSeconds * 1_000_000_000L);
        boosts.put(playerId, new Boost(bonus, expiryNanos));
    }

    /**
     * Returns the player's current speed bonus, evicting it lazily once expired.
     *
     * @param playerId the player's UUID
     * @return the active bonus, or {@code 0.0} if none is active or it has expired
     */
    public double getBonus(@Nonnull UUID playerId) {
        Boost boost = boosts.get(playerId);
        if (boost == null) {
            return 0.0;
        }
        if (System.nanoTime() >= boost.expiryNanos()) {
            // Only evict if still mapped to this boost, so a concurrent re-apply is preserved.
            boosts.remove(playerId, boost);
            return 0.0;
        }
        return boost.bonus();
    }

    /**
     * Clears any active speed bonus for the given player.
     *
     * @param playerId the player's UUID
     */
    public void clear(@Nonnull UUID playerId) {
        boosts.remove(playerId);
    }

    /**
     * An active speed bonus with its absolute expiry time.
     *
     * @param bonus       the flat move-speed bonus
     * @param expiryNanos the {@link System#nanoTime()} value at which the bonus expires
     */
    private record Boost(double bonus, long expiryNanos) {
    }
}
