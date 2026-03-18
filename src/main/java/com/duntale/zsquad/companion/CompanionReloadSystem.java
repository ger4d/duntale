package com.duntale.zsquad.companion;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * Reconnects a companion to its owner when the companion entity is loaded into the store
 * (e.g., chunk reload after unload, or server restart).
 *
 * <p>Listens for entities with {@link CompanionComponent} being added to the store via
 * {@link AddReason#LOAD}. When the owner is online, calls
 * {@link CompanionService#reconnectFromEntity} to rebuild the in-memory tracking entry
 * and re-create the flock binding.
 *
 * @since 1.4.0
 */
public class CompanionReloadSystem extends RefSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final ComponentType<EntityStore, CompanionComponent> companionComponentType;
    private final CompanionService companionService;

    /**
     * Creates a new companion reload system.
     *
     * @param companionComponentType the registered companion component type
     * @param companionService       the companion service
     */
    public CompanionReloadSystem(
            @Nonnull ComponentType<EntityStore, CompanionComponent> companionComponentType,
            @Nonnull CompanionService companionService
    ) {
        this.companionComponentType = companionComponentType;
        this.companionService = companionService;
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return companionComponentType;
    }

    @Override
    public void onEntityAdded(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull AddReason reason,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        if (reason != AddReason.LOAD) return;

        LOGGER.atInfo().log("Companion entity %s loaded, attempting to reconnect", ref);

        CompanionComponent component = store.getComponent(ref, companionComponentType);
        if (component == null) return;

        UUID ownerUuid = component.getOwnerUuid();

        PlayerRef playerRefComp = Universe.get().getPlayer(ownerUuid);
        if (playerRefComp == null) return;

        Ref<EntityStore> playerRef = playerRefComp.getReference();
        if (playerRef == null || !playerRef.isValid()) return;

        commandBuffer.run(s ->
                companionService.reconnectFromEntity(s, ref, playerRef, component));
    }

    @Override
    public void onEntityRemove(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull RemoveReason reason,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        // No-op — removal is handled by CompanionService.dismiss()
    }
}
