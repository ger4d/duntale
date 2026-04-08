package com.duntale.zsquad.command;

import com.duntale.zsquad.dungeon.FloorConfigService;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Interactive UI page for viewing and editing per-floor dungeon generation config overrides.
 *
 * <p>Displays all overridable config fields with their effective values for the specified
 * floor level. Fields explicitly overridden on this floor are marked with a green "●"
 * indicator. The player can edit values and click "Save" to persist only the fields that
 * differ from the inherited base (nearest lower-defined floor or defaults).
 *
 * <p>Opened via {@code /dungeon floorconfig <floor>}.
 *
 * @since 1.6.0
 * @see FloorConfigService
 */
public class FloorConfigPage extends InteractiveCustomUIPage<FloorConfigPage.FloorConfigEventData> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final String GREEN = "#55FF55";
    private static final String RED = "#FF5555";
    private static final String GRAY = "#AAAAAA";
    private static final String SET_INDICATOR = "*";

    private final int floorLevel;
    private final FloorConfigService floorConfigService;

    /**
     * Creates a new floor config page.
     *
     * @param playerRef          the player opening the page
     * @param floorLevel         the floor level to configure
     * @param floorConfigService the floor config service
     */
    public FloorConfigPage(
            @Nonnull PlayerRef playerRef,
            int floorLevel,
            @Nonnull FloorConfigService floorConfigService
    ) {
        super(playerRef, CustomPageLifetime.CanDismiss, FloorConfigEventData.CODEC);
        this.floorLevel = floorLevel;
        this.floorConfigService = floorConfigService;
    }

    // ============================================
    // Build
    // ============================================

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
                      @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        cmd.append("Pages/FloorConfig/FloorConfigPage.ui");

        FloorConfigService.EffectiveConfig effective = floorConfigService.getEffectiveConfig(floorLevel);

        // Header info
        cmd.set("#FloorLabel.Text", "Floor " + floorLevel + " Config");
        String baseInfo = effective.baseFloor() != null
                ? "Base: Floor " + effective.baseFloor()
                : "All defaults";
        cmd.set("#BaseLabel.Text", baseInfo);

        // Populate all fields
        populateFields(cmd, effective);

        // Bind Save button — reads all field values at dispatch time
        events.addEventBinding(CustomUIEventBindingType.Activating, "#SaveButton",
                buildSaveBinding());

        // Bind Reset All button
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ResetButton",
                EventData.of("ResetFloor", "true"));
    }

    // ============================================
    // Event Handling
    // ============================================

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull FloorConfigEventData data) {
        if (data.save != null) {
            handleSave(data);
        } else if (data.resetFloor != null) {
            handleResetFloor();
        }
    }

    private void handleSave(@Nonnull FloorConfigEventData data) {
        Map<String, Object> allValues = buildValuesMap(data);

        try {
            floorConfigService.bulkSaveOverrides(floorLevel, allValues);
            playerRef.sendMessage(Message.raw("Floor " + floorLevel + " config saved.").color(GREEN));
            updateStatus("Saved");
            refreshDisplay();
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Failed to save floor %d config: %s", floorLevel, e.getMessage());
            playerRef.sendMessage(Message.raw("Save failed: " + e.getMessage()).color(RED));
            updateStatus("Error — see chat");
        }
    }

    private void handleResetFloor() {
        try {
            floorConfigService.clearFloor(floorLevel);
            playerRef.sendMessage(
                    Message.raw("Cleared all overrides for floor " + floorLevel + ".").color(GREEN));
            updateStatus("Reset");
            refreshDisplay();
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Failed to reset floor %d config: %s", floorLevel, e.getMessage());
            playerRef.sendMessage(Message.raw("Reset failed: " + e.getMessage()).color(RED));
            updateStatus("Error — see chat");
        }
    }

    // ============================================
    // Display helpers
    // ============================================

    private void populateFields(@Nonnull UICommandBuilder cmd,
                                @Nonnull FloorConfigService.EffectiveConfig effective) {
        for (Map.Entry<String, FloorConfigService.FieldStatus> entry : effective.fields().entrySet()) {
            String path = entry.getKey();
            FloorConfigService.FieldStatus status = entry.getValue();
            setFieldValue(cmd, path, status.value());
            setFieldTag(cmd, path, status.explicit());
        }
    }

    private void setFieldValue(@Nonnull UICommandBuilder cmd, @Nonnull String path, @Nonnull Object value) {
        switch (path) {
            // Size
            case "layout.width" -> cmd.set("#Width.Value", toInt(value));
            case "layout.depth" -> cmd.set("#Depth.Value", toInt(value));
            case "layout.height" -> cmd.set("#Height.Value", toInt(value));
            // Rooms
            case "layout.maxRooms" -> cmd.set("#MaxRooms.Value", toInt(value));
            case "layout.roomDensity" -> cmd.set("#RoomDensity.Value", toFloat(value));
            case "layout.complexity" -> cmd.set("#Complexity.Value", toFloat(value));
            case "layout.minRoomSize" -> cmd.set("#MinRoomSize.Value", toInt(value));
            case "layout.maxRoomSize" -> cmd.set("#MaxRoomSize.Value", toInt(value));
            case "layout.roomShape" -> cmd.set("#RoomShape.Value", value.toString());
            case "layout.irregularity" -> cmd.set("#Irregularity.Value", toFloat(value));
            // Corridors
            case "layout.corridorWidth" -> cmd.set("#CorridorWidth.Value", toInt(value));
            case "layout.branchChance" -> cmd.set("#BranchChance.Value", toFloat(value));
            case "layout.loopChance" -> cmd.set("#LoopChance.Value", toFloat(value));
            case "layout.windingCorridors" -> cmd.set("#WindingCheck #CheckBox.Value", (Boolean) value);
            case "layout.windingFactor" -> cmd.set("#WindingFactor.Value", toFloat(value));
            // Features
            case "layout.pillarFrequency" -> cmd.set("#PillarFreq.Value", toFloat(value));
            case "layout.waterFrequency" -> cmd.set("#WaterFreq.Value", toFloat(value));
            case "layout.lavaFrequency" -> cmd.set("#LavaFreq.Value", toFloat(value));
            case "layout.trapDensity" -> cmd.set("#TrapDensity.Value", toFloat(value));
            case "layout.floorTraps" -> cmd.set("#FloorTrapsCheck #CheckBox.Value", (Boolean) value);
            case "layout.secretWallChance" -> cmd.set("#SecretWallChance.Value", toFloat(value));
            case "layout.merchantSpawnChance" -> cmd.set("#MerchantChance.Value", toFloat(value));
            // Navigation
            case "layout.entrancePlacement" -> cmd.set("#EntrancePlacement.Value", value.toString());
            case "layout.exitDistance" -> cmd.set("#ExitDistance.Value", toFloat(value));
            // Enemies
            case "layout.enemyDensity" -> cmd.set("#EnemyDensity.Value", toFloat(value));
            case "layout.maxEnemiesPerRoom" -> cmd.set("#MaxEnemiesPerRoom.Value", toInt(value));
            case "layout.ambushChance" -> cmd.set("#AmbushChance.Value", toFloat(value));
            case "layout.bossRoom" -> cmd.set("#BossRoomCheck #CheckBox.Value", (Boolean) value);
            // Architecture
            case "layout.erosion" -> cmd.set("#Erosion.Value", toFloat(value));
            case "layout.removeCeiling" -> cmd.set("#RemoveCeilingCheck #CheckBox.Value", (Boolean) value);
            case "layout.flatFloor" -> cmd.set("#FlatFloorCheck #CheckBox.Value", (Boolean) value);
            case "layout.solidFill" -> cmd.set("#SolidFillCheck #CheckBox.Value", (Boolean) value);
            // Theme
            case "theme.decayFactor" -> cmd.set("#DecayFactor.Value", toFloat(value));
            case "theme.overgrowthFactor" -> cmd.set("#OvergrowthFactor.Value", toFloat(value));
            case "theme.floodingFactor" -> cmd.set("#FloodingFactor.Value", toFloat(value));
            // Pacing
            case "pacing.breatheRoomFrequency" -> cmd.set("#BreatheRoomFreq.Value", toFloat(value));
            case "pacing.difficultyRamp" -> cmd.set("#DifficultyRamp.Value", toFloat(value));
            default -> { /* unknown field — skip */ }
        }
    }

    private void setFieldTag(@Nonnull UICommandBuilder cmd, @Nonnull String path, boolean explicit) {
        String tagId = switch (path) {
            case "layout.width" -> "#WidthTag";
            case "layout.depth" -> "#DepthTag";
            case "layout.height" -> "#HeightTag";
            case "layout.maxRooms" -> "#MaxRoomsTag";
            case "layout.roomDensity" -> "#RoomDensityTag";
            case "layout.complexity" -> "#ComplexityTag";
            case "layout.minRoomSize" -> "#MinRoomSizeTag";
            case "layout.maxRoomSize" -> "#MaxRoomSizeTag";
            case "layout.roomShape" -> "#RoomShapeTag";
            case "layout.irregularity" -> "#IrregularityTag";
            case "layout.corridorWidth" -> "#CorridorWidthTag";
            case "layout.branchChance" -> "#BranchChanceTag";
            case "layout.loopChance" -> "#LoopChanceTag";
            case "layout.windingCorridors" -> "#WindingTag";
            case "layout.windingFactor" -> "#WindingFactorTag";
            case "layout.pillarFrequency" -> "#PillarFreqTag";
            case "layout.waterFrequency" -> "#WaterFreqTag";
            case "layout.lavaFrequency" -> "#LavaFreqTag";
            case "layout.trapDensity" -> "#TrapDensityTag";
            case "layout.floorTraps" -> "#FloorTrapsTag";
            case "layout.secretWallChance" -> "#SecretWallChanceTag";
            case "layout.merchantSpawnChance" -> "#MerchantChanceTag";
            case "layout.entrancePlacement" -> "#EntrancePlacementTag";
            case "layout.exitDistance" -> "#ExitDistanceTag";
            case "layout.enemyDensity" -> "#EnemyDensityTag";
            case "layout.maxEnemiesPerRoom" -> "#MaxEnemiesPerRoomTag";
            case "layout.ambushChance" -> "#AmbushChanceTag";
            case "layout.bossRoom" -> "#BossRoomTag";
            case "layout.erosion" -> "#ErosionTag";
            case "layout.removeCeiling" -> "#RemoveCeilingTag";
            case "layout.flatFloor" -> "#FlatFloorTag";
            case "layout.solidFill" -> "#SolidFillTag";
            case "theme.decayFactor" -> "#DecayFactorTag";
            case "theme.overgrowthFactor" -> "#OvergrowthFactorTag";
            case "theme.floodingFactor" -> "#FloodingFactorTag";
            case "pacing.breatheRoomFrequency" -> "#BreatheRoomFreqTag";
            case "pacing.difficultyRamp" -> "#DifficultyRampTag";
            default -> null;
        };
        if (tagId != null) {
            cmd.set(tagId + ".Text", explicit ? SET_INDICATOR : "");
        }
    }

    private void refreshDisplay() {
        FloorConfigService.EffectiveConfig effective = floorConfigService.getEffectiveConfig(floorLevel);
        UICommandBuilder cmd = new UICommandBuilder();

        String baseInfo = effective.baseFloor() != null
                ? "Base: Floor " + effective.baseFloor()
                : "All defaults";
        cmd.set("#BaseLabel.Text", baseInfo);

        populateFields(cmd, effective);

        sendUpdate(cmd, null, false);
    }

    private void updateStatus(@Nonnull String text) {
        UICommandBuilder cmd = new UICommandBuilder();
        cmd.set("#StatusLabel.Text", text);
        cmd.set("#StatusLabel.Visible", true);
        sendUpdate(cmd, null, false);
    }

    // ============================================
    // Values map (event data → field paths)
    // ============================================

    @Nonnull
    private static Map<String, Object> buildValuesMap(@Nonnull FloorConfigEventData d) {
        Map<String, Object> map = new LinkedHashMap<>();
        putIfNotNull(map, "layout.width", d.width);
        putIfNotNull(map, "layout.depth", d.depth);
        putIfNotNull(map, "layout.height", d.height);
        putIfNotNull(map, "layout.maxRooms", d.maxRooms);
        putIfNotNull(map, "layout.roomDensity", d.roomDensity);
        putIfNotNull(map, "layout.complexity", d.complexity);
        putIfNotNull(map, "layout.minRoomSize", d.minRoomSize);
        putIfNotNull(map, "layout.maxRoomSize", d.maxRoomSize);
        putIfNotNull(map, "layout.roomShape", d.roomShape);
        putIfNotNull(map, "layout.irregularity", d.irregularity);
        putIfNotNull(map, "layout.corridorWidth", d.corridorWidth);
        putIfNotNull(map, "layout.branchChance", d.branchChance);
        putIfNotNull(map, "layout.loopChance", d.loopChance);
        putIfNotNull(map, "layout.windingCorridors", d.windingCorridors);
        putIfNotNull(map, "layout.windingFactor", d.windingFactor);
        putIfNotNull(map, "layout.pillarFrequency", d.pillarFreq);
        putIfNotNull(map, "layout.waterFrequency", d.waterFreq);
        putIfNotNull(map, "layout.lavaFrequency", d.lavaFreq);
        putIfNotNull(map, "layout.trapDensity", d.trapDensity);
        putIfNotNull(map, "layout.floorTraps", d.floorTraps);
        putIfNotNull(map, "layout.secretWallChance", d.secretWallChance);
        putIfNotNull(map, "layout.merchantSpawnChance", d.merchantChance);
        putIfNotNull(map, "layout.entrancePlacement", d.entrancePlacement);
        putIfNotNull(map, "layout.exitDistance", d.exitDistance);
        putIfNotNull(map, "layout.enemyDensity", d.enemyDensity);
        putIfNotNull(map, "layout.maxEnemiesPerRoom", d.maxEnemiesPerRoom);
        putIfNotNull(map, "layout.ambushChance", d.ambushChance);
        putIfNotNull(map, "layout.bossRoom", d.bossRoom);
        putIfNotNull(map, "layout.erosion", d.erosion);
        putIfNotNull(map, "layout.removeCeiling", d.removeCeiling);
        putIfNotNull(map, "layout.flatFloor", d.flatFloor);
        putIfNotNull(map, "layout.solidFill", d.solidFill);
        putIfNotNull(map, "theme.decayFactor", d.decayFactor);
        putIfNotNull(map, "theme.overgrowthFactor", d.overgrowthFactor);
        putIfNotNull(map, "theme.floodingFactor", d.floodingFactor);
        putIfNotNull(map, "pacing.breatheRoomFrequency", d.breatheRoomFreq);
        putIfNotNull(map, "pacing.difficultyRamp", d.difficultyRamp);
        return map;
    }

    private static void putIfNotNull(@Nonnull Map<String, Object> map, @Nonnull String key,
                                     @Nullable Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    // ============================================
    // Type helpers
    // ============================================

    private static int toInt(@Nonnull Object value) {
        return ((Number) value).intValue();
    }

    private static float toFloat(@Nonnull Object value) {
        return ((Number) value).floatValue();
    }

    // ============================================
    // Event Binding
    // ============================================

    @Nonnull
    private static EventData buildSaveBinding() {
        return EventData.of("Save", "true")
                // Size
                .append("@Width", "#Width.Value")
                .append("@Depth", "#Depth.Value")
                .append("@Height", "#Height.Value")
                // Rooms
                .append("@MaxRooms", "#MaxRooms.Value")
                .append("@RoomDensity", "#RoomDensity.Value")
                .append("@Complexity", "#Complexity.Value")
                .append("@MinRoomSize", "#MinRoomSize.Value")
                .append("@MaxRoomSize", "#MaxRoomSize.Value")
                .append("@RoomShape", "#RoomShape.Value")
                .append("@Irregularity", "#Irregularity.Value")
                // Corridors
                .append("@CorridorWidth", "#CorridorWidth.Value")
                .append("@BranchChance", "#BranchChance.Value")
                .append("@LoopChance", "#LoopChance.Value")
                .append("@Winding", "#WindingCheck #CheckBox.Value")
                .append("@WindingFactor", "#WindingFactor.Value")
                // Features
                .append("@PillarFreq", "#PillarFreq.Value")
                .append("@WaterFreq", "#WaterFreq.Value")
                .append("@LavaFreq", "#LavaFreq.Value")
                .append("@TrapDensity", "#TrapDensity.Value")
                .append("@FloorTraps", "#FloorTrapsCheck #CheckBox.Value")
                .append("@SecretWallChance", "#SecretWallChance.Value")
                .append("@MerchantChance", "#MerchantChance.Value")
                // Navigation
                .append("@EntrancePlacement", "#EntrancePlacement.Value")
                .append("@ExitDistance", "#ExitDistance.Value")
                // Enemies
                .append("@EnemyDensity", "#EnemyDensity.Value")
                .append("@MaxEnemiesPerRoom", "#MaxEnemiesPerRoom.Value")
                .append("@AmbushChance", "#AmbushChance.Value")
                .append("@BossRoom", "#BossRoomCheck #CheckBox.Value")
                // Architecture
                .append("@Erosion", "#Erosion.Value")
                .append("@RemoveCeiling", "#RemoveCeilingCheck #CheckBox.Value")
                .append("@FlatFloor", "#FlatFloorCheck #CheckBox.Value")
                .append("@SolidFill", "#SolidFillCheck #CheckBox.Value")
                // Theme
                .append("@DecayFactor", "#DecayFactor.Value")
                .append("@OvergrowthFactor", "#OvergrowthFactor.Value")
                .append("@FloodingFactor", "#FloodingFactor.Value")
                // Pacing
                .append("@BreatheRoomFreq", "#BreatheRoomFreq.Value")
                .append("@DifficultyRamp", "#DifficultyRamp.Value");
    }

    // ============================================
    // Event Data Codec
    // ============================================

    /**
     * Event data received when Save or Reset All is clicked.
     * Save reads all field values from the UI at dispatch time via {@code @} bindings.
     */
    public static class FloorConfigEventData {

        public static final BuilderCodec<FloorConfigEventData> CODEC = BuilderCodec.builder(
                        FloorConfigEventData.class, FloorConfigEventData::new)
                // Actions
                .addField(new KeyedCodec<>("Save", Codec.STRING), (e, v) -> e.save = v, e -> e.save)
                .addField(new KeyedCodec<>("ResetFloor", Codec.STRING), (e, v) -> e.resetFloor = v, e -> e.resetFloor)
                // Size
                .addField(new KeyedCodec<>("@Width", Codec.INTEGER), (e, v) -> e.width = v, e -> e.width)
                .addField(new KeyedCodec<>("@Depth", Codec.INTEGER), (e, v) -> e.depth = v, e -> e.depth)
                .addField(new KeyedCodec<>("@Height", Codec.INTEGER), (e, v) -> e.height = v, e -> e.height)
                // Rooms
                .addField(new KeyedCodec<>("@MaxRooms", Codec.INTEGER), (e, v) -> e.maxRooms = v, e -> e.maxRooms)
                .addField(new KeyedCodec<>("@RoomDensity", Codec.FLOAT), (e, v) -> e.roomDensity = v, e -> e.roomDensity)
                .addField(new KeyedCodec<>("@Complexity", Codec.FLOAT), (e, v) -> e.complexity = v, e -> e.complexity)
                .addField(new KeyedCodec<>("@MinRoomSize", Codec.INTEGER), (e, v) -> e.minRoomSize = v, e -> e.minRoomSize)
                .addField(new KeyedCodec<>("@MaxRoomSize", Codec.INTEGER), (e, v) -> e.maxRoomSize = v, e -> e.maxRoomSize)
                .addField(new KeyedCodec<>("@RoomShape", Codec.STRING), (e, v) -> e.roomShape = v, e -> e.roomShape)
                .addField(new KeyedCodec<>("@Irregularity", Codec.FLOAT), (e, v) -> e.irregularity = v, e -> e.irregularity)
                // Corridors
                .addField(new KeyedCodec<>("@CorridorWidth", Codec.INTEGER), (e, v) -> e.corridorWidth = v, e -> e.corridorWidth)
                .addField(new KeyedCodec<>("@BranchChance", Codec.FLOAT), (e, v) -> e.branchChance = v, e -> e.branchChance)
                .addField(new KeyedCodec<>("@LoopChance", Codec.FLOAT), (e, v) -> e.loopChance = v, e -> e.loopChance)
                .addField(new KeyedCodec<>("@Winding", Codec.BOOLEAN), (e, v) -> e.windingCorridors = v, e -> e.windingCorridors)
                .addField(new KeyedCodec<>("@WindingFactor", Codec.FLOAT), (e, v) -> e.windingFactor = v, e -> e.windingFactor)
                // Features
                .addField(new KeyedCodec<>("@PillarFreq", Codec.FLOAT), (e, v) -> e.pillarFreq = v, e -> e.pillarFreq)
                .addField(new KeyedCodec<>("@WaterFreq", Codec.FLOAT), (e, v) -> e.waterFreq = v, e -> e.waterFreq)
                .addField(new KeyedCodec<>("@LavaFreq", Codec.FLOAT), (e, v) -> e.lavaFreq = v, e -> e.lavaFreq)
                .addField(new KeyedCodec<>("@TrapDensity", Codec.FLOAT), (e, v) -> e.trapDensity = v, e -> e.trapDensity)
                .addField(new KeyedCodec<>("@FloorTraps", Codec.BOOLEAN), (e, v) -> e.floorTraps = v, e -> e.floorTraps)
                .addField(new KeyedCodec<>("@SecretWallChance", Codec.FLOAT), (e, v) -> e.secretWallChance = v, e -> e.secretWallChance)
                .addField(new KeyedCodec<>("@MerchantChance", Codec.FLOAT), (e, v) -> e.merchantChance = v, e -> e.merchantChance)
                // Navigation
                .addField(new KeyedCodec<>("@EntrancePlacement", Codec.STRING), (e, v) -> e.entrancePlacement = v, e -> e.entrancePlacement)
                .addField(new KeyedCodec<>("@ExitDistance", Codec.FLOAT), (e, v) -> e.exitDistance = v, e -> e.exitDistance)
                // Enemies
                .addField(new KeyedCodec<>("@EnemyDensity", Codec.FLOAT), (e, v) -> e.enemyDensity = v, e -> e.enemyDensity)
                .addField(new KeyedCodec<>("@MaxEnemiesPerRoom", Codec.INTEGER), (e, v) -> e.maxEnemiesPerRoom = v, e -> e.maxEnemiesPerRoom)
                .addField(new KeyedCodec<>("@AmbushChance", Codec.FLOAT), (e, v) -> e.ambushChance = v, e -> e.ambushChance)
                .addField(new KeyedCodec<>("@BossRoom", Codec.BOOLEAN), (e, v) -> e.bossRoom = v, e -> e.bossRoom)
                // Architecture
                .addField(new KeyedCodec<>("@Erosion", Codec.FLOAT), (e, v) -> e.erosion = v, e -> e.erosion)
                .addField(new KeyedCodec<>("@RemoveCeiling", Codec.BOOLEAN), (e, v) -> e.removeCeiling = v, e -> e.removeCeiling)
                .addField(new KeyedCodec<>("@FlatFloor", Codec.BOOLEAN), (e, v) -> e.flatFloor = v, e -> e.flatFloor)
                .addField(new KeyedCodec<>("@SolidFill", Codec.BOOLEAN), (e, v) -> e.solidFill = v, e -> e.solidFill)
                // Theme
                .addField(new KeyedCodec<>("@DecayFactor", Codec.FLOAT), (e, v) -> e.decayFactor = v, e -> e.decayFactor)
                .addField(new KeyedCodec<>("@OvergrowthFactor", Codec.FLOAT), (e, v) -> e.overgrowthFactor = v, e -> e.overgrowthFactor)
                .addField(new KeyedCodec<>("@FloodingFactor", Codec.FLOAT), (e, v) -> e.floodingFactor = v, e -> e.floodingFactor)
                // Pacing
                .addField(new KeyedCodec<>("@BreatheRoomFreq", Codec.FLOAT), (e, v) -> e.breatheRoomFreq = v, e -> e.breatheRoomFreq)
                .addField(new KeyedCodec<>("@DifficultyRamp", Codec.FLOAT), (e, v) -> e.difficultyRamp = v, e -> e.difficultyRamp)
                .build();

        // Actions
        String save;
        String resetFloor;
        // Size
        Integer width, depth, height;
        // Rooms
        Integer maxRooms;
        Float roomDensity, complexity;
        Integer minRoomSize, maxRoomSize;
        String roomShape;
        Float irregularity;
        // Corridors
        Integer corridorWidth;
        Float branchChance, loopChance;
        Boolean windingCorridors;
        Float windingFactor;
        // Features
        Float pillarFreq, waterFreq, lavaFreq;
        Float trapDensity;
        Boolean floorTraps;
        Float secretWallChance, merchantChance;
        // Navigation
        String entrancePlacement;
        Float exitDistance;
        // Enemies
        Float enemyDensity;
        Integer maxEnemiesPerRoom;
        Float ambushChance;
        Boolean bossRoom;
        // Architecture
        Float erosion;
        Boolean removeCeiling, flatFloor, solidFill;
        // Theme
        Float decayFactor, overgrowthFactor, floodingFactor;
        // Pacing
        Float breatheRoomFreq, difficultyRamp;
    }
}
