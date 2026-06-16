package com.duntale.dungeon;

import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FloorConfigServiceTest {

    @Test
    void getDayTimesForFloorDefaultsToNineteenWhenNoAssetsExist() {
        FloorConfigService service = new FloorConfigService(new TestRepository(), TreeMap::new);

        assertEquals(List.of(19), service.getDayTimesForFloor(1));
    }

    @Test
    void getDayTimesForFloorNormalizesExplicitValuesAndInheritsByFloor() {
        FloorConfigService service = new FloorConfigService(new TestRepository(), () -> assetDefaults(
                Map.entry(3, Map.<String, Object>of(
                        "dayTime", List.of(6, "12", 19, 12),
                        "theme.variants", List.of("crypt")
                )),
                Map.entry(6, Map.<String, Object>of(
                        "dayTime", List.of(21),
                        "theme.variants", List.of("crypt")
                ))
        ));

        assertEquals(List.of(6, 12, 19), service.getDayTimesForFloor(5));
        assertEquals(List.of(21), service.getDayTimesForFloor(7));
    }

    @Test
    void getDayTimesForFloorRejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class,
                () -> floorConfigServiceWithDayTime(List.of()).getDayTimesForFloor(1));
        assertThrows(IllegalArgumentException.class,
                () -> floorConfigServiceWithDayTime(List.of(-1)).getDayTimesForFloor(1));
        assertThrows(IllegalArgumentException.class,
                () -> floorConfigServiceWithDayTime(List.of(24)).getDayTimesForFloor(1));
        assertThrows(IllegalArgumentException.class,
                () -> floorConfigServiceWithDayTime(List.of("nope")).getDayTimesForFloor(1));
    }

    @Test
    void getCombatConfigForFloorDefaultsToInertWhenNoOverrides() {
        FloorConfigService service = new FloorConfigService(new TestRepository(), TreeMap::new);

        FloorConfigService.CombatConfig combat = service.getCombatConfigForFloor(1);

        assertEquals(0.0, combat.eliteRate());
        assertEquals(1.0, combat.difficultyMult());
    }

    @Test
    void getCombatConfigForFloorParsesOverridesAndInheritsByFloor() {
        FloorConfigService service = new FloorConfigService(new TestRepository(), () -> assetDefaults(
                Map.entry(3, Map.of(
                        "theme.variants", List.of("crypt"),
                        "combat.eliteRate", 0.25,
                        "combat.difficultyMult", 1.5
                )),
                Map.entry(6, Map.of(
                        "theme.variants", List.of("crypt"),
                        "combat.eliteRate", 0.1,
                        "combat.difficultyMult", 2.0
                ))
        ));

        // Floor 5 inherits floor 3's combat overrides.
        FloorConfigService.CombatConfig atFive = service.getCombatConfigForFloor(5);
        assertEquals(0.25, atFive.eliteRate());
        assertEquals(1.5, atFive.difficultyMult());

        // Floor 7 inherits floor 6's combat overrides.
        FloorConfigService.CombatConfig atSeven = service.getCombatConfigForFloor(7);
        assertEquals(0.1, atSeven.eliteRate());
        assertEquals(2.0, atSeven.difficultyMult());
    }

    @Test
    void getCombatConfigForFloorFallsBackToDefaultForMissingKnob() {
        // Only eliteRate authored; difficultyMult should fall back to the inert 1.0 default.
        FloorConfigService service = new FloorConfigService(new TestRepository(), () -> assetDefaults(
                Map.entry(2, Map.of(
                        "theme.variants", List.of("crypt"),
                        "combat.eliteRate", 0.3
                ))
        ));

        FloorConfigService.CombatConfig combat = service.getCombatConfigForFloor(2);

        assertEquals(0.3, combat.eliteRate());
        assertEquals(1.0, combat.difficultyMult());
    }

    @Test
    void resolveConfigForFloorUsesHighestActiveAssetAtOrBelowFloor() {
    FloorConfigService service = new FloorConfigService(new TestRepository(), () -> assetDefaults(
                Map.entry(3, Map.of(
                        "layout.width", 72,
                        "theme.variants", List.of("crypt"),
                        "pacing.difficultyRamp", 1.4
                )),
                Map.entry(6, Map.of(
                        "layout.width", 96,
                        "theme.variants", List.of("crypt", "arcane"),
                        "pacing.difficultyRamp", 2.1
                ))
        ));

        assertEquals(72, service.resolveConfigForFloor(5, "crypt").layout().width());
        assertEquals(1.4, service.resolveConfigForFloor(5, "crypt").pacing().difficultyRamp());
        assertEquals(96, service.resolveConfigForFloor(7, "crypt").layout().width());
        assertEquals(List.of("crypt", "arcane"), service.getThemeVariantsForFloor(7));
    }

    @Test
    void getEffectiveConfigMarksInheritedFieldsAsNonExplicit() {
    FloorConfigService service = new FloorConfigService(new TestRepository(), () -> assetDefaults(
                Map.entry(4, Map.of(
                        "layout.width", 88,
                        "theme.variants", List.of("crypt")
                ))
        ));

        FloorConfigService.EffectiveConfig exact = service.getEffectiveConfig(4);
        FloorConfigService.EffectiveConfig inherited = service.getEffectiveConfig(5);

        assertEquals(4, exact.baseFloor());
        assertTrue(exact.fields().get("layout.width").explicit());
        assertEquals(88, exact.fields().get("layout.width").value());

        assertEquals(4, inherited.baseFloor());
        assertFalse(inherited.fields().get("layout.width").explicit());
        assertEquals(88, inherited.fields().get("layout.width").value());
    }

    @Test
    void saveAssetOverrideWritesFullSnapshotToRepository() throws Exception {
        TestRepository repository = new TestRepository();

        FloorConfigService service = new FloorConfigService(repository, () -> assetDefaults(
                Map.entry(5, Map.of(
                        "layout.width", 80,
                        "theme.variants", List.of("crypt"),
                        "pacing.difficultyRamp", 1.7
                ))
        ));

        service.saveAssetOverride(5, "DevPack", Map.of(
                "layout.width", 112,
            "dayTime", List.of(6, 12),
                "theme.variants", List.of("crypt", "arcane")
        ));

            BsonDocument document = repository.savedDocument;
        assertEquals(112, document.getInt32("layout.width").getValue());
        assertEquals(1.7, document.getDouble("pacing.difficultyRamp").getValue());
        BsonArray dayTimes = document.getArray("dayTime");
        assertEquals(List.of(6, 12), dayTimes.stream().map(value -> value.asInt32().getValue()).toList());
        BsonArray variants = document.getArray("theme.variants");
        assertEquals(List.of("crypt", "arcane"), variants.stream().map(value -> value.asString().getValue()).toList());
            assertEquals(5, repository.savedFloorLevel);
            assertEquals("DevPack", repository.savedPackName);
    }

    @Test
    void deleteAssetOverrideDelegatesToRepository() throws Exception {
            TestRepository repository = new TestRepository();
            repository.deleteResult = true;

        FloorConfigService service = new FloorConfigService(repository, TreeMap::new);

        assertTrue(service.deleteAssetOverride(9, "DevPack"));
            assertEquals(9, repository.deletedFloorLevel);
            assertEquals("DevPack", repository.deletedPackName);
    }

    @Test
    void hasAssetOverrideDelegatesToRepository() {
        TestRepository repository = new TestRepository();
        repository.hasOverrideResult = true;

        FloorConfigService service = new FloorConfigService(repository, TreeMap::new);

        assertTrue(service.hasAssetOverride(4, "DevPack"));
        assertEquals(4, repository.checkedFloorLevel);
        assertEquals("DevPack", repository.checkedPackName);
    }

    @Test
    void listPackChoicesDelegatesToRepository() {
            TestRepository repository = new TestRepository();
        List<FloorConfigAssetRepository.PackChoice> choices = List.of(
                new FloorConfigAssetRepository.PackChoice("com.duntale:Duntale", true, true, "valid target")
        );
            repository.packChoices = choices;

        FloorConfigService service = new FloorConfigService(repository, TreeMap::new);

        assertSame(choices, service.listPackChoices());
    }

    @SafeVarargs
    private static TreeMap<Integer, Map<String, Object>> assetDefaults(Map.Entry<Integer, Map<String, Object>>... entries) {
        TreeMap<Integer, Map<String, Object>> values = new TreeMap<>();
        for (Map.Entry<Integer, Map<String, Object>> entry : entries) {
            values.put(entry.getKey(), entry.getValue());
        }
        return values;
    }

    private static FloorConfigService floorConfigServiceWithDayTime(Object dayTime) {
        return new FloorConfigService(new TestRepository(), () -> assetDefaults(
                Map.entry(1, Map.<String, Object>of("dayTime", dayTime))
        ));
    }

    private static final class TestRepository extends FloorConfigAssetRepository {

        private int savedFloorLevel;
        private String savedPackName;
        private BsonDocument savedDocument;
        private int deletedFloorLevel;
        private String deletedPackName;
        private int checkedFloorLevel;
        private String checkedPackName;
        private boolean deleteResult;
        private boolean hasOverrideResult;
        private List<PackChoice> packChoices = List.of();

        private TestRepository() {
            super(new NoopRuntime());
        }

        @Override
        public void saveAssetOverride(int floorLevel, String packName, BsonDocument overrides) throws IOException {
            this.savedFloorLevel = floorLevel;
            this.savedPackName = packName;
            this.savedDocument = overrides;
        }

        @Override
        public boolean deleteAssetOverride(int floorLevel, String packName) throws IOException {
            this.deletedFloorLevel = floorLevel;
            this.deletedPackName = packName;
            return deleteResult;
        }

        @Override
        public boolean hasAssetOverride(int floorLevel, String packName) {
            this.checkedFloorLevel = floorLevel;
            this.checkedPackName = packName;
            return hasOverrideResult;
        }

        @Override
        public List<PackChoice> listPackChoices() {
            return packChoices;
        }
    }

    private static final class NoopRuntime implements FloorConfigAssetRepository.AssetRuntime {

        @Override
        public List<com.hypixel.hytale.assetstore.AssetPack> getAssetPacks() {
            return List.of();
        }

        @Override
        public com.hypixel.hytale.assetstore.AssetPack getAssetPack(String packName) {
            return null;
        }

        @Override
        public com.hypixel.hytale.assetstore.AssetStore<
                String,
                com.duntale.dungeon.config.asset.FloorConfigDefaultAsset,
                com.hypixel.hytale.assetstore.map.DefaultAssetMap<String, com.duntale.dungeon.config.asset.FloorConfigDefaultAsset>
                > getFloorConfigAssetStore() {
            return null;
        }
    }
}