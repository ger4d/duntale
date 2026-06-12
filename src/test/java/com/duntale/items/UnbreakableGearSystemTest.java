package com.duntale.items;

import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.AssetUpdateQuery;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.event.EventBus;
import com.hypixel.hytale.event.IEventBus;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemWeapon;
import com.hypixel.hytale.server.core.event.events.ecs.InventoryChangeEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.Transaction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("UnbreakableGearSystem")
class UnbreakableGearSystemTest {

    private static final String BREAKABLE_WEAPON_ID = "Test_System_Weapon_Breakable";
    private static final String DURABLE_TOOL_ID = "Test_System_Tool_Durable";

    private final UnbreakableGearSystem system = new UnbreakableGearSystem();

    @BeforeAll
    static void seedGearAssets() {
        if (AssetRegistry.getAssetStore(Item.class) == null) {
            AssetRegistry.register(
                    new TestItemAssetStore.Builder()
                            .setPath("Item/Items")
                            .setCodec(Item.CODEC)
                            .setKeyFunction(Item::getId)
                            .build());
        }
        seedItem(weaponItem(BREAKABLE_WEAPON_ID, 150.0));
        seedItem(plainItem(DURABLE_TOOL_ID, 80.0));
    }

    @Test
    @DisplayName("Should convert a breakable weapon added to an inventory slot")
    void shouldConvertBreakableWeaponAddedToSlot() {
        SimpleItemContainer hotbar = new SimpleItemContainer((short) 3);
        AtomicReference<Transaction> captured = new AtomicReference<>();
        hotbar.registerChangeEvent((short) 0, event -> captured.set(event.transaction()));

        hotbar.setItemStackForSlot((short) 0, new ItemStack(BREAKABLE_WEAPON_ID, 1));
        assertTrue(hotbar.getItemStack((short) 0).getMaxDurability() > 0.0, "precondition: weapon is breakable");

        system.handle(0, null, null, null, eventFor(hotbar, captured.get()));

        ItemStack converted = hotbar.getItemStack((short) 0);
        assertTrue(converted.isUnbreakable());
    }

    @Test
    @DisplayName("Should not replace a slot again once its gear is already unbreakable")
    void shouldNotReplaceAlreadyUnbreakableGear() {
        SimpleItemContainer hotbar = new SimpleItemContainer((short) 3);
        AtomicReference<Transaction> captured = new AtomicReference<>();
        hotbar.registerChangeEvent((short) 0, event -> captured.set(event.transaction()));

        hotbar.setItemStackForSlot((short) 0, new ItemStack(BREAKABLE_WEAPON_ID, 1));
        system.handle(0, null, null, null, eventFor(hotbar, captured.get()));

        // After conversion, a follow-up event on the same (now unbreakable) slot is a no-op.
        ItemStack converted = hotbar.getItemStack((short) 0);
        system.handle(0, null, null, null, eventFor(hotbar, captured.get()));

        assertSame(converted, hotbar.getItemStack((short) 0));
    }

    @Test
    @DisplayName("Should leave non-gear durable items untouched")
    void shouldLeaveNonGearDurableItemsUntouched() {
        SimpleItemContainer hotbar = new SimpleItemContainer((short) 3);
        AtomicReference<Transaction> captured = new AtomicReference<>();
        hotbar.registerChangeEvent((short) 0, event -> captured.set(event.transaction()));

        hotbar.setItemStackForSlot((short) 0, new ItemStack(DURABLE_TOOL_ID, 1));
        system.handle(0, null, null, null, eventFor(hotbar, captured.get()));

        assertFalse(hotbar.getItemStack((short) 0).isUnbreakable());
    }

    @Nonnull
    private static InventoryChangeEvent eventFor(@Nonnull SimpleItemContainer container,
                                                 @Nonnull Transaction transaction) {
        // The system only reads the container and transaction off the event.
        return new InventoryChangeEvent(null, null, container, transaction);
    }

    // ── Asset seeding helpers ────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static void seedItem(@Nonnull Item item) {
        DefaultAssetMap<String, Item> map = Item.getAssetMap();
        try {
            Field field = DefaultAssetMap.class.getDeclaredField("assetMap");
            field.setAccessible(true);
            Map<String, Item> backing = (Map<String, Item>) field.get(map);
            backing.put(item.getId(), item);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to seed test item asset: " + item.getId(), e);
        }
    }

    @Nonnull
    private static Item weaponItem(@Nonnull String id, double durabilityValue) {
        return new Item(id) {
            {
                this.weapon = new ItemWeapon();
                this.maxDurability = durabilityValue;
            }
        };
    }

    @Nonnull
    private static Item plainItem(@Nonnull String id, double durabilityValue) {
        return new Item(id) {
            {
                this.maxDurability = durabilityValue;
            }
        };
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
