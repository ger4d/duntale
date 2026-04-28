package com.duntale.zsquad.dungeon;

import com.duntale.dungeongen.config.DungeonConfig;
import com.duntale.dungeongen.config.LayoutConfig;
import com.duntale.dungeongen.config.PacingConfig;
import com.duntale.dungeongen.config.ThemeConfig;
import com.duntale.dungeongen.config.Vec3i;
import com.duntale.dungeongen.util.JsonParser;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Level;

/**
 * Resolves per-floor generation config overrides using a rebase inheritance model.
 *
 * <p>Admins define sparse overrides for specific floor levels. The effective config for floor N
 * is {@link DungeonConfig#withDefaults()} merged with the overrides from the highest defined
 * floor {@code ≤ N} (the "base floor"). If no overrides are defined, all floors use defaults.
 *
 * <p>Theme palette is always instance-chosen (via {@code /dungeon start <theme>}), never from
 * floor config overrides.
 *
 * @since 1.6.0
 */
public class FloorConfigService {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    // ============================================
    // Field type registry
    // ============================================

    /** The data type of an overridable config field. */
    public enum FieldType { INT, DOUBLE, BOOLEAN, STRING }

    private static final Map<String, FieldType> FIELD_TYPES;

    static {
        Map<String, FieldType> map = new LinkedHashMap<>();
        // Layout — size
        map.put("layout.width", FieldType.INT);
        map.put("layout.depth", FieldType.INT);
        map.put("layout.height", FieldType.INT);
        // Layout — rooms
        map.put("layout.roomDensity", FieldType.DOUBLE);
        map.put("layout.minRoomSize", FieldType.INT);
        map.put("layout.maxRoomSize", FieldType.INT);
        map.put("layout.maxRooms", FieldType.INT);
        map.put("layout.roomShape", FieldType.STRING);
        map.put("layout.irregularity", FieldType.DOUBLE);
        // Layout — corridors
        map.put("layout.corridorWidth", FieldType.INT);
        map.put("layout.branchChance", FieldType.DOUBLE);
        map.put("layout.loopChance", FieldType.DOUBLE);
        map.put("layout.windingCorridors", FieldType.BOOLEAN);
        map.put("layout.windingFactor", FieldType.DOUBLE);
        // Layout — features
        map.put("layout.pillarFrequency", FieldType.DOUBLE);
        map.put("layout.waterFrequency", FieldType.DOUBLE);
        map.put("layout.lavaFrequency", FieldType.DOUBLE);
        map.put("layout.trapDensity", FieldType.DOUBLE);
        map.put("layout.floorTraps", FieldType.BOOLEAN);
        map.put("layout.secretWallChance", FieldType.DOUBLE);
        map.put("layout.merchantSpawnChance", FieldType.DOUBLE);
        // Layout — entrance / exit
        map.put("layout.entrancePlacement", FieldType.STRING);
        map.put("layout.exitDistance", FieldType.DOUBLE);
        // Layout — enemies
        map.put("layout.enemyDensity", FieldType.DOUBLE);
        map.put("layout.maxEnemiesPerRoom", FieldType.INT);
        map.put("layout.bossRoom", FieldType.BOOLEAN);
        map.put("layout.ambushChance", FieldType.DOUBLE);
        // Layout — architecture
        map.put("layout.erosion", FieldType.DOUBLE);
        // Layout — view
        map.put("layout.removeCeiling", FieldType.BOOLEAN);
        map.put("layout.flatFloor", FieldType.BOOLEAN);
        map.put("layout.solidFill", FieldType.BOOLEAN);
        // Layout — generation
        map.put("layout.complexity", FieldType.DOUBLE);
        // Theme (palette excluded — comes from instance)
        map.put("theme.decayFactor", FieldType.DOUBLE);
        map.put("theme.overgrowthFactor", FieldType.DOUBLE);
        map.put("theme.floodingFactor", FieldType.DOUBLE);
        // Pacing
        map.put("pacing.breatheRoomFrequency", FieldType.DOUBLE);
        map.put("pacing.difficultyRamp", FieldType.DOUBLE);
        FIELD_TYPES = Collections.unmodifiableMap(map);
    }

