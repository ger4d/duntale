package com.duntale.zsquad;

import com.duntale.zsquad.camera.BlockOcclusionManager;
import com.duntale.zsquad.camera.ClickToMoveKnockbackSystem;
import com.duntale.zsquad.camera.ClickToMoveManager;
import com.duntale.zsquad.camera.ClickToMoveTickSystem;
import com.duntale.zsquad.command.DGiveCommand;
import com.duntale.zsquad.command.DListCommand;
import com.duntale.zsquad.command.DSpawnCommand;
import com.duntale.zsquad.loot.LootEntry;
import com.duntale.zsquad.loot.LootEntry.GearType;
import com.duntale.zsquad.loot.LootTable;
import com.duntale.zsquad.loot.LootTableRegistry;
import com.duntale.zsquad.loot.NpcLootSystem;
import com.duntale.zsquad.progression.CombatScalingSystem;
import com.duntale.zsquad.progression.LeveledNpcSpawner;
import com.duntale.zsquad.progression.NpcLevelRegistry;
import com.duntale.zsquad.progression.ScalingDataCache;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.List;

public class ZSquadPlugin extends JavaPlugin {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static ZSquadPlugin instance;

    private ClickToMoveManager clickToMoveManager;
    private BlockOcclusionManager blockOcclusionManager;

    // Progression system
    private ScalingDataCache scalingDataCache;
    private NpcLevelRegistry npcLevelRegistry;
    private LeveledNpcSpawner leveledNpcSpawner;

    // Loot system
    private LootTableRegistry lootTableRegistry;

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

    /**
     * Returns the loot table registry.
     *
     * @return the loot table registry
     */
    @Nonnull
    public LootTableRegistry getLootTableRegistry() {
        return lootTableRegistry;
    }

