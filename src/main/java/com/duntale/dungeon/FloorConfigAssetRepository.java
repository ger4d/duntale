package com.duntale.dungeon;

import com.duntale.dungeon.config.asset.FloorConfigDefaultAsset;
import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.AssetModule;
import org.bson.BsonDocument;
import org.bson.BsonValue;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Persists floor-config overrides as asset-pack JSON files.
 *
 * <p>Overrides are stored under {@code Server/Configs/FloorConfig/<NNN>.json} in a writable
 * asset pack. The target pack must be writable and must load after the shipped Duntale floor
 * config pack so that the saved asset remains the active runtime version after restart.
 *
 * @since 1.8.0
 */
public class FloorConfigAssetRepository {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final List<PluginIdentifier> BASE_PACK_IDENTIFIERS = List.of(
            new PluginIdentifier("com.duntale", "Duntale")
    );

    private final AssetRuntime runtime;

    /**
     * Creates a new floor-config asset repository backed by the live Hytale asset runtime.
     */
    public FloorConfigAssetRepository() {
        this(new LiveAssetRuntime());
    }

    FloorConfigAssetRepository(@Nonnull AssetRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    /**
     * Saves the full floor-config snapshot for a floor into the selected asset pack.
     *
     * @param floorLevel the floor level to save
     * @param packName   the target asset-pack name
     * @param overrides  the full normalized overrides document to persist
     * @throws IOException              if the file cannot be written
     * @throws IllegalArgumentException if the pack is missing or is not a valid override target
     */
    public void saveAssetOverride(int floorLevel, @Nonnull String packName, @Nonnull BsonDocument overrides)
            throws IOException {
        Objects.requireNonNull(packName, "packName");
        Objects.requireNonNull(overrides, "overrides");

        AssetPack pack = requireValidPack(packName);
        String assetId = formatFloorAssetId(floorLevel);
        Path absolutePath = buildAbsoluteAssetPath(pack, assetId);

        LOGGER.atInfo().log(
            "FloorConfigAssetRepository save start floor=%d pack=%s assetId=%s path=%s overrideCount=%d",
            floorLevel,
            packName,
            assetId,
            absolutePath,
            overrides.size());
        Files.createDirectories(absolutePath.getParent());
        writeAssetFile(absolutePath, assetId, overrides);
        LOGGER.atInfo().log(
            "FloorConfigAssetRepository save disk write complete floor=%d pack=%s assetId=%s path=%s; waiting for asset monitor reload",
            floorLevel,
            packName,
            assetId,
            absolutePath);
    }

    /**
     * Deletes the selected floor-config asset override from a target pack.
     *
     * @param floorLevel the floor level to reset
     * @param packName   the target asset-pack name
     * @return {@code true} when a pack-local override file existed and was removed
     * @throws IOException              if the file cannot be deleted
     * @throws IllegalArgumentException if the pack is missing or is not a valid override target
     */
    public boolean deleteAssetOverride(int floorLevel, @Nonnull String packName) throws IOException {
        Objects.requireNonNull(packName, "packName");

        AssetPack pack = requireValidPack(packName);
        String assetId = formatFloorAssetId(floorLevel);
        Path absolutePath = buildAbsoluteAssetPath(pack, assetId);
        LOGGER.atInfo().log(
            "FloorConfigAssetRepository delete start floor=%d pack=%s assetId=%s path=%s exists=%s",
            floorLevel,
            packName,
            assetId,
            absolutePath,
            Files.exists(absolutePath));
        if (!Files.exists(absolutePath)) {
            return false;
        }

        Files.delete(absolutePath);
        LOGGER.atInfo().log(
        "FloorConfigAssetRepository delete disk complete floor=%d pack=%s assetId=%s path=%s; waiting for asset monitor removal",
            floorLevel,
            packName,
            assetId,
        absolutePath);
        return true;
    }

    /**
     * Returns whether a floor-config override asset currently exists in the selected pack.
     *
     * @param floorLevel the floor level to inspect
     * @param packName   the target asset-pack name
     * @return {@code true} if the pack currently contains the floor-config asset file
     * @throws IllegalArgumentException if the pack is missing or is not a valid override target
     */
    public boolean hasAssetOverride(int floorLevel, @Nonnull String packName) {
        Objects.requireNonNull(packName, "packName");

        AssetPack pack = requireValidPack(packName);
        String assetId = formatFloorAssetId(floorLevel);
        return Files.exists(buildAbsoluteAssetPath(pack, assetId));
    }
    private void writeAssetFile(
        @Nonnull Path absolutePath,
        @Nonnull String assetId,
        @Nonnull BsonDocument overrides
    ) throws IOException {
    AssetStore<String, FloorConfigDefaultAsset, DefaultAssetMap<String, FloorConfigDefaultAsset>> assetStore =
        runtime.getFloorConfigAssetStore();
    FloorConfigDefaultAsset asset = FloorConfigDefaultAsset.fromOverrides(assetId, overrides);
    AssetExtraInfo.Data data = assetStore.getCodec().getData(asset);
    Object parentId = data == null ? null : data.getParentKey();
    BsonValue bsonValue = assetStore.getCodec().encode(
        asset,
        new AssetExtraInfo<>(
            absolutePath,
            new AssetExtraInfo.Data(
                FloorConfigDefaultAsset.class,
                assetId,
                assetStore.transformKey(parentId))));
    Files.writeString(
        absolutePath,
        bsonValue.toString(),
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
        StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * Lists all currently loaded asset packs with floor-config override eligibility metadata.
     *
     * @return ordered pack choices matching the runtime load order
     */
    @Nonnull
    public List<PackChoice> listPackChoices() {
        return describePackChoices(runtime.getAssetPacks()).choices();
    }

    @Nonnull
    SaveTarget resolveSaveTarget(int floorLevel, @Nonnull String packName) {
        AssetPack pack = requireValidPack(packName);
        String assetId = formatFloorAssetId(floorLevel);
        return new SaveTarget(pack.getName(), buildAbsoluteAssetPath(pack, assetId), assetId);
    }

    @Nonnull
    static String formatFloorAssetId(int floorLevel) {
        if (floorLevel < 1) {
            throw new IllegalArgumentException("Floor level must be >= 1");
        }
        return String.format(Locale.ROOT, "%03d", floorLevel);
    }

    @Nonnull
    static Path assetFileName(@Nonnull String assetId) {
        return Path.of(Objects.requireNonNull(assetId, "assetId") + ".json");
    }

    @Nonnull
    static Path buildAbsoluteAssetPath(@Nonnull AssetPack pack, @Nonnull String assetId) {
        Objects.requireNonNull(pack, "pack");
        return pack.getRoot()
                .resolve("Server")
                .resolve(FloorConfigDefaultAsset.ASSET_PATH)
                .resolve(assetFileName(assetId));
    }

    @Nonnull
    static PackChoice evaluatePackChoice(
            @Nonnull AssetPack pack,
            int packIndex,
            @Nullable BasePackInfo basePackInfo
    ) {
        Objects.requireNonNull(pack, "pack");
        if (basePackInfo == null) {
            return new PackChoice(
                    pack.getName(),
                    !pack.isImmutable(),
                    false,
                    "base floor-config pack is not loaded");
        }

        if (pack.isImmutable()) {
            return new PackChoice(pack.getName(), false, false, "pack is immutable");
        }

        if (packIndex <= basePackInfo.index()) {
            return new PackChoice(
                    pack.getName(),
                    true,
                    false,
                    "pack must load after " + basePackInfo.name());
        }

        if (!dependsOnBasePack(pack, basePackInfo.identifier())) {
            return new PackChoice(
                    pack.getName(),
                    true,
                    true,
                    "valid target; add a manifest dependency on " + basePackInfo.name()
                            + " for stable restart precedence");
        }

        return new PackChoice(pack.getName(), true, true, "valid target");
    }

    @Nonnull
    private AssetPack requireValidPack(@Nonnull String packName) {
        AssetPack pack = runtime.getAssetPack(packName);
        if (pack == null) {
            throw new IllegalArgumentException("Unknown asset pack: " + packName);
        }

        PackCatalog catalog = describePackChoices(runtime.getAssetPacks());
        PackChoice choice = catalog.choicesByName().get(packName);
        if (choice == null) {
            throw new IllegalArgumentException("Unknown asset pack: " + packName);
        }
        if (!choice.isValidTarget()) {
            throw new IllegalArgumentException(
                    "Asset pack " + packName + " is not a valid floor-config target: " + choice.status());
        }
        return pack;
    }

    @Nonnull
    private static PackCatalog describePackChoices(@Nonnull List<AssetPack> packs) {
        Objects.requireNonNull(packs, "packs");
        BasePackInfo basePackInfo = resolveBasePackInfo(packs);
        LinkedHashMap<String, PackChoice> choicesByName = new LinkedHashMap<>();
        for (int index = 0; index < packs.size(); index++) {
            AssetPack pack = packs.get(index);
            PackChoice choice = evaluatePackChoice(pack, index, basePackInfo);
            choicesByName.put(pack.getName(), choice);
        }
        return new PackCatalog(List.copyOf(choicesByName.values()), Map.copyOf(choicesByName));
    }

    @Nullable
    private static BasePackInfo resolveBasePackInfo(@Nonnull List<AssetPack> packs) {
        for (PluginIdentifier identifier : BASE_PACK_IDENTIFIERS) {
            String packName = identifier.toString();
            for (int index = 0; index < packs.size(); index++) {
                if (packName.equals(packs.get(index).getName())) {
                    return new BasePackInfo(packName, index, identifier);
                }
            }
        }
        return null;
    }

    private static boolean dependsOnBasePack(@Nonnull AssetPack pack, @Nonnull PluginIdentifier basePackIdentifier) {
        return pack.getManifest().getDependencies().containsKey(basePackIdentifier);
    }

    /**
     * Describes one loaded asset pack as a potential floor-config save target.
     *
     * @param name               the runtime asset-pack name
     * @param writable           whether the pack can be edited on disk
     * @param loadsAfterBasePack whether the pack loads after the shipped Duntale floor-config pack
     * @param status             a user-facing validation message
     */
    public record PackChoice(
            @Nonnull String name,
            boolean writable,
            boolean loadsAfterBasePack,
            @Nonnull String status
    ) {
        public PackChoice {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(status, "status");
        }

        /**
         * Returns whether this pack is currently safe to use for floor-config overrides.
         *
         * @return {@code true} when the pack is writable and loads after the shipped pack
         */
        public boolean isValidTarget() {
            return writable && loadsAfterBasePack;
        }
    }

    /**
     * The computed disk target for a floor-config asset snapshot.
     *
     * @param packName  the target asset-pack name
     * @param assetPath the absolute on-disk asset path
     * @param assetId   the canonical three-digit floor asset ID
     */
    public record SaveTarget(
            @Nonnull String packName,
            @Nonnull Path assetPath,
            @Nonnull String assetId
    ) {
        public SaveTarget {
            Objects.requireNonNull(packName, "packName");
            Objects.requireNonNull(assetPath, "assetPath");
            Objects.requireNonNull(assetId, "assetId");
        }
    }

    interface AssetRuntime {
        @Nonnull
        List<AssetPack> getAssetPacks();

        @Nullable
        AssetPack getAssetPack(@Nonnull String packName);

        @Nonnull
        AssetStore<String, FloorConfigDefaultAsset, DefaultAssetMap<String, FloorConfigDefaultAsset>>
        getFloorConfigAssetStore();
    }

    private record BasePackInfo(
            @Nonnull String name,
            int index,
            @Nonnull PluginIdentifier identifier
    ) {
    }

    private record PackCatalog(
            @Nonnull List<PackChoice> choices,
            @Nonnull Map<String, PackChoice> choicesByName
    ) {
    }

    private static final class LiveAssetRuntime implements AssetRuntime {

        @Override
        @Nonnull
        public List<AssetPack> getAssetPacks() {
            return AssetModule.get().getAssetPacks();
        }

        @Override
        @Nullable
        public AssetPack getAssetPack(@Nonnull String packName) {
            return AssetModule.get().getAssetPack(packName);
        }

        @Override
        @Nonnull
        public AssetStore<String, FloorConfigDefaultAsset, DefaultAssetMap<String, FloorConfigDefaultAsset>>
        getFloorConfigAssetStore() {
            return FloorConfigDefaultAsset.getAssetStore();
        }
    }
}