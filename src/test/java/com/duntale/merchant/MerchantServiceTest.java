package com.duntale.merchant;

import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.AssetUpdateQuery;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.event.EventBus;
import com.hypixel.hytale.event.IEventBus;
import com.duntale.items.CustomItems;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("MerchantService")
class MerchantServiceTest {

        @BeforeAll
        static void ensureItemAssetStore() {
                if (AssetRegistry.getAssetStore(Item.class) != null) {
                        return;
                }

                AssetRegistry.register(
                                new TestItemAssetStore.Builder()
                                                .setPath("Item/Items")
                                                .setCodec(Item.CODEC)
                                                .setKeyFunction(Item::getId)
                                                .build());
        }

    @Test
    @DisplayName("Should retag a purchased item found in an equipment container")
    void shouldRetagPurchasedItemFoundInAnEquipmentContainer() {
        ItemContainer hotbar = new SimpleItemContainer((short) 1);
        ItemContainer storage = new SimpleItemContainer((short) 1);
        ItemContainer armor = new SimpleItemContainer((short) 1);
        armor.setItemStackForSlot((short) 0,
                merchantDisplayItem("Armor_Cobalt_Chest", 4_200L, 15_000L));

        MerchantService.tagFirstMatchInContainers(
                "Armor_Cobalt_Chest",
                2_100L,
                hotbar,
                storage,
                armor);

        ItemStack updated = armor.getItemStack((short) 0);
        assertNull(updated.getFromMetadataOrNull(MerchantService.META_BUY_PRICE, Codec.LONG));
        assertNull(updated.getFromMetadataOrNull(MerchantService.META_GOLD, Codec.LONG));
        assertEquals(2_100L,
                updated.getFromMetadataOrNull(MerchantService.META_SELL_PRICE, Codec.LONG));
    }

    @Test
    @DisplayName("Should strip merchant display metadata from equipped armor containers")
    void shouldStripMerchantDisplayMetadataFromEquippedArmorContainers() {
        ItemContainer armor = new SimpleItemContainer((short) 1);
        armor.setItemStackForSlot((short) 0,
                merchantDisplayItem("Armor_Cobalt_Chest", 4_200L, 15_000L)
                        .withMetadata(MerchantService.META_SELL_PRICE, Codec.LONG, 2_100L));

        MerchantService.stripMerchantMetaFromContainers(armor);

        ItemStack updated = armor.getItemStack((short) 0);
        assertNull(updated.getFromMetadataOrNull(MerchantService.META_BUY_PRICE, Codec.LONG));
        assertNull(updated.getFromMetadataOrNull(MerchantService.META_GOLD, Codec.LONG));
        assertEquals(2_100L,
                updated.getFromMetadataOrNull(MerchantService.META_SELL_PRICE, Codec.LONG));
    }

    @Test
    @DisplayName("Should use stamped sell-price tag over registry fallback")
    void shouldUseStampedSellPriceTagOverRegistryFallback() {
        MerchantPriceRegistry registry = new MerchantPriceRegistry();
        registry.registerCustomItem(CustomItems.VAMPIRE_JUICE, 50_000L);

        ItemStack tagged = new ItemStack(CustomItems.VAMPIRE_JUICE, 1)
                .withMetadata(MerchantService.META_SELL_PRICE, Codec.LONG, 7_500L);

        assertEquals(7_500L, MerchantService.resolveSellPrice(registry, tagged));
        assertEquals(7_500L, MerchantService.computeSellPrice(registry, tagged));
    }

    @Test
    @DisplayName("Should fall back to registry sell price when no stamped tag exists")
    void shouldFallBackToRegistrySellPriceWhenNoStampedTag() {
        MerchantPriceRegistry registry = new MerchantPriceRegistry();
        registry.registerCustomItem(CustomItems.VAMPIRE_JUICE, 50_000L);

        ItemStack plain = new ItemStack(CustomItems.VAMPIRE_JUICE, 1);

        long expected = (long) Math.floor(50_000L * MerchantPriceRegistry.SELL_RATIO);
        assertEquals(expected, MerchantService.resolveSellPrice(registry, plain));
        assertEquals(expected, MerchantService.computeSellPrice(registry, plain));
    }

    @Test
    @DisplayName("Should multiply per-unit sell price by stack quantity")
    void shouldMultiplyPerUnitSellPriceByStackQuantity() {
        MerchantPriceRegistry registry = new MerchantPriceRegistry();

        ItemStack stack = new ItemStack("Weapon_Arrow_Crude", 10)
                .withMetadata(MerchantService.META_SELL_PRICE, Codec.LONG, 2L);

        assertEquals(20L, MerchantService.computeSellPrice(registry, stack));
    }

    private static ItemStack merchantDisplayItem(String itemId, long buyPrice, long gold) {
        return new ItemStack(itemId, 1)
                .withMetadata(MerchantService.META_BUY_PRICE, Codec.LONG, buyPrice)
                .withMetadata(MerchantService.META_GOLD, Codec.LONG, gold);
    }

        private static final class TestItemAssetStore extends AssetStore<String, Item, DefaultAssetMap<String, Item>> {

                private final EventBus eventBus = new EventBus(false);

                private TestItemAssetStore(Builder builder) {
                        super(builder);
                }

                @Override
                protected IEventBus getEventBus() {
                        return eventBus;
                }

                @Override
                public void addFileMonitor(@Nonnull String packKey, Path path) {
                }

                @Override
                public void removeFileMonitor(Path path) {
                }

                @Override
                protected void handleRemoveOrUpdate(Set<String> removed,
                                                                                        Map<String, Item> loaded,
                                                                                        @Nonnull AssetUpdateQuery query) {
                }

                private static final class Builder extends AssetStore.Builder<String, Item, DefaultAssetMap<String, Item>, Builder> {

                        private Builder() {
                                super(String.class, Item.class, new DefaultAssetMap<>());
                        }

                        @Override
                        public AssetStore<String, Item, DefaultAssetMap<String, Item>> build() {
                                return new TestItemAssetStore(this);
                        }
                }
        }
}