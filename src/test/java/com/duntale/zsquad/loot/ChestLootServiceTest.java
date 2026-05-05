package com.duntale.zsquad.loot;

import com.duntale.dungeongen.model.ChestTier;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.AssetUpdateQuery;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.event.EventBus;
import com.hypixel.hytale.event.IEventBus;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ChestLootService")
class ChestLootServiceTest {

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
    @DisplayName("Should roll chest tiers using floor context without replacement")
    void shouldRollChestTiersUsingFloorContextWithoutReplacement() {
        LootTableRegistry registry = new LootTableRegistry();
        RecordingLootTable table = new RecordingLootTable(List.of(new ItemStack("Gold_Coin", 12)));
        registry.register("Chest_Epic", table);

        ChestLootService service = new ChestLootService(registry);
        List<ItemStack> drops = service.roll(ChestTier.EPIC, 14);

        assertEquals(1, drops.size());
        assertEquals("Gold_Coin", drops.getFirst().getItemId());
        assertNull(table.lastContext.npcLevel());
        assertEquals(14, table.lastContext.floorLevel());
        assertFalse(table.lastWithReplacement);
        assertTrue(table.lastRequestedRolls >= 1 && table.lastRequestedRolls <= 3);
    }

    @Test
    @DisplayName("Should return empty list when a chest tier table is missing")
    void shouldReturnEmptyListWhenTierTableMissing() {
        ChestLootService service = new ChestLootService(new LootTableRegistry());

        assertTrue(service.roll(ChestTier.LEGENDARY, 30).isEmpty());
    }

    private static final class RecordingLootTable extends LootTable {
        private final List<ItemStack> drops;
        private LootContext lastContext;
        private int lastRequestedRolls;
        private boolean lastWithReplacement;

        private RecordingLootTable(List<ItemStack> drops) {
            super(List.of(new LootEntry("Gold_Coin", 1.0)), 1);
            this.drops = List.copyOf(drops);
        }

        @Override
        public List<ItemStack> roll(LootContext context, int requestedRolls, boolean withReplacement) {
            this.lastContext = context;
            this.lastRequestedRolls = requestedRolls;
            this.lastWithReplacement = withReplacement;
            return drops;
        }
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