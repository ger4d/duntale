package com.duntale.zsquad.loot.config.asset;

import com.duntale.zsquad.loot.LootEntry;
import com.duntale.zsquad.loot.LootTable;
import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.server.core.asset.HytaleAssetStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Hytale JSON asset representing one NPC-role loot table.
 */
public class LootTableConfig
        implements JsonAssetWithMap<String, IndexedLookupTableAssetMap<String, LootTableConfig>> {

    public static final String ASSET_PATH = "Configs/LootTables";

    public static AssetBuilderCodec<String, LootTableConfig> CODEC;
    private static AssetStore<String, LootTableConfig,
            IndexedLookupTableAssetMap<String, LootTableConfig>> assetStore;

    protected String id;
    protected AssetExtraInfo.Data data;
    protected int rolls = 1;
    protected double dropChance = 1.0;
    protected LootEntryConfig[] entries = new LootEntryConfig[0];

    public LootTableConfig() {
    }

    static {
        CODEC = AssetBuilderCodec.builder(
                        LootTableConfig.class,
                        LootTableConfig::new,
                        Codec.STRING,
                        (asset, key) -> asset.id = key,
                        asset -> asset.id,
                        (asset, extra) -> asset.data = extra,
                        asset -> asset.data
                )
                .append(new KeyedCodec<>("Rolls", Codec.INTEGER),
                        (asset, value) -> asset.rolls = value,
                        asset -> asset.rolls)
                .add()
                .append(new KeyedCodec<>("DropChance", Codec.DOUBLE),
                        (asset, value) -> asset.dropChance = value,
                        asset -> asset.dropChance)
                .add()
                .append(new KeyedCodec<>("Entries", LootEntryConfig.ARRAY_CODEC),
                        (asset, value) -> asset.entries = value,
                        asset -> asset.entries)
                .add()
                .build();
    }

    @Nonnull
    public static HytaleAssetStore.Builder<String, LootTableConfig,
            IndexedLookupTableAssetMap<String, LootTableConfig>> assetStoreBuilder() {
        return HytaleAssetStore.builder(
                        LootTableConfig.class,
                        new IndexedLookupTableAssetMap<>(LootTableConfig[]::new))
                .setPath(ASSET_PATH)
                .setCodec(CODEC)
                .setKeyFunction(LootTableConfig::getId)
                .setReplaceOnRemove(id -> null);
    }

    @Nullable
    public static LootTableConfig get(@Nonnull String id) {
        return ((IndexedLookupTableAssetMap<String, LootTableConfig>) getAssetStore().getAssetMap()).getAsset(id);
    }

    @Nonnull
    public static Collection<LootTableConfig> getAll() {
        return ((IndexedLookupTableAssetMap<String, LootTableConfig>) getAssetStore().getAssetMap())
                .getAssetMap()
                .values();
    }

    @Nonnull
    public static AssetStore<String, LootTableConfig,
            IndexedLookupTableAssetMap<String, LootTableConfig>> getAssetStore() {
        if (assetStore == null) {
            assetStore = AssetRegistry.getAssetStore(LootTableConfig.class);
        }
        return Objects.requireNonNull(assetStore, "LootTableConfig asset store is not registered");
    }

    /**
     * Converts this asset config to the runtime loot table model.
     *
     * @return the converted runtime loot table
     */
    @Nonnull
    public LootTable toLootTable() {
        List<String> errors = validationErrors();
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }

        List<LootEntry> runtimeEntries = new ArrayList<>(entries.length);
        for (LootEntryConfig entry : entries) {
            runtimeEntries.add(entry.toLootEntry());
        }
        return new LootTable(runtimeEntries, rolls, dropChance);
    }

    @Override
    public String getId() {
        return id;
    }

    /**
     * Returns the configured roll count.
     *
     * @return the roll count
     */
    public int getRolls() {
        return rolls;
    }

    /**
     * Returns the configured drop chance.
     *
     * @return the drop chance
     */
    public double getDropChance() {
        return dropChance;
    }

    /**
     * Returns the configured loot entries.
     *
     * @return a defensive copy of the configured entries
     */
    @Nonnull
    public LootEntryConfig[] getEntries() {
        return entries.clone();
    }

    @Nonnull
    private List<String> validationErrors() {
        List<String> errors = new ArrayList<>();

        if (rolls < 0) {
            errors.add("Rolls must be 0 or greater");
        }
        if (dropChance < 0.0 || dropChance > 1.0) {
            errors.add("DropChance must be between 0.0 and 1.0");
        }
        if (entries == null || entries.length == 0) {
            errors.add("Entries must not be empty");
            return errors;
        }

        for (int index = 0; index < entries.length; index++) {
            LootEntryConfig entry = entries[index];
            if (entry == null) {
                errors.add("Entry at index " + index + " must not be null");
                continue;
            }
            for (String error : entry.validationErrors()) {
                errors.add("Entry[" + index + "]: " + error);
            }
        }

        return errors;
    }
}