    // ============================================
    // Fields
    // ============================================

    private final FloorConfigRepository repository;
    private volatile TreeMap<Integer, Map<String, Object>> cache = new TreeMap<>();

    // ============================================
    // Constructor
    // ============================================

    /**
     * Creates a new floor config service backed by the given repository.
     *
     * @param repository the floor config repository
     */
    public FloorConfigService(@Nonnull FloorConfigRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    // ============================================
    // Startup
    // ============================================

    /**
     * Loads all floor overrides from SQLite into the in-memory cache.
     * Called once during plugin startup.
     */
    public void loadOnStartup() {
        try {
            refreshCache();
            LOGGER.at(Level.INFO).log("Floor config loaded: %d override floor(s) defined", cache.size());
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Failed to load floor config overrides: %s", e.getMessage());
        }
    }

    // ============================================
    // Resolution
    // ============================================

    /**
     * Resolves the effective generation config for a given floor and theme.
     *
     * <p>Starts from {@link DungeonConfig#withDefaults()}, finds the highest defined override
     * floor {@code ≤ floorLevel}, and merges those sparse overrides. The theme palette is
     * always the provided {@code theme} argument, not from floor overrides.
     *
     * <p>The returned config has placeholder values for instance-specific fields
     * ({@code worldName}, {@code origin}, {@code assemble}, {@code floorLevel}) — the caller
     * must replace those before passing to the generation pipeline.
     *
     * @param floorLevel the floor level to resolve config for
     * @param theme      the theme palette name (from the dungeon instance)
     * @return a fully resolved {@link DungeonConfig}
     */
    @Nonnull
    public DungeonConfig resolveConfigForFloor(int floorLevel, @Nonnull String theme) {
        Objects.requireNonNull(theme, "theme");
        Map<String, Object> overrides = resolveBaseOverrides(floorLevel);
        LayoutConfig layout = applyLayoutOverrides(LayoutConfig.defaults(), overrides);
        ThemeConfig themeConfig = applyThemeOverrides(ThemeConfig.defaults(), theme, overrides);
        PacingConfig pacing = applyPacingOverrides(PacingConfig.defaults(), overrides);
        return new DungeonConfig(null, null, "default", Vec3i.ZERO, layout, themeConfig, pacing, false, floorLevel);
    }

    // ============================================
    // Mutation
    // ============================================

    /**
     * Sets a single field override for a floor level.
     *
     * @param floorLevel the floor level
     * @param fieldPath  the dot-path field key (e.g. {@code "layout.maxRooms"})
     * @param value      the override value (must match the field's expected type)
     * @throws SQLException             if persistence fails
     * @throws IllegalArgumentException if the field path is not a recognized overridable field
     */
    public void setOverride(int floorLevel, @Nonnull String fieldPath, @Nonnull Object value) throws SQLException {
        validateField(fieldPath);
        Objects.requireNonNull(value, "value");
        Map<String, Object> overrides = loadOverridesForFloor(floorLevel);
        overrides.put(fieldPath, value);
        repository.save(floorLevel, toJson(overrides));
        refreshCache();
    }

    /**
     * Removes a single field override for a floor level.
     *
     * <p>If removing the field leaves the floor with no overrides, the entire floor row
     * is deleted from the database.
     *
     * @param floorLevel the floor level
     * @param fieldPath  the dot-path field key
     * @throws SQLException             if persistence fails
     * @throws IllegalArgumentException if the field path is not a recognized overridable field
     */
    public void clearOverride(int floorLevel, @Nonnull String fieldPath) throws SQLException {
        validateField(fieldPath);
        Map<String, Object> overrides = loadOverridesForFloor(floorLevel);
        if (!overrides.containsKey(fieldPath)) {
            return;
        }
        overrides.remove(fieldPath);
        if (overrides.isEmpty()) {
            repository.delete(floorLevel);
        } else {
            repository.save(floorLevel, toJson(overrides));
        }
        refreshCache();
    }

    /**
     * Removes all overrides for a floor level.
     *
     * @param floorLevel the floor level
     * @throws SQLException if persistence fails
     */
    public void clearFloor(int floorLevel) throws SQLException {
        repository.delete(floorLevel);
        refreshCache();
    }

    // ============================================
    // Bulk save (for UI page)
    // ============================================

    /**
     * Returns the sparse overrides inherited from the nearest lower-defined floor,
     * ignoring the specified floor's own overrides. Returns an empty map if no
     * parent floor is defined.
     *
     * @param floorLevel the floor level
     * @return unmodifiable map of inherited overrides
     */
    @Nonnull
    public Map<String, Object> getParentOverrides(int floorLevel) {
        Integer parentFloor = cache.lowerKey(floorLevel);
        if (parentFloor == null) {
            return Map.of();
        }
        return Map.copyOf(cache.get(parentFloor));
    }

    /**
     * Replaces all overrides for a floor level based on the provided full field values.
     *
        * <p>Compares each value against defaults and only persists fields that differ.
        * This keeps the save path aligned with the rebase resolution model, where each
        * defined floor stores a sparse snapshot applied directly over defaults. Saving a
        * floor therefore materializes any visible inherited non-default values as
        * explicit overrides for that floor.
     *
     * <p>Float-precision comparison is used for {@link FieldType#DOUBLE} fields to
     * avoid false positives caused by {@code float → double} round-trip loss from
     * UI slider controls.
     *
     * @param floorLevel the floor level to save overrides for
     * @param allValues  map of field path → value for every field shown in the UI
     * @throws SQLException if persistence fails
     */
    public void bulkSaveOverrides(int floorLevel, @Nonnull Map<String, Object> allValues) throws SQLException {
        LayoutConfig defaultLayout = LayoutConfig.defaults();
        ThemeConfig defaultTheme = ThemeConfig.defaults();
        PacingConfig defaultPacing = PacingConfig.defaults();

        Map<String, Object> newOverrides = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : allValues.entrySet()) {
            String path = entry.getKey();
            Object uiValue = entry.getValue();
            if (uiValue == null || !FIELD_TYPES.containsKey(path)) {
                continue;
            }

            Object inheritedValue = getDefaultValue(path, defaultLayout, defaultTheme, defaultPacing);

            if (!valuesEqual(path, uiValue, inheritedValue)) {
                newOverrides.put(path, convertToFieldType(path, uiValue));
            }
        }

        if (newOverrides.isEmpty()) {
            repository.delete(floorLevel);
        } else {
            repository.save(floorLevel, toJson(newOverrides));
        }
        refreshCache();
    }

