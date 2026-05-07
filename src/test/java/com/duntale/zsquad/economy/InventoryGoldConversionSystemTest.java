package com.duntale.zsquad.economy;

import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.AssetUpdateQuery;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.event.EventBus;
import com.hypixel.hytale.event.IEventBus;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.Transaction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("InventoryGoldConversionSystem")
class InventoryGoldConversionSystemTest {

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
    @DisplayName("Should consume gold moved in from an external container")
    void shouldConsumeGoldMovedInFromExternalContainer() {
        SimpleItemContainer chest = new SimpleItemContainer((short) 1);
        SimpleItemContainer hotbar = new SimpleItemContainer((short) 3);
        SimpleItemContainer storage = new SimpleItemContainer((short) 3);
        CombinedItemContainer playerInventory = new CombinedItemContainer(hotbar, storage);

        chest.setItemStackForSlot((short) 0, new ItemStack("Gold_Coin", 12));

        AtomicReference<Transaction> captured = new AtomicReference<>();
        hotbar.registerChangeEvent((short) 0, event -> captured.set(event.transaction()));

        chest.moveItemStackFromSlot((short) 0, 12, playerInventory);

        int converted = InventoryGoldConversionSystem.consumeConvertedGold(hotbar, captured.get(), playerInventory);

        assertEquals(12, converted);
        assertNull(hotbar.getItemStack((short) 0));
    }

    @Test
    @DisplayName("Should only consume the newly added amount when stacking external gold")
    void shouldOnlyConsumeNewlyAddedAmountWhenStackingExternalGold() {
        SimpleItemContainer chest = new SimpleItemContainer((short) 1);
        SimpleItemContainer hotbar = new SimpleItemContainer((short) 3);
        SimpleItemContainer storage = new SimpleItemContainer((short) 3);
        CombinedItemContainer playerInventory = new CombinedItemContainer(hotbar, storage);

        hotbar.setItemStackForSlot((short) 0, new ItemStack("Gold_Coin", 3));
        chest.setItemStackForSlot((short) 0, new ItemStack("Gold_Coin", 4));

        AtomicReference<Transaction> captured = new AtomicReference<>();
        hotbar.registerChangeEvent((short) 0, event -> captured.set(event.transaction()));

        chest.moveItemStackFromSlotToSlot((short) 0, 4, hotbar, (short) 0);

        int converted = InventoryGoldConversionSystem.consumeConvertedGold(hotbar, captured.get(), playerInventory);

        assertEquals(4, converted);
        assertEquals(3, hotbar.getItemStack((short) 0).getQuantity());
    }

    @Test
    @DisplayName("Should ignore gold moved within the player inventory")
    void shouldIgnoreGoldMovedWithinPlayerInventory() {
        SimpleItemContainer hotbar = new SimpleItemContainer((short) 3);
        SimpleItemContainer storage = new SimpleItemContainer((short) 3);
        CombinedItemContainer playerInventory = new CombinedItemContainer(hotbar, storage);

        storage.setItemStackForSlot((short) 0, new ItemStack("Gold_Coin", 7));

        AtomicReference<Transaction> captured = new AtomicReference<>();
        hotbar.registerChangeEvent((short) 0, event -> captured.set(event.transaction()));

        storage.moveItemStackFromSlot((short) 0, 7, hotbar);

        int converted = InventoryGoldConversionSystem.consumeConvertedGold(hotbar, captured.get(), playerInventory);

        assertEquals(0, converted);
        assertEquals(7, hotbar.getItemStack((short) 0).getQuantity());
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