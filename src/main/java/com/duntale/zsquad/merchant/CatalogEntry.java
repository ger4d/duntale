package com.duntale.zsquad.merchant;

import javax.annotation.Nonnull;

/**
 * Defines an item in a merchant's catalog.
 *
 * <p>Each entry specifies the item asset ID and an optional gear level.
 * Level 0 indicates a non-leveled item; values 1–60 indicate a dungeon-leveled item.
 *
 * @param itemId the item asset ID (e.g. {@code "Weapon_Axe_Crude"})
 * @param level  the gear level (0 for non-leveled, 1–60 for leveled)
 */
public record CatalogEntry(@Nonnull String itemId, int level) {

    /**
     * Creates a catalog entry for a non-leveled item.
     *
     * @param itemId the item asset ID
     * @return a new catalog entry with level 0
     */
    @Nonnull
    public static CatalogEntry of(@Nonnull String itemId) {
        return new CatalogEntry(itemId, 0);
    }

    /**
     * Creates a catalog entry for a leveled item.
     *
     * @param itemId the item asset ID
     * @param level  the gear level (1–60)
     * @return a new catalog entry
     */
    @Nonnull
    public static CatalogEntry of(@Nonnull String itemId, int level) {
        return new CatalogEntry(itemId, level);
    }
}
