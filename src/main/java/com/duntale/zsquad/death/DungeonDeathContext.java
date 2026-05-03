package com.duntale.zsquad.death;

import com.duntale.zsquad.dungeon.DungeonInstance;
import com.hypixel.hytale.server.core.Message;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Snapshot of the information needed to render and validate a dungeon death choice.
 *
 * @param instance the active dungeon instance where the player died
 * @param deathReason the formatted death reason, or {@code null} when unavailable
 * @param balance the player's gold balance when the page was built
 * @param currentFloorCost the cost to respawn on the current floor
 * @param lowerFloorCost the cost to restart on the previous floor
 * @param lowerFloorAvailable whether a previous floor exists for this instance
 */
public record DungeonDeathContext(
        @Nonnull DungeonInstance instance,
        @Nullable Message deathReason,
        long balance,
        long currentFloorCost,
        long lowerFloorCost,
        boolean lowerFloorAvailable
) {

    /**
     * Validates the dungeon death context snapshot.
     */
    public DungeonDeathContext {
        Objects.requireNonNull(instance, "instance");
        if (balance < 0L) {
            throw new IllegalArgumentException("balance must not be negative");
        }
        if (currentFloorCost < 0L) {
            throw new IllegalArgumentException("currentFloorCost must not be negative");
        }
        if (lowerFloorCost < 0L) {
            throw new IllegalArgumentException("lowerFloorCost must not be negative");
        }
    }
}