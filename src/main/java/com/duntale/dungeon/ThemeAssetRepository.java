package com.duntale.dungeon;

import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.duntale.dungeongen.config.asset.DungeonThemeConfig;
import org.bson.BsonDocument;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Persists authored dungeon-theme assets as asset-pack JSON files.
 *
 * <p>Themes are stored under {@value #THEME_ASSET_PATH}{@code /<id>.json} in a writable asset
 * pack. The target pack must be writable and must load after the shipped DungeonGen theme pack so
 * that the saved asset remains the active runtime version after restart. A theme ID that matches a
 * shipped theme functions as an override (later-loading pack wins).
 *
 * <p>Unlike {@link FloorConfigAssetRepository}, a theme is supplied as a pre-built
 * {@link BsonDocument} rather than a typed asset: {@link DungeonThemeConfig} and its nested entry
 * types expose no setters or builder, and the dungeon-gen library is out of scope for this feature,
 * so the document is assembled by the caller and written verbatim. The engine asset monitor decodes
 * the file back into a {@link DungeonThemeConfig} on hot-reload.
 *
 * @since 1.9.0
 */
public class ThemeAssetRepository {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** On-disk asset path (relative to {@code <pack>/Server}) for theme JSON files. */
    public static final String THEME_ASSET_PATH = "Configs/DungeonGen/Themes";

    /**
     * Fixed asset ID for the transient preview theme written during authoring.
     *
     * <p>Two hard constraints shape this value:
     * <ul>
     *   <li><b>Letter-leading, no leading {@code _}</b> — the asset monitor treats {@code _}-prefixed
     *       files as hidden/reserved and never loads them, so the preview would never render.</li>
     *   <li><b>Single, overwrite-only, never deleted</b> — {@code DungeonThemeConfig} uses an
     *       {@code IndexedLookupTableAssetMap} whose {@code requireReplaceOnRemove()} is {@code true};
     *       deleting a theme asset that has no replacement in another pack crashes the monitor with
     *       "Replacement can't be null!". A fixed ID lets the preview be rewritten in place and never
     *       removed, sidestepping that engine limitation.</li>
     * </ul>
     *
     * <p>The {@code Zz} prefix keeps it sorted last and easy to filter out of theme lists.
     */
    public static final String DRAFT_PREFIX = "Zz_Theme_Preview";

    private static final List<PluginIdentifier> BASE_PACK_IDENTIFIERS = List.of(
            new PluginIdentifier("com.duntale", "DungeonGenAssets"),
            new PluginIdentifier("com.duntale", "DungeonGen")
    );

    private final AssetRuntime runtime;

    /**
     * Creates a new theme asset repository backed by the live Hytale asset runtime.
     */
    public ThemeAssetRepository() {
        this(new LiveAssetRuntime());
    }

    ThemeAssetRepository(@Nonnull AssetRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    /**
     * Saves a full theme document into the selected asset pack as {@code <themeId>.json}.
     *
     * @param themeId  the theme asset ID (filename without extension)
     * @param packName the target asset-pack name
     * @param theme    the full theme document to persist
     * @throws IOException              if the file cannot be written
     * @throws IllegalArgumentException if the pack is missing or is not a valid override target
     */
    public void saveAssetOverride(@Nonnull String themeId, @Nonnull String packName, @Nonnull BsonDocument theme)
            throws IOException {
        Objects.requireNonNull(themeId, "themeId");
        Objects.requireNonNull(packName, "packName");
        Objects.requireNonNull(theme, "theme");

        AssetPack pack = requireValidPack(packName);
        Path absolutePath = buildAbsoluteAssetPath(pack, themeId);
        LOGGER.atInfo().log(
                "ThemeAssetRepository save start theme=%s pack=%s path=%s",
                themeId,
                packName,
                absolutePath);
        writeThemeFile(absolutePath, theme);
        LOGGER.atInfo().log(
                "ThemeAssetRepository save disk write complete theme=%s pack=%s path=%s; waiting for asset monitor reload",
                themeId,
                packName,
                absolutePath);
    }

    /**
     * Deletes the selected theme asset override from a target pack.
     *
     * @param themeId  the theme asset ID to remove
     * @param packName the target asset-pack name
     * @return {@code true} when a pack-local override file existed and was removed
     * @throws IOException              if the file cannot be deleted
     * @throws IllegalArgumentException if the pack is missing or is not a valid override target
     */
    public boolean deleteAssetOverride(@Nonnull String themeId, @Nonnull String packName) throws IOException {
        Objects.requireNonNull(themeId, "themeId");
        Objects.requireNonNull(packName, "packName");

        AssetPack pack = requireValidPack(packName);
        Path absolutePath = buildAbsoluteAssetPath(pack, themeId);
        if (!Files.exists(absolutePath)) {
            return false;
        }
        Files.delete(absolutePath);
        LOGGER.atInfo().log(
                "ThemeAssetRepository delete complete theme=%s pack=%s path=%s; waiting for asset monitor removal",
                themeId,
                packName,
                absolutePath);
        return true;
    }

    /**
     * Returns whether a theme override asset currently exists in the selected pack.
     *
     * @param themeId  the theme asset ID to inspect
     * @param packName the target asset-pack name
     * @return {@code true} if the pack currently contains the theme asset file
     * @throws IllegalArgumentException if the pack is missing or is not a valid override target
     */
    public boolean hasAssetOverride(@Nonnull String themeId, @Nonnull String packName) {
        Objects.requireNonNull(themeId, "themeId");
        Objects.requireNonNull(packName, "packName");

        AssetPack pack = requireValidPack(packName);
        return Files.exists(buildAbsoluteAssetPath(pack, themeId));
    }

    /**
     * Writes a transient preview-draft theme file into the selected pack.
     *
     * @param draftThemeId the draft asset ID (must start with {@value #DRAFT_PREFIX})
     * @param packName     the target asset-pack name
     * @param theme        the in-progress theme document to preview
     * @return the absolute path that was written
     * @throws IOException              if the file cannot be written
     * @throws IllegalArgumentException if the pack is invalid or the ID is not a draft ID
     */
    @Nonnull
    public Path saveDraftFile(@Nonnull String draftThemeId, @Nonnull String packName, @Nonnull BsonDocument theme)
            throws IOException {
        requireDraftId(draftThemeId);
        AssetPack pack = requireValidPack(packName);
        Path absolutePath = buildAbsoluteAssetPath(pack, draftThemeId);
        writeThemeFile(absolutePath, theme);
        return absolutePath;
    }

    /**
     * Deletes a transient preview-draft theme file from the selected pack.
     *
     * @param draftThemeId the draft asset ID (must start with {@value #DRAFT_PREFIX})
     * @param packName     the target asset-pack name
     * @return {@code true} when the draft file existed and was removed
     * @throws IOException              if the file cannot be deleted
     * @throws IllegalArgumentException if the pack is invalid or the ID is not a draft ID
     */
    public boolean deleteDraftFile(@Nonnull String draftThemeId, @Nonnull String packName) throws IOException {
        requireDraftId(draftThemeId);
        AssetPack pack = requireValidPack(packName);
        Path absolutePath = buildAbsoluteAssetPath(pack, draftThemeId);
        if (!Files.exists(absolutePath)) {
            return false;
        }
        Files.delete(absolutePath);
        return true;
    }

    /**
     * Removes orphaned {@value #DRAFT_PREFIX}{@code *.json} draft files from every writable pack.
     *
     * <p>Called on plugin startup and shutdown to clean up drafts left behind by crashes or abrupt
     * disconnects. Immutable packs are skipped.
     *
     * @return the number of draft files deleted
     */
    public int sweepDraftFiles() {
        int removed = 0;
        for (AssetPack pack : runtime.getAssetPacks()) {
            if (pack.isImmutable()) {
                continue;
            }
            Path themesDir = themesDirectory(pack);
            if (!Files.isDirectory(themesDir)) {
                continue;
            }
            // Sweep current drafts plus any legacy "_preview_*" files from before the prefix change.
            for (String glob : List.of(DRAFT_PREFIX + "*.json", "_preview_*.json")) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(themesDir, glob)) {
                    for (Path draft : stream) {
                        try {
                            Files.deleteIfExists(draft);
                            removed++;
                        } catch (IOException e) {
                            LOGGER.atWarning().withCause(e).log("ThemeAssetRepository failed to delete draft %s", draft);
                        }
                    }
                } catch (IOException e) {
                    LOGGER.atWarning().withCause(e).log("ThemeAssetRepository failed to sweep drafts in %s", themesDir);
                }
            }
        }
        if (removed > 0) {
            LOGGER.atInfo().log("ThemeAssetRepository swept %d orphaned preview draft(s)", removed);
        }
        return removed;
    }

    /**
     * Returns whether a theme with the given ID is currently loaded anywhere in the asset registry.
     *
     * <p>Used by the editor to surface the "this will override shipped theme" notice.
     *
     * @param themeId the theme ID to check
     * @return {@code true} if a theme asset with this ID is loaded
     */
    public boolean themeIdExistsAnywhere(@Nonnull String themeId) {
        return runtime.isThemeLoaded(Objects.requireNonNull(themeId, "themeId"));
    }

    /**
     * Lists all currently loaded asset packs with theme-override eligibility metadata.
     *
     * @return ordered pack choices matching the runtime load order
     */
    @Nonnull
    public List<PackChoice> listPackChoices() {
        return describePackChoices(runtime.getAssetPacks()).choices();
    }

    @Nonnull
    SaveTarget resolveSaveTarget(@Nonnull String themeId, @Nonnull String packName) {
        AssetPack pack = requireValidPack(packName);
        return new SaveTarget(pack.getName(), buildAbsoluteAssetPath(pack, themeId), themeId);
    }

    private void writeThemeFile(@Nonnull Path absolutePath, @Nonnull BsonDocument theme) throws IOException {
        Files.createDirectories(absolutePath.getParent());
        Files.writeString(
                absolutePath,
                theme.toJson(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    @Nonnull
    static Path assetFileName(@Nonnull String assetId) {
        return Path.of(Objects.requireNonNull(assetId, "assetId") + ".json");
    }

    @Nonnull
    static Path themesDirectory(@Nonnull AssetPack pack) {
        return pack.getRoot().resolve("Server").resolve(THEME_ASSET_PATH);
    }

    @Nonnull
    static Path buildAbsoluteAssetPath(@Nonnull AssetPack pack, @Nonnull String assetId) {
        Objects.requireNonNull(pack, "pack");
        return themesDirectory(pack).resolve(assetFileName(assetId));
    }

    private static void requireDraftId(@Nonnull String draftThemeId) {
        if (!Objects.requireNonNull(draftThemeId, "draftThemeId").startsWith(DRAFT_PREFIX)) {
            throw new IllegalArgumentException("Draft theme ID must start with " + DRAFT_PREFIX + ": " + draftThemeId);
        }
    }

    @Nonnull
    static PackChoice evaluatePackChoice(
            @Nonnull AssetPack pack,
            int packIndex,
            @Nullable BasePackInfo basePackInfo
    ) {
        Objects.requireNonNull(pack, "pack");
        if (basePackInfo == null) {
            return new PackChoice(pack.getName(), !pack.isImmutable(), false, "base theme pack is not loaded");
        }
        if (pack.isImmutable()) {
            return new PackChoice(pack.getName(), false, false, "pack is immutable");
        }
        if (packIndex <= basePackInfo.index()) {
            return new PackChoice(pack.getName(), true, false, "pack must load after " + basePackInfo.name());
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
                    "Asset pack " + packName + " is not a valid theme target: " + choice.status());
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
            choicesByName.put(pack.getName(), evaluatePackChoice(pack, index, basePackInfo));
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
     * Describes one loaded asset pack as a potential theme save target.
     *
     * @param name               the runtime asset-pack name
     * @param writable           whether the pack can be edited on disk
     * @param loadsAfterBasePack whether the pack loads after the shipped DungeonGen theme pack
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
         * Returns whether this pack is currently safe to use for theme overrides.
         *
         * @return {@code true} when the pack is writable and loads after the shipped pack
         */
        public boolean isValidTarget() {
            return writable && loadsAfterBasePack;
        }
    }

    /**
     * The computed disk target for a theme asset.
     *
     * @param packName  the target asset-pack name
     * @param assetPath the absolute on-disk asset path
     * @param assetId   the theme asset ID
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

        boolean isThemeLoaded(@Nonnull String themeId);
    }

    record BasePackInfo(@Nonnull String name, int index, @Nonnull PluginIdentifier identifier) {
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
        public boolean isThemeLoaded(@Nonnull String themeId) {
            return DungeonThemeConfig.get(themeId) != null;
        }
    }
}
