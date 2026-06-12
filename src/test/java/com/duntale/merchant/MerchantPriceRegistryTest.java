package com.duntale.merchant;

import com.duntale.ThirdPartyModAvailabilityService;
import com.duntale.items.CustomItems;
import com.duntale.progression.AssetCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MerchantPriceRegistry")
class MerchantPriceRegistryTest {

    @Nested
    @DisplayName("level-aware pricing")
    class LevelAwarePricing {

        @Test
        @DisplayName("Should price a higher power mace above a lower power sword at the same dungeon level")
        void shouldPriceHigherPowerMaceAboveLowerPowerSwordAtSameDungeonLevel() {
            MerchantPriceRegistry registry = initializedRegistry(
                    List.of(
                            new AssetCatalog.WeaponBaseRow("Weapon Sword Onyxium", "Sword", "Epic", 50, 44.6667f),
                            new AssetCatalog.WeaponBaseRow("Weapon Mace Cobalt", "Mace", "Rare", 35, 54.2857f)
                    ),
                    List.of()
            );

            long swordPrice = registry.getBuyPrice("Weapon_Sword_Onyxium", 12);
            long macePrice = registry.getBuyPrice("Weapon_Mace_Cobalt", 12);

            assertTrue(macePrice > swordPrice);
        }

        @Test
        @DisplayName("Should price stronger armor stats above weaker armor stats even at the same asset tier")
        void shouldPriceStrongerArmorStatsAboveWeakerArmorStatsAtSameAssetTier() {
            MerchantPriceRegistry registry = initializedRegistry(
                    List.of(),
                    List.of(
                            new AssetCatalog.ArmorBaseRow("Armor Onyxium Chest", "Chest", "Epic", 50, 0.09f, 0.09f, 17, null),
                            new AssetCatalog.ArmorBaseRow("Armor Mithril Chest", "Chest", "Epic", 50, 0.144f, 0.144f, 24, null)
                    )
            );

            long onyxPrice = registry.getBuyPrice("Armor_Onyxium_Chest", 12);
            long mithrilPrice = registry.getBuyPrice("Armor_Mithril_Chest", 12);

            assertTrue(mithrilPrice > onyxPrice);
        }

        @Test
        @DisplayName("Should keep utility weapons sellable via the fallback model")
        void shouldKeepUtilityWeaponsSellableViaTheFallbackModel() {
            MerchantPriceRegistry registry = initializedRegistry(
                    List.of(
                            new AssetCatalog.WeaponBaseRow("Weapon Shield Cobalt", "Shield", "Rare", 35, 0f)
                    ),
                    List.of()
            );

            long buyPrice = registry.getBuyPrice("Weapon_Shield_Cobalt", 12);
            long sellPrice = registry.getSellPrice("Weapon_Shield_Cobalt", 12);

            assertTrue(buyPrice > 0L);
            assertEquals((long) Math.floor(buyPrice * MerchantPriceRegistry.SELL_RATIO), sellPrice);
        }

        @Test
        @DisplayName("Should continue increasing merchant pricing above level 60 up to level 100")
        void shouldContinueIncreasingMerchantPricingAboveLevelSixtyUpToLevelOneHundred() {
            MerchantPriceRegistry registry = initializedRegistry(
                    List.of(
                            new AssetCatalog.WeaponBaseRow("Weapon Sword Onyxium", "Sword", "Epic", 45, 44.6667f)
                    ),
                    List.of()
            );

            long levelSixtyPrice = registry.getBuyPrice("Weapon_Sword_Onyxium", 60);
            long levelHundredPrice = registry.getBuyPrice("Weapon_Sword_Onyxium", 100);

            assertTrue(levelHundredPrice > levelSixtyPrice);
        }
    }

    @Nested
    @DisplayName("catalog generation")
    class CatalogGeneration {

