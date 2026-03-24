package com.duntale.zsquad.merchant;

import com.duntale.zsquad.progression.AssetCatalog;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Computes and caches buy/sell prices for all weapons and armor.
 *
 * <p>Prices are derived from {@link AssetCatalog}'s weapon/armor base tables
 * using the formula:
 * <pre>
 * buyPrice  = itemLevel² × qualityCoefficient [× slotMultiplier for armor]
 * sellPrice = floor(buyPrice × SELL_RATIO)
 * </pre>
 *
 * <p>Initialise once at plugin startup via {@link #initialize(AssetCatalog)}.
 * All subsequent lookups are O(1) from cache.
 */
public class MerchantPriceRegistry {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Sell price as a fraction of buy price (80% = 20% gold sink). */
    static final double SELL_RATIO = 0.80;

    /** Minimum buy price to avoid zero-cost items at very low levels. */
    private static final long MIN_BUY_PRICE = 5L;

    private final Map<String, Long> buyPriceCache = new ConcurrentHashMap<>();
    private final Map<String, Integer> itemLevelCache = new ConcurrentHashMap<>();

    /**
     * Populates the price cache from all weapons and armor in the asset catalog.
     *
     * @param assetCatalog the asset catalog (must already be initialised)
     */
    public void initialize(@Nonnull AssetCatalog assetCatalog) {
        int weaponCount = 0;
        int armorCount = 0;

        // Load all weapons (limit 500 to cover the full catalog)
        for (AssetCatalog.WeaponBaseRow row : assetCatalog.listWeapons("name", true, 500, null)) {
            if (isExcluded(row.quality()) || isNpcItem(row.name())) {
                continue;
            }
            String assetId = toAssetId(row.name());
            long price = computeWeaponPrice(row.itemLevel(), row.quality());
            buyPriceCache.put(assetId, price);
            itemLevelCache.put(assetId, row.itemLevel());
            weaponCount++;
        }

        // Load all armor
        for (AssetCatalog.ArmorBaseRow row : assetCatalog.listArmor("name", true, 500, null)) {
            if (isExcluded(row.quality()) || isNpcItem(row.name())) {
                continue;
            }
            String assetId = toAssetId(row.name());
            long price = computeArmorPrice(row.itemLevel(), row.quality(), row.slot());
            buyPriceCache.put(assetId, price);
            itemLevelCache.put(assetId, row.itemLevel());
            armorCount++;
        }

        LOGGER.at(Level.INFO).log("MerchantPriceRegistry initialized: %d weapons, %d armor pieces",
                weaponCount, armorCount);
    }

    /**
     * Returns the buy price for the given item.
     *
     * @param itemId the item asset ID
     * @return the buy price in gold, or {@code 0} if the item is not in the registry
     */
    public long getBuyPrice(@Nonnull String itemId) {
        Long price = buyPriceCache.get(itemId);
        return price != null ? price : 0L;
    }

    /**
     * Returns the sell price for the given item (80% of buy price).
     *
     * @param itemId the item asset ID
     * @return the sell price in gold, or {@code 0} if the item is not in the registry
     */
    public long getSellPrice(@Nonnull String itemId) {
        long buyPrice = getBuyPrice(itemId);
        return buyPrice > 0 ? (long) Math.floor(buyPrice * SELL_RATIO) : 0L;
    }

    /**
     * Returns the sell price adjusted for the item's dungeon level.
     *
     * <p>Items with a dungeon level receive a multiplier based on a sigmoid scaling
     * curve: higher dungeon levels yield better sell prices.
     *
     * @param itemId       the item asset ID
     * @param dungeonLevel the dungeon level from item metadata, or {@code 0} for base price
     * @return the level-adjusted sell price in gold
     */
    public long getSellPrice(@Nonnull String itemId, int dungeonLevel) {
        long basePrice = getSellPrice(itemId);
        if (basePrice <= 0 || dungeonLevel <= 0) {
            return basePrice;
        }
        // Linear scaling: 1.0 at L1, 2.0 at L30, 3.0 at L60
        double levelMult = 1.0 + (dungeonLevel - 1) / 29.5;
        return Math.max(basePrice, (long) Math.floor(basePrice * levelMult));
    }

    /**
     * Returns whether the given item exists in the price registry and can be sold.
     *
     * @param itemId the item asset ID
     * @return {@code true} if the item is sellable
     */
    public boolean isSellable(@Nonnull String itemId) {
        return buyPriceCache.containsKey(itemId);
    }

    /**
     * Returns the base item level for the given item from the scaling database.
     *
     * @param itemId the item asset ID
     * @return the item level, or {@code 0} if the item is not in the registry
     */
    public int getItemLevel(@Nonnull String itemId) {
        Integer level = itemLevelCache.get(itemId);
        return level != null ? level : 0;
    }

    /**
     * Returns all item IDs whose base level falls within the given range (inclusive).
     *
     * @param minLevel the minimum item level (inclusive)
     * @param maxLevel the maximum item level (inclusive)
     * @return a mutable list of matching item IDs
     */
    @Nonnull
    public List<String> getItemsByLevelRange(int minLevel, int maxLevel) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : itemLevelCache.entrySet()) {
            int level = entry.getValue();
            if (level >= minLevel && level <= maxLevel) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /**
     * Returns the number of priced items in the registry.
     *
     * @return the total number of items with prices
     */
    public int size() {
        return buyPriceCache.size();
    }

    /**
     * Returns an unmodifiable view of all item IDs in the price registry.
     *
     * @return the set of priced item IDs
     */
    @Nonnull
    public Set<String> getItemIds() {
        return Set.copyOf(buyPriceCache.keySet());
    }

    // ── Price computation ────────────────────────────────────────────

    private long computeWeaponPrice(int itemLevel, @Nullable String quality) {
        double coeff = qualityCoefficient(quality);
        return Math.max(MIN_BUY_PRICE, (long) ((double) itemLevel * itemLevel * coeff));
    }

    private long computeArmorPrice(int itemLevel, @Nullable String quality, @Nullable String slot) {
        double coeff = qualityCoefficient(quality);
        double slotMult = slotMultiplier(slot);
        return Math.max(MIN_BUY_PRICE, (long) ((double) itemLevel * itemLevel * coeff * slotMult));
    }

    /**
     * Returns the quality coefficient for the given quality tier.
     *
     * @param quality the quality string (Common, Uncommon, Rare, Epic), or {@code null} for default
     * @return the quality coefficient
     */
    static double qualityCoefficient(@Nullable String quality) {
        if (quality == null) {
            return 1.0;
        }
        return switch (quality) {
            case "Common" -> 1.0;
            case "Uncommon" -> 1.5;
            case "Rare" -> 2.5;
            case "Epic" -> 5.0;
            case "Legendary" -> 15.0;
            default -> 1.0;
        };
    }

    /**
     * Returns the armor slot multiplier.
     *
     * @param slot the slot string (Chest, Legs, Head, Hands), or {@code null} for default
     * @return the slot multiplier
     */
    static double slotMultiplier(@Nullable String slot) {
        if (slot == null) {
            return 1.0;
        }
        return switch (slot) {
            case "Chest" -> 1.0;
            case "Legs" -> 0.75;
            case "Head" -> 0.60;
            case "Hands" -> 0.50;
            default -> 1.0;
        };
    }

    /**
     * Checks whether the given quality should be excluded from the merchant catalog.
     *
     * @param quality the quality string
     * @return {@code true} if the item should be excluded
     */
    private static boolean isExcluded(@Nullable String quality) {
        if (quality == null) {
            return false;
        }
        return switch (quality) {
            case "Developer", "Technical" -> true;
            default -> false;
        };
    }

    /**
     * Checks whether the given item name indicates an NPC-held item (not for players).
     *
     * @param name the item name
     * @return {@code true} if the item is NPC-only
     */
    private static boolean isNpcItem(@Nonnull String name) {
        return name.endsWith("_NPC") || name.contains("_NPC_")
                || name.endsWith(" NPC") || name.contains(" NPC ");
    }

    /**
     * Converts a space-separated display name to its underscored asset ID.
     *
     * <p>The scaling database stores names with spaces ({@code "Weapon Sword Iron"}),
     * while the Hytale asset registry uses underscores ({@code "Weapon_Sword_Iron"}).
     *
     * @param displayName the space-separated display name
     * @return the underscored asset ID
     */
    @Nonnull
    private static String toAssetId(@Nonnull String displayName) {
        return displayName.replace(' ', '_');
    }
}
