package com.duntale.zsquad.economy;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * ECS system that auto-picks up gold item entities and converts them to currency.
 *
 * <p>Matches entities with {@link ItemComponent}, {@link CurrencyDrop}, and
 * {@link TransformComponent}. For each matched entity, finds the nearest player
 * within {@link #PICKUP_RADIUS} and awards them the gold amount from the item stack.
 */
public class GoldPickupSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Maximum XZ distance (in blocks) at which a player can pick up gold. */
    private static final double PICKUP_RADIUS = 2.5;
    private static final double PICKUP_RADIUS_SQ = PICKUP_RADIUS * PICKUP_RADIUS;

    private static final String GOLD_COLOR = "#FFD700";

    @Nonnull
    private final Query<EntityStore> query = Query.and(
            ItemComponent.getComponentType(),
            CurrencyDrop.getComponentType(),
            TransformComponent.getComponentType()
    );

    private final GoldService goldService;

    /**
     * Creates a new gold pickup system.
     *
     * @param goldService the gold service for awarding currency
     */
    public GoldPickupSystem(@Nonnull GoldService goldService) {
        this.goldService = goldService;
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return this.query;
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return Collections.emptySet();
    }

    @Override
    public void tick(float dt,
                     int index,
                     @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        TransformComponent transform = archetypeChunk.getComponent(index, TransformComponent.getComponentType());
        if (transform == null) {
            return;
        }

        ItemComponent itemComponent = archetypeChunk.getComponent(index, ItemComponent.getComponentType());
        if (itemComponent == null || itemComponent.getItemStack() == null) {
            return;
        }

        int quantity = itemComponent.getItemStack().getQuantity();
        if (quantity <= 0) {
            return;
        }

        Vector3d itemPos = transform.getPosition();

        // Find the nearest player within pickup radius (XZ distance only)
        PlayerRef closestPlayer = findNearestPlayer(itemPos, store);
        if (closestPlayer == null) {
            return;
        }

        UUID playerId = closestPlayer.getUuid();
        goldService.addGold(playerId, quantity);

        long newBalance = goldService.getBalance(playerId);

        // Remove the gold entity
        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        commandBuffer.removeEntity(ref, RemoveReason.REMOVE);

        // Notify the player
        closestPlayer.sendMessage(
                Message.raw("+ " + quantity + " Gold")
                        .color(GOLD_COLOR)
                        .insert(Message.raw(" (Total: " + newBalance + ")").color("#AAAAAA"))
        );
    }

    /**
     * Finds the nearest online player within {@link #PICKUP_RADIUS} of the given position.
     *
     * @param itemPos the item entity position
     * @param store   the entity store for component lookups
     * @return the nearest player within range, or {@code null} if none found
     */
    @Nullable
    private PlayerRef findNearestPlayer(@Nonnull Vector3d itemPos, @Nonnull Store<EntityStore> store) {
        PlayerRef closest = null;
        double closestDistSq = PICKUP_RADIUS_SQ;

        for (PlayerRef playerRef : Universe.get().getPlayers()) {
            Ref<EntityStore> playerEntity = playerRef.getReference();
            if (playerEntity == null || !playerEntity.isValid()) {
                continue;
            }

            TransformComponent playerTransform = store.getComponent(playerEntity, TransformComponent.getComponentType());
            if (playerTransform == null) {
                continue;
            }

            Vector3d playerPos = playerTransform.getPosition();
            double dx = playerPos.x - itemPos.x;
            double dz = playerPos.z - itemPos.z;
            double distSq = dx * dx + dz * dz;

            if (distSq < closestDistSq) {
                closestDistSq = distSq;
                closest = playerRef;
            }
        }

        return closest;
    }
}
