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
import com.hypixel.hytale.builtin.instances.InstancesPlugin;
import com.hypixel.hytale.builtin.instances.config.InstanceWorldConfig;
import com.hypixel.hytale.builtin.instances.removal.WorldEmptyCondition;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Transform;
import org.joml.Vector3d;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;
import com.hypixel.hytale.server.core.universe.world.spawn.GlobalSpawnProvider;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.worldgen.provider.VoidWorldGenProvider;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Interactive UI page for configuring and triggering dungeon generation.
 *
 * <p>Displays a scrollable form with all {@link DungeonConfig} parameters,
 * pre-populated with saved values (or defaults on first use). The player's
 * current position fills the origin fields. Clicking "Generate Dungeon"
 * reads all field values, persists them to disk, and either invokes
 * {@link GenerationOrchestrator#generate(DungeonConfig)} directly for summary-only generation
 * or creates a fresh disposable preview world before assembling the dungeon there.</p>
 *
 * @since 1.2.0
 */
public class DungeonGeneratePage extends InteractiveCustomUIPage<DungeonGeneratePage.GenerateEventData> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String CONFIG_FILENAME = "generate-config.json";
    private static final String PREVIEW_WORLD_PREFIX = "dungeon-preview";
    private static final String PREVIEW_WORLD_SEPARATOR = "-preview-";

    private final World world;

    private record PreparedPreviewWorld(
            @Nonnull World world,
            @Nonnull DungeonConfig config,
            @Nonnull GenerationResult result
    ) {}

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

        // Preview world base name: use saved value if present, otherwise a stable preview prefix
        cmd.set("#WorldName.Value", savedStr(saved, "worldName", PREVIEW_WORLD_PREFIX));

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

        // FloatSlider values (0-1) - use saved or defaults
        cmd.set("#RoomDensity.Value", savedFloat(saved, "roomDensity", (float) ld.roomDensity()));
        cmd.set("#Complexity.Value", savedFloat(saved, "complexity", (float) ld.complexity()));
        cmd.set("#RoomShape.Value", savedStr(saved, "roomShape", ld.roomShape()));
        cmd.set("#Irregularity.Value", savedFloat(saved, "irregularity", (float) ld.irregularity()));
        cmd.set("#BranchChance.Value", savedFloat(saved, "branchChance", (float) ld.branchChance()));
        cmd.set("#LoopChance.Value", savedFloat(saved, "loopChance", (float) ld.loopChance()));
        cmd.set("#WindingFactor.Value", savedFloat(saved, "windingFactor", (float) ld.windingFactor()));
        cmd.set("#PillarFreq.Value", savedFloat(saved, "pillarFreq", (float) ld.pillarFrequency()));
        cmd.set("#WaterFreq.Value", savedFloat(saved, "waterFreq", (float) ld.waterFrequency()));
        cmd.set("#LavaFreq.Value", savedFloat(saved, "lavaFreq", (float) ld.lavaFrequency()));
        cmd.set("#TrapDensity.Value", savedFloat(saved, "trapDensity", (float) ld.trapDensity()));
        cmd.set("#SecretWallChance.Value", savedFloat(saved, "secretWallChance", (float) ld.secretWallChance()));
        cmd.set("#EntrancePlacement.Value", savedStr(saved, "entrancePlacement", ld.entrancePlacement()));
        cmd.set("#ExitDistance.Value", savedFloat(saved, "exitDistance", (float) ld.exitDistance()));
        cmd.set("#EnemyDensity.Value", savedFloat(saved, "enemyDensity", (float) ld.enemyDensity()));
        cmd.set("#AmbushChance.Value", savedFloat(saved, "ambushChance", (float) ld.ambushChance()));
        cmd.set("#Erosion.Value", savedFloat(saved, "erosion", (float) ld.erosion()));
        cmd.set("#Palette.Value", savedStr(saved, "palette", "Crypt"));
        cmd.set("#DecayFactor.Value", savedFloat(saved, "decayFactor", (float) td.decayFactor()));
        cmd.set("#OvergrowthFactor.Value", savedFloat(saved, "overgrowthFactor", (float) td.overgrowthFactor()));
        cmd.set("#FloodingFactor.Value", savedFloat(saved, "floodingFactor", (float) td.floodingFactor()));
        cmd.set("#BreatheRoomFreq.Value", savedFloat(saved, "breatheRoomFreq", (float) pd.breatheRoomFrequency()));
        cmd.set("#DifficultyRamp.Value", savedFloat(saved, "difficultyRamp", (float) pd.difficultyRamp()));

        // Checkbox values - use saved or defaults
        if (saved.containsKey("assemble")) {
            cmd.set("#AssembleCheck #CheckBox.Value", JsonParser.toBoolean(saved.get("assemble")));
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
            handleGenerate(ref, store, data);
        }
    }

    private void handleGenerate(@Nonnull Ref<EntityStore> ref,
                                @Nonnull Store<EntityStore> store,
                                @Nonnull GenerateEventData d) {
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

        if (!config.assemble()) {
            runSummaryOnlyGeneration(config);
            return;
        }

        DungeonConfig previewConfig = toPreviewConfig(config);

        updateStatus("Creating preview world...");
        playerRef.sendMessage(Message.raw(
                "[DungeonGen] Creating preview world '" + previewConfig.worldName() + "'..."
        ).color("#FFD700"));

        preparePreviewWorld(previewConfig).thenAccept(prepared ->
                world.execute(() -> enterPreviewWorld(ref, store, prepared))
        ).exceptionally(e -> {
            removePreviewWorldIfLoaded(previewConfig.worldName());
            world.execute(() -> handlePreviewFailure(previewConfig.worldName(), e));
            return null;
        });
    }

    private void runSummaryOnlyGeneration(@Nonnull DungeonConfig config) {
        updateStatus("Generating...");
        playerRef.sendMessage(Message.raw("[DungeonGen] Generating dungeon...").color("#FFD700"));

        GenerationOrchestrator orchestrator = ZSquadPlugin.get().getDungeonOrchestrator();
        orchestrator.generate(config).thenAccept(result ->
                world.execute(() -> sendGenerationSummary(result, null))
        ).exceptionally(e -> {
            world.execute(() -> {
                String msg = extractErrorMessage(e);
                playerRef.sendMessage(Message.raw("[DungeonGen] Generation failed: " + msg).color("#FF5555"));
                LOGGER.atSevere().log("[DungeonGen] Generation failed: %s", msg);
                updateStatus("Error - see chat", false);
            });
            return null;
        });
    }

    @Nonnull
    private CompletableFuture<PreparedPreviewWorld> preparePreviewWorld(@Nonnull DungeonConfig previewConfig) {
        return createPreviewWorld(previewConfig).thenCompose(previewWorld -> {
            world.execute(() -> updateStatus("Generating preview..."));
            return ZSquadPlugin.get().getDungeonOrchestrator().generate(previewConfig)
                    .thenCompose(result -> finalizePreviewWorld(previewWorld, previewConfig, result));
        });
    }

    @Nonnull
    private CompletableFuture<World> createPreviewWorld(@Nonnull DungeonConfig previewConfig) {
        Universe universe = Universe.get();
        Path savePath = universe.validateWorldPath(previewConfig.worldName());

        WorldConfig previewWorldConfig = new WorldConfig();
        previewWorldConfig.setUuid(UUID.randomUUID());
        previewWorldConfig.setDisplayName("Dungeon Preview Floor " + previewConfig.floorLevel());
        previewWorldConfig.setSeed(resolveWorldSeed(previewConfig.seed()));
        previewWorldConfig.setWorldGenProvider(new VoidWorldGenProvider(null, null));
        previewWorldConfig.setSpawnProvider(new GlobalSpawnProvider(toPlayerTransform(previewConfig.origin())));
        previewWorldConfig.setPvpEnabled(true);
        previewWorldConfig.setFallDamageEnabled(true);
        previewWorldConfig.setTicking(true);
        previewWorldConfig.setBlockTicking(false);
        previewWorldConfig.setSpawningNPC(false);
        previewWorldConfig.setSavingPlayers(false);
        previewWorldConfig.setDeleteOnRemove(true);
        previewWorldConfig.setDeleteOnUniverseStart(false);
        previewWorldConfig.setGameTimePaused(true);
        InstanceWorldConfig.ensureAndGet(previewWorldConfig).setRemovalConditions(WorldEmptyCondition.INSTANCE);
        previewWorldConfig.markChanged();

        return universe.makeWorld(previewConfig.worldName(), savePath, previewWorldConfig);
    }

    @Nonnull
    private CompletableFuture<PreparedPreviewWorld> finalizePreviewWorld(
            @Nonnull World previewWorld,
            @Nonnull DungeonConfig previewConfig,
            @Nonnull GenerationResult result
    ) {
        CompletableFuture<PreparedPreviewWorld> future = new CompletableFuture<>();
        previewWorld.execute(() -> {
            try {
                if (result.assemblyError() != null) {
                    throw new IllegalStateException(result.assemblyError());
                }

                WorldConfig worldConfig = previewWorld.getWorldConfig();
                worldConfig.setSpawnProvider(new GlobalSpawnProvider(toPlayerTransform(result.entrancePosition())));
                worldConfig.markChanged();

                createPreviewEntities(previewWorld, previewConfig, result);
                future.complete(new PreparedPreviewWorld(previewWorld, previewConfig, result));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private void createPreviewEntities(@Nonnull World previewWorld,
                                       @Nonnull DungeonConfig previewConfig,
                                       @Nonnull GenerationResult result) {
        Store<EntityStore> previewStore = previewWorld.getEntityStore().getStore();
        ZSquadPlugin plugin = ZSquadPlugin.get();

        if (!result.spawnerDefinitions().isEmpty()) {
            plugin.getSpawnerFactory().createSpawners(previewStore, result.spawnerDefinitions(), previewConfig.origin());
        }

        if (!result.merchantDefinitions().isEmpty()) {
            plugin.getMerchantNpcSpawner().spawnMerchants(previewStore, result.merchantDefinitions(), previewConfig.origin());
        }
    }

    private void enterPreviewWorld(@Nonnull Ref<EntityStore> ref,
                                   @Nonnull Store<EntityStore> store,
                                   @Nonnull PreparedPreviewWorld prepared) {
        if (!ref.isValid()) {
            removePreviewWorldIfLoaded(prepared.world().getName());
            return;
        }

        sendGenerationSummary(prepared.result(), prepared.world().getName());

        Player playerComponent = store.getComponent(ref, Player.getComponentType());
        if (playerComponent != null) {
            playerComponent.getPageManager().setPage(ref, store, Page.None);
        }

        try {
            store.addComponent(
                    ref,
                    Teleport.getComponentType(),
                    Teleport.createForPlayer(prepared.world(), toPlayerTransform(prepared.result().entrancePosition()))
            );
            updateStatus("Preview ready - entering " + prepared.world().getName(), false);
        } catch (Exception e) {
            removePreviewWorldIfLoaded(prepared.world().getName());
            String msg = extractErrorMessage(e);
            LOGGER.atSevere().log("[DungeonGen] Failed to enter preview world %s: %s",
                    prepared.world().getName(), msg);
            playerRef.sendMessage(Message.raw("[DungeonGen] Failed to enter preview world: " + msg).color("#FF5555"));
            updateStatus("Error - see chat", false);
        }
    }

    private void handlePreviewFailure(@Nonnull String worldName, @Nonnull Throwable throwable) {
        String msg = extractErrorMessage(throwable);
        playerRef.sendMessage(Message.raw(
                "[DungeonGen] Preview generation failed for '" + worldName + "': " + msg
        ).color("#FF5555"));
        LOGGER.atSevere().log("[DungeonGen] Preview generation failed for %s: %s", worldName, msg);
        updateStatus("Error - see chat", false);
    }

    private void sendGenerationSummary(@Nonnull GenerationResult result, @Nullable String previewWorldName) {
        String summary = String.format(
            "[DungeonGen] Done! %d rooms, %d corridors, %d blocks, %d spawners, %d merchants - gen %dms, asm %dms",
            result.rooms(), result.corridors(), result.totalBlocks(), result.spawners(),
            result.merchants(), result.generationTimeMs(), result.assemblyTimeMs()
        );
        String color = result.assemblyError() == null ? "#55FF55" : "#FFAA00";
        playerRef.sendMessage(Message.raw(summary).color(color));

        if (result.assemblyError() != null) {
            playerRef.sendMessage(Message.raw("[DungeonGen] Assembly error: " + result.assemblyError()).color("#FF5555"));
        }

        if (previewWorldName != null) {
            playerRef.sendMessage(Message.raw(
                    "[DungeonGen] Preview world ready: " + previewWorldName
            ).color("#55FFFF"));
        }

        updateStatus(String.format("Done - %d rooms, %dms", result.rooms(), result.generationTimeMs()), false);
    }

    @Nonnull
    private DungeonConfig toPreviewConfig(@Nonnull DungeonConfig config) {
        String baseWorldName = isBlank(config.worldName()) ? PREVIEW_WORLD_PREFIX : config.worldName().trim();
        String previewWorldName = baseWorldName + PREVIEW_WORLD_SEPARATOR + UUID.randomUUID().toString().substring(0, 8);
        return new DungeonConfig(
                config.seed(),
                config.preset(),
                previewWorldName,
                config.origin(),
                config.layout(),
                config.theme(),
                config.pacing(),
                true,
                config.floorLevel()
        );
    }

    private void removePreviewWorldIfLoaded(@Nonnull String worldName) {
        World loadedWorld = Universe.get().getWorld(worldName);
        if (loadedWorld == null) {
            return;
        }
        try {
            Universe.get().removeWorld(worldName);
        } catch (Exception e) {
            LOGGER.atWarning().log("[DungeonGen] Failed to remove preview world %s: %s", worldName, e.getMessage());
        }
    }

    @Nonnull
    private static Transform toPlayerTransform(@Nonnull Vec3i position) {
        return new Transform(position.x() + 0.5D, position.y(), position.z() + 0.5D);
    }

    private static long resolveWorldSeed(@Nullable String seed) {
        if (isBlank(seed)) {
            return UUID.randomUUID().getMostSignificantBits() ^ System.nanoTime();
        }
        try {
            return Long.parseLong(seed);
        } catch (NumberFormatException ignored) {
            return seed.hashCode();
        }
    }

    @Nonnull
    private static String extractErrorMessage(@Nonnull Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() != null ? current.getMessage() : current.toString();
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
        appendInt(sb, "width", d.width);
        appendInt(sb, "depth", d.depth);
        appendInt(sb, "height", d.height);
        appendInt(sb, "maxRooms", d.maxRooms);
        appendFloat(sb, "roomDensity", d.roomDensity);
        appendFloat(sb, "complexity", d.complexity);
        appendInt(sb, "minRoomSize", d.minRoomSize);
        appendInt(sb, "maxRoomSize", d.maxRoomSize);
        appendStr(sb, "roomShape", d.roomShape);
        appendFloat(sb, "irregularity", d.irregularity);
        appendInt(sb, "corridorWidth", d.corridorWidth);
        appendFloat(sb, "branchChance", d.branchChance);
        appendFloat(sb, "loopChance", d.loopChance);
        appendBool(sb, "windingCorridors", d.windingCorridors);
        appendFloat(sb, "windingFactor", d.windingFactor);
        appendFloat(sb, "pillarFreq", d.pillarFreq);
        appendFloat(sb, "waterFreq", d.waterFreq);
        appendFloat(sb, "lavaFreq", d.lavaFreq);
        appendFloat(sb, "trapDensity", d.trapDensity);
        appendFloat(sb, "secretWallChance", d.secretWallChance);
        appendBool(sb, "floorTraps", d.floorTraps);
        appendStr(sb, "entrancePlacement", d.entrancePlacement);
        appendFloat(sb, "exitDistance", d.exitDistance);
        appendFloat(sb, "enemyDensity", d.enemyDensity);
        appendInt(sb, "maxEnemiesPerRoom", d.maxEnemiesPerRoom);
        appendFloat(sb, "ambushChance", d.ambushChance);
        appendBool(sb, "bossRoom", d.bossRoom);
        appendFloat(sb, "erosion", d.erosion);
        appendBool(sb, "removeCeiling", d.removeCeiling);
        appendBool(sb, "flatFloor", d.flatFloor);
        appendBool(sb, "solidFill", d.solidFill);
        appendStr(sb, "palette", d.palette);
        appendFloat(sb, "decayFactor", d.decayFactor);
        appendFloat(sb, "overgrowthFactor", d.overgrowthFactor);
        appendFloat(sb, "floodingFactor", d.floodingFactor);
        appendFloat(sb, "breatheRoomFreq", d.breatheRoomFreq);
        // Last field - no trailing comma
        sb.append("  \"difficultyRamp\": ");
        sb.append(d.difficultyRamp != null ? d.difficultyRamp.toString() : "null");
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

    private static void appendFloat(@Nonnull StringBuilder sb, @Nonnull String key, @Nullable Float value) {
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

    private static float savedFloat(@Nonnull Map<String, Object> saved, @Nonnull String key,
                                    float fallback) {
        Object val = saved.get(key);
        if (val == null) return fallback;
        if (val instanceof Number n) return n.floatValue();
        try { return Float.parseFloat(val.toString()); } catch (NumberFormatException e) { return fallback; }
    }

    private static double floatOrDefault(@Nullable Float value, double fallback) {
        return value != null ? value.doubleValue() : fallback;
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
            floatOrDefault(d.roomDensity, ld.roomDensity()),
            intOrDefault(d.minRoomSize, ld.minRoomSize()),
            intOrDefault(d.maxRoomSize, ld.maxRoomSize()),
            intOrDefault(d.maxRooms, ld.maxRooms()),
            isBlank(d.roomShape) ? ld.roomShape() : d.roomShape,
            floatOrDefault(d.irregularity, ld.irregularity()),
            intOrDefault(d.corridorWidth, ld.corridorWidth()),
            floatOrDefault(d.branchChance, ld.branchChance()),
            floatOrDefault(d.loopChance, ld.loopChance()),
            d.windingCorridors != null ? d.windingCorridors : ld.windingCorridors(),
            floatOrDefault(d.windingFactor, ld.windingFactor()),
            floatOrDefault(d.pillarFreq, ld.pillarFrequency()),
            floatOrDefault(d.waterFreq, ld.waterFrequency()),
            floatOrDefault(d.lavaFreq, ld.lavaFrequency()),
            floatOrDefault(d.trapDensity, ld.trapDensity()),
            d.floorTraps != null ? d.floorTraps : ld.floorTraps(),
            floatOrDefault(d.secretWallChance, ld.secretWallChance()),
            ld.merchantSpawnChance(),
            isBlank(d.entrancePlacement) ? ld.entrancePlacement() : d.entrancePlacement,
            floatOrDefault(d.exitDistance, ld.exitDistance()),
            floatOrDefault(d.enemyDensity, ld.enemyDensity()),
            intOrDefault(d.maxEnemiesPerRoom, ld.maxEnemiesPerRoom()),
            d.bossRoom != null ? d.bossRoom : ld.bossRoom(),
            floatOrDefault(d.ambushChance, ld.ambushChance()),
            floatOrDefault(d.erosion, ld.erosion()),
            d.removeCeiling != null ? d.removeCeiling : ld.removeCeiling(),
            d.flatFloor != null ? d.flatFloor : ld.flatFloor(),
            d.solidFill != null ? d.solidFill : ld.solidFill(),
            floatOrDefault(d.complexity, ld.complexity())
        );

        ThemeConfig theme = new ThemeConfig(
            isBlank(d.palette) ? td.palette() : d.palette,
            floatOrDefault(d.decayFactor, td.decayFactor()),
            floatOrDefault(d.overgrowthFactor, td.overgrowthFactor()),
            floatOrDefault(d.floodingFactor, td.floodingFactor())
        );

        PacingConfig pacing = new PacingConfig(
            floatOrDefault(d.breatheRoomFreq, pd.breatheRoomFrequency()),
            floatOrDefault(d.difficultyRamp, pd.difficultyRamp())
        );

        boolean assemble = d.assemble != null ? d.assemble : dd.assemble();
        int floorLevel = intOrDefault(d.floorLevel, dd.floorLevel());

        return new DungeonConfig(seed, null, worldName, origin, layout, theme, pacing, assemble, floorLevel);
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
            .addField(new KeyedCodec<>("@SecretWallChance", Codec.FLOAT), (e, v) -> e.secretWallChance = v, e -> e.secretWallChance)
            .addField(new KeyedCodec<>("@FloorTraps", Codec.BOOLEAN), (e, v) -> e.floorTraps = v, e -> e.floorTraps)
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
            .addField(new KeyedCodec<>("@Palette", Codec.STRING), (e, v) -> e.palette = v, e -> e.palette)
            .addField(new KeyedCodec<>("@DecayFactor", Codec.FLOAT), (e, v) -> e.decayFactor = v, e -> e.decayFactor)
            .addField(new KeyedCodec<>("@OvergrowthFactor", Codec.FLOAT), (e, v) -> e.overgrowthFactor = v, e -> e.overgrowthFactor)
            .addField(new KeyedCodec<>("@FloodingFactor", Codec.FLOAT), (e, v) -> e.floodingFactor = v, e -> e.floodingFactor)
            // Pacing
            .addField(new KeyedCodec<>("@BreatheRoomFreq", Codec.FLOAT), (e, v) -> e.breatheRoomFreq = v, e -> e.breatheRoomFreq)
            .addField(new KeyedCodec<>("@DifficultyRamp", Codec.FLOAT), (e, v) -> e.difficultyRamp = v, e -> e.difficultyRamp)
            .build();

        // Action
        String generate;
        // General
        String seed;
        String worldName;
        Integer originX, originY, originZ;
        Integer floorLevel;
        Boolean assemble;
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
        Float trapDensity, secretWallChance;
        Boolean floorTraps;
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
        String palette;
        Float decayFactor, overgrowthFactor, floodingFactor;
        // Pacing
        Float breatheRoomFreq, difficultyRamp;
    }
}
