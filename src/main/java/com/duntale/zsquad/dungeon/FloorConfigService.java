package com.duntale.zsquad.dungeon;

import com.duntale.dungeongen.config.DungeonConfig;
import com.duntale.dungeongen.config.LayoutConfig;
import com.duntale.dungeongen.config.PacingConfig;
import com.duntale.dungeongen.config.ThemeConfig;
import com.duntale.dungeongen.config.Vec3i;
import com.duntale.dungeongen.config.asset.DungeonThemeConfig;
import com.duntale.dungeongen.util.JsonParser;
import com.duntale.zsquad.dungeon.config.asset.FloorConfigDefaultAsset;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.logger.HytaleLogger;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonType;
import org.bson.BsonValue;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.Locale;
import java.util.logging.Level;

/**
 * Resolves per-floor generation config using layered code defaults, shipped asset defaults,
 * and local SQL overrides.
 *
 * <p>The effective config for floor {@code N} is resolved in this order:
 *
 * <ol>
 *     <li>code defaults from {@link LayoutConfig#defaults()}, {@link ThemeConfig#defaults()},
 *     and {@link PacingConfig#defaults()},</li>
 *     <li>the shipped asset snapshot from the highest configured asset floor {@code ≤ N},</li>
 *     <li>the local SQL snapshot from the highest configured SQL floor in the current shipped
 *     asset segment, if one exists.</li>
 * </ol>
 *
 * <p>Local SQL state remains the only mutable layer. A SQL row rebases only within the current
 * shipped asset segment, so a floor-2 override affects floors 2-4, then floor 5 resets to the
 * shipped 005 asset baseline. UI save/reset behavior continues to edit and clear SQL rows only,
 * revealing the inherited asset-backed baseline when no local override exists.
 *
 * @since 1.6.0
 */
public class FloorConfigService {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final List<String> DEFAULT_THEME_VARIANTS = List.of("crypt");
    private static final Map<String, String> CANONICAL_THEME_IDS_BY_ALIAS = Map.of(
        "arcane", "arcane",
        "crypt", "crypt",
        "hive", "hive",
        "mine", "mine",
        "mushroom", "mushroom",
        "temple_dark", "temple_dark",
        "volcanic", "volcanic"
    );

    // ============================================
    // Field type registry
    // ============================================

