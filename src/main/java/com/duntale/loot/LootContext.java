package com.duntale.loot;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Runtime context used to evaluate loot entry conditions.
 */
public record LootContext(
        @Nullable Integer npcLevel,
        @Nullable Integer floorLevel
) {

    /**
     * Creates a context for an NPC kill, carrying BOTH the NPC level and the dungeon floor.
     *
     * <p>A dungeon NPC's level is its spawn floor, so the kill context exposes the same value as both
     * {@link #npcLevel()} and {@link #floorLevel()}. This lets floor-gated entries
     * ({@code MinFloorLevel}/{@code MaxFloorLevel}) evaluate on a kill (they were previously filtered
     * out because the floor was never populated) and lets gear default to the killed NPC's level.
     *
     * @param npcLevel the killed NPC's level (also used as the floor)
     * @return a context carrying the NPC level as both the NPC level and the floor level
     */
    @Nonnull
    public static LootContext forNpcKill(int npcLevel) {
        return new LootContext(npcLevel, npcLevel);
    }

    /**
     * Creates a context for an NPC-only loot roll that is deliberately NOT floor-gated (no floor).
     *
     * <p><strong>Do not use this for a dungeon kill</strong> — use {@link #forNpcKill(int)} instead.
     * With a {@code null} floor, floor-gated entries ({@code MinFloorLevel}/{@code MaxFloorLevel}) are
     * filtered out, so a kill rolled with this context would silently drop them.
     *
     * @param npcLevel the NPC level
     * @return a context carrying only the NPC level (no floor gating)
     */
    @Nonnull
    public static LootContext forNpcLevel(int npcLevel) {
        return new LootContext(npcLevel, null);
    }

    /**
     * Creates a context for floor-driven loot rolls.
     *
     * @param floorLevel the dungeon floor level
     * @return a context carrying the floor level
     */
    @Nonnull
    public static LootContext forFloorLevel(int floorLevel) {
        return new LootContext(null, floorLevel);
    }
}