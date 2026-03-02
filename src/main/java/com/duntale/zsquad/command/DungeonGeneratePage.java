package com.duntale.zsquad.command;

import com.duntale.dungeongen.config.DungeonConfig;
import com.duntale.dungeongen.config.LayoutConfig;
import com.duntale.dungeongen.config.PacingConfig;
import com.duntale.dungeongen.config.ThemeConfig;
import com.duntale.dungeongen.config.Vec3i;
import com.duntale.dungeongen.generator.GenerationOrchestrator;
import com.duntale.dungeongen.generator.GenerationResult;
import com.duntale.zsquad.ZSquadPlugin;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * Interactive UI page for configuring and triggering dungeon generation.
 *
 * <p>Displays a scrollable form with all {@link DungeonConfig} parameters,
 * pre-populated with defaults. The player's current position fills the
 * origin fields. Clicking "Generate Dungeon" reads all field values and
 * invokes {@link GenerationOrchestrator#generate(DungeonConfig)} directly.</p>
 *
 * @since 1.2.0
 */
public class DungeonGeneratePage extends InteractiveCustomUIPage<DungeonGeneratePage.GenerateEventData> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final World world;

    /**
     * Create a new dungeon generation page.
     *
     * @param playerRef the player opening the page
     * @param world     the world the player is in (for dispatching results back)
     */
    public DungeonGeneratePage(@Nonnull PlayerRef playerRef, @Nonnull World world) {
        super(playerRef, CustomPageLifetime.CanDismiss, GenerateEventData.CODEC);
        this.world = world;
    }

    // ============================================
    // Build
    // ============================================

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
                      @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        cmd.append("Pages/Generate/DungeonGeneratePage.ui");

        // Set origin to player's current position
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        Vector3d pos = transform.getPosition();
        cmd.set("#OriginX.Value", (int) pos.x);
        cmd.set("#OriginY.Value", (int) pos.y);
        cmd.set("#OriginZ.Value", (int) pos.z);

        // Set world name to current world
        cmd.set("#WorldName.Value", world.getName());

        // Set default values for text fields (decimals)
        LayoutConfig ld = LayoutConfig.defaults();
        ThemeConfig td = ThemeConfig.defaults();
        PacingConfig pd = PacingConfig.defaults();

        cmd.set("#RoomDensity.Value", String.valueOf(ld.roomDensity()));
        cmd.set("#Complexity.Value", String.valueOf(ld.complexity()));
        cmd.set("#RoomShape.Value", ld.roomShape());
        cmd.set("#Irregularity.Value", String.valueOf(ld.irregularity()));
        cmd.set("#BranchChance.Value", String.valueOf(ld.branchChance()));
        cmd.set("#LoopChance.Value", String.valueOf(ld.loopChance()));
        cmd.set("#WindingFactor.Value", String.valueOf(ld.windingFactor()));
        cmd.set("#PillarFreq.Value", String.valueOf(ld.pillarFrequency()));
        cmd.set("#WaterFreq.Value", String.valueOf(ld.waterFrequency()));
        cmd.set("#LavaFreq.Value", String.valueOf(ld.lavaFrequency()));
        cmd.set("#TrapDensity.Value", String.valueOf(ld.trapDensity()));
        cmd.set("#SecretWallChance.Value", String.valueOf(ld.secretWallChance()));
        cmd.set("#EntrancePlacement.Value", ld.entrancePlacement());
        cmd.set("#ExitDistance.Value", String.valueOf(ld.exitDistance()));
        cmd.set("#EnemyDensity.Value", String.valueOf(ld.enemyDensity()));
        cmd.set("#AmbushChance.Value", String.valueOf(ld.ambushChance()));
        cmd.set("#Erosion.Value", String.valueOf(ld.erosion()));
        cmd.set("#Palette.Value", td.palette());
        cmd.set("#DecayFactor.Value", String.valueOf(td.decayFactor()));
        cmd.set("#OvergrowthFactor.Value", String.valueOf(td.overgrowthFactor()));
        cmd.set("#FloodingFactor.Value", String.valueOf(td.floodingFactor()));
        cmd.set("#BreatheRoomFreq.Value", String.valueOf(pd.breatheRoomFrequency()));
        cmd.set("#DifficultyRamp.Value", String.valueOf(pd.difficultyRamp()));

        // Bind Generate button - reads ALL field values at dispatch time
        events.addEventBinding(CustomUIEventBindingType.Activating, "#GenerateButton",
            buildGenerateBinding());
    }

    // ============================================
    // Event Handling
    // ============================================

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull GenerateEventData data) {
        if (data.generate != null) {
            handleGenerate(data);
        }
    }

    private void handleGenerate(@Nonnull GenerateEventData d) {
        DungeonConfig config;
        try {
            config = buildConfig(d);
        } catch (Exception e) {
            playerRef.sendMessage(Message.raw("[DungeonGen] Invalid config: " + e.getMessage()).color("#FF5555"));
            LOGGER.atWarning().log("[DungeonGen] Config parse error: %s", e.getMessage());
            return;
        }

        // Update status on page
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        cmd.set("#StatusLabel.Text", "Generating...");
        this.sendUpdate(cmd, events, false);

        playerRef.sendMessage(Message.raw("[DungeonGen] Generating dungeon...").color("#FFD700"));

        GenerationOrchestrator orchestrator = ZSquadPlugin.get().getDungeonOrchestrator();
        orchestrator.generate(config).thenAccept(result -> {
            world.execute(() -> sendResult(result));
        }).exceptionally(e -> {
            world.execute(() -> {
                String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                playerRef.sendMessage(Message.raw("[DungeonGen] Generation failed: " + msg).color("#FF5555"));
                LOGGER.atSevere().log("[DungeonGen] Generation failed: %s", msg);
                updateStatus("Error - see chat");
            });
            return null;
        });
    }

    private void sendResult(@Nonnull GenerationResult result) {
        String summary = String.format(
            "[DungeonGen] Done! %d rooms, %d corridors, %d blocks, %d spawners - gen %dms, asm %dms",
            result.rooms(), result.corridors(), result.totalBlocks(), result.spawners(),
            result.generationTimeMs(), result.assemblyTimeMs()
        );
        String color = result.assemblyError() == null ? "#55FF55" : "#FFAA00";
        playerRef.sendMessage(Message.raw(summary).color(color));

        if (result.assemblyError() != null) {
            playerRef.sendMessage(Message.raw("[DungeonGen] Assembly error: " + result.assemblyError()).color("#FF5555"));
        }

        updateStatus(String.format("Done - %d rooms, %dms", result.rooms(), result.generationTimeMs()));
    }

    private void updateStatus(@Nonnull String text) {
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        cmd.set("#StatusLabel.Text", text);
        this.sendUpdate(cmd, events, false);
    }

    // ============================================
    // Config Builder
    // ============================================

    @Nonnull
    private DungeonConfig buildConfig(@Nonnull GenerateEventData d) {
        LayoutConfig ld = LayoutConfig.defaults();
        ThemeConfig td = ThemeConfig.defaults();
        PacingConfig pd = PacingConfig.defaults();
        DungeonConfig dd = DungeonConfig.withDefaults();

        String seed = isBlank(d.seed) ? null : d.seed;
        String worldName = isBlank(d.worldName) ? dd.worldName() : d.worldName;

        Vec3i origin = new Vec3i(
            parseInt(d.originX, dd.origin().x()),
            parseInt(d.originY, dd.origin().y()),
            parseInt(d.originZ, dd.origin().z())
        );

        LayoutConfig layout = new LayoutConfig(
            parseInt(d.width, ld.width()),
            parseInt(d.depth, ld.depth()),
            parseInt(d.height, ld.height()),
            parseDouble(d.roomDensity, ld.roomDensity()),
            parseInt(d.minRoomSize, ld.minRoomSize()),
            parseInt(d.maxRoomSize, ld.maxRoomSize()),
            parseInt(d.maxRooms, ld.maxRooms()),
            isBlank(d.roomShape) ? ld.roomShape() : d.roomShape,
            parseDouble(d.irregularity, ld.irregularity()),
            parseInt(d.corridorWidth, ld.corridorWidth()),
            parseDouble(d.branchChance, ld.branchChance()),
            parseDouble(d.loopChance, ld.loopChance()),
            d.windingCorridors != null ? d.windingCorridors : ld.windingCorridors(),
            parseDouble(d.windingFactor, ld.windingFactor()),
            parseDouble(d.pillarFreq, ld.pillarFrequency()),
            parseDouble(d.waterFreq, ld.waterFrequency()),
            parseDouble(d.lavaFreq, ld.lavaFrequency()),
            parseDouble(d.trapDensity, ld.trapDensity()),
            d.floorTraps != null ? d.floorTraps : ld.floorTraps(),
            parseDouble(d.secretWallChance, ld.secretWallChance()),
            isBlank(d.entrancePlacement) ? ld.entrancePlacement() : d.entrancePlacement,
            parseDouble(d.exitDistance, ld.exitDistance()),
            parseDouble(d.enemyDensity, ld.enemyDensity()),
            parseInt(d.maxEnemiesPerRoom, ld.maxEnemiesPerRoom()),
            d.bossRoom != null ? d.bossRoom : ld.bossRoom(),
            parseDouble(d.ambushChance, ld.ambushChance()),
            parseDouble(d.erosion, ld.erosion()),
            d.removeCeiling != null ? d.removeCeiling : ld.removeCeiling(),
            d.flatFloor != null ? d.flatFloor : ld.flatFloor(),
            d.solidFill != null ? d.solidFill : ld.solidFill(),
            parseDouble(d.complexity, ld.complexity())
        );

        ThemeConfig theme = new ThemeConfig(
            isBlank(d.palette) ? td.palette() : d.palette,
            parseDouble(d.decayFactor, td.decayFactor()),
            parseDouble(d.overgrowthFactor, td.overgrowthFactor()),
            parseDouble(d.floodingFactor, td.floodingFactor())
        );

        PacingConfig pacing = new PacingConfig(
            parseDouble(d.breatheRoomFreq, pd.breatheRoomFrequency()),
            parseDouble(d.difficultyRamp, pd.difficultyRamp())
        );

        boolean assemble = d.assemble != null ? d.assemble : dd.assemble();
        boolean clear = d.clear != null ? d.clear : dd.clear();
        int floorLevel = parseInt(d.floorLevel, dd.floorLevel());

        return new DungeonConfig(seed, null, worldName, origin, layout, theme, pacing, assemble, clear, floorLevel);
    }

    // ============================================
    // Event Binding
    // ============================================

    @Nonnull
    private static EventData buildGenerateBinding() {
        return EventData.of("Generate", "true")
            // General
            .append("@Seed", "#Seed.Value")
            .append("@WorldName", "#WorldName.Value")
            .append("@OriginX", "#OriginX.Value")
            .append("@OriginY", "#OriginY.Value")
            .append("@OriginZ", "#OriginZ.Value")
            .append("@FloorLevel", "#FloorLevel.Value")
            .append("@Assemble", "#AssembleCheck #CheckBox.Value")
            .append("@Clear", "#ClearCheck #CheckBox.Value")
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
            .append("@SecretWallChance", "#SecretWallChance.Value")
            .append("@FloorTraps", "#FloorTrapsCheck #CheckBox.Value")
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
            .append("@Palette", "#Palette.Value")
            .append("@DecayFactor", "#DecayFactor.Value")
            .append("@OvergrowthFactor", "#OvergrowthFactor.Value")
            .append("@FloodingFactor", "#FloodingFactor.Value")
            // Pacing
            .append("@BreatheRoomFreq", "#BreatheRoomFreq.Value")
            .append("@DifficultyRamp", "#DifficultyRamp.Value");
    }

    // ============================================
    // Parse helpers
    // ============================================

    private static int parseInt(@javax.annotation.Nullable String value, int fallback) {
        if (isBlank(value)) return fallback;
        try { return Integer.parseInt(value.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    private static double parseDouble(@javax.annotation.Nullable String value, double fallback) {
        if (isBlank(value)) return fallback;
        try { return Double.parseDouble(value.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    private static boolean isBlank(@javax.annotation.Nullable String s) {
        return s == null || s.isBlank();
    }

    // ============================================
    // Event Data Codec
    // ============================================

    /**
     * Event data received when the Generate button is clicked.
     * All field values are read from the UI at dispatch time via {@code @} bindings.
     */
    public static class GenerateEventData {

        public static final BuilderCodec<GenerateEventData> CODEC = BuilderCodec.builder(
                GenerateEventData.class, GenerateEventData::new)
            // Action
            .addField(new KeyedCodec<>("Generate", Codec.STRING), (e, v) -> e.generate = v, e -> e.generate)
            // General - strings
            .addField(new KeyedCodec<>("@Seed", Codec.STRING), (e, v) -> e.seed = v, e -> e.seed)
            .addField(new KeyedCodec<>("@WorldName", Codec.STRING), (e, v) -> e.worldName = v, e -> e.worldName)
            .addField(new KeyedCodec<>("@OriginX", Codec.STRING), (e, v) -> e.originX = v, e -> e.originX)
            .addField(new KeyedCodec<>("@OriginY", Codec.STRING), (e, v) -> e.originY = v, e -> e.originY)
            .addField(new KeyedCodec<>("@OriginZ", Codec.STRING), (e, v) -> e.originZ = v, e -> e.originZ)
            .addField(new KeyedCodec<>("@FloorLevel", Codec.STRING), (e, v) -> e.floorLevel = v, e -> e.floorLevel)
            .addField(new KeyedCodec<>("@Assemble", Codec.BOOLEAN), (e, v) -> e.assemble = v, e -> e.assemble)
            .addField(new KeyedCodec<>("@Clear", Codec.BOOLEAN), (e, v) -> e.clear = v, e -> e.clear)
            // Size
            .addField(new KeyedCodec<>("@Width", Codec.STRING), (e, v) -> e.width = v, e -> e.width)
            .addField(new KeyedCodec<>("@Depth", Codec.STRING), (e, v) -> e.depth = v, e -> e.depth)
            .addField(new KeyedCodec<>("@Height", Codec.STRING), (e, v) -> e.height = v, e -> e.height)
            // Rooms
            .addField(new KeyedCodec<>("@MaxRooms", Codec.STRING), (e, v) -> e.maxRooms = v, e -> e.maxRooms)
            .addField(new KeyedCodec<>("@RoomDensity", Codec.STRING), (e, v) -> e.roomDensity = v, e -> e.roomDensity)
            .addField(new KeyedCodec<>("@Complexity", Codec.STRING), (e, v) -> e.complexity = v, e -> e.complexity)
            .addField(new KeyedCodec<>("@MinRoomSize", Codec.STRING), (e, v) -> e.minRoomSize = v, e -> e.minRoomSize)
            .addField(new KeyedCodec<>("@MaxRoomSize", Codec.STRING), (e, v) -> e.maxRoomSize = v, e -> e.maxRoomSize)
            .addField(new KeyedCodec<>("@RoomShape", Codec.STRING), (e, v) -> e.roomShape = v, e -> e.roomShape)
            .addField(new KeyedCodec<>("@Irregularity", Codec.STRING), (e, v) -> e.irregularity = v, e -> e.irregularity)
            // Corridors
            .addField(new KeyedCodec<>("@CorridorWidth", Codec.STRING), (e, v) -> e.corridorWidth = v, e -> e.corridorWidth)
            .addField(new KeyedCodec<>("@BranchChance", Codec.STRING), (e, v) -> e.branchChance = v, e -> e.branchChance)
            .addField(new KeyedCodec<>("@LoopChance", Codec.STRING), (e, v) -> e.loopChance = v, e -> e.loopChance)
            .addField(new KeyedCodec<>("@Winding", Codec.BOOLEAN), (e, v) -> e.windingCorridors = v, e -> e.windingCorridors)
            .addField(new KeyedCodec<>("@WindingFactor", Codec.STRING), (e, v) -> e.windingFactor = v, e -> e.windingFactor)
            // Features
            .addField(new KeyedCodec<>("@PillarFreq", Codec.STRING), (e, v) -> e.pillarFreq = v, e -> e.pillarFreq)
            .addField(new KeyedCodec<>("@WaterFreq", Codec.STRING), (e, v) -> e.waterFreq = v, e -> e.waterFreq)
            .addField(new KeyedCodec<>("@LavaFreq", Codec.STRING), (e, v) -> e.lavaFreq = v, e -> e.lavaFreq)
            .addField(new KeyedCodec<>("@TrapDensity", Codec.STRING), (e, v) -> e.trapDensity = v, e -> e.trapDensity)
            .addField(new KeyedCodec<>("@SecretWallChance", Codec.STRING), (e, v) -> e.secretWallChance = v, e -> e.secretWallChance)
            .addField(new KeyedCodec<>("@FloorTraps", Codec.BOOLEAN), (e, v) -> e.floorTraps = v, e -> e.floorTraps)
            // Navigation
            .addField(new KeyedCodec<>("@EntrancePlacement", Codec.STRING), (e, v) -> e.entrancePlacement = v, e -> e.entrancePlacement)
            .addField(new KeyedCodec<>("@ExitDistance", Codec.STRING), (e, v) -> e.exitDistance = v, e -> e.exitDistance)
            // Enemies
            .addField(new KeyedCodec<>("@EnemyDensity", Codec.STRING), (e, v) -> e.enemyDensity = v, e -> e.enemyDensity)
            .addField(new KeyedCodec<>("@MaxEnemiesPerRoom", Codec.STRING), (e, v) -> e.maxEnemiesPerRoom = v, e -> e.maxEnemiesPerRoom)
            .addField(new KeyedCodec<>("@AmbushChance", Codec.STRING), (e, v) -> e.ambushChance = v, e -> e.ambushChance)
            .addField(new KeyedCodec<>("@BossRoom", Codec.BOOLEAN), (e, v) -> e.bossRoom = v, e -> e.bossRoom)
            // Architecture
            .addField(new KeyedCodec<>("@Erosion", Codec.STRING), (e, v) -> e.erosion = v, e -> e.erosion)
            .addField(new KeyedCodec<>("@RemoveCeiling", Codec.BOOLEAN), (e, v) -> e.removeCeiling = v, e -> e.removeCeiling)
            .addField(new KeyedCodec<>("@FlatFloor", Codec.BOOLEAN), (e, v) -> e.flatFloor = v, e -> e.flatFloor)
            .addField(new KeyedCodec<>("@SolidFill", Codec.BOOLEAN), (e, v) -> e.solidFill = v, e -> e.solidFill)
            // Theme
            .addField(new KeyedCodec<>("@Palette", Codec.STRING), (e, v) -> e.palette = v, e -> e.palette)
            .addField(new KeyedCodec<>("@DecayFactor", Codec.STRING), (e, v) -> e.decayFactor = v, e -> e.decayFactor)
            .addField(new KeyedCodec<>("@OvergrowthFactor", Codec.STRING), (e, v) -> e.overgrowthFactor = v, e -> e.overgrowthFactor)
            .addField(new KeyedCodec<>("@FloodingFactor", Codec.STRING), (e, v) -> e.floodingFactor = v, e -> e.floodingFactor)
            // Pacing
            .addField(new KeyedCodec<>("@BreatheRoomFreq", Codec.STRING), (e, v) -> e.breatheRoomFreq = v, e -> e.breatheRoomFreq)
            .addField(new KeyedCodec<>("@DifficultyRamp", Codec.STRING), (e, v) -> e.difficultyRamp = v, e -> e.difficultyRamp)
            .build();

        // Action
        String generate;
        // General
        String seed;
        String worldName;
        String originX, originY, originZ;
        String floorLevel;
        Boolean assemble;
        Boolean clear;
        // Size
        String width, depth, height;
        // Rooms
        String maxRooms, roomDensity, complexity;
        String minRoomSize, maxRoomSize;
        String roomShape, irregularity;
        // Corridors
        String corridorWidth, branchChance, loopChance;
        Boolean windingCorridors;
        String windingFactor;
        // Features
        String pillarFreq, waterFreq, lavaFreq;
        String trapDensity, secretWallChance;
        Boolean floorTraps;
        // Navigation
        String entrancePlacement, exitDistance;
        // Enemies
        String enemyDensity, maxEnemiesPerRoom, ambushChance;
        Boolean bossRoom;
        // Architecture
        String erosion;
        Boolean removeCeiling, flatFloor, solidFill;
        // Theme
        String palette, decayFactor, overgrowthFactor, floodingFactor;
        // Pacing
        String breatheRoomFreq, difficultyRamp;
    }
}