        @Test
        @DisplayName("Should use the stamped gear level price directly in generated catalogs")
        void shouldUseStampedGearLevelPriceDirectlyInGeneratedCatalogs() {
            MerchantPriceRegistry registry = initializedRegistry(
                    List.of(
                            new AssetCatalog.WeaponBaseRow("Weapon Sword Copper", "Sword", "Uncommon", 5, 12f),
                            new AssetCatalog.WeaponBaseRow("Weapon Sword Iron", "Sword", "Common", 15, 18f),
                            new AssetCatalog.WeaponBaseRow("Weapon Sword Steel", "Sword", "Rare", 25, 28f),
                            new AssetCatalog.WeaponBaseRow("Weapon Sword Cobalt", "Sword", "Rare", 35, 36f),
                            new AssetCatalog.WeaponBaseRow("Weapon Sword Onyxium", "Sword", "Epic", 45, 44.6667f)
                    ),
                    List.of()
            );
                    CatalogGenerator generator = generator(registry, false);

            List<CatalogEntry> catalog = generator.generate(20, 42L);

            for (CatalogEntry entry : catalog) {
                if (entry.level() <= 0) {
                    continue;
                }
                assertEquals(registry.getBuyPrice(entry.itemId(), entry.level()), entry.buyPrice());
            }
        }

        @Test
        @DisplayName("Should include the highest currently supported merchant tier on late floors")
        void shouldIncludeHighestCurrentlySupportedMerchantTierOnLateFloors() {
            MerchantPriceRegistry registry = initializedRegistry(
                    List.of(
                            new AssetCatalog.WeaponBaseRow("Weapon Sword Copper", "Sword", "Uncommon", 5, 12f),
                            new AssetCatalog.WeaponBaseRow("Weapon Sword Iron", "Sword", "Common", 20, 18f),
                            new AssetCatalog.WeaponBaseRow("Weapon Sword Steel", "Sword", "Rare", 35, 28f),
                            new AssetCatalog.WeaponBaseRow("Weapon Sword Cobalt", "Sword", "Rare", 55, 36f),
                            new AssetCatalog.WeaponBaseRow("Weapon Sword Mythril", "Sword", "Epic", 75, 44.6667f)
                    ),
                    List.of()
            );
            CatalogGenerator generator = generator(registry, false);

            List<CatalogEntry> catalog = generator.generate(100, 42L);
            Set<String> generatedItemIds = new HashSet<>();
            for (CatalogEntry entry : catalog) {
                if (entry.level() > 0) {
                    generatedItemIds.add(entry.itemId());
                }
            }

            assertTrue(generatedItemIds.contains("Weapon_Sword_Copper"));
            assertTrue(generatedItemIds.contains("Weapon_Sword_Iron"));
            assertTrue(generatedItemIds.contains("Weapon_Sword_Steel"));
            assertTrue(generatedItemIds.contains("Weapon_Sword_Cobalt"));
            assertTrue(generatedItemIds.contains("Weapon_Sword_Mythril"));
        }

        @Test
        @DisplayName("Should keep curated foods, include only crude arrows and enchant scrolls, and remove spellbook consumables")
        void shouldKeepCuratedFoodsIncludeArrowsAndEnchantScrollsAndRemoveSpellbookConsumables() {
            MerchantPriceRegistry registry = initializedRegistry(List.of(), List.of());
            CatalogGenerator generator = generator(registry, true);

            Set<String> seenConsumables = new HashSet<>();
            for (long seed = 0; seed < 500; seed++) {
                for (CatalogEntry entry : generator.generate(20, seed)) {
                    if (entry.level() == 0) {
                        seenConsumables.add(entry.itemId());
                    }
                }
            }

            assertTrue(seenConsumables.contains("Food_Kebab_Meat"));
            assertTrue(seenConsumables.contains("Food_Pie_Meat"));
            assertTrue(seenConsumables.contains("Weapon_Arrow_Crude"));
            assertTrue(seenConsumables.contains("Weapon_Deployable_Turret"));
            assertTrue(seenConsumables.contains("Weapon_Deployable_Healing_Totem"));
            assertTrue(seenConsumables.contains("Weapon_Deployable_Slowness_Totem"));
            assertTrue(seenConsumables.stream().anyMatch(CatalogGenerator.RESERVED_SCROLL_ITEM_IDS::contains));

            assertFalse(seenConsumables.contains("Weapon_Arrow_Iron"));
            assertFalse(seenConsumables.contains("Weapon_Arrow_Deadeye"));
            assertFalse(seenConsumables.contains("Weapon_Arrow_Clearshot"));
            assertFalse(seenConsumables.contains("Weapon_Arrow_Trueshot"));
            assertFalse(seenConsumables.contains("Food_Bread"));
            assertFalse(seenConsumables.contains("Food_Fish_Grilled"));
            assertFalse(seenConsumables.contains("Weapon_Spellbook_Fire"));
            assertFalse(seenConsumables.contains("Weapon_Spellbook_Frost"));
            assertFalse(seenConsumables.contains("Weapon_Spellbook_Demon"));
            assertFalse(seenConsumables.contains("Weapon_Spellbook_Grimoire_Brown"));
            assertFalse(seenConsumables.contains("Weapon_Spellbook_Grimoire_Purple"));
            assertFalse(seenConsumables.contains("Weapon_Spellbook_Rekindle_Embers"));
        }

