package com.duntale.merchant;

import com.duntale.loot.Rarity;
import com.duntale.loot.RarityRegistry;
import com.duntale.progression.AssetCatalog;
import com.duntale.progression.CombatScaling;
import com.duntale.progression.GearCurveRegistry;
import com.duntale.progression.PricingRegistry;
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
 * <p>Prices are derived from a single combat-value axis at the requested dungeon level. With the
 * authored {@link GearCurveRegistry} curves loaded, weapon value is the family per-hit anchor (a
 * DPS-equivalent) and armor value is an effective-HP equivalent, so weapon and armor prices share
 * one scale instead of the old weapon-unbounded / armor-capped asymmetry. Without the curves the
 * model degrades to the legacy asset-stat scoring:
 * <pre>
 * weapon combatValue = anchor(family) × CombatScaling.weaponMult(level)
 * armor combatValue  = hpShare·hpBudget(level) + k_dr·drShare·drBudget(level) / (1 − totalDR(level))
 * buyPrice           = max(minBuyPrice, gold(combatValue))   gold(v) = round(v^exponent × scale)
 * legacy fallback    = baseDamage × weaponMult(level)  /  avgEffectiveDR + healthBonus
 * sellPrice          = floor(buyPrice × SELL_RATIO)
 * </pre>
 *
 * <p>The gold-mapping scale/exponent, minimum buy price, and armor effective-HP DR weight come from
 * the {@link PricingRegistry} when its asset is loaded, falling back to the constants here otherwise.
 *
 * <p>Initialise once at plugin startup via {@link #initialize(AssetCatalog)}.
 * Base item-level filtering remains available for merchant catalog selection,
 * while level-specific price lookups are computed on demand from cached item
 * stat profiles.
 */
public class MerchantPriceRegistry {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private enum PricingCategory {
        WEAPON,
        ARMOR
    }

    private record PriceProfile(
            @Nonnull PricingCategory category,
            int itemLevel,
            @Nullable String quality,
            @Nullable String family,
            @Nullable String slot,
            float baseDamage,
            float physResist,
            float projResist,
            int healthBonus
    ) {}

    /** Sell price as a fraction of buy price (50% = 50% gold sink). */
    static final double SELL_RATIO = 0.50;

    /** Minimum buy price to avoid near-zero gear prices (fallback when no pricing asset is loaded). */
    private static final long MIN_BUY_PRICE = 25L;

    /** Default weight of the armor effective-HP DR term (fallback when no pricing asset is loaded). */
    private static final double DEFAULT_ARMOR_EHP_DR_WEIGHT = 1.0;

    /** Representative on-curve gear item used to anchor {@link #referenceGearValue(int, Rarity)}. */
    private static final String REFERENCE_GEAR_ITEM = "Weapon_Sword_Iron";

    /** Representative base damage used when the reference gear item is not priced. */
    private static final float REFERENCE_BASE_DAMAGE = 30f;

    /** Converts an effective combat score into a gold price. */
    private static final double SCORE_TO_GOLD_SCALE = 10.0;

    /** Curves stronger items upward without letting tier alone dominate pricing. */
    private static final double SCORE_TO_GOLD_EXPONENT = 1.4;

    /** Weighs effective DR percentage when scoring armor. */
    private static final double ARMOR_RESIST_SCORE_WEIGHT = 3.0;

    /** Weighs flat health bonuses when scoring armor. */
    private static final double ARMOR_HEALTH_SCORE_WEIGHT = 0.9;

    /** Softens quality impact for utility items with missing runtime damage stats. */
    private static final double QUALITY_FALLBACK_EXPONENT = 0.5;

    private final Map<String, Long> buyPriceCache = new ConcurrentHashMap<>();
    private final Map<String, Integer> itemLevelCache = new ConcurrentHashMap<>();
    private final Map<String, PriceProfile> priceProfiles = new ConcurrentHashMap<>();

    /**
     * Fixed unit buy prices for authored custom items. These are level-independent
     * and resold at {@link #SELL_RATIO}. Kept separate from the stat-derived gear
     * caches so {@link #initialize(AssetCatalog)} never clears them.
     */
    private final Map<String, Long> customBuyPrices = new ConcurrentHashMap<>();

    /** Rarity tuning for the price multiplier; {@code null} until wired (multiplier resolves to 1.0). */
    @Nullable
    private RarityRegistry rarityRegistry;

    /**
     * Authored gear curves driving the combat-value price axis; {@code null}/unloaded falls back to
     * the legacy asset-stat scoring path so behavior is unchanged.
     */
    @Nullable
    private GearCurveRegistry gearCurveRegistry;

    /**
     * Reconciled gold-mapping tuning; {@code null}/unloaded falls back to the hard-coded scale,
     * exponent, minimum buy price, and armor effective-HP DR weight constants.
     */
    @Nullable
    private PricingRegistry pricingRegistry;

    /**
     * Wires the rarity registry used by {@link #rarityPriceMult(Rarity)} and
     * {@link #referenceGearValue(int, Rarity)}. Until set, the multiplier resolves to {@code 1.0}.
     *
     * @param rarityRegistry the rarity registry
     */
    public void setRarityRegistry(@Nonnull RarityRegistry rarityRegistry) {
        this.rarityRegistry = rarityRegistry;
    }

    /**
     * Wires the authored gear-curve registry that drives the combat-value price axis. Until set (or
     * while its asset is unloaded), pricing uses the legacy asset-stat scoring path.
     *
     * @param gearCurveRegistry the gear-curve registry
     */
    public void setGearCurveRegistry(@Nonnull GearCurveRegistry gearCurveRegistry) {
        this.gearCurveRegistry = gearCurveRegistry;
    }

    /**
     * Wires the pricing registry supplying the gold-mapping scale/exponent, minimum buy price, and
     * armor effective-HP DR weight. Until set (or while its asset is unloaded), the hard-coded
     * constants apply.
     *
     * @param pricingRegistry the pricing registry
     */
    public void setPricingRegistry(@Nonnull PricingRegistry pricingRegistry) {
        this.pricingRegistry = pricingRegistry;
    }

    private boolean combatValuePricingActive() {
        return gearCurveRegistry != null && gearCurveRegistry.isLoaded();
    }

    private boolean pricingAssetLoaded() {
        return pricingRegistry != null && pricingRegistry.isLoaded();
    }

    private double goldMappingScale() {
        return pricingAssetLoaded() ? pricingRegistry.goldMappingScale() : SCORE_TO_GOLD_SCALE;
    }

    private double goldMappingExponent() {
        return pricingAssetLoaded() ? pricingRegistry.goldMappingExponent() : SCORE_TO_GOLD_EXPONENT;
    }

    private long minBuyPrice() {
        return pricingAssetLoaded() ? pricingRegistry.minBuyPrice() : MIN_BUY_PRICE;
    }

    private double armorEhpDrWeight() {
        return pricingAssetLoaded() ? pricingRegistry.armorEhpDrWeight() : DEFAULT_ARMOR_EHP_DR_WEIGHT;
    }

    /**
     * Returns the merchant price multiplier for a rarity tier.
     *
     * @param rarity the rarity, or {@code null} for unstamped (Common-equivalent) gear
     * @return the price multiplier, or {@code 1.0} when no rarity registry is wired/loaded
     */
    public float rarityPriceMult(@Nullable Rarity rarity) {
        return rarityRegistry != null ? rarityRegistry.priceMult(rarity) : 1.0f;
    }

    /**
     * Returns a representative on-level gear value at a rarity, used to size the boss gold reward.
     *
     * <p>Anchors on a canonical on-curve weapon priced at the requested level, scaled by the rarity
     * price multiplier. Falls back to a level-only weapon curve when the reference item is not yet
     * priced (e.g. before the registry is initialised). This is a draft definition pending the
     * holistic combat-value repricing.
     *
     * @param level  the on-level gear level
     * @param rarity the rarity to price at, or {@code null} for Common-equivalent
     * @return the reference gear value in gold
     */
    public long referenceGearValue(int level, @Nullable Rarity rarity) {
        long base = getBuyPrice(REFERENCE_GEAR_ITEM, level);
        if (base <= 0L) {
            double score = REFERENCE_BASE_DAMAGE * CombatScaling.weaponMult(CombatScaling.clampLevel(level));
            base = Math.max(minBuyPrice(),
                    Math.round(Math.pow(Math.max(1.0, score), goldMappingExponent()) * goldMappingScale()));
        }
        return Math.round(base * rarityPriceMult(rarity));
    }

    /**
     * Populates the price cache from all weapons and armor in the asset catalog.
     *
     * @param assetCatalog the asset catalog (must already be initialised)
     */
    public void initialize(@Nonnull AssetCatalog assetCatalog) {
        int weaponCount = 0;
        int armorCount = 0;

        buyPriceCache.clear();
        itemLevelCache.clear();
        priceProfiles.clear();

        // Load all weapons (limit 500 to cover the full catalog)
        for (AssetCatalog.WeaponBaseRow row : assetCatalog.listWeapons("name", true, 500, null)) {
            if (isExcluded(row.quality()) || isNpcItem(row.name())) {
                continue;
            }
            String assetId = toAssetId(row.name());
            PriceProfile profile = new PriceProfile(
                    PricingCategory.WEAPON,
                    row.itemLevel(),
                    row.quality(),
                    row.family(),
                    null,
                    row.baseDamage(),
                    0f,
                    0f,
                    0
            );
            priceProfiles.put(assetId, profile);
            buyPriceCache.put(assetId, computeBuyPrice(profile, defaultPricingLevel(profile)));
            if (!assetId.startsWith("Tool_")) {
                itemLevelCache.put(assetId, row.itemLevel());
            }
            weaponCount++;
        }

        // Load all armor
        for (AssetCatalog.ArmorBaseRow row : assetCatalog.listArmor("name", true, 500, null)) {
            if (isExcluded(row.quality()) || isNpcItem(row.name())) {
                continue;
            }
            String assetId = toAssetId(row.name());
            PriceProfile profile = new PriceProfile(
                    PricingCategory.ARMOR,
                    row.itemLevel(),
                    row.quality(),
                    null,
                    row.slot(),
                    0f,
                    row.physResist(),
                    row.projResist(),
                    row.healthBonus()
            );
            priceProfiles.put(assetId, profile);
            buyPriceCache.put(assetId, computeBuyPrice(profile, defaultPricingLevel(profile)));
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
     * @return the buy price in gold using the item's base asset level, or {@code 0} if the item is not in the registry
     */
    public long getBuyPrice(@Nonnull String itemId) {
        Long price = buyPriceCache.get(itemId);
        return price != null ? price : 0L;
    }

    /**
     * Returns the buy price for the given item at the provided dungeon level.
     *
     * <p>Merchant gear is stamped with its own dungeon level, so level-aware
     * pricing must use that stamped level rather than the asset's built-in tier.
     * When {@code dungeonLevel <= 0}, the item's base asset level is used.
     *
     * @param itemId       the item asset ID
     * @param dungeonLevel the dungeon level stamped on the item, or {@code 0} to price at the asset's base level
     * @return the buy price in gold, or {@code 0} if the item is not in the registry
     */
    public long getBuyPrice(@Nonnull String itemId, int dungeonLevel) {
        PriceProfile profile = priceProfiles.get(itemId);
        if (profile == null) {
            return 0L;
        }
        return computeBuyPrice(profile, resolvePricingLevel(profile, dungeonLevel));
    }

    /**
     * Returns the sell price for the given item ({@link #SELL_RATIO} of buy price).
     *
     * @param itemId the item asset ID
     * @return the sell price in gold, or {@code 0} if the item is not in the registry
     */
    public long getSellPrice(@Nonnull String itemId) {
        Long customBuy = customBuyPrices.get(itemId);
        if (customBuy != null) {
            return (long) Math.floor(customBuy * SELL_RATIO);
        }
        long buyPrice = getBuyPrice(itemId);
        return buyPrice > 0 ? (long) Math.floor(buyPrice * SELL_RATIO) : 0L;
    }

    /**
     * Returns the sell price adjusted for the item's dungeon level.
     *
     * <p>Sell price is derived from the same level-aware buy price used by merchants,
     * which keeps buy and sell values aligned for stamped dungeon gear.
     *
     * @param itemId       the item asset ID
     * @param dungeonLevel the dungeon level from item metadata, or {@code 0} for base price
     * @return the level-adjusted sell price in gold
     */
    public long getSellPrice(@Nonnull String itemId, int dungeonLevel) {
        Long customBuy = customBuyPrices.get(itemId);
        if (customBuy != null) {
            // Custom items use a fixed, level-independent price.
            return (long) Math.floor(customBuy * SELL_RATIO);
        }
        long buyPrice = getBuyPrice(itemId, dungeonLevel);
        return buyPrice > 0 ? (long) Math.floor(buyPrice * SELL_RATIO) : 0L;
    }

    /**
     * Returns whether the given item exists in the price registry and can be sold.
     *
     * @param itemId the item asset ID
     * @return {@code true} if the item is sellable
     */
    public boolean isSellable(@Nonnull String itemId) {
        return priceProfiles.containsKey(itemId) || customBuyPrices.containsKey(itemId);
    }

    /**
     * Registers a fixed unit buy price for an authored custom item, making it
     * sellable at {@link #SELL_RATIO} of that price regardless of dungeon level.
     *
     * @param itemId   the custom item asset ID
     * @param buyPrice the unit buy price in gold
     */
    public void registerCustomItem(@Nonnull String itemId, long buyPrice) {
        customBuyPrices.put(itemId, buyPrice);
    }

    /**
     * Returns whether the given item is an authored, fixed-price custom item.
     *
     * <p>Custom items are sold per unit at a fixed price, so a sold stack should be
     * credited at the per-unit sell price multiplied by its quantity.
     *
     * @param itemId the item asset ID
     * @return {@code true} if the item was registered via {@link #registerCustomItem(String, long)}
     */
    public boolean isCustomItem(@Nonnull String itemId) {
        return customBuyPrices.containsKey(itemId);
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
        return priceProfiles.size();
    }

    /**
     * Returns an unmodifiable view of all item IDs in the price registry.
     *
     * @return the set of priced item IDs
     */
    @Nonnull
    public Set<String> getItemIds() {
        return Set.copyOf(priceProfiles.keySet());
    }

    // ── Price computation ────────────────────────────────────────────

    private long computeBuyPrice(@Nonnull PriceProfile profile, int pricingLevel) {
        double score = switch (profile.category()) {
            case WEAPON -> computeWeaponScore(profile, pricingLevel);
            case ARMOR -> computeArmorScore(profile, pricingLevel);
        };
        double boundedScore = Math.max(1.0, score);
        long computed = Math.round(Math.pow(boundedScore, goldMappingExponent()) * goldMappingScale());
        return Math.max(minBuyPrice(), computed);
    }

    /**
     * Scores a weapon's combat value. With authored curves loaded this is the family per-hit anchor
     * scaled by the level curve (a DPS-equivalent — melee cadence is a single Agility throttle, so
     * per-hit is proportional to DPS), which normalizes wildly authored asset damage to one axis.
     * Without curves it degrades to the legacy asset-damage path.
     */
    private double computeWeaponScore(@Nonnull PriceProfile profile, int pricingLevel) {
        if (combatValuePricingActive() && profile.family() != null) {
            float anchor = gearCurveRegistry.weaponAnchor(profile.family());
            if (anchor > 0f) {
                return anchor * CombatScaling.weaponMult(pricingLevel);
            }
        }

        if (profile.baseDamage() > 0f) {
            return profile.baseDamage() * CombatScaling.weaponMult(pricingLevel);
        }

        return computeFallbackTierScore(profile.itemLevel(), profile.quality())
                * zeroStatWeaponFamilyMultiplier(profile.family());
    }

    /**
     * Scores an armor piece's combat value. With authored curves loaded this is an effective-HP
     * equivalent: the slot's flat-HP share plus its damage-reduction share leveraged by the
     * full-set effective-HP multiplier {@code 1 / (1 - totalDR)}, so armor value keeps climbing with
     * the DR budget instead of plateauing at the combined cap. Without curves it degrades to the
     * legacy resist-percent + health path.
     */
    private double computeArmorScore(@Nonnull PriceProfile profile, int pricingLevel) {
        double combatValue = computeArmorCombatValue(profile.slot(), pricingLevel);
        if (combatValue > 0.0) {
            return combatValue;
        }

        double physDr = profile.physResist() > 0f ? CombatScaling.armorDR(profile.physResist(), pricingLevel) : 0.0;
        double projDr = profile.projResist() > 0f ? CombatScaling.armorDR(profile.projResist(), pricingLevel) : 0.0;

        int resistSources = 0;
        if (physDr > 0.0) {
            resistSources++;
        }
        if (projDr > 0.0) {
            resistSources++;
        }

        double avgResistPercent = resistSources > 0
                ? ((physDr + projDr) / resistSources) * 100.0
                : 0.0;
        double healthScore = Math.max(0, profile.healthBonus()) * ARMOR_HEALTH_SCORE_WEIGHT;
        double score = avgResistPercent * ARMOR_RESIST_SCORE_WEIGHT + healthScore;
        if (score > 0.0) {
            return score;
        }

        return computeFallbackTierScore(profile.itemLevel(), profile.quality())
                * armorFallbackSlotMultiplier(profile.slot());
    }

    /**
     * Computes an armor slot's effective-HP combat value from the authored curves, or {@code 0} when
     * the curves are unloaded or the slot is unmapped (legacy fallback applies).
     */
    private double computeArmorCombatValue(@Nullable String slot, int pricingLevel) {
        if (!combatValuePricingActive() || slot == null || !gearCurveRegistry.hasArmorHp()) {
            return 0.0;
        }
        Float hpShare = gearCurveRegistry.armorHpShare(slot);
        Float drShare = gearCurveRegistry.slotShare(slot);
        if (hpShare == null || drShare == null) {
            return 0.0;
        }
        double hpTerm = CombatScaling.armorBudgetHp(
                hpShare, pricingLevel, gearCurveRegistry.hpBudgetMin(), gearCurveRegistry.hpBudgetMax());
        double slotDr = CombatScaling.armorBudgetDR(
                drShare, pricingLevel, gearCurveRegistry.drBudgetMin(), gearCurveRegistry.drBudgetMax());
        // Effective-HP leverage uses the full on-level set's total DR, capped at the combined limit.
        double totalDr = Math.min(CombatScaling.MAX_ARMOR_DR, CombatScaling.armorBudgetDR(
                1.0f, pricingLevel, gearCurveRegistry.drBudgetMin(), gearCurveRegistry.drBudgetMax()));
        double ehpDrTerm = armorEhpDrWeight() * slotDr / (1.0 - totalDr);
        return hpTerm + ehpDrTerm;
    }

    private static int defaultPricingLevel(@Nonnull PriceProfile profile) {
        return resolvePricingLevel(profile, 0);
    }

    private static int resolvePricingLevel(@Nonnull PriceProfile profile, int requestedLevel) {
        int baseLevel = profile.itemLevel() > 0 ? profile.itemLevel() : 1;
        int rawLevel = requestedLevel > 0 ? requestedLevel : baseLevel;
        return CombatScaling.clampLevel(rawLevel);
    }

    private static double computeFallbackTierScore(int itemLevel, @Nullable String quality) {
        int boundedLevel = Math.max(1, itemLevel);
        double qualityFactor = Math.pow(qualityCoefficient(quality), QUALITY_FALLBACK_EXPONENT);
        return boundedLevel * qualityFactor;
    }

    private static double zeroStatWeaponFamilyMultiplier(@Nullable String family) {
        if (family == null) {
            return 1.0;
        }
        return switch (family) {
            case "Bow", "Shortbow", "Crossbow", "Handgun", "Rifle" -> 1.20;
            case "Staff", "Wand", "Spellbook" -> 1.15;
            case "Shield" -> 1.05;
            case "Torch", "Fire" -> 0.90;
            default -> 1.0;
        };
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

    private static double armorFallbackSlotMultiplier(@Nullable String slot) {
        if (slot == null) {
            return 1.0;
        }
        return switch (slot) {
            case "Chest" -> 1.0;
            case "Legs" -> 0.85;
            case "Head" -> 0.75;
            case "Hands" -> 0.65;
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
