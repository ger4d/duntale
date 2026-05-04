package com.duntale.zsquad.loot;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("LootRollService")
class LootRollServiceTest {

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
    @DisplayName("Should return empty list when the role has no table")
    void shouldReturnEmptyListForMissingRole() {
        LootRollService service = new LootRollService(new LootTableRegistry());

        assertTrue(service.roll("MissingRole", 10, 0).isEmpty());
    }

    @Test
    @DisplayName("Should roll through the registry using the requested role")
    void shouldRollThroughRegistry() {
        LootTableRegistry registry = new LootTableRegistry();
        RecordingLootTable table = new RecordingLootTable(List.of(new ItemStack("Gold_Coin", 2)));
        registry.register("Zombie_Test", table);

        LootRollService service = new LootRollService(registry);
        List<ItemStack> drops = service.roll("Zombie_Test", 3, 0);

        assertEquals(1, drops.size());
        assertEquals("Gold_Coin", drops.getFirst().getItemId());
        assertEquals(6, drops.getFirst().getQuantity());
    }

    @Test
    @DisplayName("Should pass NPC level and Luck level through to the runtime loot table")
    void shouldPassLuckThroughToLootTable() {
        LootTableRegistry registry = new LootTableRegistry();
        RecordingLootTable table = new RecordingLootTable(List.of(new ItemStack("Weapon_Sword_Iron", 1)));
        registry.register("Skeleton_Test", table);

        LootRollService service = new LootRollService(registry);
        service.roll("Skeleton_Test", 12, 27);

        assertEquals(12, table.lastNpcLevel);
        assertEquals(27, table.lastLuckLevel);
    }

    @Test
    @DisplayName("Should scale only gold quantities by NPC level")
    void shouldScaleOnlyGoldQuantities() {
        LootTableRegistry registry = new LootTableRegistry();
        RecordingLootTable table = new RecordingLootTable(List.of(
                new ItemStack("Gold_Coin", 3),
                new ItemStack("Weapon_Axe_Crude", 1)
        ));
        registry.register("Trork_Test", table);

        LootRollService service = new LootRollService(registry);
        List<ItemStack> drops = service.roll("Trork_Test", 4, 0);

        assertEquals(2, drops.size());
        assertEquals(12, drops.getFirst().getQuantity());
        assertEquals(1, drops.get(1).getQuantity());
        assertEquals("Weapon_Axe_Crude", drops.get(1).getItemId());
    }

    private static final class RecordingLootTable extends LootTable {
        private final List<ItemStack> drops;
        private int lastNpcLevel;
        private int lastLuckLevel;

        private RecordingLootTable(List<ItemStack> drops) {
            super(List.of(new LootEntry.Simple("Gold_Coin", 1, 1, 1.0)), 1);
            this.drops = List.copyOf(drops);
        }

        @Override
        public List<ItemStack> roll(int npcLevel, int luckLevel) {
            this.lastNpcLevel = npcLevel;
            this.lastLuckLevel = luckLevel;
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