        @Test
        @DisplayName("Should never offer repair kits or durability scrolls now that gear is unbreakable")
        void shouldNeverOfferRepairKitsOrDurabilityScrolls() {
            MerchantPriceRegistry registry = initializedRegistry(List.of(), List.of());
            CatalogGenerator generator = generator(registry, true);

            for (long seed = 0; seed < 1000; seed++) {
                for (CatalogEntry entry : generator.generate(20, seed)) {
                    assertFalse("Tool_Repair_Kit_Iron".equals(entry.itemId()),
                            "Repair kits must not appear in merchant catalogs");
                    assertFalse(entry.itemId().startsWith("Scroll_Durability"),
                            "Durability scrolls must not appear in merchant catalogs");
                }
            }

            assertFalse(CatalogGenerator.RESERVED_SCROLL_ITEM_IDS.stream()
                            .anyMatch(id -> id.startsWith("Scroll_Durability")),
                    "Durability scrolls must be removed from the reserved scroll pool");
        }

        @Test
        @DisplayName("Should skip the reserved scroll slot when SimpleEnchantments is unavailable")
        void shouldSkipReservedScrollSlotWhenSimpleEnchantmentsIsUnavailable() {
            MerchantPriceRegistry registry = initializedRegistry(List.of(), List.of());
            CatalogGenerator generator = generator(registry, false);

            for (long seed = 0; seed < 500; seed++) {
                List<CatalogEntry> catalog = generator.generate(20, seed);
                long consumableCount = catalog.stream()
                        .filter(entry -> entry.level() == 0)
                        .count();
                long scrollCount = catalog.stream()
                        .filter(MerchantPriceRegistryTest::isEnchantScroll)
                        .count();

                assertEquals(4L, consumableCount);
                assertEquals(0L, scrollCount);
            }
        }

        @Test
        @DisplayName("Should reserve exactly one scroll slot in each generated catalog")
        void shouldReserveExactlyOneScrollSlotInEachGeneratedCatalog() {
            MerchantPriceRegistry registry = initializedRegistry(List.of(), List.of());
            CatalogGenerator generator = generator(registry, true);

            Set<String> seenScrollIds = new HashSet<>();

            for (long seed = 0; seed < 10_000; seed++) {
                List<CatalogEntry> catalog = generator.generate(20, seed);

                long consumableCount = catalog.stream()
                        .filter(entry -> entry.level() == 0)
                        .count();
                long scrollCount = catalog.stream()
                        .filter(MerchantPriceRegistryTest::isEnchantScroll)
                        .count();

                assertEquals(5L, consumableCount);
                assertEquals(1L, scrollCount);

                CatalogEntry scrollEntry = catalog.stream()
                        .filter(MerchantPriceRegistryTest::isEnchantScroll)
                        .findFirst()
                        .orElseThrow();
                assertEquals(1, scrollEntry.quantity());

                seenScrollIds.add(scrollEntry.itemId());
            }

            assertEquals(Set.copyOf(CatalogGenerator.RESERVED_SCROLL_ITEM_IDS), seenScrollIds);
        }
    }

    @Nested
    @DisplayName("custom item resale")
    class CustomItemResale {

