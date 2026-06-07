package com.duntale.merchant;

import com.duntale.ThirdPartyModAvailabilityService;
import com.duntale.items.CustomItems;
import com.duntale.progression.CombatScaling;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.common.plugin.PluginIdentifier;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Generates deterministic merchant catalogs based on dungeon floor level.
 *
 * <p>A catalog consists of {@value #GEAR_SLOTS} gear items distributed across
 * 5 level tiers (weighted by floor proximity) plus {@value #CONSUMABLE_SLOTS}
 * consumable items (1 guaranteed health potion + 3 random from pool) and up to
 * {@value #RESERVED_SCROLL_SLOTS} reserved enchant-scroll slot when
 * SimpleEnchantments is available.
 *
 * @see CatalogEntry
 * @see MerchantPriceRegistry
 */
public class CatalogGenerator {

    private static final PluginIdentifier SIMPLE_ENCHANTMENTS_PLUGIN =
            new PluginIdentifier("org.herolias", "SimpleEnchantments");
    private static final String SIMPLE_ENCHANTMENTS_SCROLL_SENTINEL_ITEM_ID = "Scroll_Cleansing";

    /** Number of gear buy slots per merchant. */
    static final int GEAR_SLOTS = 21;

    /** Number of consumable buy slots per merchant. */
    static final int CONSUMABLE_SLOTS = 4;

    /** Additional reserved buy slots that only roll enchant scrolls. */
    static final int RESERVED_SCROLL_SLOTS = 1;

    /** Maximum total buy-zone slots. */
    static final int MAX_BUY_SLOTS = GEAR_SLOTS + CONSUMABLE_SLOTS + RESERVED_SCROLL_SLOTS;

    /** Highest real base item level currently represented by available weapon assets. */
    private static final int MAX_MERCHANT_BASE_ITEM_LEVEL = 75;

    /** Tier level ranges: [minLevel, maxLevel] for each of 5 tiers. */
    private static final int[][] TIER_RANGES = buildTierRanges(0, MAX_MERCHANT_BASE_ITEM_LEVEL, 5);

    /** Central level for each tier — used for weight calculation. */
    private static final int[] TIER_CENTERS = buildTierCenters(TIER_RANGES);

    /** Weight curve peak value. */
    private static final double WEIGHT_PEAK = 10.0;

    /** Weight decay per level of distance from tier center. */
    private static final double WEIGHT_DECAY = 0.3;

    /** Minimum weight to ensure all tiers get at least 1 slot. */
    private static final double WEIGHT_FLOOR = 0.5;

    /** Floor level at which the guaranteed health potion upgrades. */
    private static final int HEALTH_POTION_UPGRADE_FLOOR = 20;

    /**
     * A consumable offer definition.
     *
     * @param itemId           the engine item ID to offer
     * @param unitPrice        the per-unit price for the offer
     * @param fixedQuantity    optional fixed quantity override; {@code 0} means offer a full
     *                         max stack and multiply the price by that resolved stack size
     */
    record ConsumableDef(@Nonnull String itemId, long unitPrice, int fixedQuantity) {
        private ConsumableDef(@Nonnull String itemId, long unitPrice) {
            this(itemId, unitPrice, 0);
        }
    }

    private static final ConsumableDef HEALTH_POTION_LOW = new ConsumableDef("Potion_Health", 50);
    private static final ConsumableDef HEALTH_POTION_HIGH = new ConsumableDef("Potion_Health_Greater", 150);

    static final ConsumableDef[] CONSUMABLE_POOL = {
            new ConsumableDef("Weapon_Arrow_Crude", 5),
            new ConsumableDef("Weapon_Arrow_Iron", 15),
            new ConsumableDef("Weapon_Arrow_Deadeye", 30),
            new ConsumableDef("Weapon_Arrow_Clearshot", 45),
            new ConsumableDef("Weapon_Arrow_Trueshot", 60),
            new ConsumableDef("Food_Kebab_Meat", 25),
            new ConsumableDef("Food_Pie_Meat", 50),
            new ConsumableDef("Potion_Stamina", 75),
            new ConsumableDef("Potion_Regen_Health", 100),
            new ConsumableDef("Potion_Antidote", 40),
            new ConsumableDef("Tool_Repair_Kit_Iron", 25_000, 1),
            new ConsumableDef("Tool_Repair_Kit_Iron", 25_000, 5),
            new ConsumableDef("Weapon_Deployable_Turret", 120_000, 1),
            new ConsumableDef("Weapon_Deployable_Healing_Totem", 125_000, 1),
            new ConsumableDef("Weapon_Deployable_Slowness_Totem", 100_000, 1),
            new ConsumableDef("Upgrade_Backpack_1", 25_000, 1),
            new ConsumableDef("Upgrade_Backpack_2", 50_000, 1),
            new ConsumableDef("Upgrade_Backpack_3", 75_000, 1),
            // ── Custom utility items (prices sourced from CustomItems.BUY_PRICES) ──
            new ConsumableDef(CustomItems.IMMUNITY_TRAP_RING, CustomItems.BUY_PRICES.get(CustomItems.IMMUNITY_TRAP_RING), 1),
            new ConsumableDef(CustomItems.SPEED_BOOTS_I, CustomItems.BUY_PRICES.get(CustomItems.SPEED_BOOTS_I), 1),
            new ConsumableDef(CustomItems.SPEED_BOOTS_II, CustomItems.BUY_PRICES.get(CustomItems.SPEED_BOOTS_II), 1),
            new ConsumableDef(CustomItems.SPEED_BOOTS_III, CustomItems.BUY_PRICES.get(CustomItems.SPEED_BOOTS_III), 1),
            new ConsumableDef(CustomItems.HEALING_NECKLACE_I, CustomItems.BUY_PRICES.get(CustomItems.HEALING_NECKLACE_I), 1),
            new ConsumableDef(CustomItems.HEALING_NECKLACE_II, CustomItems.BUY_PRICES.get(CustomItems.HEALING_NECKLACE_II), 1),
            new ConsumableDef(CustomItems.VAMPIRE_JUICE, CustomItems.BUY_PRICES.get(CustomItems.VAMPIRE_JUICE), 1),
            // +1 Stat Point token offered in stacks of 1 / 5 / 10
            new ConsumableDef(CustomItems.STAT_POINT_TOKEN, CustomItems.BUY_PRICES.get(CustomItems.STAT_POINT_TOKEN), 1),
            new ConsumableDef(CustomItems.STAT_POINT_TOKEN, CustomItems.BUY_PRICES.get(CustomItems.STAT_POINT_TOKEN), 5),
            new ConsumableDef(CustomItems.STAT_POINT_TOKEN, CustomItems.BUY_PRICES.get(CustomItems.STAT_POINT_TOKEN), 10),
    };

        /** Full SimpleEnchantments scroll catalog offered in the reserved scroll slot. */
    static final List<String> RESERVED_SCROLL_ITEM_IDS = List.of(
            "Scroll_Absorption_I",
            "Scroll_Absorption_II",
            "Scroll_Absorption_III",
            "Scroll_Burn_I",
            "Scroll_Cleansing",
            "Scroll_Coup_De_Grace_I",
            "Scroll_Coup_De_Grace_II",
            "Scroll_Coup_De_Grace_III",
            "Scroll_Custom",
            "Scroll_Dexterity_I",
            "Scroll_Dexterity_II",
            "Scroll_Dexterity_III",
            "Scroll_Durability_I",
            "Scroll_Durability_II",
            "Scroll_Durability_III",
            "Scroll_Eagles_Eye_I",
            "Scroll_Eagles_Eye_II",
            "Scroll_Eagles_Eye_III",
            "Scroll_ElementalHeart_I",
            "Scroll_Environmental_Protection_I",
            "Scroll_Environmental_Protection_II",
            "Scroll_Environmental_Protection_III",
            "Scroll_Eternal_Shot_I",
            "Scroll_Freeze_I",
            "Scroll_Frenzy_I",
            "Scroll_Frenzy_II",
            "Scroll_Frenzy_III",
            "Scroll_Knockback_I",
            "Scroll_Knockback_II",
            "Scroll_Knockback_III",
            "Scroll_Life_Leech_I",
            "Scroll_Night_Vision_I",
            "Scroll_Poison_I",
            "Scroll_Protection_I",
            "Scroll_Protection_II",
            "Scroll_Protection_III",
            "Scroll_Ranged_Protection_I",
            "Scroll_Ranged_Protection_II",
            "Scroll_Ranged_Protection_III",
            "Scroll_Reflection_I",
            "Scroll_Reflection_II",
            "Scroll_Reflection_III",
            "Scroll_Regeneration_I",
            "Scroll_Riposte_I",
            "Scroll_Riposte_II",
            "Scroll_Riposte_III",
            "Scroll_Second_Stomach_I",
            "Scroll_Second_Stomach_II",
            "Scroll_Second_Stomach_III",
            "Scroll_Sharpness_I",
            "Scroll_Sharpness_II",
            "Scroll_Sharpness_III",
            "Scroll_Strength_I",
            "Scroll_Strength_II",
            "Scroll_Strength_III",
            "Scroll_Sturdy_I",
            "Scroll_Thrift_I",
            "Scroll_Thrift_II",
            "Scroll_Thrift_III"
    );

    private static final ConsumableDef[] SCROLL_POOL = RESERVED_SCROLL_ITEM_IDS.stream()
            .map(CatalogGenerator::toScrollOffer)
            .toArray(ConsumableDef[]::new);

    private final MerchantPriceRegistry priceRegistry;
    private final ThirdPartyModAvailabilityService thirdPartyModAvailabilityService;

    /**
     * Creates a new catalog generator.
     *
     * @param priceRegistry                  the price registry for item pricing and level lookups
     * @param thirdPartyModAvailabilityService reports whether third-party mod integrations are available
     */
    public CatalogGenerator(@Nonnull MerchantPriceRegistry priceRegistry,
                            @Nonnull ThirdPartyModAvailabilityService thirdPartyModAvailabilityService) {
        this.priceRegistry = priceRegistry;
        this.thirdPartyModAvailabilityService = thirdPartyModAvailabilityService;
    }

    /**
     * Generates a deterministic merchant catalog for the given floor level.
     *
     * <p>Each gear item receives a random level in {@code [floorLevel - 4, floorLevel + 10]}
        * and its price is computed directly from that stamped level.
     *
     * @param floorLevel the dungeon floor level
     * @param seed       seed for deterministic randomisation
     * @return an immutable catalog
     */
    @Nonnull
    public List<CatalogEntry> generate(int floorLevel, long seed) {
        Random random = new Random(seed);
        List<CatalogEntry> catalog = new ArrayList<>(MAX_BUY_SLOTS);

        // ── Gear items ───────────────────────────────────────────────
        int[] tierSlots = computeTierSlots(floorLevel);

        for (int tier = 0; tier < TIER_RANGES.length; tier++) {
            int count = tierSlots[tier];
            if (count <= 0) continue;

            List<String> candidates = priceRegistry.getItemsByLevelRange(
                    TIER_RANGES[tier][0], TIER_RANGES[tier][1]);
            if (candidates.isEmpty()) continue;

            Collections.shuffle(candidates, random);
            for (int i = 0; i < count && i < candidates.size(); i++) {
                String itemId = candidates.get(i);

                // Random gear level in [floorLevel - 4, floorLevel + 10]
                int gearLevel = CombatScaling.clampLevel(floorLevel - 4 + random.nextInt(15));

                long levelPrice = priceRegistry.getBuyPrice(itemId, gearLevel);
                catalog.add(CatalogEntry.gear(itemId, gearLevel, levelPrice));
            }
        }

        // Sort gear: level ascending, then price ascending
        catalog.sort(Comparator
                .comparingInt(CatalogEntry::level)
                .thenComparingLong(CatalogEntry::buyPrice));

        // ── Consumable items ─────────────────────────────────────────
        // Guaranteed health potion
        ConsumableDef healthPotion = floorLevel >= HEALTH_POTION_UPGRADE_FLOOR
                ? HEALTH_POTION_HIGH : HEALTH_POTION_LOW;
        catalog.add(toConsumableEntry(healthPotion));

        // 3 random consumables from the standard pool
        List<ConsumableDef> pool = new ArrayList<>(List.of(CONSUMABLE_POOL));
        Collections.shuffle(pool, random);
        for (int i = 0; i < CONSUMABLE_SLOTS - 1 && i < pool.size(); i++) {
            catalog.add(toConsumableEntry(pool.get(i)));
        }

        if (thirdPartyModAvailabilityService.isAvailable(
            SIMPLE_ENCHANTMENTS_PLUGIN,
            SIMPLE_ENCHANTMENTS_SCROLL_SENTINEL_ITEM_ID
        )) {
            // Reserve one extra slot for enchant scrolls only when the mod is present.
            List<ConsumableDef> scrollPool = new ArrayList<>(List.of(SCROLL_POOL));
            Collections.shuffle(scrollPool, random);
            for (int i = 0; i < RESERVED_SCROLL_SLOTS && i < scrollPool.size(); i++) {
                catalog.add(toConsumableEntry(scrollPool.get(i)));
            }
        }

        return List.copyOf(catalog);
    }

    /**
    * Builds a consumable catalog entry. By default the item is offered as a full
    * stack, resolving the item's max stack size and scaling the per-unit price by
    * that quantity. Definitions with a fixed quantity override bypass the max-stack
    * lookup and instead use the configured quantity directly.
     *
     * @param def the consumable definition (item ID, price, and stacking behaviour)
     * @return a catalog entry with the resolved quantity and price
     */
    @Nonnull
    private static CatalogEntry toConsumableEntry(@Nonnull ConsumableDef def) {
        if (def.fixedQuantity() > 0) {
            return CatalogEntry.consumable(def.itemId(), def.unitPrice() * def.fixedQuantity(), def.fixedQuantity());
        }
        Item item = null;
        var assetStore = Item.getAssetStore();
        if (assetStore != null && assetStore.getAssetMap() != null) {
            item = Item.getAssetMap().getAsset(def.itemId());
        }
        int maxStack = item != null ? Math.max(1, item.getMaxStack()) : 1;
        long stackPrice = def.unitPrice() * maxStack;
        return CatalogEntry.consumable(def.itemId(), stackPrice, maxStack);
    }

    @Nonnull
    private static ConsumableDef toScrollOffer(@Nonnull String itemId) {
        return new ConsumableDef(itemId, scrollUnitPrice(itemId), 1);
    }

    private static long scrollUnitPrice(@Nonnull String itemId) {
        if ("Scroll_Custom".equals(itemId)) {
            return 125_000L;
        }
        if (itemId.endsWith("_III")) {
            return 175_000L;
        }
        if (itemId.endsWith("_II")) {
            return 125_000L;
        }
        return 75_000L;
    }

    @Nonnull
    private static int[][] buildTierRanges(int minLevel, int maxLevel, int tierCount) {
        int[][] ranges = new int[tierCount][2];
        int previousMax = minLevel - 1;
        int span = maxLevel - minLevel;
        for (int index = 0; index < tierCount; index++) {
            int tierMax = minLevel + Math.round((float) span * (index + 1) / tierCount);
            ranges[index][0] = index == 0 ? minLevel : previousMax + 1;
            ranges[index][1] = tierMax;
            previousMax = tierMax;
        }
        return ranges;
    }

    @Nonnull
    private static int[] buildTierCenters(@Nonnull int[][] ranges) {
        int[] centers = new int[ranges.length];
        for (int index = 0; index < ranges.length; index++) {
            centers[index] = (ranges[index][0] + ranges[index][1]) / 2;
        }
        return centers;
    }

    /**
     * Computes how many gear slots each tier gets, weighted by proximity
     * to the floor level. All tiers get at least 1 slot.
     */
    private static int[] computeTierSlots(int floorLevel) {
        double[] weights = new double[TIER_CENTERS.length];
        for (int i = 0; i < TIER_CENTERS.length; i++) {
            double distance = Math.abs(floorLevel - TIER_CENTERS[i]);
            weights[i] = Math.max(WEIGHT_FLOOR, WEIGHT_PEAK - distance * WEIGHT_DECAY);
        }

        double total = 0;
        for (double w : weights) total += w;

        int[] slots = new int[TIER_CENTERS.length];
        int assigned = 0;
        for (int i = 0; i < slots.length; i++) {
            slots[i] = Math.max(1, (int) Math.round((double) GEAR_SLOTS * weights[i] / total));
            assigned += slots[i];
        }

        // Adjust to exactly GEAR_SLOTS by adding/removing from the dominant tier
        int maxIdx = 0;
        for (int i = 1; i < weights.length; i++) {
            if (weights[i] > weights[maxIdx]) maxIdx = i;
        }
        slots[maxIdx] += GEAR_SLOTS - assigned;

        return slots;
    }
}
