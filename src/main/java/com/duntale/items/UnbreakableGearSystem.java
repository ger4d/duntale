package com.duntale.items;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.InventoryChangeEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.ListTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.MoveTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.SlotTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.Transaction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Safety net that stamps any breakable gear entering a player's inventory as
 * unbreakable.
 *
 * <p>Duntale item-generation chokepoints (loot, merchant purchases) already
 * convert gear via {@link UnbreakableItems#makeUnbreakable(ItemStack)}. This
 * system covers everything else — pre-existing gear on legacy saves and gear
 * handed out directly by the engine or third-party mods — by reacting to
 * {@link InventoryChangeEvent} on player containers, mirroring
 * {@code InventoryGoldConversionSystem}.
 *
 * <p>Churn is bounded: a slot is only replaced while its stack still has
 * {@code maxDurability > 0}, so each stack converts at most once and the
 * follow-up change event from the replacement is a no-op.
 */
public class UnbreakableGearSystem extends EntityEventSystem<EntityStore, InventoryChangeEvent> {

    public UnbreakableGearSystem() {
        super(InventoryChangeEvent.class);
    }

    @Override
    public void handle(int index,
                       @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                       @Nonnull Store<EntityStore> store,
                       @Nonnull CommandBuffer<EntityStore> commandBuffer,
                       @Nonnull InventoryChangeEvent event) {
        ItemContainer container = event.getItemContainer();

        Set<Short> modifiedSlots = new LinkedHashSet<>();
        collectModifiedSlots(event.getTransaction(), modifiedSlots);

        short capacity = container.getCapacity();
        for (short slot : modifiedSlots) {
            if (slot < 0 || slot >= capacity) {
                continue;
            }

            ItemStack current = container.getItemStack(slot);
            if (ItemStack.isEmpty(current)) {
                continue;
            }

            ItemStack unbreakable = UnbreakableItems.makeUnbreakable(current);
            if (unbreakable != current) {
                // Identity change => the stack was breakable gear; convert it in place.
                container.replaceItemStackInSlot(slot, current, unbreakable);
            }
        }
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }

    /**
     * Recursively walks a transaction tree, collecting the slot indices (relative
     * to the changed container) whose post-change stack is non-empty.
     */
    private static void collectModifiedSlots(@Nullable Transaction transaction,
                                             @Nonnull Set<Short> slots) {
        if (transaction == null || !transaction.succeeded()) {
            return;
        }

        if (transaction instanceof ListTransaction<?> listTransaction) {
            for (Transaction child : listTransaction.getList()) {
                collectModifiedSlots(child, slots);
            }
            return;
        }

        if (transaction instanceof MoveTransaction<?> moveTransaction) {
            collectModifiedSlots(moveTransaction.getAddTransaction(), slots);
            return;
        }

        if (transaction instanceof ItemStackTransaction itemStackTransaction) {
            for (ItemStackSlotTransaction slotTransaction : itemStackTransaction.getSlotTransactions()) {
                recordSlot(slotTransaction, slots);
            }
            return;
        }

        if (transaction instanceof SlotTransaction slotTransaction) {
            recordSlot(slotTransaction, slots);
        }
    }

    private static void recordSlot(@Nonnull SlotTransaction slotTransaction, @Nonnull Set<Short> slots) {
        if (slotTransaction.succeeded() && !ItemStack.isEmpty(slotTransaction.getSlotAfter())) {
            slots.add(slotTransaction.getSlot());
        }
    }
}