        @Test
        @DisplayName("Should sell a registered custom item at 80% of its fixed buy price, level-independent")
        void shouldSellRegisteredCustomItemAtEightyPercentOfBuyPrice() {
            MerchantPriceRegistry registry = new MerchantPriceRegistry();
            registry.registerCustomItem(CustomItems.VAMPIRE_JUICE, 50_000L);

            long expected = (long) Math.floor(50_000L * MerchantPriceRegistry.SELL_RATIO);
            assertEquals(expected, registry.getSellPrice(CustomItems.VAMPIRE_JUICE));
            assertEquals(expected, registry.getSellPrice(CustomItems.VAMPIRE_JUICE, 25));
            assertTrue(registry.isSellable(CustomItems.VAMPIRE_JUICE));
            assertTrue(registry.isCustomItem(CustomItems.VAMPIRE_JUICE));
        }

        @Test
        @DisplayName("Should treat an unregistered item as not sellable with zero sell price")
        void shouldTreatUnregisteredItemAsNotSellable() {
            MerchantPriceRegistry registry = new MerchantPriceRegistry();

            assertEquals(0L, registry.getSellPrice("Not_A_Real_Item"));
            assertEquals(0L, registry.getSellPrice("Not_A_Real_Item", 20));
            assertFalse(registry.isSellable("Not_A_Real_Item"));
            assertFalse(registry.isCustomItem("Not_A_Real_Item"));
        }

        @Test
        @DisplayName("Should keep custom resale prices after initialize() rebuilds the gear caches")
        void shouldKeepCustomResalePricesAfterInitialize() {
            MerchantPriceRegistry registry = new MerchantPriceRegistry();
            registry.registerCustomItem(CustomItems.STAT_POINT_TOKEN, 7_500L);
            registry.initialize(new TestAssetCatalog(List.of(), List.of()));

            assertTrue(registry.isCustomItem(CustomItems.STAT_POINT_TOKEN));
            assertEquals((long) Math.floor(7_500L * MerchantPriceRegistry.SELL_RATIO),
                    registry.getSellPrice(CustomItems.STAT_POINT_TOKEN));
        }
    }

    @Nested
    @DisplayName("custom item pool")
    class CustomItemPool {

        @Test
        @DisplayName("Should offer every custom utility item in the consumable pool")
        void shouldOfferEveryCustomUtilityItemInTheConsumablePool() {
            MerchantPriceRegistry registry = initializedRegistry(List.of(), List.of());
            CatalogGenerator generator = generator(registry, false);

            Set<String> seenConsumables = new HashSet<>();
            for (long seed = 0; seed < 2_000; seed++) {
                for (CatalogEntry entry : generator.generate(20, seed)) {
                    if (entry.level() == 0) {
                        seenConsumables.add(entry.itemId());
                    }
                }
            }

            for (String customId : CustomItems.BUY_PRICES.keySet()) {
                assertTrue(seenConsumables.contains(customId),
                        "expected merchant pool to offer custom item " + customId);
            }
        }

        @Test
        @DisplayName("Should offer stat point tokens in stacks of 1, 5, and 10 priced at the unit price")
        void shouldOfferStatPointTokensInStacksOfOneFiveAndTen() {
            MerchantPriceRegistry registry = initializedRegistry(List.of(), List.of());
            CatalogGenerator generator = generator(registry, false);

            long unitPrice = CustomItems.BUY_PRICES.get(CustomItems.STAT_POINT_TOKEN);
            boolean sawSingle = false;
            boolean sawFivePack = false;
            boolean sawTenPack = false;

            for (long seed = 0; seed < 5_000; seed++) {
                for (CatalogEntry entry : generator.generate(20, seed)) {
                    if (!CustomItems.STAT_POINT_TOKEN.equals(entry.itemId())) {
                        continue;
                    }
                    if (entry.quantity() == 1 && entry.buyPrice() == unitPrice) {
                        sawSingle = true;
                    }
                    if (entry.quantity() == 5 && entry.buyPrice() == unitPrice * 5) {
                        sawFivePack = true;
                    }
                    if (entry.quantity() == 10 && entry.buyPrice() == unitPrice * 10) {
                        sawTenPack = true;
                    }
                }
            }

            assertTrue(sawSingle);
            assertTrue(sawFivePack);
            assertTrue(sawTenPack);
        }

