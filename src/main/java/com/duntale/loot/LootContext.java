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
     * Creates a context for NPC-driven loot rolls.
     *
     * @param npcLevel the NPC level
     * @return a context carrying the NPC level
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