package com.duntale.zsquad;

import com.duntale.zsquad.camera.BlockOcclusionManager;
import com.duntale.zsquad.camera.ClickToMoveManager;
import com.duntale.zsquad.camera.ClickToMoveTickSystem;
import com.duntale.zsquad.command.DGiveCommand;
import com.duntale.zsquad.command.DListCommand;
import com.duntale.zsquad.command.DSpawnCommand;
import com.duntale.zsquad.progression.CombatScalingSystem;
import com.duntale.zsquad.progression.LeveledNpcSpawner;
import com.duntale.zsquad.progression.NpcLevelRegistry;
import com.duntale.zsquad.progression.ScalingDataCache;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import javax.annotation.Nonnull;

public class ZSquadPlugin extends JavaPlugin {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static ZSquadPlugin instance;

    private ClickToMoveManager clickToMoveManager;
    private BlockOcclusionManager blockOcclusionManager;

    // Progression system
    private ScalingDataCache scalingDataCache;
    private NpcLevelRegistry npcLevelRegistry;
    private LeveledNpcSpawner leveledNpcSpawner;

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
     * Returns the scaling data cache.
     *
     * @return the scaling data cache
     */
    @Nonnull
    public ScalingDataCache getScalingDataCache() {
        return scalingDataCache;
    }

    /**
     * Returns the NPC level registry.
     *
     * @return the NPC level registry
     */
    @Nonnull
    public NpcLevelRegistry getNpcLevelRegistry() {
        return npcLevelRegistry;
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

    @Override
    protected void setup() {
        LOGGER.at(java.util.logging.Level.INFO).log("ZSquad Plugin Setting Up...");

        // Initialize managers
        this.clickToMoveManager = new ClickToMoveManager();
        this.blockOcclusionManager = new BlockOcclusionManager();

        LOGGER.atInfo().log("Data directory: %s", getDataDirectory().toAbsolutePath());

        // ── Progression System ───────────────────────────────────────
        this.scalingDataCache = new ScalingDataCache(getDataDirectory());
        this.npcLevelRegistry = new NpcLevelRegistry();
        this.leveledNpcSpawner = new LeveledNpcSpawner(scalingDataCache, npcLevelRegistry);

        // Register ECS systems
        this.getEntityStoreRegistry().registerSystem(new ClickToMoveTickSystem(this.clickToMoveManager));
        this.getEntityStoreRegistry().registerSystem(new CombatScalingSystem(npcLevelRegistry, scalingDataCache));

        // Command registration
        this.getCommandRegistry().registerCommand(new com.duntale.zsquad.command.SpawnCommand());
        this.getCommandRegistry().registerCommand(new com.duntale.zsquad.command.CameraCommand());
        this.getCommandRegistry().registerCommand(new com.duntale.zsquad.command.WeaponCommand());
        this.getCommandRegistry().registerCommand(new DSpawnCommand(leveledNpcSpawner, scalingDataCache));
        this.getCommandRegistry().registerCommand(new DListCommand(scalingDataCache));
        this.getCommandRegistry().registerCommand(new DGiveCommand(scalingDataCache));

        // ── DynamicTooltipsLib integration (optional dependency) ──
        registerTooltipProvider();
    }

    @Override
    protected void start() {
        LOGGER.at(java.util.logging.Level.INFO).log("ZSquad Plugin Started!");
    }

    @Override
    protected void shutdown() {
        if (scalingDataCache != null) {
            scalingDataCache.shutdown();
        }
        if (npcLevelRegistry != null) {
            npcLevelRegistry.clear();
        }
    }

    /**
     * Registers the gear scaling tooltip provider with DynamicTooltipsLib if available.
     * Guarded so that the plugin works even without the optional dependency.
     */
    private void registerTooltipProvider() {
        try {
            var api = org.herolias.tooltips.api.DynamicTooltipsApiProvider.get();
            if (api != null) {
                api.registerProvider(
                        new com.duntale.zsquad.progression.GearScalingTooltipProvider(scalingDataCache));
                LOGGER.atInfo().log("Registered GearScalingTooltipProvider with DynamicTooltipsLib");
            } else {
                LOGGER.atInfo().log("DynamicTooltipsLib not available — tooltip overrides disabled");
            }
        } catch (NoClassDefFoundError e) {
            LOGGER.atInfo().log("DynamicTooltipsLib not loaded — tooltip overrides disabled");
        }
    }
}
