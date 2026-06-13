package com.duntale.items;

import com.duntale.progression.GearAttributeService;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.InventoryChangeEvent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Recomputes a player's equipped-gear attribute bonus when their armor or hotbar contents change.
 *
 * <p>Reacts to {@link InventoryChangeEvent} on the armor and hotbar containers — equipping or
 * unequipping armor, and any change to the items carried in the hotbar (the held weapon's
 * <em>contents</em> changing, as opposed to a slot swap, which is handled by
 * {@link GearAttributeActiveSlotSystem}). Changes to storage and other containers are ignored.
 */
public class GearAttributeInventorySystem extends EntityEventSystem<EntityStore, InventoryChangeEvent> {

    private final GearAttributeService gearAttributeService;

    public GearAttributeInventorySystem(@Nonnull GearAttributeService gearAttributeService) {
        super(InventoryChangeEvent.class);
        this.gearAttributeService = gearAttributeService;
    }

    @Override
    public void handle(int index,
                       @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                       @Nonnull Store<EntityStore> store,
                       @Nonnull CommandBuffer<EntityStore> commandBuffer,
                       @Nonnull InventoryChangeEvent event) {
        if (!affectsEquippedGear(event)) {
            return;
        }
        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        UUIDComponent uuidComponent = store.getComponent(ref, UUIDComponent.getComponentType());
        if (uuidComponent == null) {
            return;
        }
        UUID playerId = uuidComponent.getUuid();
        gearAttributeService.recompute(playerId, ref, store);
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }

    private static boolean affectsEquippedGear(@Nonnull InventoryChangeEvent event) {
        return event.getComponentType() == InventoryComponent.Armor.getComponentType()
                || event.getComponentType() == InventoryComponent.Hotbar.getComponentType();
    }
}