        @Test
        @DisplayName("Should offer Palporter and Village Warp in stacks of 1 priced at their unit prices")
        void shouldOfferPalporterAndVillageWarpInStacksOfOne() {
            MerchantPriceRegistry registry = initializedRegistry(List.of(), List.of());
            CatalogGenerator generator = generator(registry, false);

            long palporterUnitPrice = CustomItems.BUY_PRICES.get(CustomItems.PALPORTER);
            long villageWarpUnitPrice = CustomItems.BUY_PRICES.get(CustomItems.VILLAGE_WARP);

            boolean sawPalporterSingle = false;
            boolean sawVillageWarpSingle = false;

            for (long seed = 0; seed < 5_000; seed++) {
                for (CatalogEntry entry : generator.generate(20, seed)) {
                    if (CustomItems.PALPORTER.equals(entry.itemId())) {
                        if (entry.quantity() == 1 && entry.buyPrice() == palporterUnitPrice) {
                            sawPalporterSingle = true;
                        }
                    } else if (CustomItems.VILLAGE_WARP.equals(entry.itemId())) {
                        if (entry.quantity() == 1 && entry.buyPrice() == villageWarpUnitPrice) {
                            sawVillageWarpSingle = true;
                        }
                    }
                }
            }

            assertTrue(sawPalporterSingle);
            assertTrue(sawVillageWarpSingle);
        }
    }

    @Nested
    @DisplayName("tool filtering")
    class ToolFiltering {

        @Test
        @DisplayName("Should exclude Tool_-prefixed weapons from getItemsByLevelRange but keep them sellable")
        void shouldExcludeToolPrefixedWeaponsFromLevelRangeButKeepSellable() {
            MerchantPriceRegistry registry = initializedRegistry(
                    List.of(
                            new AssetCatalog.WeaponBaseRow("Tool Sickle Copper", "Sickle", "Common", 10, 0f),
                            new AssetCatalog.WeaponBaseRow("Weapon Sword Copper", "Sword", "Common", 10, 8f)
                    ),
                    List.of()
            );

            List<String> items = registry.getItemsByLevelRange(1, 100);
            assertFalse(items.contains("Tool_Sickle_Copper"), "Tool_Sickle_Copper should not be in the merchant buy pool");
            assertTrue(items.contains("Weapon_Sword_Copper"), "Weapon_Sword_Copper should be in the merchant buy pool");

            assertTrue(registry.isSellable("Tool_Sickle_Copper"), "Tool_Sickle_Copper should still be sellable");
            assertTrue(registry.isSellable("Weapon_Sword_Copper"), "Weapon_Sword_Copper should still be sellable");
        }
    }


    private static boolean isEnchantScroll(@Nonnull CatalogEntry entry) {
        return CatalogGenerator.RESERVED_SCROLL_ITEM_IDS.contains(entry.itemId());
    }

    @Nonnull
    private static CatalogGenerator generator(@Nonnull MerchantPriceRegistry registry,
                                              boolean simpleEnchantmentsAvailable) {
        return new CatalogGenerator(
                registry,
            new ThirdPartyModAvailabilityService((pluginIdentifier, sentinelItemId) -> simpleEnchantmentsAvailable)
        );
    }

    private static MerchantPriceRegistry initializedRegistry(
            List<AssetCatalog.WeaponBaseRow> weapons,
            List<AssetCatalog.ArmorBaseRow> armor
    ) {
        MerchantPriceRegistry registry = new MerchantPriceRegistry();
        registry.initialize(new TestAssetCatalog(weapons, armor));
        return registry;
    }

    private static final class TestAssetCatalog extends AssetCatalog {

        private final List<WeaponBaseRow> weapons;
        private final List<ArmorBaseRow> armor;

        private TestAssetCatalog(List<WeaponBaseRow> weapons, List<ArmorBaseRow> armor) {
            this.weapons = List.copyOf(weapons);
            this.armor = List.copyOf(armor);
        }

        @Override
        @Nonnull
        public List<WeaponBaseRow> listWeapons(@Nullable String sortBy, boolean ascending, int limit,
                                               @Nullable String family) {
            return weapons;
        }

        @Override
        @Nonnull
        public List<ArmorBaseRow> listArmor(@Nullable String sortBy, boolean ascending, int limit,
                                            @Nullable String slot) {
            return armor;
        }
    }
}