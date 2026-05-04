package com.duntale.zsquad.loot;

import com.duntale.zsquad.loot.config.asset.LootEntryConfig;
import com.duntale.zsquad.loot.config.asset.LootTableConfig;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.AssetUpdateQuery;
import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.hypixel.hytale.event.EventBus;
import com.hypixel.hytale.event.IEventBus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("LootTableRegistry")
class LootTableRegistryTest {

    @BeforeAll
    static void ensureLootTableAssetStore() {
        if (AssetRegistry.getAssetStore(LootTableConfig.class) != null) {
            return;
        }

        AssetRegistry.register(
                new TestLootTableAssetStore.Builder()
                        .setPath(LootTableConfig.ASSET_PATH)
                        .setCodec(LootTableConfig.CODEC)
                        .setKeyFunction(LootTableConfig::getId)
                        .setReplaceOnRemove(id -> null)
                        .build());
    }

    @Test
    @DisplayName("Should keep programmatic register, get, clear, and size behavior")
    void shouldKeepProgrammaticRegistryBehavior() {
        LootTableRegistry registry = new LootTableRegistry();
        int baselineSize = registry.size();
        String roleName = uniqueRole("ProgrammaticRole");
        LootTable table = new LootTable(List.of(new LootEntry.Simple("Gold_Coin", 1, 1, 1.0)), 1);

        registry.register(roleName, table);

        assertSame(table, registry.get(roleName));
        assertTrue(registry.has(roleName));
        assertEquals(baselineSize + 1, registry.size());

        registry.clear();

        assertNull(registry.get(roleName));
        assertFalse(registry.has(roleName));
        assertEquals(baselineSize, registry.size());
    }

    @Test
    @DisplayName("Should resolve asset-backed loot tables")
    void shouldResolveAssetBackedTables() {
        LootTableRegistry registry = new LootTableRegistry();
        String roleName = uniqueRole("AssetRole");
        loadAssetConfig(roleName, 1, 0.5,
                entry("SIMPLE", "Gold_Coin", "WEAPON", 1, 1, 2, 4, 5.0, 0, 0));

        LootTable table = registry.get(roleName);

        assertNotNull(table);
        assertEquals(1, table.getRolls());
        assertEquals(0.5, table.getDropChance());
        assertTrue(registry.has(roleName));
    }

    @Test
    @DisplayName("Should count asset-backed tables in registry size")
    void shouldCountAssetBackedTablesInSize() {
        LootTableRegistry registry = new LootTableRegistry();
        int baselineSize = registry.size();
        String roleName = uniqueRole("SizedAssetRole");
        loadAssetConfig(roleName, 1, 1.0,
                entry("SIMPLE", "Gold_Coin", "WEAPON", 1, 1, 1, 2, 5.0, 0, 0));

        assertEquals(baselineSize + 1, registry.size());
    }

    @Test
    @DisplayName("Should let programmatic registrations override asset-backed configs")
    void shouldLetProgrammaticRegistrationsOverrideAssets() {
        LootTableRegistry registry = new LootTableRegistry();
        String roleName = uniqueRole("OverrideRole");
        loadAssetConfig(roleName, 1, 0.35,
                entry("SIMPLE", "Gold_Coin", "WEAPON", 1, 1, 1, 3, 5.0, 0, 0));

        LootTable programmatic = new LootTable(List.of(new LootEntry.Simple("Weapon_Sword_Iron", 1, 1, 1.0)), 2);
        registry.register(roleName, programmatic);

        assertSame(programmatic, registry.get(roleName));
    }

    @Test
    @DisplayName("Should return null when no programmatic or asset-backed table exists")
    void shouldReturnNullForMissingRole() {
        LootTableRegistry registry = new LootTableRegistry();

        assertNull(registry.get(uniqueRole("MissingRole")));
    }

    private static void loadAssetConfig(String id,
                                        int rolls,
                                        double dropChance,
                                        LootEntryConfig... entries) {
        LootTableConfig asset = new LootTableConfig();
        setField(asset, "id", id);
        setField(asset, "rolls", rolls);
        setField(asset, "dropChance", dropChance);
        setField(asset, "entries", entries);
        LootTableConfig.getAssetStore().loadAssets("Test:LootTables", List.of(asset));
    }

    private static LootEntryConfig entry(String type,
                                         String itemId,
                                         String gearType,
                                         int gearLevelMin,
                                         int gearLevelMax,
                                         int quantityMin,
                                         int quantityMax,
                                         double weight,
                                         int minNpcLevel,
                                         int maxNpcLevel) {
        LootEntryConfig config = new LootEntryConfig();
        setField(config, "type", type);
        setField(config, "itemId", itemId);
        setField(config, "gearType", gearType);
        setField(config, "gearLevelMin", gearLevelMin);
        setField(config, "gearLevelMax", gearLevelMax);
        setField(config, "quantityMin", quantityMin);
        setField(config, "quantityMax", quantityMax);
        setField(config, "weight", weight);
        setField(config, "minNpcLevel", minNpcLevel);
        setField(config, "maxNpcLevel", maxNpcLevel);
        return config;
    }

    private static String uniqueRole(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace('-', '_');
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to set field " + fieldName, e);
        }
    }

    private static final class TestLootTableAssetStore
            extends AssetStore<String, LootTableConfig, IndexedLookupTableAssetMap<String, LootTableConfig>> {

        private final EventBus eventBus = new EventBus(false);

        private TestLootTableAssetStore(Builder builder) {
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
                                            Map<String, LootTableConfig> loaded,
                                            @Nonnull AssetUpdateQuery query) {
        }

        private static final class Builder extends AssetStore.Builder<String, LootTableConfig,
                IndexedLookupTableAssetMap<String, LootTableConfig>, Builder> {

            private Builder() {
                super(String.class, LootTableConfig.class, new IndexedLookupTableAssetMap<>(LootTableConfig[]::new));
            }

            @Override
            public AssetStore<String, LootTableConfig,
                    IndexedLookupTableAssetMap<String, LootTableConfig>> build() {
                return new TestLootTableAssetStore(this);
            }
        }
    }
}