    /** The data type of an overridable config field. */
    public enum FieldType { INT, DOUBLE, BOOLEAN, STRING, STRING_LIST }

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
        map.put("theme.variants", FieldType.STRING_LIST);
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
    private final Supplier<TreeMap<Integer, Map<String, Object>>> assetDefaultSupplier;
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
        this(repository, FloorConfigService::loadAssetDefaultsFromRegistry);
    }

    FloorConfigService(
            @Nonnull FloorConfigRepository repository,
            @Nonnull Supplier<TreeMap<Integer, Map<String, Object>>> assetDefaultSupplier
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.assetDefaultSupplier = Objects.requireNonNull(assetDefaultSupplier, "assetDefaultSupplier");
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
            normalizeStoredOverrides();
            refreshCache();
            LOGGER.at(Level.INFO).log("Floor config loaded: %d override floor(s) defined", cache.size());
        } catch (SQLException | IllegalArgumentException e) {
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
        Map<String, Object> overrides = resolveEffectiveOverrides(floorLevel);
        LayoutConfig layout = applyLayoutOverrides(LayoutConfig.defaults(), overrides);
        ThemeConfig themeConfig = applyThemeOverrides(ThemeConfig.defaults(), theme, overrides);
        PacingConfig pacing = applyPacingOverrides(PacingConfig.defaults(), overrides);
        return new DungeonConfig(null, null, "default", Vec3i.ZERO, layout, themeConfig, pacing, false, floorLevel);
    }

    /**
     * Returns the fully resolved theme variant list for a floor.
     *
     * @param floorLevel the floor level to resolve
     * @return the normalized list of allowed theme IDs for that floor
     */
    @Nonnull
    @SuppressWarnings("unchecked")
    public List<String> getThemeVariantsForFloor(int floorLevel) {
        Map<String, Object> overrides = resolveEffectiveOverrides(floorLevel);
        Object resolved = overrides.get("theme.variants");
        if (resolved == null) {
            return DEFAULT_THEME_VARIANTS;
        }
        return (List<String>) convertToFieldType("theme.variants", resolved);
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
        overrides.put(fieldPath, convertToFieldType(fieldPath, value));
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
        Map<String, Object> inheritedOverrides = resolveInheritedOverrides(floorLevel);

        Map<String, Object> newOverrides = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : allValues.entrySet()) {
            String path = entry.getKey();
            Object uiValue = entry.getValue();
            if (uiValue == null || !FIELD_TYPES.containsKey(path)) {
                continue;
            }

            Object inheritedValue = inheritedOverrides.containsKey(path)
                    ? convertToFieldType(path, inheritedOverrides.get(path))
                    : getDefaultValue(path, defaultLayout, defaultTheme, defaultPacing);

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
            case STRING_LIST -> Objects.equals(
                convertToFieldType(fieldPath, a),
                convertToFieldType(fieldPath, b));
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
        Map<String, Object> baseOverrides = resolveEffectiveOverrides(floorLevel);
        Map<String, Object> exactOverrides = cache.get(floorLevel);
        Integer baseFloor = resolveDisplayBaseFloor(floorLevel);

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
            case STRING_LIST -> convertToFieldType(fieldPath, rawValue);
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
    private Map<String, Object> resolveEffectiveOverrides(int floorLevel) {
        LinkedHashMap<String, Object> resolved = new LinkedHashMap<>(resolveInheritedOverrides(floorLevel));

        return resolved.isEmpty() ? Map.of() : Map.copyOf(resolved);
    }

    @Nonnull
    private Map<String, Object> resolveInheritedOverrides(int floorLevel) {
        TreeMap<Integer, Map<String, Object>> assetDefaults = resolveAssetDefaults();
        LinkedHashMap<String, Object> resolved = new LinkedHashMap<>();

        Integer assetFloor = assetDefaults.floorKey(floorLevel);
        if (assetFloor != null) {
            resolved.putAll(assetDefaults.get(assetFloor));
        }

        Integer sqlFloor = resolveSegmentSqlFloor(assetFloor, floorLevel, true);
        if (sqlFloor != null) {
            resolved.putAll(cache.get(sqlFloor));
        }

        return resolved.isEmpty() ? Map.of() : Map.copyOf(resolved);
    }

    @Nullable
    private Integer resolveInheritedSqlFloor(int floorLevel) {
        return resolveSegmentSqlFloor(resolveAssetDefaults().floorKey(floorLevel), floorLevel, false);
    }

    @Nullable
    private Integer resolveSegmentSqlFloor(@Nullable Integer assetFloor, int floorLevel, boolean includeExactFloor) {
        NavigableMap<Integer, Map<String, Object>> scopedSql = cache;
        if (assetFloor != null) {
            scopedSql = scopedSql.tailMap(assetFloor, true);
        }
        scopedSql = includeExactFloor ? scopedSql.headMap(floorLevel, true) : scopedSql.headMap(floorLevel, false);
        return scopedSql.isEmpty() ? null : scopedSql.lastKey();
    }

    @Nonnull
    private Map<String, Object> loadOverridesForFloor(int floorLevel) throws SQLException {
        return repository.load(floorLevel)
                .map(json -> {
                    Map<String, Object> parsed = JsonParser.parseObject(json);
                    return parsed != null
                            ? new HashMap<>(normalizeOverrides(parsed, "SQL floor " + floorLevel))
                            : new HashMap<String, Object>();
                })
                .orElseGet(HashMap::new);
    }

    private void refreshCache() throws SQLException {
        TreeMap<Integer, String> raw = repository.loadAll();
        TreeMap<Integer, Map<String, Object>> newCache = new TreeMap<>();
        for (Map.Entry<Integer, String> entry : raw.entrySet()) {
            Map<String, Object> parsed = JsonParser.parseObject(entry.getValue());
            if (parsed != null) {
                newCache.put(entry.getKey(), normalizeOverrides(parsed, "SQL floor " + entry.getKey()));
            }
        }
        this.cache = newCache;
    }

    private void normalizeStoredOverrides() throws SQLException {
        TreeMap<Integer, String> raw = repository.loadAll();
        for (Map.Entry<Integer, String> entry : raw.entrySet()) {
            Map<String, Object> parsed = JsonParser.parseObject(entry.getValue());
            if (parsed == null) {
                continue;
            }
            String normalizedJson = toJson(normalizeOverrides(parsed, "SQL floor " + entry.getKey()));
            if (!normalizedJson.equals(entry.getValue())) {
                repository.save(entry.getKey(), normalizedJson);
            }
        }
    }

    @Nullable
    private Integer resolveDisplayBaseFloor(int floorLevel) {
        Integer assetFloor = resolveAssetDefaults().floorKey(floorLevel);
        Integer sqlFloor = resolveSegmentSqlFloor(assetFloor, floorLevel, true);
        if (sqlFloor != null) {
            return sqlFloor;
        }
        return assetFloor;
    }

    @Nonnull
    private TreeMap<Integer, Map<String, Object>> resolveAssetDefaults() {
        TreeMap<Integer, Map<String, Object>> assetDefaults = assetDefaultSupplier.get();
        if (assetDefaults == null || assetDefaults.isEmpty()) {
            return new TreeMap<>();
        }

        TreeMap<Integer, Map<String, Object>> normalized = new TreeMap<>();
        for (Map.Entry<Integer, Map<String, Object>> entry : assetDefaults.entrySet()) {
            normalized.put(entry.getKey(), normalizeOverrides(entry.getValue(), "asset floor " + entry.getKey()));
        }
        return normalized;
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
            case STRING_LIST -> normalizeStringListField(fieldPath, value);
        };
    }

    @Nonnull
    private static Map<String, Object> normalizeOverrides(
            @Nonnull Map<String, Object> raw,
            @Nonnull String sourceLabel
    ) {
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String path = entry.getKey();
            validateField(path);
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            try {
                normalized.put(path, convertToFieldType(path, value));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid value for " + path + " in " + sourceLabel + ": "
                        + e.getMessage(), e);
            }
        }
        return Map.copyOf(normalized);
    }

    @Nonnull
    private static Object normalizeStringListField(@Nonnull String fieldPath, @Nonnull Object value) {
        if (!"theme.variants".equals(fieldPath)) {
            throw new IllegalArgumentException("Unsupported list field: " + fieldPath);
        }
        return normalizeThemeVariants(value);
    }

    @Nonnull
    private static List<String> normalizeThemeVariants(@Nonnull Object value) {
        Collection<?> items;
        if (value instanceof Collection<?> collection) {
            items = collection;
        } else if (value instanceof Object[] array) {
            items = Arrays.asList(array);
        } else if (value instanceof String stringValue) {
            items = Arrays.stream(stringValue.split(","))
                    .map(String::trim)
                    .filter(part -> !part.isEmpty())
                    .toList();
        } else {
            throw new IllegalArgumentException("expected a string list");
        }

        ArrayList<String> normalized = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Object item : items) {
            if (item == null) {
                continue;
            }
            String themeId = canonicalizeThemeId(item.toString());
            if (themeId.isEmpty()) {
                continue;
            }
            if (seen.add(themeId)) {
                normalized.add(themeId);
            }
        }
        return List.copyOf(normalized);
    }

    @Nonnull
    private static String canonicalizeThemeId(@Nonnull String themeId) {
        String trimmedThemeId = themeId.trim();
        if (trimmedThemeId.isBlank()) {
            throw new IllegalArgumentException("theme ID cannot be blank");
        }
        String canonicalThemeId = CANONICAL_THEME_IDS_BY_ALIAS.get(trimmedThemeId.toLowerCase(Locale.ROOT));
        if (canonicalThemeId != null) {
            return canonicalThemeId;
        }
        if (AssetRegistry.getAssetStore(DungeonThemeConfig.class) != null
                && DungeonThemeConfig.get(trimmedThemeId) != null) {
            return trimmedThemeId;
        }
        throw new IllegalArgumentException("unknown theme ID: " + themeId);
    }

    @Nonnull
    private static TreeMap<Integer, Map<String, Object>> loadAssetDefaultsFromRegistry() {
        TreeMap<Integer, Map<String, Object>> resolved = new TreeMap<>();
        for (FloorConfigDefaultAsset asset : FloorConfigDefaultAsset.getAll()) {
            int floorLevel = asset.getFloorLevel();
            Map<String, Object> overrides = normalizeOverrides(
                    bsonDocumentToMap(asset.getOverridesDocument()),
                    "asset floor " + asset.getId());
            if (resolved.putIfAbsent(floorLevel, overrides) != null) {
                throw new IllegalArgumentException("Duplicate floor config default asset floor: " + floorLevel);
            }
        }
        return resolved;
    }

    @Nonnull
    private static Map<String, Object> bsonDocumentToMap(@Nonnull BsonDocument document) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, BsonValue> entry : document.entrySet()) {
            values.put(entry.getKey(), bsonValueToObject(entry.getValue()));
        }
        return values;
    }

    @Nonnull
    private static List<Object> bsonArrayToList(@Nonnull BsonArray array) {
        ArrayList<Object> values = new ArrayList<>(array.size());
        for (BsonValue value : array) {
            values.add(bsonValueToObject(value));
        }
        return List.copyOf(values);
    }

    @Nullable
    private static Object bsonValueToObject(@Nonnull BsonValue value) {
        BsonType type = value.getBsonType();
        return switch (type) {
            case ARRAY -> bsonArrayToList(value.asArray());
            case BOOLEAN -> value.asBoolean().getValue();
            case DOCUMENT -> bsonDocumentToMap(value.asDocument());
            case DOUBLE -> value.asDouble().getValue();
            case INT32 -> value.asInt32().getValue();
            case INT64 -> value.asInt64().getValue();
            case NULL -> null;
            case STRING -> value.asString().getValue();
            default -> value.toString();
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
            case "theme.variants" -> DEFAULT_THEME_VARIANTS;
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
        } else if (value instanceof Collection<?> collection) {
            sb.append('[');
            boolean first = true;
            for (Object item : collection) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                appendJsonValue(sb, item);
            }
            sb.append(']');
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
