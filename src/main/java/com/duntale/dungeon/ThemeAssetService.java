package com.duntale.dungeon;

import com.hypixel.hytale.logger.HytaleLogger;
import org.bson.BsonDocument;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Orchestrates authoring of dungeon-theme assets on top of {@link ThemeAssetRepository}.
 *
 * <p>Validates theme IDs, delegates persistence and draft lifecycle to the repository, and exposes
 * the pack-choice list to the editing UI. Theme documents are supplied as pre-built
 * {@link BsonDocument}s because {@code DungeonThemeConfig} cannot be constructed field-by-field from
 * outside the dungeon-gen library.
 *
 * @since 1.9.0
 */
public class ThemeAssetService {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Theme IDs must start with a letter and contain only letters, digits, {@code _} or {@code -}. */
    static final Pattern THEME_ID_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{0,63}$");

    private final ThemeAssetRepository repository;

    /**
     * Creates a new theme asset service.
     *
     * @param repository the backing theme asset repository
     */
    public ThemeAssetService(@Nonnull ThemeAssetRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    /**
     * Validates and persists a theme document as {@code <themeId>.json} in the selected pack.
     *
     * @param themeId  the theme ID (validated against {@link #THEME_ID_PATTERN})
     * @param packName the target asset-pack name
     * @param theme    the full theme document to persist
     * @throws IOException              if the file cannot be written
     * @throws IllegalArgumentException if the theme ID is invalid or the pack is not a valid target
     */
    public void saveTheme(@Nonnull String themeId, @Nonnull String packName, @Nonnull BsonDocument theme)
            throws IOException {
        validateThemeId(themeId);
        Objects.requireNonNull(packName, "packName");
        Objects.requireNonNull(theme, "theme");
        repository.saveAssetOverride(themeId, packName, theme);
    }

    /**
     * Deletes a theme override file from the selected pack.
     *
     * @param themeId  the theme ID to remove
     * @param packName the target asset-pack name
     * @return {@code true} when an override file existed and was removed
     * @throws IOException if the file cannot be deleted
     */
    public boolean deleteOverride(@Nonnull String themeId, @Nonnull String packName) throws IOException {
        return repository.deleteAssetOverride(themeId, packName);
    }

    /**
     * Returns whether a theme override file exists in the selected pack.
     *
     * @param themeId  the theme ID to inspect
     * @param packName the target asset-pack name
     * @return {@code true} if the pack contains the theme override file
     */
    public boolean hasAssetOverride(@Nonnull String themeId, @Nonnull String packName) {
        return repository.hasAssetOverride(themeId, packName);
    }

    /**
     * Lists all loaded asset packs with theme-override eligibility metadata.
     *
     * @return ordered pack choices matching the runtime load order
     */
    @Nonnull
    public List<ThemeAssetRepository.PackChoice> listPackChoices() {
        return repository.listPackChoices();
    }

    /**
     * Writes a transient preview-draft theme for the in-progress edit session.
     *
     * @param draftId  the draft asset ID (must start with {@value ThemeAssetRepository#DRAFT_PREFIX})
     * @param packName the target asset-pack name
     * @param theme    the in-progress theme document
     * @return the absolute path written
     * @throws IOException if the draft cannot be written
     */
    @Nonnull
    public Path saveDraft(@Nonnull String draftId, @Nonnull String packName, @Nonnull BsonDocument theme)
            throws IOException {
        return repository.saveDraftFile(draftId, packName, theme);
    }

    /**
     * Deletes the transient preview-draft theme for an edit session.
     *
     * @param draftId  the draft asset ID
     * @param packName the target asset-pack name
     * @return {@code true} when the draft existed and was removed
     * @throws IOException if the draft cannot be deleted
     */
    public boolean deleteDraft(@Nonnull String draftId, @Nonnull String packName) throws IOException {
        return repository.deleteDraftFile(draftId, packName);
    }

    /**
     * Removes orphaned preview-draft files from all writable packs.
     *
     * @return the number of draft files deleted
     */
    public int sweepDraftFiles() {
        return repository.sweepDraftFiles();
    }

    /**
     * Returns whether a theme with the given ID is loaded anywhere (used for the override notice).
     *
     * @param themeId the theme ID to check
     * @return {@code true} if such a theme is currently loaded
     */
    public boolean themeIdExists(@Nonnull String themeId) {
        return repository.themeIdExistsAnywhere(themeId);
    }

    /**
     * Returns whether the given theme ID is well-formed per {@link #THEME_ID_PATTERN}.
     *
     * @param themeId the candidate theme ID (may be {@code null})
     * @return {@code true} if the ID is a valid theme slug
     */
    public boolean isValidThemeId(@javax.annotation.Nullable String themeId) {
        return themeId != null && THEME_ID_PATTERN.matcher(themeId).matches();
    }

    private void validateThemeId(@Nonnull String themeId) {
        if (!isValidThemeId(themeId)) {
            throw new IllegalArgumentException(
                    "Invalid theme ID '" + themeId + "': must start with a letter and use only letters, "
                            + "digits, '_' or '-' (max 64 chars)");
        }
    }
}
