package com.duntale.zsquad;

import com.duntale.dungeongen.config.Vec3i;
import com.duntale.zsquad.camera.BlockOcclusionManager;
import com.duntale.zsquad.camera.ClickToMoveKnockbackSystem;
import com.duntale.zsquad.camera.ClickToMoveManager;
import com.duntale.zsquad.camera.ClickToMoveTickSystem;
import com.duntale.zsquad.camera.PlayerDeathCleanupSystem;
import com.duntale.zsquad.command.DGiveCommand;
import com.duntale.zsquad.command.DungeonCommand;
import com.duntale.zsquad.command.PartyCommand;
import com.duntale.zsquad.command.DListCommand;
import com.duntale.zsquad.command.DSpawnCommand;
import com.duntale.zsquad.command.GenerateCommand;
import com.duntale.zsquad.command.CameraCommand;
import com.duntale.zsquad.command.SpawnCommand;
import com.duntale.zsquad.command.WeaponCommand;
import com.duntale.zsquad.companion.CompanionCommand;
import com.duntale.zsquad.companion.CompanionComponent;
import com.duntale.zsquad.companion.CompanionDeathProtectionSystem;
import com.duntale.zsquad.companion.CompanionRepository;
import com.duntale.zsquad.companion.CompanionRespawnSystem;
import com.duntale.zsquad.companion.CompanionService;
import com.duntale.zsquad.companion.CompanionTrapImmunitySystem;
import com.duntale.dungeongen.generator.GenerationOrchestrator;
import com.duntale.dungeongen.util.BlockResolver;
import com.duntale.zsquad.economy.CurrencyDrop;
import com.duntale.zsquad.economy.GoldCommand;
import com.duntale.zsquad.economy.GoldPickupSystem;
import com.duntale.zsquad.economy.GoldRepository;
import com.duntale.zsquad.economy.GoldService;
import com.duntale.zsquad.economy.PlayerDeathPenaltySystem;
import com.duntale.zsquad.loot.LootEntry;
import com.duntale.zsquad.loot.LootEntry.GearType;
import com.duntale.zsquad.loot.LootTable;
import com.duntale.zsquad.loot.LootTableRegistry;
import com.duntale.zsquad.loot.NpcLootSystem;
import com.duntale.zsquad.db.DatabaseProvider;
import com.duntale.zsquad.merchant.CatalogGenerator;
import com.duntale.zsquad.merchant.MerchantCommand;
import com.duntale.zsquad.merchant.MerchantComponent;
import com.duntale.zsquad.merchant.MerchantNpcSpawner;
import com.duntale.zsquad.merchant.MerchantPriceRegistry;
import com.duntale.zsquad.merchant.MerchantService;
import com.duntale.zsquad.merchant.BuilderActionOpenDungeonMerchant;
import com.duntale.zsquad.merchant.MerchantTooltipProvider;
import com.duntale.zsquad.progression.AssetCatalog;
import com.duntale.zsquad.progression.CombatScalingComponent;
import com.duntale.zsquad.progression.CombatScalingSystem;
import com.duntale.zsquad.progression.GearScalingTooltipProvider;
import com.duntale.zsquad.progression.LeveledNpcSpawner;
import com.duntale.zsquad.progression.ProgressionRepository;
import com.duntale.zsquad.progression.ProgressionService;
import com.duntale.zsquad.companion.CompanionSpawner;
import com.duntale.zsquad.dungeon.DungeonInstanceRepository;
import com.duntale.zsquad.dungeon.DungeonInstance;
import com.duntale.zsquad.dungeon.DungeonInstanceService;
import com.duntale.zsquad.dungeon.DungeonMembershipRepository;
import com.duntale.zsquad.dungeon.FloorConfigRepository;
import com.duntale.zsquad.dungeon.FloorConfigService;
import com.duntale.zsquad.dungeon.PartyService;
import com.duntale.zsquad.rpg.RpgDamageScalingSystem;
import com.duntale.zsquad.rpg.RpgProfile;
import com.duntale.zsquad.rpg.RpgRepository;
import com.duntale.zsquad.rpg.RpgService;
import com.duntale.zsquad.rpg.RpgStatCommand;
import com.duntale.zsquad.rpg.StatAssignCommand;
import com.duntale.zsquad.spawner.SpawnerComponent;
import com.duntale.zsquad.spawner.SpawnerFactory;
import com.duntale.zsquad.spawner.SpawnerTickSystem;
import com.duntale.zsquad.ui.ZSquadScoreboard;
import com.duntale.zsquad.ui.ZSquadScoreboardData;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.event.events.player.RemovedPlayerFromWorldEvent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.util.EventTitleUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.AllWorldsLoadedEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import org.herolias.tooltips.api.DynamicTooltipsApiProvider;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class ZSquadPlugin extends JavaPlugin {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static ZSquadPlugin instance;

    private ClickToMoveManager clickToMoveManager;
    private BlockOcclusionManager blockOcclusionManager;

    // Progression system
    private AssetCatalog assetCatalog;
    private ComponentType<EntityStore, CombatScalingComponent> combatScalingComponentType;
    private LeveledNpcSpawner leveledNpcSpawner;
    private CompanionSpawner companionSpawner;

    // Loot system
    private LootTableRegistry lootTableRegistry;

    // RPG system
    private DatabaseProvider databaseProvider;
    private RpgService rpgService;
    private GoldService goldService;
    private ProgressionService progressionService;

    // Spawner system
    private ComponentType<EntityStore, SpawnerComponent> spawnerComponentType;
    private SpawnerFactory spawnerFactory;

    // Merchant system
    private ComponentType<EntityStore, MerchantComponent> merchantComponentType;
    private MerchantNpcSpawner merchantNpcSpawner;
    private MerchantPriceRegistry merchantPriceRegistry;
    private CatalogGenerator catalogGenerator;
    private MerchantService merchantService;

    // Companion system
    private ComponentType<EntityStore, CompanionComponent> companionComponentType;
    private CompanionRepository companionRepository;
    private CompanionService companionService;

    // Dungeon instance flow
    private PartyService partyService;
    private FloorConfigService floorConfigService;
    private DungeonInstanceService dungeonInstanceService;
    private final AtomicBoolean dungeonStartupRecoveryLoaded = new AtomicBoolean();

    // HUD scoreboards per player
    private final Map<UUID, ZSquadScoreboard> scoreboards = new ConcurrentHashMap<>();
    private final Set<UUID> entryMenuPending = ConcurrentHashMap.newKeySet();

    // Dungeon generation
    private GenerationOrchestrator dungeonOrchestrator;

    public ZSquadPlugin(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
    }

    public static ZSquadPlugin get() {
        return instance;
    }

    /**
     * Returns the click-to-move manager.
     *
     * @return the click-to-move manager
     */
    @Nonnull
    public ClickToMoveManager getClickToMoveManager() {
        return clickToMoveManager;
    }

    /**
     * Returns the block occlusion manager.
     *
     * @return the block occlusion manager
     */
    @Nonnull
    public BlockOcclusionManager getBlockOcclusionManager() {
        return blockOcclusionManager;
    }

    /**
     * Returns the asset catalog.
     *
     * @return the asset catalog
     */
    @Nonnull
    public AssetCatalog getAssetCatalog() {
        return assetCatalog;
    }

    /**
     * Returns the registered component type for {@link CombatScalingComponent}.
     *
     * @return the combat scaling component type
     * @since 1.5.0
     */
    @Nonnull
    public ComponentType<EntityStore, CombatScalingComponent> getCombatScalingComponentType() {
        return combatScalingComponentType;
    }

    /**
     * Returns the leveled NPC spawner.
     *
     * @return the leveled NPC spawner
     */
    @Nonnull
    public LeveledNpcSpawner getLeveledNpcSpawner() {
        return leveledNpcSpawner;
    }

    /**
     * Returns the loot table registry.
     *
     * @return the loot table registry
     */
    @Nonnull
    public LootTableRegistry getLootTableRegistry() {
        return lootTableRegistry;
    }

    /**
     * Returns the registered component type for {@link SpawnerComponent}.
     *
     * @return the spawner component type
     * @since 1.1.0
     */
    @Nonnull
    public ComponentType<EntityStore, SpawnerComponent> getSpawnerComponentType() {
        return spawnerComponentType;
    }

    /**
     * Returns the spawner factory for creating spawner entities from blueprints.
     *
     * @return the spawner factory
     * @since 1.1.0
     */
    @Nonnull
    public SpawnerFactory getSpawnerFactory() {
        return spawnerFactory;
    }

    /**
     * Returns the registered component type for {@link MerchantComponent}.
     *
     * @return the merchant component type
     * @since 1.3.0
     */
    @Nonnull
    public ComponentType<EntityStore, MerchantComponent> getMerchantComponentType() {
        return merchantComponentType;
    }

    /**
     * Returns the merchant NPC spawner for creating merchant entities from blueprints.
     *
     * @return the merchant NPC spawner
     * @since 1.3.0
     */
    @Nonnull
    public MerchantNpcSpawner getMerchantNpcSpawner() {
        return merchantNpcSpawner;
    }

    /**
     * Returns the merchant service for buy/sell transactions.
     *
     * @return the merchant service
     * @since 1.3.0
     */
    @Nonnull
    public MerchantService getMerchantService() {
        return merchantService;
    }

    /**
     * Returns the catalog generator for merchant inventories.
     *
     * @return the catalog generator
     */
    @Nonnull
    public CatalogGenerator getCatalogGenerator() {
        return catalogGenerator;
    }

    /**
     * Returns the registered component type for {@link CompanionComponent}.
     *
     * @return the companion component type
     * @since 1.4.0
     */
    @Nonnull
    public ComponentType<EntityStore, CompanionComponent> getCompanionComponentType() {
        return companionComponentType;
    }

    /**
     * Returns the transient party service used to assemble pre-run dungeon rosters.
     *
     * @return the party service
     * @since 1.6.0
     */
    @Nonnull
    public PartyService getPartyService() {
        return partyService;
    }

    /**
     * Returns the dungeon instance service used for per-world dungeon runs.
     *
     * @return the dungeon instance service
     * @since 1.6.0
     */
    @Nonnull
    public DungeonInstanceService getDungeonInstanceService() {
        return dungeonInstanceService;
    }

    /**
     * Returns the dungeon generation orchestrator.
     *
     * @return the generation orchestrator
     * @since 1.2.0
     */
    @Nonnull
    public GenerationOrchestrator getDungeonOrchestrator() {
        return dungeonOrchestrator;
    }

    @Override
    protected void setup() {
        LOGGER.atInfo().log("ZSquad Plugin Setting Up...");

        // Initialize managers
        this.clickToMoveManager = new ClickToMoveManager();
        this.blockOcclusionManager = new BlockOcclusionManager();

        LOGGER.atInfo().log("Data directory: %s", getDataDirectory().toAbsolutePath());

        // ── RPG System ───────────────────────────────────────────────
        this.databaseProvider = new DatabaseProvider();
        this.partyService = new PartyService();
        DungeonInstanceRepository dungeonInstanceRepository;
        DungeonMembershipRepository dungeonMembershipRepository;
        FloorConfigRepository floorConfigRepository;
        try {
            Path dbPath = getDataDirectory().resolve("zsquad.db");
            databaseProvider.initialize(dbPath);

            RpgRepository rpgRepo = new RpgRepository(databaseProvider);
            rpgRepo.initialize();
            this.rpgService = new RpgService(rpgRepo);

            GoldRepository goldRepo = new GoldRepository(databaseProvider);
            goldRepo.initialize();
            this.goldService = new GoldService(goldRepo);

            ProgressionRepository progressionRepo = new ProgressionRepository(databaseProvider);
            progressionRepo.initialize();
            this.progressionService = new ProgressionService(progressionRepo);

            CompanionRepository companionRepo = new CompanionRepository(databaseProvider);
            companionRepo.initialize();
            this.companionRepository = companionRepo;

            dungeonInstanceRepository = new DungeonInstanceRepository(databaseProvider);
            dungeonInstanceRepository.initialize();

            dungeonMembershipRepository = new DungeonMembershipRepository(databaseProvider);
            dungeonMembershipRepository.initialize();

            floorConfigRepository = new FloorConfigRepository(databaseProvider);
            floorConfigRepository.initialize();
        } catch (SQLException e) {
            LOGGER.atSevere().log("Failed to initialize RPG database: %s", e.getMessage());
            this.rpgService = new RpgService(new RpgRepository(databaseProvider));
            this.goldService = new GoldService(new GoldRepository(databaseProvider));
            this.progressionService = new ProgressionService(new ProgressionRepository(databaseProvider));
            this.companionRepository = new CompanionRepository(databaseProvider);
            dungeonInstanceRepository = new DungeonInstanceRepository(databaseProvider);
            dungeonMembershipRepository = new DungeonMembershipRepository(databaseProvider);
            floorConfigRepository = new FloorConfigRepository(databaseProvider);
        }
        this.floorConfigService = new FloorConfigService(floorConfigRepository);
        this.floorConfigService.loadOnStartup();
        this.dungeonInstanceService = new DungeonInstanceService(
                databaseProvider,
                dungeonInstanceRepository,
                dungeonMembershipRepository,
                partyService,
                floorConfigService
        );
        this.clickToMoveManager.setRpgService(rpgService);

        // ── Progression System (runtime asset scan — no DB) ──────────
        this.assetCatalog = new AssetCatalog();
        // AssetCatalog.initialize() deferred to start() — asset stores load after all plugins setup()

        // ── Loot System ──────────────────────────────────────────────
        this.lootTableRegistry = new LootTableRegistry();
        registerLootTables();

        // ── Merchant System ──────────────────────────────────────────
        this.merchantPriceRegistry = new MerchantPriceRegistry();
        // MerchantPriceRegistry.initialize() deferred to start() — depends on AssetCatalog
        this.catalogGenerator = new CatalogGenerator(merchantPriceRegistry);
        this.merchantService = new MerchantService(merchantPriceRegistry, goldService);

        // ── ECS Component Registration ───────────────────────────────
        CurrencyDrop.setComponentType(
                this.getEntityStoreRegistry().registerComponent(CurrencyDrop.class, () -> CurrencyDrop.INSTANCE));

        // ── CombatScaling ECS Component ─────────────────────────────
        this.combatScalingComponentType = this.getEntityStoreRegistry().registerComponent(
                CombatScalingComponent.class, "CombatScalingComponent", CombatScalingComponent.CODEC);
        this.leveledNpcSpawner = new LeveledNpcSpawner(combatScalingComponentType);
        this.companionSpawner = new CompanionSpawner(combatScalingComponentType);

        // Register ECS systems
        this.getEntityStoreRegistry().registerSystem(new ClickToMoveTickSystem(this.clickToMoveManager));
        this.getEntityStoreRegistry().registerSystem(new CombatScalingSystem(combatScalingComponentType));
        this.getEntityStoreRegistry().registerSystem(new ClickToMoveKnockbackSystem(this.clickToMoveManager));
        this.getEntityStoreRegistry().registerSystem(new NpcLootSystem(lootTableRegistry, rpgService, progressionService));
        this.getEntityStoreRegistry().registerSystem(new GoldPickupSystem(goldService));
        this.getEntityStoreRegistry().registerSystem(new RpgDamageScalingSystem(rpgService));
        this.getEntityStoreRegistry().registerSystem(new PlayerDeathPenaltySystem(goldService));
        this.getEntityStoreRegistry().registerSystem(
            new PlayerDeathCleanupSystem(this.clickToMoveManager, this.blockOcclusionManager));

        // ── Spawner System ───────────────────────────────────────────
        this.spawnerComponentType = this.getEntityStoreRegistry().registerComponent(SpawnerComponent.class, "SpawnerComponent", SpawnerComponent.CODEC);
        this.spawnerFactory = new SpawnerFactory();
        this.getEntityStoreRegistry().registerSystem(new SpawnerTickSystem(leveledNpcSpawner));

        // ── Merchant NPC System ──────────────────────────────────────
        this.merchantComponentType = this.getEntityStoreRegistry().registerComponent(MerchantComponent.class, "MerchantComponent", MerchantComponent.CODEC);
        this.merchantNpcSpawner = new MerchantNpcSpawner();
        NPCPlugin.get().registerCoreComponentType("OpenDungeonMerchant",
                BuilderActionOpenDungeonMerchant::new);

        // ── Companion System ─────────────────────────────────────────
        this.companionComponentType = this.getEntityStoreRegistry().registerComponent(
                CompanionComponent.class, "CompanionComponent", CompanionComponent.CODEC);
        this.getEntityStoreRegistry().registerSystem(new CompanionDeathProtectionSystem(companionComponentType));
        this.getEntityStoreRegistry().registerSystem(new CompanionTrapImmunitySystem(companionComponentType));

        this.companionService = new CompanionService(
                companionSpawner, progressionService,
                companionComponentType, companionRepository);
        this.getEntityStoreRegistry().registerSystem(new CompanionRespawnSystem(companionService));

        // -- Dungeon Generation ----------------------------------------
        // Deferred to start() — DungeonSettingsConfig asset store not available during setup()

        // Command registration
        this.getCommandRegistry().registerCommand(new SpawnCommand());
        this.getCommandRegistry().registerCommand(new CameraCommand());
        this.getCommandRegistry().registerCommand(new WeaponCommand());
        this.getCommandRegistry().registerCommand(new DSpawnCommand(leveledNpcSpawner, assetCatalog));
        this.getCommandRegistry().registerCommand(new DListCommand(assetCatalog));
        this.getCommandRegistry().registerCommand(new DGiveCommand(assetCatalog));
        this.getCommandRegistry().registerCommand(new GenerateCommand());
        this.getCommandRegistry().registerCommand(new GoldCommand(goldService));
        this.getCommandRegistry().registerCommand(new RpgStatCommand(rpgService));
        this.getCommandRegistry().registerCommand(new MerchantCommand(merchantService, catalogGenerator));
        this.getCommandRegistry().registerCommand(new StatAssignCommand(rpgService));
        this.getCommandRegistry().registerCommand(new CompanionCommand(companionService));
        this.getCommandRegistry().registerCommand(new DungeonCommand(dungeonInstanceService, floorConfigService));
        this.getCommandRegistry().registerCommand(new PartyCommand(partyService));

        // ── Player join/leave events ─────────────────────────────────
        this.getEventRegistry().registerGlobal(AllWorldsLoadedEvent.class, this::onAllWorldsLoaded);
        this.getEventRegistry().register(PlayerConnectEvent.class, this::onPlayerConnect);
        this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, this::onPlayerReady);
        this.getEventRegistry().register(PlayerDisconnectEvent.class, this::onPlayerDisconnect);
        this.getEventRegistry().registerGlobal(RemovedPlayerFromWorldEvent.class, this::onPlayerRemovedFromWorld);

        // ── Level-up listener: grant stat points + update scoreboard ──
        this.progressionService.setLevelUpListener((playerId, newLevel) -> {
            rpgService.grantStatPoints(playerId, RpgService.POINTS_PER_LEVEL);
            updateScoreboard(playerId);
            sendLevelUpTitle(playerId, newLevel);
            LOGGER.atInfo().log("Player %s reached level %d — granted %d stat points",
                    playerId, newLevel, RpgService.POINTS_PER_LEVEL);
        });

        // ── XP grant listener: update scoreboard on every XP gain ─────
        this.progressionService.setXPGrantListener((playerId, amount, result) -> {
            if (!result.leveledUp()) {
                // Level-up already triggers updateScoreboard via the level-up listener
                updateScoreboard(playerId);
            }
        });

        // ── Gold change listener: update scoreboard on gold mutations ─
        this.goldService.setChangeListener((playerId, newBalance) ->
                updateScoreboard(playerId));

        // ── Stat change listener: update scoreboard on stat changes ───
        this.rpgService.setStatChangeListener((playerId, stat, newValue) ->
                updateScoreboard(playerId));

        // ── DynamicTooltipsLib integration (optional dependency) ──
        registerTooltipProvider();
    }

    @Override
    protected void start() {
        // Asset stores are fully loaded after all plugins setup() — safe to scan now
        this.assetCatalog.initialize();
        this.merchantPriceRegistry.initialize(assetCatalog);

        // Initialize dungeon orchestrator here — asset stores are available after all plugins setup()
        this.dungeonOrchestrator = new GenerationOrchestrator(new BlockResolver());

        // Normal startup recovery waits for AllWorldsLoadedEvent so interrupted worlds can be removed.
        // If the universe is already ready (for example during a late plugin start), recover immediately.
        Universe universe = Universe.get();
        if (universe != null) {
            CompletableFuture<Void> universeReady = universe.getUniverseReady();
            if (universeReady != null && universeReady.isDone()) {
                loadDungeonInstancesAfterWorldsLoaded();
            }
        }

        LOGGER.atInfo().log("ZSquad Plugin Started!");
    }

    @Override
    protected void shutdown() {
        if (clickToMoveManager != null) {
            clickToMoveManager.shutdown();
        }
        if (blockOcclusionManager != null) {
            // Retrieve world from any online player; if no players remain the
            // daemon thread has nothing to restore, so shutdown without world.
            PlayerRef anyPlayer = Universe.get()
                    .getPlayers().stream().findFirst().orElse(null);
            if (anyPlayer != null) {
                Ref<EntityStore> ref = anyPlayer.getReference();
                if (ref != null && ref.isValid()) {
                    World world = ref.getStore().getExternalData().getWorld();
                    blockOcclusionManager.shutdown(world);
                }
            }
        }
        if (dungeonOrchestrator != null) {
            dungeonOrchestrator.shutdown();
        }

        if (databaseProvider != null) {
            databaseProvider.close();
        }
    }

    // ── Player lifecycle events ──────────────────────────────────────

    private void onAllWorldsLoaded(@Nonnull AllWorldsLoadedEvent ignored) {
        loadDungeonInstancesAfterWorldsLoaded();
    }

    private void loadDungeonInstancesAfterWorldsLoaded() {
        if (!dungeonStartupRecoveryLoaded.compareAndSet(false, true)) {
            return;
        }
        dungeonInstanceService.loadOnStartup();
    }

    private void onPlayerConnect(@Nonnull PlayerConnectEvent event) {
        UUID uuid = event.getPlayerRef().getUuid();
        rpgService.onPlayerJoin(uuid);
        progressionService.onPlayerJoin(uuid);

        World sharedWorld = resolveSharedWorld();
        String currentWorldName = event.getWorld() != null ? event.getWorld().getName() : null;
        String sharedWorldName = sharedWorld != null ? sharedWorld.getName() : null;
        DungeonJoinRouting.JoinRoutingDecision joinRouting =
                DungeonJoinRouting.resolve(currentWorldName, sharedWorldName);

        if (sharedWorld != null
                && joinRouting.targetWorldName() != null
                && joinRouting.targetWorldName().equalsIgnoreCase(sharedWorld.getName())) {
            event.setWorld(sharedWorld);
        }
        if (joinRouting.openEntryMenu()) {
            entryMenuPending.add(uuid);
        }

        LOGGER.atFine().log("Pre-loaded RPG profile + ensured progression for %s", uuid);
    }

    private void onPlayerReady(@Nonnull PlayerReadyEvent event) {
        Player player = event.getPlayer();
        Ref<EntityStore> ref = event.getPlayerRef();
        Store<EntityStore> store = ref.getStore();
        PlayerRef playerRef = (PlayerRef) store.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null) return;
        World world = player.getWorld();
        if (world == null) return;

        UUID uuid = playerRef.getUuid();
        ZSquadScoreboard scoreboard = new ZSquadScoreboard(playerRef);
        player.getHudManager().setCustomHud(playerRef, scoreboard);
        scoreboard.updateData(buildScoreboardData(uuid));
        scoreboards.put(uuid, scoreboard);

        // WorldConfig only seeds the player's mode when they do not already have one.
        // Players entering a dungeon from a Creative world keep Creative unless we
        // explicitly reapply the dungeon world's configured mode on entry.
        DungeonInstance dungeonInstance = resolveDungeonInstance(world.getName());
        if (dungeonInstance != null && player.getGameMode() != world.getWorldConfig().getGameMode()) {
            Player.setGameMode(ref, world.getWorldConfig().getGameMode(), store);
        }

        // Auto-spawn companion using stored preference.
        // In dungeon worlds, use the authoritative entrance position from instance
        // metadata instead of the player's TransformComponent. This avoids spawning
        // the companion at a stale or incorrect Y-level after a cross-world teleport.
        Vector3d companionSpawnOrigin = dungeonInstance != null
                ? new Vector3d(
                        dungeonInstance.entrancePosition().x() + 0.5,
                        dungeonInstance.entrancePosition().y(),
                        dungeonInstance.entrancePosition().z() + 0.5
                )
                : null;
        companionService.spawn(store, ref, uuid, companionSpawnOrigin);

        if (isSharedWorld(world) || dungeonInstance != null) {
            clickToMoveManager.enableWithCamera(uuid, store, ref, playerRef);
        }

        if (entryMenuPending.remove(uuid) && isSharedWorld(world) && player.getPageManager().getCustomPage() == null) {
            player.getPageManager().openCustomPage(ref, store, new DungeonEntryPage(playerRef));
        }
    }

    private void onPlayerRemovedFromWorld(@Nonnull RemovedPlayerFromWorldEvent event) {
        PlayerRef playerRef = (PlayerRef) event.getHolder().getComponent(PlayerRef.getComponentType());
        if (playerRef == null) return;

        UUID uuid = playerRef.getUuid();
        companionService.dismiss(uuid);
    }

    private void onPlayerDisconnect(@Nonnull PlayerDisconnectEvent event) {
        UUID uuid = event.getPlayerRef().getUuid();
        partyService.onPlayerDisconnect(uuid);
        rpgService.onPlayerLeave(uuid);
        progressionService.onPlayerLeave(uuid);

        Ref<EntityStore> ref = event.getPlayerRef().getReference();
        if (ref != null && ref.isValid()) {
            blockOcclusionManager.disable(uuid, ref.getStore().getExternalData().getWorld());
        } else {
            blockOcclusionManager.disable(uuid);
        }

        clickToMoveManager.disable(uuid);
        merchantService.closeMerchant(uuid);
        scoreboards.remove(uuid);
        entryMenuPending.remove(uuid);
        LOGGER.atFine().log("Evicted RPG + progression data for %s", uuid);
    }

    void handleEntryContinue(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull PlayerRef playerRef
    ) {
        UUID playerId = playerRef.getUuid();
        DungeonInstanceService.ContinueRoute continueRoute;
        try {
            continueRoute = dungeonInstanceService.resolveContinueRoute(playerId);
        } catch (SQLException e) {
            LOGGER.atWarning()
                    .withCause(e)
                    .log("Failed to resolve Continue route for player %s", playerId);
            playerRef.sendMessage(Message.raw("Unable to resolve your dungeon route right now.").color("#FF5555"));
            return;
        }

        if (!continueRoute.routesToInstance()) {
            if (continueRoute.isPending()) {
                playerRef.sendMessage(switch (continueRoute.instance().state()) {
                    case CREATING ->
                            Message.raw("Your dungeon is still being prepared. Try Continue again shortly.")
                                    .color("#FFEE55");
                    case TRANSITIONING ->
                            Message.raw("Your dungeon is moving to the next floor. Try Continue again shortly.")
                                    .color("#FFEE55");
                    case ACTIVE, ENDED ->
                            Message.raw("Your dungeon is not ready to join right now.")
                                    .color("#FFEE55");
                });
                return;
            }
            routeToSharedWorld(ref, store, playerRef, Message.raw("Entering village.").color("#55FF55"));
            return;
        }

        World targetWorld = Universe.get().getWorld(continueRoute.instance().worldName());
        if (targetWorld == null) {
            LOGGER.atWarning().log(
                    "Continue route for player %s failed because world %s is not loaded; routing to village",
                    playerId,
                    continueRoute.instance().worldName()
            );
            playerRef.sendMessage(Message.raw("Your dungeon world is no longer available. Routing to village.").color("#FF5555"));
            routeToSharedWorld(ref, store, playerRef, Message.raw("Entering village.").color("#55FF55"));
            return;
        }

        closeCustomPage(ref, store);
        store.addComponent(
                ref,
                Teleport.getComponentType(),
                Teleport.createForPlayer(targetWorld, toPlayerTransform(continueRoute.instance().entrancePosition()))
        );
        playerRef.sendMessage(Message.raw("Continuing your dungeon run...").color("#FFD700"));
    }

    void handleEntryVillage(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull PlayerRef playerRef
    ) {
        routeToSharedWorld(ref, store, playerRef, Message.raw("Entering village.").color("#55FF55"));
    }

    private void routeToSharedWorld(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull PlayerRef playerRef,
            @Nonnull Message statusMessage
    ) {
        World sharedWorld = resolveSharedWorld();
        if (sharedWorld == null) {
            LOGGER.atWarning().log("Unable to route player %s to village because no shared world is configured",
                    playerRef.getUuid());
            playerRef.sendMessage(Message.raw("No shared world is currently available.").color("#FF5555"));
            return;
        }

        closeCustomPage(ref, store);

        World currentWorld = store.getExternalData().getWorld();
        if (isSameWorld(currentWorld, sharedWorld)) {
            playerRef.sendMessage(statusMessage);
            return;
        }

        store.addComponent(
                ref,
                Teleport.getComponentType(),
                Teleport.createForPlayer(sharedWorld, resolveSpawnTransform(sharedWorld, playerRef))
        );
        playerRef.sendMessage(statusMessage);
    }

    @Nullable
    private DungeonInstance resolveDungeonInstance(@Nonnull String worldName) {
        try {
            return dungeonInstanceService.getInstanceByWorld(worldName);
        } catch (SQLException e) {
            LOGGER.atWarning()
                    .withCause(e)
                    .log("Failed to resolve dungeon world context for %s", worldName);
            return null;
        }
    }

    private boolean isSharedWorld(@Nonnull World world) {
        return isSameWorld(world, resolveSharedWorld());
    }

    @Nullable
    private World resolveSharedWorld() {
        Universe universe = Universe.get();
        return universe != null ? universe.getDefaultWorld() : null;
    }

    private void closeCustomPage(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }
        player.getPageManager().setPage(ref, store, Page.None);
    }

    @Nonnull
    private static Transform resolveSpawnTransform(@Nonnull World world, @Nonnull PlayerRef playerRef) {
        return world.getWorldConfig().getSpawnProvider().getSpawnPoint(world, playerRef.getUuid());
    }

    @Nonnull
    private static Transform toPlayerTransform(@Nonnull Vec3i position) {
        return new Transform(position.x() + 0.5D, position.y(), position.z() + 0.5D);
    }

    private static boolean isSameWorld(@Nullable World first, @Nullable World second) {
        return first != null
                && second != null
                && first.getName().equalsIgnoreCase(second.getName());
    }

    /**
     * Populates the {@link LootTableRegistry} with drop tables for each NPC role.
     *
     * <p>Each table defines weighted entries that may be level-gated. Entries can be:
     * <ul>
     *   <li>{@link LootEntry.Simple} — regular items (potions, materials).</li>
     *   <li>{@link LootEntry.Leveled} — weapons/armor with gear level + variance metadata.</li>
     * </ul>
     */
    private void registerLootTables() {
        // ── Trork mobs (Lv.5–15 zone) ───────────────────────────────
        lootTableRegistry.register("Trork_Warrior", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Axe_Crude", GearType.WEAPON, 5, 8, 3.0),
                new LootEntry.Leveled("Weapon_Spear_Crude", GearType.WEAPON, 5, 8, 3.0),
                new LootEntry.Leveled("Armor_Wood_Head", GearType.ARMOR, 5, 8, 2.0),
                new LootEntry.Leveled("Armor_Wood_Chest", GearType.ARMOR, 5, 8, 1.0),
                new LootEntry.Simple("Gold_Coin", 1, 3, 5.0)
        ), 1, 0.35));

        lootTableRegistry.register("Trork_Brawler", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Club_Crude", GearType.WEAPON, 5, 10, 3.0),
                new LootEntry.Leveled("Weapon_Mace_Crude", GearType.WEAPON, 7, 10, 2.0),
                new LootEntry.Leveled("Armor_Wood_Hands", GearType.ARMOR, 5, 8, 1.5),
                new LootEntry.Leveled("Armor_Wood_Legs", GearType.ARMOR, 5, 8, 1.5),
                new LootEntry.Simple("Gold_Coin", 1, 3, 5.0)
        ), 1, 0.35));

        lootTableRegistry.register("Trork_Hunter", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Spear_Crude", GearType.WEAPON, 5, 10, 3.0),
                new LootEntry.Leveled("Weapon_Daggers_Crude", GearType.WEAPON, 6, 10, 2.0),
                new LootEntry.Leveled("Armor_Leather_Soft_Chest", GearType.ARMOR, 8, 12, 1.5, 8, null),
                new LootEntry.Simple("Gold_Coin", 1, 3, 5.0)
        ), 1, 0.35));

        lootTableRegistry.register("Trork_Guard", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Longsword_Crude", GearType.WEAPON, 5, 10, 3.0),
                new LootEntry.Leveled("Weapon_Axe_Copper", GearType.WEAPON, 10, 12, 1.5, 10, null),
                new LootEntry.Leveled("Armor_Copper_Chest", GearType.ARMOR, 10, 12, 1.0, 10, null),
                new LootEntry.Leveled("Armor_Copper_Head", GearType.ARMOR, 10, 12, 1.0, 10, null),
                new LootEntry.Simple("Gold_Coin", 2, 4, 5.0)
        ), 1, 0.40));

        // ── Skeleton mobs (Lv.15–30 zone) ───────────────────────────
        lootTableRegistry.register("Skeleton_Soldier", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Sword_Iron", GearType.WEAPON, 18, 22, 3.0),
                new LootEntry.Leveled("Weapon_Longsword_Iron", GearType.WEAPON, 18, 22, 2.0),
                new LootEntry.Leveled("Armor_Iron_Chest", GearType.ARMOR, 18, 22, 1.5),
                new LootEntry.Leveled("Armor_Iron_Head", GearType.ARMOR, 18, 22, 1.5),
                new LootEntry.Simple("Gold_Coin", 3, 8, 5.0)
        ), 1, 0.40));

        lootTableRegistry.register("Skeleton_Fighter", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Axe_Iron", GearType.WEAPON, 18, 22, 3.0),
                new LootEntry.Leveled("Weapon_Mace_Iron", GearType.WEAPON, 18, 22, 2.0),
                new LootEntry.Leveled("Armor_Iron_Hands", GearType.ARMOR, 18, 22, 1.5),
                new LootEntry.Leveled("Armor_Iron_Legs", GearType.ARMOR, 18, 22, 1.5),
                new LootEntry.Simple("Gold_Coin", 3, 8, 5.0)
        ), 1, 0.40));

        lootTableRegistry.register("Skeleton_Knight", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Sword_Bronze", GearType.WEAPON, 23, 27, 2.5),
                new LootEntry.Leveled("Weapon_Longsword_Praetorian", GearType.WEAPON, 23, 27, 1.5),
                new LootEntry.Leveled("Armor_Bronze_Chest", GearType.ARMOR, 23, 27, 1.5),
                new LootEntry.Leveled("Armor_Bronze_Head", GearType.ARMOR, 23, 27, 1.5),
                new LootEntry.Leveled("Armor_Bronze_Ornate_Chest", GearType.ARMOR, 26, 30, 0.5, 25, null),
                new LootEntry.Simple("Gold_Coin", 4, 10, 5.0)
        ), 1, 0.45));

        lootTableRegistry.register("Skeleton_Archer", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Spear_Iron", GearType.WEAPON, 18, 22, 3.0),
                new LootEntry.Leveled("Weapon_Daggers_Iron", GearType.WEAPON, 18, 22, 2.0),
                new LootEntry.Leveled("Armor_Leather_Light_Chest", GearType.ARMOR, 15, 20, 1.5),
                new LootEntry.Leveled("Armor_Leather_Light_Head", GearType.ARMOR, 15, 20, 1.5),
                new LootEntry.Simple("Gold_Coin", 3, 8, 5.0)
        ), 1, 0.40));

        // ── Goblin mobs (Lv.10–25 zone) ─────────────────────────────
        lootTableRegistry.register("Goblin_Scrapper", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Club_Copper", GearType.WEAPON, 10, 15, 3.0),
                new LootEntry.Leveled("Weapon_Sword_Scrap", GearType.WEAPON, 13, 17, 2.0, 12, null),
                new LootEntry.Leveled("Armor_Copper_Hands", GearType.ARMOR, 10, 15, 1.5),
                new LootEntry.Simple("Gold_Coin", 2, 5, 5.0)
        ), 1, 0.40));

        lootTableRegistry.register("Goblin_Scavenger", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Mace_Scrap", GearType.WEAPON, 13, 17, 3.0),
                new LootEntry.Leveled("Weapon_Club_Scrap", GearType.WEAPON, 13, 17, 2.0),
                new LootEntry.Leveled("Armor_Copper_Legs", GearType.ARMOR, 10, 15, 1.5),
                new LootEntry.Simple("Gold_Coin", 2, 5, 5.0)
        ), 1, 0.40));

        // ── Zombie mobs (Lv.25–35 zone) ─────────────────────────────
        lootTableRegistry.register("Zombie", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Sword_Bone", GearType.WEAPON, 23, 27, 2.0),
                new LootEntry.Leveled("Weapon_Axe_Bone", GearType.WEAPON, 23, 27, 2.0),
                new LootEntry.Leveled("Weapon_Sword_Doomed", GearType.WEAPON, 28, 32, 1.0, 28, null),
                new LootEntry.Leveled("Armor_Thorium_Chest", GearType.ARMOR, 28, 32, 0.8, 28, null),
                new LootEntry.Simple("Gold_Coin", 5, 12, 5.0)
        ), 1, 0.50));

        // ── Outlander mobs — Arcane dungeon floor (Lv.1–60) ─────────
        lootTableRegistry.register("Outlander_Stalker", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Daggers_Crude", GearType.WEAPON, 5, 10, 3.0),
                new LootEntry.Leveled("Weapon_Spear_Crude", GearType.WEAPON, 5, 10, 2.0),
                new LootEntry.Leveled("Armor_Leather_Soft_Chest", GearType.ARMOR, 5, 10, 1.5),
                new LootEntry.Leveled("Armor_Leather_Soft_Head", GearType.ARMOR, 5, 10, 1.5),
                new LootEntry.Simple("Gold_Coin", 1, 4, 5.0)
        ), 1, 0.35));

        lootTableRegistry.register("Outlander_Berserker", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Axe_Copper", GearType.WEAPON, 8, 15, 3.0),
                new LootEntry.Leveled("Weapon_Club_Crude", GearType.WEAPON, 5, 12, 2.0),
                new LootEntry.Leveled("Armor_Copper_Chest", GearType.ARMOR, 8, 15, 1.5),
                new LootEntry.Leveled("Armor_Copper_Head", GearType.ARMOR, 8, 15, 1.0),
                new LootEntry.Simple("Gold_Coin", 2, 6, 5.0)
        ), 1, 0.40));

        lootTableRegistry.register("Outlander_Sorcerer", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Longsword_Iron", GearType.WEAPON, 15, 25, 2.5),
                new LootEntry.Leveled("Weapon_Sword_Iron", GearType.WEAPON, 15, 25, 2.0),
                new LootEntry.Leveled("Armor_Iron_Chest", GearType.ARMOR, 15, 25, 1.5),
                new LootEntry.Leveled("Armor_Iron_Head", GearType.ARMOR, 15, 25, 1.0),
                new LootEntry.Simple("Gold_Coin", 4, 10, 5.0)
        ), 1, 0.40));

        lootTableRegistry.register("Outlander_Marauder", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Sword_Bronze", GearType.WEAPON, 20, 35, 2.5),
                new LootEntry.Leveled("Weapon_Longsword_Praetorian", GearType.WEAPON, 25, 35, 1.5),
                new LootEntry.Leveled("Armor_Bronze_Chest", GearType.ARMOR, 20, 35, 1.5),
                new LootEntry.Leveled("Armor_Bronze_Head", GearType.ARMOR, 20, 35, 1.0),
                new LootEntry.Leveled("Armor_Bronze_Ornate_Chest", GearType.ARMOR, 28, 35, 0.5, 28, null),
                new LootEntry.Simple("Gold_Coin", 5, 15, 5.0)
        ), 1, 0.45));

        lootTableRegistry.register("Outlander_Brute", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Longsword_Praetorian", GearType.WEAPON, 25, 40, 2.0),
                new LootEntry.Leveled("Weapon_Sword_Doomed", GearType.WEAPON, 30, 40, 1.5),
                new LootEntry.Leveled("Armor_Bronze_Ornate_Chest", GearType.ARMOR, 28, 40, 1.5),
                new LootEntry.Leveled("Armor_Thorium_Chest", GearType.ARMOR, 30, 40, 1.0, 30, null),
                new LootEntry.Simple("Gold_Coin", 10, 25, 5.0)
        ), 2, 0.80));

        // ── Crypt extras (Skeleton_Mage + Ghoul boss) ────────────────────
        lootTableRegistry.register("Skeleton_Mage", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Longsword_Iron", GearType.WEAPON, 18, 25, 2.5),
                new LootEntry.Leveled("Weapon_Sword_Iron", GearType.WEAPON, 18, 25, 2.0),
                new LootEntry.Leveled("Armor_Cloth_Linen_Head", GearType.ARMOR, 14, 22, 0.7),
                new LootEntry.Leveled("Armor_Cloth_Cotton_Chest", GearType.ARMOR, 22, 32, 0.4, 22, null),
                new LootEntry.Simple("Gold_Coin", 4, 10, 5.0)
        ), 1, 0.45));

        lootTableRegistry.register("Ghoul", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Sword_Bone", GearType.WEAPON, 23, 32, 2.0),
                new LootEntry.Leveled("Weapon_Axe_Bone", GearType.WEAPON, 23, 30, 1.5),
                new LootEntry.Leveled("Weapon_Longsword_Void", GearType.WEAPON, 28, 38, 1.0, 28, null),
                new LootEntry.Leveled("Armor_Iron_Head", GearType.ARMOR, 18, 28, 0.5),
                new LootEntry.Leveled("Armor_Bronze_Chest", GearType.ARMOR, 25, 36, 0.4, 25, null),
                new LootEntry.Simple("Gold_Coin", 12, 30, 5.0)
        ), 1, 0.90));

        // ── Hive theme (Scarak) ──────────────────────────────────────────
        // Louse: swarm mob — tiny gold only, no armour to speak of
        lootTableRegistry.register("Scarak_Louse", new LootTable(List.of(
                new LootEntry.Simple("Gold_Coin", 1, 2, 5.0)
        ), 1, 0.20));

        lootTableRegistry.register("Scarak_Fighter", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Axe_Crude", GearType.WEAPON, 5, 12, 2.5),
                new LootEntry.Leveled("Weapon_Club_Crude", GearType.WEAPON, 5, 12, 2.0),
                new LootEntry.Leveled("Armor_Leather_Soft_Head", GearType.ARMOR, 5, 12, 0.5),
                new LootEntry.Simple("Gold_Coin", 1, 3, 5.0)
        ), 1, 0.35));

        lootTableRegistry.register("Scarak_Seeker", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Daggers_Crude", GearType.WEAPON, 6, 14, 2.5),
                new LootEntry.Leveled("Weapon_Spear_Crude", GearType.WEAPON, 5, 14, 2.0),
                new LootEntry.Leveled("Armor_Leather_Soft_Chest", GearType.ARMOR, 8, 16, 0.8),
                new LootEntry.Leveled("Armor_Leather_Light_Head", GearType.ARMOR, 14, 22, 0.5, 14, null),
                new LootEntry.Simple("Gold_Coin", 2, 5, 5.0)
        ), 1, 0.40));

        lootTableRegistry.register("Scarak_Defender", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Axe_Copper", GearType.WEAPON, 10, 20, 2.5),
                new LootEntry.Leveled("Weapon_Mace_Crude", GearType.WEAPON, 8, 18, 2.0),
                new LootEntry.Leveled("Armor_Copper_Chest", GearType.ARMOR, 10, 20, 0.8),
                new LootEntry.Leveled("Armor_Iron_Legs", GearType.ARMOR, 18, 28, 0.5, 18, null),
                new LootEntry.Simple("Gold_Coin", 2, 6, 5.0)
        ), 1, 0.42));

        lootTableRegistry.register("Scarak_Fighter_Royal_Guard", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Axe_Copper", GearType.WEAPON, 10, 22, 2.5),
                new LootEntry.Leveled("Weapon_Longsword_Copper", GearType.WEAPON, 10, 22, 2.0),
                new LootEntry.Leveled("Armor_Iron_Chest", GearType.ARMOR, 18, 28, 1.0),
                new LootEntry.Leveled("Armor_Bronze_Hands", GearType.ARMOR, 24, 34, 0.5, 24, null),
                new LootEntry.Simple("Gold_Coin", 4, 10, 5.0)
        ), 1, 0.45));

        lootTableRegistry.register("Scarak_Broodmother", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Axe_Iron", GearType.WEAPON, 20, 32, 2.0),
                new LootEntry.Leveled("Weapon_Mace_Iron", GearType.WEAPON, 18, 30, 1.5),
                new LootEntry.Leveled("Weapon_Longsword_Praetorian", GearType.WEAPON, 24, 36, 1.0, 24, null),
                new LootEntry.Leveled("Armor_Iron_Chest", GearType.ARMOR, 20, 28, 0.5),
                new LootEntry.Leveled("Armor_Bronze_Chest", GearType.ARMOR, 25, 36, 0.4, 25, null),
                new LootEntry.Simple("Gold_Coin", 15, 35, 5.0)
        ), 1, 0.90));

        // ── Mine theme (Goblin extras) ───────────────────────────────────
        lootTableRegistry.register("Goblin_Miner", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Club_Crude", GearType.WEAPON, 5, 12, 2.5),
                new LootEntry.Leveled("Weapon_Axe_Crude", GearType.WEAPON, 5, 12, 2.0),
                new LootEntry.Leveled("Armor_Wood_Head", GearType.ARMOR, 5, 12, 0.5),
                new LootEntry.Simple("Gold_Coin", 1, 4, 5.0)
        ), 1, 0.35));

        lootTableRegistry.register("Goblin_Lobber", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Spear_Crude", GearType.WEAPON, 5, 16, 2.5),
                new LootEntry.Leveled("Weapon_Spear_Copper", GearType.WEAPON, 10, 22, 2.0),
                new LootEntry.Leveled("Armor_Leather_Light_Head", GearType.ARMOR, 14, 22, 0.8),
                new LootEntry.Leveled("Armor_Iron_Hands", GearType.ARMOR, 18, 28, 0.5, 18, null),
                new LootEntry.Simple("Gold_Coin", 2, 6, 5.0)
        ), 1, 0.40));

        lootTableRegistry.register("Goblin_Ogre", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Club_Iron", GearType.WEAPON, 18, 28, 2.5),
                new LootEntry.Leveled("Weapon_Mace_Iron", GearType.WEAPON, 18, 26, 2.0),
                new LootEntry.Leveled("Armor_Iron_Chest", GearType.ARMOR, 18, 28, 1.0),
                new LootEntry.Leveled("Armor_Bronze_Legs", GearType.ARMOR, 24, 34, 0.5, 24, null),
                new LootEntry.Simple("Gold_Coin", 4, 12, 5.0)
        ), 1, 0.45));

        lootTableRegistry.register("Goblin_Duke", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Sword_Bronze", GearType.WEAPON, 24, 36, 2.0),
                new LootEntry.Leveled("Weapon_Longsword_Praetorian", GearType.WEAPON, 24, 36, 1.5),
                new LootEntry.Leveled("Weapon_Axe_Thorium", GearType.WEAPON, 28, 42, 1.0, 28, null),
                new LootEntry.Leveled("Armor_Bronze_Chest", GearType.ARMOR, 25, 36, 0.5),
                new LootEntry.Leveled("Armor_Thorium_Legs", GearType.ARMOR, 28, 40, 0.4, 28, null),
                new LootEntry.Simple("Gold_Coin", 15, 35, 5.0)
        ), 1, 0.90));

        // ── Mushroom theme (Trork extras) ────────────────────────────────
        lootTableRegistry.register("Trork_Shaman", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Spear_Leaf", GearType.WEAPON, 14, 22, 2.5),
                new LootEntry.Leveled("Weapon_Club_Crude", GearType.WEAPON, 5, 18, 2.0),
                new LootEntry.Leveled("Armor_Cloth_Linen_Head", GearType.ARMOR, 14, 22, 0.8),
                new LootEntry.Leveled("Armor_Cloth_Cotton_Chest", GearType.ARMOR, 22, 32, 0.5, 22, null),
                new LootEntry.Simple("Gold_Coin", 2, 6, 5.0)
        ), 1, 0.40));

        lootTableRegistry.register("Trork_Mauler", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Axe_Iron", GearType.WEAPON, 18, 28, 2.5),
                new LootEntry.Leveled("Weapon_Club_Iron", GearType.WEAPON, 18, 26, 2.0),
                new LootEntry.Leveled("Armor_Iron_Chest", GearType.ARMOR, 18, 28, 1.0),
                new LootEntry.Leveled("Armor_Bronze_Hands", GearType.ARMOR, 24, 34, 0.5, 24, null),
                new LootEntry.Simple("Gold_Coin", 4, 12, 5.0)
        ), 1, 0.45));

        lootTableRegistry.register("Trork_Chieftain", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Axe_Stone_Trork", GearType.WEAPON, 22, 34, 2.0),
                new LootEntry.Leveled("Weapon_Club_Stone_Trork", GearType.WEAPON, 22, 34, 1.5),
                new LootEntry.Leveled("Weapon_Axe_Iron", GearType.WEAPON, 18, 30, 1.0, 18, null),
                new LootEntry.Leveled("Armor_Trork_Chest", GearType.ARMOR, 24, 34, 0.5),
                new LootEntry.Leveled("Armor_Bronze_Chest", GearType.ARMOR, 25, 36, 0.4, 25, null),
                new LootEntry.Simple("Gold_Coin", 15, 35, 5.0)
        ), 1, 0.90));

        // ── Temple_Dark theme (Undead/Shadow) ────────────────────────────
        lootTableRegistry.register("Wraith_Lantern", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Sword_Crude", GearType.WEAPON, 4, 12, 2.5),
                new LootEntry.Leveled("Weapon_Daggers_Crude", GearType.WEAPON, 6, 12, 2.0),
                new LootEntry.Leveled("Armor_Cloth_Wool_Head", GearType.ARMOR, 8, 14, 0.5),
                new LootEntry.Simple("Gold_Coin", 1, 3, 5.0)
        ), 1, 0.33));

        lootTableRegistry.register("Shadow_Knight", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Sword_Iron", GearType.WEAPON, 18, 26, 2.5),
                new LootEntry.Leveled("Weapon_Daggers_Iron", GearType.WEAPON, 18, 24, 2.0),
                new LootEntry.Leveled("Armor_Iron_Head", GearType.ARMOR, 18, 26, 0.8),
                new LootEntry.Leveled("Armor_Iron_Chest", GearType.ARMOR, 18, 26, 0.5, 18, null),
                new LootEntry.Simple("Gold_Coin", 3, 8, 5.0)
        ), 1, 0.42));

        lootTableRegistry.register("Wraith", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Sword_Bone", GearType.WEAPON, 22, 32, 2.5),
                new LootEntry.Leveled("Weapon_Daggers_Bone", GearType.WEAPON, 22, 30, 2.0),
                new LootEntry.Leveled("Armor_Cloth_Linen_Head", GearType.ARMOR, 14, 24, 0.8),
                new LootEntry.Leveled("Armor_Cloth_Cotton_Chest", GearType.ARMOR, 22, 32, 0.5, 22, null),
                new LootEntry.Simple("Gold_Coin", 2, 7, 5.0)
        ), 1, 0.40));

        lootTableRegistry.register("Risen_Knight", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Sword_Bronze", GearType.WEAPON, 24, 34, 2.5),
                new LootEntry.Leveled("Weapon_Longsword_Praetorian", GearType.WEAPON, 24, 34, 2.0),
                new LootEntry.Leveled("Armor_Bronze_Head", GearType.ARMOR, 24, 34, 1.0),
                new LootEntry.Leveled("Armor_Bronze_Ornate_Head", GearType.ARMOR, 28, 40, 0.5, 28, null),
                new LootEntry.Simple("Gold_Coin", 5, 14, 5.0)
        ), 1, 0.45));

        lootTableRegistry.register("Zombie_Aberrant", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Sword_Doomed", GearType.WEAPON, 28, 40, 2.0),
                new LootEntry.Leveled("Weapon_Axe_Doomed", GearType.WEAPON, 28, 40, 1.5),
                new LootEntry.Leveled("Weapon_Club_Zombie_Arm", GearType.WEAPON, 28, 40, 1.0, 28, null),
                new LootEntry.Leveled("Armor_Thorium_Head", GearType.ARMOR, 28, 40, 0.5),
                new LootEntry.Leveled("Armor_Steel_Ancient_Chest", GearType.ARMOR, 28, 40, 0.4, 28, null),
                new LootEntry.Simple("Gold_Coin", 15, 35, 5.0)
        ), 1, 0.90));

        // ── Volcanic theme ───────────────────────────────────────────────
        lootTableRegistry.register("Feran_Sharptooth", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Axe_Crude", GearType.WEAPON, 5, 12, 2.5),
                new LootEntry.Leveled("Weapon_Club_Crude", GearType.WEAPON, 5, 12, 2.0),
                new LootEntry.Leveled("Armor_Leather_Soft_Chest", GearType.ARMOR, 8, 14, 0.5),
                new LootEntry.Simple("Gold_Coin", 1, 3, 5.0)
        ), 1, 0.33));

        lootTableRegistry.register("Feran_Longtooth", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Axe_Copper", GearType.WEAPON, 10, 20, 2.5),
                new LootEntry.Leveled("Weapon_Spear_Copper", GearType.WEAPON, 10, 18, 2.0),
                new LootEntry.Leveled("Armor_Leather_Soft_Chest", GearType.ARMOR, 8, 18, 0.8),
                new LootEntry.Leveled("Armor_Copper_Head", GearType.ARMOR, 10, 20, 0.5, 10, null),
                new LootEntry.Simple("Gold_Coin", 2, 5, 5.0)
        ), 1, 0.38));

        lootTableRegistry.register("Spirit_Ember", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Sword_Iron", GearType.WEAPON, 18, 28, 2.5),
                new LootEntry.Leveled("Weapon_Spear_Iron", GearType.WEAPON, 18, 26, 2.0),
                new LootEntry.Leveled("Armor_Cloth_Linen_Head", GearType.ARMOR, 14, 24, 0.8),
                new LootEntry.Leveled("Armor_Cloth_Silk_Chest", GearType.ARMOR, 34, 44, 0.4, 34, null),
                new LootEntry.Simple("Gold_Coin", 3, 9, 5.0)
        ), 1, 0.42));

        lootTableRegistry.register("Golem_Crystal_Flame", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Axe_Iron", GearType.WEAPON, 18, 30, 2.5),
                new LootEntry.Leveled("Weapon_Mace_Iron", GearType.WEAPON, 18, 28, 2.0),
                new LootEntry.Leveled("Armor_Iron_Chest", GearType.ARMOR, 18, 30, 1.0),
                new LootEntry.Leveled("Armor_Cobalt_Head", GearType.ARMOR, 34, 44, 0.5, 34, null),
                new LootEntry.Simple("Gold_Coin", 5, 15, 5.0)
        ), 1, 0.47));

        lootTableRegistry.register("Golem_Firesteel", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Axe_Thorium", GearType.WEAPON, 28, 40, 2.0),
                new LootEntry.Leveled("Weapon_Mace_Thorium", GearType.WEAPON, 28, 40, 1.5),
                new LootEntry.Leveled("Weapon_Axe_Cobalt", GearType.WEAPON, 34, 46, 1.0, 34, null),
                new LootEntry.Leveled("Armor_Cobalt_Chest", GearType.ARMOR, 34, 46, 0.5),
                new LootEntry.Leveled("Armor_Adamantite_Head", GearType.ARMOR, 38, 50, 0.4, 38, null),
                new LootEntry.Simple("Gold_Coin", 15, 40, 5.0)
        ), 1, 0.90));

        LOGGER.atInfo().log("Registered %d custom loot tables", lootTableRegistry.size());
    }

    /**
     * Registers the gear scaling tooltip provider with DynamicTooltipsLib if available.
     * Guarded so that the plugin works even without the optional dependency.
     */
    private void registerTooltipProvider() {
        try {
            var api = DynamicTooltipsApiProvider.get();
            if (api != null) {
                api.registerProvider(
                        new GearScalingTooltipProvider(assetCatalog));
                api.registerProvider(
                        new MerchantTooltipProvider(merchantPriceRegistry));
                LOGGER.atInfo().log("Registered tooltip providers with DynamicTooltipsLib");
            } else {
                LOGGER.atInfo().log("DynamicTooltipsLib not available — tooltip overrides disabled");
            }
        } catch (NoClassDefFoundError e) {
            LOGGER.atInfo().log("DynamicTooltipsLib not loaded — tooltip overrides disabled");
        }
    }

    // ── Scoreboard Helpers ───────────────────────────────────────────

    /**
     * Builds a {@link ZSquadScoreboardData} snapshot from the current state of all services.
     *
     * @param playerId the player's UUID
     * @return the scoreboard data snapshot
     */
    @Nonnull
    private ZSquadScoreboardData buildScoreboardData(@Nonnull UUID playerId) {
        RpgProfile profile = rpgService.getProfile(playerId);
        long gold = goldService.getBalance(playerId);
        int level = progressionService.getLevel(playerId);
        long xp = progressionService.getXP(playerId);
        long xpMax = progressionService.getXPForLevel(level + 1);

        return ZSquadScoreboardData.builder()
                .gold(gold)
                .level(level)
                .xp(xp)
                .xpMax(xpMax)
                .stats(profile)
                .build();
    }

    /**
     * Updates the scoreboard HUD for the given player with fresh data from all services.
     *
     * <p>Safe to call when the player is not online — the call is a no-op if no
     * scoreboard is tracked.
     *
     * @param playerId the player's UUID
     */
    public void updateScoreboard(@Nonnull UUID playerId) {
        ZSquadScoreboard scoreboard = scoreboards.get(playerId);
        if (scoreboard == null) {
            return;
        }
        scoreboard.updateData(buildScoreboardData(playerId));
    }

    /**
     * Returns the active scoreboard HUD for the given player, or {@code null} if offline.
     *
     * @param playerId the player's UUID
     * @return the scoreboard, or {@code null}
     */
    @javax.annotation.Nullable
    public ZSquadScoreboard getScoreboard(@Nonnull UUID playerId) {
        return scoreboards.get(playerId);
    }

    /**
     * Sends a level-up event title to the player if they are currently online.
     * Silently skips the notification if the player is offline or unavailable.
     *
     * @param playerId the player's UUID
     * @param newLevel the level the player just reached
     */
    private void sendLevelUpTitle(@Nonnull UUID playerId, int newLevel) {
        PlayerRef playerRef = Universe.get().getPlayer(playerId);
        if (playerRef == null) {
            return;
        }
        Message primary = Message.raw("Level Up!");
        Message secondary = Message.raw(
                "Reached Level " + newLevel + " (+" + RpgService.POINTS_PER_LEVEL + " Stat Points)");
        EventTitleUtil.showEventTitleToPlayer(playerRef, primary, secondary, false);
    }
}
