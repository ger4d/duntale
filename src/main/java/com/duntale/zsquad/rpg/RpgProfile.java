package com.duntale.zsquad.rpg;

import javax.annotation.Nonnull;
import java.util.EnumMap;
import java.util.Map;

/**
 * Per-player RPG stat profile. Stores the base stat values for all {@link RpgStat}s.
 */
public class RpgProfile {
    private final Map<RpgStat, Integer> stats;

    /** Creates an empty profile with all stats at 0. */
    public RpgProfile() {
        this.stats = new EnumMap<>(RpgStat.class);
        for (RpgStat stat : RpgStat.values()) {
            stats.put(stat, 0);
        }
    }

    /** Creates a profile from an existing stat map. */
    public RpgProfile(@Nonnull Map<RpgStat, Integer> stats) {
        this.stats = new EnumMap<>(RpgStat.class);
        for (RpgStat stat : RpgStat.values()) {
            this.stats.put(stat, stats.getOrDefault(stat, 0));
        }
    }

    /**
     * Returns the value of the given stat.
     *
     * @param stat the stat to query
     * @return the stat value, or 0 if not set
     */
    public int getStat(@Nonnull RpgStat stat) {
        return stats.getOrDefault(stat, 0);
    }

    /**
     * Sets the value of the given stat.
     *
     * @param stat  the stat to set
     * @param value the new value
     */
    public void setStat(@Nonnull RpgStat stat, int value) {
        stats.put(stat, value);
    }

    /**
     * Returns an unmodifiable copy of all stats.
     *
     * @return an unmodifiable map of all stat values
     */
    @Nonnull
    public Map<RpgStat, Integer> getAll() {
        return Map.copyOf(stats);
    }
}
