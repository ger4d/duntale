package com.duntale.merchant;

import com.duntale.ThirdPartyModAvailabilityService;
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
        @DisplayName("Should keep curated foods, include arrows and enchant scrolls, and remove spellbook consumables")
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
            assertTrue(seenConsumables.contains("Weapon_Arrow_Iron"));
            assertTrue(seenConsumables.contains("Weapon_Arrow_Deadeye"));
            assertTrue(seenConsumables.contains("Weapon_Arrow_Clearshot"));
            assertTrue(seenConsumables.contains("Weapon_Arrow_Trueshot"));
            assertTrue(seenConsumables.contains("Weapon_Deployable_Turret"));
            assertTrue(seenConsumables.contains("Weapon_Deployable_Healing_Totem"));
            assertTrue(seenConsumables.contains("Weapon_Deployable_Slowness_Totem"));
            assertTrue(seenConsumables.stream().anyMatch(CatalogGenerator.RESERVED_SCROLL_ITEM_IDS::contains));

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
        @DisplayName("Should offer repair kits as single kits or fixed five-packs instead of full stacks")
        void shouldOfferRepairKitsAsSingleKitsOrFixedFivePacksInsteadOfFullStacks() {
            MerchantPriceRegistry registry = initializedRegistry(List.of(), List.of());
            CatalogGenerator generator = generator(registry, false);

            boolean sawSingleKit = false;
            boolean sawFivePack = false;
            boolean sawFullStack = false;

            for (long seed = 0; seed < 1000; seed++) {
                for (CatalogEntry entry : generator.generate(20, seed)) {
                    if (!"Tool_Repair_Kit_Iron".equals(entry.itemId())) {
                        continue;
                    }
                    if (entry.quantity() == 1 && entry.buyPrice() == 25_000L) {
                        sawSingleKit = true;
                    }
                    if (entry.quantity() == 5 && entry.buyPrice() == 125_000L) {
                        sawFivePack = true;
                    }
                    if (entry.quantity() > 5) {
                        sawFullStack = true;
                    }
                }
            }

            assertTrue(sawSingleKit);
            assertTrue(sawFivePack);
            assertFalse(sawFullStack);
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