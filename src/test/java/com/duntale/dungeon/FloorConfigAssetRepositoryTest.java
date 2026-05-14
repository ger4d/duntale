package com.duntale.dungeon;

import com.duntale.dungeon.config.asset.FloorConfigDefaultAsset;
import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.common.plugin.PluginManifest;
import com.hypixel.hytale.common.semver.Semver;
import com.hypixel.hytale.common.semver.SemverRange;
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

@DisplayName("FloorConfigAssetRepository")
class FloorConfigAssetRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Should format floor IDs as three digits")
    void shouldFormatFloorIdsAsThreeDigits() {
        assertEquals("001", FloorConfigAssetRepository.formatFloorAssetId(1));
        assertEquals("005", FloorConfigAssetRepository.formatFloorAssetId(5));
        assertEquals("060", FloorConfigAssetRepository.formatFloorAssetId(60));
        assertEquals("120", FloorConfigAssetRepository.formatFloorAssetId(120));
    }

    @Test
    @DisplayName("Should build the floor-config target path inside the selected pack")
    void shouldBuildTheFloorConfigTargetPathInsideTheSelectedPack() {
        AssetPack pack = createPack("com.example:Overrides", tempDir.resolve("overrides"), false, Map.of());

        Path targetPath = FloorConfigAssetRepository.buildAbsoluteAssetPath(pack, "005");

        assertEquals(
                tempDir.resolve("overrides").resolve("Server").resolve(FloorConfigDefaultAsset.ASSET_PATH)
                        .resolve("005.json"),
                targetPath);
    }

    @Test
    @DisplayName("Should mark immutable packs invalid")
    void shouldMarkImmutablePacksInvalid() {
        AssetPack basePack = createPack("com.duntale:Duntale", tempDir.resolve("base"), true, Map.of());
        AssetPack immutablePack = createPack(
                "com.example:Immutable",
                tempDir.resolve("immutable"),
                true,
                Map.of(new PluginIdentifier("com.duntale", "Duntale"), SemverRange.WILDCARD));

        FloorConfigAssetRepository repository = new FloorConfigAssetRepository(new TestRuntime(List.of(basePack, immutablePack)));

        FloorConfigAssetRepository.PackChoice choice = repository.listPackChoices().get(1);
        assertFalse(choice.isValidTarget());
        assertFalse(choice.writable());
        assertFalse(choice.loadsAfterBasePack());
        assertEquals("pack is immutable", choice.status());
    }

    @Test
    @DisplayName("Should mark packs loaded before or equal to the shipped pack invalid")
    void shouldMarkPacksLoadedBeforeOrEqualToTheShippedPackInvalid() {
        AssetPack earlyPack = createPack(
                "com.example:Early",
                tempDir.resolve("early"),
                false,
                Map.of(new PluginIdentifier("com.duntale", "Duntale"), SemverRange.WILDCARD));
        AssetPack basePack = createPack("com.duntale:Duntale", tempDir.resolve("base"), true, Map.of());

        FloorConfigAssetRepository repository = new FloorConfigAssetRepository(new TestRuntime(List.of(earlyPack, basePack)));

        FloorConfigAssetRepository.PackChoice choice = repository.listPackChoices().getFirst();
        assertFalse(choice.isValidTarget());
        assertTrue(choice.writable());
        assertFalse(choice.loadsAfterBasePack());
        assertEquals("pack must load after com.duntale:Duntale", choice.status());
    }

    @Test
    @DisplayName("Should mark writable packs loaded after the shipped pack valid")
    void shouldMarkWritablePacksLoadedAfterTheShippedPackValid() {
        AssetPack basePack = createPack("com.duntale:Duntale", tempDir.resolve("base"), true, Map.of());
        AssetPack overridePack = createPack(
                "com.example:Overrides",
                tempDir.resolve("overrides"),
                false,
                Map.of(new PluginIdentifier("com.duntale", "Duntale"), SemverRange.WILDCARD));

        FloorConfigAssetRepository repository = new FloorConfigAssetRepository(new TestRuntime(List.of(basePack, overridePack)));

        FloorConfigAssetRepository.PackChoice choice = repository.listPackChoices().get(1);
        assertTrue(choice.isValidTarget());
        assertTrue(choice.writable());
        assertTrue(choice.loadsAfterBasePack());
        assertEquals("valid target", choice.status());
    }

    @Test
    @DisplayName("Should detect whether a persisted floor asset exists in the selected pack")
    void shouldDetectWhetherAPersistedFloorAssetExistsInTheSelectedPack() throws IOException {
        AssetPack basePack = createPack("com.duntale:Duntale", tempDir.resolve("base"), true, Map.of());
        AssetPack overridePack = createPack(
                "com.example:Overrides",
                tempDir.resolve("overrides"),
                false,
                Map.of(new PluginIdentifier("com.duntale", "Duntale"), SemverRange.WILDCARD));
        FloorConfigAssetRepository repository = new FloorConfigAssetRepository(new TestRuntime(List.of(basePack, overridePack)));

        assertFalse(repository.hasAssetOverride(7, overridePack.getName()));

        Path assetPath = FloorConfigAssetRepository.buildAbsoluteAssetPath(overridePack, "007");
        Files.createDirectories(assetPath.getParent());
        Files.writeString(assetPath, "{}");

        assertTrue(repository.hasAssetOverride(7, overridePack.getName()));
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

    private static final class TestRuntime implements FloorConfigAssetRepository.AssetRuntime {

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
        @Nonnull
        public AssetStore<String, FloorConfigDefaultAsset, DefaultAssetMap<String, FloorConfigDefaultAsset>>
        getFloorConfigAssetStore() {
            throw new UnsupportedOperationException("Not needed for pack-choice tests");
        }
    }
}