    /**
     * Compares two field values for equality, using float-precision comparison for
     * {@link FieldType#DOUBLE} fields to handle {@code float → double} round-trip loss.
     */
    private static boolean valuesEqual(@Nonnull String fieldPath, @Nullable Object a, @Nullable Object b) {
        if (Objects.equals(a, b)) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        FieldType type = FIELD_TYPES.get(fieldPath);
        if (type == null) {
            return a.equals(b);
        }
        return switch (type) {
            case INT -> (a instanceof Number na) && (b instanceof Number nb)
                    && na.intValue() == nb.intValue();
            case DOUBLE -> (a instanceof Number na) && (b instanceof Number nb)
                    && Float.compare(na.floatValue(), nb.floatValue()) == 0;
            case BOOLEAN -> a.equals(b);
            case STRING -> a.toString().equals(b.toString());
        };
    }

    // ============================================
    // Display
    // ============================================

    /**
     * Returns the effective config for a floor with metadata about which fields are
     * explicitly set vs. inherited from defaults.
     *
     * @param floorLevel the floor level
     * @return the effective config with field-level inheritance metadata
     */
    @Nonnull
    public EffectiveConfig getEffectiveConfig(int floorLevel) {
        Map<String, Object> baseOverrides = resolveBaseOverrides(floorLevel);
        Map<String, Object> exactOverrides = cache.get(floorLevel);
        Integer baseFloor = cache.floorKey(floorLevel);

        LayoutConfig defaultLayout = LayoutConfig.defaults();
        ThemeConfig defaultTheme = ThemeConfig.defaults();
        PacingConfig defaultPacing = PacingConfig.defaults();

        Map<String, FieldStatus> fields = new LinkedHashMap<>();
        for (String path : FIELD_TYPES.keySet()) {
            Object defaultValue = getDefaultValue(path, defaultLayout, defaultTheme, defaultPacing);
            if (baseOverrides.containsKey(path)) {
                Object overrideValue = convertToFieldType(path, baseOverrides.get(path));
                boolean explicit = exactOverrides != null && exactOverrides.containsKey(path);
                fields.put(path, new FieldStatus(overrideValue, explicit));
            } else {
                fields.put(path, new FieldStatus(defaultValue, false));
            }
        }

        return new EffectiveConfig(baseFloor, fields);
    }

