package com.duntale.zsquad.command;

import com.duntale.dungeongen.config.DungeonConfig;
import com.duntale.dungeongen.config.LayoutConfig;
import com.duntale.dungeongen.config.PacingConfig;
import com.duntale.dungeongen.config.ThemeConfig;
import com.duntale.dungeongen.config.Vec3i;
import com.duntale.dungeongen.generator.GenerationOrchestrator;
import com.duntale.dungeongen.generator.GenerationResult;
import com.duntale.dungeongen.util.JsonParser;
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
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Interactive UI page for configuring and triggering dungeon generation.
 *
 * <p>Displays a scrollable form with all {@link DungeonConfig} parameters,
 * pre-populated with saved values (or defaults on first use). The player's
 * current position fills the origin fields. Clicking "Generate Dungeon"
 * reads all field values, persists them to disk, and invokes
 * {@link GenerationOrchestrator#generate(DungeonConfig)} directly.</p>
 *
 * @since 1.2.0
 */
public class DungeonGeneratePage extends InteractiveCustomUIPage<DungeonGeneratePage.GenerateEventData> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String CONFIG_FILENAME = "generate-config.json";

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

        // Load saved config (or empty map if none)
        Map<String, Object> saved = loadSavedConfig();

        // Origin: use saved values if present, otherwise player's current position
        if (saved.containsKey("originX")) {
            cmd.set("#OriginX.Value", JsonParser.toInt(saved.get("originX")));
            cmd.set("#OriginY.Value", JsonParser.toInt(saved.get("originY")));
            cmd.set("#OriginZ.Value", JsonParser.toInt(saved.get("originZ")));
        } else {
            TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
            Vector3d pos = transform.getPosition();
            cmd.set("#OriginX.Value", (int) pos.x);
            cmd.set("#OriginY.Value", (int) pos.y);
            cmd.set("#OriginZ.Value", (int) pos.z);
        }

        // World name: use saved value if present, otherwise current world
        cmd.set("#WorldName.Value", savedStr(saved, "worldName", world.getName()));

        // Defaults from config records
        LayoutConfig ld = LayoutConfig.defaults();
        ThemeConfig td = ThemeConfig.defaults();
        PacingConfig pd = PacingConfig.defaults();
        DungeonConfig dd = DungeonConfig.withDefaults();

        // Populate seed if saved
        String savedSeed = JsonParser.toStringOrNull(saved.get("seed"));
        if (savedSeed != null && !savedSeed.isBlank()) {
            cmd.set("#Seed.Value", savedSeed);
        }

        // NumberField values (integer) - use saved or defaults
        cmd.set("#FloorLevel.Value", saved.containsKey("floorLevel") ? JsonParser.toInt(saved.get("floorLevel")) : dd.floorLevel());
        cmd.set("#Width.Value", saved.containsKey("width") ? JsonParser.toInt(saved.get("width")) : ld.width());
        cmd.set("#Depth.Value", saved.containsKey("depth") ? JsonParser.toInt(saved.get("depth")) : ld.depth());
        cmd.set("#Height.Value", saved.containsKey("height") ? JsonParser.toInt(saved.get("height")) : ld.height());
        cmd.set("#MaxRooms.Value", saved.containsKey("maxRooms") ? JsonParser.toInt(saved.get("maxRooms")) : ld.maxRooms());
        cmd.set("#MinRoomSize.Value", saved.containsKey("minRoomSize") ? JsonParser.toInt(saved.get("minRoomSize")) : ld.minRoomSize());
        cmd.set("#MaxRoomSize.Value", saved.containsKey("maxRoomSize") ? JsonParser.toInt(saved.get("maxRoomSize")) : ld.maxRoomSize());
        cmd.set("#CorridorWidth.Value", saved.containsKey("corridorWidth") ? JsonParser.toInt(saved.get("corridorWidth")) : ld.corridorWidth());
        cmd.set("#MaxEnemiesPerRoom.Value", saved.containsKey("maxEnemiesPerRoom") ? JsonParser.toInt(saved.get("maxEnemiesPerRoom")) : ld.maxEnemiesPerRoom());

        // TextField values (strings/decimals) - use saved or defaults
        cmd.set("#RoomDensity.Value", savedStr(saved, "roomDensity", String.valueOf(ld.roomDensity())));
        cmd.set("#Complexity.Value", savedStr(saved, "complexity", String.valueOf(ld.complexity())));
        cmd.set("#RoomShape.Value", savedStr(saved, "roomShape", ld.roomShape()));
        cmd.set("#Irregularity.Value", savedStr(saved, "irregularity", String.valueOf(ld.irregularity())));
        cmd.set("#BranchChance.Value", savedStr(saved, "branchChance", String.valueOf(ld.branchChance())));
        cmd.set("#LoopChance.Value", savedStr(saved, "loopChance", String.valueOf(ld.loopChance())));
        cmd.set("#WindingFactor.Value", savedStr(saved, "windingFactor", String.valueOf(ld.windingFactor())));
        cmd.set("#PillarFreq.Value", savedStr(saved, "pillarFreq", String.valueOf(ld.pillarFrequency())));
        cmd.set("#WaterFreq.Value", savedStr(saved, "waterFreq", String.valueOf(ld.waterFrequency())));
        cmd.set("#LavaFreq.Value", savedStr(saved, "lavaFreq", String.valueOf(ld.lavaFrequency())));
        cmd.set("#TrapDensity.Value", savedStr(saved, "trapDensity", String.valueOf(ld.trapDensity())));
        cmd.set("#SecretWallChance.Value", savedStr(saved, "secretWallChance", String.valueOf(ld.secretWallChance())));
        cmd.set("#EntrancePlacement.Value", savedStr(saved, "entrancePlacement", ld.entrancePlacement()));
        cmd.set("#ExitDistance.Value", savedStr(saved, "exitDistance", String.valueOf(ld.exitDistance())));
        cmd.set("#EnemyDensity.Value", savedStr(saved, "enemyDensity", String.valueOf(ld.enemyDensity())));
        cmd.set("#AmbushChance.Value", savedStr(saved, "ambushChance", String.valueOf(ld.ambushChance())));
        cmd.set("#Erosion.Value", savedStr(saved, "erosion", String.valueOf(ld.erosion())));
        cmd.set("#Palette.Value", savedStr(saved, "palette", "Crypt"));
        cmd.set("#DecayFactor.Value", savedStr(saved, "decayFactor", String.valueOf(td.decayFactor())));
        cmd.set("#OvergrowthFactor.Value", savedStr(saved, "overgrowthFactor", String.valueOf(td.overgrowthFactor())));
        cmd.set("#FloodingFactor.Value", savedStr(saved, "floodingFactor", String.valueOf(td.floodingFactor())));
        cmd.set("#BreatheRoomFreq.Value", savedStr(saved, "breatheRoomFreq", String.valueOf(pd.breatheRoomFrequency())));
        cmd.set("#DifficultyRamp.Value", savedStr(saved, "difficultyRamp", String.valueOf(pd.difficultyRamp())));

        // Checkbox values - use saved or defaults
        if (saved.containsKey("assemble")) {
            cmd.set("#AssembleCheck #CheckBox.Value", JsonParser.toBoolean(saved.get("assemble")));
        }
        if (saved.containsKey("clear")) {
            cmd.set("#ClearCheck #CheckBox.Value", JsonParser.toBoolean(saved.get("clear")));
        }
        if (saved.containsKey("windingCorridors")) {
            cmd.set("#WindingCheck #CheckBox.Value", JsonParser.toBoolean(saved.get("windingCorridors")));
        }
        if (saved.containsKey("floorTraps")) {
            cmd.set("#FloorTrapsCheck #CheckBox.Value", JsonParser.toBoolean(saved.get("floorTraps")));
        }
        if (saved.containsKey("bossRoom")) {
            cmd.set("#BossRoomCheck #CheckBox.Value", JsonParser.toBoolean(saved.get("bossRoom")));
        }
        if (saved.containsKey("removeCeiling")) {
            cmd.set("#RemoveCeilingCheck #CheckBox.Value", JsonParser.toBoolean(saved.get("removeCeiling")));
        }
        if (saved.containsKey("flatFloor")) {
            cmd.set("#FlatFloorCheck #CheckBox.Value", JsonParser.toBoolean(saved.get("flatFloor")));
        }
        if (saved.containsKey("solidFill")) {
            cmd.set("#SolidFillCheck #CheckBox.Value", JsonParser.toBoolean(saved.get("solidFill")));
        }

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

        // Persist the current values for next time
        saveConfig(d);

        // Update status on page
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();

        if (config.clear()) {
            cmd.set("#StatusGroup.Visible", true);
            cmd.set("#StatusGroup #StatusLabel.Text", "Clearing area...");
            cmd.set("#StatusGroup #StatusSpinner.Visible", true);
            this.sendUpdate(cmd, events, false);

            // Build clear command: clear x1 y1 z1 x2 y2 z2
            Vec3i o = config.origin();
            LayoutConfig lc = config.layout();
            String clearCmd = String.format("clear %d %d %d %d %d %d",
                o.x(), o.y(), o.z(),
                o.x() + lc.width(), o.y() + lc.height(), o.z() + lc.depth());
            LOGGER.atInfo().log("[DungeonGen] Clearing area: /%s", clearCmd);
            CommandManager.get().handleCommand(playerRef, clearCmd);
            playerRef.sendMessage(Message.raw("[DungeonGen] Clearing area, generation will start in 2.5s...").color("#FFD700"));

            // Delay 2.5s then generate (off world thread, re-dispatch results back)
            CompletableFuture.runAsync(() -> {
                try { Thread.sleep(2500); } catch (InterruptedException ignored) {}
            }).thenCompose(v -> {
                world.execute(() -> updateStatus("Generating..."));
                return ZSquadPlugin.get().getDungeonOrchestrator().generate(config);
            }).thenAccept(result -> world.execute(() -> sendResult(result, config)))
              .exceptionally(e -> {
                  world.execute(() -> {
                      String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                      playerRef.sendMessage(Message.raw("[DungeonGen] Generation failed: " + msg).color("#FF5555"));
                      LOGGER.atSevere().log("[DungeonGen] Generation failed: %s", msg);
                      updateStatus("Error - see chat", false);
                  });
                  return null;
              });
        } else {
            cmd.set("#StatusGroup.Visible", true);
            cmd.set("#StatusGroup #StatusLabel.Text", "Generating...");
            cmd.set("#StatusGroup #StatusSpinner.Visible", true);
            this.sendUpdate(cmd, events, false);
            playerRef.sendMessage(Message.raw("[DungeonGen] Generating dungeon...").color("#FFD700"));

            GenerationOrchestrator orchestrator = ZSquadPlugin.get().getDungeonOrchestrator();
            orchestrator.generate(config).thenAccept(result -> {
                world.execute(() -> sendResult(result, config));
            }).exceptionally(e -> {
                world.execute(() -> {
                    String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                    playerRef.sendMessage(Message.raw("[DungeonGen] Generation failed: " + msg).color("#FF5555"));
                    LOGGER.atSevere().log("[DungeonGen] Generation failed: %s", msg);
                    updateStatus("Error - see chat", false);
                });
                return null;
            });
        }
    }

    private void sendResult(@Nonnull GenerationResult result, @Nonnull DungeonConfig config) {
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

        // Create spawner entities in the ECS (if assembled and spawners exist)
        if (config.assemble() && !result.spawnerDefinitions().isEmpty()) {
            try {
                Store<EntityStore> store = world.getEntityStore().getStore();
                ZSquadPlugin.get().getSpawnerFactory().createSpawners(
                    store, result.spawnerDefinitions(), config.origin());
                playerRef.sendMessage(Message.raw(
                    "[DungeonGen] Registered " + result.spawnerDefinitions().size() + " spawner entities"
                ).color("#55FFFF"));
            } catch (Exception e) {
                LOGGER.atSevere().log("[DungeonGen] Failed to create spawner entities: %s", e.getMessage());
                playerRef.sendMessage(Message.raw("[DungeonGen] Spawner entity creation failed: " + e.getMessage()).color("#FF5555"));
            }
        }

        updateStatus(String.format("Done - %d rooms, %dms", result.rooms(), result.generationTimeMs()), false);
    }

    private void updateStatus(@Nonnull String text) {
        updateStatus(text, true);
    }

    private void updateStatus(@Nonnull String text, boolean showSpinner) {
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        cmd.set("#StatusGroup.Visible", true);
        cmd.set("#StatusGroup #StatusLabel.Text", text);
        cmd.set("#StatusGroup #StatusSpinner.Visible", showSpinner);
        this.sendUpdate(cmd, events, false);
    }

    // ============================================
    // Config Persistence
    // ============================================

    @Nonnull
    private static Map<String, Object> loadSavedConfig() {
        Path configPath = ZSquadPlugin.get().getDataDirectory().resolve(CONFIG_FILENAME);
        if (!Files.exists(configPath)) {
            return Map.of();
        }
        try {
            String json = Files.readString(configPath);
            Map<String, Object> result = JsonParser.parseObject(json);
            return result != null ? result : Map.of();
        } catch (Exception e) {
            LOGGER.atWarning().log("[DungeonGen] Failed to load saved config: %s", e.getMessage());
            return Map.of();
        }
    }

    private static void saveConfig(@Nonnull GenerateEventData d) {
        try {
            Path dir = ZSquadPlugin.get().getDataDirectory();
            Files.createDirectories(dir);
            Path configPath = dir.resolve(CONFIG_FILENAME);
            Files.writeString(configPath, buildConfigJson(d));
        } catch (Exception e) {
            LOGGER.atWarning().log("[DungeonGen] Failed to save config: %s", e.getMessage());
        }
    }

    @Nonnull
    private static String buildConfigJson(@Nonnull GenerateEventData d) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\n");
        appendStr(sb, "seed", d.seed);
        appendStr(sb, "worldName", d.worldName);
        appendInt(sb, "originX", d.originX);
        appendInt(sb, "originY", d.originY);
        appendInt(sb, "originZ", d.originZ);
        appendInt(sb, "floorLevel", d.floorLevel);
        appendBool(sb, "assemble", d.assemble);
        appendBool(sb, "clear", d.clear);
        appendInt(sb, "width", d.width);
        appendInt(sb, "depth", d.depth);
        appendInt(sb, "height", d.height);
        appendInt(sb, "maxRooms", d.maxRooms);
        appendStr(sb, "roomDensity", d.roomDensity);
        appendStr(sb, "complexity", d.complexity);
        appendInt(sb, "minRoomSize", d.minRoomSize);
        appendInt(sb, "maxRoomSize", d.maxRoomSize);
        appendStr(sb, "roomShape", d.roomShape);
        appendStr(sb, "irregularity", d.irregularity);
        appendInt(sb, "corridorWidth", d.corridorWidth);
        appendStr(sb, "branchChance", d.branchChance);
        appendStr(sb, "loopChance", d.loopChance);
        appendBool(sb, "windingCorridors", d.windingCorridors);
        appendStr(sb, "windingFactor", d.windingFactor);
        appendStr(sb, "pillarFreq", d.pillarFreq);
        appendStr(sb, "waterFreq", d.waterFreq);
        appendStr(sb, "lavaFreq", d.lavaFreq);
        appendStr(sb, "trapDensity", d.trapDensity);
        appendStr(sb, "secretWallChance", d.secretWallChance);
        appendBool(sb, "floorTraps", d.floorTraps);
        appendStr(sb, "entrancePlacement", d.entrancePlacement);
        appendStr(sb, "exitDistance", d.exitDistance);
        appendStr(sb, "enemyDensity", d.enemyDensity);
        appendInt(sb, "maxEnemiesPerRoom", d.maxEnemiesPerRoom);
        appendStr(sb, "ambushChance", d.ambushChance);
        appendBool(sb, "bossRoom", d.bossRoom);
        appendStr(sb, "erosion", d.erosion);
        appendBool(sb, "removeCeiling", d.removeCeiling);
        appendBool(sb, "flatFloor", d.flatFloor);
        appendBool(sb, "solidFill", d.solidFill);
        appendStr(sb, "palette", d.palette);
        appendStr(sb, "decayFactor", d.decayFactor);
        appendStr(sb, "overgrowthFactor", d.overgrowthFactor);
        appendStr(sb, "floodingFactor", d.floodingFactor);
        appendStr(sb, "breatheRoomFreq", d.breatheRoomFreq);
        // Last field - no trailing comma
        sb.append("  \"difficultyRamp\": ");
        if (d.difficultyRamp != null) {
            sb.append('"').append(escapeJson(d.difficultyRamp)).append('"');
        } else {
            sb.append("null");
        }
        sb.append('\n');
        sb.append('}');
        return sb.toString();
    }

    private static void appendStr(@Nonnull StringBuilder sb, @Nonnull String key, @Nullable String value) {
        sb.append("  \"").append(key).append("\": ");
        if (value != null) {
            sb.append('"').append(escapeJson(value)).append('"');
        } else {
            sb.append("null");
        }
        sb.append(",\n");
    }

    private static void appendInt(@Nonnull StringBuilder sb, @Nonnull String key, @Nullable Integer value) {
        sb.append("  \"").append(key).append("\": ");
        sb.append(value != null ? value.toString() : "null");
        sb.append(",\n");
    }

    private static void appendBool(@Nonnull StringBuilder sb, @Nonnull String key, @Nullable Boolean value) {
        sb.append("  \"").append(key).append("\": ");
        sb.append(value != null ? value.toString() : "null");
        sb.append(",\n");
    }

    @Nonnull
    private static String escapeJson(@Nonnull String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    @Nonnull
    private static String savedStr(@Nonnull Map<String, Object> saved, @Nonnull String key,
                                   @Nonnull String fallback) {
        String val = JsonParser.toStringOrNull(saved.get(key));
        return val != null ? val : fallback;
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
            intOrDefault(d.originX, dd.origin().x()),
            intOrDefault(d.originY, dd.origin().y()),
            intOrDefault(d.originZ, dd.origin().z())
        );

        LayoutConfig layout = new LayoutConfig(
            intOrDefault(d.width, ld.width()),
            intOrDefault(d.depth, ld.depth()),
            intOrDefault(d.height, ld.height()),
            parseDouble(d.roomDensity, ld.roomDensity()),
            intOrDefault(d.minRoomSize, ld.minRoomSize()),
            intOrDefault(d.maxRoomSize, ld.maxRoomSize()),
            intOrDefault(d.maxRooms, ld.maxRooms()),
            isBlank(d.roomShape) ? ld.roomShape() : d.roomShape,
            parseDouble(d.irregularity, ld.irregularity()),
            intOrDefault(d.corridorWidth, ld.corridorWidth()),
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
            intOrDefault(d.maxEnemiesPerRoom, ld.maxEnemiesPerRoom()),
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
        int floorLevel = intOrDefault(d.floorLevel, dd.floorLevel());

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

    private static int intOrDefault(@Nullable Integer value, int fallback) {
        return value != null ? value : fallback;
    }

    private static double parseDouble(@Nullable String value, double fallback) {
        if (isBlank(value)) return fallback;
        try { return Double.parseDouble(value.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }

    // ============================================
    // Event Data Codec
    // ============================================

    /**
     * Event data received when the Generate button is clicked.
     * All field values are read from the UI at dispatch time via {@code @} bindings.
     * NumberField elements send integers; TextField elements send strings.
     */
    public static class GenerateEventData {

        public static final BuilderCodec<GenerateEventData> CODEC = BuilderCodec.builder(
                GenerateEventData.class, GenerateEventData::new)
            // Action
            .addField(new KeyedCodec<>("Generate", Codec.STRING), (e, v) -> e.generate = v, e -> e.generate)
            // General
            .addField(new KeyedCodec<>("@Seed", Codec.STRING), (e, v) -> e.seed = v, e -> e.seed)
            .addField(new KeyedCodec<>("@WorldName", Codec.STRING), (e, v) -> e.worldName = v, e -> e.worldName)
            .addField(new KeyedCodec<>("@OriginX", Codec.INTEGER), (e, v) -> e.originX = v, e -> e.originX)
            .addField(new KeyedCodec<>("@OriginY", Codec.INTEGER), (e, v) -> e.originY = v, e -> e.originY)
            .addField(new KeyedCodec<>("@OriginZ", Codec.INTEGER), (e, v) -> e.originZ = v, e -> e.originZ)
            .addField(new KeyedCodec<>("@FloorLevel", Codec.INTEGER), (e, v) -> e.floorLevel = v, e -> e.floorLevel)
            .addField(new KeyedCodec<>("@Assemble", Codec.BOOLEAN), (e, v) -> e.assemble = v, e -> e.assemble)
            .addField(new KeyedCodec<>("@Clear", Codec.BOOLEAN), (e, v) -> e.clear = v, e -> e.clear)
            // Size
            .addField(new KeyedCodec<>("@Width", Codec.INTEGER), (e, v) -> e.width = v, e -> e.width)
            .addField(new KeyedCodec<>("@Depth", Codec.INTEGER), (e, v) -> e.depth = v, e -> e.depth)
            .addField(new KeyedCodec<>("@Height", Codec.INTEGER), (e, v) -> e.height = v, e -> e.height)
            // Rooms
            .addField(new KeyedCodec<>("@MaxRooms", Codec.INTEGER), (e, v) -> e.maxRooms = v, e -> e.maxRooms)
            .addField(new KeyedCodec<>("@RoomDensity", Codec.STRING), (e, v) -> e.roomDensity = v, e -> e.roomDensity)
            .addField(new KeyedCodec<>("@Complexity", Codec.STRING), (e, v) -> e.complexity = v, e -> e.complexity)
            .addField(new KeyedCodec<>("@MinRoomSize", Codec.INTEGER), (e, v) -> e.minRoomSize = v, e -> e.minRoomSize)
            .addField(new KeyedCodec<>("@MaxRoomSize", Codec.INTEGER), (e, v) -> e.maxRoomSize = v, e -> e.maxRoomSize)
            .addField(new KeyedCodec<>("@RoomShape", Codec.STRING), (e, v) -> e.roomShape = v, e -> e.roomShape)
            .addField(new KeyedCodec<>("@Irregularity", Codec.STRING), (e, v) -> e.irregularity = v, e -> e.irregularity)
            // Corridors
            .addField(new KeyedCodec<>("@CorridorWidth", Codec.INTEGER), (e, v) -> e.corridorWidth = v, e -> e.corridorWidth)
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
            .addField(new KeyedCodec<>("@MaxEnemiesPerRoom", Codec.INTEGER), (e, v) -> e.maxEnemiesPerRoom = v, e -> e.maxEnemiesPerRoom)
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
        Integer originX, originY, originZ;
        Integer floorLevel;
        Boolean assemble;
        Boolean clear;
        // Size
        Integer width, depth, height;
        // Rooms
        Integer maxRooms;
        String roomDensity, complexity;
        Integer minRoomSize, maxRoomSize;
        String roomShape, irregularity;
        // Corridors
        Integer corridorWidth;
        String branchChance, loopChance;
        Boolean windingCorridors;
        String windingFactor;
        // Features
        String pillarFreq, waterFreq, lavaFreq;
        String trapDensity, secretWallChance;
        Boolean floorTraps;
        // Navigation
        String entrancePlacement, exitDistance;
        // Enemies
        String enemyDensity;
        Integer maxEnemiesPerRoom;
        String ambushChance;
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
