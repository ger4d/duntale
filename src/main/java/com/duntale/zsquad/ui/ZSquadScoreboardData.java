package com.duntale.zsquad.ui;

import com.duntale.zsquad.rpg.RpgProfile;
import com.duntale.zsquad.rpg.RpgStat;

import javax.annotation.Nonnull;
import java.util.EnumMap;
import java.util.Map;

/**
 * Immutable data snapshot for the {@link ZSquadScoreboard} HUD.
 *
 * <p>Built via the {@link Builder} pattern. All fields have sensible defaults.
 */
public final class ZSquadScoreboardData {

    private final long gold;
    private final int level;
    private final long xp;
    private final long xpMax;
    private final Map<RpgStat, Integer> stats;

    private ZSquadScoreboardData(@Nonnull Builder builder) {
        this.gold = builder.gold;
        this.level = builder.level;
        this.xp = builder.xp;
        this.xpMax = builder.xpMax;
        this.stats = Map.copyOf(builder.stats);
    }

    /** Returns the gold balance. */
    public long gold() { return gold; }

    /** Returns the player level. */
    public int level() { return level; }

    /** Returns the current XP within this level. */
    public long xp() { return xp; }

    /** Returns the XP required for the next level. */
    public long xpMax() { return xpMax; }

    /**
     * Returns the value of the given RPG stat.
     *
     * @param stat the RPG stat
     * @return the stat value, or {@code 0} if not set
     */
    public int getStat(@Nonnull RpgStat stat) {
        return stats.getOrDefault(stat, 0);
    }

    /**
     * Creates a new builder, optionally copying values from an existing data snapshot.
     *
     * @return a new builder
     */
    @Nonnull
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a builder pre-populated from this data snapshot.
     *
     * @return a pre-populated builder
     */
    @Nonnull
    public Builder toBuilder() {
        Builder b = new Builder();
        b.gold = this.gold;
        b.level = this.level;
        b.xp = this.xp;
        b.xpMax = this.xpMax;
        b.stats.putAll(this.stats);
        return b;
    }

    /**
     * Builder for {@link ZSquadScoreboardData}.
     */
    public static final class Builder {
        private long gold;
        private int level = 1;
        private long xp;
        private long xpMax;
        private final EnumMap<RpgStat, Integer> stats = new EnumMap<>(RpgStat.class);

        private Builder() {}

        /** Sets the gold balance. */
        @Nonnull public Builder gold(long gold) { this.gold = gold; return this; }

        /** Sets the player level. */
        @Nonnull public Builder level(int level) { this.level = level; return this; }

        /** Sets the current XP. */
        @Nonnull public Builder xp(long xp) { this.xp = xp; return this; }

        /** Sets the XP threshold for the next level. */
        @Nonnull public Builder xpMax(long xpMax) { this.xpMax = xpMax; return this; }

        /** Sets all stats from an RPG profile. */
        @Nonnull
        public Builder stats(@Nonnull RpgProfile profile) {
            for (RpgStat stat : RpgStat.values()) {
                this.stats.put(stat, profile.getStat(stat));
            }
            return this;
        }

        /** Sets a single stat value. */
        @Nonnull
        public Builder stat(@Nonnull RpgStat stat, int value) {
            this.stats.put(stat, value);
            return this;
        }

        /** Builds the immutable data snapshot. */
        @Nonnull
        public ZSquadScoreboardData build() {
            return new ZSquadScoreboardData(this);
        }
    }
}
