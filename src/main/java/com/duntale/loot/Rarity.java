package com.duntale.loot;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Duntale-owned gear rarity ladder, ordered from least to most powerful.
 *
 * <p>Rarity is decoupled from the engine's built-in asset quality (which stays a cosmetic border
 * color). The real rarity is stamped in item metadata at generation under {@code duntale_rarity}
 * (see {@code GearLevelService}), drives the gear-curve power nudge, the merchant price multiplier,
 * and the bonus-attribute system. The string {@link #id()} is the stable token persisted in
 * metadata and config JSON ({@code "Common"} &hellip; {@code "Legendary"}), matching the keys used
 * by {@code GearCurveRegistry.rarityNudge} and {@code RarityRegistry}.
 */
public enum Rarity {
    COMMON("Common"),
    UNCOMMON("Uncommon"),
    RARE("Rare"),
    EPIC("Epic"),
    LEGENDARY("Legendary"),
    RELIC("Relic"),
    ABYSSAL("Abyssal");

    private static final Rarity[] VALUES = values();

    @Nonnull
    private final String id;

    Rarity(@Nonnull String id) {
        this.id = id;
    }

    /**
     * Returns the stable string token persisted in item metadata and config JSON.
     *
     * @return the rarity id (e.g. {@code "Legendary"})
     */
    @Nonnull
    public String id() {
        return id;
    }

    /**
     * Returns the zero-based tier index, with {@link #COMMON} at {@code 0} and {@link #LEGENDARY}
     * at {@code VALUES.length - 1}.
     *
     * @return the tier index
     */
    public int tierIndex() {
        return ordinal();
    }

    /**
     * Resolves a rarity from its tier index, clamping out-of-range values into the ladder.
     *
     * @param tierIndex the tier index
     * @return the rarity at the (clamped) tier index
     */
    @Nonnull
    public static Rarity fromTierIndex(int tierIndex) {
        return VALUES[Math.clamp(tierIndex, 0, VALUES.length - 1)];
    }

    /**
     * Resolves a rarity from its string id (case-insensitive).
     *
     * @param id the rarity id (e.g. {@code "Rare"}), or {@code null}
     * @return the matching rarity, or {@code null} when unknown/blank
     */
    @Nullable
    public static Rarity fromId(@Nullable String id) {
        if (id == null) {
            return null;
        }
        for (Rarity rarity : VALUES) {
            if (rarity.id.equalsIgnoreCase(id)) {
                return rarity;
            }
        }
        return null;
    }

    /**
     * Returns the next rarity up the ladder, capped at {@link #LEGENDARY}.
     *
     * @return the next rarity, or this rarity when already at the ceiling
     */
    @Nonnull
    public Rarity next() {
        return promote(1);
    }

    /**
     * Promotes this rarity up the ladder by the given number of tiers, capped at
     * {@link #LEGENDARY}. Non-positive promotions return this rarity unchanged.
     *
     * @param tiers the number of tiers to promote by
     * @return the promoted rarity
     */
    @Nonnull
    public Rarity promote(int tiers) {
        if (tiers <= 0) {
            return this;
        }
        return fromTierIndex(ordinal() + tiers);
    }
}
