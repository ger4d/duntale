package com.duntale.zsquad.companion;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * Reconnects a player's companion flock when the player respawns after death.
 *
 * <p>Listens for {@link DeathComponent} removal, which signals respawn completion.
 * When the player's {@link DeathComponent} is removed, the companion's flock binding
 * is rebuilt via {@link CompanionService#reconnect}.
 *
 * @since 1.4.0
 */
public class CompanionRespawnSystem extends DeathSystems.OnDeathSystem {

    private final CompanionService companionService;

    /**
     * Creates a new companion respawn system.
     *
     * @param companionService the companion service
     */
    public CompanionRespawnSystem(@Nonnull CompanionService companionService) {
        this.companionService = companionService;
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return PlayerRef.getComponentType();
    }

    @Override
    public void onComponentAdded(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull DeathComponent component,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        // No-op on death — reconnect happens on respawn (component removed)
    }

    @Override
    public void onComponentRemoved(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull DeathComponent component,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        PlayerRef playerRefComp = store.getComponent(ref, PlayerRef.getComponentType());
        if (playerRefComp == null) return;

        UUID playerId = playerRefComp.getUuid();
        commandBuffer.run(s -> companionService.reconnect(s, ref, playerId));
    }
}
