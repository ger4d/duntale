package com.duntale.merchant;

import com.duntale.loot.GearAttribute;
import com.duntale.loot.Rarity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

/**
 * Defines an item in a merchant's catalog with its pre-computed buy price.
 *
 * <p>Each entry specifies the item asset ID, an optional gear level, and the
 * buy price in gold. Level 0 indicates a consumable or non-leveled item;
 * positive values indicate a dungeon-leveled gear item whose price was computed
 * from that stamped level.
 *
 * <p>Gear entries additionally carry the rolled {@link Rarity} and rarity-granted
 * {@link GearAttribute}s so the buy path can stamp them onto the purchased stack and the buy price
 * already reflects the rarity multiplier. Consumables leave rarity {@code null} and attributes empty.
 *
 * @param itemId     the item asset ID (e.g. {@code "Weapon_Axe_Crude"})
 * @param level      the gear level (0 for consumables/non-leveled, positive for gear)
 * @param buyPrice   the buy price in gold (pre-computed for the stamped level and rarity)
 * @param quantity   the stack quantity to offer (1 for gear, max-stack for consumables)
 * @param rarity     the rolled rarity for gear, or {@code null} for consumables/unstamped gear
 * @param attributes the rarity-granted attributes for gear (empty for consumables)
 */
public record CatalogEntry(@Nonnull String itemId, int level, long buyPrice, int quantity,
                           @Nullable Rarity rarity, @Nonnull List<GearAttribute> attributes) {

    /**
     * Creates a catalog entry for a leveled gear item with a stack quantity of 1 and no rarity.
     *
     * @param itemId   the item asset ID
     * @param level    the gear level within supported combat bounds
     * @param buyPrice the buy price in gold
     * @return a new catalog entry
     */
    @Nonnull
    public static CatalogEntry gear(@Nonnull String itemId, int level, long buyPrice) {
        return new CatalogEntry(itemId, level, buyPrice, 1, null, List.of());
    }

    /**
     * Creates a catalog entry for a leveled gear item with a rolled rarity and attributes.
     *
     * @param itemId     the item asset ID
     * @param level      the gear level within supported combat bounds
     * @param buyPrice   the buy price in gold (already including the rarity multiplier)
     * @param rarity     the rolled rarity
     * @param attributes the rarity-granted attributes
     * @return a new catalog entry
     */
    @Nonnull
    public static CatalogEntry gear(@Nonnull String itemId, int level, long buyPrice,
                                    @Nonnull Rarity rarity, @Nonnull List<GearAttribute> attributes) {
        return new CatalogEntry(itemId, level, buyPrice, 1, rarity, List.copyOf(attributes));
    }

    /**
     * Creates a catalog entry for a consumable with a fixed price and a stack
     * quantity of 1.
     *
     * @param itemId   the item asset ID
     * @param buyPrice the fixed buy price in gold
     * @return a new catalog entry with level 0 and quantity 1
     */
    @Nonnull
    public static CatalogEntry consumable(@Nonnull String itemId, long buyPrice) {
        return new CatalogEntry(itemId, 0, buyPrice, 1, null, List.of());
    }

    /**
     * Creates a catalog entry for a consumable offered as a stack.
     *
     * @param itemId   the item asset ID
     * @param buyPrice the buy price in gold for the full stack
     * @param quantity the stack quantity to offer
     * @return a new catalog entry with level 0
     */
    @Nonnull
    public static CatalogEntry consumable(@Nonnull String itemId, long buyPrice, int quantity) {
        return new CatalogEntry(itemId, 0, buyPrice, quantity, null, List.of());
    }
}