    @Override
    protected void setup() {
        LOGGER.atInfo().log("ZSquad Plugin Setting Up...");

        // Initialize managers
        this.clickToMoveManager = new ClickToMoveManager();
        this.blockOcclusionManager = new BlockOcclusionManager();

        LOGGER.atInfo().log("Data directory: %s", getDataDirectory().toAbsolutePath());

        // ── Progression System ───────────────────────────────────────
        this.scalingDataCache = new ScalingDataCache(getDataDirectory());
        this.npcLevelRegistry = new NpcLevelRegistry();
        this.leveledNpcSpawner = new LeveledNpcSpawner(scalingDataCache, npcLevelRegistry);

        // ── Loot System ──────────────────────────────────────────────
        this.lootTableRegistry = new LootTableRegistry();
        registerLootTables();

        // Register ECS systems
        this.getEntityStoreRegistry().registerSystem(new ClickToMoveTickSystem(this.clickToMoveManager));
        this.getEntityStoreRegistry().registerSystem(new CombatScalingSystem(npcLevelRegistry, scalingDataCache));
        this.getEntityStoreRegistry().registerSystem(new ClickToMoveKnockbackSystem(this.clickToMoveManager));
        this.getEntityStoreRegistry().registerSystem(new NpcLootSystem(lootTableRegistry, npcLevelRegistry));

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
        if (scalingDataCache != null) {
            scalingDataCache.shutdown();
        }
        if (npcLevelRegistry != null) {
            npcLevelRegistry.clear();
        }
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
                new LootEntry.Leveled("Armor_Wood_Chest", GearType.ARMOR, 5, 8, 1.0)
        ), 1, 0.35));

        lootTableRegistry.register("Trork_Brawler", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Club_Crude", GearType.WEAPON, 5, 10, 3.0),
                new LootEntry.Leveled("Weapon_Mace_Crude", GearType.WEAPON, 7, 10, 2.0),
                new LootEntry.Leveled("Armor_Wood_Hands", GearType.ARMOR, 5, 8, 1.5),
                new LootEntry.Leveled("Armor_Wood_Legs", GearType.ARMOR, 5, 8, 1.5)
        ), 1, 0.35));

        lootTableRegistry.register("Trork_Hunter", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Spear_Crude", GearType.WEAPON, 5, 10, 3.0),
                new LootEntry.Leveled("Weapon_Daggers_Crude", GearType.WEAPON, 6, 10, 2.0),
                new LootEntry.Leveled("Armor_Leather_Soft_Chest", GearType.ARMOR, 8, 12, 1.5, 8, null)
        ), 1, 0.35));

        lootTableRegistry.register("Trork_Guard", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Longsword_Crude", GearType.WEAPON, 5, 10, 3.0),
                new LootEntry.Leveled("Weapon_Axe_Copper", GearType.WEAPON, 10, 12, 1.5, 10, null),
                new LootEntry.Leveled("Armor_Copper_Chest", GearType.ARMOR, 10, 12, 1.0, 10, null),
                new LootEntry.Leveled("Armor_Copper_Head", GearType.ARMOR, 10, 12, 1.0, 10, null)
        ), 1, 0.40));

        // ── Skeleton mobs (Lv.15–30 zone) ───────────────────────────
        lootTableRegistry.register("Skeleton_Soldier", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Sword_Iron", GearType.WEAPON, 18, 22, 3.0),
                new LootEntry.Leveled("Weapon_Longsword_Iron", GearType.WEAPON, 18, 22, 2.0),
                new LootEntry.Leveled("Armor_Iron_Chest", GearType.ARMOR, 18, 22, 1.5),
                new LootEntry.Leveled("Armor_Iron_Head", GearType.ARMOR, 18, 22, 1.5)
        ), 1, 0.40));

        lootTableRegistry.register("Skeleton_Fighter", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Axe_Iron", GearType.WEAPON, 18, 22, 3.0),
                new LootEntry.Leveled("Weapon_Mace_Iron", GearType.WEAPON, 18, 22, 2.0),
                new LootEntry.Leveled("Armor_Iron_Hands", GearType.ARMOR, 18, 22, 1.5),
                new LootEntry.Leveled("Armor_Iron_Legs", GearType.ARMOR, 18, 22, 1.5)
        ), 1, 0.40));

        lootTableRegistry.register("Skeleton_Knight", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Sword_Bronze", GearType.WEAPON, 23, 27, 2.5),
                new LootEntry.Leveled("Weapon_Longsword_Praetorian", GearType.WEAPON, 23, 27, 1.5),
                new LootEntry.Leveled("Armor_Bronze_Chest", GearType.ARMOR, 23, 27, 1.5),
                new LootEntry.Leveled("Armor_Bronze_Head", GearType.ARMOR, 23, 27, 1.5),
                new LootEntry.Leveled("Armor_Bronze_Ornate_Chest", GearType.ARMOR, 26, 30, 0.5, 25, null)
        ), 1, 0.45));

        lootTableRegistry.register("Skeleton_Archer", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Spear_Iron", GearType.WEAPON, 18, 22, 3.0),
                new LootEntry.Leveled("Weapon_Daggers_Iron", GearType.WEAPON, 18, 22, 2.0),
                new LootEntry.Leveled("Armor_Leather_Light_Chest", GearType.ARMOR, 15, 20, 1.5),
                new LootEntry.Leveled("Armor_Leather_Light_Head", GearType.ARMOR, 15, 20, 1.5)
        ), 1, 0.40));

        // ── Goblin mobs (Lv.10–25 zone) ─────────────────────────────
        lootTableRegistry.register("Goblin_Scrapper", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Club_Copper", GearType.WEAPON, 10, 15, 3.0),
                new LootEntry.Leveled("Weapon_Sword_Scrap", GearType.WEAPON, 13, 17, 2.0, 12, null),
                new LootEntry.Leveled("Armor_Copper_Hands", GearType.ARMOR, 10, 15, 1.5)
        ), 1, 0.40));

        lootTableRegistry.register("Goblin_Scavenger", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Mace_Scrap", GearType.WEAPON, 13, 17, 3.0),
                new LootEntry.Leveled("Weapon_Club_Scrap", GearType.WEAPON, 13, 17, 2.0),
                new LootEntry.Leveled("Armor_Copper_Legs", GearType.ARMOR, 10, 15, 1.5)
        ), 1, 0.40));

        // ── Zombie mobs (Lv.25–35 zone) ─────────────────────────────
        lootTableRegistry.register("Zombie", new LootTable(List.of(
                new LootEntry.Leveled("Weapon_Sword_Bone", GearType.WEAPON, 23, 27, 2.0),
                new LootEntry.Leveled("Weapon_Axe_Bone", GearType.WEAPON, 23, 27, 2.0),
                new LootEntry.Leveled("Weapon_Sword_Doomed", GearType.WEAPON, 28, 32, 1.0, 28, null),
                new LootEntry.Leveled("Armor_Thorium_Chest", GearType.ARMOR, 28, 32, 0.8, 28, null)
        ), 1, 0.50));

        LOGGER.atInfo().log("Registered %d custom loot tables", lootTableRegistry.size());
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
