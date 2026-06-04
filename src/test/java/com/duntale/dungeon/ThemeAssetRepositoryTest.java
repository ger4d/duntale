package com.duntale.dungeon;

import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.common.plugin.PluginManifest;
import com.hypixel.hytale.common.semver.Semver;
import com.hypixel.hytale.common.semver.SemverRange;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ThemeAssetRepository")
class ThemeAssetRepositoryTest {

    @TempDir
    Path tempDir;

    private static final PluginIdentifier BASE_ID = new PluginIdentifier("com.duntale", "DungeonGenAssets");

    @Test
    @DisplayName("Should build the theme target path inside the selected pack")
    void shouldBuildThemeTargetPath() {
        AssetPack pack = createPack("com.example:Overrides", tempDir.resolve("overrides"), false, Map.of());

        Path targetPath = ThemeAssetRepository.buildAbsoluteAssetPath(pack, "mytown");

        assertEquals(
                tempDir.resolve("overrides").resolve("Server")
                        .resolve(ThemeAssetRepository.THEME_ASSET_PATH).resolve("mytown.json"),
                targetPath);
    }

    @Test
    @DisplayName("Should mark immutable packs invalid")
    void shouldMarkImmutablePacksInvalid() {
        AssetPack basePack = createPack("com.duntale:DungeonGenAssets", tempDir.resolve("base"), true, Map.of());
        AssetPack immutablePack = createPack(
                "com.example:Immutable", tempDir.resolve("immutable"), true,
                Map.of(BASE_ID, SemverRange.WILDCARD));

        ThemeAssetRepository repository = new ThemeAssetRepository(new TestRuntime(List.of(basePack, immutablePack)));

        ThemeAssetRepository.PackChoice choice = repository.listPackChoices().get(1);
        assertFalse(choice.isValidTarget());
        assertFalse(choice.writable());
        assertEquals("pack is immutable", choice.status());
    }

    @Test
    @DisplayName("Should mark packs loaded before the base pack invalid")
    void shouldMarkPacksLoadedBeforeBaseInvalid() {
        AssetPack earlyPack = createPack(
                "com.example:Early", tempDir.resolve("early"), false, Map.of(BASE_ID, SemverRange.WILDCARD));
        AssetPack basePack = createPack("com.duntale:DungeonGenAssets", tempDir.resolve("base"), true, Map.of());

        ThemeAssetRepository repository = new ThemeAssetRepository(new TestRuntime(List.of(earlyPack, basePack)));

        ThemeAssetRepository.PackChoice choice = repository.listPackChoices().getFirst();
        assertFalse(choice.isValidTarget());
        assertTrue(choice.writable());
        assertFalse(choice.loadsAfterBasePack());
        assertEquals("pack must load after com.duntale:DungeonGenAssets", choice.status());
    }

    @Test
    @DisplayName("Should mark writable packs loaded after the base pack valid")
    void shouldMarkWritablePacksAfterBaseValid() {
        AssetPack basePack = createPack("com.duntale:DungeonGenAssets", tempDir.resolve("base"), true, Map.of());
        AssetPack overridePack = createPack(
                "com.example:Overrides", tempDir.resolve("overrides"), false, Map.of(BASE_ID, SemverRange.WILDCARD));

        ThemeAssetRepository repository = new ThemeAssetRepository(new TestRuntime(List.of(basePack, overridePack)));

        ThemeAssetRepository.PackChoice choice = repository.listPackChoices().get(1);
        assertTrue(choice.isValidTarget());
        assertEquals("valid target", choice.status());
    }

