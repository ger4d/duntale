package com.duntale.merchant;

import javax.annotation.Nonnull;

/**
 * Defines an item in a merchant's catalog with its pre-computed buy price.
 *
 * <p>Each entry specifies the item asset ID, an optional gear level, and the
 * buy price in gold. Level 0 indicates a consumable or non-leveled item;
 * values 1–60 indicate a dungeon-leveled gear item whose price was computed
 * from that stamped level.
 *
 * @param itemId   the item asset ID (e.g. {@code "Weapon_Axe_Crude"})
 * @param level    the gear level (0 for consumables/non-leveled, 1–60 for gear)
 * @param buyPrice the buy price in gold (pre-computed for the stamped level)
 */
public record CatalogEntry(@Nonnull String itemId, int level, long buyPrice) {

    /**
     * Creates a catalog entry for a leveled gear item.
     *
     * @param itemId   the item asset ID
     * @param level    the gear level (1–60)
     * @param buyPrice the buy price in gold
     * @return a new catalog entry
     */
    @Nonnull
    public static CatalogEntry gear(@Nonnull String itemId, int level, long buyPrice) {
        return new CatalogEntry(itemId, level, buyPrice);
    }

    /**
     * Creates a catalog entry for a consumable with a fixed price.
     *
     * @param itemId   the item asset ID
     * @param buyPrice the fixed buy price in gold
     * @return a new catalog entry with level 0
     */
    @Nonnull
    public static CatalogEntry consumable(@Nonnull String itemId, long buyPrice) {
        return new CatalogEntry(itemId, 0, buyPrice);
    }
}
