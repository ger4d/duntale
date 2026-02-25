package com.duntale.zsquad;

import com.duntale.zsquad.camera.BlockOcclusionManager;
import com.duntale.zsquad.camera.ClickToMoveManager;
import com.duntale.zsquad.camera.ClickToMoveTickSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import javax.annotation.Nonnull;

public class ZSquadPlugin extends JavaPlugin {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static ZSquadPlugin instance;

    private ClickToMoveManager clickToMoveManager;
    private BlockOcclusionManager blockOcclusionManager;

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

    @Override
    protected void setup() {
        LOGGER.at(java.util.logging.Level.INFO).log("ZSquad Plugin Setting Up...");

        // Initialize managers
        this.clickToMoveManager = new ClickToMoveManager();
        this.blockOcclusionManager = new BlockOcclusionManager();

        // Register ECS ticking system for click-to-move (runs at native 30 TPS)
        this.getEntityStoreRegistry().registerSystem(new ClickToMoveTickSystem(this.clickToMoveManager));

        // Command registration
        this.getCommandRegistry().registerCommand(new com.duntale.zsquad.command.SpawnCommand());
        this.getCommandRegistry().registerCommand(new com.duntale.zsquad.command.CameraCommand());
        this.getCommandRegistry().registerCommand(new com.duntale.zsquad.command.WeaponCommand());
    }

    @Override
    protected void start() {
        LOGGER.at(java.util.logging.Level.INFO).log("ZSquad Plugin Started!");
    }
}
