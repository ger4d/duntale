package com.duntale.zsquad;

import com.duntale.dungeongen.config.Vec3i;
import com.duntale.zsquad.camera.BlockOcclusionManager;
import com.duntale.zsquad.camera.ClickToMoveKnockbackSystem;
import com.duntale.zsquad.camera.ClickToMoveManager;
import com.duntale.zsquad.camera.ClickToMoveTickSystem;
import com.duntale.zsquad.camera.PlayerDeathCleanupSystem;
import com.duntale.zsquad.config.asset.CustomizeCharacterConfigAsset;
import com.duntale.zsquad.command.DGiveCommand;
import com.duntale.zsquad.command.DLootCommand;
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
import com.duntale.zsquad.loot.ChestLootService;
import com.duntale.zsquad.loot.LootRollService;
import com.duntale.zsquad.loot.LootTableRegistry;
import com.duntale.zsquad.loot.NpcLootSystem;
import com.duntale.zsquad.loot.config.asset.LootTableConfig;
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
import com.duntale.zsquad.dungeon.config.asset.FloorConfigDefaultAsset;
import com.duntale.zsquad.death.DungeonDeathContext;
import com.duntale.zsquad.death.DungeonDeathPage;
import com.duntale.zsquad.death.DungeonDeathScreenSystem;
import com.duntale.zsquad.death.DungeonRespawnService;
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
import com.duntale.zsquad.volume.DungeonInstancePortalTriggerService;
import com.hypixel.hytale.builtin.triggervolumes.event.TriggerVolumeEvent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.event.events.player.RemovedPlayerFromWorldEvent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.PendingTeleport;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

public class ZSquadPlugin extends JavaPlugin {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String DUNGEON_INSTANCE_PORTAL_VOLUME_ID = "dungeon_instance_portal";
    private static final int DUNGEON_START_FLOOR = 1;
    private static final int RESPAWN_SETTLE_MAX_ATTEMPTS = 50;
    private static final long RESPAWN_SETTLE_RETRY_DELAY_MS = 100L;
    private static final String COLOR_GREEN = "#55FF55";
    private static final String COLOR_RED = "#FF5555";
    private static final String COLOR_AQUA = "#55FFFF";
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
    private LootRollService lootRollService;
    private ChestLootService chestLootService;

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
    private CustomizeCharacterService customizeCharacterService;
    private PlayerEntryService playerEntryService;

    // Dungeon instance flow
    private PartyService partyService;
    private FloorConfigService floorConfigService;
    private DungeonInstanceService dungeonInstanceService;
    private DungeonRespawnService dungeonRespawnService;
    private DungeonInstancePortalTriggerService dungeonInstancePortalTriggerService;
    private final AtomicBoolean dungeonStartupRecoveryLoaded = new AtomicBoolean();
    private final AtomicBoolean dungeonPortalTriggerRegistered = new AtomicBoolean();

