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

    @BeforeEach
    void setUp() throws SQLException {
        database = new DatabaseProvider();
        database.initialize(tempDir.resolve("floor-config-test.db"));

        repository = new FloorConfigRepository(database);
        repository.initialize();

        service = new FloorConfigService(repository);
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

    // ============================================
    // Rebase resolution
    // ============================================

    @Nested
    @DisplayName("rebase resolution")
    class RebaseResolution {

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
        @DisplayName("Should apply single floor override at exact floor")
        void shouldApplySingleFloorOverrideAtExactFloor() throws SQLException {
            service.setOverride(1, "layout.maxRooms", 30);

            DungeonConfig config = service.resolveConfigForFloor(1, "crypt");

            assertEquals(30, config.layout().maxRooms());
            assertEquals(LayoutConfig.defaults().enemyDensity(), config.layout().enemyDensity());
        }

        @Test
        @DisplayName("Should inherit floor 1 overrides for floors below next defined floor")
        void shouldInheritFloor1OverridesForFloorsBelowNextDefined() throws SQLException {
            service.setOverride(1, "layout.maxRooms", 25);
            service.setOverride(10, "layout.maxRooms", 40);

            DungeonConfig floor7 = service.resolveConfigForFloor(7, "crypt");

            assertEquals(25, floor7.layout().maxRooms());
        }

        @Test
        @DisplayName("Should use floor 10 overrides for floors 10-29 when 10 and 30 are defined")
        void shouldUseFloor10OverridesForMiddleRange() throws SQLException {
            service.setOverride(1, "layout.maxRooms", 20);
            service.setOverride(10, "layout.maxRooms", 35);
            service.setOverride(30, "layout.maxRooms", 50);

            DungeonConfig floor15 = service.resolveConfigForFloor(15, "crypt");

            assertEquals(35, floor15.layout().maxRooms());
        }

        @Test
        @DisplayName("Should use highest defined floor for floors beyond the last override")
        void shouldUseHighestDefinedFloorForFloorsBeyondLast() throws SQLException {
            service.setOverride(1, "layout.maxRooms", 20);
            service.setOverride(30, "layout.maxRooms", 50);

            DungeonConfig floor100 = service.resolveConfigForFloor(100, "crypt");

            assertEquals(50, floor100.layout().maxRooms());
        }

        @Test
        @DisplayName("Should use exact match when floor has defined overrides")
        void shouldUseExactMatchWhenFloorHasOverrides() throws SQLException {
            service.setOverride(10, "layout.maxRooms", 40);

            DungeonConfig config = service.resolveConfigForFloor(10, "crypt");

            assertEquals(40, config.layout().maxRooms());
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
            service.setOverride(1, "layout.maxRooms", 30);
            service.setOverride(1, "layout.enemyDensity", 0.8);
            service.setOverride(1, "pacing.difficultyRamp", 0.9);

            DungeonConfig config = service.resolveConfigForFloor(1, "crypt");

            assertEquals(30, config.layout().maxRooms());
            assertEquals(0.8, config.layout().enemyDensity(), 0.001);
            assertEquals(0.9, config.pacing().difficultyRamp(), 0.001);
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
        @DisplayName("Should preserve inherited non-default values when defining a new floor snapshot")
        void shouldPreserveInheritedNonDefaultValuesWhenDefiningNewFloorSnapshot() throws SQLException {
            service.setOverride(1, "layout.removeCeiling", true);
            service.setOverride(1, "layout.solidFill", false);

            Map<String, Object> allValues = toAllValues(service.getEffectiveConfig(15));
            allValues.put("layout.maxRooms", 30);

            service.bulkSaveOverrides(15, allValues);

            DungeonConfig config = service.resolveConfigForFloor(15, "crypt");
            assertEquals(30, config.layout().maxRooms());
            assertTrue(config.layout().removeCeiling());
            assertFalse(config.layout().solidFill());

            FloorConfigService.EffectiveConfig effective = service.getEffectiveConfig(15);
            assertTrue(effective.fields().get("layout.removeCeiling").explicit());
            assertTrue(effective.fields().get("layout.solidFill").explicit());
        }

        @Test
        @DisplayName("Should preserve values that match a lower floor when re-saving an existing floor")
        void shouldPreserveValuesThatMatchALowerFloorWhenResavingAnExistingFloor() throws SQLException {
            service.setOverride(1, "layout.removeCeiling", true);
            service.setOverride(1, "layout.solidFill", false);
            service.setOverride(10, "layout.maxRooms", 35);

            Map<String, Object> allValues = toAllValues(service.getEffectiveConfig(10));
            allValues.put("layout.removeCeiling", true);
            allValues.put("layout.solidFill", false);

            service.bulkSaveOverrides(10, allValues);

            DungeonConfig config = service.resolveConfigForFloor(10, "crypt");
            assertEquals(35, config.layout().maxRooms());
            assertTrue(config.layout().removeCeiling());
            assertFalse(config.layout().solidFill());

            FloorConfigService.EffectiveConfig effective = service.getEffectiveConfig(10);
            assertTrue(effective.fields().get("layout.removeCeiling").explicit());
            assertTrue(effective.fields().get("layout.solidFill").explicit());
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
        @DisplayName("Should show base floor for inherited configs")
        void shouldShowBaseFloorForInheritedConfigs() throws SQLException {
            service.setOverride(10, "layout.maxRooms", 40);

            FloorConfigService.EffectiveConfig effective = service.getEffectiveConfig(15);

            assertEquals(10, effective.baseFloor());
            assertFalse(effective.fields().get("layout.maxRooms").explicit());
            assertEquals(40, effective.fields().get("layout.maxRooms").value());
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

            // Force reload from database
            FloorConfigService freshService = new FloorConfigService(repository);
            freshService.loadOnStartup();

            DungeonConfig config = freshService.resolveConfigForFloor(1, "crypt");
            assertEquals(30, config.layout().maxRooms());
            assertEquals(0.75, config.layout().enemyDensity(), 0.001);
            assertTrue(config.layout().bossRoom());
            assertEquals("circular", config.layout().roomShape());
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
            // 32 layout + 3 theme + 2 pacing = 37 fields
            assertEquals(37, fields.size());
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