    @Test
    @DisplayName("Should save, detect and delete a theme override file")
    void shouldSaveDetectAndDeleteOverride() throws IOException {
        AssetPack basePack = createPack("com.duntale:DungeonGenAssets", tempDir.resolve("base"), true, Map.of());
        AssetPack overridePack = createPack(
                "com.example:Overrides", tempDir.resolve("overrides"), false, Map.of(BASE_ID, SemverRange.WILDCARD));
        ThemeAssetRepository repository = new ThemeAssetRepository(new TestRuntime(List.of(basePack, overridePack)));

        assertFalse(repository.hasAssetOverride("mytown", overridePack.getName()));

        BsonDocument theme = new BsonDocument().append("FillBlock", new BsonString("Rock_Stone_Brick"));
        repository.saveAssetOverride("mytown", overridePack.getName(), theme);

        Path written = ThemeAssetRepository.buildAbsoluteAssetPath(overridePack, "mytown");
        assertTrue(Files.exists(written));
        assertTrue(Files.readString(written).contains("Rock_Stone_Brick"));
        assertTrue(repository.hasAssetOverride("mytown", overridePack.getName()));

        assertTrue(repository.deleteAssetOverride("mytown", overridePack.getName()));
        assertFalse(repository.hasAssetOverride("mytown", overridePack.getName()));
        assertFalse(repository.deleteAssetOverride("mytown", overridePack.getName()));
    }

    @Test
    @DisplayName("Should sweep only preview drafts and skip immutable packs")
    void shouldSweepOnlyDrafts() throws IOException {
        AssetPack basePack = createPack("com.duntale:DungeonGenAssets", tempDir.resolve("base"), true, Map.of());
        AssetPack overridePack = createPack(
                "com.example:Overrides", tempDir.resolve("overrides"), false, Map.of(BASE_ID, SemverRange.WILDCARD));
        ThemeAssetRepository repository = new ThemeAssetRepository(new TestRuntime(List.of(basePack, overridePack)));

        Path themesDir = ThemeAssetRepository.themesDirectory(overridePack);
        Files.createDirectories(themesDir);
        Path keep = themesDir.resolve("Crypt.json");
        Path draftA = themesDir.resolve(ThemeAssetRepository.DRAFT_PREFIX + "a1b2c3.json");
        Path draftB = themesDir.resolve(ThemeAssetRepository.DRAFT_PREFIX + "d4e5f6.json");
        Files.writeString(keep, "{}");
        Files.writeString(draftA, "{}");
        Files.writeString(draftB, "{}");

        // A draft in an immutable pack must never be touched.
        Path immutableDir = ThemeAssetRepository.themesDirectory(basePack);
        Files.createDirectories(immutableDir);
        Path immutableDraft = immutableDir.resolve(ThemeAssetRepository.DRAFT_PREFIX + "zz.json");
        Files.writeString(immutableDraft, "{}");

        int removed = repository.sweepDraftFiles();

        assertEquals(2, removed);
        assertTrue(Files.exists(keep));
        assertFalse(Files.exists(draftA));
        assertFalse(Files.exists(draftB));
        assertTrue(Files.exists(immutableDraft));
    }

    @Nonnull
    private static AssetPack createPack(
            @Nonnull String packName,
            @Nonnull Path root,
            boolean immutable,
            @Nonnull Map<PluginIdentifier, SemverRange> dependencies
    ) {
        String[] parts = packName.split(":", 2);
        PluginManifest manifest = new PluginManifest();
        manifest.setGroup(parts[0]);
        manifest.setName(parts[1]);
        manifest.setVersion(Semver.fromString("1.0.0"));
        for (Map.Entry<PluginIdentifier, SemverRange> entry : dependencies.entrySet()) {
            manifest.injectDependency(entry.getKey(), entry.getValue());
        }
        return new AssetPack(root, packName, root, null, immutable, manifest, AssetPack.PackSource.RUNTIME);
    }

    private static final class TestRuntime implements ThemeAssetRepository.AssetRuntime {

        private final List<AssetPack> packs;

        private TestRuntime(@Nonnull List<AssetPack> packs) {
            this.packs = packs;
        }

        @Override
        @Nonnull
        public List<AssetPack> getAssetPacks() {
            return packs;
        }

        @Override
        @Nullable
        public AssetPack getAssetPack(@Nonnull String packName) {
            return packs.stream().filter(pack -> pack.getName().equals(packName)).findFirst().orElse(null);
        }

        @Override
        public boolean isThemeLoaded(@Nonnull String themeId) {
            return false;
        }
    }
}
