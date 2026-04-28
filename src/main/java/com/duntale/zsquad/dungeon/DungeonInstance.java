package com.duntale.zsquad.dungeon;

import com.duntale.dungeongen.config.Vec3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Persisted metadata for a single dungeon instance.
 *
 * @param instanceId       unique instance identifier
 * @param worldName        world name backing the instance
 * @param floorLevel       current floor number, starting at {@code 1}
 * @param floorY           authoritative floor Y value for runtime systems such as click-to-move
 * @param entrancePosition authoritative generated entrance position for the current floor
 * @param exitPosition     authoritative generated exit position for the current floor
 * @param state            current lifecycle state
 * @param theme            active generated-floor theme selected for the current floor
 * @param seed             generation seed, or {@code null} to represent random generation
 * @param createdAt        creation timestamp in epoch milliseconds
 * @since 1.6.0
 */
public record DungeonInstance(
        @Nonnull String instanceId,
        @Nonnull String worldName,
        int floorLevel,
        double floorY,
        @Nonnull Vec3i entrancePosition,
        @Nonnull Vec3i exitPosition,
        @Nonnull DungeonInstanceState state,
        @Nonnull String theme,
        @Nullable String seed,
        long createdAt
) {

    /**
     * Validates the persisted metadata for a dungeon instance.
     */
    public DungeonInstance {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(worldName, "worldName");
        Objects.requireNonNull(entrancePosition, "entrancePosition");
        Objects.requireNonNull(exitPosition, "exitPosition");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(theme, "theme");

        if (floorLevel < 1) {
            throw new IllegalArgumentException("floorLevel must be at least 1");
        }
        if (!Double.isFinite(floorY)) {
            throw new IllegalArgumentException("floorY must be finite");
        }
        if (createdAt < 0L) {
            throw new IllegalArgumentException("createdAt must be non-negative");
        }
    }
}
