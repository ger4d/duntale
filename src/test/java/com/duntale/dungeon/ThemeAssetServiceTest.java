package com.duntale.dungeon;

import com.hypixel.hytale.assetstore.AssetPack;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ThemeAssetService")
class ThemeAssetServiceTest {

    @Test
    @DisplayName("Should accept valid theme IDs")
    void shouldAcceptValidThemeIds() {
        ThemeAssetService service = new ThemeAssetService(new RecordingRepository());
        assertTrue(service.isValidThemeId("crypt"));
        assertTrue(service.isValidThemeId("Crypt"));
        assertTrue(service.isValidThemeId("my_town-2"));
        assertTrue(service.isValidThemeId("Temple_Dark"));
        assertTrue(service.isValidThemeId("a".repeat(64)));
    }

    @Test
    @DisplayName("Should reject malformed theme IDs")
    void shouldRejectMalformedThemeIds() {
        ThemeAssetService service = new ThemeAssetService(new RecordingRepository());
        assertFalse(service.isValidThemeId(null));
        assertFalse(service.isValidThemeId(""));
        assertFalse(service.isValidThemeId(" "));
        assertFalse(service.isValidThemeId("1abc"));
        assertFalse(service.isValidThemeId("a b"));
        assertFalse(service.isValidThemeId("a/b"));
        assertFalse(service.isValidThemeId(".."));
        assertFalse(service.isValidThemeId("_preview_x"));
        assertFalse(service.isValidThemeId("a".repeat(65)));
    }

    @Test
    @DisplayName("saveTheme should reject an invalid ID before touching the repository")
    void saveThemeRejectsInvalidId() {
        RecordingRepository repository = new RecordingRepository();
        ThemeAssetService service = new ThemeAssetService(repository);

        assertThrows(IllegalArgumentException.class,
                () -> service.saveTheme("_preview_x", "pack", new BsonDocument()));
        assertEquals(0, repository.saveCount);
    }

    @Test
    @DisplayName("saveTheme should delegate to the repository with the given document")
    void saveThemeDelegates() throws IOException {
        RecordingRepository repository = new RecordingRepository();
        ThemeAssetService service = new ThemeAssetService(repository);
        BsonDocument theme = new BsonDocument().append("FillBlock", new BsonString("Rock_Stone_Brick"));

        service.saveTheme("mytown", "pack", theme);

        assertEquals(1, repository.saveCount);
        assertEquals("mytown", repository.lastThemeId);
        assertEquals("pack", repository.lastPackName);
        assertSame(theme, repository.lastTheme);
    }

    @Test
    @DisplayName("saveTheme should propagate an IOException from the repository")
    void saveThemePropagatesIoException() {
        RecordingRepository repository = new RecordingRepository();
        repository.failOnSave = true;
        ThemeAssetService service = new ThemeAssetService(repository);

        assertThrows(IOException.class, () -> service.saveTheme("mytown", "pack", new BsonDocument()));
    }

    @Test
    @DisplayName("Draft save and delete should round-trip through the repository")
    void draftRoundTrip() throws IOException {
        RecordingRepository repository = new RecordingRepository();
        ThemeAssetService service = new ThemeAssetService(repository);
        BsonDocument theme = new BsonDocument();

        Path written = service.saveDraft("_preview_abc", "pack", theme);
        assertEquals(repository.draftPath, written);
        assertEquals("_preview_abc", repository.lastDraftId);
        assertSame(theme, repository.lastTheme);
        assertTrue(service.deleteDraft("_preview_abc", "pack"));
        assertEquals("_preview_abc", repository.lastDeletedDraftId);
    }

    /** Hand-rolled fake (no Mockito on this project's test classpath). */
    private static final class RecordingRepository extends ThemeAssetRepository {

        final Path draftPath = Path.of("/tmp/_preview_abc.json");
        int saveCount;
        boolean failOnSave;
        @Nullable String lastThemeId;
        @Nullable String lastPackName;
        @Nullable BsonDocument lastTheme;
        @Nullable String lastDraftId;
        @Nullable String lastDeletedDraftId;

        private RecordingRepository() {
            super(new NoopRuntime());
        }

        @Override
        public void saveAssetOverride(@Nonnull String themeId, @Nonnull String packName, @Nonnull BsonDocument theme)
                throws IOException {
            if (failOnSave) {
                throw new IOException("disk full");
            }
            saveCount++;
            lastThemeId = themeId;
            lastPackName = packName;
            lastTheme = theme;
        }

        @Override
        @Nonnull
        public Path saveDraftFile(@Nonnull String draftThemeId, @Nonnull String packName, @Nonnull BsonDocument theme) {
            lastDraftId = draftThemeId;
            lastPackName = packName;
            lastTheme = theme;
            return draftPath;
        }

        @Override
        public boolean deleteDraftFile(@Nonnull String draftThemeId, @Nonnull String packName) {
            lastDeletedDraftId = draftThemeId;
            return true;
        }
    }

    private static final class NoopRuntime implements ThemeAssetRepository.AssetRuntime {
        @Override
        @Nonnull
        public List<AssetPack> getAssetPacks() {
            return List.of();
        }

        @Override
        @Nullable
        public AssetPack getAssetPack(@Nonnull String packName) {
            return null;
        }

        @Override
        public boolean isThemeLoaded(@Nonnull String themeId) {
            return false;
        }
    }
}
