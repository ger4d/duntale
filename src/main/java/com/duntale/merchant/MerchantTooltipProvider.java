package com.duntale.merchant;

import org.herolias.tooltips.api.TooltipData;
import org.herolias.tooltips.api.TooltipPriority;
import org.herolias.tooltips.api.TooltipProvider;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Tooltip provider that displays buy/sell prices and gold balance on merchant items.
 *
 * <p><strong>Buy items</strong> have metadata containing {@code merchant_buy_price} and
 * {@code merchant_gold}, injected by {@link MerchantService}. The tooltip shows
 * the buy price and the player's current gold balance.
 *
 * <p><strong>Sell items</strong> — any item with a known sell price in the
 * {@link MerchantPriceRegistry} — shows the sell value. Sell price is derived
 * from the item ID plus any stamped dungeon level metadata, so no extra sell
 * metadata injection is needed.
 *
 * <p>Registered with DynamicTooltipsLib in {@code DuntalePlugin}. Requires the
 * optional DynamicTooltipsLib dependency to be loaded at runtime.
 *
 * @see MerchantService
 */
public class MerchantTooltipProvider implements TooltipProvider {

    private static final String PROVIDER_ID = "duntale_merchant";

    private static final String COLOR_GOLD = "#FFD700";
    private static final String COLOR_GREEN = "#55FF55";
    private static final String COLOR_GRAY = "#AAAAAA";
    private static final String COLOR_RED = "#FF5555";

    private final MerchantPriceRegistry priceRegistry;

    /**
     * Creates a new merchant tooltip provider.
     *
     * @param priceRegistry the price registry for sell-price lookups
     */
    public MerchantTooltipProvider(@Nonnull MerchantPriceRegistry priceRegistry) {
        this.priceRegistry = priceRegistry;
    }

    @Nonnull
    @Override
    public String getProviderId() {
        return PROVIDER_ID;
    }

    @Override
    public int getPriority() {
        return TooltipPriority.LATE;
    }

    @Nullable
    @Override
    public TooltipData getTooltipData(@Nonnull String itemId, @Nullable String metadata) {
        // 1. Check for merchant buy-zone metadata
        if (metadata != null && metadata.contains(MerchantService.META_BUY_PRICE)) {
            Long price = extractLong(metadata, MerchantService.META_BUY_PRICE);
            Long gold = extractLong(metadata, MerchantService.META_GOLD);
            if (price != null && gold != null) {
                return TooltipData.builder()
                        .hashInput("merch_buy:" + price + ":" + gold)
                        .addLine(colorTag(COLOR_GOLD, "Buy: " + formatGold(price)))
                        .addLine(colorTag(gold >= price ? COLOR_GRAY : COLOR_RED,
                                "Your Gold: " + formatGold(gold)))
                        .build();
            }
        }

        // 2. Any sellable item (always shows sell value for items in the registry)
        if (priceRegistry.isSellable(itemId)) {
            int dungeonLevel = extractDungeonLevel(metadata);
            long sellPrice = priceRegistry.getSellPrice(itemId, dungeonLevel);
            return TooltipData.builder()
                    .hashInput("merch_sell:" + sellPrice)
                    .addLine(colorTag(COLOR_GREEN, "Sell: " + formatGold(sellPrice)))
                    .build();
        }

        return null;
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /**
     * Extracts a long value from a metadata string containing BSON-like key-value pairs.
     *
     * <p>Metadata strings from {@link com.hypixel.hytale.server.core.inventory.ItemStack#withMetadata}
     * are serialized BSON documents. For simple numeric values stored via {@code Codec.LONG},
     * the value appears as a JSON number after the key.
     *
     * @param metadata the metadata string
     * @param key      the key to extract
     * @return the extracted value, or {@code null} if not found
     */
    @Nullable
    private static Long extractLong(@Nonnull String metadata, @Nonnull String key) {
        int keyIndex = metadata.indexOf(key);
        if (keyIndex < 0) {
            return null;
        }

        // Find the start of the numeric value after the key
        int afterKey = keyIndex + key.length();
        int numStart = -1;
        for (int i = afterKey; i < metadata.length(); i++) {
            char c = metadata.charAt(i);
            if (c == '-' || (c >= '0' && c <= '9')) {
                numStart = i;
                break;
            }
            // Skip delimiters: whitespace, colons, quotes, commas
            if (c != ':' && c != '"' && c != ',' && c != ' ' && c != '{' && c != '}') {
                // Unexpected character — might be nested key collision
                break;
            }
        }

        if (numStart < 0) {
            return null;
        }

        // Read digits
        int numEnd = numStart;
        for (int i = numStart; i < metadata.length(); i++) {
            char c = metadata.charAt(i);
            if (c == '-' || (c >= '0' && c <= '9')) {
                numEnd = i + 1;
            } else {
                break;
            }
        }

        try {
            return Long.parseLong(metadata.substring(numStart, numEnd));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Wraps text in a Hytale color tag.
     *
     * @param color the hex color (e.g. {@code "#FFD700"})
     * @param text  the text to wrap
     * @return the color-tagged string
     */
    @Nonnull
    private static String colorTag(@Nonnull String color, @Nonnull String text) {
        return "<color is=\"" + color + "\">" + text + "</color>";
    }

    /**
     * Extracts the dungeon level from item metadata (weapon or armor level).
     *
     * @param metadata the raw metadata string
     * @return the dungeon level, or {@code 0} if not found
     */
    private static int extractDungeonLevel(@Nullable String metadata) {
        if (metadata == null) {
            return 0;
        }
        Long weaponLevel = extractLong(metadata, "duntale_weapon_level");
        if (weaponLevel != null && weaponLevel > 0) {
            return weaponLevel.intValue();
        }
        Long armorLevel = extractLong(metadata, "duntale_armor_level");
        if (armorLevel != null && armorLevel > 0) {
            return armorLevel.intValue();
        }
        return 0;
    }

    /**
     * Formats a gold amount with comma separators.
     *
     * @param gold the gold amount
     * @return the formatted string (e.g. {@code "12,345 Gold"})
     */
    @Nonnull
    private static String formatGold(long gold) {
        return String.format("%,d Gold", gold);
    }
}
