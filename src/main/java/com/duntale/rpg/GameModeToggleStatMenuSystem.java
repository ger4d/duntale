package com.duntale.rpg;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.ChangeGameModeEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Repurposes the in-game gamemode toggle (the <b>O</b> key) for players.
 *
 * <p>The client's gamemode toggle key dispatches a {@link ChangeGameModeEvent} through
 * {@code Player.setGameMode(...)}, which honours {@link ChangeGameModeEvent#isCancelled()}.
 * This system cancels that event for any player entity and instead opens the RPG
 * {@link StatAssignmentPage}, so pressing O never changes the gamemode and always shows the
 * stat-assignment menu &mdash; even when the player has zero unassigned points.
 *
 * @see StatAssignmentPage
 * @see ChangeGameModeEvent
 */
public class GameModeToggleStatMenuSystem extends EntityEventSystem<EntityStore, ChangeGameModeEvent> {

    @Nonnull
    private final RpgService rpgService;

    /**
     * Creates the gamemode-toggle interceptor.
     *
     * @param rpgService the RPG service used to back the stat-assignment page
     */
    public GameModeToggleStatMenuSystem(@Nonnull RpgService rpgService) {
        super(ChangeGameModeEvent.class);
        this.rpgService = rpgService;
    }

    @Override
    public void handle(int index,
                       @Nonnull ArchetypeChunk<EntityStore> chunk,
                       @Nonnull Store<EntityStore> store,
                       @Nonnull CommandBuffer<EntityStore> cmd,
                       @Nonnull ChangeGameModeEvent event) {
        Ref<EntityStore> ref = chunk.getReferenceTo(index);

        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }

        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null) {
            return;
        }

        event.setCancelled(true);

        StatAssignmentPage page = new StatAssignmentPage(playerRef, rpgService);
        player.getPageManager().openCustomPage(ref, store, page);
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }
}
