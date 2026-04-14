package com.duntale.zsquad.camera;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * Resets click-to-move and xray state while a player is dead, then restores the
 * necessary camera-side flags once respawn completes.
 */
public class PlayerDeathCleanupSystem extends DeathSystems.OnDeathSystem {

    @Nonnull
    private static final Query<EntityStore> QUERY = PlayerRef.getComponentType();

    @Nonnull
    private final ClickToMoveManager clickToMoveManager;

    @Nonnull
    private final BlockOcclusionManager blockOcclusionManager;

    /**
     * Creates a new player death cleanup system.
     *
     * @param clickToMoveManager the click-to-move manager to reset on death and respawn
     * @param blockOcclusionManager the block occlusion manager to pause during the death window
     */
    public PlayerDeathCleanupSystem(@Nonnull ClickToMoveManager clickToMoveManager,
                                    @Nonnull BlockOcclusionManager blockOcclusionManager) {
        this.clickToMoveManager = clickToMoveManager;
        this.blockOcclusionManager = blockOcclusionManager;
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return QUERY;
    }

    @Override
    public void onComponentAdded(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull DeathComponent component,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null) {
            return;
        }

        UUID uuid = playerRef.getUuid();
        World world = store.getExternalData().getWorld();

        clickToMoveManager.onPlayerDeath(uuid, store, ref);
        blockOcclusionManager.pauseForDeath(uuid, world);
    }

    @Override
    public void onComponentRemoved(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull DeathComponent component,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null) {
            return;
        }

        UUID uuid = playerRef.getUuid();

        clickToMoveManager.onPlayerRespawn(uuid, store, ref);
        blockOcclusionManager.resumeAfterRespawn(uuid);
    }
}