    /**
     * Returns the floor levels that have defined overrides.
     *
     * @return immutable list of floor levels with defined overrides
     * @throws SQLException if the query fails
     */
    @Nonnull
    public List<Integer> listDefinedFloors() throws SQLException {
        return repository.listDefinedFloors();
    }

    // ============================================
    // Static helpers
    // ============================================

    /**
     * Parses a raw string value into the correct type for a given field path.
     *
     * @param fieldPath the dot-path field key
     * @param rawValue  the raw string value from command input
     * @return the parsed value with the correct type
     * @throws IllegalArgumentException if the field path is unknown or the value is invalid
     */
    @Nonnull
    public static Object parseFieldValue(@Nonnull String fieldPath, @Nonnull String rawValue) {
        FieldType type = FIELD_TYPES.get(fieldPath);
        if (type == null) {
            throw new IllegalArgumentException("Unknown field: " + fieldPath);
        }
        return switch (type) {
            case INT -> {
                try {
                    yield Integer.parseInt(rawValue);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "Invalid integer for " + fieldPath + ": " + rawValue);
                }
            }
            case DOUBLE -> {
                try {
                    yield Double.parseDouble(rawValue);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "Invalid number for " + fieldPath + ": " + rawValue);
                }
            }
            case BOOLEAN -> {
                if ("true".equalsIgnoreCase(rawValue) || "false".equalsIgnoreCase(rawValue)) {
                    yield Boolean.parseBoolean(rawValue);
                }
                throw new IllegalArgumentException(
                        "Invalid boolean for " + fieldPath + ": " + rawValue + " (expected true/false)");
            }
            case STRING -> rawValue;
        };
    }

    /**
     * Returns whether the given field path is a recognized overridable field.
     *
     * @param fieldPath the dot-path field key
     * @return {@code true} if the field path is recognized
     */
    public static boolean isValidField(@Nonnull String fieldPath) {
        return FIELD_TYPES.containsKey(fieldPath);
    }

    /**
     * Returns the set of all supported overridable field paths.
     *
     * @return unmodifiable set of field paths
     */
    @Nonnull
    public static Set<String> getSupportedFields() {
        return FIELD_TYPES.keySet();
    }

    // ============================================
    // Inner types
    // ============================================

    /**
     * The effective config for a floor, with per-field metadata about whether each
     * value is explicitly set or inherited from defaults.
     *
     * @param baseFloor the floor level whose overrides are used as the base, or null if defaults
     * @param fields    map of field path to status (value + explicit flag)
     */
    public record EffectiveConfig(
            @Nullable Integer baseFloor,
            @Nonnull Map<String, FieldStatus> fields
    ) {}

    /**
     * A single field's resolved value and whether it was explicitly set or inherited.
     *
     * @param value    the resolved value
     * @param explicit {@code true} if the value comes from an explicit override
     */
    public record FieldStatus(
            @Nonnull Object value,
            boolean explicit
    ) {}

    // ============================================
    // Private helpers
    // ============================================

    @Nonnull
    private Map<String, Object> resolveBaseOverrides(int floorLevel) {
        Integer baseFloor = cache.floorKey(floorLevel);
        if (baseFloor == null) {
            return Map.of();
        }
        return Map.copyOf(cache.get(baseFloor));
    }

    @Nonnull
    private Map<String, Object> loadOverridesForFloor(int floorLevel) throws SQLException {
        return repository.load(floorLevel)
                .map(json -> {
                    Map<String, Object> parsed = JsonParser.parseObject(json);
                    return parsed != null ? new HashMap<>(parsed) : new HashMap<String, Object>();
                })
                .orElseGet(HashMap::new);
    }

    private void refreshCache() throws SQLException {
        TreeMap<Integer, String> raw = repository.loadAll();
        TreeMap<Integer, Map<String, Object>> newCache = new TreeMap<>();
        for (Map.Entry<Integer, String> entry : raw.entrySet()) {
            Map<String, Object> parsed = JsonParser.parseObject(entry.getValue());
            if (parsed != null) {
                newCache.put(entry.getKey(), parsed);
            }
        }
        this.cache = newCache;
    }

    private static void validateField(@Nonnull String fieldPath) {
        if (!FIELD_TYPES.containsKey(fieldPath)) {
            throw new IllegalArgumentException("Unknown field: " + fieldPath);
        }
    }

    private static Object convertToFieldType(@Nonnull String fieldPath, @Nullable Object value) {
        FieldType type = FIELD_TYPES.get(fieldPath);
        if (type == null || value == null) {
            return value;
        }
        return switch (type) {
            case INT -> JsonParser.toInt(value);
            case DOUBLE -> JsonParser.toDouble(value);
            case BOOLEAN -> JsonParser.toBoolean(value);
            case STRING -> JsonParser.toStringOrNull(value);
        };
    }

    // ── Config builders ──────────────────────────────────────────────

    @Nonnull
    private static LayoutConfig applyLayoutOverrides(
            @Nonnull LayoutConfig d,
            @Nonnull Map<String, Object> o
    ) {
        return new LayoutConfig(
                o.containsKey("layout.width") ? JsonParser.toInt(o.get("layout.width")) : d.width(),
                o.containsKey("layout.depth") ? JsonParser.toInt(o.get("layout.depth")) : d.depth(),
                o.containsKey("layout.height") ? JsonParser.toInt(o.get("layout.height")) : d.height(),
                o.containsKey("layout.roomDensity") ? JsonParser.toDouble(o.get("layout.roomDensity")) : d.roomDensity(),
                o.containsKey("layout.minRoomSize") ? JsonParser.toInt(o.get("layout.minRoomSize")) : d.minRoomSize(),
                o.containsKey("layout.maxRoomSize") ? JsonParser.toInt(o.get("layout.maxRoomSize")) : d.maxRoomSize(),
                o.containsKey("layout.maxRooms") ? JsonParser.toInt(o.get("layout.maxRooms")) : d.maxRooms(),
                o.containsKey("layout.roomShape") ? JsonParser.toStringOrNull(o.get("layout.roomShape")) : d.roomShape(),
                o.containsKey("layout.irregularity") ? JsonParser.toDouble(o.get("layout.irregularity")) : d.irregularity(),
                o.containsKey("layout.corridorWidth") ? JsonParser.toInt(o.get("layout.corridorWidth")) : d.corridorWidth(),
                o.containsKey("layout.branchChance") ? JsonParser.toDouble(o.get("layout.branchChance")) : d.branchChance(),
                o.containsKey("layout.loopChance") ? JsonParser.toDouble(o.get("layout.loopChance")) : d.loopChance(),
                o.containsKey("layout.windingCorridors") ? JsonParser.toBoolean(o.get("layout.windingCorridors")) : d.windingCorridors(),
                o.containsKey("layout.windingFactor") ? JsonParser.toDouble(o.get("layout.windingFactor")) : d.windingFactor(),
                o.containsKey("layout.pillarFrequency") ? JsonParser.toDouble(o.get("layout.pillarFrequency")) : d.pillarFrequency(),
                o.containsKey("layout.waterFrequency") ? JsonParser.toDouble(o.get("layout.waterFrequency")) : d.waterFrequency(),
                o.containsKey("layout.lavaFrequency") ? JsonParser.toDouble(o.get("layout.lavaFrequency")) : d.lavaFrequency(),
                o.containsKey("layout.trapDensity") ? JsonParser.toDouble(o.get("layout.trapDensity")) : d.trapDensity(),
                o.containsKey("layout.floorTraps") ? JsonParser.toBoolean(o.get("layout.floorTraps")) : d.floorTraps(),
                o.containsKey("layout.secretWallChance") ? JsonParser.toDouble(o.get("layout.secretWallChance")) : d.secretWallChance(),
                o.containsKey("layout.merchantSpawnChance") ? JsonParser.toDouble(o.get("layout.merchantSpawnChance")) : d.merchantSpawnChance(),
                o.containsKey("layout.entrancePlacement") ? JsonParser.toStringOrNull(o.get("layout.entrancePlacement")) : d.entrancePlacement(),
                o.containsKey("layout.exitDistance") ? JsonParser.toDouble(o.get("layout.exitDistance")) : d.exitDistance(),
                o.containsKey("layout.enemyDensity") ? JsonParser.toDouble(o.get("layout.enemyDensity")) : d.enemyDensity(),
                o.containsKey("layout.maxEnemiesPerRoom") ? JsonParser.toInt(o.get("layout.maxEnemiesPerRoom")) : d.maxEnemiesPerRoom(),
                o.containsKey("layout.bossRoom") ? JsonParser.toBoolean(o.get("layout.bossRoom")) : d.bossRoom(),
                o.containsKey("layout.ambushChance") ? JsonParser.toDouble(o.get("layout.ambushChance")) : d.ambushChance(),
                o.containsKey("layout.erosion") ? JsonParser.toDouble(o.get("layout.erosion")) : d.erosion(),
                o.containsKey("layout.removeCeiling") ? JsonParser.toBoolean(o.get("layout.removeCeiling")) : d.removeCeiling(),
                o.containsKey("layout.flatFloor") ? JsonParser.toBoolean(o.get("layout.flatFloor")) : d.flatFloor(),
                o.containsKey("layout.solidFill") ? JsonParser.toBoolean(o.get("layout.solidFill")) : d.solidFill(),
                o.containsKey("layout.complexity") ? JsonParser.toDouble(o.get("layout.complexity")) : d.complexity()
        );
    }

    @Nonnull
    private static ThemeConfig applyThemeOverrides(
            @Nonnull ThemeConfig d,
            @Nonnull String theme,
            @Nonnull Map<String, Object> o
    ) {
        return new ThemeConfig(
                theme,
                o.containsKey("theme.decayFactor") ? JsonParser.toDouble(o.get("theme.decayFactor")) : d.decayFactor(),
                o.containsKey("theme.overgrowthFactor") ? JsonParser.toDouble(o.get("theme.overgrowthFactor")) : d.overgrowthFactor(),
                o.containsKey("theme.floodingFactor") ? JsonParser.toDouble(o.get("theme.floodingFactor")) : d.floodingFactor()
        );
    }

    @Nonnull
    private static PacingConfig applyPacingOverrides(
            @Nonnull PacingConfig d,
            @Nonnull Map<String, Object> o
    ) {
        return new PacingConfig(
                o.containsKey("pacing.breatheRoomFrequency") ? JsonParser.toDouble(o.get("pacing.breatheRoomFrequency")) : d.breatheRoomFrequency(),
                o.containsKey("pacing.difficultyRamp") ? JsonParser.toDouble(o.get("pacing.difficultyRamp")) : d.difficultyRamp()
        );
    }

    @Nonnull
    private static Object getDefaultValue(
            @Nonnull String path,
            @Nonnull LayoutConfig layout,
            @Nonnull ThemeConfig theme,
            @Nonnull PacingConfig pacing
    ) {
        return switch (path) {
            // Layout — size
            case "layout.width" -> layout.width();
            case "layout.depth" -> layout.depth();
            case "layout.height" -> layout.height();
            // Layout — rooms
            case "layout.roomDensity" -> layout.roomDensity();
            case "layout.minRoomSize" -> layout.minRoomSize();
            case "layout.maxRoomSize" -> layout.maxRoomSize();
            case "layout.maxRooms" -> layout.maxRooms();
            case "layout.roomShape" -> layout.roomShape();
            case "layout.irregularity" -> layout.irregularity();
            // Layout — corridors
            case "layout.corridorWidth" -> layout.corridorWidth();
            case "layout.branchChance" -> layout.branchChance();
            case "layout.loopChance" -> layout.loopChance();
            case "layout.windingCorridors" -> layout.windingCorridors();
            case "layout.windingFactor" -> layout.windingFactor();
            // Layout — features
            case "layout.pillarFrequency" -> layout.pillarFrequency();
            case "layout.waterFrequency" -> layout.waterFrequency();
            case "layout.lavaFrequency" -> layout.lavaFrequency();
            case "layout.trapDensity" -> layout.trapDensity();
            case "layout.floorTraps" -> layout.floorTraps();
            case "layout.secretWallChance" -> layout.secretWallChance();
            case "layout.merchantSpawnChance" -> layout.merchantSpawnChance();
            // Layout — entrance / exit
            case "layout.entrancePlacement" -> layout.entrancePlacement();
            case "layout.exitDistance" -> layout.exitDistance();
            // Layout — enemies
            case "layout.enemyDensity" -> layout.enemyDensity();
            case "layout.maxEnemiesPerRoom" -> layout.maxEnemiesPerRoom();
            case "layout.bossRoom" -> layout.bossRoom();
            case "layout.ambushChance" -> layout.ambushChance();
            // Layout — architecture
            case "layout.erosion" -> layout.erosion();
            // Layout — view
            case "layout.removeCeiling" -> layout.removeCeiling();
            case "layout.flatFloor" -> layout.flatFloor();
            case "layout.solidFill" -> layout.solidFill();
            // Layout — generation
            case "layout.complexity" -> layout.complexity();
            // Theme
            case "theme.decayFactor" -> theme.decayFactor();
            case "theme.overgrowthFactor" -> theme.overgrowthFactor();
            case "theme.floodingFactor" -> theme.floodingFactor();
            // Pacing
            case "pacing.breatheRoomFrequency" -> pacing.breatheRoomFrequency();
            case "pacing.difficultyRamp" -> pacing.difficultyRamp();
            default -> throw new IllegalArgumentException("Unknown field: " + path);
        };
    }

    // ── JSON serialization ───────────────────────────────────────────

    /**
     * Serializes a flat key-value map to a JSON object string.
     * Package-private for testing.
     */
    @Nonnull
    static String toJson(@Nonnull Map<String, Object> map) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : new TreeMap<>(map).entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append('"').append(escapeJsonString(entry.getKey())).append("\": ");
            appendJsonValue(sb, entry.getValue());
        }
        sb.append('}');
        return sb.toString();
    }

    private static void appendJsonValue(@Nonnull StringBuilder sb, @Nullable Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            sb.append('"').append(escapeJsonString(s)).append('"');
        } else if (value instanceof Boolean || value instanceof Number) {
            sb.append(value);
        } else {
            sb.append('"').append(escapeJsonString(value.toString())).append('"');
        }
    }

    @Nonnull
    private static String escapeJsonString(@Nonnull String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
