package com.duntale.dungeon.config.asset;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.server.core.asset.HytaleAssetStore;
import org.bson.BsonDocument;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Objects;

/**
 * Shipped per-floor default overrides loaded from the Duntale asset pack.
 *
 * <p>Each file under {@value #ASSET_PATH} is keyed by its numeric filename, for example
 * {@code 001.json}. The filename becomes the asset ID, and the numeric ID is parsed as the
 * floor breakpoint that this sparse override snapshot applies to.
 *
 * @since 1.8.0
 */
public class FloorConfigDefaultAsset
    implements JsonAssetWithMap<String, DefaultAssetMap<String, FloorConfigDefaultAsset>> {

    public static final String ASSET_PATH = "Configs/FloorConfig";

    public static AssetBuilderCodec<String, FloorConfigDefaultAsset> CODEC;
    private static AssetStore<String, FloorConfigDefaultAsset,
            DefaultAssetMap<String, FloorConfigDefaultAsset>> ASSET_STORE;

    protected String id;
    protected AssetExtraInfo.Data data;
    protected BsonDocument overrides = new BsonDocument();

    public FloorConfigDefaultAsset() {
    }

    static {
        CODEC = AssetBuilderCodec.builder(
                        FloorConfigDefaultAsset.class,
                        FloorConfigDefaultAsset::new,
                        Codec.STRING,
                        (asset, key) -> asset.id = key,
                        asset -> asset.id,
                        (asset, extra) -> asset.data = extra,
                        asset -> asset.data
                )
                .append(new KeyedCodec<>("Overrides", Codec.BSON_DOCUMENT),
                        (asset, value) -> asset.overrides = value,
                        asset -> asset.overrides)
                .add()
                .build();
    }

    @Nonnull
    public static HytaleAssetStore.Builder<String, FloorConfigDefaultAsset,
            DefaultAssetMap<String, FloorConfigDefaultAsset>> assetStoreBuilder() {
        return HytaleAssetStore.builder(
                        FloorConfigDefaultAsset.class,
                        new DefaultAssetMap<>())
                .setPath(ASSET_PATH)
                .setCodec(CODEC)
                .setKeyFunction(FloorConfigDefaultAsset::getId);
    }

    @Nullable
    public static FloorConfigDefaultAsset get(@Nonnull String id) {
        return getAssetMap().getAsset(id);
    }

    @Nonnull
    public static AssetStore<String, FloorConfigDefaultAsset,
            DefaultAssetMap<String, FloorConfigDefaultAsset>> getAssetStore() {
        if (ASSET_STORE == null) {
            ASSET_STORE = AssetRegistry.getAssetStore(FloorConfigDefaultAsset.class);
        }
        return Objects.requireNonNull(ASSET_STORE, "FloorConfigDefaultAsset asset store is not registered");
    }

    @Nonnull
    public static DefaultAssetMap<String, FloorConfigDefaultAsset> getAssetMap() {
        return (DefaultAssetMap<String, FloorConfigDefaultAsset>) getAssetStore().getAssetMap();
    }

    @Nonnull
    public static Collection<FloorConfigDefaultAsset> getAll() {
        return getAssetMap().getAssetMap().values();
    }

    /**
     * Creates a writable floor-config asset snapshot for the provided floor asset ID.
     *
     * @param id        the canonical floor asset ID, for example {@code "005"}
     * @param overrides the full overrides document to persist
     * @return a new floor-config asset ready for {@link AssetStore#writeAssetToDisk}
     */
    @Nonnull
    public static FloorConfigDefaultAsset fromOverrides(@Nonnull String id, @Nonnull BsonDocument overrides) {
        FloorConfigDefaultAsset asset = new FloorConfigDefaultAsset();
        asset.id = Objects.requireNonNull(id, "id");
        asset.overrides = Objects.requireNonNull(overrides, "overrides");
        return asset;
    }

    @Override
    public String getId() {
        return id;
    }

    /**
     * Returns the numeric floor breakpoint encoded in the filename-derived asset ID.
     *
     * @return the parsed floor breakpoint
     * @throws IllegalArgumentException if the asset ID is not a numeric floor value
     */
    public int getFloorLevel() {
        try {
            return Integer.parseInt(id);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Floor config default asset ID must be numeric: " + id, e);
        }
    }

    /**
     * Returns the raw BSON overrides document loaded from the asset file.
     *
     * @return the raw overrides document
     */
    @Nonnull
    public BsonDocument getOverridesDocument() {
        return overrides;
    }
}