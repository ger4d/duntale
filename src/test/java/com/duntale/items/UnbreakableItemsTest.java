package com.duntale.items;

import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.AssetUpdateQuery;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.event.EventBus;
import com.hypixel.hytale.event.IEventBus;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemArmor;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemWeapon;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("UnbreakableItems")
class UnbreakableItemsTest {

    private static final String BREAKABLE_WEAPON_ID = "Test_Weapon_Breakable";
    private static final String BREAKABLE_ARMOR_ID = "Test_Armor_Breakable";
    private static final String DURABLE_TOOL_ID = "Test_Tool_Durable";

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

        // The shared test asset stores use a no-op loader, so insert directly into
        // the active map's backing store to make these gear assets resolvable
        // regardless of which test class registered the store first.
        seedItem(weaponItem(BREAKABLE_WEAPON_ID, 150.0));
        seedItem(armorItem(BREAKABLE_ARMOR_ID, 200.0));
        seedItem(plainItem(DURABLE_TOOL_ID, 80.0));
    }

    @Test
    @DisplayName("Should convert a breakable weapon stack to an unbreakable one")
    void shouldConvertBreakableWeaponStackToUnbreakable() {
        ItemStack weapon = new ItemStack(BREAKABLE_WEAPON_ID, 1);
        assertTrue(weapon.getMaxDurability() > 0.0, "precondition: weapon is breakable");

        ItemStack result = UnbreakableItems.makeUnbreakable(weapon);

        assertEquals(0.0, result.getMaxDurability());
        assertTrue(result.isUnbreakable());
        assertFalse(result.isBroken());
    }

    @Test
    @DisplayName("Should convert a breakable armor stack to an unbreakable one")
    void shouldConvertBreakableArmorStackToUnbreakable() {
        ItemStack armor = new ItemStack(BREAKABLE_ARMOR_ID, 1);
        assertTrue(armor.getMaxDurability() > 0.0, "precondition: armor is breakable");

        ItemStack result = UnbreakableItems.makeUnbreakable(armor);

        assertEquals(0.0, result.getMaxDurability());
        assertTrue(result.isUnbreakable());
    }

    @Test
    @DisplayName("Should return the same instance for an already-unbreakable gear stack")
    void shouldReturnSameInstanceForAlreadyUnbreakableGear() {
        ItemStack alreadyUnbreakable = new ItemStack(BREAKABLE_WEAPON_ID, 1, 0.0, 0.0, null);

        ItemStack result = UnbreakableItems.makeUnbreakable(alreadyUnbreakable);

        assertSame(alreadyUnbreakable, result);
    }

    @Test
    @DisplayName("Should leave non-gear items untouched even when they have durability")
    void shouldLeaveNonGearItemsUntouched() {
        ItemStack tool = new ItemStack(DURABLE_TOOL_ID, 1);
        assertTrue(tool.getMaxDurability() > 0.0, "precondition: tool is durable");
        assertFalse(UnbreakableItems.isGear(tool.getItem()), "precondition: tool is not gear");

        ItemStack result = UnbreakableItems.makeUnbreakable(tool);

        assertSame(tool, result);
    }

    @Test
    @DisplayName("Should return the empty stack unchanged")
    void shouldReturnEmptyStackUnchanged() {
        ItemStack result = UnbreakableItems.makeUnbreakable(ItemStack.EMPTY);

        assertSame(ItemStack.EMPTY, result);
    }

    @Test
    @DisplayName("isGear should be true for weapons and armor, false otherwise")
    void isGearShouldReflectWeaponOrArmorConfig() {
        assertTrue(UnbreakableItems.isGear(Item.getAssetMap().getAsset(BREAKABLE_WEAPON_ID)));
        assertTrue(UnbreakableItems.isGear(Item.getAssetMap().getAsset(BREAKABLE_ARMOR_ID)));
        assertFalse(UnbreakableItems.isGear(Item.getAssetMap().getAsset(DURABLE_TOOL_ID)));
        assertFalse(UnbreakableItems.isGear(null));
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
    private static Item armorItem(@Nonnull String id, double durabilityValue) {
        return new Item(id) {
            {
                this.armor = new ItemArmor() {
                };
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