    // HUD scoreboards per player
    private final Map<UUID, ZSquadScoreboard> scoreboards = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerEntryService.EntryDestination> pendingEntryDestinations = new ConcurrentHashMap<>();

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
     * Returns the shared chest loot service.
     *
     * @return the chest loot service
     */
    @Nonnull
    public ChestLootService getChestLootService() {
        return chestLootService;
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

        AssetRegistry.register(FloorConfigDefaultAsset.assetStoreBuilder().build());
        AssetRegistry.register(CustomizeCharacterConfigAsset.assetStoreBuilder().build());
        AssetRegistry.register(LootTableConfig.assetStoreBuilder().build());

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
        this.dungeonRespawnService = new DungeonRespawnService(dungeonInstanceService, goldService);
        this.dungeonInstancePortalTriggerService =
            new DungeonInstancePortalTriggerService(DUNGEON_INSTANCE_PORTAL_VOLUME_ID);
        this.clickToMoveManager.setRpgService(rpgService);

        // ── Progression System (runtime asset scan — no DB) ──────────
        this.assetCatalog = new AssetCatalog();
        // AssetCatalog.initialize() deferred to start() — asset stores load after all plugins setup()

        // ── Loot System ──────────────────────────────────────────────
        this.lootTableRegistry = new LootTableRegistry();
        this.lootRollService = new LootRollService(lootTableRegistry);
        this.chestLootService = new ChestLootService(lootTableRegistry);

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
        this.getEntityStoreRegistry().registerSystem(new NpcLootSystem(lootRollService, rpgService, progressionService));
        this.getEntityStoreRegistry().registerSystem(new GoldPickupSystem(goldService));
        this.getEntityStoreRegistry().registerSystem(new RpgDamageScalingSystem(rpgService));
        this.getEntityStoreRegistry().registerSystem(new DungeonDeathScreenSystem(dungeonRespawnService));
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
        this.customizeCharacterService = new CustomizeCharacterService(
            clickToMoveManager,
            companionService,
            companionSpawner,
            progressionService
        );
        this.playerEntryService = new PlayerEntryService(companionService, dungeonInstanceService);
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
        this.getCommandRegistry().registerCommand(new DLootCommand(lootRollService));
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
                registerDungeonInstancePortalTrigger();
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
        registerDungeonInstancePortalTrigger();
    }

    private void loadDungeonInstancesAfterWorldsLoaded() {
        if (!dungeonStartupRecoveryLoaded.compareAndSet(false, true)) {
            return;
        }
        dungeonInstanceService.loadOnStartup();
    }

    private void registerDungeonInstancePortalTrigger() {
        World sharedWorld = resolveSharedWorld();
        if (sharedWorld == null) {
            LOGGER.atWarning().log("Unable to register dungeon portal trigger because no shared world is configured");
            return;
        }

        if (!dungeonPortalTriggerRegistered.compareAndSet(false, true)) {
            return;
        }

        this.getEventRegistry().register(
                TriggerVolumeEvent.class,
                sharedWorld.getName(),
                this::onDungeonInstancePortalTrigger
        );
        LOGGER.atInfo().log(
                "Registered dungeon portal trigger %s for world %s",
                DUNGEON_INSTANCE_PORTAL_VOLUME_ID,
                sharedWorld.getName()
        );
    }

    private void onDungeonInstancePortalTrigger(@Nonnull TriggerVolumeEvent event) {
        if (!dungeonInstancePortalTriggerService.matches(event)) {
            return;
        }

        Ref<EntityStore> ref = event.getEntityRef();
        if (!ref.isValid()) {
            return;
        }

        Store<EntityStore> store = ref.getStore();
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null || player.getPageManager().getCustomPage() != null) {
            return;
        }

