package com.duntale.config.asset;

import com.duntale.CustomizeCharacterConfig;
import com.duntale.config.RawBsonDocumentCodec;
import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.server.core.asset.HytaleAssetStore;
import org.bson.BsonDocument;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Asset-store wrapper for the character customization entry-flow config.
 */
public class CustomizeCharacterConfigAsset
        implements JsonAssetWithMap<String, IndexedLookupTableAssetMap<String, CustomizeCharacterConfigAsset>> {

    public static final String ASSET_PATH = "Configs/EntryFlow";
    private static final String ASSET_ID = "CustomizeCharacter";
    private static final ArrayCodec<BsonDocument> DOCUMENT_ARRAY_CODEC =
            new ArrayCodec<>(RawBsonDocumentCodec.INSTANCE, BsonDocument[]::new);

    public static AssetBuilderCodec<String, CustomizeCharacterConfigAsset> CODEC;
    private static AssetStore<String, CustomizeCharacterConfigAsset,
            IndexedLookupTableAssetMap<String, CustomizeCharacterConfigAsset>> assetStore;

    protected String id;
    protected AssetExtraInfo.Data data;
    protected String defaultCompanionRole = "Companion_Wolf_Black";
    protected BsonDocument[] setupSlots = new BsonDocument[0];
    protected BsonDocument camera = new BsonDocument();
    protected BsonDocument companionOffset = new BsonDocument();

    public CustomizeCharacterConfigAsset() {
    }

    static {
        CODEC = AssetBuilderCodec.builder(
                        CustomizeCharacterConfigAsset.class,
                        CustomizeCharacterConfigAsset::new,
                        Codec.STRING,
                        (asset, key) -> asset.id = key,
                        asset -> asset.id,
                        (asset, extra) -> asset.data = extra,
                        asset -> asset.data
                )
                .append(new KeyedCodec<>("DefaultCompanionRole", Codec.STRING),
                        (asset, value) -> asset.defaultCompanionRole = value,
                        asset -> asset.defaultCompanionRole)
                .add()
                .append(new KeyedCodec<>("SetupSlots", DOCUMENT_ARRAY_CODEC),
                        (asset, value) -> asset.setupSlots = value,
                        asset -> asset.setupSlots)
                .add()
                .append(new KeyedCodec<>("Camera", RawBsonDocumentCodec.INSTANCE),
                        (asset, value) -> asset.camera = value,
                        asset -> asset.camera)
                .add()
                .append(new KeyedCodec<>("CompanionOffset", RawBsonDocumentCodec.INSTANCE),
                        (asset, value) -> asset.companionOffset = value,
                        asset -> asset.companionOffset)
                .add()
                .build();
    }

    @Nonnull
    public static HytaleAssetStore.Builder<String, CustomizeCharacterConfigAsset,
            IndexedLookupTableAssetMap<String, CustomizeCharacterConfigAsset>> assetStoreBuilder() {
        return HytaleAssetStore.builder(
                        CustomizeCharacterConfigAsset.class,
                        new IndexedLookupTableAssetMap<>(CustomizeCharacterConfigAsset[]::new))
                .setPath(ASSET_PATH)
                .setCodec(CODEC)
                .setKeyFunction(CustomizeCharacterConfigAsset::getId)
                .setReplaceOnRemove(id -> null);
    }

    @Nullable
    public static CustomizeCharacterConfigAsset get() {
        return ((IndexedLookupTableAssetMap<String, CustomizeCharacterConfigAsset>) getAssetStore().getAssetMap())
                .getAsset(ASSET_ID);
    }

    @Nullable
    public static CustomizeCharacterConfig getConfig() {
        CustomizeCharacterConfigAsset asset = get();
        return asset != null ? CustomizeCharacterConfig.fromAsset(asset) : null;
    }

    @Nonnull
    public static AssetStore<String, CustomizeCharacterConfigAsset,
            IndexedLookupTableAssetMap<String, CustomizeCharacterConfigAsset>> getAssetStore() {
        if (assetStore == null) {
            assetStore = AssetRegistry.getAssetStore(CustomizeCharacterConfigAsset.class);
        }
        return Objects.requireNonNull(assetStore, "CustomizeCharacterConfigAsset asset store is not registered");
    }

    @Override
    public String getId() {
        return id;
    }

    @Nonnull
    public String getDefaultCompanionRole() {
        return defaultCompanionRole;
    }

    @Nonnull
    public BsonDocument[] getSetupSlots() {
        return setupSlots;
    }

    @Nonnull
    public BsonDocument getCamera() {
        return camera;
    }

    @Nonnull
    public BsonDocument getCompanionOffset() {
        return companionOffset;
    }
}