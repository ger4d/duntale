package com.duntale.zsquad.camera;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Set;
import java.util.UUID;

/**
 * ECS ticking system that processes click-to-move movement updates at the native
 * server tick rate (30 TPS), synchronized with the world thread.
 *
 * <p>Runs <b>after</b> {@link PlayerSystems.ProcessPlayerInput} so that:
 * <ol>
 *   <li>Our packet filter's modified body/look orientation has been applied to
 *       the server-side {@link TransformComponent}.</li>
 *   <li>Our rotation sync ({@code ClientTeleport}) is sent in the same tick's
 *       outgoing packet batch, reaching the client with minimal delay.</li>
 * </ol>
 *
 * <p>Iterates over all entities with {@link PlayerRef} and {@link TransformComponent}.
 * For each player with an active click-to-move target, delegates to
 * {@link ClickToMoveManager#updateMovement} for velocity, animation, and rotation sync.</p>
 */
public class ClickToMoveTickSystem extends EntityTickingSystem<EntityStore> {

    private static final Set<Dependency<EntityStore>> DEPENDENCIES = Set.of(
            new SystemDependency<>(Order.AFTER, PlayerSystems.ProcessPlayerInput.class)
    );

    @Nonnull
    private final Query<EntityStore> query = Query.and(
            PlayerRef.getComponentType(),
            TransformComponent.getComponentType()
    );

    @Nonnull
    private final ClickToMoveManager manager;

    /**
     * Creates a new tick system for click-to-move.
     *
     * @param manager the click-to-move manager holding state and utility methods
     */
    public ClickToMoveTickSystem(@Nonnull ClickToMoveManager manager) {
        this.manager = manager;
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return this.query;
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return DEPENDENCIES;
    }

    @Override
    public void tick(float dt,
                     int index,
                     @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        PlayerRef playerRef = archetypeChunk.getComponent(index, PlayerRef.getComponentType());
        if (playerRef == null) return;

        UUID uuid = playerRef.getUuid();
        if (!manager.needsProcessing(uuid)) return;

        TransformComponent transform = archetypeChunk.getComponent(index, TransformComponent.getComponentType());
        if (transform == null) return;

        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        manager.tickMovement(ref, store, playerRef, uuid);
    }
}
