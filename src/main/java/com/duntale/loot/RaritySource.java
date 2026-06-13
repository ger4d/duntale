package com.duntale.loot;

import com.duntale.dungeongen.model.ChestTier;
import com.duntale.progression.CombatScaling;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The roll source whose ladder weights seed a base {@link Rarity} before Luck promotion.
 *
 * <p>Each source maps to a weighted rarity ladder in {@code RarityConfigAsset}/{@code RarityRegistry}.
 * Higher-tier sources (elites, bosses, premium chests) skew toward rarer base rolls; player-context
 * sources additionally receive a two-step Luck promotion.
 */
public enum RaritySource {
    MOB,
    ELITE,
    BOSS,
    CHEST_REGULAR,
    CHEST_GOLDEN,
    CHEST_EPIC,
    CHEST_LEGENDARY,
    MERCHANT;

    /**
     * Resolves a source from its config id (case-insensitive, matches {@link #name()}).
     *
     * @param id the source id (e.g. {@code "MOB"}), or {@code null}
     * @return the matching source, or {@code null} when unknown/blank
     */
    @Nullable
    public static RaritySource fromId(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (RaritySource source : values()) {
            if (source.name().equalsIgnoreCase(id.trim())) {
                return source;
            }
        }
        return null;
    }

    /**
     * Maps an NPC combat variant to its drop ladder source.
     *
     * @param variant the NPC variant
     * @return the matching ladder source
     */
    @Nonnull
    public static RaritySource forNpcVariant(@Nonnull CombatScaling.NpcVariant variant) {
        return switch (variant) {
            case NORMAL -> MOB;
            case ELITE -> ELITE;
            case BOSS -> BOSS;
        };
    }

    /**
     * Maps a dungeon chest tier to its on-open ladder source.
     *
     * @param tier the chest tier
     * @return the matching ladder source
     */
    @Nonnull
    public static RaritySource forChestTier(@Nonnull ChestTier tier) {
        return switch (tier) {
            case REGULAR -> CHEST_REGULAR;
            case GOLDEN -> CHEST_GOLDEN;
            case EPIC -> CHEST_EPIC;
            case LEGENDARY -> CHEST_LEGENDARY;
        };
    }
}
