package com.duntale.zsquad.loot;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A single item that can drop from a {@link LootTable}.
 *
 * @param itemId      the Hytale item asset ID (e.g. "Common:Gold_Coin")
 * @param quantityMin minimum quantity (inclusive, &ge; 1)
 * @param quantityMax maximum quantity (inclusive, &ge; quantityMin)
 * @param weight      relative weight for weighted random selection (&gt; 0)
 * @param minLevel    minimum NPC level required for this drop (inclusive), or {@code null} for no minimum
 * @param maxLevel    maximum NPC level required for this drop (inclusive), or {@code null} for no maximum
 */
public record LootEntry(
        @Nonnull String itemId,
        int quantityMin,
        int quantityMax,
        double weight,
        @Nullable Integer minLevel,
        @Nullable Integer maxLevel
) {

    /**
     * Creates a loot entry with no level restriction.
     *
     * @param itemId      the item asset ID
     * @param quantityMin minimum quantity
     * @param quantityMax maximum quantity
     * @param weight      relative weight
     */
    public LootEntry(@Nonnull String itemId, int quantityMin, int quantityMax, double weight) {
        this(itemId, quantityMin, quantityMax, weight, null, null);
    }

    /**
     * Returns whether this entry is eligible for the given NPC level.
     *
     * @param npcLevel the NPC's dungeon level
     * @return {@code true} if the entry can drop at this level
     */
    public boolean isEligible(int npcLevel) {
        if (minLevel != null && npcLevel < minLevel) {
            return false;
        }
        return maxLevel == null || npcLevel <= maxLevel;
    }
}
