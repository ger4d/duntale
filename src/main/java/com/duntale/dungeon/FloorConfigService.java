package com.duntale.dungeon;

import com.duntale.dungeongen.config.DungeonConfig;
import com.duntale.dungeongen.config.LayoutConfig;
import com.duntale.dungeongen.config.PacingConfig;
import com.duntale.dungeongen.config.ThemeConfig;
import com.duntale.dungeongen.config.Vec3i;
import com.duntale.dungeongen.config.asset.DungeonThemeConfig;
import com.duntale.dungeongen.util.JsonParser;
import com.duntale.dungeon.config.asset.FloorConfigDefaultAsset;
import com.hypixel.hytale.assetstore.AssetRegistry;
import org.bson.BsonArray;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonNull;
import org.bson.BsonString;
import org.bson.BsonType;
import org.bson.BsonValue;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Resolves per-floor generation config using code defaults plus the active floor-config asset
 * snapshot selected by the Hytale asset registry.
 *
 * <p>The effective config for floor {@code N} is resolved in this order:
 *
 * <ol>
 *     <li>code defaults from {@link LayoutConfig#defaults()}, {@link ThemeConfig#defaults()},
 *     and {@link PacingConfig#defaults()},</li>
 *     <li>the active asset snapshot from the highest configured floor asset {@code ≤ N}.</li>
 * </ol>
 *
 * <p>Editable overrides are persisted as full asset-pack snapshots via
 * {@link FloorConfigAssetRepository}. The service itself keeps no SQL cache and always re-reads
 * the active asset registry so file-watch reloads are reflected without a service restart.
 *
 * @since 1.6.0
 */
public class FloorConfigService {

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

    private final FloorConfigAssetRepository repository;
    private final Supplier<TreeMap<Integer, Map<String, Object>>> assetDefaultSupplier;

    // ============================================
    // Constructor
    // ============================================

    /**
     * Creates a new floor config service backed by the given asset repository.
     *
     * @param repository the asset-backed floor config repository
     */
    public FloorConfigService(@Nonnull FloorConfigAssetRepository repository) {
        this(repository, FloorConfigService::loadActiveAssetsFromRegistry);
    }

    FloorConfigService(
            @Nonnull FloorConfigAssetRepository repository,
            @Nonnull Supplier<TreeMap<Integer, Map<String, Object>>> assetDefaultSupplier
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.assetDefaultSupplier = Objects.requireNonNull(assetDefaultSupplier, "assetDefaultSupplier");
    }

    // ============================================
    // Startup
    // ============================================

    /**
     * No-op retained for callers that still invoke the old startup warmup hook.
     */
    public void loadOnStartup() {
        // Asset-backed floor config resolution reads the live registry directly.
    }

    // ============================================
    // Resolution
    // ============================================

    /**
     * Resolves the effective generation config for a given floor and theme.
     *
     * <p>Starts from code defaults, then applies the active floor-config asset snapshot from the
     * highest configured floor {@code ≤ floorLevel}. The theme palette is always the provided
     * {@code theme} argument, not from floor overrides.
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
     * Saves a full floor-config snapshot into the selected asset pack.
     *
     * @param floorLevel the floor level to save
     * @param packName   the target asset-pack name
     * @param allValues  field values gathered from the editor UI
     * @throws IOException              if the asset file cannot be written
     * @throws IllegalArgumentException if the values or pack selection are invalid
     */
    public void saveAssetOverride(int floorLevel, @Nonnull String packName, @Nonnull Map<String, Object> allValues)
            throws IOException {
        Objects.requireNonNull(packName, "packName");
        Objects.requireNonNull(allValues, "allValues");

        Map<String, Object> snapshot = buildFullSnapshot(floorLevel, allValues);
        @SuppressWarnings("unchecked")
        List<String> themeVariants = (List<String>) snapshot.get("theme.variants");
        if (themeVariants == null || themeVariants.isEmpty()) {
            throw new IllegalArgumentException("At least one theme variant must be selected");
        }
        repository.saveAssetOverride(floorLevel, packName, toBsonDocument(snapshot));
    }

    /**
     * Deletes the selected floor-config snapshot from a target asset pack.
     *
     * @param floorLevel the floor level to reset
     * @param packName   the target asset-pack name
     * @return {@code true} if a pack-local override file existed and was removed
     * @throws IOException              if the asset file cannot be deleted
     * @throws IllegalArgumentException if the pack selection is invalid
     */
    public boolean deleteAssetOverride(int floorLevel, @Nonnull String packName) throws IOException {
        return repository.deleteAssetOverride(floorLevel, packName);
    }

    /**
     * Returns whether the selected asset pack already contains a persisted floor-config snapshot.
     *
     * @param floorLevel the floor level to inspect
     * @param packName   the target asset-pack name
     * @return {@code true} if the floor asset file already exists in the selected pack
     * @throws IllegalArgumentException if the pack selection is invalid
     */
    public boolean hasAssetOverride(int floorLevel, @Nonnull String packName) {
        return repository.hasAssetOverride(floorLevel, packName);
    }
    /**
     * Lists the currently loaded asset packs that may be used for floor-config editing.
     *
     * @return ordered pack choices in runtime load order
     */
    @Nonnull
    public List<FloorConfigAssetRepository.PackChoice> listPackChoices() {
        return repository.listPackChoices();
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
        TreeMap<Integer, Map<String, Object>> activeAssets = resolveActiveAssets();
        Map.Entry<Integer, Map<String, Object>> baseEntry = activeAssets.floorEntry(floorLevel);
        Map<String, Object> baseOverrides = baseEntry != null ? baseEntry.getValue() : Map.of();
        Map<String, Object> exactOverrides = activeAssets.get(floorLevel);
        Integer baseFloor = baseEntry != null ? baseEntry.getKey() : null;

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
     * Returns the floor levels that currently have active floor-config assets.
     *
     * @return immutable list of active floor-config breakpoints
     */
    @Nonnull
    public List<Integer> listDefinedFloors() {
        return List.copyOf(resolveActiveAssets().keySet());
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
     * @param explicit {@code true} if the value comes from an exact-floor active asset
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
        Map.Entry<Integer, Map<String, Object>> baseEntry = resolveActiveAssets().floorEntry(floorLevel);
        if (baseEntry == null) {
            return Map.of();
        }
        return Map.copyOf(baseEntry.getValue());
    }

    @Nonnull
    private Map<String, Object> buildFullSnapshot(int floorLevel, @Nonnull Map<String, Object> allValues) {
        EffectiveConfig current = getEffectiveConfig(floorLevel);
        LinkedHashMap<String, Object> snapshot = new LinkedHashMap<>();
        for (String path : FIELD_TYPES.keySet()) {
            Object rawValue = allValues.containsKey(path)
                    ? allValues.get(path)
                    : current.fields().get(path).value();
            if (rawValue == null) {
                continue;
            }
            snapshot.put(path, convertToFieldType(path, rawValue));
        }
        return Map.copyOf(snapshot);
    }

    @Nonnull
    private TreeMap<Integer, Map<String, Object>> resolveActiveAssets() {
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
    private static TreeMap<Integer, Map<String, Object>> loadActiveAssetsFromRegistry() {
        TreeMap<Integer, Map<String, Object>> resolved = new TreeMap<>();
        TreeMap<Integer, String> activeAssetIds = new TreeMap<>();
        for (FloorConfigDefaultAsset asset : FloorConfigDefaultAsset.getAll()) {
            int floorLevel = asset.getFloorLevel();
            String previousAssetId = activeAssetIds.putIfAbsent(floorLevel, asset.getId());
            if (previousAssetId != null) {
                throw new IllegalArgumentException(
                        "Duplicate active floor config assets for floor " + floorLevel
                                + ": " + previousAssetId + " and " + asset.getId());
            }

            resolved.put(
                    floorLevel,
                    normalizeOverrides(
                            bsonDocumentToMap(asset.getOverridesDocument()),
                            "asset floor " + asset.getId()));
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
            case "layout.width" -> layout.width();
            case "layout.depth" -> layout.depth();
            case "layout.height" -> layout.height();
            case "layout.roomDensity" -> layout.roomDensity();
            case "layout.minRoomSize" -> layout.minRoomSize();
            case "layout.maxRoomSize" -> layout.maxRoomSize();
            case "layout.maxRooms" -> layout.maxRooms();
            case "layout.roomShape" -> layout.roomShape();
            case "layout.irregularity" -> layout.irregularity();
            case "layout.corridorWidth" -> layout.corridorWidth();
            case "layout.branchChance" -> layout.branchChance();
            case "layout.loopChance" -> layout.loopChance();
            case "layout.windingCorridors" -> layout.windingCorridors();
            case "layout.windingFactor" -> layout.windingFactor();
            case "layout.pillarFrequency" -> layout.pillarFrequency();
            case "layout.waterFrequency" -> layout.waterFrequency();
            case "layout.lavaFrequency" -> layout.lavaFrequency();
            case "layout.trapDensity" -> layout.trapDensity();
            case "layout.floorTraps" -> layout.floorTraps();
            case "layout.secretWallChance" -> layout.secretWallChance();
            case "layout.merchantSpawnChance" -> layout.merchantSpawnChance();
            case "layout.entrancePlacement" -> layout.entrancePlacement();
            case "layout.exitDistance" -> layout.exitDistance();
            case "layout.enemyDensity" -> layout.enemyDensity();
            case "layout.maxEnemiesPerRoom" -> layout.maxEnemiesPerRoom();
            case "layout.bossRoom" -> layout.bossRoom();
            case "layout.ambushChance" -> layout.ambushChance();
            case "layout.erosion" -> layout.erosion();
            case "layout.removeCeiling" -> layout.removeCeiling();
            case "layout.flatFloor" -> layout.flatFloor();
            case "layout.solidFill" -> layout.solidFill();
            case "layout.complexity" -> layout.complexity();
            case "theme.variants" -> DEFAULT_THEME_VARIANTS;
            case "theme.decayFactor" -> theme.decayFactor();
            case "theme.overgrowthFactor" -> theme.overgrowthFactor();
            case "theme.floodingFactor" -> theme.floodingFactor();
            case "pacing.breatheRoomFrequency" -> pacing.breatheRoomFrequency();
            case "pacing.difficultyRamp" -> pacing.difficultyRamp();
            default -> throw new IllegalArgumentException("Unknown field: " + path);
        };
    }

    // ── BSON serialization ───────────────────────────────────────────

    /**
     * Serializes a flat key-value map to the BSON document format used by floor-config assets.
     */
    @Nonnull
    static BsonDocument toBsonDocument(@Nonnull Map<String, Object> map) {
        BsonDocument document = new BsonDocument();
        for (Map.Entry<String, Object> entry : new TreeMap<>(map).entrySet()) {
            document.put(entry.getKey(), toBsonValue(entry.getValue()));
        }
        return document;
    }

    @Nonnull
    private static BsonValue toBsonValue(@Nullable Object value) {
        if (value == null) {
            return BsonNull.VALUE;
        }
        if (value instanceof Collection<?> collection) {
            BsonArray array = new BsonArray();
            for (Object item : collection) {
                array.add(toBsonValue(item));
            }
            return array;
        }
        if (value instanceof String s) {
            return new BsonString(s);
        }
        if (value instanceof Boolean bool) {
            return BsonBoolean.valueOf(bool);
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return new BsonInt32(((Number) value).intValue());
        }
        if (value instanceof Number number) {
            return new BsonDouble(number.doubleValue());
        }
        return new BsonString(value.toString());
    }
}