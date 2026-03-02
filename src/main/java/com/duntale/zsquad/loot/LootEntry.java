package com.duntale.zsquad.loot;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A single entry in a {@link LootTable} that can produce one kind of drop.
 *
 * <p>This is a sealed interface with two variants:
 * <ul>
 *   <li>{@link Simple} — regular items (potions, materials, etc.) with no level metadata.</li>
 *   <li>{@link Leveled} — weapons or armor that drop with {@code zsquad_weapon_level} /
 *       {@code zsquad_armor_level} metadata and variance, integrated with
 *       {@link com.duntale.zsquad.progression.GearLevelService}.</li>
 * </ul>
 *
 * <p>Both variants share a {@link #weight()} for weighted random selection and optional
 * NPC-level gates ({@link #minNpcLevel()}, {@link #maxNpcLevel()}) that control at which
 * monster levels the entry is eligible.
 */
public sealed interface LootEntry permits LootEntry.Simple, LootEntry.Leveled {

    /**
     * Returns the Hytale item asset ID (e.g. {@code "Weapon_Sword_Cobalt"}).
     *
     * @return the item ID
     */
    @Nonnull
    String itemId();

    /**
     * Returns the relative weight for weighted random selection (&gt; 0).
     *
     * @return the weight
     */
    double weight();

    /**
     * Returns the minimum NPC level required for this drop (inclusive), or {@code null} for no minimum.
     *
     * @return the minimum NPC level, or {@code null}
     */
    @Nullable
    Integer minNpcLevel();

    /**
     * Returns the maximum NPC level required for this drop (inclusive), or {@code null} for no maximum.
     *
     * @return the maximum NPC level, or {@code null}
     */
    @Nullable
    Integer maxNpcLevel();

    /**
     * Returns whether this entry is eligible for the given NPC level.
     *
     * @param npcLevel the NPC's dungeon level
     * @return {@code true} if the entry can drop at this level
     */
    default boolean isEligible(int npcLevel) {
        if (minNpcLevel() != null && npcLevel < minNpcLevel()) {
            return false;
        }
        return maxNpcLevel() == null || npcLevel <= maxNpcLevel();
    }

    // ── Simple (regular) item drop ───────────────────────────────────

    /**
     * A regular item drop with no level/scaling metadata (e.g. potions, materials, coins).
     *
     * @param itemId      the Hytale item asset ID
     * @param quantityMin minimum quantity (inclusive, &ge; 1)
     * @param quantityMax maximum quantity (inclusive, &ge; quantityMin)
     * @param weight      relative weight for weighted random selection
     * @param minNpcLevel minimum NPC level for eligibility, or {@code null}
     * @param maxNpcLevel maximum NPC level for eligibility, or {@code null}
     */
    record Simple(
            @Nonnull String itemId,
            int quantityMin,
            int quantityMax,
            double weight,
            @Nullable Integer minNpcLevel,
            @Nullable Integer maxNpcLevel
    ) implements LootEntry {

        /**
         * Creates a simple loot entry with no NPC-level restriction.
         *
         * @param itemId      the item asset ID
         * @param quantityMin minimum quantity
         * @param quantityMax maximum quantity
         * @param weight      relative weight
         */
        public Simple(@Nonnull String itemId, int quantityMin, int quantityMax, double weight) {
            this(itemId, quantityMin, quantityMax, weight, null, null);
        }
    }

    // ── Leveled (weapon/armor) item drop ─────────────────────────────

    /**
     * The type of leveled gear — determines which metadata key is applied.
     */
    enum GearType {
        /** Applies {@code zsquad_weapon_level} + {@code zsquad_weapon_variance}. */
        WEAPON,
        /** Applies {@code zsquad_armor_level} + {@code zsquad_armor_variance}. */
        ARMOR
    }

    /**
     * A leveled weapon or armor drop that will be stamped with scaling metadata.
     *
     * <p>When rolled, the gear level is randomised between {@link #gearLevelMin()} and
     * {@link #gearLevelMax()}, and a variance is rolled via
     * {@link com.duntale.zsquad.progression.GearLevelService#rollVariance()}.
     *
     * @param itemId        the Hytale item asset ID (e.g. {@code "Weapon_Sword_Cobalt"})
     * @param gearType      whether this is a weapon or armor piece
     * @param gearLevelMin  minimum gear level to roll (inclusive, &ge; 1)
     * @param gearLevelMax  maximum gear level to roll (inclusive, &ge; gearLevelMin)
     * @param weight        relative weight for weighted random selection
     * @param minNpcLevel   minimum NPC level for eligibility, or {@code null}
     * @param maxNpcLevel   maximum NPC level for eligibility, or {@code null}
     */
    record Leveled(
            @Nonnull String itemId,
            @Nonnull GearType gearType,
            int gearLevelMin,
            int gearLevelMax,
            double weight,
            @Nullable Integer minNpcLevel,
            @Nullable Integer maxNpcLevel
    ) implements LootEntry {

        /**
         * Creates a leveled loot entry with no NPC-level restriction.
         *
         * @param itemId       the item asset ID
         * @param gearType     weapon or armor
         * @param gearLevelMin minimum gear level
         * @param gearLevelMax maximum gear level
         * @param weight       relative weight
         */
        public Leveled(@Nonnull String itemId, @Nonnull GearType gearType,
                       int gearLevelMin, int gearLevelMax, double weight) {
            this(itemId, gearType, gearLevelMin, gearLevelMax, weight, null, null);
        }
    }
}
