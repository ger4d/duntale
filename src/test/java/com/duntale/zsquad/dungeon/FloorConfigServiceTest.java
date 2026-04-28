package com.duntale.zsquad.dungeon;

import com.duntale.dungeongen.config.DungeonConfig;
import com.duntale.dungeongen.config.LayoutConfig;
import com.duntale.dungeongen.config.PacingConfig;
import com.duntale.dungeongen.config.ThemeConfig;
import com.duntale.zsquad.db.DatabaseProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("FloorConfigService")
class FloorConfigServiceTest {

    @TempDir
    Path tempDir;

    private DatabaseProvider database;
    private FloorConfigRepository repository;
    private FloorConfigService service;
    private TreeMap<Integer, Map<String, Object>> assetDefaults;

    @BeforeEach
    void setUp() throws SQLException {
        database = new DatabaseProvider();
        database.initialize(tempDir.resolve("floor-config-test.db"));

        repository = new FloorConfigRepository(database);
        repository.initialize();

        assetDefaults = new TreeMap<>();
        service = new FloorConfigService(repository, () -> new TreeMap<>(assetDefaults));
        service.loadOnStartup();
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    private static Map<String, Object> toAllValues(FloorConfigService.EffectiveConfig effective) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, FloorConfigService.FieldStatus> entry : effective.fields().entrySet()) {
            values.put(entry.getKey(), entry.getValue().value());
        }
        return values;
    }

    private void putAssetOverride(int floorLevel, String fieldPath, Object value) {
        assetDefaults.computeIfAbsent(floorLevel, ignored -> new LinkedHashMap<>()).put(fieldPath, value);
    }

    // ============================================
    // Rebase resolution
    // ============================================

    @Nested
    @DisplayName("segmented sql resolution")
    class SegmentedSqlResolution {

        @Test
        @DisplayName("Should use all defaults when no overrides are defined")
        void shouldUseAllDefaultsWhenNoOverridesDefined() {
            DungeonConfig config = service.resolveConfigForFloor(1, "crypt");

            assertEquals(LayoutConfig.defaults().maxRooms(), config.layout().maxRooms());
            assertEquals(LayoutConfig.defaults().enemyDensity(), config.layout().enemyDensity());
            assertEquals(PacingConfig.defaults().difficultyRamp(), config.pacing().difficultyRamp());
            assertEquals("crypt", config.theme().palette());
            assertEquals(ThemeConfig.defaults().decayFactor(), config.theme().decayFactor());
        }

        @Test
        @DisplayName("Should use all defaults for any floor when no overrides are defined")
        void shouldUseAllDefaultsForAnyFloorWhenNoOverridesDefined() {
            DungeonConfig floor1 = service.resolveConfigForFloor(1, "crypt");
            DungeonConfig floor50 = service.resolveConfigForFloor(50, "crypt");

            assertEquals(floor1.layout(), floor50.layout());
            assertEquals(floor1.pacing(), floor50.pacing());
            assertEquals(floor1.theme().decayFactor(), floor50.theme().decayFactor());
        }

        @Test
        @DisplayName("Should apply a SQL override starting at its floor within the current asset segment")
        void shouldApplyASqlOverrideStartingAtItsFloorWithinTheCurrentAssetSegment() throws SQLException {
            putAssetOverride(1, "layout.maxRooms", 20);
            putAssetOverride(5, "layout.maxRooms", 28);
            service.setOverride(2, "layout.maxRooms", 30);

            DungeonConfig floor1 = service.resolveConfigForFloor(1, "crypt");
            DungeonConfig floor2 = service.resolveConfigForFloor(2, "crypt");
            DungeonConfig floor4 = service.resolveConfigForFloor(4, "crypt");
            DungeonConfig floor5 = service.resolveConfigForFloor(5, "crypt");

            assertEquals(20, floor1.layout().maxRooms());
            assertEquals(30, floor2.layout().maxRooms());
            assertEquals(30, floor4.layout().maxRooms());
            assertEquals(28, floor5.layout().maxRooms());
        }

        @Test
        @DisplayName("Should use the latest SQL row inside the current asset segment")
        void shouldUseTheLatestSqlRowInsideTheCurrentAssetSegment() throws SQLException {
            putAssetOverride(1, "layout.maxRooms", 20);
            putAssetOverride(5, "layout.maxRooms", 28);
            service.setOverride(2, "layout.maxRooms", 24);
            service.setOverride(4, "layout.maxRooms", 26);

            DungeonConfig floor3 = service.resolveConfigForFloor(3, "crypt");
            DungeonConfig floor4 = service.resolveConfigForFloor(4, "crypt");

            assertEquals(24, floor3.layout().maxRooms());
            assertEquals(26, floor4.layout().maxRooms());
        }

        @Test
        @DisplayName("Should reset to the next shipped asset breakpoint")
        void shouldResetToTheNextShippedAssetBreakpoint() throws SQLException {
            putAssetOverride(1, "layout.maxRooms", 20);
            putAssetOverride(5, "layout.maxRooms", 28);
            putAssetOverride(10, "layout.maxRooms", 36);
            service.setOverride(2, "layout.maxRooms", 24);
            service.setOverride(7, "layout.maxRooms", 32);

            DungeonConfig floor4 = service.resolveConfigForFloor(4, "crypt");
            DungeonConfig floor5 = service.resolveConfigForFloor(5, "crypt");
            DungeonConfig floor9 = service.resolveConfigForFloor(9, "crypt");
            DungeonConfig floor10 = service.resolveConfigForFloor(10, "crypt");

            assertEquals(24, floor4.layout().maxRooms());
            assertEquals(28, floor5.layout().maxRooms());
            assertEquals(32, floor9.layout().maxRooms());
            assertEquals(36, floor10.layout().maxRooms());
        }

        @Test
        @DisplayName("Should use defaults for floors below any defined override")
        void shouldUseDefaultsForFloorsBelowAnyDefinedOverride() throws SQLException {
            service.setOverride(10, "layout.maxRooms", 40);

            DungeonConfig config = service.resolveConfigForFloor(5, "crypt");

            assertEquals(LayoutConfig.defaults().maxRooms(), config.layout().maxRooms());
        }

        @Test
        @DisplayName("Should merge multiple fields from the same base floor")
        void shouldMergeMultipleFieldsFromSameBaseFloor() throws SQLException {
            putAssetOverride(1, "layout.maxRooms", 20);
            service.setOverride(1, "layout.maxRooms", 30);
            service.setOverride(1, "layout.enemyDensity", 0.8);
            service.setOverride(1, "pacing.difficultyRamp", 0.9);

            DungeonConfig config = service.resolveConfigForFloor(4, "crypt");

            assertEquals(30, config.layout().maxRooms());
            assertEquals(0.8, config.layout().enemyDensity(), 0.001);
            assertEquals(0.9, config.pacing().difficultyRamp(), 0.001);
        }
    }

    // ============================================
    // Layered defaults
    // ============================================

    @Nested
    @DisplayName("layered defaults")
    class LayeredDefaults {

        @Test
        @DisplayName("Should use code defaults below the first asset breakpoint")
        void shouldUseCodeDefaultsBelowTheFirstAssetBreakpoint() {
            putAssetOverride(5, "layout.maxRooms", 28);

            DungeonConfig config = service.resolveConfigForFloor(4, "crypt");

            assertEquals(LayoutConfig.defaults().maxRooms(), config.layout().maxRooms());
            assertEquals(List.of("crypt"), service.getThemeVariantsForFloor(4));
        }

        @Test
        @DisplayName("Should apply asset defaults when no SQL override exists")
        void shouldApplyAssetDefaultsWhenNoSqlOverrideExists() {
            putAssetOverride(5, "layout.maxRooms", 28);
            putAssetOverride(5, "theme.variants", List.of("CRYPT", "Arcane", "crypt"));

            DungeonConfig config = service.resolveConfigForFloor(8, "hive");

            assertEquals(28, config.layout().maxRooms());
            assertEquals("hive", config.theme().palette());
            assertEquals(List.of("crypt", "arcane"), service.getThemeVariantsForFloor(8));
        }

        @Test
        @DisplayName("Should merge code defaults then asset defaults then SQL overrides")
        void shouldMergeCodeDefaultsThenAssetDefaultsThenSqlOverrides() throws SQLException {
            putAssetOverride(5, "layout.maxRooms", 28);
            putAssetOverride(5, "layout.waterFrequency", 0.22);
            putAssetOverride(5, "theme.variants", List.of("arcane", "hive"));
            service.setOverride(8, "layout.maxRooms", 44);

            DungeonConfig config = service.resolveConfigForFloor(8, "crypt");
            DungeonConfig nextFloorConfig = service.resolveConfigForFloor(9, "crypt");

            assertEquals(44, config.layout().maxRooms());
            assertEquals(0.22, config.layout().waterFrequency(), 0.001);
            assertEquals(List.of("arcane", "hive"), service.getThemeVariantsForFloor(8));
            assertEquals(44, nextFloorConfig.layout().maxRooms());
        }

        @Test
        @DisplayName("Should let SQL overrides beat asset defaults for the same field")
        void shouldLetSqlOverridesBeatAssetDefaultsForTheSameField() throws SQLException {
            putAssetOverride(10, "pacing.difficultyRamp", 0.9);
            service.setOverride(10, "pacing.difficultyRamp", 0.65);

            DungeonConfig config = service.resolveConfigForFloor(10, "crypt");

            assertEquals(0.65, config.pacing().difficultyRamp(), 0.001);
        }

        @Test
        @DisplayName("Should keep shipped breakpoint defaults when a lower segment has SQL overrides")
        void shouldKeepShippedBreakpointDefaultsWhenALowerSegmentHasSqlOverrides() throws SQLException {
            putAssetOverride(5, "layout.maxRooms", 28);
            putAssetOverride(10, "layout.maxRooms", 36);
            service.setOverride(1, "layout.maxRooms", 44);

            DungeonConfig floor1 = service.resolveConfigForFloor(1, "crypt");
            DungeonConfig floor7 = service.resolveConfigForFloor(7, "crypt");
            DungeonConfig floor12 = service.resolveConfigForFloor(12, "crypt");

            assertEquals(44, floor1.layout().maxRooms());
            assertEquals(28, floor7.layout().maxRooms());
            assertEquals(36, floor12.layout().maxRooms());
        }

        @Test
        @DisplayName("Should reject invalid theme IDs in layered data")
        void shouldRejectInvalidThemeIdsInLayeredData() {
            putAssetOverride(1, "theme.variants", List.of("crypt", "bad_theme"));

            assertThrows(IllegalArgumentException.class,
                    () -> service.getThemeVariantsForFloor(1));
        }

        @Test
        @DisplayName("Should support injected synthetic asset defaults without a live asset registry")
        void shouldSupportInjectedSyntheticAssetDefaultsWithoutALiveAssetRegistry() {
            putAssetOverride(20, "layout.maxRooms", 52);

            DungeonConfig config = service.resolveConfigForFloor(20, "crypt");

            assertEquals(52, config.layout().maxRooms());
        }
    }

    // ============================================
    // Theme variants
    // ============================================

    @Nested
    @DisplayName("theme variants")
    class ThemeVariants {

        @Test
        @DisplayName("Should register theme variants and default them to crypt")
        void shouldRegisterThemeVariantsAndDefaultThemToCrypt() {
            assertTrue(FloorConfigService.isValidField("theme.variants"));
            assertEquals(List.of("crypt"), service.getThemeVariantsForFloor(1));
            assertEquals(List.of("crypt"),
                    service.getEffectiveConfig(1).fields().get("theme.variants").value());
        }

        @Test
        @DisplayName("Should normalize theme variants from the asset layer")
        void shouldNormalizeThemeVariantsFromTheAssetLayer() {
            putAssetOverride(1, "theme.variants", List.of("CRYPT", "arcane", "crypt", "Arcane", "hive"));

            assertEquals(List.of("crypt", "arcane", "hive"), service.getThemeVariantsForFloor(1));
        }
    }

    // ============================================
    // Theme palette
    // ============================================

    @Nested
    @DisplayName("theme palette")
    class ThemePalette {

        @Test
        @DisplayName("Should always use the provided theme palette, not from overrides")
        void shouldAlwaysUseProvidedThemePalette() throws SQLException {
            service.setOverride(1, "theme.decayFactor", 0.8);

            DungeonConfig config = service.resolveConfigForFloor(1, "volcanic");

            assertEquals("volcanic", config.theme().palette());
            assertEquals(0.8, config.theme().decayFactor(), 0.001);
        }

        @Test
        @DisplayName("Should preserve theme palette across different floors")
        void shouldPreserveThemePaletteAcrossFloors() {
            DungeonConfig cryptConfig = service.resolveConfigForFloor(5, "crypt");
            DungeonConfig volcanicConfig = service.resolveConfigForFloor(5, "volcanic");

            assertEquals("crypt", cryptConfig.theme().palette());
            assertEquals("volcanic", volcanicConfig.theme().palette());
        }
    }

    // ============================================
    // Override mutations
    // ============================================

    @Nested
    @DisplayName("override mutations")
    class OverrideMutations {

        @Test
        @DisplayName("Should clear a single field override")
        void shouldClearSingleFieldOverride() throws SQLException {
            service.setOverride(1, "layout.maxRooms", 30);
            service.setOverride(1, "layout.enemyDensity", 0.8);

            service.clearOverride(1, "layout.maxRooms");

            DungeonConfig config = service.resolveConfigForFloor(1, "crypt");
            assertEquals(LayoutConfig.defaults().maxRooms(), config.layout().maxRooms());
            assertEquals(0.8, config.layout().enemyDensity(), 0.001);
        }

        @Test
        @DisplayName("Should delete floor row when clearing the last field")
        void shouldDeleteFloorRowWhenClearingLastField() throws SQLException {
            service.setOverride(5, "layout.maxRooms", 30);

            service.clearOverride(5, "layout.maxRooms");

            List<Integer> floors = service.listDefinedFloors();
            assertFalse(floors.contains(5));
        }

        @Test
        @DisplayName("Should clear all overrides for a floor")
        void shouldClearAllOverridesForFloor() throws SQLException {
            service.setOverride(1, "layout.maxRooms", 30);
            service.setOverride(1, "layout.enemyDensity", 0.8);

            service.clearFloor(1);

            DungeonConfig config = service.resolveConfigForFloor(1, "crypt");
            assertEquals(LayoutConfig.defaults().maxRooms(), config.layout().maxRooms());
            assertEquals(LayoutConfig.defaults().enemyDensity(), config.layout().enemyDensity());
            assertTrue(service.listDefinedFloors().isEmpty());
        }

        @Test
        @DisplayName("Should overwrite an existing override value")
        void shouldOverwriteExistingOverrideValue() throws SQLException {
            service.setOverride(1, "layout.maxRooms", 30);
            service.setOverride(1, "layout.maxRooms", 50);

            DungeonConfig config = service.resolveConfigForFloor(1, "crypt");
            assertEquals(50, config.layout().maxRooms());
        }

        @Test
        @DisplayName("Should reject unknown field path for setOverride")
        void shouldRejectUnknownFieldForSet() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.setOverride(1, "layout.nonexistent", 42));
        }

        @Test
        @DisplayName("Should reject unknown field path for clearOverride")
        void shouldRejectUnknownFieldForClear() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.clearOverride(1, "layout.nonexistent"));
        }
    }

    // ============================================
    // Bulk save
    // ============================================

    @Nested
    @DisplayName("bulk save")
    class BulkSave {

        @Test
        @DisplayName("Should preserve inherited asset values without materializing them as SQL")
        void shouldPreserveInheritedAssetValuesWithoutMaterializingThemAsSql() throws SQLException {
            putAssetOverride(10, "layout.removeCeiling", true);
            putAssetOverride(10, "layout.solidFill", false);

            Map<String, Object> allValues = toAllValues(service.getEffectiveConfig(15));
            allValues.put("layout.maxRooms", 30);

            service.bulkSaveOverrides(15, allValues);

            DungeonConfig config = service.resolveConfigForFloor(15, "crypt");
            assertEquals(30, config.layout().maxRooms());
            assertTrue(config.layout().removeCeiling());
            assertFalse(config.layout().solidFill());

            FloorConfigService.EffectiveConfig effective = service.getEffectiveConfig(15);
            assertFalse(effective.fields().get("layout.removeCeiling").explicit());
            assertFalse(effective.fields().get("layout.solidFill").explicit());
            assertTrue(effective.fields().get("layout.maxRooms").explicit());
        }

        @Test
        @DisplayName("Should drop fields that match the inherited baseline when re-saving a floor")
        void shouldDropFieldsThatMatchTheInheritedBaselineWhenResavingAFloor() throws SQLException {
            putAssetOverride(10, "layout.removeCeiling", true);
            putAssetOverride(10, "layout.solidFill", false);
            service.setOverride(12, "layout.maxRooms", 35);
            service.setOverride(12, "layout.removeCeiling", false);

            Map<String, Object> allValues = toAllValues(service.getEffectiveConfig(14));
            allValues.put("layout.solidFill", false);

            service.bulkSaveOverrides(14, allValues);

            DungeonConfig config = service.resolveConfigForFloor(14, "crypt");
            assertEquals(35, config.layout().maxRooms());
            assertFalse(config.layout().removeCeiling());
            assertFalse(config.layout().solidFill());

            FloorConfigService.EffectiveConfig effective = service.getEffectiveConfig(14);
            assertFalse(effective.fields().get("layout.removeCeiling").explicit());
            assertFalse(effective.fields().get("layout.solidFill").explicit());
            assertFalse(effective.fields().get("layout.maxRooms").explicit());
        }
    }

    // ============================================
    // Effective config display
    // ============================================

    @Nested
    @DisplayName("effective config display")
    class EffectiveConfigDisplay {

        @Test
        @DisplayName("Should mark all fields as inherited when no overrides exist")
        void shouldMarkAllFieldsAsInheritedWhenNoOverrides() {
            FloorConfigService.EffectiveConfig effective = service.getEffectiveConfig(1);

            assertNull(effective.baseFloor());
            for (FloorConfigService.FieldStatus status : effective.fields().values()) {
                assertFalse(status.explicit());
            }
        }

        @Test
        @DisplayName("Should mark explicitly set fields")
        void shouldMarkExplicitlySetFields() throws SQLException {
            service.setOverride(1, "layout.maxRooms", 30);

            FloorConfigService.EffectiveConfig effective = service.getEffectiveConfig(1);

            assertEquals(1, effective.baseFloor());
            assertTrue(effective.fields().get("layout.maxRooms").explicit());
            assertEquals(30, effective.fields().get("layout.maxRooms").value());
            assertFalse(effective.fields().get("layout.enemyDensity").explicit());
        }

        @Test
        @DisplayName("Should show the inherited SQL floor within the current asset segment")
        void shouldShowTheInheritedSqlFloorWithinTheCurrentAssetSegment() throws SQLException {
            putAssetOverride(10, "layout.maxRooms", 40);
            service.setOverride(12, "layout.maxRooms", 44);

            FloorConfigService.EffectiveConfig effective = service.getEffectiveConfig(15);

            assertEquals(12, effective.baseFloor());
            assertFalse(effective.fields().get("layout.maxRooms").explicit());
            assertEquals(44, effective.fields().get("layout.maxRooms").value());
        }
    }

    // ============================================
    // Field value parsing
    // ============================================

    @Nested
    @DisplayName("field value parsing")
    class FieldValueParsing {

        @Test
        @DisplayName("Should parse integer field values")
        void shouldParseIntegerFieldValues() {
            Object result = FloorConfigService.parseFieldValue("layout.maxRooms", "30");
            assertEquals(30, result);
        }

        @Test
        @DisplayName("Should parse double field values")
        void shouldParseDoubleFieldValues() {
            Object result = FloorConfigService.parseFieldValue("layout.enemyDensity", "0.75");
            assertEquals(0.75, result);
        }

        @Test
        @DisplayName("Should parse boolean field values")
        void shouldParseBooleanFieldValues() {
            Object result = FloorConfigService.parseFieldValue("layout.bossRoom", "true");
            assertEquals(true, result);
        }

        @Test
        @DisplayName("Should parse string field values")
        void shouldParseStringFieldValues() {
            Object result = FloorConfigService.parseFieldValue("layout.roomShape", "circular");
            assertEquals("circular", result);
        }

        @Test
        @DisplayName("Should parse string list field values")
        void shouldParseStringListFieldValues() {
            Object result = FloorConfigService.parseFieldValue("theme.variants", "Crypt, arcane, crypt");
            assertEquals(List.of("crypt", "arcane"), result);
        }

        @Test
        @DisplayName("Should reject invalid integer")
        void shouldRejectInvalidInteger() {
            assertThrows(IllegalArgumentException.class,
                    () -> FloorConfigService.parseFieldValue("layout.maxRooms", "abc"));
        }

        @Test
        @DisplayName("Should reject invalid boolean")
        void shouldRejectInvalidBoolean() {
            assertThrows(IllegalArgumentException.class,
                    () -> FloorConfigService.parseFieldValue("layout.bossRoom", "maybe"));
        }

        @Test
        @DisplayName("Should reject unknown field path")
        void shouldRejectUnknownFieldPath() {
            assertThrows(IllegalArgumentException.class,
                    () -> FloorConfigService.parseFieldValue("unknown.field", "42"));
        }
    }

    // ============================================
    // Floor listing
    // ============================================

    @Nested
    @DisplayName("floor listing")
    class FloorListing {

        @Test
        @DisplayName("Should return empty list when no overrides defined")
        void shouldReturnEmptyListWhenNoOverrides() throws SQLException {
            assertTrue(service.listDefinedFloors().isEmpty());
        }

        @Test
        @DisplayName("Should return defined floors in ascending order")
        void shouldReturnDefinedFloorsInOrder() throws SQLException {
            service.setOverride(30, "layout.maxRooms", 50);
            service.setOverride(1, "layout.maxRooms", 20);
            service.setOverride(10, "layout.maxRooms", 35);

            List<Integer> floors = service.listDefinedFloors();
            assertEquals(List.of(1, 10, 30), floors);
        }
    }

    // ============================================
    // JSON round-trip
    // ============================================

    @Nested
    @DisplayName("JSON round-trip")
    class JsonRoundTrip {

        @Test
        @DisplayName("Should serialize and deserialize overrides correctly")
        void shouldSerializeAndDeserializeOverrides() throws SQLException {
            service.setOverride(1, "layout.maxRooms", 30);
            service.setOverride(1, "layout.enemyDensity", 0.75);
            service.setOverride(1, "layout.bossRoom", true);
            service.setOverride(1, "layout.roomShape", "circular");
            service.setOverride(1, "theme.variants", List.of("crypt", "arcane", "crypt"));

            // Force reload from database
            FloorConfigService freshService = new FloorConfigService(repository, () -> new TreeMap<>(assetDefaults));
            freshService.loadOnStartup();

            DungeonConfig config = freshService.resolveConfigForFloor(1, "crypt");
            assertEquals(30, config.layout().maxRooms());
            assertEquals(0.75, config.layout().enemyDensity(), 0.001);
            assertTrue(config.layout().bossRoom());
            assertEquals("circular", config.layout().roomShape());
            assertEquals(List.of("crypt", "arcane"), freshService.getThemeVariantsForFloor(1));
        }

        @Test
        @DisplayName("Should normalize persisted theme variant casing on startup")
        void shouldNormalizePersistedThemeVariantCasingOnStartup() throws SQLException {
            repository.save(1, "{\"theme.variants\": [\"Arcane\", \"CRYPT\", \"arcane\"], \"layout.width\": 50}");

            FloorConfigService freshService = new FloorConfigService(repository, () -> new TreeMap<>(assetDefaults));
            freshService.loadOnStartup();

            assertEquals(List.of("arcane", "crypt"), freshService.getThemeVariantsForFloor(1));
            assertEquals(
                    "{\"layout.width\": 50, \"theme.variants\": [\"arcane\", \"crypt\"]}",
                    repository.load(1).orElseThrow());
        }
    }

    // ============================================
    // Field registry
    // ============================================

    @Nested
    @DisplayName("field registry")
    class FieldRegistry {

        @Test
        @DisplayName("Should recognize all layout fields")
        void shouldRecognizeAllLayoutFields() {
            assertTrue(FloorConfigService.isValidField("layout.maxRooms"));
            assertTrue(FloorConfigService.isValidField("layout.enemyDensity"));
            assertTrue(FloorConfigService.isValidField("layout.complexity"));
        }

        @Test
        @DisplayName("Should recognize theme fields but not palette")
        void shouldRecognizeThemeFieldsButNotPalette() {
            assertTrue(FloorConfigService.isValidField("theme.variants"));
            assertTrue(FloorConfigService.isValidField("theme.decayFactor"));
            assertTrue(FloorConfigService.isValidField("theme.overgrowthFactor"));
            assertTrue(FloorConfigService.isValidField("theme.floodingFactor"));
            assertFalse(FloorConfigService.isValidField("theme.palette"));
        }

        @Test
        @DisplayName("Should recognize pacing fields")
        void shouldRecognizePacingFields() {
            assertTrue(FloorConfigService.isValidField("pacing.breatheRoomFrequency"));
            assertTrue(FloorConfigService.isValidField("pacing.difficultyRamp"));
        }

        @Test
        @DisplayName("Should reject unknown fields")
        void shouldRejectUnknownFields() {
            assertFalse(FloorConfigService.isValidField("unknown.field"));
            assertFalse(FloorConfigService.isValidField("layout.nonexistent"));
        }

        @Test
        @DisplayName("Should have all supported fields in the registry")
        void shouldHaveAllSupportedFields() {
            Set<String> fields = FloorConfigService.getSupportedFields();
            assertNotNull(fields);
            assertFalse(fields.isEmpty());
            // 32 layout + 4 theme + 2 pacing = 38 fields
            assertEquals(38, fields.size());
        }
    }

    // ============================================
    // Integration with DungeonInstanceService
    // ============================================

    @Nested
    @DisplayName("DungeonInstanceService integration")
    class ServiceIntegration {

        @Test
        @DisplayName("Should use floor config overrides in generation config")
        void shouldUseFloorConfigOverridesInGenerationConfig() throws SQLException {
            service.setOverride(1, "layout.maxRooms", 30);
            service.setOverride(1, "pacing.difficultyRamp", 0.9);

            DungeonConfig config = service.resolveConfigForFloor(1, "mine");

            assertEquals(30, config.layout().maxRooms());
            assertEquals(0.9, config.pacing().difficultyRamp(), 0.001);
            assertEquals("mine", config.theme().palette());
        }

        @Test
        @DisplayName("Should scale difficulty across floors via rebase")
        void shouldScaleDifficultyAcrossFloorsViaRebase() throws SQLException {
            service.setOverride(1, "layout.maxRooms", 15);
            service.setOverride(1, "layout.enemyDensity", 0.3);
            service.setOverride(10, "layout.maxRooms", 25);
            service.setOverride(10, "layout.enemyDensity", 0.6);
            service.setOverride(20, "layout.maxRooms", 40);
            service.setOverride(20, "layout.enemyDensity", 0.9);

            DungeonConfig floor5 = service.resolveConfigForFloor(5, "crypt");
            DungeonConfig floor15 = service.resolveConfigForFloor(15, "crypt");
            DungeonConfig floor25 = service.resolveConfigForFloor(25, "crypt");

            assertEquals(15, floor5.layout().maxRooms());
            assertEquals(0.3, floor5.layout().enemyDensity(), 0.001);

            assertEquals(25, floor15.layout().maxRooms());
            assertEquals(0.6, floor15.layout().enemyDensity(), 0.001);

            assertEquals(40, floor25.layout().maxRooms());
            assertEquals(0.9, floor25.layout().enemyDensity(), 0.001);
        }
    }
}