        PlayerRef playerRef = (PlayerRef) store.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null) {
            return;
        }

        DungeonInstance activeInstance;
        try {
            activeInstance = dungeonInstanceService.getActiveInstance(playerRef.getUuid());
        } catch (SQLException e) {
            LOGGER.atWarning()
                    .withCause(e)
                    .log("Failed to resolve dungeon portal state for player %s", playerRef.getUuid());
            playerRef.sendMessage(Message.raw("Unable to open the dungeon portal right now.").color(COLOR_RED));
            return;
        }

        DungeonInstancePortalPage.PortalMode mode = activeInstance == null
                ? DungeonInstancePortalPage.PortalMode.NO_INSTANCE
                : DungeonInstancePortalPage.PortalMode.EXISTING_INSTANCE;
        player.getPageManager().openCustomPage(ref, store, new DungeonInstancePortalPage(playerRef, mode, activeInstance));
    }

    private void onPlayerConnect(@Nonnull PlayerConnectEvent event) {
        UUID uuid = event.getPlayerRef().getUuid();
        rpgService.onPlayerJoin(uuid);
        progressionService.onPlayerJoin(uuid);

        World sharedWorld = resolveSharedWorld();
        String currentWorldName = event.getWorld() != null ? event.getWorld().getName() : null;
        String sharedWorldName = sharedWorld != null ? sharedWorld.getName() : null;
        PlayerEntryService.EntryDecision entryDecision =
                playerEntryService.resolve(uuid, currentWorldName, sharedWorldName);

        if (sharedWorld != null
                && entryDecision.targetWorldName() != null
                && entryDecision.targetWorldName().equalsIgnoreCase(sharedWorld.getName())) {
            event.setWorld(sharedWorld);
        }

        if (entryDecision.destination() == PlayerEntryService.EntryDestination.VILLAGE) {
            pendingEntryDestinations.remove(uuid);
        } else {
            pendingEntryDestinations.put(uuid, entryDecision.destination());
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
        player.getHudManager().addCustomHud(playerRef, scoreboard);
        scoreboard.updateData(buildScoreboardData(uuid));
        scoreboards.put(uuid, scoreboard);

        // WorldConfig only seeds the player's mode when they do not already have one.
        // Players entering a dungeon from a Creative world keep Creative unless we
        // explicitly reapply the dungeon world's configured mode on entry.
        DungeonInstance dungeonInstance = resolveDungeonInstance(world.getName());
        if (dungeonInstance != null && player.getGameMode() != world.getWorldConfig().getGameMode()) {
            Player.setGameMode(ref, world.getWorldConfig().getGameMode(), store);
        }

        PlayerEntryService.EntryDestination destination = pendingEntryDestinations.remove(uuid);
        if (destination == PlayerEntryService.EntryDestination.CUSTOMIZE_CHARACTER && isSharedWorld(world)) {
            customizeCharacterService.start(ref, store, playerRef);
            if (player.getPageManager().getCustomPage() == null) {
                player.getPageManager().openCustomPage(ref, store, new CustomizeCharacterPage(playerRef));
            }
            return;
        }

        // Auto-spawn companion using stored preference.
        // In dungeon worlds, use the authoritative entrance position from instance
        // metadata instead of the player's TransformComponent. This avoids spawning
        // the companion at a stale or incorrect Y-level after a cross-world teleport.
        Transform sharedWorldSpawn = null;
        if (dungeonInstance == null && isSharedWorld(world)) {
            sharedWorldSpawn = resolveSpawnTransform(world, ref, store);
            store.addComponent(
                ref,
                Teleport.getComponentType(),
                Teleport.createForPlayer(world, sharedWorldSpawn)
            );
        }

        Vector3d companionSpawnOrigin = dungeonInstance != null
                ? new Vector3d(
                        dungeonInstance.entrancePosition().x() + 0.5,
                        dungeonInstance.entrancePosition().y(),
                        dungeonInstance.entrancePosition().z() + 0.5
                )
            : sharedWorldSpawn != null
            ? new Vector3d(
                sharedWorldSpawn.getPosition().x,
                sharedWorldSpawn.getPosition().y,
                sharedWorldSpawn.getPosition().z
            )
                : null;
        companionService.spawn(store, ref, uuid, companionSpawnOrigin);

        if (dungeonInstance != null) {
            clickToMoveManager.enableWithCamera(uuid, store, ref, playerRef);
        } else if (isSharedWorld(world)) {
            restoreBuiltInControls(ref, store, playerRef);
        }

        if (destination == PlayerEntryService.EntryDestination.DUNGEON_ENTRY
                && isSharedWorld(world)
                && player.getPageManager().getCustomPage() == null) {
            player.getPageManager().openCustomPage(ref, store, new DungeonEntryPage(playerRef));
        }
    }

    private void onPlayerRemovedFromWorld(@Nonnull RemovedPlayerFromWorldEvent event) {
        PlayerRef playerRef = (PlayerRef) event.getHolder().getComponent(PlayerRef.getComponentType());
        if (playerRef == null) return;

        UUID uuid = playerRef.getUuid();
        pendingEntryDestinations.remove(uuid);
        customizeCharacterService.cleanup(uuid, event.getWorld().getEntityStore().getStore());
        companionService.dismissFromWorld(event.getWorld().getEntityStore().getStore(), uuid);
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
        pendingEntryDestinations.remove(uuid);
        customizeCharacterService.cleanup(uuid);
        LOGGER.atFine().log("Evicted RPG + progression data for %s", uuid);
    }

    void handleCustomizeCharacterConfirm(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull PlayerRef playerRef,
            @Nullable String roleName,
            @Nullable String companionName
    ) {
        if (customizeCharacterService == null) {
            playerRef.sendMessage(Message.raw("Character setup is currently unavailable.").color("#FF5555"));
            return;
        }

        customizeCharacterService.complete(ref, store, playerRef, roleName, companionName);
    }

    void handleCustomizeCharacterPreviewName(
            @Nonnull PlayerRef playerRef,
            @Nonnull Store<EntityStore> store,
            @Nullable String companionName
    ) {
        if (customizeCharacterService == null) {
            return;
        }

        customizeCharacterService.updatePreviewName(playerRef.getUuid(), store, companionName);
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

    void handlePortalEnter(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull PlayerRef playerRef
    ) {
        closeCustomPage(ref, store);
        startDungeonInstanceForPortal(playerRef);
    }

    void handlePortalContinue(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull PlayerRef playerRef
    ) {
        handleEntryContinue(ref, store, playerRef);
    }

    void handlePortalNewDungeon(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull PlayerRef playerRef
    ) {
        closeCustomPage(ref, store);

        UUID playerId = playerRef.getUuid();
        DungeonInstance activeInstance;
        try {
            activeInstance = dungeonInstanceService.getActiveInstance(playerId);
        } catch (SQLException e) {
            LOGGER.atWarning()
                    .withCause(e)
                    .log("Failed to resolve current dungeon before starting a new portal run for player %s", playerId);
            playerRef.sendMessage(Message.raw("Unable to resolve your current dungeon run right now.").color(COLOR_RED));
            return;
        }

        if (activeInstance == null) {
            startDungeonInstanceForPortal(playerRef);
            return;
        }

        try {
            dungeonInstanceService.forceEndInstance(activeInstance.instanceId())
                    .thenRun(() -> startDungeonInstanceForPortal(playerRef))
                    .exceptionally(throwable -> {
                        playerRef.sendMessage(
                                Message.raw("Force-end failed: " + describeFailure(throwable)).color(COLOR_RED)
                        );
                        return null;
                    });
        } catch (SQLException | IllegalArgumentException | IllegalStateException e) {
            playerRef.sendMessage(Message.raw("Force-end failed: " + describeFailure(e)).color(COLOR_RED));
        }
    }

    void handlePortalCancel(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        closeCustomPage(ref, store);
    }

    /**
     * Handles the paid current-floor respawn action from the dungeon death page.
     *
     * @param ref the player's current entity reference
     * @param store the player's current entity store
     * @param playerRef the dead player's player reference
     */
    public void handleDungeonRespawnCurrent(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull PlayerRef playerRef
    ) {
        DungeonDeathContext context = resolveLiveDungeonDeathContext(ref, store, playerRef);
        if (context == null || !ensureDungeonDeathComponent(ref, store, playerRef)) {
            return;
        }

        UUID playerId = playerRef.getUuid();
        long cost = context.currentFloorCost();
        if (!dungeonRespawnService.chargeGold(playerId, cost)) {
            sendInsufficientGold(playerRef, cost, context.balance());
            reopenDungeonDeathPage(ref, store, playerRef);
            return;
        }

        DeathComponent.respawn(store, ref)
                .thenCompose(unused -> runOnPlayerWorld(playerRef, (currentRef, currentStore) -> {
                    closeCustomPage(currentRef, currentStore);
                    playerRef.sendMessage(
                            Message.raw("Respawned on Floor " + context.instance().floorLevel() + " for ")
                                    .color(COLOR_GREEN)
                                    .insert(Message.raw(formatGold(cost)).color("#FFD700").bold(true))
                                    .insert(Message.raw(" gold. Balance: "
                                            + formatGold(goldService.getBalance(playerId))).color(COLOR_GREEN))
                    );
                }))
                .exceptionally(throwable -> {
                    dungeonRespawnService.refundGold(playerId, cost);
                    playerRef.sendMessage(
                            Message.raw("Respawn failed: " + describeFailure(throwable)).color(COLOR_RED)
                    );
                    runOnPlayerWorld(playerRef, (currentRef, currentStore) ->
                            reopenDungeonDeathPage(currentRef, currentStore, playerRef));
                    return null;
                });
    }

    /**
     * Handles the paid lower-floor restart action from the dungeon death page.
     *
     * @param ref the player's current entity reference
     * @param store the player's current entity store
     * @param playerRef the dead player's player reference
     */
    public void handleDungeonRespawnLowerFloor(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull PlayerRef playerRef
    ) {
        DungeonDeathContext context = resolveLiveDungeonDeathContext(ref, store, playerRef);
        if (context == null || !ensureDungeonDeathComponent(ref, store, playerRef)) {
            return;
        }
        if (!context.lowerFloorAvailable()) {
            playerRef.sendMessage(Message.raw("There is no lower dungeon floor to restart on.").color(COLOR_RED));
            reopenDungeonDeathPage(ref, store, playerRef);
            return;
        }

        UUID playerId = playerRef.getUuid();
        long cost = context.lowerFloorCost();
        int targetFloor = context.instance().floorLevel() - 1;
        if (!dungeonRespawnService.chargeGold(playerId, cost)) {
            sendInsufficientGold(playerRef, cost, context.balance());
            reopenDungeonDeathPage(ref, store, playerRef);
            return;
        }

        DeathComponent.respawn(store, ref)
                .thenCompose(unused -> restartDungeonInstanceAtFloor(context.instance().instanceId(), targetFloor))
                .thenCompose(newInstance -> runOnPlayerWorld(playerRef, (currentRef, currentStore) -> {
                    closeCustomPage(currentRef, currentStore);
                    playerRef.sendMessage(
                            Message.raw("Restarting on Floor " + newInstance.floorLevel() + " for ")
                                    .color(COLOR_GREEN)
                                    .insert(Message.raw(formatGold(cost)).color("#FFD700").bold(true))
                                    .insert(Message.raw(" gold. Balance: "
                                            + formatGold(goldService.getBalance(playerId))).color(COLOR_GREEN))
                    );
                }))
                .exceptionally(throwable -> {
                    dungeonRespawnService.refundGold(playerId, cost);
                    playerRef.sendMessage(
                            Message.raw("Lower-floor restart failed: " + describeFailure(throwable)).color(COLOR_RED)
                    );
                    runOnPlayerWorld(playerRef, (currentRef, currentStore) -> {
                        if (currentStore.getComponent(currentRef, DeathComponent.getComponentType()) != null) {
                            reopenDungeonDeathPage(currentRef, currentStore, playerRef);
                        } else {
                            closeCustomPage(currentRef, currentStore);
                        }
                    });
                    return null;
                });
    }

    /**
     * Handles the free village retreat action from the dungeon death page.
     *
        * <p>The village option ends the active dungeon through the same force-end lifecycle as
        * {@code /dungeon end} after the player's respawn teleport has settled.
     *
     * @param ref the player's current entity reference
     * @param store the player's current entity store
     * @param playerRef the dead player's player reference
     */
    public void handleDungeonReturnVillage(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull PlayerRef playerRef
    ) {
        if (!ensureDungeonDeathComponent(ref, store, playerRef)) {
            routeToSharedWorld(ref, store, playerRef, Message.raw("Entering village.").color(COLOR_GREEN));
            return;
        }

        DungeonDeathContext context = resolveDungeonDeathContext(ref, store, playerRef);
        if (context == null) {
            DeathComponent.respawn(store, ref)
                    .thenCompose(unused -> waitForRespawnTeleportToSettle(playerRef))
                    .thenCompose(unused -> runOnPlayerWorld(playerRef, (currentRef, currentStore) ->
                            routeToSharedWorld(
                                    currentRef,
                                    currentStore,
                                    playerRef,
                                    Message.raw("Entering village.").color(COLOR_GREEN)
                            )))
                    .exceptionally(throwable -> {
                        playerRef.sendMessage(
                                Message.raw("Village return failed: " + describeFailure(throwable)).color(COLOR_RED)
                        );
                        runOnPlayerWorld(playerRef, (currentRef, currentStore) ->
                                reopenDungeonDeathPage(currentRef, currentStore, playerRef));
                        return null;
                    });
            return;
        }

        DeathComponent.respawn(store, ref)
                .thenCompose(unused -> waitForRespawnTeleportToSettle(playerRef))
                .thenCompose(unused -> runOnPlayerWorld(playerRef, (currentRef, currentStore) ->
                        restoreBuiltInControls(currentRef, currentStore, playerRef)))
                .thenCompose(unused -> forceEndDungeonInstance(context.instance().instanceId()))
                .thenCompose(unused -> runOnPlayerWorld(playerRef, (currentRef, currentStore) ->
                        playerRef.sendMessage(Message.raw("Dungeon ended. Entering village.").color(COLOR_GREEN))))
                .exceptionally(throwable -> {
                    playerRef.sendMessage(
                            Message.raw("Dungeon end failed: " + describeFailure(throwable)).color(COLOR_RED)
                    );
                    runOnPlayerWorld(playerRef, (currentRef, currentStore) ->
                            reopenDungeonDeathPage(currentRef, currentStore, playerRef));
                    return null;
                });
    }

    private void startDungeonInstanceForPortal(@Nonnull PlayerRef playerRef) {
        UUID playerId = playerRef.getUuid();
        dungeonInstanceService.createInstanceForPlayer(playerId, DUNGEON_START_FLOOR)
                .thenAccept(instance -> sendDungeonInstanceCreated(playerRef, instance))
                .exceptionally(throwable -> {
                    sendDungeonInstanceStartFailure(playerRef, throwable);
                    return null;
                });
    }

    private void sendDungeonInstanceCreated(@Nonnull PlayerRef playerRef, @Nonnull DungeonInstance instance) {
        playerRef.sendMessage(
                Message.raw("Dungeon instance created: ").color(COLOR_GREEN)
                        .insert(Message.raw(truncateId(instance.instanceId())).color(COLOR_AQUA).monospace(true))
        );
    }

    private void sendDungeonInstanceStartFailure(@Nonnull PlayerRef playerRef, @Nonnull Throwable throwable) {
        Throwable cause = unwrapCompletionException(throwable);
        if (cause instanceof DungeonInstanceService.RosterValidationException rosterValidationException) {
            StringBuilder names = new StringBuilder();
            Universe universe = Universe.get();
            for (UUID blockedPlayerId : rosterValidationException.getBlockedPlayers()) {
                if (!names.isEmpty()) {
                    names.append(", ");
                }
                PlayerRef blockedPlayerRef = universe != null ? universe.getPlayer(blockedPlayerId) : null;
                names.append(blockedPlayerRef != null ? blockedPlayerRef.getUsername() : blockedPlayerId.toString());
            }
            playerRef.sendMessage(
                    Message.raw("Cannot start: players already in a dungeon: ").color(COLOR_RED)
                            .insert(Message.raw(names.toString()).color(COLOR_AQUA))
            );
            return;
        }

        playerRef.sendMessage(
                Message.raw("Failed to create instance: " + describeFailure(throwable)).color(COLOR_RED)
        );
    }

    @Nonnull
    private static String describeFailure(@Nonnull Throwable throwable) {
        Throwable cause = unwrapCompletionException(throwable);
        return cause.getMessage() != null ? cause.getMessage() : cause.toString();
    }

    @Nonnull
    private static Throwable unwrapCompletionException(@Nonnull Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @Nonnull
    private static String truncateId(@Nonnull String id) {
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    @Nonnull
    private static String formatGold(long amount) {
        return Long.toString(amount);
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
        restoreBuiltInControls(ref, store, playerRef);

        store.addComponent(
                ref,
                Teleport.getComponentType(),
                Teleport.createForPlayer(sharedWorld, resolveSpawnTransform(sharedWorld, ref, store))
        );
        playerRef.sendMessage(statusMessage);
    }

    private void restoreBuiltInControls(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull PlayerRef playerRef
    ) {
        UUID playerId = playerRef.getUuid();
        clickToMoveManager.disableWithCameraReset(playerId, store, ref, playerRef);
        World world = store.getExternalData().getWorld();
        if (world != null) {
            blockOcclusionManager.disable(playerId, world);
        } else {
            blockOcclusionManager.disable(playerId);
        }
    }

    @Nullable
    private DungeonDeathContext resolveLiveDungeonDeathContext(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull PlayerRef playerRef
    ) {
        World currentWorld = store.getExternalData().getWorld();
        DeathComponent deathComponent = store.getComponent(ref, DeathComponent.getComponentType());
        DungeonDeathContext context = dungeonRespawnService.resolveContext(
                playerRef.getUuid(),
                currentWorld != null ? currentWorld.getName() : null,
                deathComponent != null ? deathComponent.getDeathMessage() : null
        ).orElse(null);
        if (context == null) {
            playerRef.sendMessage(
                    Message.raw("Your dungeon death state is no longer available. Returning to village.")
                            .color(COLOR_RED)
            );
            handleDungeonReturnVillage(ref, store, playerRef);
        }
        return context;
    }

    private boolean ensureDungeonDeathComponent(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull PlayerRef playerRef
    ) {
        if (store.getComponent(ref, DeathComponent.getComponentType()) != null) {
            return true;
        }
        closeCustomPage(ref, store);
        playerRef.sendMessage(Message.raw("You have already respawned.").color(COLOR_RED));
        return false;
    }

    private void reopenDungeonDeathPage(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull PlayerRef playerRef
    ) {
        World currentWorld = store.getExternalData().getWorld();
        DeathComponent deathComponent = store.getComponent(ref, DeathComponent.getComponentType());
        if (deathComponent == null) {
            closeCustomPage(ref, store);
            return;
        }

        dungeonRespawnService.resolveContext(
                playerRef.getUuid(),
                currentWorld != null ? currentWorld.getName() : null,
                deathComponent.getDeathMessage()
        ).ifPresent(context -> {
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player != null) {
                player.getPageManager().openCustomPage(ref, store, new DungeonDeathPage(playerRef, context));
            }
        });
    }

    @Nullable
    private DungeonDeathContext resolveDungeonDeathContext(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull PlayerRef playerRef
    ) {
        World currentWorld = store.getExternalData().getWorld();
        DeathComponent deathComponent = store.getComponent(ref, DeathComponent.getComponentType());
        return dungeonRespawnService.resolveContext(
                playerRef.getUuid(),
                currentWorld != null ? currentWorld.getName() : null,
                deathComponent != null ? deathComponent.getDeathMessage() : null
        ).orElse(null);
    }

    @Nonnull
    private CompletableFuture<DungeonInstance> restartDungeonInstanceAtFloor(
            @Nonnull String instanceId,
            int floorLevel
    ) {
        try {
            return dungeonRespawnService.restartInstanceAtFloor(instanceId, floorLevel);
        } catch (SQLException | IllegalArgumentException | IllegalStateException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Nonnull
    private CompletableFuture<Void> forceEndDungeonInstance(@Nonnull String instanceId) {
        try {
            return dungeonInstanceService.forceEndInstance(instanceId);
        } catch (SQLException | IllegalArgumentException | IllegalStateException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Nonnull
    private CompletableFuture<Void> waitForRespawnTeleportToSettle(@Nonnull PlayerRef playerRef) {
        return waitForRespawnTeleportToSettle(playerRef, 0);
    }

    @Nonnull
    private CompletableFuture<Void> waitForRespawnTeleportToSettle(
            @Nonnull PlayerRef playerRef,
            int attempt
    ) {
        return isRespawnTeleportSettled(playerRef).thenCompose(settled -> {
            if (settled) {
                return CompletableFuture.completedFuture(null);
            }
            if (attempt >= RESPAWN_SETTLE_MAX_ATTEMPTS) {
                return CompletableFuture.failedFuture(new IllegalStateException("Respawn teleport did not settle"));
            }
            return delayRespawnSettleCheck()
                    .thenCompose(unused -> waitForRespawnTeleportToSettle(playerRef, attempt + 1));
        });
    }

    @Nonnull
    private CompletableFuture<Void> delayRespawnSettleCheck() {
        return CompletableFuture.runAsync(
                () -> {
                },
                CompletableFuture.delayedExecutor(RESPAWN_SETTLE_RETRY_DELAY_MS, TimeUnit.MILLISECONDS)
        );
    }

    @Nonnull
    private CompletableFuture<Boolean> isRespawnTeleportSettled(@Nonnull PlayerRef playerRef) {
        Ref<EntityStore> currentRef = playerRef.getReference();
        if (currentRef == null || !currentRef.isValid()) {
            return CompletableFuture.completedFuture(true);
        }
        Store<EntityStore> currentStore = currentRef.getStore();
        World currentWorld = currentStore.getExternalData().getWorld();
        if (currentWorld == null) {
            return CompletableFuture.completedFuture(isRespawnTeleportSettled(currentRef, currentStore));
        }
        return CompletableFuture.supplyAsync(() -> {
            if (!currentRef.isValid()) {
                return true;
            }
            return isRespawnTeleportSettled(currentRef, currentStore);
        }, currentWorld);
    }

    private boolean isRespawnTeleportSettled(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store
    ) {
        return store.getComponent(ref, DeathComponent.getComponentType()) == null
                && store.getComponent(ref, Teleport.getComponentType()) == null
                && store.getComponent(ref, PendingTeleport.getComponentType()) == null;
    }

    @Nonnull
    private CompletableFuture<Void> runOnPlayerWorld(
            @Nonnull PlayerRef playerRef,
            @Nonnull BiConsumer<Ref<EntityStore>, Store<EntityStore>> action
    ) {
        Ref<EntityStore> currentRef = playerRef.getReference();
        if (currentRef == null || !currentRef.isValid()) {
            return CompletableFuture.completedFuture(null);
        }
        Store<EntityStore> currentStore = currentRef.getStore();
        World currentWorld = currentStore.getExternalData().getWorld();
        if (currentWorld == null) {
            action.accept(currentRef, currentStore);
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            if (currentRef.isValid()) {
                action.accept(currentRef, currentStore);
            }
        }, currentWorld);
    }

    private void sendInsufficientGold(@Nonnull PlayerRef playerRef, long cost, long balance) {
        playerRef.sendMessage(
                Message.raw("Not enough gold. Need ").color(COLOR_RED)
                        .insert(Message.raw(formatGold(cost)).color("#FFD700").bold(true))
                        .insert(Message.raw(", have " + formatGold(balance) + ".").color(COLOR_RED))
        );
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
    private static Transform resolveSpawnTransform(
            @Nonnull World world,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store
    ) {
        return world.getWorldConfig().getSpawnProvider().getSpawnPoint(ref, store);
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
