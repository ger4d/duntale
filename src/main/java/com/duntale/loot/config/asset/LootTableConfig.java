package com.duntale.loot.config.asset;

import com.duntale.loot.LootEntry;
import com.duntale.loot.LootTable;
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
    protected double goldChance = 0.0;
    protected LootEntryConfig[] goldEntries = new LootEntryConfig[0];

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
                .append(new KeyedCodec<>("GoldChance", Codec.DOUBLE),
                        (asset, value) -> asset.goldChance = value,
                        asset -> asset.goldChance)
                .add()
                .append(new KeyedCodec<>("GoldEntries", LootEntryConfig.ARRAY_CODEC),
                        (asset, value) -> asset.goldEntries = value,
                        asset -> asset.goldEntries)
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
        List<LootEntry> runtimeGoldEntries = new ArrayList<>(goldEntries.length);
        for (LootEntryConfig entry : goldEntries) {
            runtimeGoldEntries.add(entry.toLootEntry());
        }
        return new LootTable(runtimeEntries, rolls, dropChance, runtimeGoldEntries, goldChance);
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
     * Returns the configured gear loot entries.
     *
     * @return a defensive copy of the configured gear entries
     */
    @Nonnull
    public LootEntryConfig[] getEntries() {
        return entries.clone();
    }

    /**
     * Returns the configured gold drop chance.
     *
     * @return the gold drop chance
     */
    public double getGoldChance() {
        return goldChance;
    }

    /**
     * Returns the configured gold loot entries.
     *
     * @return a defensive copy of the configured gold entries (may be empty)
     */
    @Nonnull
    public LootEntryConfig[] getGoldEntries() {
        return goldEntries.clone();
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
        if (goldChance < 0.0 || goldChance > 1.0) {
            errors.add("GoldChance must be between 0.0 and 1.0");
        }

        // A table must define at least one pool. Gold-only tables (empty gear Entries) are valid —
        // e.g. swarm roles that drop only small gold and never gear.
        boolean hasGear = entries != null && entries.length > 0;
        boolean hasGold = goldEntries != null && goldEntries.length > 0;
        if (!hasGear && !hasGold) {
            errors.add("A loot table must define at least one of Entries or GoldEntries");
            return errors;
        }

        validatePool("Entry", entries, errors);
        validatePool("GoldEntry", goldEntries, errors);

        return errors;
    }

    private static void validatePool(@Nonnull String label, @Nullable LootEntryConfig[] pool,
                                     @Nonnull List<String> errors) {
        if (pool == null) {
            return;
        }
        for (int index = 0; index < pool.length; index++) {
            LootEntryConfig entry = pool[index];
            if (entry == null) {
                errors.add(label + " at index " + index + " must not be null");
                continue;
            }
            for (String error : entry.validationErrors()) {
                errors.add(label + "[" + index + "]: " + error);
            }
        }
    }
}