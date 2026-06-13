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
import com.hypixel.hytale.server.core.event.events.ecs.InventorySetActiveSlotEvent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Recomputes a player's equipped-gear attribute bonus when they swap the active hotbar slot.
 *
 * <p>The held weapon contributes its attributes only while it is the active hotbar item; selecting
 * a different slot swaps which weapon's attributes apply, so a recompute is triggered on each
 * hotbar active-slot change. Active-slot changes in non-hotbar sections (tools, utility) are
 * ignored.
 */
public class GearAttributeActiveSlotSystem extends EntityEventSystem<EntityStore, InventorySetActiveSlotEvent> {

    private final GearAttributeService gearAttributeService;

    public GearAttributeActiveSlotSystem(@Nonnull GearAttributeService gearAttributeService) {
        super(InventorySetActiveSlotEvent.class);
        this.gearAttributeService = gearAttributeService;
    }

    @Override
    public void handle(int index,
                       @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                       @Nonnull Store<EntityStore> store,
                       @Nonnull CommandBuffer<EntityStore> commandBuffer,
                       @Nonnull InventorySetActiveSlotEvent event) {
        if (event.getInventorySectionId() != InventoryComponent.HOTBAR_SECTION_ID) {
            return;
        }
        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        UUIDComponent uuidComponent = store.getComponent(ref, UUIDComponent.getComponentType());
        if (uuidComponent == null) {
            return;
        }
        gearAttributeService.recompute(uuidComponent.getUuid(), ref, store);
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }
}
